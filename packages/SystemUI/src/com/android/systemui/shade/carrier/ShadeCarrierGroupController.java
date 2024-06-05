/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade.carrier;

import static android.telephony.SubscriptionManager.INVALID_SIM_SLOT_INDEX;
import static android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
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
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.connectivity.MobileDataIndicators;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.connectivity.SignalCallback;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconsBinder;
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernShadeCarrierGroupMobileView;
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel;
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.ShadeCarrierGroupMobileIconViewModel;
import com.android.systemui.util.CarrierConfigTracker;

import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;

public class ShadeCarrierGroupController {
    private static final String TAG = "ShadeCarrierGroup";

    /**
     * Support up to 3 slots which is what's supported by {@link TelephonyManager#getPhoneCount}
     */
    private static final int SIM_SLOTS = 3;

    private final ActivityStarter mActivityStarter;
    private final Handler mBgHandler;
    private final Context mContext;
    private final NetworkController mNetworkController;
    private final CarrierTextManager mCarrierTextManager;
    private final TextView mNoSimTextView;
    // Non final for testing
    private H mMainHandler;
    private final Callback mCallback;
    private final MobileIconsViewModel mMobileIconsViewModel;
    private final MobileContextProvider mMobileContextProvider;
    private final StatusBarPipelineFlags mStatusBarPipelineFlags;
    private boolean mListening;
    private final CellSignalState[] mInfos =
            new CellSignalState[SIM_SLOTS];
    private View[] mCarrierDividers = new View[SIM_SLOTS - 1];
    private ShadeCarrier[] mCarrierGroups = new ShadeCarrier[SIM_SLOTS];
    private int[] mLastSignalLevel = new int[SIM_SLOTS];
    private String[] mLastSignalLevelDescription = new String[SIM_SLOTS];
    private final CarrierConfigTracker mCarrierConfigTracker;

    private boolean mIsSingleCarrier;
    @Nullable
    private OnSingleCarrierChangedListener mOnSingleCarrierChangedListener;

    private final SlotIndexResolver mSlotIndexResolver;

    private final ShadeCarrierGroupControllerLogger mLogger;

    private final SignalCallback mSignalCallback = new SignalCallback() {
                @Override
                public void setMobileDataIndicators(@NonNull MobileDataIndicators indicators) {
                    int slotIndex = getSlotIndex(indicators.subId);
                    if (slotIndex >= SIM_SLOTS) {
                        Log.w(TAG, "setMobileDataIndicators - slot: " + slotIndex);
                        return;
                    }
                    if (slotIndex == INVALID_SIM_SLOT_INDEX) {
                        Log.e(TAG, "Invalid SIM slot index for subscription: " + indicators.subId);
                        return;
                    }
                    mInfos[slotIndex] = new CellSignalState(
                            indicators.statusIcon.visible,
                            indicators.statusIcon.icon,
                            indicators.statusIcon.contentDescription,
                            indicators.typeContentDescription.toString(),
                            indicators.roaming
                    );
                    mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
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

    private ShadeCarrierGroupController(
            ShadeCarrierGroup view,
            ActivityStarter activityStarter,
            @Background Handler bgHandler,
            @Main Looper mainLooper,
            ShadeCarrierGroupControllerLogger logger,
            NetworkController networkController,
            CarrierTextManager.Builder carrierTextManagerBuilder,
            Context context,
            CarrierConfigTracker carrierConfigTracker,
            SlotIndexResolver slotIndexResolver,
            MobileUiAdapter mobileUiAdapter,
            MobileContextProvider mobileContextProvider,
            StatusBarPipelineFlags statusBarPipelineFlags
    ) {
        mContext = context;
        mActivityStarter = activityStarter;
        mBgHandler = bgHandler;
        mLogger = logger;
        mNetworkController = networkController;
        mStatusBarPipelineFlags = statusBarPipelineFlags;
        mCarrierTextManager = carrierTextManagerBuilder
                .setShowAirplaneMode(false)
                .setShowMissingSim(false)
                .setDebugLocationString("Shade")
                .build();
        mCarrierConfigTracker = carrierConfigTracker;
        mSlotIndexResolver = slotIndexResolver;
        View.OnClickListener onClickListener = v -> {
            if (!v.isVisibleToUser()) {
                return;
            }
            mActivityStarter.postStartActivityDismissingKeyguard(
                    new Intent(Settings.ACTION_WIRELESS_SETTINGS), 0);
        };

        mNoSimTextView = view.getNoSimTextView();
        mNoSimTextView.setOnClickListener(onClickListener);
        mMainHandler = new H(mainLooper, this::handleUpdateCarrierInfo, this::handleUpdateState);
        mCallback = new Callback(mMainHandler);

        mCarrierGroups[0] = view.getCarrier1View();
        mCarrierGroups[1] = view.getCarrier2View();
        mCarrierGroups[2] = view.getCarrier3View();

        mMobileContextProvider = mobileContextProvider;
        mMobileIconsViewModel = mobileUiAdapter.getMobileIconsViewModel();

        if (mStatusBarPipelineFlags.useNewShadeCarrierGroupMobileIcons()) {
            mobileUiAdapter.setShadeCarrierGroupController(this);
            MobileIconsBinder.bind(view, mMobileIconsViewModel);
        }

        mCarrierDividers[0] = view.getCarrierDivider1();
        mCarrierDividers[1] = view.getCarrierDivider2();

        for (int i = 0; i < SIM_SLOTS; i++) {
            mInfos[i] = new CellSignalState(
                    false,
                    R.drawable.ic_shade_no_calling_sms,
                    context.getText(AccessibilityContentDescriptions.NO_CALLING).toString(),
                    "",
                    false);
            mLastSignalLevel[i] = TelephonyIcons.MOBILE_CALL_STRENGTH_ICONS[0];
            mLastSignalLevelDescription[i] =
                    context.getText(AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0])
                            .toString();
            mCarrierGroups[i].setOnClickListener(onClickListener);
        }
        mIsSingleCarrier = computeIsSingleCarrier();
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

    /** Updates the number of visible mobile icons using the new pipeline. */
    public void updateModernMobileIcons(List<Integer> subIds) {
        if (!mStatusBarPipelineFlags.useNewShadeCarrierGroupMobileIcons()) {
            Log.d(TAG, "ignoring new pipeline callback because new mobile icon is disabled");
            return;
        }

        for (ShadeCarrier carrier : mCarrierGroups) {
            carrier.removeModernMobileView();
        }

        List<IconData> iconDataList = processSubIdList(subIds);

        for (IconData iconData : iconDataList) {
            ShadeCarrier carrier = mCarrierGroups[iconData.slotIndex];

            Context mobileContext =
                    mMobileContextProvider.getMobileContextForSub(iconData.subId, mContext);
            ModernShadeCarrierGroupMobileView modernMobileView = ModernShadeCarrierGroupMobileView
                    .constructAndBind(
                        mobileContext,
                        mMobileIconsViewModel.getLogger(),
                        "mobile_carrier_shade_group",
                        (ShadeCarrierGroupMobileIconViewModel) mMobileIconsViewModel
                                .viewModelForSub(iconData.subId,
                                    StatusBarLocation.SHADE_CARRIER_GROUP)
                    );
            carrier.addModernMobileView(modernMobileView);
        }
    }

    @VisibleForTesting
    List<IconData> processSubIdList(List<Integer> subIds) {
        return subIds
                .stream()
                .limit(SIM_SLOTS)
                .map(subId -> new IconData(subId, getSlotIndex(subId)))
                .filter(iconData ->
                        iconData.slotIndex < SIM_SLOTS
                                && iconData.slotIndex != INVALID_SIM_SLOT_INDEX
                )
                .toList();
    }

    @VisibleForTesting
    protected int getSlotIndex(int subscriptionId) {
        return mSlotIndexResolver.getSlotIndex(subscriptionId);
    }

    @VisibleForTesting
    protected int getShadeCarrierVisibility(int index) {
        return mCarrierGroups[index].getVisibility();
    }

    /**
     * Sets a {@link OnSingleCarrierChangedListener}.
     *
     * This will get notified when the number of carriers changes between 1 and "not one".
     * @param listener
     */
    public void setOnSingleCarrierChangedListener(
            @Nullable OnSingleCarrierChangedListener listener) {
        mOnSingleCarrierChangedListener = listener;
    }

    public boolean isSingleCarrier() {
        return mIsSingleCarrier;
    }

    private boolean computeIsSingleCarrier() {
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

        boolean singleCarrier = computeIsSingleCarrier();

        if (singleCarrier) {
            for (int i = 0; i < SIM_SLOTS; i++) {
                if (mInfos[i].visible
                        && mInfos[i].mobileSignalIconId == R.drawable.ic_shade_sim_card) {
                    mInfos[i] = new CellSignalState(true, R.drawable.ic_blank, "", "", false);
                }
            }
        }

        if (!mStatusBarPipelineFlags.useNewShadeCarrierGroupMobileIcons()) {
            for (int i = 0; i < SIM_SLOTS; i++) {
                mCarrierGroups[i].updateState(mInfos[i], singleCarrier);
            }
        }

        mCarrierDividers[0].setVisibility(
                mInfos[0].visible && mInfos[1].visible ? View.VISIBLE : View.GONE);
        // This tackles the case of slots 2 being available as well as at least one other.
        // In that case we show the second divider. Note that if both dividers are visible, it means
        // all three slots are in use, and that is correct.
        mCarrierDividers[1].setVisibility(
                (mInfos[1].visible && mInfos[2].visible)
                        || (mInfos[0].visible && mInfos[2].visible) ? View.VISIBLE : View.GONE);
        if (mIsSingleCarrier != singleCarrier) {
            mIsSingleCarrier = singleCarrier;
            if (mOnSingleCarrierChangedListener != null) {
                mOnSingleCarrierChangedListener.onSingleCarrierChanged(singleCarrier);
            }
        }
    }

    @MainThread
    private void handleUpdateCarrierInfo(CarrierTextManager.CarrierTextCallbackInfo info) {
        if (!mMainHandler.getLooper().isCurrentThread()) {
            mMainHandler.obtainMessage(H.MSG_UPDATE_CARRIER_INFO, info).sendToTarget();
            return;
        }

        mLogger.logHandleUpdateCarrierInfo(info);

        mNoSimTextView.setVisibility(View.GONE);
        if (info.isInSatelliteMode) {
            mLogger.logUsingSatelliteText(info.carrierText);
            showSingleText(info.carrierText);
        } else if (!info.airplaneMode && info.anySimReady) {
            boolean[] slotSeen = new boolean[SIM_SLOTS];
            if (info.listOfCarriers.length == info.subscriptionIds.length) {
                mLogger.logUsingSimViews();
                for (int i = 0; i < SIM_SLOTS && i < info.listOfCarriers.length; i++) {
                    int slot = getSlotIndex(info.subscriptionIds[i]);
                    if (slot >= SIM_SLOTS) {
                        Log.w(TAG, "updateInfoCarrier - slot: " + slot);
                        continue;
                    }
                    if (slot == INVALID_SIM_SLOT_INDEX) {
                        Log.e(TAG,
                                "Invalid SIM slot index for subscription: "
                                        + info.subscriptionIds[i]);
                        continue;
                    }
                    String carrierText = info.listOfCarriers[i].toString().trim();
                    if (!TextUtils.isEmpty(carrierText)) {
                        mInfos[slot] = mInfos[slot].changeVisibility(true);
                        slotSeen[slot] = true;
                        mCarrierGroups[slot].setCarrierText(carrierText);
                        mCarrierGroups[slot].setVisibility(View.VISIBLE);
                    }
                }
                for (int i = 0; i < SIM_SLOTS; i++) {
                    if (!slotSeen[i]) {
                        mInfos[i] = mInfos[i].changeVisibility(false);
                        mCarrierGroups[i].setVisibility(View.GONE);
                    }
                }
            } else {
                mLogger.logInvalidArrayLengths(
                        info.listOfCarriers.length, info.subscriptionIds.length);
            }
        } else {
            // No sims or airplane mode (but not WFC), so just show the main carrier text.
            mLogger.logUsingNoSimView(info.carrierText);
            showSingleText(info.carrierText);
        }
        handleUpdateState(); // handleUpdateCarrierInfo is always called from main thread.
    }

    /**
     * Shows only the given text in a single TextView and hides ShadeCarrierGroup (which would show
     * individual SIM details).
     */
    private void showSingleText(CharSequence text) {
        for (int i = 0; i < SIM_SLOTS; i++) {
            mInfos[i] = mInfos[i].changeVisibility(false);
            mCarrierGroups[i].setCarrierText("");
            mCarrierGroups[i].setVisibility(View.GONE);
        }
        // TODO(b/341841138): Re-name this view now that it's being used for more than just the
        //  no-SIM case.
        mNoSimTextView.setText(text);
        if (!TextUtils.isEmpty(text)) {
            mNoSimTextView.setVisibility(View.VISIBLE);
        }
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
        private ShadeCarrierGroup mView;
        private final ActivityStarter mActivityStarter;
        private final Handler mHandler;
        private final Looper mLooper;
        private final ShadeCarrierGroupControllerLogger mLogger;
        private final NetworkController mNetworkController;
        private final CarrierTextManager.Builder mCarrierTextControllerBuilder;
        private final Context mContext;
        private final CarrierConfigTracker mCarrierConfigTracker;
        private final SlotIndexResolver mSlotIndexResolver;
        private final MobileUiAdapter mMobileUiAdapter;
        private final MobileContextProvider mMobileContextProvider;
        private final StatusBarPipelineFlags mStatusBarPipelineFlags;

        @Inject
        public Builder(
                ActivityStarter activityStarter,
                @Background Handler handler,
                @Main Looper looper,
                ShadeCarrierGroupControllerLogger logger,
                NetworkController networkController,
                CarrierTextManager.Builder carrierTextControllerBuilder,
                Context context,
                CarrierConfigTracker carrierConfigTracker,
                SlotIndexResolver slotIndexResolver,
                MobileUiAdapter mobileUiAdapter,
                MobileContextProvider mobileContextProvider,
                StatusBarPipelineFlags statusBarPipelineFlags
        ) {
            mActivityStarter = activityStarter;
            mHandler = handler;
            mLooper = looper;
            mLogger = logger;
            mNetworkController = networkController;
            mCarrierTextControllerBuilder = carrierTextControllerBuilder;
            mContext = context;
            mCarrierConfigTracker = carrierConfigTracker;
            mSlotIndexResolver = slotIndexResolver;
            mMobileUiAdapter = mobileUiAdapter;
            mMobileContextProvider = mobileContextProvider;
            mStatusBarPipelineFlags = statusBarPipelineFlags;
        }

        public Builder setShadeCarrierGroup(ShadeCarrierGroup view) {
            mView = view;
            return this;
        }

        public ShadeCarrierGroupController build() {
            return new ShadeCarrierGroupController(
                    mView,
                    mActivityStarter,
                    mHandler,
                    mLooper,
                    mLogger,
                    mNetworkController,
                    mCarrierTextControllerBuilder,
                    mContext,
                    mCarrierConfigTracker,
                    mSlotIndexResolver,
                    mMobileUiAdapter,
                    mMobileContextProvider,
                    mStatusBarPipelineFlags
            );
        }
    }

    /**
     * Notify when the state changes from 1 carrier to "not one" and viceversa
     */
    @FunctionalInterface
    public interface OnSingleCarrierChangedListener {
        void onSingleCarrierChanged(boolean isSingleCarrier);
    }

    /**
     * Interface for resolving slot index from subscription ID.
     */
    @FunctionalInterface
    public interface SlotIndexResolver {
        /**
         * Get slot index for given sub id.
         */
        int getSlotIndex(int subscriptionId);
    }

    /**
     * Default implementation for {@link SlotIndexResolver}.
     *
     * It retrieves the slot index using {@link SubscriptionManager#getSlotIndex}.
     */
    @SysUISingleton
    public static class SubscriptionManagerSlotIndexResolver implements SlotIndexResolver {

        @Inject
        public SubscriptionManagerSlotIndexResolver() {}

        @Override
        public int getSlotIndex(int subscriptionId) {
            return SubscriptionManager.getSlotIndex(subscriptionId);
        }
    }

    @VisibleForTesting
    static class IconData {
        public final int subId;
        public final int slotIndex;

        IconData(int subId, int slotIndex) {
            this.subId = subId;
            this.slotIndex = slotIndex;
        }
    }
}
