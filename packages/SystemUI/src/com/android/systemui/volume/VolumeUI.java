/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import com.android.settingslib.volume.data.repository.AudioRepository;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.volume.domain.interactor.AudioSharingInteractor;
import com.android.systemui.volume.shared.VolumeLogger;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.io.PrintWriter;

import javax.inject.Inject;

@SysUISingleton
public class VolumeUI implements CoreStartable, ConfigurationController.ConfigurationListener {
    private static final String TAG = "VolumeUI";
    private static boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);

    private boolean mEnabled;
    private final Context mContext;
    private VolumeDialogComponent mVolumeComponent;
    private AudioSharingInteractor mAudioSharingInteractor;
    private AudioRepository mAudioRepository;
    private JavaAdapter mJavaAdapter;
    private VolumeLogger mVolumeLogger;

    @Inject
    public VolumeUI(Context context,
            VolumeDialogComponent volumeDialogComponent,
            AudioRepository audioRepository,
            AudioSharingInteractor audioSharingInteractor,
            JavaAdapter javaAdapter,
            VolumeLogger volumeLogger) {
        mContext = context;
        mVolumeComponent = volumeDialogComponent;
        mAudioRepository = audioRepository;
        mAudioSharingInteractor = audioSharingInteractor;
        mJavaAdapter = javaAdapter;
        mVolumeLogger = volumeLogger;
    }

    @Override
    public void start() {
        mAudioRepository.init();
        boolean enableVolumeUi = mContext.getResources().getBoolean(R.bool.enable_volume_ui);
        boolean enableSafetyWarning =
                mContext.getResources().getBoolean(R.bool.enable_safety_warning);
        mEnabled = enableVolumeUi || enableSafetyWarning;
        if (!mEnabled) return;

        mVolumeComponent.setEnableDialogs(enableVolumeUi, enableSafetyWarning);
        setDefaultVolumeController();
        Function1<Throwable, Unit> errorCallback = (ex) -> {
            mVolumeLogger.onAudioSharingAvailabilityRequestedError("start()",
                    ex.getMessage());
            return null;
        };
        var unused =
                mJavaAdapter.<Context, Boolean>callSuspend(
                        mAudioSharingInteractor::audioSharingVolumeBarAvailable, mContext,
                        result -> {
                            if (result) {
                                mAudioSharingInteractor.handlePrimaryGroupChange();
                            }
                            return null;
                        },
                        errorCallback,
                        errorCallback);
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (!mEnabled) return;
        mVolumeComponent.onConfigurationChanged(newConfig);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.print("mEnabled=");
        pw.println(mEnabled);
        if (!mEnabled) return;
        mVolumeComponent.dump(pw, args);
    }

    private void setDefaultVolumeController() {
        DndTile.setVisible(mContext, true);
        if (LOGD) Log.d(TAG, "Registering default volume controller");
        mVolumeComponent.register();
    }
}
