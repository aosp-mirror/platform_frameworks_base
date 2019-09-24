/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.systemui.Dependency.BG_HANDLER;
import static com.android.systemui.Dependency.BG_HANDLER_NAME;
import static com.android.systemui.Dependency.MAIN_LOOPER;
import static com.android.systemui.Dependency.MAIN_LOOPER_NAME;
import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.MainThread;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.keyguard.CarrierTextController;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.NetworkController;

import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Displays Carrier name and network status in QS
 */
public class QSCarrierGroup extends LinearLayout implements
        NetworkController.SignalCallback, View.OnClickListener {

    private static final String TAG = "QSCarrierGroup";
    /**
     * Support up to 3 slots which is what's supported by {@link TelephonyManager#getPhoneCount}
     */
    private static final int SIM_SLOTS = 3;
    private final NetworkController mNetworkController;
    private final Handler mBgHandler;
    private final H mMainHandler;

    private View[] mCarrierDividers = new View[SIM_SLOTS - 1];
    private QSCarrier[] mCarrierGroups = new QSCarrier[SIM_SLOTS];
    private TextView mNoSimTextView;
    private final CellSignalState[] mInfos = new CellSignalState[SIM_SLOTS];
    private CarrierTextController mCarrierTextController;
    private CarrierTextController.CarrierTextCallback mCallback;
    private ActivityStarter mActivityStarter;

    private boolean mListening;

    @Inject
    public QSCarrierGroup(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            NetworkController networkController, ActivityStarter activityStarter,
            @Named(BG_HANDLER_NAME) Handler handler,
            @Named(MAIN_LOOPER_NAME) Looper looper) {
        super(context, attrs);
        mNetworkController = networkController;
        mActivityStarter = activityStarter;
        mBgHandler = handler;
        mMainHandler = new H(looper, this::handleUpdateCarrierInfo, this::handleUpdateState);
        mCallback = new Callback(mMainHandler);
    }

    @VisibleForTesting
    protected CarrierTextController.CarrierTextCallback getCallback() {
        return mCallback;
    }

    @VisibleForTesting
    public QSCarrierGroup(Context context, AttributeSet attrs) {
        this(context, attrs,
                Dependency.get(NetworkController.class),
                Dependency.get(ActivityStarter.class),
                Dependency.get(BG_HANDLER),
                Dependency.get(MAIN_LOOPER));
    }

    @Override
    public void onClick(View v) {
        if (!v.isVisibleToUser()) return;
        mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                Settings.ACTION_WIRELESS_SETTINGS), 0);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mCarrierGroups[0] = findViewById(R.id.carrier1);
        mCarrierGroups[1] = findViewById(R.id.carrier2);
        mCarrierGroups[2] = findViewById(R.id.carrier3);

        mCarrierDividers[0] = findViewById(R.id.qs_carrier_divider1);
        mCarrierDividers[1] = findViewById(R.id.qs_carrier_divider2);

        mNoSimTextView = findViewById(R.id.no_carrier_text);

        for (int i = 0; i < SIM_SLOTS; i++) {
            mInfos[i] = new CellSignalState();
            mCarrierGroups[i].setOnClickListener(this);
        }
        mNoSimTextView.setOnClickListener(this);

        CharSequence separator = mContext.getString(
                com.android.internal.R.string.kg_text_message_separator);
        mCarrierTextController = new CarrierTextController(mContext, separator, false, false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;
        mBgHandler.post(this::updateListeners);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        super.onDetachedFromWindow();
    }

    private void updateListeners() {
        if (mListening) {
            if (mNetworkController.hasVoiceCallingFeature()) {
                mNetworkController.addCallback(this);
            }
            mCarrierTextController.setListening(mCallback);
        } else {
            mNetworkController.removeCallback(this);
            mCarrierTextController.setListening(null);
        }
    }

    @MainThread
    private void handleUpdateState() {
        if (!mMainHandler.getLooper().isCurrentThread()) {
            mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
            return;
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

    @VisibleForTesting
    protected int getSlotIndex(int subscriptionId) {
        return SubscriptionManager.getSlotIndex(subscriptionId);
    }

    @MainThread
    private void handleUpdateCarrierInfo(CarrierTextController.CarrierTextCallbackInfo info) {
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
                    mInfos[slot].visible = true;
                    slotSeen[slot] = true;
                    mCarrierGroups[slot].setCarrierText(
                            info.listOfCarriers[i].toString().trim());
                    mCarrierGroups[slot].setVisibility(View.VISIBLE);
                }
                for (int i = 0; i < SIM_SLOTS; i++) {
                    if (!slotSeen[i]) {
                        mInfos[i].visible = false;
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
                mInfos[i].visible = false;
                mCarrierGroups[i].setCarrierText("");
                mCarrierGroups[i].setVisibility(View.GONE);
            }
            mNoSimTextView.setText(info.carrierText);
            mNoSimTextView.setVisibility(View.VISIBLE);
        }
        handleUpdateState(); // handleUpdateCarrierInfo is always called from main thread.
    }

    @Override
    public void setMobileDataIndicators(NetworkController.IconState statusIcon,
            NetworkController.IconState qsIcon, int statusType,
            int qsType, boolean activityIn, boolean activityOut,
            String typeContentDescription,
            String description, boolean isWide, int subId, boolean roaming) {
        int slotIndex = getSlotIndex(subId);
        if (slotIndex >= SIM_SLOTS) {
            Log.w(TAG, "setMobileDataIndicators - slot: " + slotIndex);
            return;
        }
        if (slotIndex == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            Log.e(TAG, "Invalid SIM slot index for subscription: " + subId);
            return;
        }
        mInfos[slotIndex].visible = statusIcon.visible;
        mInfos[slotIndex].mobileSignalIconId = statusIcon.icon;
        mInfos[slotIndex].contentDescription = statusIcon.contentDescription;
        mInfos[slotIndex].typeContentDescription = typeContentDescription;
        mInfos[slotIndex].roaming = roaming;
        mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
    }

    @Override
    public void setNoSims(boolean hasNoSims, boolean simDetected) {
        if (hasNoSims) {
            for (int i = 0; i < SIM_SLOTS; i++) {
                mInfos[i].visible = false;
            }
        }
        mMainHandler.obtainMessage(H.MSG_UPDATE_STATE).sendToTarget();
    }

    static final class CellSignalState {
        boolean visible;
        int mobileSignalIconId;
        String contentDescription;
        String typeContentDescription;
        boolean roaming;
    }

    private static class H extends Handler {
        private Consumer<CarrierTextController.CarrierTextCallbackInfo> mUpdateCarrierInfo;
        private Runnable mUpdateState;
        static final int MSG_UPDATE_CARRIER_INFO = 0;
        static final int MSG_UPDATE_STATE = 1;

        H(Looper looper,
                Consumer<CarrierTextController.CarrierTextCallbackInfo> updateCarrierInfo,
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
                            (CarrierTextController.CarrierTextCallbackInfo) msg.obj);
                    break;
                case MSG_UPDATE_STATE:
                    mUpdateState.run();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private static class Callback implements CarrierTextController.CarrierTextCallback {
        private H mMainHandler;

        Callback(H handler) {
            mMainHandler = handler;
        }

        @Override
        public void updateCarrierInfo(CarrierTextController.CarrierTextCallbackInfo info) {
            mMainHandler.obtainMessage(H.MSG_UPDATE_CARRIER_INFO, info).sendToTarget();
        }
    }
}
