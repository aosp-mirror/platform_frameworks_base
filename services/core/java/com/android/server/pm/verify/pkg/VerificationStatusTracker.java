/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.pm.verify.pkg;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

/**
 * This class keeps record of the current timeout status of a verification request.
 */
public final class VerificationStatusTracker {
    private final @CurrentTimeMillisLong long mStartTime;
    private @CurrentTimeMillisLong long mTimeoutTime;
    private final @CurrentTimeMillisLong long mMaxTimeoutTime;
    @NonNull
    private final VerifierController.Injector mInjector;
    // Record the package name associated with the verification result
    @NonNull
    private final String mPackageName;

    /**
     * By default, the timeout time is the default timeout duration plus the current time (when
     * the timer starts for a verification request). Both the default timeout time and the max
     * timeout time cannot be changed after the timer has started, but the actual timeout time
     * can be extended via {@link #extendTimeRemaining} to the maximum allowed.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    public VerificationStatusTracker(@NonNull String packageName,
            long defaultTimeoutMillis, long maxExtendedTimeoutMillis,
            @NonNull VerifierController.Injector injector) {
        mPackageName = packageName;
        mStartTime = injector.getCurrentTimeMillis();
        mTimeoutTime = mStartTime + defaultTimeoutMillis;
        mMaxTimeoutTime = mStartTime + maxExtendedTimeoutMillis;
        mInjector = injector;
    }

    /**
     * Used by the controller to inform the verifier agent about the timestamp when the verification
     * request will timeout.
     */
    public @CurrentTimeMillisLong long getTimeoutTime() {
        return mTimeoutTime;
    }

    /**
     * Used by the controller to decide when to check for timeout again.
     * @return 0 if the timeout time has been reached, otherwise the remaining time in milliseconds
     * before the timeout is reached.
     */
    public @CurrentTimeMillisLong long getRemainingTime() {
        final long remainingTime = mTimeoutTime - mInjector.getCurrentTimeMillis();
        if (remainingTime < 0) {
            return 0;
        }
        return remainingTime;
    }

    /**
     * Used by the controller to extend the timeout duration of the verification request, upon
     * receiving the callback from the verifier agent.
     * @return the amount of time in millis that the timeout has been extended, subject to the max
     * amount allowed.
     */
    public long extendTimeRemaining(@CurrentTimeMillisLong long additionalMs) {
        if (mTimeoutTime + additionalMs > mMaxTimeoutTime) {
            additionalMs = mMaxTimeoutTime - mTimeoutTime;
        }
        mTimeoutTime += additionalMs;
        return additionalMs;
    }

    /**
     * Used by the controller to get the timeout status of the request.
     * @return False if the request still has some time left before timeout, otherwise return True.
     */
    public boolean isTimeout() {
        return mInjector.getCurrentTimeMillis() >= mTimeoutTime;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }
}
