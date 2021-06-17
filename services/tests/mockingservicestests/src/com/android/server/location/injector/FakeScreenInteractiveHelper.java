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

package com.android.server.location.injector;

/**
 * Version of ScreenInteractiveHelper for testing. Screen is initialized as interactive (on).
 */
public class FakeScreenInteractiveHelper extends ScreenInteractiveHelper {

    private boolean mIsInteractive;

    public FakeScreenInteractiveHelper() {
        mIsInteractive = true;
    }

    public void setScreenInteractive(boolean interactive) {
        if (interactive == mIsInteractive) {
            return;
        }

        mIsInteractive = interactive;
        notifyScreenInteractiveChanged(interactive);
    }

    public boolean isInteractive() {
        return mIsInteractive;
    }
}
