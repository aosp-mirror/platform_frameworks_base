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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArraySet;
import android.util.Log;

import androidx.window.common.BaseDataProducer;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.EmptyLifecycleCallbacksAdapter;
import androidx.window.common.RawFoldingFeatureProducer;
import androidx.window.common.layout.CommonFoldingFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Basic implementation of the {@link SidecarInterface}. An OEM can choose to use it as the base
 * class for their implementation.
 */
class SidecarImpl implements SidecarInterface {

    private static final String TAG = "WindowManagerSidecar";

    @Nullable
    private SidecarCallback mSidecarCallback;
    private final ArraySet<IBinder> mWindowLayoutChangeListenerTokens = new ArraySet<>();
    private boolean mDeviceStateChangeListenerRegistered;
    @NonNull
    private List<CommonFoldingFeature> mStoredFeatures = new ArrayList<>();

    SidecarImpl(Context context) {
        ((Application) context.getApplicationContext())
                .registerActivityLifecycleCallbacks(new SidecarImpl.NotifyOnConfigurationChanged());
        RawFoldingFeatureProducer settingsFeatureProducer = new RawFoldingFeatureProducer(context);
        BaseDataProducer<List<CommonFoldingFeature>> foldingFeatureProducer =
                new DeviceStateManagerFoldingFeatureProducer(context,
                        settingsFeatureProducer,
                        context.getSystemService(DeviceStateManager.class));

        foldingFeatureProducer.addDataChangedCallback(this::onDisplayFeaturesChanged);
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
    public void setSidecarCallback(@NonNull SidecarCallback sidecarCallback) {
        mSidecarCallback = sidecarCallback;
    }

    @Override
    public void onWindowLayoutChangeListenerAdded(@NonNull IBinder iBinder) {
        mWindowLayoutChangeListenerTokens.add(iBinder);
        onListenersChanged();
    }

    @Override
    public void onWindowLayoutChangeListenerRemoved(@NonNull IBinder iBinder) {
        mWindowLayoutChangeListenerTokens.remove(iBinder);
        onListenersChanged();
    }

    @Override
    public void onDeviceStateListenersChanged(boolean isEmpty) {
        mDeviceStateChangeListenerRegistered = !isEmpty;
        onListenersChanged();
    }

    private void setStoredFeatures(@NonNull List<CommonFoldingFeature> storedFeatures) {
        mStoredFeatures = Objects.requireNonNull(storedFeatures);
    }

    private void onDisplayFeaturesChanged(@NonNull List<CommonFoldingFeature> storedFeatures) {
        setStoredFeatures(storedFeatures);
        updateDeviceState(getDeviceState());
        for (IBinder windowToken : getWindowsListeningForLayoutChanges()) {
            SidecarWindowLayoutInfo newLayout = getWindowLayoutInfo(windowToken);
            updateWindowLayout(windowToken, newLayout);
        }
    }

    void updateDeviceState(@NonNull SidecarDeviceState newState) {
        if (mSidecarCallback != null) {
            try {
                mSidecarCallback.onDeviceStateChanged(newState);
            } catch (AbstractMethodError e) {
                Log.e(TAG, "App is using an outdated Window Jetpack library", e);
            }
        }
    }

    void updateWindowLayout(@NonNull IBinder windowToken,
            @NonNull SidecarWindowLayoutInfo newLayout) {
        if (mSidecarCallback != null) {
            try {
                mSidecarCallback.onWindowLayoutChanged(windowToken, newLayout);
            } catch (AbstractMethodError e) {
                Log.e(TAG, "App is using an outdated Window Jetpack library", e);
            }
        }
    }

    @NonNull
    private Set<IBinder> getWindowsListeningForLayoutChanges() {
        return mWindowLayoutChangeListenerTokens;
    }

    protected boolean hasListeners() {
        return !mWindowLayoutChangeListenerTokens.isEmpty() || mDeviceStateChangeListenerRegistered;
    }

    private void onListenersChanged() {
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
