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

package androidx.window.sidecar;

import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.window.sidecar.SidecarHelper.getWindowDisplay;
import static androidx.window.sidecar.SidecarHelper.isInMultiWindow;
import static androidx.window.sidecar.SidecarHelper.rotateRectToDisplayRotation;
import static androidx.window.sidecar.SidecarHelper.transformToWindowSpaceRect;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SettingsSidecarImpl extends StubSidecar {
    private static final String TAG = "SettingsSidecar";

    private static final String DEVICE_POSTURE = "device_posture";
    private static final String DISPLAY_FEATURES = "display_features";

    private static final Pattern FEATURE_PATTERN =
            Pattern.compile("([a-z]+)-\\[(\\d+),(\\d+),(\\d+),(\\d+)]");

    private static final String FEATURE_TYPE_FOLD = "fold";
    private static final String FEATURE_TYPE_HINGE = "hinge";

    private Context mContext;
    private SettingsObserver mSettingsObserver;

    final class SettingsObserver extends ContentObserver {
        private final Uri mDevicePostureUri =
                Settings.Global.getUriFor(DEVICE_POSTURE);
        private final Uri mDisplayFeaturesUri =
                Settings.Global.getUriFor(DISPLAY_FEATURES);
        private final ContentResolver mResolver = mContext.getContentResolver();
        private boolean mRegisteredObservers;


        private SettingsObserver() {
            super(new Handler(Looper.getMainLooper()));
        }

        private void registerObserversIfNeeded() {
            if (mRegisteredObservers) {
                return;
            }
            mRegisteredObservers = true;
            mResolver.registerContentObserver(mDevicePostureUri, false /* notifyForDescendents */,
                    this /* ContentObserver */);
            mResolver.registerContentObserver(mDisplayFeaturesUri, false /* notifyForDescendents */,
                    this /* ContentObserver */);
        }

        private void unregisterObserversIfNeeded() {
            if (!mRegisteredObservers) {
                return;
            }
            mRegisteredObservers = false;
            mResolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri == null) {
                return;
            }

            if (mDevicePostureUri.equals(uri)) {
                updateDevicePosture();
                return;
            }
            if (mDisplayFeaturesUri.equals(uri)) {
                updateDisplayFeatures();
                return;
            }
        }
    }

    SettingsSidecarImpl(Context context) {
        mContext = context;
        mSettingsObserver = new SettingsObserver();
    }

    private void updateDevicePosture() {
        updateDeviceState(getDeviceState());
    }

    /** Update display features with values read from settings. */
    private void updateDisplayFeatures() {
        for (IBinder windowToken : getWindowsListeningForLayoutChanges()) {
            SidecarWindowLayoutInfo newLayout = getWindowLayoutInfo(windowToken);
            updateWindowLayout(windowToken, newLayout);
        }
    }

    @NonNull
    @Override
    public SidecarDeviceState getDeviceState() {
        ContentResolver resolver = mContext.getContentResolver();
        int posture = Settings.Global.getInt(resolver, DEVICE_POSTURE,
                SidecarDeviceState.POSTURE_UNKNOWN);
        SidecarDeviceState deviceState = new SidecarDeviceState();
        deviceState.posture = posture;
        return deviceState;
    }

    @NonNull
    @Override
    public SidecarWindowLayoutInfo getWindowLayoutInfo(@NonNull IBinder windowToken) {
        List<SidecarDisplayFeature> displayFeatures = readDisplayFeatures(windowToken);
        SidecarWindowLayoutInfo windowLayoutInfo = new SidecarWindowLayoutInfo();
        windowLayoutInfo.displayFeatures = displayFeatures;
        return windowLayoutInfo;
    }

    private List<SidecarDisplayFeature> readDisplayFeatures(IBinder windowToken) {
        List<SidecarDisplayFeature> features = new ArrayList<SidecarDisplayFeature>();
        int displayId = getWindowDisplay(windowToken);
        if (displayId != DEFAULT_DISPLAY) {
            Log.w(TAG, "This sample doesn't support display features on secondary displays");
            return features;
        }

        ContentResolver resolver = mContext.getContentResolver();
        final String displayFeaturesString = Settings.Global.getString(resolver, DISPLAY_FEATURES);
        if (isInMultiWindow(windowToken)) {
            // It is recommended not to report any display features in multi-window mode, since it
            // won't be possible to synchronize the display feature positions with window movement.
            return features;
        }
        if (TextUtils.isEmpty(displayFeaturesString)) {
            return features;
        }

        String[] featureStrings = displayFeaturesString.split(";");
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
                        type = SidecarDisplayFeature.TYPE_FOLD;
                        break;
                    case FEATURE_TYPE_HINGE:
                        type = SidecarDisplayFeature.TYPE_HINGE;
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
                rotateRectToDisplayRotation(featureRect, displayId);
                transformToWindowSpaceRect(featureRect, windowToken);
                if (!featureRect.isEmpty()) {
                    SidecarDisplayFeature feature = new SidecarDisplayFeature();
                    feature.setRect(featureRect);
                    feature.setType(type);
                    features.add(feature);
                } else {
                    Log.w(TAG, "Failed to adjust feature to window");
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Malformed feature description: " + featureString);
            }
        }
        return features;
    }

    @Override
    protected void onListenersChanged() {
        if (mSettingsObserver == null) {
            return;
        }

        if (hasListeners()) {
            mSettingsObserver.registerObserversIfNeeded();
        } else {
            mSettingsObserver.unregisterObserversIfNeeded();
        }
    }
}
