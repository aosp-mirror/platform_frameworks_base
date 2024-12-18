/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static androidx.window.common.ExtensionHelper.rotateRectToDisplayRotation;
import static androidx.window.common.ExtensionHelper.transformToWindowSpaceRect;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.ActivityThread;
import android.graphics.Rect;
import android.os.IBinder;

import androidx.window.common.layout.CommonFoldingFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A utility class for transforming between Sidecar and Extensions features.
 */
class SidecarHelper {

    private SidecarHelper() {}

    /**
     * Returns the {@link SidecarDeviceState} posture that is calculated for the first fold in
     * the feature list. Sidecar devices only have one fold so we only pick the first one to
     * determine the state.
     * @param featureList the {@link CommonFoldingFeature} that are currently active.
     * @return the {@link SidecarDeviceState} calculated from the {@link List} of
     * {@link CommonFoldingFeature}.
     */
    @SuppressWarnings("deprecation")
    private static int deviceStateFromFeatureList(@NonNull List<CommonFoldingFeature> featureList) {
        for (int i = 0; i < featureList.size(); i++) {
            final CommonFoldingFeature feature = featureList.get(i);
            final int state = feature.getState();
            switch (state) {
                case CommonFoldingFeature.COMMON_STATE_FLAT:
                    return SidecarDeviceState.POSTURE_OPENED;
                case CommonFoldingFeature.COMMON_STATE_HALF_OPENED:
                    return SidecarDeviceState.POSTURE_HALF_OPENED;
                case CommonFoldingFeature.COMMON_STATE_UNKNOWN:
                    return SidecarDeviceState.POSTURE_UNKNOWN;
                case CommonFoldingFeature.COMMON_STATE_NO_FOLDING_FEATURES:
                    return SidecarDeviceState.POSTURE_UNKNOWN;
                case CommonFoldingFeature.COMMON_STATE_USE_BASE_STATE:
                    return SidecarDeviceState.POSTURE_UNKNOWN;
            }
        }
        return SidecarDeviceState.POSTURE_UNKNOWN;
    }

    /**
     * Returns a {@link SidecarDeviceState} calculated from a {@link List} of
     * {@link CommonFoldingFeature}s.
     */
    @SuppressWarnings("deprecation")
    static SidecarDeviceState calculateDeviceState(
            @NonNull List<CommonFoldingFeature> featureList) {
        final SidecarDeviceState deviceState = new SidecarDeviceState();
        deviceState.posture = deviceStateFromFeatureList(featureList);
        return deviceState;
    }

    @SuppressWarnings("deprecation")
    private static List<SidecarDisplayFeature> calculateDisplayFeatures(
            @NonNull Activity activity,
            @NonNull List<CommonFoldingFeature> featureList
    ) {
        final int displayId = activity.getDisplay().getDisplayId();
        if (displayId != DEFAULT_DISPLAY) {
            return Collections.emptyList();
        }

        if (activity.isInMultiWindowMode()) {
            // It is recommended not to report any display features in multi-window mode, since it
            // won't be possible to synchronize the display feature positions with window movement.
            return Collections.emptyList();
        }

        final List<SidecarDisplayFeature> features = new ArrayList<>();
        final int rotation = activity.getResources().getConfiguration().windowConfiguration
                .getDisplayRotation();
        for (CommonFoldingFeature baseFeature : featureList) {
            final SidecarDisplayFeature feature = new SidecarDisplayFeature();
            final Rect featureRect = baseFeature.getRect();
            rotateRectToDisplayRotation(displayId, rotation, featureRect);
            transformToWindowSpaceRect(activity, featureRect);
            feature.setRect(featureRect);
            feature.setType(baseFeature.getType());
            features.add(feature);
        }
        return Collections.unmodifiableList(features);
    }

    /**
     * Returns a {@link SidecarWindowLayoutInfo} calculated from the {@link List} of
     * {@link CommonFoldingFeature}.
     */
    @SuppressWarnings("deprecation")
    static SidecarWindowLayoutInfo calculateWindowLayoutInfo(@NonNull IBinder windowToken,
            @NonNull List<CommonFoldingFeature> featureList) {
        final Activity activity = ActivityThread.currentActivityThread().getActivity(windowToken);
        final SidecarWindowLayoutInfo windowLayoutInfo = new SidecarWindowLayoutInfo();
        if (activity == null) {
            return windowLayoutInfo;
        }
        windowLayoutInfo.displayFeatures = calculateDisplayFeatures(activity, featureList);
        return windowLayoutInfo;
    }
}
