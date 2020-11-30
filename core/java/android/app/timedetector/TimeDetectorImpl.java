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

package android.app.timedetector;

import android.annotation.NonNull;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;

/**
 * The real implementation of {@link TimeDetector}.
 *
 * @hide
 */
public final class TimeDetectorImpl implements TimeDetector {
    private static final String TAG = "timedetector.TimeDetector";
    private static final boolean DEBUG = false;

    private final ITimeDetectorService mITimeDetectorService;

    public TimeDetectorImpl() throws ServiceNotFoundException {
        mITimeDetectorService = ITimeDetectorService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TIME_DETECTOR_SERVICE));
    }

    @Override
    public void suggestTelephonyTime(@NonNull TelephonyTimeSuggestion timeSuggestion) {
        if (DEBUG) {
            Log.d(TAG, "suggestTelephonyTime called: " + timeSuggestion);
        }
        try {
            mITimeDetectorService.suggestTelephonyTime(timeSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean suggestManualTime(@NonNull ManualTimeSuggestion timeSuggestion) {
        if (DEBUG) {
            Log.d(TAG, "suggestManualTime called: " + timeSuggestion);
        }
        try {
            return mITimeDetectorService.suggestManualTime(timeSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void suggestNetworkTime(NetworkTimeSuggestion timeSuggestion) {
        if (DEBUG) {
            Log.d(TAG, "suggestNetworkTime called: " + timeSuggestion);
        }
        try {
            mITimeDetectorService.suggestNetworkTime(timeSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
