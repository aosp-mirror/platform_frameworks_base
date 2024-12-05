/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.common.split;

import static com.android.wm.shell.shared.split.SplitScreenConstants.NOT_IN_SPLIT;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SplitScreenState;

/**
 * A class that manages the "state" of split screen. See {@link SplitScreenState} for definitions.
 */
public class SplitState {
    private @SplitScreenState int mState = NOT_IN_SPLIT;

    /** Updates the current state of split screen on this device. */
    public void set(@SplitScreenState int newState) {
        mState = newState;
    }

    /** Reports the current state of split screen on this device. */
    public @SplitScreenState int get() {
        return mState;
    }

    /** Sets NOT_IN_SPLIT when user exits split. */
    public void exit() {
        set(NOT_IN_SPLIT);
    }
}
