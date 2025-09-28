package com.shariqbinrashid.kmp_agent_sdk.samples

import com.shariqbinrashid.kmp_agent_sdk.AgentSdk
import com.shariqbinrashid.kmp_agent_sdk.ToolExecutionResultFactory
import com.shariqbinrashid.kmp_agent_sdk.createToolCallback
import com.shariqbinrashid.kmp_agent_sdk.models.*
import com.shariqbinrashid.kmp_agent_sdk.models.AgentState
import kotlinx.serialization.json.*

/**
 * Sample integration showing how to use the Agent SDK
 * This demonstrates common e-commerce use cases like adding to cart and rendering categories
 */
class SampleECommerceIntegration {
    
    private lateinit var sdk: AgentSdk
    
    /**
     * Initialize the SDK with sample configuration
     */
    suspend fun initialize() {
        // Initialize SDK
        val config = AgentConfig(
            appId = "sample-ecommerce-app",
            apiKey = "your-api-key",
            baseUrl = "https://your-agent-backend.com",
            streamingEnabled = true,
            locale = "en",
            telemetryOptIn = true
        )
        
        val agentConfig = RemoteAgentConfig(
            baseUrl = "https://your-agent-backend.com",
            streamPath = "/v1/stream",
            planPath = "/v1/plan"
        )
        
        sdk = AgentSdk.init(config, agentConfig)
        
        // Register e-commerce tools
        registerECommerceTools()
    }
    
    /**
     * Register sample e-commerce tools
     */
    private suspend fun registerECommerceTools() {
        
        // 1. Add to Cart Tool
        registerAddToCartTool()
        
        // 2. Get Categories Tool
        registerGetCategoresTool()
        
        // 3. Search Products Tool
        registerSearchProductsTool()
        
        // 4. Get Product Details Tool
        registerGetProductDetailsTool()
        
        // 5. Apply Discount Tool
        registerApplyDiscountTool()
    }
    
    private suspend fun registerAddToCartTool() {
        val argsSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("productId", buildJsonObject {
                    put("type", "string")
                    put("description", "The ID of the product to add to cart")
                })
                put("quantity", buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("description", "Quantity of the product to add")
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("description", "Size variant (optional)")
                })
                put("color", buildJsonObject {
                    put("type", "string")
                    put("description", "Color variant (optional)")
                })
            })
            put("required", buildJsonArray {
                add("productId")
                add("quantity")
            })
        }
        
        sdk.registerTool(
            name = "add_to_cart",
            description = "Add a product to the user's shopping cart",
            argsSchema = argsSchema,
            executionMode = ToolExecutionMode.CONFIRM,
            uiHint = ToolUIHint.SUGGESTION_CHIP,
            category = "cart"
        ) { args, context ->
            val productId = args["productId"]?.jsonPrimitive?.content ?: ""
            val quantity = args["quantity"]?.jsonPrimitive?.int ?: 1
            val size = args["size"]?.jsonPrimitive?.content
            val color = args["color"]?.jsonPrimitive?.content
            
            // Simulate adding to cart
            val result = addToCart(productId, quantity, size, color)
            
            if (result.success) {
                ToolExecutionResultFactory.success(
                    data = buildJsonObject {
                        put("success", true)
                        put("cartItemId", result.cartItemId)
                        put("totalItems", result.totalItems)
                        put("message", "Product added to cart successfully")
                    }
                )
            } else {
                ToolExecutionResultFactory.failure("Failed to add product to cart: ${result.error}")
            }
        }
    }
    
    private suspend fun registerGetCategoresTool() {
        val argsSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("parentCategory", buildJsonObject {
                    put("type", "string")
                    put("description", "Parent category to filter by (optional)")
                })
                put("includeSubcategories", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to include subcategories")
                    put("default", true)
                })
            })
        }
        
        sdk.registerTool(
            name = "get_categories",
            description = "Get product categories to display to the user",
            argsSchema = argsSchema,
            executionMode = ToolExecutionMode.AUTO, // Auto-execute since it's just data retrieval
            uiHint = ToolUIHint.INLINE_CARD,
            category = "catalog"
        ) { args, context ->
            val parentCategory = args["parentCategory"]?.jsonPrimitive?.content
            val includeSubcategories = args["includeSubcategories"]?.jsonPrimitive?.boolean ?: true
            
            val categories = getCategories(parentCategory, includeSubcategories)
            
            ToolExecutionResultFactory.success(
                data = buildJsonObject {
                    put("categories", buildJsonArray {
                        categories.forEach { category ->
                            add(buildJsonObject {
                                put("id", category.id)
                                put("name", category.name)
                                put("description", category.description)
                                put("imageUrl", category.imageUrl)
                                put("productCount", category.productCount)
                            })
                        }
                    })
                }
            )
        }
    }
    
    private suspend fun registerSearchProductsTool() {
        val argsSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Search query for products")
                })
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "Category to filter by (optional)")
                })
                put("minPrice", buildJsonObject {
                    put("type", "number")
                    put("description", "Minimum price filter (optional)")
                })
                put("maxPrice", buildJsonObject {
                    put("type", "number")
                    put("description", "Maximum price filter (optional)")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of results to return")
                    put("default", 10)
                    put("maximum", 50)
                })
            })
            put("required", buildJsonArray {
                add("query")
            })
        }
        
        sdk.registerTool(
            name = "search_products",
            description = "Search for products based on user query and filters",
            argsSchema = argsSchema,
            executionMode = ToolExecutionMode.AUTO,
            uiHint = ToolUIHint.INLINE_CARD,
            category = "catalog"
        ) { args, context ->
            val query = args["query"]?.jsonPrimitive?.content ?: ""
            val category = args["category"]?.jsonPrimitive?.content
            val minPrice = args["minPrice"]?.jsonPrimitive?.double
            val maxPrice = args["maxPrice"]?.jsonPrimitive?.double
            val limit = args["limit"]?.jsonPrimitive?.int ?: 10
            
            val products = searchProducts(query, category, minPrice, maxPrice, limit)
            
            ToolExecutionResultFactory.success(
                data = buildJsonObject {
                    put("products", buildJsonArray {
                        products.forEach { product ->
                            add(buildJsonObject {
                                put("id", product.id)
                                put("name", product.name)
                                put("description", product.description)
                                put("price", product.price)
                                put("imageUrl", product.imageUrl)
                                put("rating", product.rating)
                                put("inStock", product.inStock)
                            })
                        }
                    })
                    put("totalResults", products.size)
                }
            )
        }
    }
    
    private suspend fun registerGetProductDetailsTool() {
        val argsSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("productId", buildJsonObject {
                    put("type", "string")
                    put("description", "The ID of the product to get details for")
                })
            })
            put("required", buildJsonArray {
                add("productId")
            })
        }
        
        sdk.registerTool(
            name = "get_product_details",
            description = "Get detailed information about a specific product",
            argsSchema = argsSchema,
            executionMode = ToolExecutionMode.AUTO,
            uiHint = ToolUIHint.FULL_SCREEN,
            category = "catalog"
        ) { args, context ->
            val productId = args["productId"]?.jsonPrimitive?.content ?: ""
            
            val product = getProductDetails(productId)
            
            if (product != null) {
                ToolExecutionResultFactory.success(
                    data = buildJsonObject {
                        put("product", buildJsonObject {
                            put("id", product.id)
                            put("name", product.name)
                            put("description", product.description)
                            put("price", product.price)
                            put("images", buildJsonArray {
                                product.images.forEach { add(it) }
                            })
                            put("specifications", buildJsonObject {
                                product.specifications.forEach { (key, value) ->
                                    put(key, value)
                                }
                            })
                            put("reviews", buildJsonArray {
                                product.reviews.forEach { review ->
                                    add(buildJsonObject {
                                        put("rating", review.rating)
                                        put("comment", review.comment)
                                        put("author", review.author)
                                    })
                                }
                            })
                            put("inStock", product.inStock)
                            put("availableSizes", buildJsonArray {
                                product.availableSizes.forEach { add(it) }
                            })
                            put("availableColors", buildJsonArray {
                                product.availableColors.forEach { add(it) }
                            })
                        })
                    }
                )
            } else {
                ToolExecutionResultFactory.failure("Product not found")
            }
        }
    }
    
    private suspend fun registerApplyDiscountTool() {
        val argsSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("discountCode", buildJsonObject {
                    put("type", "string")
                    put("description", "The discount code to apply")
                })
            })
            put("required", buildJsonArray {
                add("discountCode")
            })
        }
        
        sdk.registerTool(
            name = "apply_discount",
            description = "Apply a discount code to the user's cart",
            argsSchema = argsSchema,
            executionMode = ToolExecutionMode.CONFIRM,
            uiHint = ToolUIHint.DIALOG,
            category = "cart"
        ) { args, context ->
            val discountCode = args["discountCode"]?.jsonPrimitive?.content ?: ""
            
            val result = applyDiscount(discountCode)
            
            if (result.success) {
                ToolExecutionResultFactory.success(
                    data = buildJsonObject {
                        put("success", true)
                        put("discountAmount", result.discountAmount)
                        put("newTotal", result.newTotal)
                        put("message", "Discount applied successfully")
                    }
                )
            } else {
                ToolExecutionResultFactory.failure("Invalid discount code")
            }
        }
    }
    
    /**
     * Example of how to observe state changes and handle UI updates
     */
    fun observeAgentState(onStateChange: (AgentState) -> Unit) {
        // In a real app, you'd collect this flow in your UI layer
        // sdk.stateEvents.collect { event ->
        //     onStateChange(event.newState)
        //     
        //     when (event.newState) {
        //         is AgentState.Thinking -> {
        //             // Show thinking indicator
        //         }
        //         is AgentState.Streaming -> {
        //             // Update streaming text
        //         }
        //         is AgentState.ProposedAction -> {
        //             // Show action confirmation UI
        //         }
        //         is AgentState.ToolExecuting -> {
        //             // Show tool execution progress
        //         }
        //         is AgentState.Error -> {
        //             // Show error message with retry option if retryable
        //         }
        //         else -> {
        //             // Handle other states
        //         }
        //     }
        // }
    }
    
    /**
     * Example usage scenarios
     */
    suspend fun exampleUsage() {
        // User asks: "I need some running shoes"
        sdk.sendMessage("I need some running shoes")
        
        // Agent might respond with search results and suggest categories
        // User can then interact with the proposed actions
        
        // User asks: "Add the Nike Air Max to my cart in size 10"
        sdk.sendMessage("Add the Nike Air Max to my cart in size 10")
        
        // Agent will propose an add_to_cart action which user can confirm
    }
    
    // Mock data and functions (replace with real implementations)
    
    private data class CartResult(
        val success: Boolean,
        val cartItemId: String = "",
        val totalItems: Int = 0,
        val error: String = ""
    )
    
    private data class Category(
        val id: String,
        val name: String,
        val description: String,
        val imageUrl: String,
        val productCount: Int
    )
    
    private data class Product(
        val id: String,
        val name: String,
        val description: String,
        val price: Double,
        val imageUrl: String,
        val rating: Double,
        val inStock: Boolean,
        val images: List<String> = emptyList(),
        val specifications: Map<String, String> = emptyMap(),
        val reviews: List<Review> = emptyList(),
        val availableSizes: List<String> = emptyList(),
        val availableColors: List<String> = emptyList()
    )
    
    private data class Review(
        val rating: Int,
        val comment: String,
        val author: String
    )
    
    private data class DiscountResult(
        val success: Boolean,
        val discountAmount: Double = 0.0,
        val newTotal: Double = 0.0
    )
    
    private suspend fun addToCart(productId: String, quantity: Int, size: String?, color: String?): CartResult {
        // Mock implementation
        return CartResult(
            success = true,
            cartItemId = "cart_item_${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}",
            totalItems = quantity
        )
    }
    
    private suspend fun getCategories(parentCategory: String?, includeSubcategories: Boolean): List<Category> {
        // Mock implementation
        return listOf(
            Category("1", "Electronics", "Electronic devices and gadgets", "https://example.com/electronics.jpg", 150),
            Category("2", "Clothing", "Fashion and apparel", "https://example.com/clothing.jpg", 300),
            Category("3", "Sports", "Sports and fitness equipment", "https://example.com/sports.jpg", 75)
        )
    }
    
    private suspend fun searchProducts(query: String, category: String?, minPrice: Double?, maxPrice: Double?, limit: Int): List<Product> {
        // Mock implementation
        return listOf(
            Product("1", "Nike Air Max", "Comfortable running shoes", 120.0, "https://example.com/nike.jpg", 4.5, true),
            Product("2", "Adidas Ultraboost", "High-performance running shoes", 150.0, "https://example.com/adidas.jpg", 4.7, true)
        )
    }
    
    private suspend fun getProductDetails(productId: String): Product? {
        // Mock implementation
        return Product(
            id = productId,
            name = "Nike Air Max",
            description = "Comfortable running shoes with air cushioning",
            price = 120.0,
            imageUrl = "https://example.com/nike.jpg",
            rating = 4.5,
            inStock = true,
            images = listOf("https://example.com/nike1.jpg", "https://example.com/nike2.jpg"),
            specifications = mapOf("Material" to "Synthetic", "Weight" to "300g"),
            reviews = listOf(Review(5, "Great shoes!", "John")),
            availableSizes = listOf("8", "9", "10", "11"),
            availableColors = listOf("Black", "White", "Red")
        )
    }
    
    private suspend fun applyDiscount(discountCode: String): DiscountResult {
        // Mock implementation
        return if (discountCode == "SAVE10") {
            DiscountResult(success = true, discountAmount = 10.0, newTotal = 110.0)
        } else {
            DiscountResult(success = false)
        }
    }
}
