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

package androidx.window.util;

import static androidx.window.util.ExtensionHelper.isZero;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device and display feature state provider that uses Settings as the source.
 */
public final class SettingsConfigProvider extends ContentObserver {
    private static final String TAG = "SettingsConfigProvider";
    private static final String DEVICE_POSTURE = "device_posture";
    private static final String DISPLAY_FEATURES = "display_features";

    private static final Pattern FEATURE_PATTERN =
            Pattern.compile("([a-z]+)-\\[(\\d+),(\\d+),(\\d+),(\\d+)]");

    private static final String FEATURE_TYPE_FOLD = "fold";
    private static final String FEATURE_TYPE_HINGE = "hinge";

    private final Uri mDevicePostureUri =
            Settings.Global.getUriFor(DEVICE_POSTURE);
    private final Uri mDisplayFeaturesUri =
            Settings.Global.getUriFor(DISPLAY_FEATURES);
    private final Context mContext;
    private final ContentResolver mResolver;
    private final StateChangeCallback mCallback;
    private boolean mRegisteredObservers;

    public SettingsConfigProvider(@NonNull Context context, @NonNull StateChangeCallback callback) {
        super(new Handler(Looper.getMainLooper()));
        mContext = context;
        mResolver = context.getContentResolver();
        mCallback = callback;
    }

    /**
     * Registers the content observers for Settings keys that store device state and display feature
     * configurations.
     */
    public void registerObserversIfNeeded() {
        if (mRegisteredObservers) {
            return;
        }
        mRegisteredObservers = true;
        mResolver.registerContentObserver(mDevicePostureUri, false /* notifyForDescendants */,
                this /* ContentObserver */);
        mResolver.registerContentObserver(mDisplayFeaturesUri, false /* notifyForDescendants */,
                this /* ContentObserver */);
    }

    /**
     * Unregisters the content observers that are tracking the state changes.
     * @see #registerObserversIfNeeded()
     */
    public void unregisterObserversIfNeeded() {
        if (!mRegisteredObservers) {
            return;
        }
        mRegisteredObservers = false;
        mResolver.unregisterContentObserver(this);
    }

    /**
     * Gets the device posture int stored in Settings.
     */
    public int getDeviceState() {
        return Settings.Global.getInt(mResolver, DEVICE_POSTURE,
                0 /* POSTURE_UNKNOWN */);
    }

    /**
     * Gets the list of all display feature configs stored in Settings. Uses a custom
     * {@link BaseDisplayFeature} class to report the config to be translated for actual
     * containers in Sidecar or Extensions.
     */
    public List<BaseDisplayFeature> getDisplayFeatures() {
        List<BaseDisplayFeature> features = new ArrayList<>();
        String displayFeaturesString = Settings.Global.getString(mResolver, DISPLAY_FEATURES);
        if (TextUtils.isEmpty(displayFeaturesString)) {
            displayFeaturesString = mContext.getResources().getString(
                    R.string.config_display_features);
        }
        if (TextUtils.isEmpty(displayFeaturesString)) {
            return features;
        }
        String[] featureStrings =  displayFeaturesString.split(";");

        int deviceState = getDeviceState();

        for (String featureString : featureStrings) {
            Matcher featureMatcher = FEATURE_PATTERN.matcher(featureString);
            if (!featureMatcher.matches()) {
                Log.e(TAG, "Malformed feature description format: " + featureString);
                continue;
            }
            try {
                String featureType = featureMatcher.group(1);
                int type;
                switch (featureType) {
                    case FEATURE_TYPE_FOLD:
                        type = 1 /* TYPE_FOLD */;
                        break;
                    case FEATURE_TYPE_HINGE:
                        type = 2 /* TYPE_HINGE */;
                        break;
                    default: {
                        Log.e(TAG, "Malformed feature type: " + featureType);
                        continue;
                    }
                }

                int left = Integer.parseInt(featureMatcher.group(2));
                int top = Integer.parseInt(featureMatcher.group(3));
                int right = Integer.parseInt(featureMatcher.group(4));
                int bottom = Integer.parseInt(featureMatcher.group(5));
                Rect featureRect = new Rect(left, top, right, bottom);
                if (!isZero(featureRect)) {
                    BaseDisplayFeature feature = new BaseDisplayFeature(type, deviceState,
                            featureRect);
                    features.add(feature);
                } else {
                    Log.w(TAG, "Read empty feature");
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Malformed feature description: " + featureString);
            }
        }
        return features;
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        if (uri == null) {
            return;
        }

        if (mDevicePostureUri.equals(uri)) {
            mCallback.onDevicePostureChanged();
            mCallback.onDisplayFeaturesChanged();
            return;
        }
        if (mDisplayFeaturesUri.equals(uri)) {
            mCallback.onDisplayFeaturesChanged();
        }
    }

    /**
     * Callback that notifies about device or display feature state changes.
     */
    public interface StateChangeCallback {
        /**
         * Notifies about the device state update.
         */
        void onDevicePostureChanged();

        /**
         * Notifies about the display feature config update.
         */
        void onDisplayFeaturesChanged();
    }
}
