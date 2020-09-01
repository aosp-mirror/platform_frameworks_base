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

package com.android.systemui.car.navigationbar;

import android.app.ActivityTaskManager;
import android.util.Log;

import com.android.systemui.shared.system.TaskStackChangeListener;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * An implementation of TaskStackChangeListener, that listens for changes in the system
 * task stack and notifies the navigation bar.
 */
@Singleton
class ButtonSelectionStateListener extends TaskStackChangeListener {
    private static final String TAG = ButtonSelectionStateListener.class.getSimpleName();

    private final ButtonSelectionStateController mButtonSelectionStateController;

    @Inject
    ButtonSelectionStateListener(ButtonSelectionStateController carNavigationButtonController) {
        mButtonSelectionStateController = carNavigationButtonController;
    }

    @Override
    public void onTaskStackChanged() {
        try {
            mButtonSelectionStateController.taskChanged(
                    ActivityTaskManager.getService().getAllStackInfos());
        } catch (Exception e) {
            Log.e(TAG, "Getting StackInfo from activity manager failed", e);
        }
    }

    @Override
    public void onTaskDisplayChanged(int taskId, int newDisplayId) {
        try {
            mButtonSelectionStateController.taskChanged(
                    ActivityTaskManager.getService().getAllStackInfos());
        } catch (Exception e) {
            Log.e(TAG, "Getting StackInfo from activity manager failed", e);
        }

    }
}
