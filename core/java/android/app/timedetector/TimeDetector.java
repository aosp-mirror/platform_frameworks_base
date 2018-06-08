/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;

/**
 * The interface through which system components can send signals to the TimeDetectorService.
 * @hide
 */
@SystemService(Context.TIME_DETECTOR_SERVICE)
public final class TimeDetector {
    private static final String TAG = "timedetector.TimeDetector";
    private static final boolean DEBUG = false;

    private final ITimeDetectorService mITimeDetectorService;

    public TimeDetector() throws ServiceNotFoundException {
        mITimeDetectorService = ITimeDetectorService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TIME_DETECTOR_SERVICE));
    }

    /**
     * Suggests the current time to the detector. The detector may ignore the signal if better
     * signals are available such as those that come from more reliable sources or were
     * determined more recently.
     */
    public void suggestTime(@NonNull TimeSignal timeSignal) {
        if (DEBUG) {
            Log.d(TAG, "suggestTime called: " + timeSignal);
        }
        try {
            mITimeDetectorService.suggestTime(timeSignal);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
