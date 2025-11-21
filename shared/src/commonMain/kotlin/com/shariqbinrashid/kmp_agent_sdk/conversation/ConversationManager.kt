package com.shariqbinrashid.kmp_agent_sdk.conversation

import com.shariqbinrashid.kmp_agent_sdk.agent.RemoteAgent
import com.shariqbinrashid.kmp_agent_sdk.models.*
import com.shariqbinrashid.kmp_agent_sdk.registry.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Manages conversation state and orchestrates the flow between user, agent, and tools
 */
class ConversationManager(
    private val remoteAgent: RemoteAgent,
    private val toolRegistry: ToolRegistry,
    private val agentConfig: AgentConfig,
    private val preferences: AgentPreferences,
    private val appMeta: AppMeta
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _conversationState = MutableStateFlow(
        ConversationState(
            sessionId = generateSessionId(),
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )
    )
    
    private val _stateEvents = Channel<StateChangeEvent>(capacity = Channel.UNLIMITED)
    private var currentStreamingJob: Job? = null
    
    /**
     * Observable conversation state
     */
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()
    
    /**
     * Observable state change events
     */
    val stateEvents: Flow<StateChangeEvent> = _stateEvents.receiveAsFlow()
    
    /**
     * Send a user message and start agent processing
     */
    suspend fun sendUserMessage(
        text: String,
        attachments: List<Attachment> = emptyList()
    ): Result<Unit> {
        if (!_conversationState.value.isTerminal()) {
            return Result.failure(IllegalStateException("Cannot send message while processing"))
        }
        
        return try {
            // Add user message
            val userMessage = UserMessage(
                id = generateMessageId(),
                text = text,
                attachments = attachments,
                timestamp = Clock.System.now()
            )
            
            updateState { state ->
                state.copy(
                    messages = state.messages + userMessage,
                    currentState = AgentState.Sending,
                    updatedAt = Clock.System.now()
                )
            }
            
            // Start agent processing
            processAgentRequest()
            Result.success(Unit)
        } catch (e: Exception) {
            updateState { state ->
                state.copy(
                    currentState = AgentState.Error(
                        kind = ErrorKind.UNKNOWN,
                        message = "Failed to send message: ${e.message}",
                        retryable = true
                    ),
                    updatedAt = Clock.System.now()
                )
            }
            Result.failure(e)
        }
    }
    
    /**
     * Execute a proposed tool action
     */
    suspend fun executeAction(proposalId: String): Result<Unit> {
        val currentState = _conversationState.value.currentState
        if (currentState !is AgentState.ProposedAction || currentState.proposalId != proposalId) {
            return Result.failure(IllegalStateException("No matching proposed action found"))
        }
        
        return try {
            updateState { state ->
                state.copy(
                    currentState = AgentState.ToolExecuting(
                        toolName = currentState.toolName,
                        args = currentState.args
                    ),
                    updatedAt = Clock.System.now()
                )
            }
            
            // Execute the tool
            val context = ToolExecutionContext(
                sessionId = _conversationState.value.sessionId,
                conversationId = _conversationState.value.conversationId ?: "",
                metadata = _conversationState.value.metadata
            )
            
            val result = toolRegistry.executeTool(
                toolName = currentState.toolName,
                args = currentState.args,
                context = context
            )
            
            result.fold(
                onSuccess = { toolResult ->
                    // Add tool result message
                    val toolResultMessage = ToolResultMessage(
                        id = generateMessageId(),
                        toolName = currentState.toolName,
                        result = toolResult,
                        timestamp = Clock.System.now()
                    )
                    
                    updateState { state ->
                        state.copy(
                            messages = state.messages + toolResultMessage,
                            currentState = AgentState.ToolExecuted(
                                toolName = currentState.toolName,
                                result = toolResult
                            ),
                            updatedAt = Clock.System.now()
                        )
                    }
                    
                    // Send follow-up request to agent with tool result
                    processAgentRequest(includeToolResult = true)
                },
                onFailure = { error ->
                    updateState { state ->
                        state.copy(
                            currentState = AgentState.Error(
                                kind = ErrorKind.TOOL_EXECUTION,
                                message = "Tool execution failed: ${error.message}",
                                retryable = true
                            ),
                            updatedAt = Clock.System.now()
                        )
                    }
                }
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            updateState { state ->
                state.copy(
                    currentState = AgentState.Error(
                        kind = ErrorKind.TOOL_EXECUTION,
                        message = "Failed to execute action: ${e.message}",
                        retryable = true
                    ),
                    updatedAt = Clock.System.now()
                )
            }
            Result.failure(e)
        }
    }
    
    /**
     * Reload the last turn with the agent
     */
    suspend fun reload(): Result<Unit> {
        if (_conversationState.value.isProcessing()) {
            return Result.failure(IllegalStateException("Cannot reload while processing"))
        }
        
        return try {
            updateState { state ->
                state.copy(
                    currentState = AgentState.Sending,
                    updatedAt = Clock.System.now()
                )
            }
            
            processAgentRequest(reload = true)
            Result.success(Unit)
        } catch (e: Exception) {
            updateState { state ->
                state.copy(
                    currentState = AgentState.Error(
                        kind = ErrorKind.UNKNOWN,
                        message = "Failed to reload: ${e.message}",
                        retryable = true
                    ),
                    updatedAt = Clock.System.now()
                )
            }
            Result.failure(e)
        }
    }
    
    /**
     * Cancel current processing
     */
    fun cancel() {
        currentStreamingJob?.cancel()
        updateState { state ->
            state.copy(
                currentState = AgentState.Idle,
                updatedAt = Clock.System.now()
            )
        }
    }
    
    /**
     * Reset the conversation
     */
    fun reset() {
        currentStreamingJob?.cancel()
        updateState { state ->
            ConversationState(
                sessionId = generateSessionId(),
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now()
            )
        }
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        scope.cancel()
        currentStreamingJob?.cancel()
        _stateEvents.close()
    }
    
    private suspend fun processAgentRequest(
        includeToolResult: Boolean = false,
        reload: Boolean = false
    ) {
        currentStreamingJob?.cancel()
        
        currentStreamingJob = scope.launch {
            try {
                val request = buildAgentRequest(reload)
                
                if (agentConfig.streamingEnabled) {
                    processStreamingResponse(request)
                } else {
                    processSyncResponse(request)
                }
            } catch (e: Exception) {
                updateState { state ->
                    state.copy(
                        currentState = AgentState.Error(
                            kind = ErrorKind.UNKNOWN,
                            message = "Processing failed: ${e.message}",
                            retryable = true
                        ),
                        updatedAt = Clock.System.now()
                    )
                }
            }
        }
    }
    
    private suspend fun processStreamingResponse(request: AgentRequest) {
        updateState { state ->
            state.copy(
                currentState = AgentState.Thinking(),
                updatedAt = Clock.System.now()
            )
        }
        
        var currentAssistantMessage: AssistantMessage? = null
        
        remoteAgent.sendStreamingRequest(request)
            .collect { result ->
                result.fold(
                    onSuccess = { event ->
                        when (event) {
                            is SSEEvent.Thinking -> {
                                updateState { state ->
                                    state.copy(
                                        currentState = AgentState.Thinking(event.traceId),
                                        updatedAt = Clock.System.now()
                                    )
                                }
                            }
                            
                            is SSEEvent.Token -> {
                                val existingMessage = currentAssistantMessage
                                if (existingMessage != null) {
                                    val updatedText = existingMessage.text + event.deltaText
                                    currentAssistantMessage = existingMessage.copy(text = updatedText)
                                    
                                    updateState { state ->
                                        val updatedMessages = state.messages.dropLast(1) + currentAssistantMessage!!
                                        state.copy(
                                            messages = updatedMessages,
                                            currentState = AgentState.Streaming(event.deltaText, updatedText),
                                            updatedAt = Clock.System.now()
                                        )
                                    }
                                } else {
                                    currentAssistantMessage = AssistantMessage(
                                        id = generateMessageId(),
                                        text = event.deltaText,
                                        partial = true,
                                        timestamp = Clock.System.now()
                                    )
                                    
                                    updateState { state ->
                                        state.copy(
                                            messages = state.messages + currentAssistantMessage!!,
                                            currentState = AgentState.Streaming(event.deltaText, event.deltaText),
                                            updatedAt = Clock.System.now()
                                        )
                                    }
                                }
                            }
                            
                            is SSEEvent.ToolCall -> {
                                val toolProposal = ToolCallProposedMessage(
                                    id = generateMessageId(),
                                    toolName = event.tool,
                                    args = event.args,
                                    uiHint = event.uiHint,
                                    preview = event.preview,
                                    rationale = event.rationale,
                                    timestamp = Clock.System.now()
                                )
                                
                                // Check if tool has AUTO execution mode and execute automatically
                                val toolSpec = toolRegistry.getTool(event.tool)
                                val shouldAutoExecute = toolSpec?.executionMode == ToolExecutionMode.AUTO
                                
                                if (shouldAutoExecute) {
                                    // Auto-execute the tool
                                    val context = ToolExecutionContext(
                                        sessionId = _conversationState.value.sessionId,
                                        conversationId = _conversationState.value.conversationId ?: "",
                                        metadata = _conversationState.value.metadata
                                    )
                                    
                                    val toolResult = toolRegistry.executeTool(
                                        toolName = event.tool,
                                        args = event.args,
                                        context = context
                                    )
                                    
                                    toolResult.fold(
                                        onSuccess = { result ->
                                            val toolResultMessage = ToolResultMessage(
                                                id = generateMessageId(),
                                                toolName = event.tool,
                                                result = result,
                                                timestamp = Clock.System.now()
                                            )
                                            
                                            updateState { state ->
                                                state.copy(
                                                    messages = state.messages + toolProposal + toolResultMessage,
                                                    currentState = AgentState.ToolExecuted(
                                                        toolName = event.tool,
                                                        result = result
                                                    ),
                                                    conversationId = state.conversationId,
                                                    updatedAt = Clock.System.now()
                                                )
                                            }
                                        },
                                        onFailure = { error ->
                                            updateState { state ->
                                                state.copy(
                                                    messages = state.messages + toolProposal,
                                                    currentState = AgentState.Error(
                                                        kind = ErrorKind.TOOL_EXECUTION,
                                                        message = "Tool execution failed: ${error.message}",
                                                        retryable = true
                                                    ),
                                                    updatedAt = Clock.System.now()
                                                )
                                            }
                                        }
                                    )
                                } else {
                                    // Manual execution required
                                    updateState { state ->
                                        state.copy(
                                            messages = state.messages + toolProposal,
                                            currentState = AgentState.ProposedAction(
                                                toolName = event.tool,
                                                args = event.args,
                                                uiHint = event.uiHint,
                                                preview = event.preview,
                                                proposalId = event.id
                                            ),
                                            conversationId = state.conversationId, // Keep existing conversation ID
                                            updatedAt = Clock.System.now()
                                        )
                                    }
                                }
                            }
                            
                            is SSEEvent.AssistantFinal -> {
                                // Finalize the assistant message
                                currentAssistantMessage?.let { message ->
                                    val finalMessage = message.copy(
                                        text = event.text,
                                        partial = false
                                    )
                                    
                                    updateState { state ->
                                        val updatedMessages = state.messages.dropLast(1) + finalMessage
                                        state.copy(
                                            messages = updatedMessages,
                                            currentState = AgentState.AssistantFinal(event.text),
                                            conversationId = event.conversationId ?: state.conversationId,
                                            updatedAt = Clock.System.now()
                                        )
                                    }
                                } ?: run {
                                    // No streaming happened, add final message directly
                                    val finalMessage = AssistantMessage(
                                        id = generateMessageId(),
                                        text = event.text,
                                        partial = false,
                                        timestamp = Clock.System.now()
                                    )
                                    
                                    updateState { state ->
                                        state.copy(
                                            messages = state.messages + finalMessage,
                                            currentState = AgentState.AssistantFinal(event.text),
                                            conversationId = event.conversationId ?: state.conversationId,
                                            updatedAt = Clock.System.now()
                                        )
                                    }
                                }
                                
                                // Transition to idle after a brief delay
                                delay(500)
                                updateState { state ->
                                    state.copy(
                                        currentState = AgentState.Idle,
                                        updatedAt = Clock.System.now()
                                    )
                                }
                            }
                            
                            is SSEEvent.Guardrail -> {
                                val systemNotice = SystemNoticeMessage(
                                    id = generateMessageId(),
                                    text = event.reason,
                                    kind = SystemNoticeKind.GUARDRAIL,
                                    timestamp = Clock.System.now()
                                )
                                
                                updateState { state ->
                                    state.copy(
                                        messages = state.messages + systemNotice,
                                        updatedAt = Clock.System.now()
                                    )
                                }
                            }
                            
                            is SSEEvent.Error -> {
                                updateState { state ->
                                    state.copy(
                                        currentState = AgentState.Error(
                                            kind = ErrorKind.SERVER_ERROR,
                                            message = event.message,
                                            retryable = true
                                        ),
                                        updatedAt = Clock.System.now()
                                    )
                                }
                            }
                            
                            is SSEEvent.Done -> {
                                // Stream completed, ensure we're in a terminal state
                                if (_conversationState.value.currentState !is AgentState.ProposedAction) {
                                    updateState { state ->
                                        state.copy(
                                            currentState = AgentState.Idle,
                                            updatedAt = Clock.System.now()
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onFailure = { error ->
                        updateState { state ->
                            state.copy(
                                currentState = AgentState.Error(
                                    kind = ErrorKind.NETWORK,
                                    message = "Streaming error: ${error.message}",
                                    retryable = true
                                ),
                                updatedAt = Clock.System.now()
                            )
                        }
                    }
                )
            }
    }
    
    private suspend fun processSyncResponse(request: AgentRequest) {
        val result = remoteAgent.sendSyncRequest(request)
        
        result.fold(
            onSuccess = { response ->
                when (response) {
                    is AgentResponse.Assistant -> {
                        val assistantMessage = AssistantMessage(
                            id = generateMessageId(),
                            text = response.text,
                            timestamp = Clock.System.now()
                        )
                        
                        updateState { state ->
                            state.copy(
                                messages = state.messages + assistantMessage,
                                currentState = AgentState.AssistantFinal(response.text),
                                conversationId = response.conversationId ?: state.conversationId,
                                updatedAt = Clock.System.now()
                            )
                        }
                        
                        // Transition to idle
                        delay(500)
                        updateState { state ->
                            state.copy(
                                currentState = AgentState.Idle,
                                updatedAt = Clock.System.now()
                            )
                        }
                    }
                    
                    is AgentResponse.ToolCall -> {
                        val toolProposal = ToolCallProposedMessage(
                            id = generateMessageId(),
                            toolName = response.tool,
                            args = response.args,
                            uiHint = response.uiHint,
                            preview = response.preview,
                            rationale = response.rationale,
                            timestamp = Clock.System.now()
                        )
                        
                        updateState { state ->
                            state.copy(
                                messages = state.messages + toolProposal,
                                currentState = AgentState.ProposedAction(
                                    toolName = response.tool,
                                    args = response.args,
                                    uiHint = response.uiHint,
                                    preview = response.preview,
                                    proposalId = response.id
                                ),
                                conversationId = response.conversationId ?: state.conversationId,
                                updatedAt = Clock.System.now()
                            )
                        }
                    }
                    
                    is AgentResponse.Error -> {
                        updateState { state ->
                            state.copy(
                                currentState = AgentState.Error(
                                    kind = ErrorKind.SERVER_ERROR,
                                    message = response.message,
                                    retryable = response.retryable
                                ),
                                updatedAt = Clock.System.now()
                            )
                        }
                    }
                }
            },
            onFailure = { error ->
                updateState { state ->
                    state.copy(
                        currentState = AgentState.Error(
                            kind = ErrorKind.NETWORK,
                            message = "Request failed: ${error.message}",
                            retryable = true
                        ),
                        updatedAt = Clock.System.now()
                    )
                }
            }
        )
    }
    
    private suspend fun buildAgentRequest(reload: Boolean = false): AgentRequest {
        val state = _conversationState.value
        val tools = toolRegistry.getAllTools()
        
        val apiMessages = state.messages.mapNotNull { message ->
            when (message) {
                is UserMessage -> ApiMessage(
                    role = MessageRole.USER,
                    content = message.text,
                    timestamp = message.timestamp.toString()
                )
                is AssistantMessage -> if (!message.partial) {
                    ApiMessage(
                        role = MessageRole.ASSISTANT,
                        content = message.text,
                        timestamp = message.timestamp.toString()
                    )
                } else null
                is ToolResultMessage -> ApiMessage(
                    role = MessageRole.TOOL,
                    content = "",
                    toolName = message.toolName,
                    result = kotlinx.serialization.json.Json.encodeToJsonElement(ToolExecutionResult.serializer(), message.result) as kotlinx.serialization.json.JsonObject,
                    timestamp = message.timestamp.toString()
                )
                else -> null
            }
        }
        
        val apiTools = tools.map { tool ->
            ApiTool(
                name = tool.name,
                description = tool.description,
                argsSchema = tool.argsSchema,
                executionMode = tool.executionMode,
                uiHint = tool.uiHint
            )
        }
        
        return AgentRequest(
            sessionId = state.sessionId,
            conversationId = state.conversationId,
            messages = apiMessages,
            tools = apiTools,
            preferences = preferences,
            appMeta = appMeta,
            reload = reload
        )
    }
    
    private fun updateState(update: (ConversationState) -> ConversationState) {
        val oldState = _conversationState.value
        val newState = update(oldState)
        _conversationState.value = newState
        
        // Emit state change event
        val event = StateChangeEvent(
            previousState = oldState.currentState,
            newState = newState.currentState
        )
        _stateEvents.trySend(event)
    }
    
    private fun generateSessionId(): String = com.benasher44.uuid.uuid4().toString()
    private fun generateMessageId(): String = com.benasher44.uuid.uuid4().toString()
}
