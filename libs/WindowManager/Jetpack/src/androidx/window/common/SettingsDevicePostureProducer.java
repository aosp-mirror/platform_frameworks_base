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

package androidx.window.common;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.window.util.BaseDataProducer;

import java.util.Optional;

/**
 * Implementation of {@link androidx.window.util.DataProducer} that provides the device posture
 * as an {@link Integer} from a value stored in {@link Settings}.
 */
public final class SettingsDevicePostureProducer extends BaseDataProducer<Integer> {
    private static final String DEVICE_POSTURE = "device_posture";

    private final Uri mDevicePostureUri =
            Settings.Global.getUriFor(DEVICE_POSTURE);

    private final ContentResolver mResolver;
    private final ContentObserver mObserver;
    private boolean mRegisteredObservers;

    public SettingsDevicePostureProducer(@NonNull Context context) {
        mResolver = context.getContentResolver();
        mObserver = new SettingsObserver();
    }

    @Override
    @Nullable
    public Optional<Integer> getData() {
        int posture = Settings.Global.getInt(mResolver, DEVICE_POSTURE, -1);
        return posture == -1 ? Optional.empty() : Optional.of(posture);
    }

    /**
     * Registers settings observers, if needed. When settings observers are registered for this
     * producer callbacks for changes in data will be triggered.
     */
    public void registerObserversIfNeeded() {
        if (mRegisteredObservers) {
            return;
        }
        mRegisteredObservers = true;
        mResolver.registerContentObserver(mDevicePostureUri, false /* notifyForDescendants */,
                mObserver /* ContentObserver */);
    }

    /**
     * Unregisters settings observers, if needed. When settings observers are unregistered for this
     * producer callbacks for changes in data will not be triggered.
     */
    public void unregisterObserversIfNeeded() {
        if (!mRegisteredObservers) {
            return;
        }
        mRegisteredObservers = false;
        mResolver.unregisterContentObserver(mObserver);
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mDevicePostureUri.equals(uri)) {
                notifyDataChanged();
            }
        }
    }
}
