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

import android.app.IWallpaperManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.statusbar.phone.DozeParameters;

import java.io.PrintWriter;

/**
 * Propagates doze state to wallpaper engine.
 */
public class DozeWallpaperState implements DozeMachine.Part {

    private static final String TAG = "DozeWallpaperState";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final IWallpaperManager mWallpaperManagerService;
    private boolean mKeyguardVisible;
    private boolean mIsAmbientMode;
    private final DozeParameters mDozeParameters;

    public DozeWallpaperState(Context context, DozeParameters dozeParameters) {
        this(IWallpaperManager.Stub.asInterface(
                ServiceManager.getService(Context.WALLPAPER_SERVICE)),
                dozeParameters, KeyguardUpdateMonitor.getInstance(context));
    }

    @VisibleForTesting
    DozeWallpaperState(IWallpaperManager wallpaperManagerService, DozeParameters parameters,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        mWallpaperManagerService = wallpaperManagerService;
        mDozeParameters = parameters;
        keyguardUpdateMonitor.registerCallback(new KeyguardUpdateMonitorCallback() {
            @Override
            public void onKeyguardVisibilityChanged(boolean showing) {
                mKeyguardVisible = showing;
            }
        });
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        final boolean isAmbientMode;
        switch (newState) {
            case DOZE_AOD:
            case DOZE_AOD_PAUSING:
            case DOZE_AOD_PAUSED:
            case DOZE_REQUEST_PULSE:
            case DOZE_PULSING:
            case DOZE_PULSE_DONE:
                isAmbientMode = mDozeParameters.getAlwaysOn();
                break;
            default:
                isAmbientMode = false;
        }

        final boolean animated;
        if (isAmbientMode) {
            animated = mDozeParameters.shouldControlScreenOff();
        } else {
            animated = !mDozeParameters.getDisplayNeedsBlanking();
        }

        if (isAmbientMode != mIsAmbientMode) {
            mIsAmbientMode = isAmbientMode;
            try {
                if (DEBUG) {
                    Log.i(TAG, "AOD wallpaper state changed to: " + mIsAmbientMode
                            + ", animated: " + animated);
                }
                mWallpaperManagerService.setInAmbientMode(mIsAmbientMode, animated);
            } catch (RemoteException e) {
                // Cannot notify wallpaper manager service, but it's fine, let's just skip it.
                Log.w(TAG, "Cannot notify state to WallpaperManagerService: " + mIsAmbientMode);
            }
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeWallpaperState:");
        pw.println(" isAmbientMode: " + mIsAmbientMode);
    }
}
