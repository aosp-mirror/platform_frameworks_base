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

package androidx.window.extensions.layout;

import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.window.common.DisplayFeature.COMMON_STATE_FLAT;
import static androidx.window.common.DisplayFeature.COMMON_STATE_HALF_OPENED;
import static androidx.window.util.ExtensionHelper.rotateRectToDisplayRotation;
import static androidx.window.util.ExtensionHelper.transformToWindowSpaceRect;

import android.annotation.Nullable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reference implementation of androidx.window.extensions.layout OEM interface for use with
 * WindowManager Jetpack.
 *
 * NOTE: This version is a work in progress and under active development. It MUST NOT be used in
 * production builds since the interface can still change before reaching stable version.
 * Please refer to {@link androidx.window.sidecar.SampleSidecarImpl} instead.
 */
public class WindowLayoutComponentImpl implements WindowLayoutComponent {
    private static final String TAG = "SampleExtension";
    private static WindowLayoutComponent sInstance;

    private final Map<Activity, Consumer<WindowLayoutInfo>> mWindowLayoutChangeListeners =
            new HashMap<>();

    private final SettingsDevicePostureProducer mSettingsDevicePostureProducer;
    private final DataProducer<Integer> mDevicePostureProducer;

    private final SettingsDisplayFeatureProducer mSettingsDisplayFeatureProducer;
    private final DataProducer<List<DisplayFeature>> mDisplayFeatureProducer;

    public WindowLayoutComponentImpl(Context context) {
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

    /**
     * Adds a listener interested in receiving updates to {@link WindowLayoutInfo}
     * @param activity hosting a {@link android.view.Window}
     * @param consumer interested in receiving updates to {@link WindowLayoutInfo}
     */
    public void addWindowLayoutInfoListener(@NonNull Activity activity,
            @NonNull Consumer<WindowLayoutInfo> consumer) {
        mWindowLayoutChangeListeners.put(activity, consumer);
        updateRegistrations();
    }

    /**
     * Removes a listener no longer interested in receiving updates.
     * @param consumer no longer interested in receiving updates to {@link WindowLayoutInfo}
     */
    public void removeWindowLayoutInfoListener(
            @NonNull Consumer<WindowLayoutInfo> consumer) {
        mWindowLayoutChangeListeners.values().remove(consumer);
        updateRegistrations();
    }

    void updateWindowLayout(@NonNull Activity activity,
            @NonNull WindowLayoutInfo newLayout) {
        Consumer<WindowLayoutInfo> consumer = mWindowLayoutChangeListeners.get(activity);
        if (consumer != null) {
            consumer.accept(newLayout);
        }
    }

    @NonNull
    Set<Activity> getActivitiesListeningForLayoutChanges() {
        return mWindowLayoutChangeListeners.keySet();
    }

    protected boolean hasListeners() {
        return !mWindowLayoutChangeListeners.isEmpty();
    }

    /**
     * Calculate the {@link DisplayFeature.State} from the feature or the device posture producer.
     * If the given {@link DisplayFeature.State} is not valid then {@code null} will be returned.
     * The {@link FoldingFeature} should be ignored in the case of an invalid
     * {@link DisplayFeature.State}.
     *
     * @param feature a {@link DisplayFeature} to provide the feature state if present.
     * @return {@link DisplayFeature.State} of the hinge if present or the state from the posture
     * produce if present.
     */
    @Nullable
    private Integer getFeatureState(DisplayFeature feature) {
        Integer featureState = feature.getState();
        Optional<Integer> posture = mDevicePostureProducer.getData();
        Integer state = featureState == null ? posture.orElse(null) : featureState;
        return convertToExtensionState(state);
    }

    /**
     * A convenience method to translate from the common feature state to the extensions feature
     * state.  More specifically, translates from {@link DisplayFeature.State} to
     * {@link FoldingFeature.STATE_FLAT} or {@link FoldingFeature.STATE_HALF_OPENED}. If it is not
     * possible to translate, then we will return a {@code null} value.
     *
     * @param state if it matches a value in {@link DisplayFeature.State}, {@code null} otherwise.
     * @return a {@link FoldingFeature.STATE_FLAT} or {@link FoldingFeature.STATE_HALF_OPENED} if
     * the given state matches a value in {@link DisplayFeature.State} and {@code null} otherwise.
     */
    @Nullable
    private Integer convertToExtensionState(@Nullable Integer state) {
        if (state == null) { // The null check avoids a NullPointerException.
            return null;
        } else if (state == COMMON_STATE_FLAT) {
            return FoldingFeature.STATE_FLAT;
        } else if (state == COMMON_STATE_HALF_OPENED) {
            return FoldingFeature.STATE_HALF_OPENED;
        } else {
            return null;
        }
    }

    private void onDisplayFeaturesChanged() {
        for (Activity activity : getActivitiesListeningForLayoutChanges()) {
            WindowLayoutInfo newLayout = getWindowLayoutInfo(activity);
            updateWindowLayout(activity, newLayout);
        }
    }

    @NonNull
    private WindowLayoutInfo getWindowLayoutInfo(@NonNull Activity activity) {
        List<androidx.window.extensions.layout.DisplayFeature> displayFeatures =
                getDisplayFeatures(activity);
        return new WindowLayoutInfo(displayFeatures);
    }

    /**
     * Translate from the {@link DisplayFeature} to
     * {@link androidx.window.extensions.layout.DisplayFeature} for a given {@link Activity}. If a
     * {@link DisplayFeature} is not valid then it will be omitted.
     *
     * For a {@link FoldingFeature} the bounds are localized into the {@link Activity} window
     * coordinate space and the state is calculated either from {@link DisplayFeature#getState()} or
     * {@link #mDisplayFeatureProducer}. The state from {@link #mDisplayFeatureProducer} may not be
     * valid since {@link #mDisplayFeatureProducer} is a general state controller. If the state is
     * not valid, the {@link FoldingFeature} is omitted from the {@link List} of
     * {@link androidx.window.extensions.layout.DisplayFeature}. If the bounds are not valid,
     * constructing a {@link FoldingFeature} will throw an {@link IllegalArgumentException} since
     * this can cause negative UI effects down stream.
     *
     * @param activity a proxy for the {@link android.view.Window} that contains the
     * {@link androidx.window.extensions.layout.DisplayFeature}.
     * @return a {@link List} of valid {@link androidx.window.extensions.layout.DisplayFeature} that
     * are within the {@link android.view.Window} of the {@link Activity}
     */
    private List<androidx.window.extensions.layout.DisplayFeature> getDisplayFeatures(
            @NonNull Activity activity) {
        List<androidx.window.extensions.layout.DisplayFeature> features = new ArrayList<>();
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
                Integer state = getFeatureState(baseFeature);
                if (state == null) {
                    continue;
                }
                Rect featureRect = baseFeature.getRect();
                rotateRectToDisplayRotation(displayId, featureRect);
                transformToWindowSpaceRect(activity, featureRect);

                features.add(new FoldingFeature(featureRect, baseFeature.getType(),
                        getFeatureState(baseFeature)));
            }
        }
        return features;
    }

    private void updateRegistrations() {
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
