package com.jeremy.dashcam.core.pro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.jeremy.dashcam.data.ProStore
import java.security.MessageDigest

/**
 * Google Play subscription "jeremy_pro" (base plan "monthly", ₪9.90, with a 7-day free-trial offer for new subscribers).
 * Play decides trial eligibility and handles payment, renewal and cancellation.
 */
object Billing : PurchasesUpdatedListener {
    const val PRODUCT = "jeremy_pro"
    private const val TAG = "JeremyBilling"
    private lateinit var app: Context
    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    private val main = Handler(Looper.getMainLooper())
    private var retryMs = 2_000L

    fun init(c: Context) {
        app = c
        client = BillingClient.newBuilder(c)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect()
    }

    private fun connect() {
        val c = client ?: return
        if (c.isReady) { refresh(); return }
        runCatching {
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                        retryMs = 2_000L
                        refresh()
                    } else Log.w(TAG, "setup: ${r.responseCode} ${r.debugMessage}")
                }
                override fun onBillingServiceDisconnected() {
                    main.postDelayed({ connect() }, retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(60_000L)
                }
            })
        }.onFailure { Log.w(TAG, "connect failed", it) }
    }

    /** Re-reads the product (price / trial) and the active subscription. */
    fun refresh() {
        val c = client ?: return
        if (!c.isReady) { connect(); return }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT).setProductType(BillingClient.ProductType.SUBS).build()
        c.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { r, result ->
            val pd = if (r.responseCode == BillingClient.BillingResponseCode.OK) result.productDetailsList.firstOrNull() else null
            details = pd
            val offers = pd?.subscriptionOfferDetails.orEmpty()
            val trial = offers.any { o -> o.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros == 0L }
            val base = offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull()
            val price = base?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            main.post { ProStore.update { it.copy(productAvailable = pd != null, trialAvailable = trial, price = price ?: ProStore.PRICE) } }
        }
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, purchases ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, replace = true)
        }
    }

    /** Opens the Google Play purchase sheet (trial offer if eligible, else the monthly plan). */
    fun launch(activity: Activity): String? {
        val c = client ?: return "Google Play לא זמין"
        val pd = details ?: run { refresh(); return "המנוי עדיין לא זמין בחנות. נסה שוב מאוחר יותר." }
        val offers = pd.subscriptionOfferDetails.orEmpty()
        val offer = offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros == 0L }
            ?: offers.firstOrNull { it.offerId == null } ?: offers.firstOrNull()
            ?: return "המנוי עדיין לא זמין בחנות"
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).setOfferToken(offer.offerToken).build()
            ))
            .apply { ProStore.current.email?.let { setObfuscatedAccountId(sha256(it)) } }
            .build()
        val r = c.launchBillingFlow(activity, params)
        return if (r.responseCode == BillingClient.BillingResponseCode.OK) null else "לא ניתן לפתוח את Google Play (${r.responseCode})"
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> { handle(purchases.orEmpty(), replace = false); refresh() }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> main.post { ProStore.message("הרכישה לא הושלמה (${r.responseCode})") }
        }
    }

    private fun handle(purchases: List<Purchase>, replace: Boolean) {
        val active = purchases.filter { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        active.filter { !it.isAcknowledged }.forEach { p ->
            client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { ar ->
                Log.i(TAG, "acknowledge: ${ar.responseCode}")
            }
        }
        if (!replace && active.isEmpty()) return
        main.post {
            ProStore.markVerified()
            val was = ProStore.current.subscribed
            ProStore.update { it.copy(subscribed = active.isNotEmpty(), autoRenewing = active.any { p -> p.isAutoRenewing }) }
            if (!was && active.isNotEmpty() && !replace) ProStore.message("ברוך הבא ל־Jeremy Pro!")
        }
    }

    fun manageIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/account/subscriptions?sku=$PRODUCT&package=${app.packageName}"),
    )

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.lowercase().toByteArray())
        .joinToString("") { "%02x".format(it) }
}
