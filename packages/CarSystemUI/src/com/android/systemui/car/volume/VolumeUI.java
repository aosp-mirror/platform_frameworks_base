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

package com.android.systemui.car.volume;

import android.car.Car;
import android.car.media.CarAudioManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.volume.VolumeDialogComponent;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/** The entry point for controlling the volume ui in cars. */
@Singleton
public class VolumeUI extends SystemUI {

    private static final String TAG = "VolumeUI";
    private final Resources mResources;
    private final Handler mMainHandler;
    private final CarServiceProvider mCarServiceProvider;
    private final Lazy<VolumeDialogComponent> mVolumeDialogComponentLazy;

    private final CarAudioManager.CarVolumeCallback mVolumeChangeCallback =
            new CarAudioManager.CarVolumeCallback() {
                @Override
                public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
                    initVolumeDialogComponent();
                }

                @Override
                public void onMasterMuteChanged(int zoneId, int flags) {
                    initVolumeDialogComponent();
                }

                private void initVolumeDialogComponent() {
                    if (mVolumeDialogComponent == null) {
                        mMainHandler.post(() -> {
                            mVolumeDialogComponent = mVolumeDialogComponentLazy.get();
                            mVolumeDialogComponent.register();
                        });
                        mCarAudioManager.unregisterCarVolumeCallback(mVolumeChangeCallback);
                    }
                }
            };

    private boolean mEnabled;
    private CarAudioManager mCarAudioManager;
    private VolumeDialogComponent mVolumeDialogComponent;

    @Inject
    public VolumeUI(
            Context context,
            @Main Resources resources,
            @Main Handler mainHandler,
            CarServiceProvider carServiceProvider,
            Lazy<VolumeDialogComponent> volumeDialogComponentLazy
    ) {
        super(context);
        mResources = resources;
        mMainHandler = mainHandler;
        mCarServiceProvider = carServiceProvider;
        mVolumeDialogComponentLazy = volumeDialogComponentLazy;
    }

    @Override
    public void start() {
        boolean enableVolumeUi = mResources.getBoolean(R.bool.enable_volume_ui);
        mEnabled = enableVolumeUi;
        if (!mEnabled) return;

        mCarServiceProvider.addListener(car -> {
            if (mCarAudioManager != null) {
                return;
            }

            mCarAudioManager = (CarAudioManager) car.getCarManager(Car.AUDIO_SERVICE);
            Log.d(TAG, "Registering mVolumeChangeCallback.");
            // This volume call back is never unregistered because CarStatusBar is
            // never destroyed.
            mCarAudioManager.registerCarVolumeCallback(mVolumeChangeCallback);
        });
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!mEnabled) return;
        if (mVolumeDialogComponent != null) {
            mVolumeDialogComponent.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("mEnabled="); pw.println(mEnabled);
        if (!mEnabled) return;
        if (mVolumeDialogComponent != null) {
            mVolumeDialogComponent.dump(fd, pw, args);
        }
    }
}
