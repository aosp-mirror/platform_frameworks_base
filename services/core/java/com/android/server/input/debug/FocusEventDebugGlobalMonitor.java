/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input.debug;

import android.view.Display;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

import com.android.server.UiThread;
import com.android.server.input.InputManagerService;

/**
 * Receives input events before they are dispatched and reports them to FocusEventDebugView.
 */
class FocusEventDebugGlobalMonitor extends InputEventReceiver {
    private final FocusEventDebugView mDebugView;

    FocusEventDebugGlobalMonitor(FocusEventDebugView debugView, InputManagerService service) {
        super(service.monitorInput("FocusEventDebugGlobalMonitor", Display.DEFAULT_DISPLAY),
            UiThread.getHandler().getLooper());
        mDebugView = debugView;
    }

    @Override
    public void onInputEvent(InputEvent event) {
        try {
            if (event instanceof MotionEvent) {
                mDebugView.reportMotionEvent((MotionEvent) event);
            }
        } finally {
            finishInputEvent(event, false);
        }
    }
}
