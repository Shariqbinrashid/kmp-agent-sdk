package com.shariqbinrashid.kmp_agent_sdk.registry

import com.shariqbinrashid.kmp_agent_sdk.models.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Registry for managing tool specifications and callbacks
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, RegisteredTool>()
    private val mutex = Mutex()
    
    /**
     * Register a tool with its specification and callback
     */
    suspend fun register(
        toolSpec: ToolSpec,
        callback: ToolCallback
    ): Result<Unit> = mutex.withLock {
        return try {
            // Validate tool specification
            validateToolSpec(toolSpec)
            
            // Check for duplicate names
            if (tools.containsKey(toolSpec.name)) {
                Result.failure(ToolRegistrationException("Tool '${toolSpec.name}' is already registered"))
            } else {
                tools[toolSpec.name] = RegisteredTool(toolSpec, callback)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Unregister a tool by name
     */
    suspend fun unregister(toolName: String): Boolean = mutex.withLock {
        return tools.remove(toolName) != null
    }
    
    /**
     * Get all registered tools
     */
    suspend fun getAllTools(): List<ToolSpec> = mutex.withLock {
        return tools.values.map { it.spec }
    }
    
    /**
     * Get a specific tool by name
     */
    suspend fun getTool(name: String): ToolSpec? = mutex.withLock {
        return tools[name]?.spec
    }
    
    /**
     * Execute a tool with the given arguments
     */
    suspend fun executeTool(
        toolName: String,
        args: JsonObject,
        context: ToolExecutionContext
    ): Result<ToolExecutionResult> {
        val registeredTool = mutex.withLock { tools[toolName] }
            ?: return Result.failure(ToolNotFoundException("Tool '$toolName' not found"))
        
        return try {
            // Validate arguments against schema
            val validationResult = validateArgs(registeredTool.spec.argsSchema, args)
            if (!validationResult.isValid) {
                return Result.failure(
                    ToolValidationException(
                        "Invalid arguments for tool '$toolName': ${validationResult.errors.joinToString(", ")}"
                    )
                )
            }
            
            // Execute the tool
            val result = registeredTool.callback.execute(args, context)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(ToolExecutionException("Error executing tool '$toolName': ${e.message}", e))
        }
    }
    
    /**
     * Check if a tool is registered
     */
    suspend fun isRegistered(toolName: String): Boolean = mutex.withLock {
        return tools.containsKey(toolName)
    }
    
    /**
     * Clear all registered tools
     */
    suspend fun clear() = mutex.withLock {
        tools.clear()
    }
    
    /**
     * Get tools by category
     */
    suspend fun getToolsByCategory(category: String): List<ToolSpec> = mutex.withLock {
        return tools.values
            .filter { it.spec.category == category }
            .map { it.spec }
    }
    
    private fun validateToolSpec(spec: ToolSpec) {
        if (spec.name.isBlank()) {
            throw ToolValidationException("Tool name cannot be blank")
        }
        
        if (spec.description.isBlank()) {
            throw ToolValidationException("Tool description cannot be blank")
        }
        
        // Basic JSON Schema validation
        if (!spec.argsSchema.containsKey("type")) {
            throw ToolValidationException("Tool args schema must have a 'type' property")
        }
    }
    
    private fun validateArgs(schema: JsonObject, args: JsonObject): ValidationResult {
        // Basic JSON Schema validation
        // In a real implementation, you'd use a proper JSON Schema validator
        val errors = mutableListOf<String>()
        
        val schemaType = schema["type"]?.jsonPrimitive?.content
        if (schemaType == "object") {
            val properties = schema["properties"]?.jsonObject
            val required = schema["required"]?.jsonArray
            
            // Check required properties
            required?.forEach { requiredProp ->
                val propName = requiredProp.jsonPrimitive.content
                if (!args.containsKey(propName)) {
                    errors.add("Missing required property: $propName")
                }
            }
            
            // Check property types (basic validation)
            properties?.forEach { (propName, propSchema) ->
                if (args.containsKey(propName)) {
                    val propValue = args[propName]
                    val expectedType = propSchema.jsonObject["type"]?.jsonPrimitive?.content
                    
                    val actualType = when (propValue) {
                        is JsonPrimitive -> when {
                            propValue.isString -> "string"
                            propValue.booleanOrNull != null -> "boolean"
                            propValue.intOrNull != null -> "integer"
                            propValue.doubleOrNull != null -> "number"
                            else -> "unknown"
                        }
                        is JsonObject -> "object"
                        is JsonArray -> "array"
                        else -> "null"
                    }
                    
                    if (expectedType != null && expectedType != actualType) {
                        errors.add("Property '$propName' expected type '$expectedType' but got '$actualType'")
                    }
                }
            }
        }
        
        return ValidationResult(errors.isEmpty(), errors)
    }
}

/**
 * Internal representation of a registered tool
 */
private data class RegisteredTool(
    val spec: ToolSpec,
    val callback: ToolCallback
)

/**
 * Result of argument validation
 */
private data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)

/**
 * Exception thrown when tool registration fails
 */
class ToolRegistrationException(message: String) : Exception(message)

/**
 * Exception thrown when tool is not found
 */
class ToolNotFoundException(message: String) : Exception(message)

/**
 * Exception thrown when tool validation fails
 */
class ToolValidationException(message: String) : Exception(message)

/**
 * Exception thrown when tool execution fails
 */
class ToolExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)
