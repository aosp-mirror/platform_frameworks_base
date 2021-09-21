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

import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

import static com.android.systemui.Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.wifitrackerlib.WifiEntry;

import java.util.List;

/**
 * Dialog for showing mobile network, connected Wi-Fi network and Wi-Fi networks.
 */
@SysUISingleton
public class InternetDialog extends SystemUIDialog implements
        InternetDialogController.InternetDialogCallback, Window.Callback {
    private static final String TAG = "InternetDialog";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final long PROGRESS_DELAY_MS = 2000L;

    private final Handler mHandler;
    private final LinearLayoutManager mLayoutManager;

    @VisibleForTesting
    protected InternetAdapter mAdapter;
    @VisibleForTesting
    protected WifiManager mWifiManager;
    @VisibleForTesting
    protected View mDialogView;
    @VisibleForTesting
    protected boolean mCanConfigWifi;

    private InternetDialogFactory mInternetDialogFactory;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private AlertDialog mAlertDialog;
    private UiEventLogger mUiEventLogger;
    private Context mContext;
    private InternetDialogController mInternetDialogController;
    private TextView mInternetDialogTitle;
    private TextView mInternetDialogSubTitle;
    private View mDivider;
    private ProgressBar mProgressBar;
    private LinearLayout mInternetDialogLayout;
    private LinearLayout mConnectedWifListLayout;
    private LinearLayout mMobileNetworkLayout;
    private LinearLayout mTurnWifiOnLayout;
    private LinearLayout mEthernetLayout;
    private TextView mWifiToggleTitleText;
    private LinearLayout mSeeAllLayout;
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
    private int mListMaxHeight;
    private int mDefaultDataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private boolean mCanConfigMobileData;

    // Wi-Fi entries
    protected WifiEntry mConnectedWifiEntry;
    protected int mWifiEntriesCount;

    // Wi-Fi scanning progress bar
    protected boolean mIsProgressBarVisible;
    protected boolean mIsSearchingHidden;
    protected final Runnable mHideProgressBarRunnable = () -> {
        setProgressBarVisible(false);
    };
    protected Runnable mHideSearchingRunnable = () -> {
        mIsSearchingHidden = true;
        mInternetDialogSubTitle.setText(getSubtitleText());
    };

    private final ViewTreeObserver.OnGlobalLayoutListener mInternetListLayoutListener = () -> {
        // Set max height for list
        if (mInternetDialogLayout.getHeight() > mListMaxHeight) {
            ViewGroup.LayoutParams params = mInternetDialogLayout.getLayoutParams();
            params.height = mListMaxHeight;
            mInternetDialogLayout.setLayoutParams(params);
        }
    };

    public InternetDialog(Context context, InternetDialogFactory internetDialogFactory,
            InternetDialogController internetDialogController, boolean canConfigMobileData,
            boolean canConfigWifi, boolean aboveStatusBar, UiEventLogger uiEventLogger,
            @Main Handler handler) {
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
        mCanConfigMobileData = canConfigMobileData;
        mCanConfigWifi = canConfigWifi;

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
        final WindowManager.LayoutParams layoutParams = window.getAttributes();
        layoutParams.gravity = Gravity.BOTTOM;
        // Move down the dialog to overlay the navigation bar.
        layoutParams.setFitInsetsTypes(
                layoutParams.getFitInsetsTypes() & ~WindowInsets.Type.navigationBars());
        layoutParams.setFitInsetsSides(WindowInsets.Side.all());
        layoutParams.setFitInsetsIgnoringVisibility(true);
        window.setAttributes(layoutParams);
        window.setContentView(mDialogView);
        //Only fix the width for large screen or tablet.
        window.setLayout(mContext.getResources().getDimensionPixelSize(
                R.dimen.internet_dialog_list_max_width), ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setWindowAnimations(R.style.Animation_InternetDialog);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(FLAG_LAYOUT_NO_LIMITS);

        mInternetDialogLayout = mDialogView.requireViewById(R.id.internet_connectivity_dialog);
        mInternetDialogTitle = mDialogView.requireViewById(R.id.internet_dialog_title);
        mInternetDialogSubTitle = mDialogView.requireViewById(R.id.internet_dialog_subtitle);
        mDivider = mDialogView.requireViewById(R.id.divider);
        mProgressBar = mDialogView.requireViewById(R.id.wifi_searching_progress);
        mEthernetLayout = mDialogView.requireViewById(R.id.ethernet_layout);
        mMobileNetworkLayout = mDialogView.requireViewById(R.id.mobile_network_layout);
        mTurnWifiOnLayout = mDialogView.requireViewById(R.id.turn_on_wifi_layout);
        mWifiToggleTitleText = mDialogView.requireViewById(R.id.wifi_toggle_title);
        mConnectedWifListLayout = mDialogView.requireViewById(R.id.wifi_connected_layout);
        mConnectedWifiIcon = mDialogView.requireViewById(R.id.wifi_connected_icon);
        mConnectedWifiTitleText = mDialogView.requireViewById(R.id.wifi_connected_title);
        mConnectedWifiSummaryText = mDialogView.requireViewById(R.id.wifi_connected_summary);
        mWifiSettingsIcon = mDialogView.requireViewById(R.id.wifi_settings_icon);
        mWifiRecyclerView = mDialogView.requireViewById(R.id.wifi_list_layout);
        mSeeAllLayout = mDialogView.requireViewById(R.id.see_all_layout);
        mDoneButton = mDialogView.requireViewById(R.id.done);
        mSignalIcon = mDialogView.requireViewById(R.id.signal_icon);
        mMobileTitleText = mDialogView.requireViewById(R.id.mobile_title);
        mMobileSummaryText = mDialogView.requireViewById(R.id.mobile_summary);
        mMobileDataToggle = mDialogView.requireViewById(R.id.mobile_toggle);
        mWiFiToggle = mDialogView.requireViewById(R.id.wifi_toggle);
        mBackgroundOn = mContext.getDrawable(R.drawable.settingslib_switch_bar_bg_on);
        mInternetDialogLayout.getViewTreeObserver().addOnGlobalLayoutListener(
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
        mInternetDialogController.onStart(this, mCanConfigWifi);
        if (!mCanConfigWifi) {
            hideWifiViews();
        }
    }

    @VisibleForTesting
    void hideWifiViews() {
        setProgressBarVisible(false);
        mTurnWifiOnLayout.setVisibility(View.GONE);
        mConnectedWifListLayout.setVisibility(View.GONE);
        mWifiRecyclerView.setVisibility(View.GONE);
        mSeeAllLayout.setVisibility(View.GONE);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (DEBUG) {
            Log.d(TAG, "onStop");
        }
        mHandler.removeCallbacks(mHideProgressBarRunnable);
        mHandler.removeCallbacks(mHideSearchingRunnable);
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
        updateEthernet();
        setMobileDataLayout(mInternetDialogController.activeNetworkIsCellular());

        if (!mCanConfigWifi) {
            return;
        }

        showProgressBar();
        final boolean isDeviceLocked = mInternetDialogController.isDeviceLocked();
        final boolean isWifiEnabled = mWifiManager.isWifiEnabled();
        updateWifiToggle(isWifiEnabled, isDeviceLocked);
        updateConnectedWifi(isWifiEnabled, isDeviceLocked);

        final int visibility = (isDeviceLocked || !isWifiEnabled || mWifiEntriesCount <= 0)
                ? View.GONE : View.VISIBLE;
        mWifiRecyclerView.setVisibility(visibility);
        mSeeAllLayout.setVisibility(visibility);
    }

    private void setOnClickListener() {
        mMobileNetworkLayout.setOnClickListener(v -> {
            if (mInternetDialogController.isMobileDataEnabled()
                    && !mInternetDialogController.isDeviceLocked()) {
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
        mConnectedWifListLayout.setOnClickListener(v -> onClickConnectedWifi());
        mSeeAllLayout.setOnClickListener(v -> onClickSeeMoreButton());
        mWiFiToggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    buttonView.setChecked(isChecked);
                    mWifiManager.setWifiEnabled(isChecked);
                });
        mDoneButton.setOnClickListener(v -> dismiss());
    }

    @MainThread
    private void updateEthernet() {
        mEthernetLayout.setVisibility(
                mInternetDialogController.hasEthernet() ? View.VISIBLE : View.GONE);
    }

    private void setMobileDataLayout(boolean isCellularNetwork) {
        if (mInternetDialogController.isAirplaneModeEnabled()
                || !mInternetDialogController.hasCarrier()) {
            mMobileNetworkLayout.setVisibility(View.GONE);
        } else {
            mMobileDataToggle.setChecked(mInternetDialogController.isMobileDataEnabled());
            mMobileNetworkLayout.setVisibility(View.VISIBLE);
            mMobileTitleText.setText(getMobileNetworkTitle());
            if (!TextUtils.isEmpty(getMobileNetworkSummary())) {
                mMobileSummaryText.setText(
                        Html.fromHtml(getMobileNetworkSummary(), Html.FROM_HTML_MODE_LEGACY));
                mMobileSummaryText.setVisibility(View.VISIBLE);
            } else {
                mMobileSummaryText.setVisibility(View.GONE);
            }
            mSignalIcon.setImageDrawable(getSignalStrengthDrawable());
            mMobileTitleText.setTextAppearance(isCellularNetwork
                    ? R.style.TextAppearance_InternetDialog_Active
                    : R.style.TextAppearance_InternetDialog);
            mMobileSummaryText.setTextAppearance(isCellularNetwork
                    ? R.style.TextAppearance_InternetDialog_Secondary_Active
                    : R.style.TextAppearance_InternetDialog_Secondary);
            mMobileNetworkLayout.setBackground(isCellularNetwork ? mBackgroundOn : null);

            mMobileDataToggle.setVisibility(mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @MainThread
    private void updateWifiToggle(boolean isWifiEnabled, boolean isDeviceLocked) {
        mWiFiToggle.setChecked(isWifiEnabled);
        if (isDeviceLocked) {
            mWifiToggleTitleText.setTextAppearance((mConnectedWifiEntry != null)
                    ? R.style.TextAppearance_InternetDialog_Active
                    : R.style.TextAppearance_InternetDialog);
        }
        mTurnWifiOnLayout.setBackground(
                (isDeviceLocked && mConnectedWifiEntry != null) ? mBackgroundOn : null);
    }

    @MainThread
    private void updateConnectedWifi(boolean isWifiEnabled, boolean isDeviceLocked) {
        if (!isWifiEnabled || mConnectedWifiEntry == null || isDeviceLocked) {
            mConnectedWifListLayout.setVisibility(View.GONE);
            return;
        }
        mConnectedWifListLayout.setVisibility(View.VISIBLE);
        mConnectedWifiTitleText.setText(mConnectedWifiEntry.getTitle());
        mConnectedWifiSummaryText.setText(mConnectedWifiEntry.getSummary(false));
        mConnectedWifiIcon.setImageDrawable(
                mInternetDialogController.getInternetWifiDrawable(mConnectedWifiEntry));
        mWifiSettingsIcon.setColorFilter(
                mContext.getColor(R.color.connected_network_primary_color));
    }

    void onClickConnectedWifi() {
        if (mConnectedWifiEntry == null) {
            return;
        }
        mInternetDialogController.launchWifiNetworkDetailsSetting(mConnectedWifiEntry.getKey());
    }

    void onClickSeeMoreButton() {
        mInternetDialogController.launchNetworkSetting();
    }

    CharSequence getDialogTitleText() {
        return mInternetDialogController.getDialogTitleText();
    }

    CharSequence getSubtitleText() {
        return mInternetDialogController.getSubtitleText(
                mIsProgressBarVisible && !mIsSearchingHidden);
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

    protected void showProgressBar() {
        if (mWifiManager == null || !mWifiManager.isWifiEnabled()
                || mInternetDialogController.isDeviceLocked()) {
            setProgressBarVisible(false);
            return;
        }
        setProgressBarVisible(true);
        if (mConnectedWifiEntry != null || mWifiEntriesCount > 0) {
            mHandler.postDelayed(mHideProgressBarRunnable, PROGRESS_DELAY_MS);
        } else if (!mIsSearchingHidden) {
            mHandler.postDelayed(mHideSearchingRunnable, PROGRESS_DELAY_MS);
        }
    }

    private void setProgressBarVisible(boolean visible) {
        if (mWifiManager.isWifiEnabled() && mAdapter.mHolderView != null
                && mAdapter.mHolderView.isAttachedToWindow()) {
            mIsProgressBarVisible = true;
        }
        mIsProgressBarVisible = visible;
        mProgressBar.setVisibility(mIsProgressBarVisible ? View.VISIBLE : View.GONE);
        mDivider.setVisibility(mIsProgressBarVisible ? View.GONE : View.VISIBLE);
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
        CharSequence carrierName = getMobileNetworkTitle();
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
    @WorkerThread
    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
        mHandler.post(() -> updateDialog());
    }

    @Override
    @WorkerThread
    public void onLost(Network network) {
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
    @WorkerThread
    public void onDataConnectionStateChanged(int state, int networkType) {
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
    @WorkerThread
    public void onAccessPointsChanged(@Nullable List<WifiEntry> wifiEntries,
            @Nullable WifiEntry connectedEntry) {
        mConnectedWifiEntry = connectedEntry;
        mWifiEntriesCount = wifiEntries == null ? 0 : wifiEntries.size();
        mAdapter.setWifiEntries(wifiEntries, mWifiEntriesCount);
        mHandler.post(() -> {
            mAdapter.notifyDataSetChanged();
            updateDialog();
        });
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
