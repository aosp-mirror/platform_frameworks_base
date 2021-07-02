/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.carrier;

import static android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES;

import android.annotation.MainThread;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.keyguard.CarrierTextManager;
import com.android.settingslib.AccessibilityContentDescriptions;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.MobileDataIndicators;
import com.android.systemui.util.CarrierConfigTracker;

import java.util.function.Consumer;

import javax.inject.Inject;

public class QSCarrierGroupController {
    private static final String TAG = "QSCarrierGroup";

    /**
     * Support up to 3 slots which is what's supported by {@link TelephonyManager#getPhoneCount}
     */
    private static final int SIM_SLOTS = 3;

    private final ActivityStarter mActivityStarter;
    private final Handler mBgHandler;
    private final NetworkController mNetworkController;
    private final CarrierTextManager mCarrierTextManager;
    private final TextView mNoSimTextView;
    private final H mMainHandler;
    private final Callback mCallback;
    private boolean mListening;
    private final CellSignalState[] mInfos =
            new CellSignalState[SIM_SLOTS];
    private View[] mCarrierDividers = new View[SIM_SLOTS - 1];
    private QSCarrier[] mCarrierGroups = new QSCarrier[SIM_SLOTS];
    private int[] mLastSignalLevel = new int[SIM_SLOTS];
    private String[] mLastSignalLevelDescription = new String[SIM_SLOTS];
    private final boolean mProviderModel;
    private final CarrierConfigTracker mCarrierConfigTracker;

    private final NetworkController.SignalCallback mSignalCallback =
            new NetworkController.SignalCallback() {
                @Override
                public void setMobileDataIndicators(MobileDataIndicators indicators) {
                    if (mProviderModel) {
                        return;
                    }
                    int slotIndex = getSlotIndex(indicators.subId);
                    if (slotIndex >= SIM_SLOTS) {
                        Log.w(TAG, "setMobileDataIndicators - slot: " + slotIndex);
                        return;
                    }
                    if (slotIndex == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                        Log.e(TAG, "Invalid SIM slot index for subscription: " + indicators.subId);
                        return;
                    }
                    mInfos[slotIndex] = new CellSignalState(
                            indicators.statusIcon.visible,
                            indicators.statusIcon.icon,
                            indicators.statusIcon.contentDescription,
                            indicators.typeContentDescription.toString(),
                            indicators.roaming,
                            mProviderModel
                    );
                    mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
                }

                @Override
                public void setCallIndicator(NetworkController.IconState statusIcon, int subId) {
                    if (!mProviderModel) {
                        return;
                    }
                    int slotIndex = getSlotIndex(subId);
                    if (slotIndex >= SIM_SLOTS) {
                        Log.w(TAG, "setMobileDataIndicators - slot: " + slotIndex);
                        return;
                    }
                    if (slotIndex == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                        Log.e(TAG, "Invalid SIM slot index for subscription: " + subId);
                        return;
                    }

                    boolean displayCallStrengthIcon =
                            mCarrierConfigTracker.getCallStrengthConfig(subId);

                    if (statusIcon.icon == R.drawable.ic_qs_no_calling_sms) {
                        if (statusIcon.visible) {
                            mInfos[slotIndex] = new CellSignalState(
                                    true,
                                    statusIcon.icon,
                                    statusIcon.contentDescription,
                                    "",
                                    false,
                                    mProviderModel);
                        } else {
                            // Whenever the no Calling & SMS state is cleared, switched to the last
                            // known call strength icon.
                            if (displayCallStrengthIcon) {
                                mInfos[slotIndex] = new CellSignalState(
                                        true,
                                        mLastSignalLevel[slotIndex],
                                        mLastSignalLevelDescription[slotIndex],
                                        "",
                                        false,
                                        mProviderModel);
                            } else {
                                mInfos[slotIndex] = new CellSignalState(
                                        true,
                                        R.drawable.ic_qs_sim_card,
                                        "",
                                        "",
                                        false,
                                        mProviderModel);
                            }
                        }
                        mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
                    } else {
                        mLastSignalLevel[slotIndex] = statusIcon.icon;
                        mLastSignalLevelDescription[slotIndex] = statusIcon.contentDescription;
                        // Only Shows the call strength icon when the no Calling & SMS icon is not
                        // shown.
                        if (mInfos[slotIndex].mobileSignalIconId
                                != R.drawable.ic_qs_no_calling_sms) {
                            if (displayCallStrengthIcon) {
                                mInfos[slotIndex] = new CellSignalState(
                                        true,
                                        statusIcon.icon,
                                        statusIcon.contentDescription,
                                        "",
                                        false,
                                        mProviderModel);
                            } else {
                                mInfos[slotIndex] = new CellSignalState(
                                        true,
                                        R.drawable.ic_qs_sim_card,
                                        "",
                                        "",
                                        false,
                                        mProviderModel);
                            }
                            mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
                        }
                    }
                }

                @Override
                public void setNoSims(boolean hasNoSims, boolean simDetected) {
                    if (hasNoSims) {
                        for (int i = 0; i < SIM_SLOTS; i++) {
                            mInfos[i] = mInfos[i].changeVisibility(false);
                        }
                    }
                    mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
                }
            };

    private static class Callback implements CarrierTextManager.CarrierTextCallback {
        private H mHandler;

        Callback(H handler) {
            mHandler = handler;
        }

        @Override
        public void updateCarrierInfo(CarrierTextManager.CarrierTextCallbackInfo info) {
            mHandler.obtainMessage(H.MSG_UPDATE_CARRIER_INFO, info).sendToTarget();
        }
    }

    private QSCarrierGroupController(QSCarrierGroup view, ActivityStarter activityStarter,
            @Background Handler bgHandler, @Main Looper mainLooper,
            NetworkController networkController,
            CarrierTextManager.Builder carrierTextManagerBuilder, Context context,
            CarrierConfigTracker carrierConfigTracker, FeatureFlags featureFlags) {

        if (featureFlags.isCombinedStatusBarSignalIconsEnabled()) {
            mProviderModel = true;
        } else {
            mProviderModel = false;
        }
        mActivityStarter = activityStarter;
        mBgHandler = bgHandler;
        mNetworkController = networkController;
        mCarrierTextManager = carrierTextManagerBuilder
                .setShowAirplaneMode(false)
                .setShowMissingSim(false)
                .build();
        mCarrierConfigTracker = carrierConfigTracker;
        View.OnClickListener onClickListener = v -> {
            if (!v.isVisibleToUser()) {
                return;
            }
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(Settings.ACTION_WIRELESS_SETTINGS), 0);
        };
        view.setOnClickListener(onClickListener);
        mNoSimTextView = view.getNoSimTextView();
        mNoSimTextView.setOnClickListener(onClickListener);
        mMainHandler = new H(mainLooper, this::handleUpdateCarrierInfo, this::handleUpdateState);
        mCallback = new Callback(mMainHandler);

        mCarrierGroups[0] = view.getCarrier1View();
        mCarrierGroups[1] = view.getCarrier2View();
        mCarrierGroups[2] = view.getCarrier3View();

        mCarrierDividers[0] = view.getCarrierDivider1();
        mCarrierDividers[1] = view.getCarrierDivider2();

        for (int i = 0; i < SIM_SLOTS; i++) {
            mInfos[i] = new CellSignalState(
                    true,
                    R.drawable.ic_qs_no_calling_sms,
                    context.getText(AccessibilityContentDescriptions.NO_CALLING).toString(),
                    "",
                    false,
                    mProviderModel);
            mLastSignalLevel[i] = TelephonyIcons.MOBILE_CALL_STRENGTH_ICONS[0];
            mLastSignalLevelDescription[i] =
                    context.getText(AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0])
                            .toString();
            mCarrierGroups[i].setOnClickListener(onClickListener);
        }
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                setListening(false);
            }
        });
    }

    @VisibleForTesting
    protected int getSlotIndex(int subscriptionId) {
        return SubscriptionManager.getSlotIndex(subscriptionId);
    }

    private boolean isSingleCarrier() {
        int carrierCount = 0;
        for (int i = 0; i < SIM_SLOTS; i++) {

            if (mInfos[i].visible) {
                carrierCount++;
            }
        }
        return carrierCount == 1;
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;

        mBgHandler.post(this::updateListeners);
    }

    private void updateListeners() {
        if (mListening) {
            if (mNetworkController.hasVoiceCallingFeature()) {
                mNetworkController.addCallback(mSignalCallback);
            }
            mCarrierTextManager.setListening(mCallback);
        } else {
            mNetworkController.removeCallback(mSignalCallback);
            mCarrierTextManager.setListening(null);
        }
    }


    @MainThread
    private void handleUpdateState() {
        if (!mMainHandler.getLooper().isCurrentThread()) {
            mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
            return;
        }

        if (isSingleCarrier()) {
            for (int i = 0; i < SIM_SLOTS; i++) {
                if (mInfos[i].visible
                        && mInfos[i].mobileSignalIconId == R.drawable.ic_qs_sim_card) {
                    mInfos[i] = new CellSignalState(true, R.drawable.ic_blank, "", "", false,
                            mProviderModel);
                }
            }
        }

        for (int i = 0; i < SIM_SLOTS; i++) {
            mCarrierGroups[i].updateState(mInfos[i]);
        }

        mCarrierDividers[0].setVisibility(
                mInfos[0].visible && mInfos[1].visible ? View.VISIBLE : View.GONE);
        // This tackles the case of slots 2 being available as well as at least one other.
        // In that case we show the second divider. Note that if both dividers are visible, it means
        // all three slots are in use, and that is correct.
        mCarrierDividers[1].setVisibility(
                (mInfos[1].visible && mInfos[2].visible)
                        || (mInfos[0].visible && mInfos[2].visible) ? View.VISIBLE : View.GONE);
    }

    @MainThread
    private void handleUpdateCarrierInfo(CarrierTextManager.CarrierTextCallbackInfo info) {
        if (!mMainHandler.getLooper().isCurrentThread()) {
            mMainHandler.obtainMessage(H.MSG_UPDATE_CARRIER_INFO, info).sendToTarget();
            return;
        }

        mNoSimTextView.setVisibility(View.GONE);
        if (!info.airplaneMode && info.anySimReady) {
            boolean[] slotSeen = new boolean[SIM_SLOTS];
            if (info.listOfCarriers.length == info.subscriptionIds.length) {
                for (int i = 0; i < SIM_SLOTS && i < info.listOfCarriers.length; i++) {
                    int slot = getSlotIndex(info.subscriptionIds[i]);
                    if (slot >= SIM_SLOTS) {
                        Log.w(TAG, "updateInfoCarrier - slot: " + slot);
                        continue;
                    }
                    if (slot == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                        Log.e(TAG,
                                "Invalid SIM slot index for subscription: "
                                        + info.subscriptionIds[i]);
                        continue;
                    }
                    mInfos[slot] = mInfos[slot].changeVisibility(true);
                    slotSeen[slot] = true;
                    mCarrierGroups[slot].setCarrierText(
                            info.listOfCarriers[i].toString().trim());
                    mCarrierGroups[slot].setVisibility(View.VISIBLE);
                }
                for (int i = 0; i < SIM_SLOTS; i++) {
                    if (!slotSeen[i]) {
                        mInfos[i] = mInfos[i].changeVisibility(false);
                        mCarrierGroups[i].setVisibility(View.GONE);
                    }
                }
            } else {
                Log.e(TAG, "Carrier information arrays not of same length");
            }
        } else {
            // No sims or airplane mode (but not WFC). Do not show QSCarrierGroup, instead just show
            // info.carrierText in a different view.
            for (int i = 0; i < SIM_SLOTS; i++) {
                mInfos[i] = mInfos[i].changeVisibility(false);
                mCarrierGroups[i].setCarrierText("");
                mCarrierGroups[i].setVisibility(View.GONE);
            }
            mNoSimTextView.setText(info.carrierText);
            if (!TextUtils.isEmpty(info.carrierText)) {
                mNoSimTextView.setVisibility(View.VISIBLE);
            }
        }
        handleUpdateState(); // handleUpdateCarrierInfo is always called from main thread.
    }

    private static class H extends Handler {
        private Consumer<CarrierTextManager.CarrierTextCallbackInfo> mUpdateCarrierInfo;
        private Runnable mUpdateState;
        static final int MSG_UPDATE_CARRIER_INFO = 0;
        static final int MSG_UPDATE_STATE = 1;

        H(Looper looper,
                Consumer<CarrierTextManager.CarrierTextCallbackInfo> updateCarrierInfo,
                Runnable updateState) {
            super(looper);
            mUpdateCarrierInfo = updateCarrierInfo;
            mUpdateState = updateState;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_CARRIER_INFO:
                    mUpdateCarrierInfo.accept(
                            (CarrierTextManager.CarrierTextCallbackInfo) msg.obj);
                    break;
                case MSG_UPDATE_STATE:
                    mUpdateState.run();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    public static class Builder {
        private QSCarrierGroup mView;
        private final ActivityStarter mActivityStarter;
        private final Handler mHandler;
        private final Looper mLooper;
        private final NetworkController mNetworkController;
        private final CarrierTextManager.Builder mCarrierTextControllerBuilder;
        private final Context mContext;
        private final CarrierConfigTracker mCarrierConfigTracker;
        private final FeatureFlags mFeatureFlags;

        @Inject
        public Builder(ActivityStarter activityStarter, @Background Handler handler,
                @Main Looper looper, NetworkController networkController,
                CarrierTextManager.Builder carrierTextControllerBuilder, Context context,
                CarrierConfigTracker carrierConfigTracker, FeatureFlags featureFlags) {
            mActivityStarter = activityStarter;
            mHandler = handler;
            mLooper = looper;
            mNetworkController = networkController;
            mCarrierTextControllerBuilder = carrierTextControllerBuilder;
            mContext = context;
            mCarrierConfigTracker = carrierConfigTracker;
            mFeatureFlags = featureFlags;
        }

        public Builder setQSCarrierGroup(QSCarrierGroup view) {
            mView = view;
            return this;
        }

        public QSCarrierGroupController build() {
            return new QSCarrierGroupController(mView, mActivityStarter, mHandler, mLooper,
                    mNetworkController, mCarrierTextControllerBuilder, mContext,
                    mCarrierConfigTracker, mFeatureFlags);
        }
    }
}
