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

import android.annotation.Nullable;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.window.common.CommonFoldingFeature;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.EmptyLifecycleCallbacksAdapter;
import androidx.window.common.RawFoldingFeatureProducer;
import androidx.window.util.BaseDataProducer;

import java.util.ArrayList;
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
        RawFoldingFeatureProducer settingsFeatureProducer = new RawFoldingFeatureProducer(context);
        BaseDataProducer<List<CommonFoldingFeature>> foldingFeatureProducer =
                new DeviceStateManagerFoldingFeatureProducer(context,
                        settingsFeatureProducer,
                        context.getSystemService(DeviceStateManager.class));

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
        return SidecarHelper.calculateDeviceState(mStoredFeatures);
    }

    @NonNull
    @Override
    public SidecarWindowLayoutInfo getWindowLayoutInfo(@NonNull IBinder windowToken) {
        return SidecarHelper.calculateWindowLayoutInfo(windowToken, mStoredFeatures);
    }

    @Override
    protected void onListenersChanged() {
        if (hasListeners()) {
            onDisplayFeaturesChanged(mStoredFeatures);
        }
    }

    private final class NotifyOnConfigurationChanged extends EmptyLifecycleCallbacksAdapter {
        @Override
        public void onActivityCreated(@NonNull Activity activity,
                @Nullable Bundle savedInstanceState) {
            super.onActivityCreated(activity, savedInstanceState);
            onDisplayFeaturesChangedForActivity(activity);
        }

        @Override
        public void onActivityConfigurationChanged(@NonNull Activity activity) {
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
