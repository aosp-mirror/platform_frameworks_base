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

import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;

import static com.android.systemui.Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.Utils;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;

import androidx.annotation.VisibleForTesting;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Dialog for showing mobile network, connected Wi-Fi network and Wi-Fi networks.
 */
@SysUISingleton
public class InternetDialog extends SystemUIDialog implements
        InternetDialogController.InternetDialogCallback, Window.Callback {
    private static final String TAG = "InternetDialog";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private final Handler mHandler;
    private final LinearLayoutManager mLayoutManager;
    private final Runnable mHideProgressBarRunnable = () -> {
        setProgressBarVisible(false);
    };

    @VisibleForTesting
    protected InternetAdapter mAdapter;
    @VisibleForTesting
    protected WifiManager mWifiManager;
    @VisibleForTesting
    protected View mDialogView;

    private InternetDialogFactory mInternetDialogFactory;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private AlertDialog mAlertDialog;
    private UiEventLogger mUiEventLogger;
    private Context mContext;
    private InternetDialogController mInternetDialogController;
    private TextView mInternetDialogTitle;
    private TextView mInternetDialogSubTitle;
    private ProgressBar mProgressBar;
    private LinearLayout mInternetListLayout;
    private LinearLayout mConnectedWifListLayout;
    private LinearLayout mConnectedWifList;
    private LinearLayout mMobileNetworkLayout;
    private LinearLayout mMobileNetworkList;
    private LinearLayout mTurnWifiOnLayout;
    private LinearLayout mSeeAllLayout;
    private Space mSpace;
    private RecyclerView mWifiRecyclerView;
    private ImageView mConnectedWifiIcon;
    private ImageView mWifiSettingsIcon;
    private TextView mConnectedWifiTitleText;
    private TextView mConnectedWifiSummaryText;
    private ImageView mSignalIcon;
    private TextView mMobileTitleText;
    private TextView mMobileSummaryText;
    private Switch mMobileDataToggle;
    private Switch mWiFiToggle;
    private Button mDoneButton;
    private Drawable mBackgroundOn;
    private WifiEntry mConnectedWifiEntry;
    private int mListMaxHeight;
    private int mDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private boolean mIsProgressBarVisible;

    private final ViewTreeObserver.OnGlobalLayoutListener mInternetListLayoutListener = () -> {
        // Set max height for list
        if (mInternetListLayout.getHeight() > mListMaxHeight) {
            ViewGroup.LayoutParams params = mInternetListLayout.getLayoutParams();
            params.height = mListMaxHeight;
            mInternetListLayout.setLayoutParams(params);
        }
    };

    public InternetDialog(Context context, InternetDialogFactory internetDialogFactory,
            InternetDialogController internetDialogController,
            boolean aboveStatusBar, UiEventLogger uiEventLogger, @Main Handler handler) {
        super(context, R.style.Theme_SystemUI_Dialog_Internet);
        if (DEBUG) {
            Log.d(TAG, "Init InternetDialog");
        }
        mContext = context;
        mHandler = handler;
        mInternetDialogFactory = internetDialogFactory;
        mInternetDialogController = internetDialogController;
        mSubscriptionManager = mInternetDialogController.getSubscriptionManager();
        mDefaultDataSubId = mInternetDialogController.getDefaultDataSubscriptionId();
        mTelephonyManager = mInternetDialogController.getTelephonyManager();
        mWifiManager = mInternetDialogController.getWifiManager();

        mLayoutManager = new LinearLayoutManager(mContext) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        };
        mListMaxHeight = context.getResources().getDimensionPixelSize(
                R.dimen.internet_dialog_list_max_height);
        mUiEventLogger = uiEventLogger;
        mAdapter = new InternetAdapter(mInternetDialogController);
        if (!aboveStatusBar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        show();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }
        mUiEventLogger.log(InternetDialogEvent.INTERNET_DIALOG_SHOW);
        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.internet_connectivity_dialog,
                null);
        final Window window = getWindow();
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.BOTTOM;
        lp.setFitInsetsTypes(statusBars() | navigationBars());
        lp.setFitInsetsSides(WindowInsets.Side.all());
        lp.setFitInsetsIgnoringVisibility(true);
        window.setAttributes(lp);
        window.setContentView(mDialogView);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setWindowAnimations(R.style.Animation_InternetDialog);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        mInternetDialogTitle = mDialogView.requireViewById(R.id.internet_dialog_title);
        mInternetDialogSubTitle = mDialogView.requireViewById(R.id.internet_dialog_subtitle);
        mProgressBar = mDialogView.requireViewById(R.id.wifi_searching_progress);
        mInternetListLayout = mDialogView.requireViewById(R.id.internet_list);
        mMobileNetworkLayout = mDialogView.requireViewById(R.id.mobile_network_layout);
        mMobileNetworkList = mDialogView.requireViewById(R.id.mobile_network_list);
        mTurnWifiOnLayout = mDialogView.requireViewById(R.id.turn_on_wifi_layout);
        mConnectedWifListLayout = mDialogView.requireViewById(R.id.wifi_connected_layout);
        mConnectedWifList = mDialogView.requireViewById(R.id.wifi_connected_list);
        mConnectedWifiIcon = mDialogView.requireViewById(R.id.wifi_connected_icon);
        mConnectedWifiTitleText = mDialogView.requireViewById(R.id.wifi_connected_title);
        mConnectedWifiSummaryText = mDialogView.requireViewById(R.id.wifi_connected_summary);
        mWifiSettingsIcon = mDialogView.requireViewById(R.id.wifi_settings_icon);
        mWifiRecyclerView = mDialogView.requireViewById(R.id.wifi_list_layout);
        mSeeAllLayout = mDialogView.requireViewById(R.id.see_all_layout);
        mSpace = mDialogView.requireViewById(R.id.space);
        mDoneButton = mDialogView.requireViewById(R.id.done);
        mSignalIcon = mDialogView.requireViewById(R.id.signal_icon);
        mMobileTitleText = mDialogView.requireViewById(R.id.mobile_title);
        mMobileSummaryText = mDialogView.requireViewById(R.id.mobile_summary);
        mMobileDataToggle = mDialogView.requireViewById(R.id.mobile_toggle);
        mWiFiToggle = mDialogView.requireViewById(R.id.wifi_toggle);
        mBackgroundOn = mContext.getDrawable(R.drawable.settingslib_switch_bar_bg_on);
        mInternetListLayout.getViewTreeObserver().addOnGlobalLayoutListener(
                mInternetListLayoutListener);
        mInternetDialogTitle.setText(getDialogTitleText());
        mInternetDialogTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        setOnClickListener();
        mTurnWifiOnLayout.setBackground(null);
        mWifiRecyclerView.setLayoutManager(mLayoutManager);
        mWifiRecyclerView.setAdapter(mAdapter);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (DEBUG) {
            Log.d(TAG, "onStart");
        }
        mInternetDialogController.onStart(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (DEBUG) {
            Log.d(TAG, "onStop");
        }
        mHandler.removeCallbacks(mHideProgressBarRunnable);
        mMobileNetworkLayout.setOnClickListener(null);
        mMobileDataToggle.setOnCheckedChangeListener(null);
        mConnectedWifListLayout.setOnClickListener(null);
        mSeeAllLayout.setOnClickListener(null);
        mWiFiToggle.setOnCheckedChangeListener(null);
        mDoneButton.setOnClickListener(null);
        mInternetDialogController.onStop();
        mInternetDialogFactory.destroyDialog();
    }

    @Override
    public void dismissDialog() {
        if (DEBUG) {
            Log.d(TAG, "dismissDialog");
        }
        mInternetDialogFactory.destroyDialog();
        dismiss();
    }

    void updateDialog() {
        if (DEBUG) {
            Log.d(TAG, "updateDialog");
        }
        if (mInternetDialogController.isAirplaneModeEnabled()) {
            mInternetDialogSubTitle.setVisibility(View.GONE);
        } else {
            mInternetDialogSubTitle.setText(getSubtitleText());
        }
        showProgressBar();
        setMobileDataLayout(mInternetDialogController.activeNetworkIsCellular());
        setConnectedWifiLayout();
        boolean isWifiEnabled = mWifiManager.isWifiEnabled();
        mWiFiToggle.setChecked(isWifiEnabled);
        int visible = isWifiEnabled ? View.VISIBLE : View.GONE;
        mWifiRecyclerView.setVisibility(visible);
        mAdapter.notifyDataSetChanged();
        mSeeAllLayout.setVisibility(visible);
        mSpace.setVisibility(isWifiEnabled ? View.GONE : View.VISIBLE);
    }

    private void setOnClickListener() {
        mMobileNetworkLayout.setOnClickListener(v -> {
            if (mInternetDialogController.isMobileDataEnabled()) {
                if (!mInternetDialogController.activeNetworkIsCellular()) {
                    mInternetDialogController.connectCarrierNetwork();
                }
            }
        });
        mMobileDataToggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (!isChecked && shouldShowMobileDialog()) {
                        showTurnOffMobileDialog();
                    } else if (!shouldShowMobileDialog()) {
                        mInternetDialogController.setMobileDataEnabled(mContext, mDefaultDataSubId,
                                isChecked, false);
                    }
                });
        mConnectedWifListLayout.setOnClickListener(v -> {
            // TODO(b/191475923): Need to launch the detailed page of Wi-Fi entry.
        });
        mSeeAllLayout.setOnClickListener(v -> onClickSeeMoreButton());
        mWiFiToggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    buttonView.setChecked(isChecked);
                    mWifiManager.setWifiEnabled(isChecked);
                    mSpace.setVisibility(isChecked ? View.GONE : View.VISIBLE);
                });
        mDoneButton.setOnClickListener(v -> dismiss());
    }

    private void setMobileDataLayout(boolean isCellularNetwork) {
        if (mInternetDialogController.isAirplaneModeEnabled()
                || !mInternetDialogController.hasCarrier()) {
            mMobileNetworkLayout.setVisibility(View.GONE);
        } else {
            mMobileDataToggle.setChecked(mInternetDialogController.isMobileDataEnabled());
            mMobileNetworkLayout.setVisibility(View.VISIBLE);
            mMobileTitleText.setText(getMobileNetworkTitle());
            mMobileSummaryText.setText(
                    Html.fromHtml(getMobileNetworkSummary(), Html.FROM_HTML_MODE_LEGACY));
            mSignalIcon.setImageDrawable(getSignalStrengthDrawable());
            int titleColor = isCellularNetwork ? mContext.getColor(
                    R.color.connected_network_primary_color) : Utils.getColorAttrDefaultColor(
                    mContext, android.R.attr.textColorPrimary);
            int summaryColor = isCellularNetwork ? mContext.getColor(
                    R.color.connected_network_tertiary_color) : Utils.getColorAttrDefaultColor(
                    mContext, android.R.attr.textColorTertiary);
            mMobileTitleText.setTextColor(titleColor);
            mMobileSummaryText.setTextColor(summaryColor);
            mMobileNetworkLayout.setBackground(isCellularNetwork ? mBackgroundOn : null);
        }
    }

    private void setConnectedWifiLayout() {
        if (!mWifiManager.isWifiEnabled()
                || mInternetDialogController.getConnectedWifiEntry() == null) {
            mConnectedWifListLayout.setBackground(null);
            mConnectedWifListLayout.setVisibility(View.GONE);
            return;
        }
        mConnectedWifListLayout.setVisibility(View.VISIBLE);
        mConnectedWifiTitleText.setText(getConnectedWifiTitle());
        mConnectedWifiSummaryText.setText(getConnectedWifiSummary());
        mConnectedWifiIcon.setImageDrawable(getConnectedWifiDrawable());
        mConnectedWifiTitleText.setTextColor(
                mContext.getColor(R.color.connected_network_primary_color));
        mConnectedWifiSummaryText.setTextColor(
                mContext.getColor(R.color.connected_network_tertiary_color));
        mWifiSettingsIcon.setColorFilter(
                mContext.getColor(R.color.connected_network_primary_color));
        mConnectedWifListLayout.setBackground(mBackgroundOn);
    }

    void onClickSeeMoreButton() {
        mInternetDialogController.launchNetworkSetting();
    }

    CharSequence getDialogTitleText() {
        return mInternetDialogController.getDialogTitleText();
    }

    CharSequence getSubtitleText() {
        return mInternetDialogController.getSubtitleText(mIsProgressBarVisible);
    }

    private Drawable getConnectedWifiDrawable() {
        try {
            return mInternetDialogController.getWifiConnectedDrawable(mConnectedWifiEntry);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    private Drawable getSignalStrengthDrawable() {
        return mInternetDialogController.getSignalStrengthDrawable();
    }

    CharSequence getMobileNetworkTitle() {
        return mInternetDialogController.getMobileNetworkTitle();
    }

    String getMobileNetworkSummary() {
        return mInternetDialogController.getMobileNetworkSummary();
    }

    String getConnectedWifiTitle() {
        return mInternetDialogController.getConnectedWifiTitle();
    }

    String getConnectedWifiSummary() {
        return mInternetDialogController.getConnectedWifiSummary();
    }

    private void showProgressBar() {
        if (mWifiManager == null || !mWifiManager.isWifiEnabled()) {
            setProgressBarVisible(false);
            return;
        }
        setProgressBarVisible(true);
        List<ScanResult> wifiScanResults = mWifiManager.getScanResults();
        if (wifiScanResults != null && wifiScanResults.size() > 0) {
            mContext.getMainThreadHandler().postDelayed(mHideProgressBarRunnable,
                    2000 /* delay millis */);
        }
    }

    private void setProgressBarVisible(boolean visible) {
        if (mWifiManager.isWifiEnabled() && mAdapter.mHolderView != null
                && mAdapter.mHolderView.isAttachedToWindow()) {
            mIsProgressBarVisible = true;
        }
        mIsProgressBarVisible = visible;
        mProgressBar.setVisibility(mIsProgressBarVisible ? View.VISIBLE : View.INVISIBLE);
        mInternetDialogSubTitle.setText(getSubtitleText());
    }

    private boolean shouldShowMobileDialog() {
        boolean flag = Prefs.getBoolean(mContext, QS_HAS_TURNED_OFF_MOBILE_DATA,
                false);
        if (mInternetDialogController.isMobileDataEnabled() && !flag) {
            return true;
        }
        return false;
    }

    private void showTurnOffMobileDialog() {
        CharSequence carrierName =
                mSubscriptionManager.getDefaultDataSubscriptionInfo().getCarrierName();
        boolean isInService = mInternetDialogController.isVoiceStateInService();
        if (TextUtils.isEmpty(carrierName) || !isInService) {
            carrierName = mContext.getString(R.string.mobile_data_disable_message_default_carrier);
        }
        mAlertDialog = new Builder(mContext)
                .setTitle(R.string.mobile_data_disable_title)
                .setMessage(mContext.getString(R.string.mobile_data_disable_message, carrierName))
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    mMobileDataToggle.setChecked(true);
                })
                .setPositiveButton(
                        com.android.internal.R.string.alert_windows_notification_turn_off_action,
                        (d, w) -> {
                            mInternetDialogController.setMobileDataEnabled(mContext,
                                    mDefaultDataSubId, false, false);
                            mMobileDataToggle.setChecked(false);
                            Prefs.putBoolean(mContext, QS_HAS_TURNED_OFF_MOBILE_DATA, true);
                        })
                .create();
        mAlertDialog.setOnCancelListener(dialog -> mMobileDataToggle.setChecked(true));
        mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(mAlertDialog, true);
        SystemUIDialog.registerDismissListener(mAlertDialog);
        SystemUIDialog.setWindowOnTop(mAlertDialog);
        mAlertDialog.show();
    }

    @Override
    public void onRefreshCarrierInfo() {
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onSimStateChanged() {
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onSubscriptionsChanged(int defaultDataSubId) {
        mDefaultDataSubId = defaultDataSubId;
        mTelephonyManager = mTelephonyManager.createForSubscriptionId(mDefaultDataSubId);
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onServiceStateChanged(ServiceState serviceState) {
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onDataConnectionStateChanged(int state, int networkType) {
        mAdapter.notifyDataSetChanged();
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onAccessPointsChanged(List<WifiEntry> wifiEntryList, WifiEntry connectedEntry) {
        mConnectedWifiEntry = connectedEntry;
        mAdapter.notifyDataSetChanged();
        mHandler.post(() -> updateDialog());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mAlertDialog != null && !mAlertDialog.isShowing()) {
            if (!hasFocus && isShowing()) {
                dismiss();
            }
        }
    }

    @Override
    public void onWifiStateReceived(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
            mInternetDialogController.scanWifiAccessPoints();
            showProgressBar();
            return;
        }

        if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            mHandler.post(() -> updateDialog());
        }
    }

    public enum InternetDialogEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The Internet dialog became visible on the screen.")
        INTERNET_DIALOG_SHOW(843);

        private final int mId;

        InternetDialogEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
