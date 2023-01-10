/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.os.Handler;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;

import javax.inject.Inject;

/**
 * Controller which handles all the doze animations of the scrims.
 */
@SysUISingleton
public class DozeScrimController implements StateListener {
    private final DozeLog mDozeLog;
    private final DozeParameters mDozeParameters;
    private final Handler mHandler = new Handler();

    private boolean mDozing;
    private DozeHost.PulseCallback mPulseCallback;
    private int mPulseReason;

    private final ScrimController.Callback mScrimCallback = new ScrimController.Callback() {
        @Override
        public void onDisplayBlanked() {
            if (!mDozing) {
                mDozeLog.tracePulseDropped("onDisplayBlanked - not dozing");
                return;
            }

            if (mPulseCallback != null) {
                // Signal that the pulse is ready to turn the screen on and draw.
                mDozeLog.tracePulseStart(mPulseReason);
                mPulseCallback.onPulseStarted();
            }
        }

        @Override
        public void onFinished() {
            mDozeLog.tracePulseEvent("scrimCallback-onFinished", mDozing, mPulseReason);

            if (!mDozing) {
                return;
            }
            // Notifications should time out on their own.  Pulses due to notifications should
            // instead be managed externally based off the notification's lifetime.
            // Dock also controls the time out by self.
            if (mPulseReason != DozeLog.PULSE_REASON_NOTIFICATION
                    && mPulseReason != DozeLog.PULSE_REASON_DOCKING) {
                mHandler.postDelayed(mPulseOut, mDozeParameters.getPulseVisibleDuration());
                mHandler.postDelayed(mPulseOutExtended,
                        mDozeParameters.getPulseVisibleDurationExtended());
            }
        }

        /**
         * Transition was aborted before it was over.
         */
        @Override
        public void onCancelled() {
            pulseFinished();
        }
    };

    @Inject
    public DozeScrimController(
            DozeParameters dozeParameters,
            DozeLog dozeLog,
            StatusBarStateController statusBarStateController
    ) {
        mDozeParameters = dozeParameters;
        // Never expected to be destroyed
        statusBarStateController.addCallback(this);
        mDozeLog = dozeLog;
    }

    @VisibleForTesting
    public void setDozing(boolean dozing) {
        if (mDozing == dozing) return;
        mDozing = dozing;
        if (!mDozing) {
            cancelPulsing();
        }
    }

    /** When dozing, fade screen contents in and out using the front scrim. */
    public void pulse(@NonNull DozeHost.PulseCallback callback, int reason) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        if (!mDozing || mPulseCallback != null) {
            // Pulse suppressed.
            callback.onPulseFinished();
            if (!mDozing) {
                mDozeLog.tracePulseDropped("pulse - device isn't dozing");
            } else {
                mDozeLog.tracePulseDropped("pulse - already has pulse callback mPulseCallback="
                        + mPulseCallback);
            }
            return;
        }

        // Begin pulse. Note that it's very important that the pulse finished callback
        // be invoked when we're done so that the caller can drop the pulse wakelock.
        mPulseCallback = callback;
        mPulseReason = reason;
    }

    public void pulseOutNow() {
        mPulseOut.run();
    }

    public boolean isPulsing() {
        return mPulseCallback != null;
    }

    public boolean isDozing() {
        return mDozing;
    }

    public void extendPulse() {
        mHandler.removeCallbacks(mPulseOut);
    }

    /**
     * When pulsing, cancel any timeouts that would take you out of the pulsing state.
     */
    public void cancelPendingPulseTimeout() {
        mHandler.removeCallbacks(mPulseOut);
        mHandler.removeCallbacks(mPulseOutExtended);
    }

    private void cancelPulsing() {
        if (mPulseCallback != null) {
            mDozeLog.tracePulseEvent("cancel", mDozing, mPulseReason);
            mHandler.removeCallbacks(mPulseOut);
            mHandler.removeCallbacks(mPulseOutExtended);
            pulseFinished();
        }
    }

    private void pulseFinished() {
        if (mPulseCallback != null) {
            mDozeLog.tracePulseFinish();
            mPulseCallback.onPulseFinished();
            mPulseCallback = null;
        }
    }

    private final Runnable mPulseOutExtended = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(mPulseOut);
            mPulseOut.run();
        }
    };

    private final Runnable mPulseOut = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(mPulseOut);
            mHandler.removeCallbacks(mPulseOutExtended);
            mDozeLog.tracePulseEvent("out", mDozing, mPulseReason);
            if (!mDozing) return;
            pulseFinished();
        }
    };

    public ScrimController.Callback getScrimCallback() {
        return mScrimCallback;
    }

    @Override
    public void onStateChanged(int newState) {
        // don't care
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        if (mDozing != isDozing) {
            mDozeLog.traceDozingChanged(isDozing);
        }

        setDozing(isDozing);
    }
}