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
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.doze.DozeHost;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarStateController.StateListener;

/**
 * Controller which handles all the doze animations of the scrims.
 */
public class DozeScrimController implements StateListener {
    private static final String TAG = "DozeScrimController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final DozeParameters mDozeParameters;
    private final Handler mHandler = new Handler();

    private boolean mDozing;
    private DozeHost.PulseCallback mPulseCallback;
    private int mPulseReason;
    private boolean mFullyPulsing;

    private final ScrimController.Callback mScrimCallback = new ScrimController.Callback() {
        @Override
        public void onDisplayBlanked() {
            if (DEBUG) {
                Log.d(TAG, "Pulse in, mDozing=" + mDozing + " mPulseReason="
                        + DozeLog.reasonToString(mPulseReason));
            }
            if (!mDozing) {
                return;
            }

            // Signal that the pulse is ready to turn the screen on and draw.
            pulseStarted();
        }

        @Override
        public void onFinished() {
            if (DEBUG) {
                Log.d(TAG, "Pulse in finished, mDozing=" + mDozing);
            }
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
            mFullyPulsing = true;
        }

        /**
         * Transition was aborted before it was over.
         */
        @Override
        public void onCancelled() {
            pulseFinished();
        }
    };

    public DozeScrimController(DozeParameters dozeParameters) {
        mDozeParameters = dozeParameters;
        //Never expected to be destroyed
        Dependency.get(StatusBarStateController.class).addCallback(this);
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
            if (DEBUG) {
                Log.d(TAG, "Pulse supressed. Dozing: " + mDozeParameters + " had callback? "
                        + (mPulseCallback != null));
            }
            // Pulse suppressed.
            callback.onPulseFinished();
            return;
        }

        // Begin pulse. Note that it's very important that the pulse finished callback
        // be invoked when we're done so that the caller can drop the pulse wakelock.
        mPulseCallback = callback;
        mPulseReason = reason;
    }

    public void pulseOutNow() {
        if (mPulseCallback != null && mFullyPulsing) {
            mPulseOut.run();
        }
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

    private void cancelPulsing() {
        if (mPulseCallback != null) {
            if (DEBUG) Log.d(TAG, "Cancel pulsing");
            mFullyPulsing = false;
            mHandler.removeCallbacks(mPulseOut);
            mHandler.removeCallbacks(mPulseOutExtended);
            pulseFinished();
        }
    }

    private void pulseStarted() {
        DozeLog.tracePulseStart(mPulseReason);
        if (mPulseCallback != null) {
            mPulseCallback.onPulseStarted();
        }
    }

    private void pulseFinished() {
        DozeLog.tracePulseFinish();
        if (mPulseCallback != null) {
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
            mFullyPulsing = false;
            mHandler.removeCallbacks(mPulseOut);
            mHandler.removeCallbacks(mPulseOutExtended);
            if (DEBUG) Log.d(TAG, "Pulse out, mDozing=" + mDozing);
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
        setDozing(isDozing);
    }
}