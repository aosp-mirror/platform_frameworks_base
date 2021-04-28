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

package androidx.window.extensions;

import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.window.util.ExtensionHelper.rotateRectToDisplayRotation;
import static androidx.window.util.ExtensionHelper.transformToWindowSpaceRect;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.window.common.DeviceStateManagerPostureProducer;
import androidx.window.common.DisplayFeature;
import androidx.window.common.ResourceConfigDisplayFeatureProducer;
import androidx.window.common.SettingsDevicePostureProducer;
import androidx.window.common.SettingsDisplayFeatureProducer;
import androidx.window.util.DataProducer;
import androidx.window.util.PriorityDataProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reference implementation of androidx.window.extensions OEM interface for use with
 * WindowManager Jetpack.
 *
 * NOTE: This version is a work in progress and under active development. It MUST NOT be used in
 * production builds since the interface can still change before reaching stable version.
 * Please refer to {@link androidx.window.sidecar.SampleSidecarImpl} instead.
 */
class SampleExtensionImpl extends StubExtension {
    private static final String TAG = "SampleExtension";

    private final SettingsDevicePostureProducer mSettingsDevicePostureProducer;
    private final DataProducer<Integer> mDevicePostureProducer;

    private final SettingsDisplayFeatureProducer mSettingsDisplayFeatureProducer;
    private final DataProducer<List<DisplayFeature>> mDisplayFeatureProducer;

    SampleExtensionImpl(Context context) {
        mSettingsDevicePostureProducer = new SettingsDevicePostureProducer(context);
        mDevicePostureProducer = new PriorityDataProducer<>(List.of(
                mSettingsDevicePostureProducer,
                new DeviceStateManagerPostureProducer(context)
        ));

        mSettingsDisplayFeatureProducer = new SettingsDisplayFeatureProducer(context);
        mDisplayFeatureProducer = new PriorityDataProducer<>(List.of(
                mSettingsDisplayFeatureProducer,
                new ResourceConfigDisplayFeatureProducer(context)
        ));

        mDevicePostureProducer.addDataChangedCallback(this::onDisplayFeaturesChanged);
        mDisplayFeatureProducer.addDataChangedCallback(this::onDisplayFeaturesChanged);
    }

    private int getFeatureState(DisplayFeature feature) {
        Integer featureState = feature.getState();
        Optional<Integer> posture = mDevicePostureProducer.getData();
        int fallbackPosture = posture.orElse(ExtensionFoldingFeature.STATE_FLAT);
        return featureState == null ? fallbackPosture : featureState;
    }

    private void onDisplayFeaturesChanged() {
        for (Activity activity : getActivitiesListeningForLayoutChanges()) {
            ExtensionWindowLayoutInfo newLayout = getWindowLayoutInfo(activity);
            updateWindowLayout(activity, newLayout);
        }
    }

    @NonNull
    private ExtensionWindowLayoutInfo getWindowLayoutInfo(@NonNull Activity activity) {
        List<ExtensionDisplayFeature> displayFeatures = getDisplayFeatures(activity);
        return new ExtensionWindowLayoutInfo(displayFeatures);
    }

    private List<ExtensionDisplayFeature> getDisplayFeatures(@NonNull Activity activity) {
        List<ExtensionDisplayFeature> features = new ArrayList<>();
        int displayId = activity.getDisplay().getDisplayId();
        if (displayId != DEFAULT_DISPLAY) {
            Log.w(TAG, "This sample doesn't support display features on secondary displays");
            return features;
        }

        if (activity.isInMultiWindowMode()) {
            // It is recommended not to report any display features in multi-window mode, since it
            // won't be possible to synchronize the display feature positions with window movement.
            return features;
        }

        Optional<List<DisplayFeature>> storedFeatures = mDisplayFeatureProducer.getData();
        if (storedFeatures.isPresent()) {

            for (DisplayFeature baseFeature : storedFeatures.get()) {
                Rect featureRect = baseFeature.getRect();
                rotateRectToDisplayRotation(displayId, featureRect);
                transformToWindowSpaceRect(activity, featureRect);

                features.add(new ExtensionFoldingFeature(featureRect, baseFeature.getType(),
                        getFeatureState(baseFeature)));
            }
        }
        return features;
    }

    @Override
    protected void onListenersChanged() {
        if (hasListeners()) {
            mSettingsDevicePostureProducer.registerObserversIfNeeded();
            mSettingsDisplayFeatureProducer.registerObserversIfNeeded();
        } else {
            mSettingsDevicePostureProducer.unregisterObserversIfNeeded();
            mSettingsDisplayFeatureProducer.unregisterObserversIfNeeded();
        }

        onDisplayFeaturesChanged();
    }
}
