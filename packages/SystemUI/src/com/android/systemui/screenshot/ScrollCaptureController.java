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

package com.android.systemui.screenshot;

import android.os.IBinder;
import android.view.IWindowManager;

import javax.inject.Inject;

/**
 * Stub
 */
public class ScrollCaptureController {

    public static final int STATUS_A = 0;
    public static final int STATUS_B = 1;

    private final IWindowManager mWindowManagerService;
    private StatusListener mListener;

    /**
     *
     * @param windowManagerService
     */
    @Inject
    public ScrollCaptureController(IWindowManager windowManagerService) {
        mWindowManagerService = windowManagerService;
    }

    interface StatusListener {
        void onScrollCaptureStatus(boolean available);
    }

    /**
     *
     * @param window
     * @param listener
     */
    public void getStatus(IBinder window, StatusListener listener) {
        mListener = listener;
//        try {
//           mWindowManagerService.requestScrollCapture(window, new ClientCallbacks());
//        } catch (RemoteException e) {
//        }
    }

}
