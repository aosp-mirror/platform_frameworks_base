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

package android.app.timezonedetector;

import android.annotation.NonNull;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.ArraySet;
import android.util.Log;

/**
 * The real implementation of {@link TimeZoneDetector}.
 *
 * @hide
 */
public final class TimeZoneDetectorImpl implements TimeZoneDetector {
    private static final String TAG = "timezonedetector.TimeZoneDetector";
    private static final boolean DEBUG = false;

    private final ITimeZoneDetectorService mITimeZoneDetectorService;

    private ITimeZoneConfigurationListener mConfigurationReceiver;
    private ArraySet<TimeZoneConfigurationListener> mConfigurationListeners;

    public TimeZoneDetectorImpl() throws ServiceNotFoundException {
        mITimeZoneDetectorService = ITimeZoneDetectorService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TIME_ZONE_DETECTOR_SERVICE));
    }

    @Override
    @NonNull
    public TimeZoneCapabilities getCapabilities() {
        if (DEBUG) {
            Log.d(TAG, "getCapabilities called");
        }
        try {
            return mITimeZoneDetectorService.getCapabilities();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @NonNull
    public TimeZoneConfiguration getConfiguration() {
        if (DEBUG) {
            Log.d(TAG, "getConfiguration called");
        }
        try {
            return mITimeZoneDetectorService.getConfiguration();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean updateConfiguration(@NonNull TimeZoneConfiguration configuration) {
        if (DEBUG) {
            Log.d(TAG, "updateConfiguration called: " + configuration);
        }
        try {
            return mITimeZoneDetectorService.updateConfiguration(configuration);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void addConfigurationListener(@NonNull TimeZoneConfigurationListener listener) {
        if (DEBUG) {
            Log.d(TAG, "addConfigurationListener called: " + listener);
        }
        synchronized (this) {
            if (mConfigurationListeners.contains(listener)) {
                return;
            }
            if (mConfigurationReceiver == null) {
                ITimeZoneConfigurationListener iListener =
                        new ITimeZoneConfigurationListener.Stub() {
                    @Override
                    public void onChange(@NonNull TimeZoneConfiguration configuration) {
                        notifyConfigurationListeners(configuration);
                    }
                };
                mConfigurationReceiver = iListener;
            }
            if (mConfigurationListeners == null) {
                mConfigurationListeners = new ArraySet<>();
            }

            boolean wasEmpty = mConfigurationListeners.isEmpty();
            mConfigurationListeners.add(listener);
            if (wasEmpty) {
                try {
                    mITimeZoneDetectorService.addConfigurationListener(mConfigurationReceiver);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    private void notifyConfigurationListeners(@NonNull TimeZoneConfiguration configuration) {
        ArraySet<TimeZoneConfigurationListener> configurationListeners;
        synchronized (this) {
            configurationListeners = new ArraySet<>(mConfigurationListeners);
        }
        int size = configurationListeners.size();
        for (int i = 0; i < size; i++) {
            configurationListeners.valueAt(i).onChange(configuration);
        }
    }

    @Override
    public void removeConfigurationListener(@NonNull TimeZoneConfigurationListener listener) {
        if (DEBUG) {
            Log.d(TAG, "removeConfigurationListener called: " + listener);
        }

        synchronized (this) {
            if (mConfigurationListeners == null) {
                return;
            }
            boolean wasEmpty = mConfigurationListeners.isEmpty();
            mConfigurationListeners.remove(listener);
            if (mConfigurationListeners.isEmpty() && !wasEmpty) {
                try {
                    mITimeZoneDetectorService.removeConfigurationListener(mConfigurationReceiver);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    @Override
    public boolean suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion timeZoneSuggestion) {
        if (DEBUG) {
            Log.d(TAG, "suggestManualTimeZone called: " + timeZoneSuggestion);
        }
        try {
            return mITimeZoneDetectorService.suggestManualTimeZone(timeZoneSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion timeZoneSuggestion) {
        if (DEBUG) {
            Log.d(TAG, "suggestTelephonyTimeZone called: " + timeZoneSuggestion);
        }
        try {
            mITimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
