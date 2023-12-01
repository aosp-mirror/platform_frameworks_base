/**
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.soundtrigger;

import android.telephony.Annotation;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.telephony.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles monitoring telephony call state across active subscriptions.
 *
 * @hide
 */
public class PhoneCallStateHandler {

    public interface Callback {
        void onPhoneCallStateChanged(boolean isInPhoneCall);
    }

    private final Object mLock = new Object();

    // Actually never contended due to executor.
    @GuardedBy("mLock")
    private final List<MyCallStateListener> mListenerList = new ArrayList<>();

    private final AtomicBoolean mIsPhoneCallOngoing = new AtomicBoolean(false);

    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyManager mTelephonyManager;
    private final Callback mCallback;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    public PhoneCallStateHandler(
            SubscriptionManager subscriptionManager,
            TelephonyManager telephonyManager,
            Callback callback) {
        mSubscriptionManager = Objects.requireNonNull(subscriptionManager);
        mTelephonyManager = Objects.requireNonNull(telephonyManager);
        mCallback = Objects.requireNonNull(callback);
        mSubscriptionManager.addOnSubscriptionsChangedListener(
                mExecutor,
                new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        updateTelephonyListeners();
                    }

                    @Override
                    public void onAddListenerFailed() {
                        Slog.wtf(
                                "SoundTriggerPhoneCallStateHandler",
                                "Failed to add a telephony listener");
                    }
                });
    }

    private final class MyCallStateListener extends TelephonyCallback
            implements TelephonyCallback.CallStateListener {

        final TelephonyManager mTelephonyManagerForSubId;

        // Manager corresponding to the sub-id
        MyCallStateListener(TelephonyManager telephonyManager) {
            mTelephonyManagerForSubId = telephonyManager;
        }

        void cleanup() {
            mExecutor.execute(() -> mTelephonyManagerForSubId.unregisterTelephonyCallback(this));
        }

        @Override
        public void onCallStateChanged(int unused) {
            updateCallStatus();
        }
    }

    /** Compute the current call status, and dispatch callback if it has changed. */
    private void updateCallStatus() {
        boolean callStatus = checkCallStatus();
        if (mIsPhoneCallOngoing.compareAndSet(!callStatus, callStatus)) {
            mCallback.onPhoneCallStateChanged(callStatus);
        }
    }

    /**
     * Synchronously query the current telephony call state across all subscriptions
     *
     * @return - {@code true} if in call, {@code false} if not in call.
     */
    private boolean checkCallStatus() {
        List<SubscriptionInfo> infoList = mSubscriptionManager.getActiveSubscriptionInfoList();
        if (infoList == null) return false;
        if (!Flags.enforceTelephonyFeatureMapping()) {
            return infoList.stream()
                    .filter(s -> (s.getSubscriptionId()
                            != SubscriptionManager.INVALID_SUBSCRIPTION_ID))
                    .anyMatch(s -> isCallOngoingFromState(
                            mTelephonyManager
                                    .createForSubscriptionId(s.getSubscriptionId())
                                    .getCallStateForSubscription()));
        } else {
            return infoList.stream()
                    .filter(s -> (s.getSubscriptionId()
                            != SubscriptionManager.INVALID_SUBSCRIPTION_ID))
                    .anyMatch(s -> {
                        try {
                            return isCallOngoingFromState(mTelephonyManager
                                    .createForSubscriptionId(s.getSubscriptionId())
                                    .getCallStateForSubscription());
                        } catch (UnsupportedOperationException e) {
                            return false;
                        }
                    });
        }
    }

    private void updateTelephonyListeners() {
        synchronized (mLock) {
            for (var listener : mListenerList) {
                listener.cleanup();
            }
            mListenerList.clear();
            List<SubscriptionInfo> infoList = mSubscriptionManager.getActiveSubscriptionInfoList();
            if (infoList == null) return;
            infoList.stream()
                    .filter(s -> s.getSubscriptionId()
                                            != SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                    .map(s -> mTelephonyManager.createForSubscriptionId(s.getSubscriptionId()))
                    .forEach(manager -> {
                        synchronized (mLock) {
                            var listener = new MyCallStateListener(manager);
                            mListenerList.add(listener);
                            manager.registerTelephonyCallback(mExecutor, listener);
                        }
                    });
        }
    }

    private static boolean isCallOngoingFromState(@Annotation.CallState int callState) {
        return switch (callState) {
            case TelephonyManager.CALL_STATE_IDLE, TelephonyManager.CALL_STATE_RINGING -> false;
            case TelephonyManager.CALL_STATE_OFFHOOK -> true;
            default -> throw new IllegalStateException(
                    "Received unexpected call state from Telephony Manager: " + callState);
        };
    }
}
