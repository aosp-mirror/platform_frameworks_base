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
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.window.util.BaseDataProducer;

import com.android.internal.R;

import java.util.Optional;
import java.util.Set;

/**
 * Implementation of {@link androidx.window.util.DataProducer} that produces a
 * {@link String} that can be parsed to a {@link CommonFoldingFeature}.
 * {@link RawFoldingFeatureProducer} searches for the value in two places. The first check is in
 * settings where the {@link String} property is saved with the key
 * {@link RawFoldingFeatureProducer#DISPLAY_FEATURES}. If this value is null or empty then the
 * value in {@link android.content.res.Resources} is used. If both are empty then
 * {@link RawFoldingFeatureProducer#getData()} returns an empty object.
 * {@link RawFoldingFeatureProducer} listens to changes in the setting so that it can override
 * the system {@link CommonFoldingFeature} data.
 */
public final class RawFoldingFeatureProducer extends BaseDataProducer<String> {
    private static final String DISPLAY_FEATURES = "display_features";

    private final Uri mDisplayFeaturesUri =
            Settings.Global.getUriFor(DISPLAY_FEATURES);

    private final ContentResolver mResolver;
    private final ContentObserver mObserver;
    private final String mResourceFeature;
    private boolean mRegisteredObservers;

    public RawFoldingFeatureProducer(@NonNull Context context) {
        mResolver = context.getContentResolver();
        mObserver = new SettingsObserver();
        mResourceFeature = context.getResources().getString(R.string.config_display_features);
    }

    @Override
    @NonNull
    public Optional<String> getData() {
        String displayFeaturesString = getFeatureString();
        if (displayFeaturesString == null) {
            return Optional.empty();
        }
        return Optional.of(displayFeaturesString);
    }

    /**
     * Returns the {@link String} representation for a {@link CommonFoldingFeature} from settings if
     * present and falls back to the resource value if empty or {@code null}.
     */
    private String getFeatureString() {
        String settingsFeature = Settings.Global.getString(mResolver, DISPLAY_FEATURES);
        if (TextUtils.isEmpty(settingsFeature)) {
            return mResourceFeature;
        }
        return settingsFeature;
    }

    @Override
    protected void onListenersChanged(Set<Runnable> callbacks) {
        if (callbacks.isEmpty()) {
            unregisterObserversIfNeeded();
        } else {
            registerObserversIfNeeded();
        }
    }

    /**
     * Registers settings observers, if needed. When settings observers are registered for this
     * producer callbacks for changes in data will be triggered.
     */
    private void registerObserversIfNeeded() {
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
    private void unregisterObserversIfNeeded() {
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
