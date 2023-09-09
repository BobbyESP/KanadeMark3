package caios.android.kanade.core.billing

import android.app.Activity
import android.content.Context
import caios.android.kanade.core.billing.models.ProductDetails
import caios.android.kanade.core.billing.models.ProductType
import caios.android.kanade.core.billing.models.translate
import caios.android.kanade.core.common.network.di.ApplicationScope
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchaseHistoryRecord
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryPurchaseHistoryParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import javax.inject.Inject

typealias ResponseListener<T> = (Result<T>) -> Unit

interface BillingClientProvider {

    fun initialize()
    fun dispose()

    fun queryProductDetails(productDetailsCommand: QueryProductDetailsCommand, listener: ResponseListener<List<ProductDetails>>)
    fun queryPurchases(productType: ProductType, listener: ResponseListener<List<Purchase>>)
    fun queryPurchaseHistory(productType: ProductType, listener: ResponseListener<List<PurchaseHistoryRecord>>)
    fun acknowledgePurchase(purchase: Purchase, listener: ResponseListener<Unit>)
    fun launchBillingFlow(activity: Activity, command: PurchaseSingleCommand, listener: ResponseListener<SingleBillingFlowResult>)

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        DISPOSED,
        UNAVAILABLE,
    }
}

class BillingClientProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) : BillingClientProvider {

    private val initializeResponseListeners = mutableListOf<ResponseListener<Unit>>()
    private val compositeListener = CompositePurchasesUpdatedListener()

    private val billingClient = BillingClient
        .newBuilder(context)
        .setListener(compositeListener)
        .enablePendingPurchases()
        .build()

    private var state = BillingClientProvider.State.DISCONNECTED

    override fun initialize() {
        if (state == BillingClientProvider.State.DISPOSED) {
            Timber.d("BillingClient already disposed")
            return
        }

        if (state == BillingClientProvider.State.UNAVAILABLE) {
            Timber.d("BillingClient unavailable")
            return
        }

        if (state == BillingClientProvider.State.CONNECTED) {
            Timber.d("BillingClient already connected")
            return
        }

        if (state == BillingClientProvider.State.CONNECTING) {
            Timber.d("BillingClient is connecting")
            return
        }

        state = BillingClientProvider.State.CONNECTING

        billingClient.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    state = when (val response = billingResult.toResponse()) {
                        is BillingResponse.OK -> {
                            Timber.d("BillingClient connected")
                            initializeResponseListeners.forEach { it.invoke(Result.success(Unit)) }
                            BillingClientProvider.State.CONNECTED
                        }
                        is BillingResponse.BillingUnavailable -> {
                            Timber.d("BillingClient unavailable")
                            initializeResponseListeners.forEach { it.invoke(Result.failure(InitializationFailedException(response))) }
                            BillingClientProvider.State.UNAVAILABLE
                        }
                        else -> {
                            Timber.d("BillingClient connection failed: $response")
                            initializeResponseListeners.forEach { it.invoke(Result.failure(InitializationFailedException(response))) }
                            BillingClientProvider.State.DISPOSED
                        }
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Timber.d("BillingClient disconnected")
                    state = BillingClientProvider.State.DISCONNECTED
                }
            }
        )
    }

    override fun dispose() {
        billingClient.endConnection()
        state = BillingClientProvider.State.DISPOSED
    }

    override fun queryProductDetails(
        productDetailsCommand: QueryProductDetailsCommand,
        listener: ResponseListener<List<ProductDetails>>,
    ) {
        require(state == BillingClientProvider.State.CONNECTED) { "BillingClient is not connected" }

        billingClient.queryProductDetailsAsync(productDetailsCommand.toQueryProductDetailsParams()) { result, products ->
            when (val response = result.toResponse()) {
                is BillingResponse.OK -> {
                    listener.invoke(Result.success(products.translate()))
                }
                is BillingResponse.ServiceDisconnected, is BillingResponse.ServiceError -> {
                    Timber.d("queryProductDetails: service error. CODE=${response.code}")
                    state = BillingClientProvider.State.DISPOSED
                    listener.invoke(Result.failure(QueryProductDetailsListFailedException(response, productDetailsCommand)))
                }
                else -> {
                    listener.invoke(Result.failure(QueryProductDetailsListFailedException(response, productDetailsCommand)))
                }
            }
        }
    }

    override fun queryPurchases(
        productType: ProductType,
        listener: ResponseListener<List<Purchase>>
    ) {
        require(state == BillingClientProvider.State.CONNECTED) { "BillingClient is not connected" }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(productType.rawValue)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            when (val response = result.toResponse()) {
                is BillingResponse.OK -> {
                    listener.invoke(Result.success(purchases))
                }
                is BillingResponse.ServiceDisconnected, is BillingResponse.ServiceError -> {
                    Timber.d("queryPurchases: service error. CODE=${response.code}")
                    state = BillingClientProvider.State.DISPOSED
                    listener.invoke(Result.failure(QueryPurchasesFailedException(response)))
                }
                else -> {
                    listener.invoke(Result.failure(QueryPurchasesFailedException(response)))
                }
            }
        }
    }

    override fun queryPurchaseHistory(
        productType: ProductType,
        listener: ResponseListener<List<PurchaseHistoryRecord>>
    ) {
        require(state == BillingClientProvider.State.CONNECTED) { "BillingClient is not connected" }

        val params = QueryPurchaseHistoryParams.newBuilder()
            .setProductType(productType.rawValue)
            .build()

        billingClient.queryPurchaseHistoryAsync(params) { result, purchases ->
            when (val response = result.toResponse()) {
                is BillingResponse.OK -> {
                    listener.invoke(Result.success(purchases ?: emptyList()))
                }
                is BillingResponse.ServiceDisconnected, is BillingResponse.ServiceError -> {
                    Timber.d("queryPurchaseHistory: service error. CODE=${response.code}")
                    state = BillingClientProvider.State.DISPOSED
                    listener.invoke(Result.failure(QueryPurchasesFailedException(response)))
                }
                else -> {
                    listener.invoke(Result.failure(QueryPurchasesFailedException(response)))
                }
            }
        }
    }

    override fun acknowledgePurchase(
        purchase: Purchase,
        listener: ResponseListener<Unit>
    ) {
        require(state == BillingClientProvider.State.CONNECTED) { "BillingClient is not connected" }

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        billingClient.acknowledgePurchase(params) { result ->
            when (val response = result.toResponse()) {
                is BillingResponse.OK -> {
                    listener.invoke(Result.success(Unit))
                }
                is BillingResponse.ServiceDisconnected, is BillingResponse.ServiceError -> {
                    Timber.d("acknowledgePurchase: service error. CODE=${response.code}")
                    state = BillingClientProvider.State.DISPOSED
                    listener.invoke(Result.failure(AcknowledgePurchaseFailedException(response, params)))
                }
                else -> {
                    listener.invoke(Result.failure(AcknowledgePurchaseFailedException(response, params)))
                }
            }
        }
    }

    override fun launchBillingFlow(
        activity: Activity,
        command: PurchaseSingleCommand,
        listener: ResponseListener<SingleBillingFlowResult>,
    ) {
        require(state == BillingClientProvider.State.CONNECTED) { "BillingClient is not connected" }

        val updatedListener = object : PurchasesUpdatedListener {
            override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
                val response = result.toResponse()
                val isError = response !is BillingResponse.OK
                val isLibraryError = response is BillingResponse.OK && purchases == null
                val isPurchaseHandled = response is BillingResponse.OK && purchases != null && purchases.any { it.products.contains(command.productId.value) && !it.isAcknowledged }

                if (isError || isLibraryError || isPurchaseHandled) {
                    compositeListener.remove(this)
                }

                when (response) {
                    is BillingResponse.OK -> {
                        if (purchases == null) {
                            listener.invoke(Result.failure(LaunchBillingFlowFailedException(response, command)))
                            return
                        }

                        if (purchases.any { it.products.contains(command.productId.value) && !it.isAcknowledged }) {
                            listener.invoke(Result.success(SingleBillingFlowResult(command, purchases)))
                        }
                    }
                    is BillingResponse.ServiceDisconnected, is BillingResponse.ServiceError -> {
                        Timber.d("launchBillingFlow: service error. CODE=${response.code}")
                        state = BillingClientProvider.State.DISPOSED
                        listener.invoke(Result.failure(LaunchBillingFlowFailedException(response, command)))
                    }
                    else -> {
                        listener.invoke(Result.failure(LaunchBillingFlowFailedException(response, command)))
                    }
                }
            }
        }

        compositeListener.add(updatedListener)

        val launchBillingResponse = billingClient
            .launchBillingFlow(activity, command.toBillingFlowParams())
            .toResponse()

        if (launchBillingResponse !is BillingResponse.OK) {
            if (launchBillingResponse is BillingResponse.ServiceDisconnected || launchBillingResponse is BillingResponse.ServiceError) {
                Timber.d("launchBillingFlow: service error. CODE=${launchBillingResponse.code}")
                state = BillingClientProvider.State.DISPOSED
            }

            compositeListener.remove(updatedListener)
            listener.invoke(Result.failure(LaunchBillingFlowFailedException(launchBillingResponse, command)))
        }
    }
}
