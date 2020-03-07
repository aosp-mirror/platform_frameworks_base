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

package com.android.systemui.shared.system;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;

/**
 * A generic receiver that specifically handles SurfaceView request created by {@link
 * com.android.systemui.shared.system.SurfaceViewRequestUtils}.
 */
public class SurfaceViewRequestReceiver {

    private final int mOpacity;
    private SurfaceControlViewHost mSurfaceControlViewHost;

    public SurfaceViewRequestReceiver() {
        this(PixelFormat.TRANSPARENT);
    }

    public SurfaceViewRequestReceiver(int opacity) {
        mOpacity = opacity;
    }

    /** Called whenever a surface view request is received. */
    public void onReceive(Context context, Bundle bundle, View view) {
        if (mSurfaceControlViewHost != null) {
            mSurfaceControlViewHost.die();
        }
        SurfaceControl surfaceControl = SurfaceViewRequestUtils.getSurfaceControl(bundle);
        if (surfaceControl != null) {
            IBinder hostToken = SurfaceViewRequestUtils.getHostToken(bundle);

            WindowlessWindowManager windowlessWindowManager =
                    new WindowlessWindowManager(context.getResources().getConfiguration(),
                            surfaceControl, hostToken);
            mSurfaceControlViewHost = new SurfaceControlViewHost(context,
                    context.getDisplayNoVerify(), windowlessWindowManager);
            WindowManager.LayoutParams layoutParams =
                    new WindowManager.LayoutParams(
                            surfaceControl.getWidth(),
                            surfaceControl.getHeight(),
                            WindowManager.LayoutParams.TYPE_APPLICATION,
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                            mOpacity);

            mSurfaceControlViewHost.addView(view, layoutParams);
        }
    }
}
