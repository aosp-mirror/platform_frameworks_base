/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.wm;

import android.os.Handler;
import android.os.RemoteException;
import android.view.IDisplayWindowRotationCallback;
import android.view.IDisplayWindowRotationController;
import android.view.IWindowManager;
import android.window.WindowContainerTransaction;

import java.util.ArrayList;

/**
 * This module deals with display rotations coming from WM. When WM starts a rotation: after it has
 * frozen the screen, it will call into this class. This will then call all registered local
 * controllers and give them a chance to queue up task changes to be applied synchronously with that
 * rotation.
 */
public class DisplayChangeController {

    private final Handler mHandler;
    private final IWindowManager mWmService;

    private final ArrayList<OnDisplayChangingListener> mRotationListener =
            new ArrayList<>();
    private final ArrayList<OnDisplayChangingListener> mTmpListeners = new ArrayList<>();

    private final IDisplayWindowRotationController mDisplayRotationController =
            new IDisplayWindowRotationController.Stub() {
                @Override
                public void onRotateDisplay(int displayId, final int fromRotation,
                        final int toRotation, IDisplayWindowRotationCallback callback) {
                    mHandler.post(() -> {
                        WindowContainerTransaction t = new WindowContainerTransaction();
                        synchronized (mRotationListener) {
                            mTmpListeners.clear();
                            // Make a local copy in case the handlers add/remove themselves.
                            mTmpListeners.addAll(mRotationListener);
                        }
                        for (OnDisplayChangingListener c : mTmpListeners) {
                            c.onRotateDisplay(displayId, fromRotation, toRotation, t);
                        }
                        try {
                            callback.continueRotateDisplay(toRotation, t);
                        } catch (RemoteException e) {
                        }
                    });
                }
            };

    public DisplayChangeController(Handler mainHandler, IWindowManager wmService) {
        mHandler = mainHandler;
        mWmService = wmService;
        try {
            mWmService.setDisplayWindowRotationController(mDisplayRotationController);
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to register rotation controller");
        }
    }

    /**
     * Adds a display rotation controller.
     */
    public void addRotationListener(OnDisplayChangingListener listener) {
        synchronized (mRotationListener) {
            mRotationListener.add(listener);
        }
    }

    /**
     * Removes a display rotation controller.
     */
    public void removeRotationListener(OnDisplayChangingListener listener) {
        synchronized (mRotationListener) {
            mRotationListener.remove(listener);
        }
    }

    /**
     * Give a listener a chance to queue up configuration changes to execute as part of a
     * display rotation. The contents of {@link #onRotateDisplay} must run synchronously.
     */
    public interface OnDisplayChangingListener {
        /**
         * Called before the display is rotated. Contents of this method must run synchronously.
         * @param displayId Id of display that is rotating.
         * @param fromRotation starting rotation of the display.
         * @param toRotation target rotation of the display (after rotating).
         * @param t A task transaction to populate.
         */
        void onRotateDisplay(int displayId, int fromRotation, int toRotation,
                WindowContainerTransaction t);
    }
}
