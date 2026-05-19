package com.foodtracker.diary.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class BillingProduct(
    val productId: String,
    val title: String,
    val price: String,
    val description: String,
    val type: String,
    val productDetails: ProductDetails,
    val offerToken: String? = null,
)

data class BillingUiState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val products: List<BillingProduct> = emptyList(),
)

class BillingRepository(
    context: Context,
    private val settingsRepository: AppSettingsRepository,
) {
    private val appContext = context.applicationContext
    private var products = emptyList<BillingProduct>()
    private val billingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val billingClient = BillingClient.newBuilder(appContext)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build(),
        )
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                purchases.forEach { purchase ->
                    billingScope.launch { runCatching { handlePurchase(purchase) } }
                }
            }
        }
        .build()

    suspend fun loadProducts(): BillingUiState = withContext(Dispatchers.IO) {
        val connected = ensureConnected()
        if (!connected) {
            return@withContext BillingUiState(message = "Google Play Billing is not available on this device.")
        }
        products = queryProducts()
        val message = if (products.isEmpty()) {
            "Create the Nibbl products in Google Play Console, then publish an internal test build."
        } else {
            null
        }
        restorePurchases()
        BillingUiState(available = true, products = products, message = message)
    }

    suspend fun launchPurchase(activity: Activity, productId: String): String = withContext(Dispatchers.Main) {
        val product = products.firstOrNull { it.productId == productId }
            ?: return@withContext "Product is not ready yet. Try Restore or reopen Settings."
        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product.productDetails)
        product.offerToken?.let { paramsBuilder.setOfferToken(it) }
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(paramsBuilder.build()))
                .build(),
        )
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> "Opening Google Play checkout."
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                restorePurchases()
                "Purchase restored."
            }
            else -> result.debugMessage.ifBlank { "Could not start checkout." }
        }
    }

    suspend fun restorePurchases(): AppSettings = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext settingsRepository.settings()
        val inApp = billingClient.queryPurchasesAsyncCompat(BillingClient.ProductType.INAPP)
        val subs = billingClient.queryPurchasesAsyncCompat(BillingClient.ProductType.SUBS)
        (inApp + subs).forEach { handlePurchase(it) }
        refreshEntitlements(inApp + subs)
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchaseAsyncCompat(params)
        }
        refreshEntitlements(listOf(purchase))
    }

    private suspend fun refreshEntitlements(purchases: List<Purchase>): AppSettings {
        val plus = purchases.any { it.isPurchased(NIBBL_PLUS_LIFETIME) }
        val pro = purchases.any { it.isPurchased(NIBBL_PRO_MONTHLY) || it.isPurchased(NIBBL_PRO_YEARLY) }
        return settingsRepository.update {
            it.copy(
                plusUnlocked = plus || it.plusUnlocked,
                proActive = pro,
                lastPurchaseSyncMillis = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun queryProducts(): List<BillingProduct> {
        val inApp = billingClient.queryProductDetailsAsyncCompat(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(NIBBL_PLUS_LIFETIME)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build(),
                    ),
                )
                .build(),
            BillingClient.ProductType.INAPP,
        )
        val subs = billingClient.queryProductDetailsAsyncCompat(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(NIBBL_PRO_MONTHLY)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build(),
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(NIBBL_PRO_YEARLY)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build(),
                    ),
                )
                .build(),
            BillingClient.ProductType.SUBS,
        )
        return inApp + subs
    }

    private suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) return true
        return suspendCancellableCoroutine { continuation ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                }
            })
        }
    }

    companion object {
        const val NIBBL_PLUS_LIFETIME = "nibbl_plus_lifetime"
        const val NIBBL_PRO_MONTHLY = "nibbl_pro_monthly"
        const val NIBBL_PRO_YEARLY = "nibbl_pro_yearly"
    }
}

private suspend fun BillingClient.queryProductDetailsAsyncCompat(
    params: QueryProductDetailsParams,
    type: String,
): List<BillingProduct> = suspendCancellableCoroutine { continuation ->
    queryProductDetailsAsync(params) { result, productDetailsResult ->
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            continuation.resume(emptyList())
            return@queryProductDetailsAsync
        }
        val products = productDetailsResult.productDetailsList.mapNotNull { details ->
            when (type) {
                BillingClient.ProductType.INAPP -> {
                    val price = details.oneTimePurchaseOfferDetails?.formattedPrice ?: return@mapNotNull null
                    BillingProduct(details.productId, details.name, price, details.description, type, details)
                }
                BillingClient.ProductType.SUBS -> {
                    val offer = details.subscriptionOfferDetails?.firstOrNull()
                    val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                    BillingProduct(
                        productId = details.productId,
                        title = details.name,
                        price = phase?.formattedPrice ?: return@mapNotNull null,
                        description = details.description,
                        type = type,
                        productDetails = details,
                        offerToken = offer.offerToken,
                    )
                }
                else -> null
            }
        }
        continuation.resume(products)
    }
}

private suspend fun BillingClient.queryPurchasesAsyncCompat(type: String): List<Purchase> =
    suspendCancellableCoroutine { continuation ->
        queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(type).build(),
        ) { result, purchases ->
            continuation.resume(
                if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases else emptyList(),
            )
        }
    }

private suspend fun BillingClient.acknowledgePurchaseAsyncCompat(params: AcknowledgePurchaseParams): BillingResult =
    suspendCancellableCoroutine { continuation ->
        acknowledgePurchase(params) { result -> continuation.resume(result) }
    }

private fun Purchase.isPurchased(productId: String): Boolean =
    purchaseState == Purchase.PurchaseState.PURCHASED && products.contains(productId)
