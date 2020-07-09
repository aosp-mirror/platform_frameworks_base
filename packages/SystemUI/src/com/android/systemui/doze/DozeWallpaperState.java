/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import android.annotation.Nullable;
import android.app.IWallpaperManager;
import android.os.RemoteException;
import android.util.Log;

import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.DozeParameters;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Propagates doze state to wallpaper engine.
 */
@DozeScope
public class DozeWallpaperState implements DozeMachine.Part {

    private static final String TAG = "DozeWallpaperState";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @Nullable
    private final IWallpaperManager mWallpaperManagerService;
    private final DozeParameters mDozeParameters;
    private final BiometricUnlockController mBiometricUnlockController;
    private boolean mIsAmbientMode;

    @Inject
    public DozeWallpaperState(
            @Nullable IWallpaperManager wallpaperManagerService,
            BiometricUnlockController biometricUnlockController,
            DozeParameters parameters) {
        mWallpaperManagerService = wallpaperManagerService;
        mBiometricUnlockController = biometricUnlockController;
        mDozeParameters = parameters;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        final boolean isAmbientMode;
        switch (newState) {
            case DOZE:
            case DOZE_AOD:
            case DOZE_AOD_DOCKED:
            case DOZE_AOD_PAUSING:
            case DOZE_AOD_PAUSED:
            case DOZE_REQUEST_PULSE:
            case DOZE_PULSE_DONE:
            case DOZE_PULSING:
                isAmbientMode = true;
                break;
            case DOZE_PULSING_BRIGHT:
            default:
                isAmbientMode = false;
        }
        final boolean animated;
        if (isAmbientMode) {
            animated = mDozeParameters.shouldControlScreenOff();
        } else {
            boolean wakingUpFromPulse = oldState == DozeMachine.State.DOZE_PULSING
                    && newState == DozeMachine.State.FINISH;
            boolean fastDisplay = !mDozeParameters.getDisplayNeedsBlanking();
            animated = (fastDisplay && !mBiometricUnlockController.unlockedByWakeAndUnlock())
                    || wakingUpFromPulse;
        }

        if (isAmbientMode != mIsAmbientMode) {
            mIsAmbientMode = isAmbientMode;
            if (mWallpaperManagerService != null) {
                try {
                    long duration = animated ? StackStateAnimator.ANIMATION_DURATION_WAKEUP : 0L;
                    if (DEBUG) {
                        Log.i(TAG, "AOD wallpaper state changed to: " + mIsAmbientMode
                            + ", animationDuration: " + duration);
                    }
                    mWallpaperManagerService.setInAmbientMode(mIsAmbientMode, duration);
                } catch (RemoteException e) {
                    // Cannot notify wallpaper manager service, but it's fine, let's just skip it.
                    Log.w(TAG, "Cannot notify state to WallpaperManagerService: " + mIsAmbientMode);
                }
            }
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeWallpaperState:");
        pw.println(" isAmbientMode: " + mIsAmbientMode);
        pw.println(" hasWallpaperService: " + (mWallpaperManagerService != null));
    }
}
