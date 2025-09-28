package com.shariqbinrashid.kmp_agent_sdk.models

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ============= Core Configuration Models =============

/**
 * Configuration for the Agent SDK
 */
@Serializable
data class AgentConfig(
    val appId: String,
    val apiKey: String,
    val baseUrl: String = "https://api.agent.com",
    val streamingEnabled: Boolean = true,
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val locale: String = "en",
    val telemetryOptIn: Boolean = true,
    val timeoutMs: Long = 30000,
    val retryAttempts: Int = 3
)

/**
 * Remote agent configuration for custom backends
 */
@Serializable
data class RemoteAgentConfig(
    val baseUrl: String,
    val streamPath: String = "/v1/stream",
    val planPath: String = "/v1/plan",
    val authToken: String? = null,
    val timeoutMs: Long = 30000,
    val headers: Map<String, String> = emptyMap()
)

/**
 * App metadata sent with requests
 */
@Serializable
data class AppMeta(
    val sdkVersion: String,
    val appId: String,
    val platform: String,
    val appVersion: String? = null
)

/**
 * User preferences for agent behavior
 */
@Serializable
data class AgentPreferences(
    val language: String = "en",
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val toolBias: Float? = null,
    val topKHistory: Int = 10
)

// ============= Tool Models =============

/**
 * Tool execution modes
 */
enum class ToolExecutionMode {
    AUTO,    // Execute automatically without user confirmation
    CONFIRM  // Require user confirmation before execution
}

/**
 * UI hints for how tools should be presented
 */
enum class ToolUIHint {
    SUGGESTION_CHIP,
    INLINE_CARD,
    DIALOG,
    FULL_SCREEN,
    NONE
}

/**
 * Tool specification for registration
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val argsSchema: JsonObject, // JSON Schema Draft-07
    val executionMode: ToolExecutionMode = ToolExecutionMode.CONFIRM,
    val uiHint: ToolUIHint = ToolUIHint.SUGGESTION_CHIP,
    val category: String? = null,
    val version: String = "1.0.0"
)

/**
 * Tool execution context provided to callbacks
 */
data class ToolExecutionContext(
    val sessionId: String,
    val conversationId: String,
    val userId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Result of tool execution
 */
@Serializable
data class ToolExecutionResult(
    val success: Boolean,
    val data: JsonElement? = null,
    val error: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Tool call proposal from agent
 */
@Serializable
data class ToolCallProposal(
    val toolName: String,
    val args: JsonObject,
    val uiHint: ToolUIHint? = null,
    val preview: String? = null,
    val rationale: String? = null,
    val id: String
)

/**
 * Tool callback interface
 */
interface ToolCallback {
    suspend fun execute(args: JsonObject, context: ToolExecutionContext): ToolExecutionResult
}

// ============= State Models =============

/**
 * Types of errors that can occur
 */
enum class ErrorKind {
    NETWORK,
    TIMEOUT,
    AUTHENTICATION,
    SCHEMA_VALIDATION,
    TOOL_EXECUTION,
    RATE_LIMIT,
    SERVER_ERROR,
    UNKNOWN
}

/**
 * Agent/UI states representing the current state of the conversation
 */
sealed class AgentState {
    object Idle : AgentState()
    object Sending : AgentState()
    data class Thinking(val traceId: String? = null) : AgentState()
    data class Streaming(val textDelta: String, val fullText: String) : AgentState()
    data class ProposedAction(
        val toolName: String,
        val args: JsonObject,
        val uiHint: ToolUIHint?,
        val preview: String?,
        val proposalId: String
    ) : AgentState()
    data class ToolExecuting(val toolName: String, val args: JsonObject) : AgentState()
    data class ToolExecuted(val toolName: String, val result: ToolExecutionResult) : AgentState()
    data class AssistantFinal(val message: String) : AgentState()
    data class Error(
        val kind: ErrorKind,
        val message: String,
        val retryable: Boolean = false,
        val details: Map<String, String> = emptyMap()
    ) : AgentState()
}

/**
 * State change event emitted by the SDK
 */
data class StateChangeEvent(
    val previousState: AgentState,
    val newState: AgentState,
    val timestamp: Instant = Clock.System.now()
)

// ============= Message Models =============

/**
 * Base class for all message types in the conversation timeline
 */
@Serializable
sealed class Message {
    abstract val id: String
    abstract val timestamp: Instant
}

/**
 * User message in the conversation
 */
@Serializable
data class UserMessage(
    override val id: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    override val timestamp: Instant
) : Message()

/**
 * Assistant message in the conversation
 */
@Serializable
data class AssistantMessage(
    override val id: String,
    val text: String,
    val partial: Boolean = false,
    override val timestamp: Instant
) : Message()

/**
 * Tool call proposal message
 */
@Serializable
data class ToolCallProposedMessage(
    override val id: String,
    val toolName: String,
    val args: JsonObject,
    val uiHint: ToolUIHint?,
    val preview: String?,
    val rationale: String? = null,
    override val timestamp: Instant
) : Message()

/**
 * Tool execution result message
 */
@Serializable
data class ToolResultMessage(
    override val id: String,
    val toolName: String,
    val result: ToolExecutionResult,
    override val timestamp: Instant
) : Message()

/**
 * System notice message (errors, guardrails, etc.)
 */
@Serializable
data class SystemNoticeMessage(
    override val id: String,
    val text: String,
    val kind: SystemNoticeKind,
    override val timestamp: Instant
) : Message()

/**
 * Types of system notices
 */
enum class SystemNoticeKind {
    ERROR,
    GUARDRAIL,
    INFO,
    WARNING
}

/**
 * File or media attachment
 */
@Serializable
data class Attachment(
    val id: String,
    val type: AttachmentType,
    val url: String? = null,
    val data: String? = null, // Base64 encoded data for small files
    val mimeType: String,
    val size: Long? = null,
    val name: String? = null
)

/**
 * Types of attachments supported
 */
enum class AttachmentType {
    IMAGE,
    DOCUMENT,
    AUDIO,
    VIDEO,
    OTHER
}

// ============= Conversation Models =============

/**
 * Complete state of a conversation
 */
@Serializable
data class ConversationState(
    val sessionId: String,
    val conversationId: String? = null, // Backend-provided chat ID for continuity
    val messages: List<Message> = emptyList(),
    @Contextual val currentState: AgentState = AgentState.Idle,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /**
     * Get the latest user message
     */
    fun getLastUserMessage(): UserMessage? {
        return messages.filterIsInstance<UserMessage>().lastOrNull()
    }
    
    /**
     * Get the latest assistant message
     */
    fun getLastAssistantMessage(): AssistantMessage? {
        return messages.filterIsInstance<AssistantMessage>().lastOrNull()
    }
    
    /**
     * Check if conversation is in a terminal state
     */
    fun isTerminal(): Boolean {
        return currentState is AgentState.Idle || 
               currentState is AgentState.AssistantFinal ||
               currentState is AgentState.Error
    }
    
    /**
     * Check if conversation is actively processing
     */
    fun isProcessing(): Boolean {
        return currentState is AgentState.Sending ||
               currentState is AgentState.Thinking ||
               currentState is AgentState.Streaming ||
               currentState is AgentState.ToolExecuting
    }
}

/**
 * Conversation snapshot for UI consumption
 */
data class ConversationSnapshot(
    val state: ConversationState,
    val pendingActions: List<ToolCallProposal> = emptyList(),
    val canSendMessage: Boolean,
    val canRetry: Boolean,
    val canCancel: Boolean
) {
    companion object {
        fun from(state: ConversationState): ConversationSnapshot {
            val canSendMessage = state.isTerminal()
            val canRetry = state.currentState is AgentState.Error && 
                          (state.currentState as AgentState.Error).retryable
            val canCancel = state.isProcessing()
            
            return ConversationSnapshot(
                state = state,
                canSendMessage = canSendMessage,
                canRetry = canRetry,
                canCancel = canCancel
            )
        }
    }
}

// ============= API Models =============

/**
 * Request payload for both sync and streaming endpoints
 */
@Serializable
data class AgentRequest(
    val sessionId: String,
    val conversationId: String? = null, // For conversation continuity
    val systemPrompt: String? = null,
    val messages: List<ApiMessage>,
    val tools: List<ApiTool>,
    val preferences: AgentPreferences,
    val appMeta: AppMeta,
    val reload: Boolean = false
)

/**
 * Message format for API communication
 */
@Serializable
data class ApiMessage(
    val role: MessageRole,
    val content: String,
    val toolName: String? = null,
    val args: JsonObject? = null,
    val result: JsonObject? = null,
    val timestamp: String? = null
)

/**
 * Message roles for API
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL
}

/**
 * Tool definition for API
 */
@Serializable
data class ApiTool(
    val name: String,
    val description: String,
    val argsSchema: JsonObject,
    val executionMode: ToolExecutionMode,
    val uiHint: ToolUIHint
)

/**
 * Sync response types
 */
@Serializable
sealed class AgentResponse {
    @Serializable
    data class Assistant(
        val type: String = "assistant",
        val text: String,
        val rationale: String? = null,
        val conversationId: String? = null
    ) : AgentResponse()
    
    @Serializable
    data class ToolCall(
        val type: String = "toolCall",
        val tool: String,
        val args: JsonObject,
        val uiHint: ToolUIHint? = null,
        val preview: String? = null,
        val rationale: String? = null,
        val conversationId: String? = null,
        val id: String
    ) : AgentResponse()
    
    @Serializable
    data class Error(
        val type: String = "error",
        val code: String,
        val message: String,
        val retryable: Boolean = false
    ) : AgentResponse()
}

/**
 * SSE event types and payloads
 */
@Serializable
sealed class SSEEvent {
    @Serializable
    data class Thinking(
        val traceId: String? = null
    ) : SSEEvent()
    
    @Serializable
    data class Token(
        val deltaText: String
    ) : SSEEvent()
    
    @Serializable
    data class ToolCall(
        val tool: String,
        val args: JsonObject,
        val uiHint: ToolUIHint? = null,
        val preview: String? = null,
        val rationale: String? = null,
        val id: String
    ) : SSEEvent()
    
    @Serializable
    data class AssistantFinal(
        val text: String,
        val conversationId: String? = null
    ) : SSEEvent()
    
    @Serializable
    data class Guardrail(
        val reason: String
    ) : SSEEvent()
    
    @Serializable
    data class Error(
        val code: String,
        val message: String
    ) : SSEEvent()
    
    @Serializable
    object Done : SSEEvent()
}

/**
 * Raw SSE event from server
 */
data class RawSSEEvent(
    val event: String,
    val data: String,
    val id: String? = null
)
