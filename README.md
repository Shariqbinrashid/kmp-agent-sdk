# KMP Agent SDK

A Kotlin Multiplatform SDK for integrating AI agents into mobile applications with support for tool execution, streaming responses, and state management.

## Features

- 🤖 **Agent Integration**: Connect to remote AI agents via HTTP/SSE
- 🔧 **Tool Registry**: Register custom tools with JSON Schema validation
- 📱 **Cross-Platform**: Works on Android and iOS
- 🌊 **Streaming Support**: Real-time streaming responses with SSE
- 🎯 **State Management**: Comprehensive state machine for UI integration
- 🔄 **Reactive**: Built with Kotlin Flows for reactive UI updates
- 🛡️ **Type Safe**: Full Kotlin type safety across platforms

## Installation

The SDK is published via **JitPack** - automatically built from GitHub releases. No approval needed, works immediately!

### Gradle (Kotlin DSL)

Add JitPack repository and the dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.Shariqbinrashid:kmp-agent-sdk:1.0.0")
}
```

### Gradle (Groovy)

Add JitPack repository and the dependency to your `build.gradle`:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.Shariqbinrashid:kmp-agent-sdk:1.0.0'
}
```

### Using a Specific Version

You can use:
- **Release tags**: `1.0.0`, `1.0.1`, etc. (recommended)
- **Commit hash**: `abc123def` (short hash)
- **Branch**: `master-SNAPSHOT` (for latest from branch)

```kotlin
// Use a specific release (recommended)
implementation("com.github.Shariqbinrashid:kmp-agent-sdk:1.0.0")

// Use latest from master branch
implementation("com.github.Shariqbinrashid:kmp-agent-sdk:master-SNAPSHOT")
```

**Note:** The SDK supports Android, iOS, and all Kotlin Multiplatform targets. JitPack automatically builds and publishes new versions when you create GitHub release tags.
## Quick Start

### 1. Initialize the SDK

```kotlin
import com.shariqbinrashid.kmp_agent_sdk.*
import com.shariqbinrashid.kmp_agent_sdk.models.*

// Configure the SDK
val config = AgentConfig(
    appId = "your-app-id",
    apiKey = "your-api-key",
    baseUrl = "https://your-agent-backend.com",
    streamingEnabled = true,
    locale = "en"
)

// Configure remote agent (optional - uses config.baseUrl by default)
val agentConfig = RemoteAgentConfig(
    baseUrl = "https://your-agent-backend.com",
    streamPath = "/v1/stream",
    planPath = "/v1/plan",
    authToken = "your-auth-token"
)

// Initialize SDK
val sdk = AgentSdk.init(config, agentConfig)
```

### 2. Register Tools

```kotlin
// Define tool schema
val addToCartSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("productId", buildJsonObject {
            put("type", "string")
            put("description", "Product ID to add to cart")
        })
        put("quantity", buildJsonObject {
            put("type", "integer")
            put("minimum", 1)
        })
    })
    put("required", buildJsonArray {
        add("productId")
        add("quantity")
    })
}

// Register tool
sdk.registerTool(
    name = "add_to_cart",
    description = "Add a product to the shopping cart",
    argsSchema = addToCartSchema,
    executionMode = ToolExecutionMode.CONFIRM,
    uiHint = ToolUIHint.SUGGESTION_CHIP
) { args, context ->
    val productId = args["productId"]?.jsonPrimitive?.content ?: ""
    val quantity = args["quantity"]?.jsonPrimitive?.int ?: 1
    
    // Your tool implementation
    val success = addProductToCart(productId, quantity)
    
    if (success) {
        ToolExecutionResult.success(
            data = buildJsonObject {
                put("message", "Product added to cart")
                put("cartTotal", getCurrentCartTotal())
            }
        )
    } else {
        ToolExecutionResult.failure("Failed to add product to cart")
    }
}
```

### 3. Handle Conversations

```kotlin
// Send user message
sdk.sendMessage("I want to buy some running shoes")

// Observe conversation state
sdk.conversationState.collect { state ->
    when (state.currentState) {
        is AgentState.Thinking -> {
            // Show thinking indicator
            showThinkingIndicator()
        }
        is AgentState.Streaming -> {
            // Update streaming text
            updateStreamingText(state.currentState.textDelta)
        }
        is AgentState.ProposedAction -> {
            // Show action confirmation UI
            showActionConfirmation(
                toolName = state.currentState.toolName,
                args = state.currentState.args,
                preview = state.currentState.preview
            )
        }
        is AgentState.Error -> {
            // Handle errors
            showError(state.currentState.message, state.currentState.retryable)
        }
        else -> {
            // Handle other states
        }
    }
}

// Execute proposed actions
sdk.executeAction(proposalId)
```

## Platform Integration

### Android (Compose)

```kotlin
import com.shariqbinrashid.kmp_agent_sdk.platform.AndroidAgentBridge

@Composable
fun ChatScreen() {
    val bridge = AndroidAgentBridge.getInstance()
    val conversationState by bridge.conversationState.collectAsState()
    
    Column {
        // Messages list
        LazyColumn {
            items(conversationState.messages) { message ->
                MessageItem(message = message)
            }
        }
        
        // Input field
        if (bridge.canSendMessage()) {
            ChatInput(
                onSendMessage = { text ->
                    scope.launch {
                        bridge.sendMessage(text)
                    }
                }
            )
        }
        
        // Action buttons for proposed actions
        when (val state = conversationState.currentState) {
            is AgentState.ProposedAction -> {
                ActionButton(
                    text = "Execute ${state.toolName}",
                    onClick = {
                        scope.launch {
                            bridge.executeAction(state.proposalId)
                        }
                    }
                )
            }
        }
    }
}
```

### iOS (SwiftUI)

```kotlin
import com.shariqbinrashid.kmp_agent_sdk.platform.IOSAgentBridge

class ChatViewModel: ObservableObject {
    private let bridge = IOSAgentBridge.getInstance()
    
    @Published var conversationState: ConversationState
    @Published var canSendMessage = false
    
    init() {
        // Observe state changes
        bridge.conversationState.collect { [weak self] state in
            DispatchQueue.main.async {
                self?.conversationState = state
                self?.canSendMessage = state.isTerminal()
            }
        }
    }
    
    func sendMessage(_ text: String) {
        bridge.sendMessage(text: text) { result in
            // Handle result
        }
    }
    
    func executeAction(_ proposalId: String) {
        bridge.executeAction(proposalId: proposalId) { result in
            // Handle result
        }
    }
}
```

## Agent State Machine

The SDK manages conversation state through a comprehensive state machine:

- **Idle**: Ready to accept new messages
- **Sending**: Request dispatched to agent
- **Thinking**: Agent is processing (show spinner)
- **Streaming**: Receiving streamed response tokens
- **ProposedAction**: Agent proposed a tool execution
- **ToolExecuting**: Tool is being executed
- **ToolExecuted**: Tool execution completed
- **AssistantFinal**: Final response received
- **Error**: Error occurred (may be retryable)

## Tool Execution Modes

- **AUTO**: Execute automatically without user confirmation
- **CONFIRM**: Require user confirmation before execution

## UI Hints

Guide how tools should be presented in your UI:

- **SUGGESTION_CHIP**: Small chip/button
- **INLINE_CARD**: Inline card component
- **DIALOG**: Modal dialog
- **FULL_SCREEN**: Full screen presentation
- **NONE**: No specific UI hint

## Error Handling

The SDK provides comprehensive error handling:

```kotlin
sdk.stateEvents.collect { event ->
    when (val newState = event.newState) {
        is AgentState.Error -> {
            when (newState.kind) {
                ErrorKind.NETWORK -> {
                    // Handle network errors
                    if (newState.retryable) {
                        showRetryOption()
                    }
                }
                ErrorKind.AUTHENTICATION -> {
                    // Handle auth errors
                    redirectToLogin()
                }
                ErrorKind.TOOL_EXECUTION -> {
                    // Handle tool execution errors
                    showToolError(newState.message)
                }
                // ... handle other error types
            }
        }
    }
}
```

## Backend Integration

Your agent backend should implement these endpoints:

### Streaming Endpoint: `POST /v1/stream`

Returns Server-Sent Events:

```
event: thinking
data: {"traceId": "abc123"}

event: token
data: {"deltaText": "I can help you find running shoes. "}

event: tool_call
data: {"tool": "search_products", "args": {"query": "running shoes"}, "id": "tool_123"}

event: assistant_final
data: {"text": "I found some great running shoes for you!", "conversationId": "conv_456"}

event: done
data: {}
```

### Sync Endpoint: `POST /v1/plan`

Returns JSON response:

```json
{
  "type": "toolCall",
  "tool": "search_products",
  "args": {"query": "running shoes"},
  "uiHint": "INLINE_CARD",
  "preview": "Searching for running shoes...",
  "conversationId": "conv_456",
  "id": "tool_123"
}
```

## Advanced Usage

### Custom Tool Validation

```kotlin
// Register tool with custom validation
val toolSpec = ToolSpec(
    name = "custom_tool",
    description = "A custom tool with validation",
    argsSchema = customSchema,
    executionMode = ToolExecutionMode.CONFIRM
)

val callback = createToolCallback { args, context ->
    // Custom validation
    if (!validateCustomArgs(args)) {
        return@createToolCallback ToolExecutionResult.failure("Invalid arguments")
    }
    
    // Execute tool logic
    executeCustomTool(args, context)
}

sdk.registerTool(toolSpec, callback)
```

### Conversation Management

```kotlin
// Reload last turn
sdk.reload()

// Cancel current processing
sdk.cancel()

// Reset conversation
sdk.reset()

// Get conversation metadata
val sessionId = sdk.getSessionId()
val conversationId = sdk.getConversationId()
```

### Tool Categories

```kotlin
// Register tools with categories
sdk.registerTool(
    name = "search_products",
    description = "Search for products",
    argsSchema = schema,
    category = "catalog"
) { args, context -> /* ... */ }

// Get tools by category
val catalogTools = sdk.getToolsByCategory("catalog")
```

## Sample Integration

Check out the [SampleIntegration.kt](shared/src/commonMain/kotlin/com/shariqbinrashid/kmp_agent_sdk/samples/SampleIntegration.kt) file for a complete e-commerce integration example showing:

- Product search and categories
- Add to cart functionality
- Discount code application
- Product details retrieval

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

1. Fork the repository
2. Create your feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## Publishing

The SDK is integrated with **JitPack** - it automatically builds and publishes from GitHub releases. No manual publishing needed!

### How It Works

1. **Push code to GitHub** (already done)
2. **Create a release tag:**
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. **JitPack automatically:**
   - Detects the new tag
   - Builds your project
   - Publishes to JitPack Maven repository
   - Makes it available immediately (takes 2-5 minutes)

### Check Build Status

Visit: https://jitpack.io/#Shariqbinrashid/kmp-agent-sdk

You can see:
- Build logs for each version
- Build status (success/failure)
- Available versions
- Download statistics

## Support

For questions and support, please open an issue on [GitHub](https://github.com/Shariqbinrashid/kmp-agent-sdk/issues).

## About

This SDK was inspired by the need for a Kotlin Multiplatform solution for integrating AI agents into mobile applications. 
