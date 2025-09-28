package com.shariqbinrashid.kmp_agent_sdk.platform

import com.shariqbinrashid.kmp_agent_sdk.AgentSdk
import com.shariqbinrashid.kmp_agent_sdk.models.*
import com.shariqbinrashid.kmp_agent_sdk.models.StateChangeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android-specific bridge for integrating with Compose UI
 */
class AndroidAgentBridge private constructor(
    private val sdk: AgentSdk
) {
    
    companion object {
        /**
         * Create an Android bridge from the initialized SDK
         */
        fun from(sdk: AgentSdk): AndroidAgentBridge {
            return AndroidAgentBridge(sdk)
        }
        
        /**
         * Create an Android bridge from the global SDK instance
         */
        fun getInstance(): AndroidAgentBridge {
            return AndroidAgentBridge(AgentSdk.getInstance())
        }
    }
    
    // Expose flows for Compose integration
    
    /**
     * StateFlow for conversation state - perfect for Compose
     */
    val conversationState: StateFlow<ConversationState> = sdk.conversationState
    
    /**
     * Flow of state change events
     */
    val stateEvents: Flow<StateChangeEvent> = sdk.stateEvents
    
    /**
     * Flow of conversation snapshots for UI consumption
     */
    val conversationSnapshot: Flow<ConversationSnapshot> = sdk.conversationSnapshot
    
    // Delegate all methods to SDK
    
    suspend fun sendMessage(text: String, attachments: List<Attachment> = emptyList()) = 
        sdk.sendMessage(text, attachments)
    
    suspend fun executeAction(proposalId: String) = sdk.executeAction(proposalId)
    
    suspend fun reload() = sdk.reload()
    
    fun cancel() = sdk.cancel()
    
    fun reset() = sdk.reset()
    
    suspend fun registerTool(toolSpec: ToolSpec, callback: ToolCallback) = 
        sdk.registerTool(toolSpec, callback)
    
    suspend fun registerTool(
        name: String,
        description: String,
        argsSchema: kotlinx.serialization.json.JsonObject,
        executionMode: ToolExecutionMode = ToolExecutionMode.CONFIRM,
        uiHint: ToolUIHint = ToolUIHint.SUGGESTION_CHIP,
        category: String? = null,
        callback: suspend (kotlinx.serialization.json.JsonObject, ToolExecutionContext) -> ToolExecutionResult
    ) = sdk.registerTool(name, description, argsSchema, executionMode, uiHint, category, callback)
    
    suspend fun unregisterTool(toolName: String) = sdk.unregisterTool(toolName)
    
    suspend fun getRegisteredTools() = sdk.getRegisteredTools()
    
    fun getCurrentState() = sdk.getCurrentState()
    
    fun canSendMessage() = sdk.canSendMessage()
    
    fun isProcessing() = sdk.isProcessing()
    
    fun canRetry() = sdk.canRetry()
    
    fun canCancel() = sdk.canCancel()
    
    fun getSessionId() = sdk.getSessionId()
    
    fun getConversationId() = sdk.getConversationId()
}

/**
 * Compose-friendly state holder for agent UI
 */
    // @androidx.compose.runtime.Stable // Commented out to avoid Compose dependency
class AgentUiState(
    val conversationState: ConversationState,
    val canSendMessage: Boolean,
    val canRetry: Boolean,
    val canCancel: Boolean,
    val isProcessing: Boolean
) {
    companion object {
        fun from(snapshot: ConversationSnapshot): AgentUiState {
            return AgentUiState(
                conversationState = snapshot.state,
                canSendMessage = snapshot.canSendMessage,
                canRetry = snapshot.canRetry,
                canCancel = snapshot.canCancel,
                isProcessing = snapshot.state.isProcessing()
            )
        }
    }
}
