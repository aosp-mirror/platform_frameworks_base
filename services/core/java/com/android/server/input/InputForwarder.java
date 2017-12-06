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

package com.android.server.input;

import android.app.IInputForwarder;
import android.hardware.input.InputManagerInternal;
import android.view.InputEvent;
import android.os.Binder;

import com.android.server.LocalServices;

import static android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;

/**
 * Basic implementation of {@link IInputForwarder}.
 */
class InputForwarder extends IInputForwarder.Stub {

    private final InputManagerInternal mInputManagerInternal;
    private final int mDisplayId;

    InputForwarder(int displayId) {
        mDisplayId = displayId;
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
    }

    @Override
    public boolean forwardEvent(InputEvent event) {
        return mInputManagerInternal.injectInputEvent(event, mDisplayId,
                INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}