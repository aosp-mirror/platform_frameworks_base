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
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.window.util.BaseDisplayFeature;
import androidx.window.util.SettingsConfigProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference implementation of androidx.window.sidecar OEM interface for use with
 * WindowManager Jetpack.
 */
class SampleSidecarImpl extends StubSidecar implements
        SettingsConfigProvider.StateChangeCallback {
    private static final String TAG = "SampleSidecar";

    private final SettingsConfigProvider mConfigProvider;

    SampleSidecarImpl(Context context) {
        mConfigProvider = new SettingsConfigProvider(context, this);
    }

    @Override
    public void onDevicePostureChanged() {
        updateDeviceState(getDeviceState());
    }

    @Override
    public void onDisplayFeaturesChanged() {
        for (IBinder windowToken : getWindowsListeningForLayoutChanges()) {
            SidecarWindowLayoutInfo newLayout = getWindowLayoutInfo(windowToken);
            updateWindowLayout(windowToken, newLayout);
        }
    }

    @NonNull
    @Override
    public SidecarDeviceState getDeviceState() {
        SidecarDeviceState deviceState = new SidecarDeviceState();
        deviceState.posture = mConfigProvider.getDeviceState();
        return deviceState;
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
        List<SidecarDisplayFeature> features = new ArrayList<SidecarDisplayFeature>();
        int displayId = activity.getDisplayId();
        if (displayId != DEFAULT_DISPLAY) {
            Log.w(TAG, "This sample doesn't support display features on secondary displays");
            return features;
        }

        if (activity.isInMultiWindowMode()) {
            // It is recommended not to report any display features in multi-window mode, since it
            // won't be possible to synchronize the display feature positions with window movement.
            return features;
        }

        List<BaseDisplayFeature> storedFeatures = mConfigProvider.getDisplayFeatures();
        for (BaseDisplayFeature baseFeature : storedFeatures) {
            SidecarDisplayFeature feature = new SidecarDisplayFeature();
            Rect featureRect = baseFeature.getRect();
            rotateRectToDisplayRotation(displayId, featureRect);
            transformToWindowSpaceRect(activity, featureRect);
            feature.setRect(featureRect);
            feature.setType(baseFeature.getType());
            features.add(feature);
        }
        return features;
    }

    @Override
    protected void onListenersChanged() {
        if (hasListeners()) {
            mConfigProvider.registerObserversIfNeeded();
        } else {
            mConfigProvider.unregisterObserversIfNeeded();
        }
    }
}
