/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.tiles.dialog;

import static com.android.settingslib.mobile.MobileMappings.getIconKey;
import static com.android.settingslib.mobile.MobileMappings.mapIconSets;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.settingslib.DeviceInfoUtils;
import com.android.settingslib.Utils;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.MobileMappings;
import com.android.settingslib.net.SignalStrengthUtil;
import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.AccessPointController;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.wifitrackerlib.MergedCarrierEntry;
import com.android.wifitrackerlib.WifiEntry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;

public class InternetDialogController implements WifiEntry.DisconnectCallback,
        NetworkController.AccessPointController.AccessPointCallback {

    private static final String TAG = "InternetDialogController";
    private static final String ACTION_NETWORK_PROVIDER_SETTINGS =
            "android.settings.NETWORK_PROVIDER_SETTINGS";
    private static final String EXTRA_CHOSEN_WIFI_ENTRY_KEY = "key_chosen_wifientry_key";
    public static final Drawable EMPTY_DRAWABLE = new ColorDrawable(Color.TRANSPARENT);
    public static final int NO_CELL_DATA_TYPE_ICON = 0;
    private static final int SUBTITLE_TEXT_WIFI_IS_OFF = R.string.wifi_is_off;
    private static final int SUBTITLE_TEXT_TAP_A_NETWORK_TO_CONNECT =
            R.string.tap_a_network_to_connect;
    private static final int SUBTITLE_TEXT_UNLOCK_TO_VIEW_NETWORKS =
            R.string.unlock_to_view_networks;
    private static final int SUBTITLE_TEXT_SEARCHING_FOR_NETWORKS =
            R.string.wifi_empty_list_wifi_on;
    private static final int SUBTITLE_TEXT_NON_CARRIER_NETWORK_UNAVAILABLE =
            R.string.non_carrier_network_unavailable;
    private static final int SUBTITLE_TEXT_ALL_CARRIER_NETWORK_UNAVAILABLE =
            R.string.all_network_unavailable;
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private WifiManager mWifiManager;
    private Context mContext;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private ConnectivityManager mConnectivityManager;
    private TelephonyDisplayInfo mTelephonyDisplayInfo =
            new TelephonyDisplayInfo(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE);
    private Handler mHandler;
    private MobileMappings.Config mConfig = null;
    private Executor mExecutor;
    private AccessPointController mAccessPointController;
    private IntentFilter mConnectionStateFilter;
    private InternetDialogCallback mCallback;
    private List<WifiEntry> mWifiEntry;
    private UiEventLogger mUiEventLogger;
    private BroadcastDispatcher mBroadcastDispatcher;
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private GlobalSettings mGlobalSettings;
    private int mDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    @VisibleForTesting
    protected ActivityStarter mActivityStarter;
    @VisibleForTesting
    protected WifiEntry mConnectedEntry;
    @VisibleForTesting
    protected SubscriptionManager.OnSubscriptionsChangedListener mOnSubscriptionsChangedListener;
    @VisibleForTesting
    protected InternetTelephonyCallback mInternetTelephonyCallback;
    @VisibleForTesting
    protected WifiUtils.InternetIconInjector mWifiIconInjector;

    @VisibleForTesting
    KeyguardStateController mKeyguardStateController;

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onRefreshCarrierInfo() {
                    mCallback.onRefreshCarrierInfo();
                }

                @Override
                public void onSimStateChanged(int subId, int slotId, int simState) {
                    mCallback.onSimStateChanged();
                }
            };

    protected List<SubscriptionInfo> getSubscriptionInfo() {
        return mKeyguardUpdateMonitor.getFilteredSubscriptionInfo(false);
    }

    @Inject
    public InternetDialogController(@NonNull Context context, UiEventLogger uiEventLogger,
            ActivityStarter starter, AccessPointController accessPointController,
            SubscriptionManager subscriptionManager, TelephonyManager telephonyManager,
            @Nullable WifiManager wifiManager, ConnectivityManager connectivityManager,
            @Main Handler handler, @Main Executor mainExecutor,
            BroadcastDispatcher broadcastDispatcher, KeyguardUpdateMonitor keyguardUpdateMonitor,
            GlobalSettings globalSettings, KeyguardStateController keyguardStateController) {
        if (DEBUG) {
            Log.d(TAG, "Init InternetDialogController");
        }
        mHandler = handler;
        mExecutor = mainExecutor;
        mContext = context;
        mGlobalSettings = globalSettings;
        mWifiManager = wifiManager;
        mTelephonyManager = telephonyManager;
        mConnectivityManager = connectivityManager;
        mSubscriptionManager = subscriptionManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardStateController = keyguardStateController;
        mConnectionStateFilter = new IntentFilter();
        mConnectionStateFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mConnectionStateFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mConnectionStateFilter.addAction(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        mUiEventLogger = uiEventLogger;
        mActivityStarter = starter;
        mAccessPointController = accessPointController;
        mConfig = MobileMappings.Config.readConfig(mContext);
        mWifiIconInjector = new WifiUtils.InternetIconInjector(mContext);
    }

    void onStart(@NonNull InternetDialogCallback callback) {
        if (DEBUG) {
            Log.d(TAG, "onStart");
        }
        mCallback = callback;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateCallback);
        mAccessPointController.addAccessPointCallback(this);
        mBroadcastDispatcher.registerReceiver(mConnectionStateReceiver, mConnectionStateFilter,
                mExecutor);
        // Listen the subscription changes
        mOnSubscriptionsChangedListener = new InternetOnSubscriptionChangedListener();
        mSubscriptionManager.addOnSubscriptionsChangedListener(mExecutor,
                mOnSubscriptionsChangedListener);
        mDefaultDataSubId = getDefaultDataSubscriptionId();
        if (DEBUG) {
            Log.d(TAG, "Init, SubId: " + mDefaultDataSubId);
        }
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(mDefaultDataSubId);
        mInternetTelephonyCallback = new InternetTelephonyCallback();
        mTelephonyManager.registerTelephonyCallback(mExecutor, mInternetTelephonyCallback);
        // Listen the connectivity changes
        mConnectivityManager.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(), new DataConnectivityListener(), mHandler);
        scanWifiAccessPoints();
    }

    void onStop() {
        if (DEBUG) {
            Log.d(TAG, "onStop");
        }
        mBroadcastDispatcher.unregisterReceiver(mConnectionStateReceiver);
        mTelephonyManager.unregisterTelephonyCallback(mInternetTelephonyCallback);
        mSubscriptionManager.removeOnSubscriptionsChangedListener(
                mOnSubscriptionsChangedListener);
        mAccessPointController.removeAccessPointCallback(this);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateCallback);
    }

    @VisibleForTesting
    boolean isAirplaneModeEnabled() {
        return mGlobalSettings.getInt(Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    @VisibleForTesting
    protected int getDefaultDataSubscriptionId() {
        return mSubscriptionManager.getDefaultDataSubscriptionId();
    }

    @VisibleForTesting
    protected Intent getSettingsIntent() {
        return new Intent(ACTION_NETWORK_PROVIDER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    protected Intent getWifiDetailsSettingsIntent() {
        String key = mConnectedEntry == null ? null : mConnectedEntry.getKey();
        if (TextUtils.isEmpty(key)) {
            if (DEBUG) {
                Log.d(TAG, "connected entry's key is empty");
            }
            return null;
        }
        return WifiUtils.getWifiDetailsSettingsIntent(key);
    }

    CharSequence getDialogTitleText() {
        if (isAirplaneModeEnabled()) {
            return mContext.getText(R.string.airplane_mode);
        }
        return mContext.getText(R.string.quick_settings_internet_label);
    }

    CharSequence getSubtitleText(boolean isProgressBarVisible) {
        if (isAirplaneModeEnabled()) {
            return null;
        }

        if (!mWifiManager.isWifiEnabled()) {
            // When the airplane mode is off and Wi-Fi is disabled.
            //   Sub-Title: Wi-Fi is off
            if (DEBUG) {
                Log.d(TAG, "Airplane mode off + Wi-Fi off.");
            }
            return mContext.getText(SUBTITLE_TEXT_WIFI_IS_OFF);
        }

        if (isDeviceLocked()) {
            // When the device is locked.
            //   Sub-Title: Unlock to view networks
            if (DEBUG) {
                Log.d(TAG, "The device is locked.");
            }
            return mContext.getText(SUBTITLE_TEXT_UNLOCK_TO_VIEW_NETWORKS);
        }

        final List<ScanResult> wifiList = mWifiManager.getScanResults();
        if (wifiList != null && wifiList.size() != 0) {
            return mContext.getText(SUBTITLE_TEXT_TAP_A_NETWORK_TO_CONNECT);
        }

        if (isProgressBarVisible) {
            // When the Wi-Fi scan result callback is received
            //   Sub-Title: Searching for networks...
            return mContext.getText(SUBTITLE_TEXT_SEARCHING_FOR_NETWORKS);
        }

        // Sub-Title:
        // show non_carrier_network_unavailable
        //   - while Wi-Fi on + no Wi-Fi item
        //   - while Wi-Fi on + no Wi-Fi item + mobile data off
        // show all_network_unavailable:
        //   - while Wi-Fi on + no Wi-Fi item + no carrier item
        //   - while Wi-Fi on + no Wi-Fi item + service is out of service
        //   - while Wi-Fi on + no Wi-Fi item + mobile data on + no carrier data.
        if (DEBUG) {
            Log.d(TAG, "No Wi-Fi item.");
        }
        if (!hasCarrier() || (!isVoiceStateInService() && !isDataStateInService())) {
            if (DEBUG) {
                Log.d(TAG, "No carrier or service is out of service.");
            }
            return mContext.getText(SUBTITLE_TEXT_ALL_CARRIER_NETWORK_UNAVAILABLE);
        }

        if (!isMobileDataEnabled()) {
            if (DEBUG) {
                Log.d(TAG, "Mobile data off");
            }
            return mContext.getText(SUBTITLE_TEXT_NON_CARRIER_NETWORK_UNAVAILABLE);
        }

        if (!activeNetworkIsCellular()) {
            if (DEBUG) {
                Log.d(TAG, "No carrier data.");
            }
            return mContext.getText(SUBTITLE_TEXT_ALL_CARRIER_NETWORK_UNAVAILABLE);
        }

        return mContext.getText(SUBTITLE_TEXT_NON_CARRIER_NETWORK_UNAVAILABLE);
    }

    Drawable getConnectedWifiDrawable(@NonNull WifiEntry wifiEntry) {
        final Drawable drawable =
                mWifiIconInjector.getIcon(false /* noInternet*/, wifiEntry.getLevel());
        if (drawable == null) {
            return null;
        }
        drawable.setTint(mContext.getColor(R.color.connected_network_primary_color));
        return drawable;
    }

    boolean isNightMode() {
        return (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    Drawable getSignalStrengthDrawable() {
        Drawable drawable = mContext.getDrawable(
                R.drawable.ic_signal_strength_zero_bar_no_internet);
        try {
            if (mTelephonyManager == null) {
                if (DEBUG) {
                    Log.d(TAG, "TelephonyManager is null");
                }
                return drawable;
            }

            if (isDataStateInService() || isVoiceStateInService()) {
                AtomicReference<Drawable> shared = new AtomicReference<>();
                shared.set(getSignalStrengthDrawableWithLevel());
                drawable = shared.get();
            }

            drawable.setTint(activeNetworkIsCellular() ? mContext.getColor(
                    R.color.connected_network_primary_color) : Utils.getColorAttrDefaultColor(
                    mContext, android.R.attr.textColorTertiary));
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return drawable;
    }

    /**
     * To get the signal bar icon with level.
     *
     * @return The Drawable which is a signal bar icon with level.
     */
    Drawable getSignalStrengthDrawableWithLevel() {
        final SignalStrength strength = mTelephonyManager.getSignalStrength();
        int level = (strength == null) ? 0 : strength.getLevel();
        int numLevels = SignalStrength.NUM_SIGNAL_STRENGTH_BINS;
        if (mSubscriptionManager != null && shouldInflateSignalStrength(mDefaultDataSubId)) {
            level += 1;
            numLevels += 1;
        }
        return getSignalStrengthIcon(mContext, level, numLevels, NO_CELL_DATA_TYPE_ICON, false);
    }

    Drawable getSignalStrengthIcon(Context context, int level, int numLevels,
            int iconType, boolean cutOut) {
        Log.d(TAG, "getSignalStrengthIcon");
        final SignalDrawable signalDrawable = new SignalDrawable(context);
        signalDrawable.setLevel(
                SignalDrawable.getState(level, numLevels, cutOut));

        // Make the network type drawable
        final Drawable networkDrawable =
                iconType == NO_CELL_DATA_TYPE_ICON
                        ? EMPTY_DRAWABLE
                        : context.getResources().getDrawable(iconType, context.getTheme());

        // Overlay the two drawables
        final Drawable[] layers = {networkDrawable, signalDrawable};
        final int iconSize =
                context.getResources().getDimensionPixelSize(R.dimen.signal_strength_icon_size);

        final LayerDrawable icons = new LayerDrawable(layers);
        // Set the network type icon at the top left
        icons.setLayerGravity(0 /* index of networkDrawable */, Gravity.TOP | Gravity.LEFT);
        // Set the signal strength icon at the bottom right
        icons.setLayerGravity(1 /* index of SignalDrawable */, Gravity.BOTTOM | Gravity.RIGHT);
        icons.setLayerSize(1 /* index of SignalDrawable */, iconSize, iconSize);
        icons.setTintList(Utils.getColorAttr(context, android.R.attr.textColorTertiary));
        return icons;
    }

    private boolean shouldInflateSignalStrength(int subId) {
        return SignalStrengthUtil.shouldInflateSignalStrength(mContext, subId);
    }

    private CharSequence getUniqueSubscriptionDisplayName(int subscriptionId, Context context) {
        final Map<Integer, CharSequence> displayNames = getUniqueSubscriptionDisplayNames(context);
        return displayNames.getOrDefault(subscriptionId, "");
    }

    private Map<Integer, CharSequence> getUniqueSubscriptionDisplayNames(Context context) {
        class DisplayInfo {
            public SubscriptionInfo subscriptionInfo;
            public CharSequence originalName;
            public CharSequence uniqueName;
        }

        // Map of SubscriptionId to DisplayName
        final Supplier<Stream<DisplayInfo>> originalInfos =
                () -> getSubscriptionInfo()
                        .stream()
                        .filter(i -> {
                            // Filter out null values.
                            return (i != null && i.getDisplayName() != null);
                        })
                        .map(i -> {
                            DisplayInfo info = new DisplayInfo();
                            info.subscriptionInfo = i;
                            info.originalName = i.getDisplayName().toString().trim();
                            return info;
                        });

        // A Unique set of display names
        Set<CharSequence> uniqueNames = new HashSet<>();
        // Return the set of duplicate names
        final Set<CharSequence> duplicateOriginalNames = originalInfos.get()
                .filter(info -> !uniqueNames.add(info.originalName))
                .map(info -> info.originalName)
                .collect(Collectors.toSet());

        // If a display name is duplicate, append the final 4 digits of the phone number.
        // Creates a mapping of Subscription id to original display name + phone number display name
        final Supplier<Stream<DisplayInfo>> uniqueInfos = () -> originalInfos.get().map(info -> {
            if (duplicateOriginalNames.contains(info.originalName)) {
                // This may return null, if the user cannot view the phone number itself.
                final String phoneNumber = DeviceInfoUtils.getBidiFormattedPhoneNumber(context,
                        info.subscriptionInfo);
                String lastFourDigits = "";
                if (phoneNumber != null) {
                    lastFourDigits = (phoneNumber.length() > 4)
                            ? phoneNumber.substring(phoneNumber.length() - 4) : phoneNumber;
                }

                if (TextUtils.isEmpty(lastFourDigits)) {
                    info.uniqueName = info.originalName;
                } else {
                    info.uniqueName = info.originalName + " " + lastFourDigits;
                }

            } else {
                info.uniqueName = info.originalName;
            }
            return info;
        });

        // Check uniqueness a second time.
        // We might not have had permission to view the phone numbers.
        // There might also be multiple phone numbers whose last 4 digits the same.
        uniqueNames.clear();
        final Set<CharSequence> duplicatePhoneNames = uniqueInfos.get()
                .filter(info -> !uniqueNames.add(info.uniqueName))
                .map(info -> info.uniqueName)
                .collect(Collectors.toSet());

        return uniqueInfos.get().map(info -> {
            if (duplicatePhoneNames.contains(info.uniqueName)) {
                info.uniqueName = info.originalName + " "
                        + info.subscriptionInfo.getSubscriptionId();
            }
            return info;
        }).collect(Collectors.toMap(
                info -> info.subscriptionInfo.getSubscriptionId(),
                info -> info.uniqueName));
    }

    CharSequence getMobileNetworkTitle() {
        return getUniqueSubscriptionDisplayName(mDefaultDataSubId, mContext);
    }

    String getMobileNetworkSummary() {
        String description = getNetworkTypeDescription(mContext, mConfig,
                mTelephonyDisplayInfo, mDefaultDataSubId);
        return getMobileSummary(mContext, mTelephonyManager, description);
    }

    /**
     * Get currently description of mobile network type.
     */
    private String getNetworkTypeDescription(Context context, MobileMappings.Config config,
            TelephonyDisplayInfo telephonyDisplayInfo, int subId) {
        String iconKey = getIconKey(telephonyDisplayInfo);

        if (mapIconSets(config) == null || mapIconSets(config).get(iconKey) == null) {
            if (DEBUG) {
                Log.d(TAG, "The description of network type is empty.");
            }
            return "";
        }

        int resId = mapIconSets(config).get(iconKey).dataContentDescription;
        return resId != 0
                ? SubscriptionManager.getResourcesForSubId(context, subId).getString(resId) : "";
    }

    private String getMobileSummary(Context context, TelephonyManager telephonyManager,
            String networkTypeDescription) {
        if (!isMobileDataEnabled()) {
            return context.getString(R.string.mobile_data_off_summary);
        }
        if (!isDataStateInService()) {
            return context.getString(R.string.mobile_data_no_connection);
        }
        String summary = networkTypeDescription;
        if (activeNetworkIsCellular()) {
            summary = context.getString(R.string.preference_summary_default_combination,
                    context.getString(R.string.mobile_data_connection_active),
                    networkTypeDescription);
        }
        return summary;
    }

    String getDefaultWifiTitle() {
        if (getDefaultWifiEntry() == null) {
            if (DEBUG) {
                Log.d(TAG, "connected entry is null");
            }
            return "";
        }
        return getDefaultWifiEntry().getTitle();
    }

    String getDefaultWifiSummary() {
        if (getDefaultWifiEntry() == null) {
            if (DEBUG) {
                Log.d(TAG, "connected entry is null");
            }
            return "";
        }
        return getDefaultWifiEntry().getSummary(false);
    }

    void launchNetworkSetting() {
        mCallback.dismissDialog();
        mActivityStarter.postStartActivityDismissingKeyguard(getSettingsIntent(), 0);
    }

    void launchWifiNetworkDetailsSetting() {
        Intent intent = getWifiDetailsSettingsIntent();
        if (intent != null) {
            mCallback.dismissDialog();
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
        }
    }

    void connectCarrierNetwork() {
        final MergedCarrierEntry mergedCarrierEntry =
                mAccessPointController.getMergedCarrierEntry();
        if (mergedCarrierEntry != null && mergedCarrierEntry.canConnect()) {
            mergedCarrierEntry.connect(null /* ConnectCallback */);
        }
    }

    List<WifiEntry> getWifiEntryList() {
        return mWifiEntry;
    }

    WifiEntry getDefaultWifiEntry() {
        if (mConnectedEntry != null && mConnectedEntry.isDefaultNetwork()) {
            return mConnectedEntry;
        }
        return null;
    }

    WifiManager getWifiManager() {
        return mWifiManager;
    }

    TelephonyManager getTelephonyManager() {
        return mTelephonyManager;
    }

    SubscriptionManager getSubscriptionManager() {
        return mSubscriptionManager;
    }

    /**
     * @return whether there is the carrier item in the slice.
     */
    boolean hasCarrier() {
        if (mSubscriptionManager == null) {
            if (DEBUG) {
                Log.d(TAG, "SubscriptionManager is null, can not check carrier.");
            }
            return false;
        }

        if (isAirplaneModeEnabled() || mTelephonyManager == null
                || mSubscriptionManager.getActiveSubscriptionIdList().length <= 0) {
            return false;
        }
        return true;
    }

    /**
     * Return {@code true} if mobile data is enabled
     */
    boolean isMobileDataEnabled() {
        if (mTelephonyManager == null || !mTelephonyManager.isDataEnabled()) {
            return false;
        }
        return true;
    }

    /**
     * Set whether to enable data for {@code subId}, also whether to disable data for other
     * subscription
     */
    void setMobileDataEnabled(Context context, int subId, boolean enabled,
            boolean disableOtherSubscriptions) {
        if (mTelephonyManager == null) {
            if (DEBUG) {
                Log.d(TAG, "TelephonyManager is null, can not set mobile data.");
            }
            return;
        }

        if (mSubscriptionManager == null) {
            if (DEBUG) {
                Log.d(TAG, "SubscriptionManager is null, can not set mobile data.");
            }
            return;
        }

        mTelephonyManager.setDataEnabled(enabled);
        if (disableOtherSubscriptions) {
            final List<SubscriptionInfo> subInfoList =
                    mSubscriptionManager.getActiveSubscriptionInfoList();
            if (subInfoList != null) {
                for (SubscriptionInfo subInfo : subInfoList) {
                    // We never disable mobile data for opportunistic subscriptions.
                    if (subInfo.getSubscriptionId() != subId && !subInfo.isOpportunistic()) {
                        context.getSystemService(TelephonyManager.class).createForSubscriptionId(
                                subInfo.getSubscriptionId()).setDataEnabled(false);
                    }
                }
            }
        }
    }

    boolean isDataStateInService() {
        final ServiceState serviceState = mTelephonyManager.getServiceState();
        NetworkRegistrationInfo regInfo =
                (serviceState == null) ? null : serviceState.getNetworkRegistrationInfo(
                        NetworkRegistrationInfo.DOMAIN_PS,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        return (regInfo == null) ? false : regInfo.isRegistered();
    }

    boolean isVoiceStateInService() {
        if (mTelephonyManager == null) {
            if (DEBUG) {
                Log.d(TAG, "TelephonyManager is null, can not detect voice state.");
            }
            return false;
        }

        final ServiceState serviceState = mTelephonyManager.getServiceState();
        return serviceState != null
                && serviceState.getState() == serviceState.STATE_IN_SERVICE;
    }

    public boolean isDeviceLocked() {
        return !mKeyguardStateController.isUnlocked();
    }

    boolean activeNetworkIsCellular() {
        if (mConnectivityManager == null) {
            if (DEBUG) {
                Log.d(TAG, "ConnectivityManager is null, can not check active network.");
            }
            return false;
        }

        final Network activeNetwork = mConnectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        final NetworkCapabilities networkCapabilities =
                mConnectivityManager.getNetworkCapabilities(activeNetwork);
        if (networkCapabilities == null) {
            return false;
        }
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    boolean connect(WifiEntry ap) {
        if (ap == null) {
            if (DEBUG) {
                Log.d(TAG, "No Wi-Fi ap to connect.");
            }
            return false;
        }

        if (ap.getWifiConfiguration() != null) {
            if (DEBUG) {
                Log.d(TAG, "connect networkId=" + ap.getWifiConfiguration().networkId);
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "connect to unsaved network " + ap.getTitle());
            }
        }
        ap.connect(new WifiEntryConnectCallback(mActivityStarter, mContext, ap));
        return false;
    }

    static class WifiEntryConnectCallback implements WifiEntry.ConnectCallback {
        final ActivityStarter mActivityStarter;
        final Context mContext;
        final WifiEntry mWifiEntry;

        WifiEntryConnectCallback(ActivityStarter activityStarter, Context context,
                WifiEntry connectWifiEntry) {
            mActivityStarter = activityStarter;
            mContext = context;
            mWifiEntry = connectWifiEntry;
        }

        @Override
        public void onConnectResult(@ConnectStatus int status) {
            if (DEBUG) {
                Log.d(TAG, "onConnectResult " + status);
            }

            if (status == WifiEntry.ConnectCallback.CONNECT_STATUS_FAILURE_NO_CONFIG) {
                final Intent intent = new Intent("com.android.settings.WIFI_DIALOG")
                        .putExtra(EXTRA_CHOSEN_WIFI_ENTRY_KEY, mWifiEntry.getKey());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mActivityStarter.startActivity(intent, true);
            } else if (status == CONNECT_STATUS_FAILURE_UNKNOWN) {
                Toast.makeText(mContext, R.string.wifi_failed_connect_message,
                        Toast.LENGTH_SHORT).show();
            } else {
                if (DEBUG) {
                    Log.d(TAG, "connect failure reason=" + status);
                }
            }
        }
    }

    void scanWifiAccessPoints() {
        mAccessPointController.scanForAccessPoints();
    }

    @Override
    public void onAccessPointsChanged(List<WifiEntry> accessPoints) {
        if (accessPoints == null) {
            return;
        }

        boolean hasConnectedWifi = false;
        mWifiEntry = accessPoints;
        for (WifiEntry wifiEntry : accessPoints) {
            if (wifiEntry.getConnectedState() == WifiEntry.CONNECTED_STATE_CONNECTED) {
                mConnectedEntry = wifiEntry;
                hasConnectedWifi = true;
                break;
            }
        }
        if (!hasConnectedWifi) {
            mConnectedEntry = null;
        }

        mCallback.onAccessPointsChanged(mWifiEntry, getDefaultWifiEntry());
    }

    @Override
    public void onSettingsActivityTriggered(Intent settingsIntent) {
    }

    @Override
    public void onDisconnectResult(int status) {
    }

    private class InternetTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.DataConnectionStateListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener {

        @Override
        public void onServiceStateChanged(@NonNull ServiceState serviceState) {
            mCallback.onServiceStateChanged(serviceState);
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            mCallback.onDataConnectionStateChanged(state, networkType);
        }

        @Override
        public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
            mCallback.onSignalStrengthsChanged(signalStrength);
        }

        @Override
        public void onDisplayInfoChanged(@NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
            mTelephonyDisplayInfo = telephonyDisplayInfo;
            mCallback.onDisplayInfoChanged(telephonyDisplayInfo);
        }
    }

    private class InternetOnSubscriptionChangedListener
            extends SubscriptionManager.OnSubscriptionsChangedListener {
        InternetOnSubscriptionChangedListener() {
            super();
        }

        @Override
        public void onSubscriptionsChanged() {
            updateListener();
        }
    }

    private class DataConnectivityListener extends ConnectivityManager.NetworkCallback {
        @Override
        public void onCapabilitiesChanged(@NonNull Network network,
                @NonNull NetworkCapabilities networkCapabilities) {
            final Network activeNetwork = mConnectivityManager.getActiveNetwork();
            if (activeNetwork != null && activeNetwork.equals(network)) {
                // update UI
                mCallback.onCapabilitiesChanged(network, networkCapabilities);
            }
        }
    }

    private final BroadcastReceiver mConnectionStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                    || action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                mCallback.onWifiStateReceived(context, intent);
            }

            if (action.equals(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)) {
                if (DEBUG) {
                    Log.d(TAG, "ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
                }
                updateListener();
            }
        }
    };

    private void updateListener() {
        int defaultDataSubId = getDefaultDataSubscriptionId();
        if (mDefaultDataSubId == getDefaultDataSubscriptionId()) {
            if (DEBUG) {
                Log.d(TAG, "DDS: no change");
            }
            return;
        }

        mDefaultDataSubId = defaultDataSubId;
        if (DEBUG) {
            Log.d(TAG, "DDS: defaultDataSubId:" + mDefaultDataSubId);
        }
        if (SubscriptionManager.isUsableSubscriptionId(mDefaultDataSubId)) {
            mTelephonyManager.unregisterTelephonyCallback(mInternetTelephonyCallback);
            mTelephonyManager = mTelephonyManager.createForSubscriptionId(mDefaultDataSubId);
            mTelephonyManager.registerTelephonyCallback(mHandler::post,
                    mInternetTelephonyCallback);
            mCallback.onSubscriptionsChanged(mDefaultDataSubId);
        }
    }

    public WifiUtils.InternetIconInjector getWifiIconInjector() {
        return mWifiIconInjector;
    }

    interface InternetDialogCallback {

        void onRefreshCarrierInfo();

        void onSimStateChanged();

        void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities);

        void onSubscriptionsChanged(int defaultDataSubId);

        void onServiceStateChanged(ServiceState serviceState);

        void onDataConnectionStateChanged(int state, int networkType);

        void onSignalStrengthsChanged(SignalStrength signalStrength);

        void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo);

        void dismissDialog();

        void onAccessPointsChanged(List<WifiEntry> wifiEntryList, WifiEntry connectedEntry);

        void onWifiStateReceived(Context context, Intent intent);
    }
}
