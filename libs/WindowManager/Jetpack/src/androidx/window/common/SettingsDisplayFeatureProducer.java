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
import android.text.TextUtils;
import android.util.Log;

import androidx.window.util.BaseDataProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of {@link androidx.window.util.DataProducer} that produces
 * {@link CommonDisplayFeature} parsed from a string stored in {@link Settings}.
 */
public final class SettingsDisplayFeatureProducer
        extends BaseDataProducer<List<DisplayFeature>> {
    private static final boolean DEBUG = false;
    private static final String TAG = "SettingsDisplayFeatureProducer";
    private static final String DISPLAY_FEATURES = "display_features";

    private final Uri mDisplayFeaturesUri =
            Settings.Global.getUriFor(DISPLAY_FEATURES);

    private final ContentResolver mResolver;
    private final ContentObserver mObserver;
    private boolean mRegisteredObservers;

    public SettingsDisplayFeatureProducer(@NonNull Context context) {
        mResolver = context.getContentResolver();
        mObserver = new SettingsObserver();
    }

    @Override
    @Nullable
    public Optional<List<DisplayFeature>> getData() {
        String displayFeaturesString = Settings.Global.getString(mResolver, DISPLAY_FEATURES);
        if (displayFeaturesString == null) {
            return Optional.empty();
        }

        List<DisplayFeature> features = new ArrayList<>();
        if (TextUtils.isEmpty(displayFeaturesString)) {
            return Optional.of(features);
        }
        String[] featureStrings =  displayFeaturesString.split(";");

        for (String featureString : featureStrings) {
            CommonDisplayFeature feature;
            try {
                feature = CommonDisplayFeature.parseFromString(featureString);
            } catch (IllegalArgumentException e) {
                if (DEBUG) {
                    Log.w(TAG, "Failed to parse display feature: " + featureString, e);
                }
                continue;
            }
            features.add(feature);
        }
        return Optional.of(features);
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
            if (mDisplayFeaturesUri.equals(uri)) {
                notifyDataChanged();
            }
        }
    }
}
