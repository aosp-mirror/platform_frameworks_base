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

import static androidx.window.util.ExtensionHelper.rotateRectToDisplayRotation;
import static androidx.window.util.ExtensionHelper.transformToWindowSpaceRect;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.window.common.CommonFoldingFeature;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.EmptyLifecycleCallbacksAdapter;
import androidx.window.common.RawFoldingFeatureProducer;
import androidx.window.util.BaseDataProducer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reference implementation of androidx.window.sidecar OEM interface for use with
 * WindowManager Jetpack.
 */
class SampleSidecarImpl extends StubSidecar {
    private List<CommonFoldingFeature> mStoredFeatures = new ArrayList<>();

    SampleSidecarImpl(Context context) {
        ((Application) context.getApplicationContext())
                .registerActivityLifecycleCallbacks(new NotifyOnConfigurationChanged());
        BaseDataProducer<String> settingsFeatureProducer = new RawFoldingFeatureProducer(context);
        BaseDataProducer<List<CommonFoldingFeature>> foldingFeatureProducer =
                new DeviceStateManagerFoldingFeatureProducer(context,
                        settingsFeatureProducer);

        foldingFeatureProducer.addDataChangedCallback(this::onDisplayFeaturesChanged);
    }

    private void setStoredFeatures(List<CommonFoldingFeature> storedFeatures) {
        mStoredFeatures = storedFeatures;
    }

    private void onDisplayFeaturesChanged(List<CommonFoldingFeature> storedFeatures) {
        setStoredFeatures(storedFeatures);
        updateDeviceState(getDeviceState());
        for (IBinder windowToken : getWindowsListeningForLayoutChanges()) {
            SidecarWindowLayoutInfo newLayout = getWindowLayoutInfo(windowToken);
            updateWindowLayout(windowToken, newLayout);
        }
    }

    @NonNull
    @Override
    public SidecarDeviceState getDeviceState() {
        SidecarDeviceState deviceState = new SidecarDeviceState();
        deviceState.posture = deviceStateFromFeature();
        return deviceState;
    }

    private int deviceStateFromFeature() {
        for (int i = 0; i < mStoredFeatures.size(); i++) {
            CommonFoldingFeature feature = mStoredFeatures.get(i);
            final int state = feature.getState();
            switch (state) {
                case CommonFoldingFeature.COMMON_STATE_FLAT:
                    return SidecarDeviceState.POSTURE_OPENED;
                case CommonFoldingFeature.COMMON_STATE_HALF_OPENED:
                    return SidecarDeviceState.POSTURE_HALF_OPENED;
                case CommonFoldingFeature.COMMON_STATE_UNKNOWN:
                    return SidecarDeviceState.POSTURE_UNKNOWN;
            }
        }
        return SidecarDeviceState.POSTURE_UNKNOWN;
    }

    @NonNull
    @Override
    public SidecarWindowLayoutInfo getWindowLayoutInfo(@NonNull IBinder windowToken) {
        Activity activity = ActivityThread.currentActivityThread().getActivity(windowToken);
        SidecarWindowLayoutInfo windowLayoutInfo = new SidecarWindowLayoutInfo();
        if (activity == null) {
            return windowLayoutInfo;
        }
        windowLayoutInfo.displayFeatures = getDisplayFeatures(activity);
        return windowLayoutInfo;
    }

    private List<SidecarDisplayFeature> getDisplayFeatures(@NonNull Activity activity) {
        int displayId = activity.getDisplay().getDisplayId();
        if (displayId != DEFAULT_DISPLAY) {
            return Collections.emptyList();
        }

        if (activity.isInMultiWindowMode()) {
            // It is recommended not to report any display features in multi-window mode, since it
            // won't be possible to synchronize the display feature positions with window movement.
            return Collections.emptyList();
        }

        List<SidecarDisplayFeature> features = new ArrayList<>();
        for (CommonFoldingFeature baseFeature : mStoredFeatures) {
            SidecarDisplayFeature feature = new SidecarDisplayFeature();
            Rect featureRect = baseFeature.getRect();
            rotateRectToDisplayRotation(displayId, featureRect);
            transformToWindowSpaceRect(activity, featureRect);
            feature.setRect(featureRect);
            feature.setType(baseFeature.getType());
            features.add(feature);
        }
        return Collections.unmodifiableList(features);
    }

    @Override
    protected void onListenersChanged() {
        if (hasListeners()) {
            onDisplayFeaturesChanged(mStoredFeatures);
        }
    }

    private final class NotifyOnConfigurationChanged extends EmptyLifecycleCallbacksAdapter {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            super.onActivityCreated(activity, savedInstanceState);
            onDisplayFeaturesChangedForActivity(activity);
        }

        @Override
        public void onActivityConfigurationChanged(Activity activity) {
            super.onActivityConfigurationChanged(activity);
            onDisplayFeaturesChangedForActivity(activity);
        }

        private void onDisplayFeaturesChangedForActivity(@NonNull Activity activity) {
            IBinder token = activity.getWindow().getAttributes().token;
            if (token == null || mWindowLayoutChangeListenerTokens.contains(token)) {
                onDisplayFeaturesChanged(mStoredFeatures);
            }
        }
    }
}
