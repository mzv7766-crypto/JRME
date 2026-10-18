package com.shomer.bodycam.cloud

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
import com.shomer.bodycam.R
import java.security.MessageDigest

/**
 * Google Play subscriptions:
 *  - "shomer_pro"  – personal Pro, base plan "monthly" (₪19.90) with a 14-day free-trial offer.
 *  - "shomer_team" – security companies, one base plan per package size: "team-5", "team-10", "team-25", "team-50".
 *    Changing the package replaces the old subscription (prorated by Play).
 */
object Billing : PurchasesUpdatedListener {
    const val PRO = "shomer_pro"
    const val TEAM = "shomer_team"
    val TEAM_SEATS = listOf(5, 10, 25, 50)
    fun seatsOf(basePlan: String?) = basePlan?.removePrefix("team-")?.toIntOrNull()?.takeIf { it in TEAM_SEATS }

    private lateinit var app: Context
    private var client: BillingClient? = null
    private var pro: ProductDetails? = null
    private var team: ProductDetails? = null
    private val main = Handler(Looper.getMainLooper())
    private var retryMs = 2_000L
    /** Base plan the manager is buying right now (Play doesn't tell the app which base plan a purchase is for). */
    private var pendingTeamPlan: String? = null

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
                    if (r.responseCode == BillingClient.BillingResponseCode.OK) { retryMs = 2_000L; refresh() }
                    else Log.w(Cloud.TAG, "billing setup: ${r.responseCode} ${r.debugMessage}")
                }
                override fun onBillingServiceDisconnected() {
                    main.postDelayed({ connect() }, retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(60_000L)
                }
            })
        }.onFailure { Log.w(Cloud.TAG, "billing connect failed", it) }
    }

    fun refresh() {
        val c = client ?: return
        if (!c.isReady) { connect(); return }
        val products = listOf(PRO, TEAM).map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }
        c.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { r, result ->
            val list = if (r.responseCode == BillingClient.BillingResponseCode.OK) result.productDetailsList else emptyList()
            pro = list.firstOrNull { it.productId == PRO }
            team = list.firstOrNull { it.productId == TEAM }
            val proOffers = pro?.subscriptionOfferDetails.orEmpty()
            val trial = proOffers.any { o -> o.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros == 0L }
            val proBase = proOffers.firstOrNull { it.offerId == null } ?: proOffers.firstOrNull()
            val teamOffers = team?.subscriptionOfferDetails.orEmpty().filter { it.offerId == null }.mapNotNull { o ->
                val seats = seatsOf(o.basePlanId) ?: return@mapNotNull null
                TeamOffer(o.basePlanId, seats, o.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: "", o.offerToken)
            }.sortedBy { it.seats }
            main.post {
                AccountStore.update {
                    it.copy(
                        proAvailable = pro != null, proTrialAvailable = trial,
                        proPrice = proBase?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice ?: AccountStore.PRO_PRICE,
                        teamOffers = teamOffers,
                    )
                }
            }
        }
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, purchases ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, fromQuery = true)
        }
    }

    /** Personal Pro: trial offer if eligible, else the monthly plan. */
    fun launchPro(activity: Activity): String? {
        val c = client ?: return app.getString(R.string.play_unavailable)
        val pd = pro ?: run { refresh(); return app.getString(R.string.product_not_ready) }
        val offers = pd.subscriptionOfferDetails.orEmpty()
        val offer = offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros == 0L }
            ?: offers.firstOrNull { it.offerId == null } ?: return app.getString(R.string.product_not_ready)
        return launch(c, activity, pd, offer.offerToken, null)
    }

    /** Team package for the company (replaces the current package when there is one). */
    fun launchTeam(activity: Activity, offer: TeamOffer): String? {
        val c = client ?: return app.getString(R.string.play_unavailable)
        val pd = team ?: run { refresh(); return app.getString(R.string.product_not_ready) }
        pendingTeamPlan = offer.basePlan
        return launch(c, activity, pd, offer.offerToken, AccountStore.current.teamPurchase?.token)
    }

    private fun launch(c: BillingClient, activity: Activity, pd: ProductDetails, offerToken: String, replaceToken: String?): String? {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).setOfferToken(offerToken).build()))
            .apply {
                AccountStore.current.email?.let { setObfuscatedAccountId(sha256(it)) }
                if (replaceToken != null) setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                        .setOldPurchaseToken(replaceToken)
                        .setSubscriptionReplacementMode(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE)
                        .build()
                )
            }.build()
        val r = c.launchBillingFlow(activity, params)
        return if (r.responseCode == BillingClient.BillingResponseCode.OK) null else app.getString(R.string.play_open_failed, r.responseCode)
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> { handle(purchases.orEmpty(), fromQuery = false); refresh() }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> pendingTeamPlan = null
            else -> main.post { AccountStore.message(app.getString(R.string.purchase_incomplete, r.responseCode)) }
        }
    }

    private fun handle(purchases: List<Purchase>, fromQuery: Boolean) {
        val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        active.filter { !it.isAcknowledged }.forEach { p ->
            client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { ar ->
                Log.i(Cloud.TAG, "acknowledge: ${ar.responseCode}")
            }
        }
        val proP = active.filter { PRO in it.products }
        val teamP = active.firstOrNull { TEAM in it.products }
        main.post {
            if (fromQuery || proP.isNotEmpty()) {
                AccountStore.markVerified()
                AccountStore.update { it.copy(proSubscribed = proP.isNotEmpty(), proAutoRenew = proP.any { p -> p.isAutoRenewing }) }
            }
            if (teamP != null) {
                // which package: the one just bought, or the one the company already has for this token
                val plan = pendingTeamPlan ?: OrgStore.current.org?.planBase?.takeIf { OrgStore.current.org?.planToken == teamP.purchaseToken }
                val seats = seatsOf(plan)
                if (plan != null && seats != null) {
                    AccountStore.update { it.copy(teamPurchase = TeamPurchase(plan, seats, teamP.purchaseToken, teamP.isAutoRenewing)) }
                }
                // the server checks the payment with Google Play and sets the company's devices
                AccountStore.update { it.copy(teamToken = teamP.purchaseToken) }
                pendingTeamPlan = null
            } else if (fromQuery) AccountStore.update { it.copy(teamPurchase = null, teamToken = null) }
        }
    }

    fun manageIntent(product: String): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/account/subscriptions?sku=$product&package=${app.packageName}"),
    )

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
}
