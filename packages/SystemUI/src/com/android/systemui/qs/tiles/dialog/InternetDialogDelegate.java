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

import static com.android.settingslib.satellite.SatelliteDialogUtils.TYPE_IS_WIFI;
import static com.android.systemui.Prefs.Key.QS_HAS_TURNED_OFF_MOBILE_DATA;
import static com.android.systemui.qs.tiles.dialog.InternetDialogController.MAX_WIFI_ENTRY_COUNT;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.text.Html;
import android.text.Layout;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewStub;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.telephony.flags.Flags;
import com.android.settingslib.satellite.SatelliteDialogUtils;
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils;
import com.android.systemui.Prefs;
import com.android.systemui.accessibility.floatingmenu.AnnotationLinkSpan;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wifitrackerlib.WifiEntry;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Dialog for showing mobile network, connected Wi-Fi network and Wi-Fi networks.
 */
public class InternetDialogDelegate implements
        SystemUIDialog.Delegate,
        InternetDialogController.InternetDialogCallback {
    private static final String TAG = "InternetDialog";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ABOVE_STATUS_BAR = "above_status_bar";
    private static final String CAN_CONFIG_MOBILE_DATA = "can_config_mobile_data";
    private static final String CAN_CONFIG_WIFI = "can_config_wifi";

    static final int MAX_NETWORK_COUNT = 4;

    private final Handler mHandler;
    private final Executor mBackgroundExecutor;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final boolean mAboveStatusBar;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;

    @VisibleForTesting
    protected InternetAdapter mAdapter;
    @VisibleForTesting
    protected View mDialogView;
    @VisibleForTesting
    protected boolean mCanConfigWifi;

    private final InternetDialogManager mInternetDialogManager;
    @Nullable
    private AlertDialog mAlertDialog;
    private final UiEventLogger mUiEventLogger;
    private final InternetDialogController mInternetDialogController;
    private TextView mInternetDialogTitle;
    private TextView mInternetDialogSubTitle;
    private View mDivider;
    private ProgressBar mProgressBar;
    private LinearLayout mConnectedWifListLayout;
    private LinearLayout mMobileNetworkLayout;
    private LinearLayout mSecondaryMobileNetworkLayout;
    private LinearLayout mTurnWifiOnLayout;
    private LinearLayout mEthernetLayout;
    private TextView mWifiToggleTitleText;
    private LinearLayout mWifiScanNotifyLayout;
    private TextView mWifiScanNotifyText;
    private LinearLayout mSeeAllLayout;
    private RecyclerView mWifiRecyclerView;
    private ImageView mConnectedWifiIcon;
    private ImageView mWifiSettingsIcon;
    private TextView mConnectedWifiTitleText;
    private TextView mConnectedWifiSummaryText;
    private ImageView mSignalIcon;
    private TextView mMobileTitleText;
    private TextView mMobileSummaryText;
    private TextView mAirplaneModeSummaryText;
    private Switch mMobileDataToggle;
    private View mMobileToggleDivider;
    private Switch mWiFiToggle;
    private Button mDoneButton;

    @VisibleForTesting
    protected Button mShareWifiButton;
    private Button mAirplaneModeButton;
    private Drawable mBackgroundOn;
    private final KeyguardStateController mKeyguard;
    @Nullable
    private Drawable mBackgroundOff = null;
    private int mDefaultDataSubId;
    private final boolean mCanConfigMobileData;
    private final boolean mCanChangeWifiState;
    // Wi-Fi entries
    private int mWifiNetworkHeight;
    @Nullable
    @VisibleForTesting
    protected WifiEntry mConnectedWifiEntry;
    @VisibleForTesting
    protected int mWifiEntriesCount;
    @VisibleForTesting
    protected boolean mHasMoreWifiEntries;

    // Wi-Fi scanning progress bar
    protected boolean mIsProgressBarVisible;
    private SystemUIDialog mDialog;
    private final CoroutineScope mCoroutineScope;
    @Nullable
    private Job mClickJob;

    // These are to reduce the UI janky frame duration. b/323286540
    private LifecycleRegistry mLifecycleRegistry;
    @VisibleForTesting
    LifecycleOwner mLifecycleOwner;
    @VisibleForTesting
    MutableLiveData<InternetContent> mDataInternetContent = new MutableLiveData<>();

    @AssistedFactory
    public interface Factory {
        InternetDialogDelegate create(
                @Assisted(ABOVE_STATUS_BAR) boolean aboveStatusBar,
                @Assisted(CAN_CONFIG_MOBILE_DATA) boolean canConfigMobileData,
                @Assisted(CAN_CONFIG_WIFI) boolean canConfigWifi,
                @Assisted CoroutineScope coroutineScope);
    }

    @AssistedInject
    public InternetDialogDelegate(
            Context context,
            InternetDialogManager internetDialogManager,
            InternetDialogController internetDialogController,
            @Assisted(CAN_CONFIG_MOBILE_DATA) boolean canConfigMobileData,
            @Assisted(CAN_CONFIG_WIFI) boolean canConfigWifi,
            @Assisted(ABOVE_STATUS_BAR) boolean aboveStatusBar,
            @Assisted CoroutineScope coroutineScope,
            UiEventLogger uiEventLogger,
            DialogTransitionAnimator dialogTransitionAnimator,
            @Main Handler handler,
            @Background Executor executor,
            KeyguardStateController keyguardStateController,
            SystemUIDialog.Factory systemUIDialogFactory) {
        mAboveStatusBar = aboveStatusBar;
        mSystemUIDialogFactory = systemUIDialogFactory;
        if (DEBUG) {
            Log.d(TAG, "Init InternetDialog");
        }

        // Save the context that is wrapped with our theme.
        mHandler = handler;
        mBackgroundExecutor = executor;
        mInternetDialogManager = internetDialogManager;
        mInternetDialogController = internetDialogController;
        mDefaultDataSubId = mInternetDialogController.getDefaultDataSubscriptionId();
        mCanConfigMobileData = canConfigMobileData;
        mCanConfigWifi = canConfigWifi;
        mCanChangeWifiState = WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(context);
        mKeyguard = keyguardStateController;
        mCoroutineScope = coroutineScope;
        mUiEventLogger = uiEventLogger;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mAdapter = new InternetAdapter(mInternetDialogController, coroutineScope);
    }

    @Override
    public SystemUIDialog createDialog() {
        SystemUIDialog dialog = mSystemUIDialogFactory.create(this);
        if (!mAboveStatusBar) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }

        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = dialog;
        mLifecycleOwner = new LifecycleOwner() {
            @NonNull
            @Override
            public Lifecycle getLifecycle() {
                return mLifecycleRegistry;
            }
        };
        mLifecycleRegistry = new LifecycleRegistry(mLifecycleOwner);

        return dialog;
    }

    @Override
    public void onCreate(SystemUIDialog dialog, Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }
        Context context = dialog.getContext();
        mUiEventLogger.log(InternetDialogEvent.INTERNET_DIALOG_SHOW);
        mDialogView = LayoutInflater.from(context).inflate(
                R.layout.internet_connectivity_dialog, null);
        mDialogView.setAccessibilityPaneTitle(
                context.getText(R.string.accessibility_desc_quick_settings));
        final Window window = dialog.getWindow();
        window.setContentView(mDialogView);

        window.setWindowAnimations(R.style.Animation_InternetDialog);

        mWifiNetworkHeight = context.getResources()
                .getDimensionPixelSize(R.dimen.internet_dialog_wifi_network_height);
        mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        mDataInternetContent.observe(
                mLifecycleOwner, (internetContent) -> updateDialogUI(internetContent));
        mInternetDialogTitle = mDialogView.requireViewById(R.id.internet_dialog_title);
        mInternetDialogSubTitle = mDialogView.requireViewById(R.id.internet_dialog_subtitle);
        mDivider = mDialogView.requireViewById(R.id.divider);
        mProgressBar = mDialogView.requireViewById(R.id.wifi_searching_progress);
        mEthernetLayout = mDialogView.requireViewById(R.id.ethernet_layout);
        mMobileNetworkLayout = mDialogView.requireViewById(R.id.mobile_network_layout);
        mTurnWifiOnLayout = mDialogView.requireViewById(R.id.turn_on_wifi_layout);
        mWifiToggleTitleText = mDialogView.requireViewById(R.id.wifi_toggle_title);
        mWifiScanNotifyLayout = mDialogView.requireViewById(R.id.wifi_scan_notify_layout);
        mWifiScanNotifyText = mDialogView.requireViewById(R.id.wifi_scan_notify_text);
        mConnectedWifListLayout = mDialogView.requireViewById(R.id.wifi_connected_layout);
        mConnectedWifiIcon = mDialogView.requireViewById(R.id.wifi_connected_icon);
        mConnectedWifiTitleText = mDialogView.requireViewById(R.id.wifi_connected_title);
        mConnectedWifiSummaryText = mDialogView.requireViewById(R.id.wifi_connected_summary);
        mWifiSettingsIcon = mDialogView.requireViewById(R.id.wifi_settings_icon);
        mWifiRecyclerView = mDialogView.requireViewById(R.id.wifi_list_layout);
        mSeeAllLayout = mDialogView.requireViewById(R.id.see_all_layout);
        mDoneButton = mDialogView.requireViewById(R.id.done_button);
        mShareWifiButton = mDialogView.requireViewById(R.id.share_wifi_button);
        mAirplaneModeButton = mDialogView.requireViewById(R.id.apm_button);
        mSignalIcon = mDialogView.requireViewById(R.id.signal_icon);
        mMobileTitleText = mDialogView.requireViewById(R.id.mobile_title);
        mMobileSummaryText = mDialogView.requireViewById(R.id.mobile_summary);
        mAirplaneModeSummaryText = mDialogView.requireViewById(R.id.airplane_mode_summary);
        mMobileToggleDivider = mDialogView.requireViewById(R.id.mobile_toggle_divider);
        mMobileDataToggle = mDialogView.requireViewById(R.id.mobile_toggle);
        mWiFiToggle = mDialogView.requireViewById(R.id.wifi_toggle);
        mBackgroundOn = context.getDrawable(R.drawable.settingslib_switch_bar_bg_on);
        mInternetDialogTitle.setText(getDialogTitleText());
        mInternetDialogTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        mBackgroundOff = context.getDrawable(R.drawable.internet_dialog_selected_effect);
        setOnClickListener(dialog);
        mTurnWifiOnLayout.setBackground(null);
        mAirplaneModeButton.setVisibility(
                mInternetDialogController.isAirplaneModeEnabled() ? View.VISIBLE : View.GONE);
        mWifiRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        mWifiRecyclerView.setAdapter(mAdapter);

        updateDialogUI(getWifiNetworkContent());
    }

    @Override
    public void onStart(SystemUIDialog dialog) {
        if (DEBUG) {
            Log.d(TAG, "onStart");
        }

        mLifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

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
        mShareWifiButton.setVisibility(View.GONE);
    }

    @Override
    public void onStop(SystemUIDialog dialog) {
        if (DEBUG) {
            Log.d(TAG, "onStop");
        }
        mLifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        mMobileNetworkLayout.setOnClickListener(null);
        mConnectedWifListLayout.setOnClickListener(null);
        if (mSecondaryMobileNetworkLayout != null) {
            mSecondaryMobileNetworkLayout.setOnClickListener(null);
        }
        mSeeAllLayout.setOnClickListener(null);
        mWiFiToggle.setOnCheckedChangeListener(null);
        mDoneButton.setOnClickListener(null);
        mShareWifiButton.setOnClickListener(null);
        mAirplaneModeButton.setOnClickListener(null);
        mInternetDialogController.onStop();
        mInternetDialogManager.destroyDialog();
    }

    @Override
    public void dismissDialog() {
        if (DEBUG) {
            Log.d(TAG, "dismissDialog");
        }
        mInternetDialogManager.destroyDialog();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    /**
     * Update the internet dialog when receiving the callback.
     *
     * @param shouldUpdateMobileNetwork {@code true} for update the mobile network layout,
     *                                  otherwise {@code false}.
     */
    void updateDialog(boolean shouldUpdateMobileNetwork) {
        mBackgroundExecutor.execute(() -> {
            mDataInternetContent.postValue(getInternetContent(shouldUpdateMobileNetwork));
        });
    }

    private void updateDialogUI(InternetContent internetContent) {
        if (DEBUG) {
            Log.d(TAG, "updateDialog ");
        }

        mInternetDialogTitle.setText(internetContent.mInternetDialogTitleString);
        mInternetDialogSubTitle.setText(internetContent.mInternetDialogSubTitle);
        mAirplaneModeButton.setVisibility(
                internetContent.mIsAirplaneModeEnabled ? View.VISIBLE : View.GONE);

        updateEthernet(internetContent);
        setMobileDataLayout(internetContent);

        if (!mCanConfigWifi) {
            return;
        }
        updateWifiToggle(internetContent);
        updateConnectedWifi(internetContent);
        updateWifiListAndSeeAll(internetContent);
        updateWifiScanNotify(internetContent);
    }

    private InternetContent getInternetContent(boolean shouldUpdateMobileNetwork) {
        InternetContent internetContent = new InternetContent();
        internetContent.mShouldUpdateMobileNetwork = shouldUpdateMobileNetwork;
        internetContent.mInternetDialogTitleString = getDialogTitleText();
        internetContent.mInternetDialogSubTitle = getSubtitleText();
        if (shouldUpdateMobileNetwork) {
            internetContent.mActiveNetworkIsCellular =
                    mInternetDialogController.activeNetworkIsCellular();
            internetContent.mIsCarrierNetworkActive =
                    mInternetDialogController.isCarrierNetworkActive();
        }
        internetContent.mIsAirplaneModeEnabled = mInternetDialogController.isAirplaneModeEnabled();
        internetContent.mHasEthernet = mInternetDialogController.hasEthernet();
        internetContent.mIsWifiEnabled = mInternetDialogController.isWifiEnabled();
        internetContent.mHasActiveSubIdOnDds = mInternetDialogController.hasActiveSubIdOnDds();
        internetContent.mIsMobileDataEnabled = mInternetDialogController.isMobileDataEnabled();
        internetContent.mIsDeviceLocked = mInternetDialogController.isDeviceLocked();
        internetContent.mIsWifiScanEnabled = mInternetDialogController.isWifiScanEnabled();
        return internetContent;
    }

    private InternetContent getWifiNetworkContent() {
        InternetContent internetContent = new InternetContent();
        internetContent.mInternetDialogTitleString = getDialogTitleText();
        internetContent.mInternetDialogSubTitle = getSubtitleText();
        internetContent.mIsWifiEnabled = mInternetDialogController.isWifiEnabled();
        internetContent.mIsDeviceLocked = mInternetDialogController.isDeviceLocked();
        return internetContent;
    }

    private void setOnClickListener(SystemUIDialog dialog) {
        mMobileNetworkLayout.setOnClickListener(v -> {
            int autoSwitchNonDdsSubId = mInternetDialogController.getActiveAutoSwitchNonDdsSubId();
            if (autoSwitchNonDdsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                showTurnOffAutoDataSwitchDialog(dialog, autoSwitchNonDdsSubId);
            }
            mInternetDialogController.connectCarrierNetwork();
        });
        mMobileDataToggle.setOnClickListener(v -> {
            boolean isChecked = mMobileDataToggle.isChecked();
            if (!isChecked && shouldShowMobileDialog()) {
                mMobileDataToggle.setChecked(true);
                showTurnOffMobileDialog(dialog);
            } else if (mInternetDialogController.isMobileDataEnabled() != isChecked) {
                mInternetDialogController.setMobileDataEnabled(
                        dialog.getContext(), mDefaultDataSubId, isChecked, false);
            }
        });
        mConnectedWifListLayout.setOnClickListener(this::onClickConnectedWifi);
        mSeeAllLayout.setOnClickListener(this::onClickSeeMoreButton);
        mWiFiToggle.setOnClickListener(v -> {
            handleWifiToggleClicked(mWiFiToggle.isChecked());
        });
        mDoneButton.setOnClickListener(v -> dialog.dismiss());
        mShareWifiButton.setOnClickListener(v -> {
            if (mInternetDialogController.mayLaunchShareWifiSettings(mConnectedWifiEntry, v)) {
                mUiEventLogger.log(InternetDialogEvent.SHARE_WIFI_QS_BUTTON_CLICKED);
            }
        });
        mAirplaneModeButton.setOnClickListener(v -> {
            mInternetDialogController.setAirplaneModeDisabled();
        });
    }

    private void handleWifiToggleClicked(boolean isChecked) {
        if (Flags.oemEnabledSatelliteFlag()) {
            if (mClickJob != null && !mClickJob.isCompleted()) {
                return;
            }
            mClickJob = SatelliteDialogUtils.mayStartSatelliteWarningDialog(
                    mDialog.getContext(), mCoroutineScope, TYPE_IS_WIFI, isAllowClick -> {
                        if (isAllowClick) {
                            setWifiEnable(isChecked);
                        } else {
                            mWiFiToggle.setChecked(!isChecked);
                        }
                        return null;
                    });
            return;
        }
        setWifiEnable(isChecked);
    }

    private void setWifiEnable(boolean isChecked) {
        if (mInternetDialogController.isWifiEnabled() == isChecked) {
            return;
        }
        mInternetDialogController.setWifiEnabled(isChecked);
    }

    @MainThread
    private void updateEthernet(InternetContent internetContent) {
        mEthernetLayout.setVisibility(
                internetContent.mHasEthernet ? View.VISIBLE : View.GONE);
    }

    private void setMobileDataLayout(InternetContent internetContent) {
        if (!internetContent.mShouldUpdateMobileNetwork && mDialog == null) {
            return;
        }
        setMobileDataLayout(mDialog, internetContent);
    }

    private void setMobileDataLayout(SystemUIDialog dialog, InternetContent internetContent) {
        boolean isNetworkConnected =
                internetContent.mActiveNetworkIsCellular
                        || internetContent.mIsCarrierNetworkActive;
        // 1. Mobile network should be gone if airplane mode ON or the list of active
        //    subscriptionId is null.
        // 2. Carrier network should be gone if airplane mode ON and Wi-Fi is OFF.
        if (DEBUG) {
            Log.d(TAG, "setMobileDataLayout, isCarrierNetworkActive = "
                    + internetContent.mIsCarrierNetworkActive);
        }

        if (!internetContent.mHasActiveSubIdOnDds && (!internetContent.mIsWifiEnabled
                || !internetContent.mIsCarrierNetworkActive)) {
            mMobileNetworkLayout.setVisibility(View.GONE);
            if (mSecondaryMobileNetworkLayout != null) {
                mSecondaryMobileNetworkLayout.setVisibility(View.GONE);
            }
        } else {
            mMobileNetworkLayout.setVisibility(View.VISIBLE);
            mMobileDataToggle.setChecked(internetContent.mIsMobileDataEnabled);
            mMobileTitleText.setText(getMobileNetworkTitle(mDefaultDataSubId));
            String summary = getMobileNetworkSummary(mDefaultDataSubId);
            if (!TextUtils.isEmpty(summary)) {
                mMobileSummaryText.setText(
                        Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY));
                mMobileSummaryText.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
                mMobileSummaryText.setVisibility(View.VISIBLE);
            } else {
                mMobileSummaryText.setVisibility(View.GONE);
            }
            mBackgroundExecutor.execute(() -> {
                Drawable drawable = getSignalStrengthDrawable(mDefaultDataSubId);
                mHandler.post(() -> {
                    mSignalIcon.setImageDrawable(drawable);
                });
            });

            mMobileDataToggle.setVisibility(mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
            mMobileToggleDivider.setVisibility(
                    mCanConfigMobileData ? View.VISIBLE : View.INVISIBLE);
            int primaryColor = isNetworkConnected
                    ? R.color.connected_network_primary_color
                    : R.color.disconnected_network_primary_color;
            mMobileToggleDivider.setBackgroundColor(dialog.getContext().getColor(primaryColor));

            // Display the info for the non-DDS if it's actively being used
            int autoSwitchNonDdsSubId = mInternetDialogController.getActiveAutoSwitchNonDdsSubId();
            int nonDdsVisibility = autoSwitchNonDdsSubId
                    != SubscriptionManager.INVALID_SUBSCRIPTION_ID ? View.VISIBLE : View.GONE;

            int secondaryRes = isNetworkConnected
                    ? R.style.TextAppearance_InternetDialog_Secondary_Active
                    : R.style.TextAppearance_InternetDialog_Secondary;
            if (nonDdsVisibility == View.VISIBLE) {
                // non DDS is the currently active sub, set primary visual for it
                ViewStub stub = mDialogView.findViewById(R.id.secondary_mobile_network_stub);
                if (stub != null) {
                    stub.inflate();
                }
                mSecondaryMobileNetworkLayout = mDialogView.findViewById(
                        R.id.secondary_mobile_network_layout);
                mSecondaryMobileNetworkLayout.setOnClickListener(
                        this::onClickConnectedSecondarySub);
                mSecondaryMobileNetworkLayout.setBackground(mBackgroundOn);

                TextView mSecondaryMobileTitleText = mDialogView.requireViewById(
                        R.id.secondary_mobile_title);
                mSecondaryMobileTitleText.setText(getMobileNetworkTitle(autoSwitchNonDdsSubId));
                mSecondaryMobileTitleText.setTextAppearance(
                        R.style.TextAppearance_InternetDialog_Active);

                TextView mSecondaryMobileSummaryText =
                        mDialogView.requireViewById(R.id.secondary_mobile_summary);
                summary = getMobileNetworkSummary(autoSwitchNonDdsSubId);
                if (!TextUtils.isEmpty(summary)) {
                    mSecondaryMobileSummaryText.setText(
                            Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY));
                    mSecondaryMobileSummaryText.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
                    mSecondaryMobileSummaryText.setTextAppearance(
                            R.style.TextAppearance_InternetDialog_Active);
                }

                ImageView mSecondarySignalIcon =
                        mDialogView.requireViewById(R.id.secondary_signal_icon);
                mBackgroundExecutor.execute(() -> {
                    Drawable drawable = getSignalStrengthDrawable(autoSwitchNonDdsSubId);
                    mHandler.post(() -> {
                        mSecondarySignalIcon.setImageDrawable(drawable);
                    });
                });

                ImageView mSecondaryMobileSettingsIcon =
                        mDialogView.requireViewById(R.id.secondary_settings_icon);
                mSecondaryMobileSettingsIcon.setColorFilter(
                        dialog.getContext().getColor(R.color.connected_network_primary_color));

                // set secondary visual for default data sub
                mMobileNetworkLayout.setBackground(mBackgroundOff);
                mMobileTitleText.setTextAppearance(R.style.TextAppearance_InternetDialog);
                mMobileSummaryText.setTextAppearance(
                        R.style.TextAppearance_InternetDialog_Secondary);
                mSignalIcon.setColorFilter(
                        dialog.getContext().getColor(R.color.connected_network_secondary_color));
            } else {
                mMobileNetworkLayout.setBackground(
                        isNetworkConnected ? mBackgroundOn : mBackgroundOff);
                mMobileTitleText.setTextAppearance(isNetworkConnected
                        ?
                        R.style.TextAppearance_InternetDialog_Active
                        : R.style.TextAppearance_InternetDialog);
                mMobileSummaryText.setTextAppearance(secondaryRes);
            }

            if (mSecondaryMobileNetworkLayout != null) {
                mSecondaryMobileNetworkLayout.setVisibility(nonDdsVisibility);
            }

            // Set airplane mode to the summary for carrier network
            if (internetContent.mIsAirplaneModeEnabled) {
                mAirplaneModeSummaryText.setVisibility(View.VISIBLE);
                mAirplaneModeSummaryText.setText(
                        dialog.getContext().getText(R.string.airplane_mode));
                mAirplaneModeSummaryText.setTextAppearance(secondaryRes);
            } else {
                mAirplaneModeSummaryText.setVisibility(View.GONE);
            }
        }
    }

    @MainThread
    private void updateWifiToggle(InternetContent internetContent) {
        if (mWiFiToggle.isChecked() != internetContent.mIsWifiEnabled) {
            mWiFiToggle.setChecked(internetContent.mIsWifiEnabled);
        }
        if (internetContent.mIsDeviceLocked) {
            mWifiToggleTitleText.setTextAppearance((mConnectedWifiEntry != null)
                    ? R.style.TextAppearance_InternetDialog_Active
                    : R.style.TextAppearance_InternetDialog);
        }
        mTurnWifiOnLayout.setBackground(
                (internetContent.mIsDeviceLocked && mConnectedWifiEntry != null) ? mBackgroundOn
                        : null);

        if (!mCanChangeWifiState && mWiFiToggle.isEnabled()) {
            mWiFiToggle.setEnabled(false);
            mWifiToggleTitleText.setEnabled(false);
            final TextView summaryText = mDialogView.requireViewById(R.id.wifi_toggle_summary);
            summaryText.setEnabled(false);
            summaryText.setVisibility(View.VISIBLE);
        }
    }

    @MainThread
    private void updateConnectedWifi(InternetContent internetContent) {
        if (mDialog == null || !internetContent.mIsWifiEnabled || mConnectedWifiEntry == null
                || internetContent.mIsDeviceLocked) {
            mConnectedWifListLayout.setVisibility(View.GONE);
            mShareWifiButton.setVisibility(View.GONE);
            return;
        }
        mConnectedWifListLayout.setVisibility(View.VISIBLE);
        mConnectedWifiTitleText.setText(mConnectedWifiEntry.getTitle());
        mConnectedWifiSummaryText.setText(mConnectedWifiEntry.getSummary(false));
        mConnectedWifiIcon.setImageDrawable(
                mInternetDialogController.getInternetWifiDrawable(mConnectedWifiEntry));
        mWifiSettingsIcon.setColorFilter(
                mDialog.getContext().getColor(R.color.connected_network_primary_color));
        if (mInternetDialogController.getConfiguratorQrCodeGeneratorIntentOrNull(
                mConnectedWifiEntry) != null) {
            mShareWifiButton.setVisibility(View.VISIBLE);
        } else {
            mShareWifiButton.setVisibility(View.GONE);
        }

        if (mSecondaryMobileNetworkLayout != null) {
            mSecondaryMobileNetworkLayout.setVisibility(View.GONE);
        }
    }

    @MainThread
    private void updateWifiListAndSeeAll(InternetContent internetContent) {
        if (!internetContent.mIsWifiEnabled || internetContent.mIsDeviceLocked) {
            mWifiRecyclerView.setVisibility(View.GONE);
            mSeeAllLayout.setVisibility(View.GONE);
            return;
        }
        final int wifiListMaxCount = getWifiListMaxCount();
        if (mAdapter.getItemCount() > wifiListMaxCount) {
            mHasMoreWifiEntries = true;
        }
        mAdapter.setMaxEntriesCount(wifiListMaxCount);
        final int wifiListMinHeight = mWifiNetworkHeight * wifiListMaxCount;
        if (mWifiRecyclerView.getMinimumHeight() != wifiListMinHeight) {
            mWifiRecyclerView.setMinimumHeight(wifiListMinHeight);
        }
        mWifiRecyclerView.setVisibility(View.VISIBLE);
        mSeeAllLayout.setVisibility(mHasMoreWifiEntries ? View.VISIBLE : View.INVISIBLE);
    }

    @VisibleForTesting
    @MainThread
    int getWifiListMaxCount() {
        // Use the maximum count of networks to calculate the remaining count for Wi-Fi networks.
        int count = MAX_NETWORK_COUNT;
        if (mEthernetLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }
        if (mMobileNetworkLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }

        // If the remaining count is greater than the maximum count of the Wi-Fi network, the
        // maximum count of the Wi-Fi network is used.
        if (count > MAX_WIFI_ENTRY_COUNT) {
            count = MAX_WIFI_ENTRY_COUNT;
        }
        if (mConnectedWifListLayout.getVisibility() == View.VISIBLE) {
            count -= 1;
        }
        return count;
    }

    @MainThread
    private void updateWifiScanNotify(InternetContent internetContent) {
        if (mDialog == null || internetContent.mIsWifiEnabled
                || !internetContent.mIsWifiScanEnabled
                || internetContent.mIsDeviceLocked) {
            mWifiScanNotifyLayout.setVisibility(View.GONE);
            return;
        }
        if (TextUtils.isEmpty(mWifiScanNotifyText.getText())) {
            final AnnotationLinkSpan.LinkInfo linkInfo = new AnnotationLinkSpan.LinkInfo(
                    AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION,
                    mInternetDialogController::launchWifiScanningSetting);
            mWifiScanNotifyText.setText(AnnotationLinkSpan.linkify(
                    mDialog.getContext().getText(R.string.wifi_scan_notify_message), linkInfo));
            mWifiScanNotifyText.setMovementMethod(LinkMovementMethod.getInstance());
        }
        mWifiScanNotifyLayout.setVisibility(View.VISIBLE);
    }

    void onClickConnectedWifi(View view) {
        if (mConnectedWifiEntry == null) {
            return;
        }
        mInternetDialogController.launchWifiDetailsSetting(mConnectedWifiEntry.getKey(), view);
    }

    /** For DSDS auto data switch **/
    void onClickConnectedSecondarySub(View view) {
        mInternetDialogController.launchMobileNetworkSettings(view);
    }

    void onClickSeeMoreButton(View view) {
        mInternetDialogController.launchNetworkSetting(view);
    }

    CharSequence getDialogTitleText() {
        return mInternetDialogController.getDialogTitleText();
    }

    @Nullable
    CharSequence getSubtitleText() {
        return mInternetDialogController.getSubtitleText(mIsProgressBarVisible);
    }

    private Drawable getSignalStrengthDrawable(int subId) {
        return mInternetDialogController.getSignalStrengthDrawable(subId);
    }

    CharSequence getMobileNetworkTitle(int subId) {
        return mInternetDialogController.getMobileNetworkTitle(subId);
    }

    String getMobileNetworkSummary(int subId) {
        return mInternetDialogController.getMobileNetworkSummary(subId);
    }

    private void setProgressBarVisible(boolean visible) {
        if (mIsProgressBarVisible == visible) {
            return;
        }
        mIsProgressBarVisible = visible;
        mProgressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        mProgressBar.setIndeterminate(visible);
        mDivider.setVisibility(visible ? View.GONE : View.VISIBLE);
        mInternetDialogSubTitle.setText(getSubtitleText());
    }

    private boolean shouldShowMobileDialog() {
        if (mDialog == null) {
            return false;
        }
        boolean flag = Prefs.getBoolean(mDialog.getContext(), QS_HAS_TURNED_OFF_MOBILE_DATA,
                false);
        if (mInternetDialogController.isMobileDataEnabled() && !flag) {
            return true;
        }
        return false;
    }

    private void showTurnOffMobileDialog(SystemUIDialog dialog) {
        Context context = dialog.getContext();
        CharSequence carrierName = getMobileNetworkTitle(mDefaultDataSubId);
        boolean isInService = mInternetDialogController.isVoiceStateInService(mDefaultDataSubId);
        if (TextUtils.isEmpty(carrierName) || !isInService) {
            carrierName = context.getString(R.string.mobile_data_disable_message_default_carrier);
        }
        mAlertDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.mobile_data_disable_title)
                .setMessage(context.getString(R.string.mobile_data_disable_message, carrierName))
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                })
                .setPositiveButton(
                        com.android.internal.R.string.alert_windows_notification_turn_off_action,
                        (d, w) -> {
                            mInternetDialogController.setMobileDataEnabled(context,
                                    mDefaultDataSubId, false, false);
                            mMobileDataToggle.setChecked(false);
                            Prefs.putBoolean(context, QS_HAS_TURNED_OFF_MOBILE_DATA, true);
                        })
                .create();
        mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(mAlertDialog, true);
        SystemUIDialog.registerDismissListener(mAlertDialog);
        SystemUIDialog.setWindowOnTop(mAlertDialog, mKeyguard.isShowing());
        mDialogTransitionAnimator.showFromDialog(mAlertDialog, dialog, null, false);
    }

    private void showTurnOffAutoDataSwitchDialog(SystemUIDialog dialog, int subId) {
        Context context = dialog.getContext();
        CharSequence carrierName = getMobileNetworkTitle(mDefaultDataSubId);
        if (TextUtils.isEmpty(carrierName)) {
            carrierName = context.getString(R.string.mobile_data_disable_message_default_carrier);
        }
        mAlertDialog = new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.auto_data_switch_disable_title, carrierName))
                .setMessage(R.string.auto_data_switch_disable_message)
                .setNegativeButton(R.string.auto_data_switch_dialog_negative_button,
                        (d, w) -> {
                        })
                .setPositiveButton(R.string.auto_data_switch_dialog_positive_button,
                        (d, w) -> {
                            mInternetDialogController
                                    .setAutoDataSwitchMobileDataPolicy(subId, false);
                            if (mSecondaryMobileNetworkLayout != null) {
                                mSecondaryMobileNetworkLayout.setVisibility(View.GONE);
                            }
                        })
                .create();
        mAlertDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        SystemUIDialog.setShowForAllUsers(mAlertDialog, true);
        SystemUIDialog.registerDismissListener(mAlertDialog);
        SystemUIDialog.setWindowOnTop(mAlertDialog, mKeyguard.isShowing());
        mDialogTransitionAnimator.showFromDialog(mAlertDialog, dialog, null, false);
    }

    @Override
    public void onRefreshCarrierInfo() {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    public void onSimStateChanged() {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    @WorkerThread
    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    @WorkerThread
    public void onLost(Network network) {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    public void onSubscriptionsChanged(int defaultDataSubId) {
        mDefaultDataSubId = defaultDataSubId;
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    public void onUserMobileDataStateChanged(boolean enabled) {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    public void onServiceStateChanged(ServiceState serviceState) {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    @WorkerThread
    public void onDataConnectionStateChanged(int state, int networkType) {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    public void onCarrierNetworkChange(boolean active) {

        updateDialog(true /* shouldUpdateMobileNetwork */);
    }

    @Override
    @WorkerThread
    public void onAccessPointsChanged(@Nullable List<WifiEntry> wifiEntries,
            @Nullable WifiEntry connectedEntry, boolean hasMoreWifiEntries) {
        // Should update the carrier network layout when it is connected under airplane mode ON.
        boolean shouldUpdateCarrierNetwork = mMobileNetworkLayout.getVisibility() == View.VISIBLE
                && mInternetDialogController.isAirplaneModeEnabled();
        mHandler.post(() -> {
            mConnectedWifiEntry = connectedEntry;
            mWifiEntriesCount = wifiEntries == null ? 0 : wifiEntries.size();
            mHasMoreWifiEntries = hasMoreWifiEntries;
            updateDialog(shouldUpdateCarrierNetwork /* shouldUpdateMobileNetwork */);
            mAdapter.setWifiEntries(wifiEntries, mWifiEntriesCount);
            mAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onWifiScan(boolean isScan) {
        setProgressBarVisible(isScan);
    }

    @Override
    public void onWindowFocusChanged(SystemUIDialog dialog, boolean hasFocus) {
        if (mAlertDialog != null && !mAlertDialog.isShowing()) {
            if (!hasFocus && dialog.isShowing()) {
                dialog.dismiss();
            }
        }
    }

    public enum InternetDialogEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The Internet dialog became visible on the screen.")
        INTERNET_DIALOG_SHOW(843),

        @UiEvent(doc = "The share wifi button is clicked.")
        SHARE_WIFI_QS_BUTTON_CLICKED(1462);

        private final int mId;

        InternetDialogEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    @VisibleForTesting
    static class InternetContent {
        CharSequence mInternetDialogTitleString = "";
        CharSequence mInternetDialogSubTitle = "";
        boolean mIsAirplaneModeEnabled = false;
        boolean mHasEthernet = false;
        boolean mShouldUpdateMobileNetwork = false;
        boolean mActiveNetworkIsCellular = false;
        boolean mIsCarrierNetworkActive = false;
        boolean mIsWifiEnabled = false;
        boolean mHasActiveSubIdOnDds = false;
        boolean mIsMobileDataEnabled = false;
        boolean mIsDeviceLocked = false;
        boolean mIsWifiScanEnabled = false;
    }
}
