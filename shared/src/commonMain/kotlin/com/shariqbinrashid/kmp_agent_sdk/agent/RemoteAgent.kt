package com.shariqbinrashid.kmp_agent_sdk.agent

import com.shariqbinrashid.kmp_agent_sdk.models.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

/**
 * Remote agent implementation for communicating with backend services
 */
class RemoteAgent(
    private val config: RemoteAgentConfig,
    private val agentConfig: AgentConfig
) {
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        
        install(Logging) {
            logger = Logger.DEFAULT
            level = if (agentConfig.telemetryOptIn) LogLevel.INFO else LogLevel.NONE
        }
        
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
            connectTimeoutMillis = 10.seconds.inWholeMilliseconds
        }
        
        // Add authentication if provided
        config.authToken?.let { token ->
            install(Auth) {
                bearer {
                    loadTokens {
                        BearerTokens(token, token)
                    }
                }
            }
        }
        
        // Add custom headers
        defaultRequest {
            config.headers.forEach { (key, value) ->
                header(key, value)
            }
        }
    }
    
    /**
     * Send a synchronous request to the agent
     */
    suspend fun sendSyncRequest(request: AgentRequest): Result<AgentResponse> {
        return try {
            val response = httpClient.post("${config.baseUrl}${config.planPath}") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            
            when (response.status) {
                HttpStatusCode.OK -> {
                    val responseBody = response.body<JsonObject>()
                    val agentResponse = parseAgentResponse(responseBody)
                    Result.success(agentResponse)
                }
                HttpStatusCode.Unauthorized -> {
                    Result.failure(AgentException(ErrorKind.AUTHENTICATION, "Unauthorized"))
                }
                HttpStatusCode.TooManyRequests -> {
                    Result.failure(AgentException(ErrorKind.RATE_LIMIT, "Rate limit exceeded"))
                }
                else -> {
//                    val errorBody = response.bodyAsText()
                    Result.failure(AgentException(ErrorKind.SERVER_ERROR, "Server error:"))
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            Result.failure(AgentException(ErrorKind.TIMEOUT, "Request timeout"))
        } catch (e: Exception) {
            Result.failure(AgentException(ErrorKind.NETWORK, "Network error: ${e.message}"))
        }
    }
    
    /**
     * Send a streaming request to the agent
     */
    fun sendStreamingRequest(request: AgentRequest): Flow<Result<SSEEvent>> = flow {
        try {
            val response = httpClient.post("${config.baseUrl}${config.streamPath}") {
                contentType(ContentType.Application.Json)
                setBody(request)
                header(HttpHeaders.Accept, "text/event-stream")
                header(HttpHeaders.CacheControl, "no-cache")
            }
            
            when (response.status) {
                HttpStatusCode.OK -> {
                    response.bodyAsChannel().parseSSEEvents()
                        .collect { event ->
                            emit(Result.success(event))
                        }
                }
                HttpStatusCode.Unauthorized -> {
                    emit(Result.failure(AgentException(ErrorKind.AUTHENTICATION, "Unauthorized")))
                }
                HttpStatusCode.TooManyRequests -> {
                    emit(Result.failure(AgentException(ErrorKind.RATE_LIMIT, "Rate limit exceeded")))
                }
                else -> {
                    val errorBody = response.bodyAsText()
                    emit(Result.failure(AgentException(ErrorKind.SERVER_ERROR, "Server error: $errorBody")))
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            emit(Result.failure(AgentException(ErrorKind.TIMEOUT, "Request timeout")))
        } catch (e: Exception) {
            emit(Result.failure(AgentException(ErrorKind.NETWORK, "Network error: ${e.message}")))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Close the HTTP client and clean up resources
     */
    fun close() {
        httpClient.close()
    }
    
    private fun parseAgentResponse(json: JsonObject): AgentResponse {
        val type = json["type"]?.jsonPrimitive?.content
        
        return when (type) {
            "assistant" -> AgentResponse.Assistant(
                text = json["text"]?.jsonPrimitive?.content ?: "",
                rationale = json["rationale"]?.jsonPrimitive?.content,
                conversationId = json["conversationId"]?.jsonPrimitive?.content
            )
            "toolCall" -> AgentResponse.ToolCall(
                tool = json["tool"]?.jsonPrimitive?.content ?: "",
                args = json["args"]?.jsonObject ?: JsonObject(emptyMap()),
                uiHint = json["uiHint"]?.jsonPrimitive?.content?.let { 
                    ToolUIHint.valueOf(it.uppercase()) 
                },
                preview = json["preview"]?.jsonPrimitive?.content,
                rationale = json["rationale"]?.jsonPrimitive?.content,
                conversationId = json["conversationId"]?.jsonPrimitive?.content,
                id = json["id"]?.jsonPrimitive?.content ?: generateId()
            )
            "error" -> AgentResponse.Error(
                code = json["code"]?.jsonPrimitive?.content ?: "UNKNOWN",
                message = json["message"]?.jsonPrimitive?.content ?: "Unknown error",
                retryable = json["retryable"]?.jsonPrimitive?.boolean ?: false
            )
            else -> AgentResponse.Error(
                code = "INVALID_RESPONSE",
                message = "Invalid response type: $type"
            )
        }
    }
    
    private fun generateId(): String {
        return com.benasher44.uuid.uuid4().toString()
    }
}

/**
 * Extension function to parse SSE events from a ByteReadChannel
 */
private fun io.ktor.utils.io.ByteReadChannel.parseSSEEvents(): Flow<SSEEvent> = flow {
    var currentEvent = mutableMapOf<String, String>()
    val buffer = StringBuilder()
    
    try {
        while (!isClosedForRead) {
            val temp = ByteArray(1024)
            val bytesRead = readAvailable(temp)
            if (bytesRead <= 0) break
            val text = temp.decodeToString(0, bytesRead)
            buffer.append(text)
            
            var newlineIdx: Int
            while (buffer.indexOf('\n').also { newlineIdx = it } != -1) {
                val line = buffer.substring(0, newlineIdx).trimEnd('\r')
                buffer.deleteRange(0, newlineIdx + 1)
                
                when {
                    line.isEmpty() -> {
                        if (currentEvent.isNotEmpty()) {
                            parseSSEEvent(currentEvent)?.let { emit(it) }
                            currentEvent.clear()
                        }
                    }
                    line.startsWith("event:") -> {
                        currentEvent["event"] = line.substring(6).trim()
                    }
                    line.startsWith("data:") -> {
                        val data = line.substring(5).trim()
                        currentEvent["data"] = (currentEvent["data"] ?: "") + data
                    }
                    line.startsWith("id:") -> {
                        currentEvent["id"] = line.substring(3).trim()
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Stream ended or error
    }
}

/**
 * Parse a single SSE event from the event data
 */
private fun parseSSEEvent(eventData: Map<String, String>): SSEEvent? {
    val eventType = eventData["event"] ?: return null
    val data = eventData["data"] ?: return null
    
    return try {
        val json = Json.parseToJsonElement(data).jsonObject
        
        when (eventType) {
            "thinking" -> SSEEvent.Thinking(
                traceId = json["traceId"]?.jsonPrimitive?.content
            )
            "token" -> SSEEvent.Token(
                deltaText = json["deltaText"]?.jsonPrimitive?.content ?: ""
            )
            "tool_call" -> SSEEvent.ToolCall(
                tool = json["tool"]?.jsonPrimitive?.content ?: "",
                args = json["args"]?.jsonObject ?: JsonObject(emptyMap()),
                uiHint = json["uiHint"]?.jsonPrimitive?.content?.let { 
                    ToolUIHint.valueOf(it.uppercase()) 
                },
                preview = json["preview"]?.jsonPrimitive?.content,
                rationale = json["rationale"]?.jsonPrimitive?.content,
                id = json["id"]?.jsonPrimitive?.content ?: com.benasher44.uuid.uuid4().toString()
            )
            "assistant_final" -> SSEEvent.AssistantFinal(
                text = json["text"]?.jsonPrimitive?.content ?: "",
                conversationId = json["conversationId"]?.jsonPrimitive?.content
            )
            "guardrail" -> SSEEvent.Guardrail(
                reason = json["reason"]?.jsonPrimitive?.content ?: ""
            )
            "error" -> SSEEvent.Error(
                code = json["code"]?.jsonPrimitive?.content ?: "UNKNOWN",
                message = json["message"]?.jsonPrimitive?.content ?: "Unknown error"
            )
            "done" -> SSEEvent.Done
            else -> null
        }
    } catch (e: Exception) {
        null // Skip malformed events
    }
}

/**
 * Exception thrown by the remote agent
 */
class AgentException(
    val kind: ErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
