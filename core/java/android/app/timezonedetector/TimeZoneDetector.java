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

package android.app.timezonedetector;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.Log;

/**
 * The interface through which system components can send signals to the TimeZoneDetectorService.
 *
 * @hide
 */
@SystemService(Context.TIME_ZONE_DETECTOR_SERVICE)
public class TimeZoneDetector {
    private static final String TAG = "timezonedetector.TimeZoneDetector";
    private static final boolean DEBUG = false;

    private final ITimeZoneDetectorService mITimeZoneDetectorService;

    public TimeZoneDetector() throws ServiceNotFoundException {
        mITimeZoneDetectorService = ITimeZoneDetectorService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TIME_ZONE_DETECTOR_SERVICE));
    }

    /**
     * Suggests the current time zone, determined using telephony signals, to the detector. The
     * detector may ignore the signal based on system settings, whether better information is
     * available, and so on.
     */
    @RequiresPermission(android.Manifest.permission.SUGGEST_PHONE_TIME_AND_ZONE)
    public void suggestPhoneTimeZone(@NonNull PhoneTimeZoneSuggestion timeZoneSuggestion) {
        if (DEBUG) {
            Log.d(TAG, "suggestPhoneTimeZone called: " + timeZoneSuggestion);
        }
        try {
            mITimeZoneDetectorService.suggestPhoneTimeZone(timeZoneSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Suggests the current time zone, determined for the user's manually information, to the
     * detector. The detector may ignore the signal based on system settings.
     */
    @RequiresPermission(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE)
    public void suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion timeZoneSuggestion) {
        if (DEBUG) {
            Log.d(TAG, "suggestManualTimeZone called: " + timeZoneSuggestion);
        }
        try {
            mITimeZoneDetectorService.suggestManualTimeZone(timeZoneSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * A shared utility method to create a {@link ManualTimeZoneSuggestion}.
     */
    public static ManualTimeZoneSuggestion createManualTimeZoneSuggestion(String tzId, String why) {
        ManualTimeZoneSuggestion suggestion = new ManualTimeZoneSuggestion(tzId);
        suggestion.addDebugInfo(why);
        return suggestion;
    }
}
