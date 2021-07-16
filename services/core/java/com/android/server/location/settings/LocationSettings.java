/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.location.settings;

import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;

import android.content.Context;
import android.os.Environment;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.FgThread;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Accessor for location user settings. Ensure there is only ever one instance as multiple instances
 * don't play nicely with each other.
 */
public class LocationSettings {

    /** Listens for changes to location user settings. */
    public interface LocationUserSettingsListener {
        /** Invoked when location user settings have changed for the given user. */
        void onLocationUserSettingsChanged(int userId, LocationUserSettings oldSettings,
                LocationUserSettings newSettings);
    }

    private static final String LOCATION_DIRNAME = "location";
    private static final String LOCATION_SETTINGS_FILENAME = "settings";

    final Context mContext;

    @GuardedBy("mUserSettings")
    private final SparseArray<LocationUserSettingsStore> mUserSettings;
    private final CopyOnWriteArrayList<LocationUserSettingsListener> mUserSettingsListeners;

    public LocationSettings(Context context) {
        mContext = context;
        mUserSettings = new SparseArray<>(1);
        mUserSettingsListeners = new CopyOnWriteArrayList<>();
    }

    /** Registers a listener for changes to location user settings. */
    public final void registerLocationUserSettingsListener(LocationUserSettingsListener listener) {
        mUserSettingsListeners.add(listener);
    }

    /** Unregisters a listener for changes to location user settings. */
    public final void unregisterLocationUserSettingsListener(
            LocationUserSettingsListener listener) {
        mUserSettingsListeners.remove(listener);
    }

    protected File getUserSettingsDir(int userId) {
        return Environment.getDataSystemDeDirectory(userId);
    }

    protected LocationUserSettingsStore createUserSettingsStore(int userId, File file) {
        return new LocationUserSettingsStore(userId, file);
    }

    private LocationUserSettingsStore getUserSettingsStore(int userId) {
        synchronized (mUserSettings) {
            LocationUserSettingsStore settingsStore = mUserSettings.get(userId);
            if (settingsStore == null) {
                File file = new File(new File(getUserSettingsDir(userId), LOCATION_DIRNAME),
                        LOCATION_SETTINGS_FILENAME);
                settingsStore = createUserSettingsStore(userId, file);
                mUserSettings.put(userId, settingsStore);
            }
            return settingsStore;
        }
    }

    /** Retrieves the current state of location user settings. */
    public final LocationUserSettings getUserSettings(int userId) {
        return getUserSettingsStore(userId).get();
    }

    /** Updates the current state of location user settings for the given user. */
    public final void updateUserSettings(int userId,
            Function<LocationUserSettings, LocationUserSettings> updater) {
        getUserSettingsStore(userId).update(updater);
    }

    @VisibleForTesting
    final void flushFiles() throws InterruptedException {
        synchronized (mUserSettings) {
            int size = mUserSettings.size();
            for (int i = 0; i < size; i++) {
                mUserSettings.valueAt(i).flushFile();
            }
        }
    }

    @VisibleForTesting
    final void deleteFiles() throws InterruptedException {
        synchronized (mUserSettings) {
            int size = mUserSettings.size();
            for (int i = 0; i < size; i++) {
                mUserSettings.valueAt(i).deleteFile();
            }
        }
    }

    protected final void fireListeners(int userId, LocationUserSettings oldSettings,
            LocationUserSettings newSettings) {
        for (LocationUserSettingsListener listener : mUserSettingsListeners) {
            listener.onLocationUserSettingsChanged(userId, oldSettings, newSettings);
        }
    }

    class LocationUserSettingsStore extends SettingsStore<LocationUserSettings> {

        protected final int mUserId;

        LocationUserSettingsStore(int userId, File file) {
            super(file);
            mUserId = userId;
        }

        @Override
        protected LocationUserSettings read(int version, DataInput in) throws IOException {
            return filterSettings(LocationUserSettings.read(mContext.getResources(), version, in));
        }

        @Override
        protected void write(DataOutput out, LocationUserSettings settings) throws IOException {
            settings.write(out);
        }

        @Override
        public void update(Function<LocationUserSettings, LocationUserSettings> updater) {
            super.update(settings -> filterSettings(updater.apply(settings)));
        }

        @Override
        protected void onChange(LocationUserSettings oldSettings,
                LocationUserSettings newSettings) {
            FgThread.getExecutor().execute(() -> fireListeners(mUserId, oldSettings, newSettings));
        }

        private LocationUserSettings filterSettings(LocationUserSettings settings) {
            if (settings.isAdasGnssLocationEnabled()
                    && !mContext.getPackageManager().hasSystemFeature(FEATURE_AUTOMOTIVE)) {
                // prevent non-automotive devices from ever enabling this
                settings = settings.withAdasGnssLocationEnabled(false);
            }
            return settings;
        }
    }
}
