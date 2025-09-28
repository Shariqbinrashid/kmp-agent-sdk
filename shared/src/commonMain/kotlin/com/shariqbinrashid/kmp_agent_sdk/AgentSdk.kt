package com.shariqbinrashid.kmp_agent_sdk

import com.shariqbinrashid.kmp_agent_sdk.agent.RemoteAgent
import com.shariqbinrashid.kmp_agent_sdk.conversation.ConversationManager
import com.shariqbinrashid.kmp_agent_sdk.models.*
import com.shariqbinrashid.kmp_agent_sdk.registry.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Main SDK class providing the public API for agent integration
 */
class AgentSdk private constructor(
    private val conversationManager: ConversationManager,
    private val toolRegistry: ToolRegistry,
    private val remoteAgent: RemoteAgent
) {
    
    companion object {
        private var instance: AgentSdk? = null
        
        /**
         * Initialize the Agent SDK
         * 
         * @param config SDK configuration
         * @param agentConfig Remote agent configuration  
         * @param toolRegistry Pre-configured tool registry (optional)
         * @return Initialized SDK instance
         */
        fun init(
            config: AgentConfig,
            agentConfig: RemoteAgentConfig? = null,
            toolRegistry: ToolRegistry? = null
        ): AgentSdk {
            // Close existing instance if any
            instance?.close()
            
            val registry = toolRegistry ?: ToolRegistry()
            
            val remoteAgentConfig = agentConfig ?: RemoteAgentConfig(
                baseUrl = config.baseUrl
            )
            
            val agent = RemoteAgent(remoteAgentConfig, config)
            
            val preferences = AgentPreferences(
                language = config.locale,
                maxTokens = config.maxTokens,
                temperature = config.temperature
            )
            
            val appMeta = AppMeta(
                sdkVersion = "1.0.0", // TODO: Get from build config
                appId = config.appId,
                platform = getPlatform().name
            )
            
            val manager = ConversationManager(
                remoteAgent = agent,
                toolRegistry = registry,
                agentConfig = config,
                preferences = preferences,
                appMeta = appMeta
            )
            
            instance = AgentSdk(manager, registry, agent)
            return instance!!
        }
        
        /**
         * Get the current SDK instance
         * @throws IllegalStateException if SDK is not initialized
         */
        fun getInstance(): AgentSdk {
            return instance ?: throw IllegalStateException("AgentSdk not initialized. Call init() first.")
        }
        
        /**
         * Check if SDK is initialized
         */
        fun isInitialized(): Boolean = instance != null
    }
    
    // Public API
    
    /**
     * Observable conversation state
     */
    val conversationState: StateFlow<ConversationState> = conversationManager.conversationState
    
    /**
     * Observable state change events
     */
    val stateEvents: Flow<StateChangeEvent> = conversationManager.stateEvents
    
    /**
     * Get conversation snapshot for UI consumption
     */
    val conversationSnapshot: Flow<ConversationSnapshot> = conversationManager.conversationState
        .map { ConversationSnapshot.from(it) }
    
    // Tool Management
    
    /**
     * Register a tool with the SDK
     * 
     * @param toolSpec Tool specification
     * @param callback Tool execution callback
     * @return Result indicating success or failure
     */
    suspend fun registerTool(
        toolSpec: ToolSpec,
        callback: ToolCallback
    ): Result<Unit> {
        return toolRegistry.register(toolSpec, callback)
    }
    
    /**
     * Register a tool with inline callback
     */
    suspend fun registerTool(
        name: String,
        description: String,
        argsSchema: kotlinx.serialization.json.JsonObject,
        executionMode: ToolExecutionMode = ToolExecutionMode.CONFIRM,
        uiHint: ToolUIHint = ToolUIHint.SUGGESTION_CHIP,
        category: String? = null,
        callback: suspend (kotlinx.serialization.json.JsonObject, ToolExecutionContext) -> ToolExecutionResult
    ): Result<Unit> {
        val toolSpec = ToolSpec(
            name = name,
            description = description,
            argsSchema = argsSchema,
            executionMode = executionMode,
            uiHint = uiHint,
            category = category
        )
        
        val toolCallback = object : ToolCallback {
            override suspend fun execute(
                args: kotlinx.serialization.json.JsonObject,
                context: ToolExecutionContext
            ): ToolExecutionResult {
                return callback(args, context)
            }
        }
        
        return toolRegistry.register(toolSpec, toolCallback)
    }
    
    /**
     * Unregister a tool by name
     */
    suspend fun unregisterTool(toolName: String): Boolean {
        return toolRegistry.unregister(toolName)
    }
    
    /**
     * Get all registered tools
     */
    suspend fun getRegisteredTools(): List<ToolSpec> {
        return toolRegistry.getAllTools()
    }
    
    /**
     * Get tools by category
     */
    suspend fun getToolsByCategory(category: String): List<ToolSpec> {
        return toolRegistry.getToolsByCategory(category)
    }
    
    /**
     * Check if a tool is registered
     */
    suspend fun isToolRegistered(toolName: String): Boolean {
        return toolRegistry.isRegistered(toolName)
    }
    
    // Conversation Management
    
    /**
     * Send a user message to the agent
     * 
     * @param text Message text
     * @param attachments Optional file attachments
     * @return Result indicating success or failure
     */
    suspend fun sendMessage(
        text: String,
        attachments: List<Attachment> = emptyList()
    ): Result<Unit> {
        return conversationManager.sendUserMessage(text, attachments)
    }
    
    /**
     * Execute a proposed tool action
     * 
     * @param proposalId ID of the proposed action to execute
     * @return Result indicating success or failure
     */
    suspend fun executeAction(proposalId: String): Result<Unit> {
        return conversationManager.executeAction(proposalId)
    }
    
    /**
     * Reload the last turn with the agent
     * 
     * @return Result indicating success or failure
     */
    suspend fun reload(): Result<Unit> {
        return conversationManager.reload()
    }
    
    /**
     * Cancel current processing
     */
    fun cancel() {
        conversationManager.cancel()
    }
    
    /**
     * Reset the conversation (clear all messages and state)
     */
    fun reset() {
        conversationManager.reset()
    }
    
    // Utility Methods
    
    /**
     * Get the current conversation state value
     */
    fun getCurrentState(): ConversationState {
        return conversationManager.conversationState.value
    }
    
    /**
     * Check if the conversation can accept new messages
     */
    fun canSendMessage(): Boolean {
        return getCurrentState().isTerminal()
    }
    
    /**
     * Check if the conversation is currently processing
     */
    fun isProcessing(): Boolean {
        return getCurrentState().isProcessing()
    }
    
    /**
     * Check if the current state allows retry
     */
    fun canRetry(): Boolean {
        val currentState = getCurrentState().currentState
        return currentState is AgentState.Error && currentState.retryable
    }
    
    /**
     * Check if the current processing can be cancelled
     */
    fun canCancel(): Boolean {
        return getCurrentState().isProcessing()
    }
    
    /**
     * Get the session ID for the current conversation
     */
    fun getSessionId(): String {
        return getCurrentState().sessionId
    }
    
    /**
     * Get the conversation ID (if available from backend)
     */
    fun getConversationId(): String? {
        return getCurrentState().conversationId
    }
    
    /**
     * Close the SDK and clean up resources
     */
    fun close() {
        conversationManager.close()
        remoteAgent.close()
        instance = null
    }
}

/**
 * Extension function to create a simple tool callback
 */
fun createToolCallback(
    callback: suspend (kotlinx.serialization.json.JsonObject, ToolExecutionContext) -> ToolExecutionResult
): ToolCallback {
    return object : ToolCallback {
        override suspend fun execute(
            args: kotlinx.serialization.json.JsonObject,
            context: ToolExecutionContext
        ): ToolExecutionResult {
            return callback(args, context)
        }
    }
}

/**
 * Utility object for creating tool execution results
 */
object ToolExecutionResultFactory {
    fun success(
        data: kotlinx.serialization.json.JsonElement? = null,
        metadata: Map<String, String> = emptyMap()
    ): ToolExecutionResult {
        return ToolExecutionResult(
            success = true,
            data = data,
            metadata = metadata
        )
    }

    fun failure(
        error: String,
        metadata: Map<String, String> = emptyMap()
    ): ToolExecutionResult {
        return ToolExecutionResult(
            success = false,
            error = error,
            metadata = metadata
        )
    }
}
