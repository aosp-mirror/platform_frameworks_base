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

import static android.os.PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED;
import static android.os.PowerManager.SOUND_TRIGGER_MODE_ALL_ENABLED;
import static android.os.PowerManager.SOUND_TRIGGER_MODE_CRITICAL_ONLY;

import com.android.internal.annotations.GuardedBy;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages device state events which require pausing SoundTrigger recognition
 *
 * @hide
 */
public class DeviceStateHandler implements PhoneCallStateHandler.Callback {

    public static final long CALL_INACTIVE_MSG_DELAY_MS = 1000;

    public interface DeviceStateListener {
        void onSoundTriggerDeviceStateUpdate(SoundTriggerDeviceState state);
    }

    public enum SoundTriggerDeviceState {
        DISABLE, // The device state requires all SoundTrigger sessions are disabled
        CRITICAL, // The device state requires all non-critical SoundTrigger sessions are disabled
        ENABLE // The device state permits all SoundTrigger sessions
    }

    private final Object mLock = new Object();

    private final EventLogger mEventLogger;

    @GuardedBy("mLock")
    SoundTriggerDeviceState mSoundTriggerDeviceState = SoundTriggerDeviceState.ENABLE;

    // Individual components of the SoundTriggerDeviceState
    @GuardedBy("mLock")
    private int mSoundTriggerPowerSaveMode = SOUND_TRIGGER_MODE_ALL_ENABLED;

    @GuardedBy("mLock")
    private boolean mIsPhoneCallOngoing = false;

    // There can only be one pending notify at any given time.
    // If any phone state change comes in between, we will cancel the previous pending
    // task.
    @GuardedBy("mLock")
    private NotificationTask mPhoneStateChangePendingNotify = null;

    private Set<DeviceStateListener> mCallbackSet = ConcurrentHashMap.newKeySet(4);

    private final Executor mDelayedNotificationExecutor = Executors.newSingleThreadExecutor();

    private final Executor mCallbackExecutor;

    public void onPowerModeChanged(int soundTriggerPowerSaveMode) {
        mEventLogger.enqueue(new SoundTriggerPowerEvent(soundTriggerPowerSaveMode));
        synchronized (mLock) {
            if (soundTriggerPowerSaveMode == mSoundTriggerPowerSaveMode) {
                // No state change, nothing to do
                return;
            }
            mSoundTriggerPowerSaveMode = soundTriggerPowerSaveMode;
            evaluateStateChange();
        }
    }

    @Override
    public void onPhoneCallStateChanged(boolean isInPhoneCall) {
        mEventLogger.enqueue(new PhoneCallEvent(isInPhoneCall));
        synchronized (mLock) {
            if (mIsPhoneCallOngoing == isInPhoneCall) {
                // no change, nothing to do
                return;
            }
            // Clear any pending notification
            if (mPhoneStateChangePendingNotify != null) {
                mPhoneStateChangePendingNotify.cancel();
                mPhoneStateChangePendingNotify = null;
            }
            mIsPhoneCallOngoing = isInPhoneCall;
            if (!mIsPhoneCallOngoing) {
                // State has changed from call to no call, delay notification
                mPhoneStateChangePendingNotify = new NotificationTask(
                        new Runnable() {
                            @Override
                            public void run() {
                                synchronized (mLock) {
                                    if (mPhoneStateChangePendingNotify != null &&
                                            mPhoneStateChangePendingNotify.runnableEquals(this)) {

                                        mPhoneStateChangePendingNotify = null;
                                        evaluateStateChange();
                                    }
                                }
                            }
                        },
                        CALL_INACTIVE_MSG_DELAY_MS);
                mDelayedNotificationExecutor.execute(mPhoneStateChangePendingNotify);
            } else {
                evaluateStateChange();
            }
        }
    }

    /** Note, we expect initial callbacks immediately following construction */
    public DeviceStateHandler(Executor callbackExecutor, EventLogger eventLogger) {
        mCallbackExecutor = Objects.requireNonNull(callbackExecutor);
        mEventLogger = Objects.requireNonNull(eventLogger);
    }

    public SoundTriggerDeviceState getDeviceState() {
        synchronized (mLock) {
            return mSoundTriggerDeviceState;
        }
    }

    public void registerListener(DeviceStateListener callback) {
        final var state = getDeviceState();
        mCallbackExecutor.execute(
                () -> callback.onSoundTriggerDeviceStateUpdate(state));
        mCallbackSet.add(callback);
    }

    public void unregisterListener(DeviceStateListener callback) {
        mCallbackSet.remove(callback);
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("DeviceState: " + mSoundTriggerDeviceState.name());
            pw.println("PhoneState: " + mIsPhoneCallOngoing);
            pw.println("PowerSaveMode: " + mSoundTriggerPowerSaveMode);
        }
    }

    @GuardedBy("mLock")
    private void evaluateStateChange() {
        // We should wait until any pending delays are complete to update.
        // We will eventually get called by the notification task, or something which
        // cancels it.
        // Additionally, if there isn't a state change, there is nothing to update.
        SoundTriggerDeviceState newState = computeState();
        if (mPhoneStateChangePendingNotify != null || mSoundTriggerDeviceState == newState) {
            return;
        }

        mSoundTriggerDeviceState = newState;
        mEventLogger.enqueue(new DeviceStateEvent(mSoundTriggerDeviceState));
        final var state = mSoundTriggerDeviceState;
        for (var callback : mCallbackSet) {
            mCallbackExecutor.execute(
                    () -> callback.onSoundTriggerDeviceStateUpdate(state));
        }
    }

    @GuardedBy("mLock")
    private SoundTriggerDeviceState computeState() {
        if (mIsPhoneCallOngoing) {
            return SoundTriggerDeviceState.DISABLE;
        }
        return switch (mSoundTriggerPowerSaveMode) {
            case SOUND_TRIGGER_MODE_ALL_ENABLED -> SoundTriggerDeviceState.ENABLE;
            case SOUND_TRIGGER_MODE_CRITICAL_ONLY -> SoundTriggerDeviceState.CRITICAL;
            case SOUND_TRIGGER_MODE_ALL_DISABLED -> SoundTriggerDeviceState.DISABLE;
            default -> throw new IllegalStateException(
                    "Received unexpected power state code" + mSoundTriggerPowerSaveMode);
        };
    }

    /**
     * One-shot, cancellable task which runs after a delay. Run must only be called once, from a
     * single thread. Cancel can be called from any other thread.
     */
    private static class NotificationTask implements Runnable {
        private final Runnable mRunnable;
        private final long mWaitInMillis;

        private final CountDownLatch mCancelLatch = new CountDownLatch(1);

        NotificationTask(Runnable r, long waitInMillis) {
            mRunnable = r;
            mWaitInMillis = waitInMillis;
        }

        void cancel() {
            mCancelLatch.countDown();
        }

        // Used for determining task equality.
        boolean runnableEquals(Runnable runnable) {
            return mRunnable == runnable;
        }

        public void run() {
            try {
                if (!mCancelLatch.await(mWaitInMillis, TimeUnit.MILLISECONDS)) {
                    mRunnable.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Unexpected InterruptedException", e);
            }
        }
    }

    private static class PhoneCallEvent extends EventLogger.Event {
        final boolean mIsInPhoneCall;

        PhoneCallEvent(boolean isInPhoneCall) {
            mIsInPhoneCall = isInPhoneCall;
        }

        @Override
        public String eventToString() {
            return "PhoneCallChange - inPhoneCall: " + mIsInPhoneCall;
        }
    }

    private static class SoundTriggerPowerEvent extends EventLogger.Event {
        final int mSoundTriggerPowerState;

        SoundTriggerPowerEvent(int soundTriggerPowerState) {
            mSoundTriggerPowerState = soundTriggerPowerState;
        }

        @Override
        public String eventToString() {
            return "SoundTriggerPowerChange: " + stateToString();
        }

        private String stateToString() {
            return switch (mSoundTriggerPowerState) {
                case SOUND_TRIGGER_MODE_ALL_ENABLED -> "All enabled";
                case SOUND_TRIGGER_MODE_CRITICAL_ONLY -> "Critical only";
                case SOUND_TRIGGER_MODE_ALL_DISABLED -> "All disabled";
                default -> "Unknown power state: " + mSoundTriggerPowerState;
            };
        }
    }

    private static class DeviceStateEvent extends EventLogger.Event {
        final SoundTriggerDeviceState mSoundTriggerDeviceState;

        DeviceStateEvent(SoundTriggerDeviceState soundTriggerDeviceState) {
            mSoundTriggerDeviceState = soundTriggerDeviceState;
        }

        @Override
        public String eventToString() {
            return "DeviceStateChange: " + mSoundTriggerDeviceState.name();
        }
    }
}
