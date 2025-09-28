package com.shariqbinrashid.kmp_agent_sdk.platform

import com.shariqbinrashid.kmp_agent_sdk.AgentSdk
import com.shariqbinrashid.kmp_agent_sdk.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS-specific bridge for integrating with SwiftUI
 * Provides Combine-compatible publishers and async methods
 */
class IOSAgentBridge private constructor(
    private val sdk: AgentSdk
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        /**
         * Create an iOS bridge from the initialized SDK
         */
        fun from(sdk: AgentSdk): IOSAgentBridge {
            return IOSAgentBridge(sdk)
        }
        
        /**
         * Create an iOS bridge from the global SDK instance
         */
        fun getInstance(): IOSAgentBridge {
            return IOSAgentBridge(AgentSdk.getInstance())
        }
    }
    
    // Expose flows for SwiftUI integration
    
    /**
     * StateFlow for conversation state
     */
    val conversationState: StateFlow<ConversationState> = sdk.conversationState
    
    /**
     * Flow of state change events
     */
    val stateEvents: Flow<StateChangeEvent> = sdk.stateEvents
    
    /**
     * Flow of conversation snapshots
     */
    val conversationSnapshot: Flow<ConversationSnapshot> = sdk.conversationSnapshot
    
    // iOS-friendly async methods
    
    /**
     * Send message with completion callback
     */
    fun sendMessage(
        text: String,
        attachments: List<Attachment> = emptyList(),
        completion: (Result<Unit>) -> Unit
    ) {
        scope.launch {
            val result = sdk.sendMessage(text, attachments)
            completion(result)
        }
    }
    
    /**
     * Execute action with completion callback
     */
    fun executeAction(proposalId: String, completion: (Result<Unit>) -> Unit) {
        scope.launch {
            val result = sdk.executeAction(proposalId)
            completion(result)
        }
    }
    
    /**
     * Reload with completion callback
     */
    fun reload(completion: (Result<Unit>) -> Unit) {
        scope.launch {
            val result = sdk.reload()
            completion(result)
        }
    }
    
    /**
     * Register tool with completion callback
     */
    fun registerTool(
        toolSpec: ToolSpec,
        callback: ToolCallback,
        completion: (Result<Unit>) -> Unit
    ) {
        scope.launch {
            val result = sdk.registerTool(toolSpec, callback)
            completion(result)
        }
    }
    
    /**
     * Register tool with inline callback and completion
     */
    fun registerTool(
        name: String,
        description: String,
        argsSchema: kotlinx.serialization.json.JsonObject,
        executionMode: ToolExecutionMode = ToolExecutionMode.CONFIRM,
        uiHint: ToolUIHint = ToolUIHint.SUGGESTION_CHIP,
        category: String? = null,
        callback: suspend (kotlinx.serialization.json.JsonObject, ToolExecutionContext) -> ToolExecutionResult,
        completion: (Result<Unit>) -> Unit
    ) {
        scope.launch {
            val result = sdk.registerTool(name, description, argsSchema, executionMode, uiHint, category, callback)
            completion(result)
        }
    }
    
    /**
     * Unregister tool with completion callback
     */
    fun unregisterTool(toolName: String, completion: (Boolean) -> Unit) {
        scope.launch {
            val result = sdk.unregisterTool(toolName)
            completion(result)
        }
    }
    
    /**
     * Get registered tools with completion callback
     */
    fun getRegisteredTools(completion: (List<ToolSpec>) -> Unit) {
        scope.launch {
            val tools = sdk.getRegisteredTools()
            completion(tools)
        }
    }
    
    // Synchronous methods for immediate state access
    
    fun cancel() = sdk.cancel()
    
    fun reset() = sdk.reset()
    
    fun getCurrentState() = sdk.getCurrentState()
    
    fun canSendMessage() = sdk.canSendMessage()
    
    fun isProcessing() = sdk.isProcessing()
    
    fun canRetry() = sdk.canRetry()
    
    fun canCancel() = sdk.canCancel()
    
    fun getSessionId() = sdk.getSessionId()
    
    fun getConversationId() = sdk.getConversationId()
    
    /**
     * Clean up resources
     */
    fun close() {
        scope.cancel()
    }
}

/**
 * SwiftUI-friendly state holder
 */
data class IOSAgentUiState(
    val conversationState: ConversationState,
    val canSendMessage: Boolean,
    val canRetry: Boolean,
    val canCancel: Boolean,
    val isProcessing: Boolean
) {
    companion object {
        fun from(snapshot: ConversationSnapshot): IOSAgentUiState {
            return IOSAgentUiState(
                conversationState = snapshot.state,
                canSendMessage = snapshot.canSendMessage,
                canRetry = snapshot.canRetry,
                canCancel = snapshot.canCancel,
                isProcessing = snapshot.state.isProcessing()
            )
        }
    }
}
