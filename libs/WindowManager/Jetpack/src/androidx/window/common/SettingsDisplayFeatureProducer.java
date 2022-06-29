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

import static androidx.window.common.CommonFoldingFeature.COMMON_STATE_FLAT;
import static androidx.window.common.CommonFoldingFeature.COMMON_STATE_HALF_OPENED;
import static androidx.window.common.CommonFoldingFeature.COMMON_STATE_UNKNOWN;
import static androidx.window.common.CommonFoldingFeature.parseListFromString;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.window.util.BaseDataProducer;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of {@link androidx.window.util.DataProducer} that produces
 * {@link CommonFoldingFeature} parsed from a string stored in {@link Settings}.
 */
public final class SettingsDisplayFeatureProducer
        extends BaseDataProducer<List<CommonFoldingFeature>> {
    private static final String DISPLAY_FEATURES = "display_features";
    private static final String DEVICE_POSTURE = "device_posture";

    private final Uri mDevicePostureUri =
            Settings.Global.getUriFor(DEVICE_POSTURE);
    private final Uri mDisplayFeaturesUri =
            Settings.Global.getUriFor(DISPLAY_FEATURES);

    private final ContentResolver mResolver;
    private final ContentObserver mObserver;
    private boolean mRegisteredObservers;

    public SettingsDisplayFeatureProducer(@NonNull Context context) {
        mResolver = context.getContentResolver();
        mObserver = new SettingsObserver();
    }

    private int getPosture() {
        int posture = Settings.Global.getInt(mResolver, DEVICE_POSTURE, COMMON_STATE_UNKNOWN);
        if (posture == COMMON_STATE_HALF_OPENED || posture == COMMON_STATE_FLAT) {
            return posture;
        } else {
            return COMMON_STATE_UNKNOWN;
        }
    }

    @Override
    @NonNull
    public Optional<List<CommonFoldingFeature>> getData() {
        String displayFeaturesString = Settings.Global.getString(mResolver, DISPLAY_FEATURES);
        if (displayFeaturesString == null) {
            return Optional.empty();
        }

        if (TextUtils.isEmpty(displayFeaturesString)) {
            return Optional.of(Collections.emptyList());
        }
        return Optional.of(parseListFromString(displayFeaturesString, getPosture()));
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
        mResolver.registerContentObserver(mDisplayFeaturesUri, false /* notifyForDescendants */,
                mObserver /* ContentObserver */);
        mResolver.registerContentObserver(mDevicePostureUri, false, mObserver);
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
            if (mDisplayFeaturesUri.equals(uri) || mDevicePostureUri.equals(uri)) {
                notifyDataChanged();
            }
        }
    }
}
