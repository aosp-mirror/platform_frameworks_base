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
 * limitations under the License.
 */

package com.android.systemui.shared.system;

import static android.view.Display.DEFAULT_DISPLAY;

import android.graphics.Rect;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManagerGlobal;

import com.android.systemui.shared.recents.view.AppTransitionAnimationSpecsFuture;
import com.android.systemui.shared.recents.view.RecentsTransition;

public class WindowManagerWrapper {

    private static final String TAG = "WindowManagerWrapper";

    private static final WindowManagerWrapper sInstance = new WindowManagerWrapper();

    public static WindowManagerWrapper getInstance() {
        return sInstance;
    }

    /**
     * @return the stable insets for the primary display.
     */
    public void getStableInsets(Rect outStableInsets) {
        try {
            WindowManagerGlobal.getWindowManagerService().getStableInsets(DEFAULT_DISPLAY,
                    outStableInsets);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get stable insets", e);
        }
    }

    /**
     * Overrides a pending app transition.
     */
    public void overridePendingAppTransitionMultiThumbFuture(
            AppTransitionAnimationSpecsFuture animationSpecFuture,
            Runnable animStartedCallback, Handler animStartedCallbackHandler, boolean scaleUp) {
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .overridePendingAppTransitionMultiThumbFuture(animationSpecFuture.getFuture(),
                            RecentsTransition.wrapStartedListener(animStartedCallbackHandler,
                                    animStartedCallback), scaleUp);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to override pending app transition (multi-thumbnail future): ", e);
        }
    }
}
