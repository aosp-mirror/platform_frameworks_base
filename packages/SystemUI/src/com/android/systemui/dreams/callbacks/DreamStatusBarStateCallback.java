/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.dreams.callbacks;

import android.util.Log;

import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.statusbar.SysuiStatusBarStateController;

import javax.inject.Inject;

/**
 * A callback that informs {@link SysuiStatusBarStateController} when the dream state has changed.
 */
public class DreamStatusBarStateCallback implements Monitor.Callback {
    private static final String TAG = "DreamStatusBarCallback";

    private final SysuiStatusBarStateController mStateController;

    @Inject
    public DreamStatusBarStateCallback(SysuiStatusBarStateController statusBarStateController) {
        mStateController = statusBarStateController;
    }

    @Override
    public void onConditionsChanged(boolean allConditionsMet) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onConditionChanged:" + allConditionsMet);
        }

        mStateController.setIsDreaming(allConditionsMet);
    }
}
