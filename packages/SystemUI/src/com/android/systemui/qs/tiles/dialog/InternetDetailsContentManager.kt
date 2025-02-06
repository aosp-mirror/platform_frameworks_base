/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles.dialog

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyDisplayInfo
import android.text.Html
import android.text.Layout
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.telephony.flags.Flags
import com.android.settingslib.satellite.SatelliteDialogUtils.TYPE_IS_WIFI
import com.android.settingslib.satellite.SatelliteDialogUtils.mayStartSatelliteWarningDialog
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils
import com.android.systemui.Prefs
import com.android.systemui.accessibility.floatingmenu.AnnotationLinkSpan
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.wifitrackerlib.WifiEntry
import com.google.common.annotations.VisibleForTesting
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * View content for the Internet tile details that handles all UI interactions and state management.
 */
class InternetDetailsContentManager
@AssistedInject
constructor(
    private val internetDetailsContentController: InternetDetailsContentController,
    @Assisted(CAN_CONFIG_MOBILE_DATA) private val canConfigMobileData: Boolean,
    @Assisted(CAN_CONFIG_WIFI) private val canConfigWifi: Boolean,
    @Assisted private val coroutineScope: CoroutineScope,
    @Assisted private var context: Context,
    private val uiEventLogger: UiEventLogger,
    @Main private val handler: Handler,
    @Background private val backgroundExecutor: Executor,
    private val keyguard: KeyguardStateController,
) {
    // Lifecycle
    private lateinit var lifecycleRegistry: LifecycleRegistry
    @VisibleForTesting internal var lifecycleOwner: LifecycleOwner? = null
    @VisibleForTesting internal val internetContentData = MutableLiveData<InternetContent>()
    @VisibleForTesting internal var connectedWifiEntry: WifiEntry? = null
    @VisibleForTesting internal var isProgressBarVisible = false

    // UI Components
    private lateinit var contentView: View
    private lateinit var divider: View
    private lateinit var progressBar: ProgressBar
    private lateinit var ethernetLayout: LinearLayout
    private lateinit var mobileNetworkLayout: LinearLayout
    private var secondaryMobileNetworkLayout: LinearLayout? = null
    private lateinit var turnWifiOnLayout: LinearLayout
    private lateinit var wifiToggleTitleTextView: TextView
    private lateinit var wifiScanNotifyLayout: LinearLayout
    private lateinit var wifiScanNotifyTextView: TextView
    private lateinit var connectedWifiListLayout: LinearLayout
    private lateinit var connectedWifiIcon: ImageView
    private lateinit var connectedWifiTitleTextView: TextView
    private lateinit var connectedWifiSummaryTextView: TextView
    private lateinit var wifiSettingsIcon: ImageView
    private lateinit var wifiRecyclerView: RecyclerView
    private lateinit var seeAllLayout: LinearLayout
    private lateinit var signalIcon: ImageView
    private lateinit var mobileTitleTextView: TextView
    private lateinit var mobileSummaryTextView: TextView
    private lateinit var airplaneModeSummaryTextView: TextView
    private lateinit var mobileDataToggle: Switch
    private lateinit var mobileToggleDivider: View
    private lateinit var wifiToggle: Switch
    private lateinit var shareWifiButton: Button
    private lateinit var airplaneModeButton: Button
    private var alertDialog: AlertDialog? = null

    private val canChangeWifiState =
        WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(context)
    private var wifiNetworkHeight = 0
    private var backgroundOn: Drawable? = null
    private var backgroundOff: Drawable? = null
    private var clickJob: Job? = null
    private var defaultDataSubId = internetDetailsContentController.defaultDataSubscriptionId
    @VisibleForTesting
    internal var adapter = InternetAdapter(internetDetailsContentController, coroutineScope)
    @VisibleForTesting internal var wifiEntriesCount: Int = 0
    @VisibleForTesting internal var hasMoreWifiEntries: Boolean = false

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted(CAN_CONFIG_MOBILE_DATA) canConfigMobileData: Boolean,
            @Assisted(CAN_CONFIG_WIFI) canConfigWifi: Boolean,
            coroutineScope: CoroutineScope,
            context: Context,
        ): InternetDetailsContentManager
    }

    /**
     * Binds the content manager to the provided content view.
     *
     * This method initializes the lifecycle, views, click listeners, and UI of the details content.
     * It also updates the UI with the current Wi-Fi network information.
     *
     * @param contentView The view to which the content manager should be bound.
     */
    fun bind(contentView: View) {
        if (DEBUG) {
            Log.d(TAG, "Bind InternetDetailsContentManager")
        }

        this.contentView = contentView

        initializeLifecycle()
        initializeViews()
        updateDetailsUI(getStartingInternetContent())
        initializeAndConfigure()
    }

    /**
     * Initializes the LifecycleRegistry if it hasn't been initialized yet. It sets the initial
     * state of the LifecycleRegistry to Lifecycle.State.CREATED.
     */
    fun initializeLifecycle() {
        if (!::lifecycleRegistry.isInitialized) {
            lifecycleOwner =
                object : LifecycleOwner {
                    override val lifecycle: Lifecycle
                        get() = lifecycleRegistry
                }
            lifecycleRegistry = LifecycleRegistry(lifecycleOwner!!)
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    private fun initializeViews() {
        // Set accessibility properties
        contentView.accessibilityPaneTitle =
            context.getText(R.string.accessibility_desc_quick_settings)

        // Get dimension resources
        wifiNetworkHeight =
            context.resources.getDimensionPixelSize(R.dimen.internet_dialog_wifi_network_height)

        // Initialize LiveData observer
        internetContentData.observe(lifecycleOwner!!) { internetContent ->
            updateDetailsUI(internetContent)
        }

        // Network layouts
        divider = contentView.requireViewById(R.id.divider)
        progressBar = contentView.requireViewById(R.id.wifi_searching_progress)

        // Set wifi, mobile and ethernet layouts
        setWifiLayout()
        setMobileLayout()
        ethernetLayout = contentView.requireViewById(R.id.ethernet_layout)

        // Share WiFi
        shareWifiButton = contentView.requireViewById(R.id.share_wifi_button)
        shareWifiButton.setOnClickListener { view ->
            if (
                internetDetailsContentController.mayLaunchShareWifiSettings(
                    connectedWifiEntry,
                    view,
                )
            ) {
                uiEventLogger.log(InternetDetailsEvent.SHARE_WIFI_QS_BUTTON_CLICKED)
            }
        }

        // Airplane mode
        airplaneModeButton = contentView.requireViewById(R.id.apm_button)
        airplaneModeButton.setOnClickListener {
            internetDetailsContentController.setAirplaneModeDisabled()
        }
        airplaneModeSummaryTextView = contentView.requireViewById(R.id.airplane_mode_summary)

        // Background drawables
        backgroundOn = context.getDrawable(R.drawable.settingslib_switch_bar_bg_on)
        backgroundOff = context.getDrawable(R.drawable.internet_dialog_selected_effect)

        // Done button is only visible for the dialog view
        contentView.findViewById<Button>(R.id.done_button).apply { visibility = View.GONE }

        // Title and subtitle will be added in the `TileDetails`
        contentView.findViewById<TextView>(R.id.internet_dialog_title).apply {
            visibility = View.GONE
        }
        contentView.findViewById<TextView>(R.id.internet_dialog_subtitle).apply {
            visibility = View.GONE
        }
    }

    private fun setWifiLayout() {
        // Initialize Wi-Fi related views
        turnWifiOnLayout = contentView.requireViewById(R.id.turn_on_wifi_layout)
        wifiToggleTitleTextView = contentView.requireViewById(R.id.wifi_toggle_title)
        wifiScanNotifyLayout = contentView.requireViewById(R.id.wifi_scan_notify_layout)
        wifiScanNotifyTextView = contentView.requireViewById(R.id.wifi_scan_notify_text)
        connectedWifiListLayout = contentView.requireViewById(R.id.wifi_connected_layout)
        connectedWifiIcon = contentView.requireViewById(R.id.wifi_connected_icon)
        connectedWifiTitleTextView = contentView.requireViewById(R.id.wifi_connected_title)
        connectedWifiSummaryTextView = contentView.requireViewById(R.id.wifi_connected_summary)
        wifiSettingsIcon = contentView.requireViewById(R.id.wifi_settings_icon)
        wifiToggle = contentView.requireViewById(R.id.wifi_toggle)
        wifiRecyclerView =
            contentView.requireViewById<RecyclerView>(R.id.wifi_list_layout).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@InternetDetailsContentManager.adapter
            }
        seeAllLayout = contentView.requireViewById(R.id.see_all_layout)

        // Set click listeners for Wi-Fi related views
        wifiToggle.setOnClickListener {
            val isChecked = wifiToggle.isChecked
            handleWifiToggleClicked(isChecked)
        }
        connectedWifiListLayout.setOnClickListener(this::onClickConnectedWifi)
        seeAllLayout.setOnClickListener(this::onClickSeeMoreButton)
    }

    private fun setMobileLayout() {
        // Initialize mobile data related views
        mobileNetworkLayout = contentView.requireViewById(R.id.mobile_network_layout)
        signalIcon = contentView.requireViewById(R.id.signal_icon)
        mobileTitleTextView = contentView.requireViewById(R.id.mobile_title)
        mobileSummaryTextView = contentView.requireViewById(R.id.mobile_summary)
        mobileDataToggle = contentView.requireViewById(R.id.mobile_toggle)
        mobileToggleDivider = contentView.requireViewById(R.id.mobile_toggle_divider)

        // Set click listeners for mobile data related views
        mobileNetworkLayout.setOnClickListener {
            val autoSwitchNonDdsSubId: Int =
                internetDetailsContentController.getActiveAutoSwitchNonDdsSubId()
            if (autoSwitchNonDdsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                showTurnOffAutoDataSwitchDialog(autoSwitchNonDdsSubId)
            }
            internetDetailsContentController.connectCarrierNetwork()
        }

        // Mobile data toggle
        mobileDataToggle.setOnClickListener {
            val isChecked = mobileDataToggle.isChecked
            if (!isChecked && shouldShowMobileDialog()) {
                mobileDataToggle.isChecked = true
                showTurnOffMobileDialog()
            } else if (internetDetailsContentController.isMobileDataEnabled != isChecked) {
                internetDetailsContentController.setMobileDataEnabled(
                    context,
                    defaultDataSubId,
                    isChecked,
                    false,
                )
            }
        }
    }

    /**
     * This function ensures the component is in the RESUMED state and sets up the internet details
     * content controller.
     *
     * If the component is already in the RESUMED state, this function does nothing.
     */
    fun initializeAndConfigure() {
        // If the current state is RESUMED, it's already initialized.
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            return
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        internetDetailsContentController.onStart(internetDetailsCallback, canConfigWifi)
        if (!canConfigWifi) {
            hideWifiViews()
        }
    }

    fun getTitleText(): String {
        return internetDetailsContentController.getDialogTitleText().toString()
    }

    fun getSubtitleText(): String {
        return internetDetailsContentController.getSubtitleText(isProgressBarVisible).toString()
    }

    private fun updateDetailsUI(internetContent: InternetContent) {
        if (DEBUG) {
            Log.d(TAG, "updateDetailsUI ")
        }

        airplaneModeButton.visibility =
            if (internetContent.isAirplaneModeEnabled) View.VISIBLE else View.GONE

        updateEthernetUI(internetContent)
        updateMobileUI(internetContent)
        updateWifiUI(internetContent)
    }

    private fun getStartingInternetContent(): InternetContent {
        return InternetContent(
            isWifiEnabled = internetDetailsContentController.isWifiEnabled,
            isDeviceLocked = internetDetailsContentController.isDeviceLocked,
        )
    }

    @VisibleForTesting
    internal fun hideWifiViews() {
        setProgressBarVisible(false)
        turnWifiOnLayout.visibility = View.GONE
        connectedWifiListLayout.visibility = View.GONE
        wifiRecyclerView.visibility = View.GONE
        seeAllLayout.visibility = View.GONE
        shareWifiButton.visibility = View.GONE
    }

    private fun setProgressBarVisible(visible: Boolean) {
        if (isProgressBarVisible == visible) {
            return
        }

        // Set the indeterminate value from false to true each time to ensure that the progress bar
        // resets its animation and starts at the leftmost starting point each time it is displayed.
        isProgressBarVisible = visible
        progressBar.visibility = if (visible) View.VISIBLE else View.GONE
        progressBar.isIndeterminate = visible
        divider.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun showTurnOffAutoDataSwitchDialog(subId: Int) {
        var carrierName: CharSequence? = getMobileNetworkTitle(defaultDataSubId)
        if (TextUtils.isEmpty(carrierName)) {
            carrierName = getDefaultCarrierName()
        }
        alertDialog =
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.auto_data_switch_disable_title, carrierName))
                .setMessage(R.string.auto_data_switch_disable_message)
                .setNegativeButton(R.string.auto_data_switch_dialog_negative_button) { _, _ -> }
                .setPositiveButton(R.string.auto_data_switch_dialog_positive_button) { _, _ ->
                    internetDetailsContentController.setAutoDataSwitchMobileDataPolicy(
                        subId,
                        /* enable= */ false,
                    )
                    secondaryMobileNetworkLayout?.visibility = View.GONE
                }
                .create()
        alertDialog!!.window?.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
        SystemUIDialog.setShowForAllUsers(alertDialog, true)
        SystemUIDialog.registerDismissListener(alertDialog)
        SystemUIDialog.setWindowOnTop(alertDialog, keyguard.isShowing())
        alertDialog!!.show()
    }

    private fun shouldShowMobileDialog(): Boolean {
        val mobileDataTurnedOff =
            Prefs.getBoolean(context, Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA, false)
        return internetDetailsContentController.isMobileDataEnabled && !mobileDataTurnedOff
    }

    private fun getMobileNetworkTitle(subId: Int): CharSequence {
        return internetDetailsContentController.getMobileNetworkTitle(subId)
    }

    private fun showTurnOffMobileDialog() {
        val context = contentView.context
        var carrierName: CharSequence? = getMobileNetworkTitle(defaultDataSubId)
        val isInService: Boolean =
            internetDetailsContentController.isVoiceStateInService(defaultDataSubId)
        if (TextUtils.isEmpty(carrierName) || !isInService) {
            carrierName = getDefaultCarrierName()
        }
        alertDialog =
            AlertDialog.Builder(context)
                .setTitle(R.string.mobile_data_disable_title)
                .setMessage(context.getString(R.string.mobile_data_disable_message, carrierName))
                .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int -> }
                .setPositiveButton(
                    com.android.internal.R.string.alert_windows_notification_turn_off_action
                ) { _: DialogInterface?, _: Int ->
                    internetDetailsContentController.setMobileDataEnabled(
                        context,
                        defaultDataSubId,
                        false,
                        false,
                    )
                    mobileDataToggle.isChecked = false
                    Prefs.putBoolean(context, Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA, true)
                }
                .create()
        alertDialog!!.window?.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
        SystemUIDialog.setShowForAllUsers(alertDialog, true)
        SystemUIDialog.registerDismissListener(alertDialog)
        SystemUIDialog.setWindowOnTop(alertDialog, keyguard.isShowing())

        alertDialog!!.show()
    }

    private fun onClickConnectedWifi(view: View?) {
        if (connectedWifiEntry == null) {
            return
        }
        internetDetailsContentController.launchWifiDetailsSetting(connectedWifiEntry!!.key, view)
    }

    private fun onClickSeeMoreButton(view: View?) {
        internetDetailsContentController.launchNetworkSetting(view)
    }

    private fun handleWifiToggleClicked(isChecked: Boolean) {
        if (Flags.oemEnabledSatelliteFlag()) {
            if (clickJob != null && !clickJob!!.isCompleted) {
                return
            }
            clickJob =
                mayStartSatelliteWarningDialog(contentView.context, coroutineScope, TYPE_IS_WIFI) {
                    isAllowClick: Boolean ->
                    if (isAllowClick) {
                        setWifiEnabled(isChecked)
                    } else {
                        wifiToggle.isChecked = !isChecked
                    }
                }
            return
        }
        setWifiEnabled(isChecked)
    }

    private fun setWifiEnabled(isEnabled: Boolean) {
        if (internetDetailsContentController.isWifiEnabled == isEnabled) {
            return
        }
        internetDetailsContentController.isWifiEnabled = isEnabled
    }

    @MainThread
    private fun updateEthernetUI(internetContent: InternetContent) {
        ethernetLayout.visibility = if (internetContent.hasEthernet) View.VISIBLE else View.GONE
    }

    private fun updateWifiUI(internetContent: InternetContent) {
        if (!canConfigWifi) {
            return
        }

        updateWifiToggle(internetContent)
        updateConnectedWifi(internetContent)
        updateWifiListAndSeeAll(internetContent)
        updateWifiScanNotify(internetContent)
    }

    private fun updateMobileUI(internetContent: InternetContent) {
        if (!internetContent.shouldUpdateMobileNetwork) {
            return
        }

        val isNetworkConnected =
            internetContent.activeNetworkIsCellular || internetContent.isCarrierNetworkActive
        // 1. Mobile network should be gone if airplane mode ON or the list of active
        //    subscriptionId is null.
        // 2. Carrier network should be gone if airplane mode ON and Wi-Fi is OFF.
        if (DEBUG) {
            Log.d(
                TAG,
                /*msg = */ "updateMobileUI, isCarrierNetworkActive = " +
                    internetContent.isCarrierNetworkActive,
            )
        }

        if (
            !internetContent.hasActiveSubIdOnDds &&
                (!internetContent.isWifiEnabled || !internetContent.isCarrierNetworkActive)
        ) {
            mobileNetworkLayout.visibility = View.GONE
            secondaryMobileNetworkLayout?.visibility = View.GONE
            return
        }

        mobileNetworkLayout.visibility = View.VISIBLE
        mobileDataToggle.setChecked(internetDetailsContentController.isMobileDataEnabled)
        mobileTitleTextView.text = getMobileNetworkTitle(defaultDataSubId)
        val summary = getMobileNetworkSummary(defaultDataSubId)
        if (!TextUtils.isEmpty(summary)) {
            mobileSummaryTextView.text = Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY)
            mobileSummaryTextView.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            mobileSummaryTextView.visibility = View.VISIBLE
        } else {
            mobileSummaryTextView.visibility = View.GONE
        }
        backgroundExecutor.execute {
            val drawable = getSignalStrengthDrawable(defaultDataSubId)
            handler.post { signalIcon.setImageDrawable(drawable) }
        }

        mobileDataToggle.visibility = if (canConfigMobileData) View.VISIBLE else View.INVISIBLE
        mobileToggleDivider.visibility = if (canConfigMobileData) View.VISIBLE else View.INVISIBLE
        val primaryColor =
            if (isNetworkConnected) R.color.connected_network_primary_color
            else R.color.disconnected_network_primary_color
        mobileToggleDivider.setBackgroundColor(context.getColor(primaryColor))

        // Display the info for the non-DDS if it's actively being used
        val autoSwitchNonDdsSubId: Int = internetContent.activeAutoSwitchNonDdsSubId

        val nonDdsVisibility =
            if (autoSwitchNonDdsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) View.VISIBLE
            else View.GONE

        val secondaryRes =
            if (isNetworkConnected) R.style.TextAppearance_InternetDialog_Secondary_Active
            else R.style.TextAppearance_InternetDialog_Secondary
        if (nonDdsVisibility == View.VISIBLE) {
            // non DDS is the currently active sub, set primary visual for it
            setNonDDSActive(autoSwitchNonDdsSubId)
        } else {
            mobileNetworkLayout.background = if (isNetworkConnected) backgroundOn else backgroundOff
            mobileTitleTextView.setTextAppearance(
                if (isNetworkConnected) R.style.TextAppearance_InternetDialog_Active
                else R.style.TextAppearance_InternetDialog
            )
            mobileSummaryTextView.setTextAppearance(secondaryRes)
        }

        secondaryMobileNetworkLayout?.visibility = nonDdsVisibility

        // Set airplane mode to the summary for carrier network
        if (internetContent.isAirplaneModeEnabled) {
            airplaneModeSummaryTextView.apply {
                visibility = View.VISIBLE
                text = context.getText(R.string.airplane_mode)
                setTextAppearance(secondaryRes)
            }
        } else {
            airplaneModeSummaryTextView.visibility = View.GONE
        }
    }

    private fun setNonDDSActive(autoSwitchNonDdsSubId: Int) {
        val stub: ViewStub = contentView.findViewById(R.id.secondary_mobile_network_stub)
        stub.inflate()
        secondaryMobileNetworkLayout =
            contentView.findViewById(R.id.secondary_mobile_network_layout)
        secondaryMobileNetworkLayout?.setOnClickListener { view: View? ->
            this.onClickConnectedSecondarySub(view)
        }
        secondaryMobileNetworkLayout?.background = backgroundOn

        contentView.requireViewById<TextView>(R.id.secondary_mobile_title).apply {
            text = getMobileNetworkTitle(autoSwitchNonDdsSubId)
            setTextAppearance(R.style.TextAppearance_InternetDialog_Active)
        }

        val summary = getMobileNetworkSummary(autoSwitchNonDdsSubId)
        contentView.requireViewById<TextView>(R.id.secondary_mobile_summary).apply {
            if (!TextUtils.isEmpty(summary)) {
                text = Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY)
                breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                setTextAppearance(R.style.TextAppearance_InternetDialog_Active)
            }
        }

        val secondarySignalIcon: ImageView = contentView.requireViewById(R.id.secondary_signal_icon)
        backgroundExecutor.execute {
            val drawable = getSignalStrengthDrawable(autoSwitchNonDdsSubId)
            handler.post { secondarySignalIcon.setImageDrawable(drawable) }
        }

        contentView.requireViewById<ImageView>(R.id.secondary_settings_icon).apply {
            setColorFilter(context.getColor(R.color.connected_network_primary_color))
        }

        // set secondary visual for default data sub
        mobileNetworkLayout.background = backgroundOff
        mobileTitleTextView.setTextAppearance(R.style.TextAppearance_InternetDialog)
        mobileSummaryTextView.setTextAppearance(R.style.TextAppearance_InternetDialog_Secondary)
        signalIcon.setColorFilter(context.getColor(R.color.connected_network_secondary_color))
    }

    @MainThread
    private fun updateWifiToggle(internetContent: InternetContent) {
        if (wifiToggle.isChecked != internetContent.isWifiEnabled) {
            wifiToggle.isChecked = internetContent.isWifiEnabled
        }
        if (internetContent.isDeviceLocked) {
            wifiToggleTitleTextView.setTextAppearance(
                if ((connectedWifiEntry != null)) R.style.TextAppearance_InternetDialog_Active
                else R.style.TextAppearance_InternetDialog
            )
        }
        turnWifiOnLayout.background =
            if ((internetContent.isDeviceLocked && connectedWifiEntry != null)) backgroundOn
            else null

        if (!canChangeWifiState && wifiToggle.isEnabled) {
            wifiToggle.isEnabled = false
            wifiToggleTitleTextView.isEnabled = false
            contentView.requireViewById<TextView>(R.id.wifi_toggle_summary).apply {
                isEnabled = false
                visibility = View.VISIBLE
            }
        }
    }

    @MainThread
    private fun updateConnectedWifi(internetContent: InternetContent) {
        if (
            !internetContent.isWifiEnabled ||
                connectedWifiEntry == null ||
                internetContent.isDeviceLocked
        ) {
            connectedWifiListLayout.visibility = View.GONE
            shareWifiButton.visibility = View.GONE
            return
        }
        connectedWifiListLayout.visibility = View.VISIBLE
        connectedWifiTitleTextView.text = connectedWifiEntry!!.title
        connectedWifiSummaryTextView.text = connectedWifiEntry!!.getSummary(false)
        connectedWifiIcon.setImageDrawable(
            internetDetailsContentController.getInternetWifiDrawable(connectedWifiEntry!!)
        )
        wifiSettingsIcon.setColorFilter(context.getColor(R.color.connected_network_primary_color))

        val canShareWifi =
            internetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                connectedWifiEntry
            ) != null
        shareWifiButton.visibility = if (canShareWifi) View.VISIBLE else View.GONE

        secondaryMobileNetworkLayout?.visibility = View.GONE
    }

    @MainThread
    private fun updateWifiListAndSeeAll(internetContent: InternetContent) {
        if (!internetContent.isWifiEnabled || internetContent.isDeviceLocked) {
            wifiRecyclerView.visibility = View.GONE
            seeAllLayout.visibility = View.GONE
            return
        }
        val wifiListMaxCount = getWifiListMaxCount()
        if (adapter.itemCount > wifiListMaxCount) {
            hasMoreWifiEntries = true
        }
        adapter.setMaxEntriesCount(wifiListMaxCount)
        val wifiListMinHeight = wifiNetworkHeight * wifiListMaxCount
        if (wifiRecyclerView.minimumHeight != wifiListMinHeight) {
            wifiRecyclerView.minimumHeight = wifiListMinHeight
        }
        wifiRecyclerView.visibility = View.VISIBLE
        seeAllLayout.visibility = if (hasMoreWifiEntries) View.VISIBLE else View.INVISIBLE
    }

    @MainThread
    private fun updateWifiScanNotify(internetContent: InternetContent) {
        if (
            internetContent.isWifiEnabled ||
                !internetContent.isWifiScanEnabled ||
                internetContent.isDeviceLocked
        ) {
            wifiScanNotifyLayout.visibility = View.GONE
            return
        }

        if (TextUtils.isEmpty(wifiScanNotifyTextView.text)) {
            val linkInfo =
                AnnotationLinkSpan.LinkInfo(AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION) {
                    view: View? ->
                    internetDetailsContentController.launchWifiScanningSetting(view)
                }
            wifiScanNotifyTextView.text =
                AnnotationLinkSpan.linkify(
                    context.getText(R.string.wifi_scan_notify_message),
                    linkInfo,
                )
            wifiScanNotifyTextView.movementMethod = LinkMovementMethod.getInstance()
        }
        wifiScanNotifyLayout.visibility = View.VISIBLE
    }

    @VisibleForTesting
    @MainThread
    internal fun getWifiListMaxCount(): Int {
        // Use the maximum count of networks to calculate the remaining count for Wi-Fi networks.
        var count = MAX_NETWORK_COUNT
        if (ethernetLayout.visibility == View.VISIBLE) {
            count -= 1
        }
        if (mobileNetworkLayout.visibility == View.VISIBLE) {
            count -= 1
        }

        // If the remaining count is greater than the maximum count of the Wi-Fi network, the
        // maximum count of the Wi-Fi network is used.
        if (count > InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT) {
            count = InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT
        }
        if (connectedWifiListLayout.visibility == View.VISIBLE) {
            count -= 1
        }
        return count
    }

    private fun getMobileNetworkSummary(subId: Int): String {
        return internetDetailsContentController.getMobileNetworkSummary(subId)
    }

    /** For DSDS auto data switch */
    private fun onClickConnectedSecondarySub(view: View?) {
        internetDetailsContentController.launchMobileNetworkSettings(view)
    }

    private fun getSignalStrengthDrawable(subId: Int): Drawable {
        return internetDetailsContentController.getSignalStrengthDrawable(subId)
    }

    /**
     * Unbinds all listeners and resources associated with the view. This method should be called
     * when the view is no longer needed.
     */
    fun unBind() {
        if (DEBUG) {
            Log.d(TAG, "unBind")
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        mobileNetworkLayout.setOnClickListener(null)
        connectedWifiListLayout.setOnClickListener(null)
        secondaryMobileNetworkLayout?.setOnClickListener(null)
        seeAllLayout.setOnClickListener(null)
        wifiToggle.setOnCheckedChangeListener(null)
        shareWifiButton.setOnClickListener(null)
        airplaneModeButton.setOnClickListener(null)
        internetDetailsContentController.onStop()
    }

    /**
     * Update the internet details content when receiving the callback.
     *
     * @param shouldUpdateMobileNetwork `true` for update the mobile network layout, otherwise
     *   `false`.
     */
    @VisibleForTesting
    internal fun updateContent(shouldUpdateMobileNetwork: Boolean) {
        backgroundExecutor.execute {
            internetContentData.postValue(getInternetContent(shouldUpdateMobileNetwork))
        }
    }

    private fun getInternetContent(shouldUpdateMobileNetwork: Boolean): InternetContent {
        return InternetContent(
            shouldUpdateMobileNetwork = shouldUpdateMobileNetwork,
            activeNetworkIsCellular =
                if (shouldUpdateMobileNetwork)
                    internetDetailsContentController.activeNetworkIsCellular()
                else false,
            isCarrierNetworkActive =
                if (shouldUpdateMobileNetwork)
                    internetDetailsContentController.isCarrierNetworkActive()
                else false,
            isAirplaneModeEnabled = internetDetailsContentController.isAirplaneModeEnabled,
            hasEthernet = internetDetailsContentController.hasEthernet(),
            isWifiEnabled = internetDetailsContentController.isWifiEnabled,
            hasActiveSubIdOnDds = internetDetailsContentController.hasActiveSubIdOnDds(),
            isDeviceLocked = internetDetailsContentController.isDeviceLocked,
            isWifiScanEnabled = internetDetailsContentController.isWifiScanEnabled(),
            activeAutoSwitchNonDdsSubId =
                internetDetailsContentController.getActiveAutoSwitchNonDdsSubId(),
        )
    }

    /**
     * Handles window focus changes. If the activity loses focus and the system UI dialog is
     * showing, it dismisses the current alert dialog to prevent it from persisting in the
     * background.
     *
     * @param dialog The internet system UI dialog whose focus state has changed.
     * @param hasFocus True if the window has gained focus, false otherwise.
     */
    fun onWindowFocusChanged(dialog: SystemUIDialog, hasFocus: Boolean) {
        if (alertDialog != null && !alertDialog!!.isShowing) {
            if (!hasFocus && dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }

    private fun getDefaultCarrierName(): String? {
        return context.getString(R.string.mobile_data_disable_message_default_carrier)
    }

    @VisibleForTesting
    internal val internetDetailsCallback =
        object : InternetDetailsContentController.InternetDialogCallback {
            override fun onRefreshCarrierInfo() {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onSimStateChanged() {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            @WorkerThread
            override fun onCapabilitiesChanged(
                network: Network?,
                networkCapabilities: NetworkCapabilities?,
            ) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            @WorkerThread
            override fun onLost(network: Network) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onSubscriptionsChanged(dataSubId: Int) {
                defaultDataSubId = dataSubId
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onServiceStateChanged(serviceState: ServiceState?) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            @WorkerThread
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onUserMobileDataStateChanged(enabled: Boolean) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo?) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun onCarrierNetworkChange(active: Boolean) {
                updateContent(shouldUpdateMobileNetwork = true)
            }

            override fun dismissDialog() {
                if (DEBUG) {
                    Log.d(TAG, "dismissDialog")
                }
                // TODO: b/377388104 Close details view
            }

            override fun onAccessPointsChanged(
                wifiEntries: MutableList<WifiEntry>?,
                connectedEntry: WifiEntry?,
                ifHasMoreWifiEntries: Boolean,
            ) {
                // Should update the carrier network layout when it is connected under airplane
                // mode ON.
                val shouldUpdateCarrierNetwork =
                    (mobileNetworkLayout.visibility == View.VISIBLE) &&
                        internetDetailsContentController.isAirplaneModeEnabled
                handler.post {
                    connectedWifiEntry = connectedEntry
                    wifiEntriesCount = wifiEntries?.size ?: 0
                    hasMoreWifiEntries = ifHasMoreWifiEntries
                    updateContent(shouldUpdateCarrierNetwork)
                    adapter.setWifiEntries(wifiEntries, wifiEntriesCount)
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onWifiScan(isScan: Boolean) {
                setProgressBarVisible(isScan)
            }
        }

    enum class InternetDetailsEvent(private val id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The Internet details became visible on the screen.")
        INTERNET_DETAILS_VISIBLE(2071),
        @UiEvent(doc = "The share wifi button is clicked.") SHARE_WIFI_QS_BUTTON_CLICKED(1462);

        override fun getId(): Int {
            return id
        }
    }

    @VisibleForTesting
    data class InternetContent(
        val isAirplaneModeEnabled: Boolean = false,
        val hasEthernet: Boolean = false,
        val shouldUpdateMobileNetwork: Boolean = false,
        val activeNetworkIsCellular: Boolean = false,
        val isCarrierNetworkActive: Boolean = false,
        val isWifiEnabled: Boolean = false,
        val hasActiveSubIdOnDds: Boolean = false,
        val isDeviceLocked: Boolean = false,
        val isWifiScanEnabled: Boolean = false,
        val activeAutoSwitchNonDdsSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    )

    companion object {
        private const val TAG = "InternetDetailsContent"
        private val DEBUG: Boolean = Log.isLoggable(TAG, Log.DEBUG)
        private const val MAX_NETWORK_COUNT = 4
        const val CAN_CONFIG_MOBILE_DATA = "can_config_mobile_data"
        const val CAN_CONFIG_WIFI = "can_config_wifi"
    }
}
