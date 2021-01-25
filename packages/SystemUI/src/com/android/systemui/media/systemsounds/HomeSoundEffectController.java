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

package com.android.systemui.media.systemsounds;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.media.AudioManager;

import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import javax.inject.Inject;

/**
 * If a sound effect is defined for {@link android.media.AudioManager#FX_HOME}, a sound is played
 * when the home task moves to front and the last task that moved to front was not the home task.
 */
@SysUISingleton
public class HomeSoundEffectController extends SystemUI {

    private final AudioManager mAudioManager;
    private final TaskStackChangeListeners mTaskStackChangeListeners;
    // Initialize true because home sound should not be played when the system boots.
    private boolean mIsLastTaskHome = true;

    @Inject
    public HomeSoundEffectController(
            Context context,
            AudioManager audioManager,
            TaskStackChangeListeners taskStackChangeListeners) {
        super(context);
        mAudioManager = audioManager;
        mTaskStackChangeListeners = taskStackChangeListeners;
    }

    @Override
    public void start() {
        if (mAudioManager.isHomeSoundEffectEnabled()) {
            mTaskStackChangeListeners.registerTaskStackListener(
                    new TaskStackChangeListener() {
                        @Override
                        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                            handleHomeTaskMovedToFront(taskInfo);
                        }
                    });
        }
    }

    private boolean isHomeTask(ActivityManager.RunningTaskInfo taskInfo) {
        return taskInfo.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_HOME;
    }

    /**
     * To enable a home sound, check if the home app moves to front.
     */
    private void handleHomeTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
        boolean isCurrentTaskHome = isHomeTask(taskInfo);
        // If the last task is home we don't want to play the home sound. This avoids playing
        // the home sound after FallbackHome transitions to Home
        if (!mIsLastTaskHome && isCurrentTaskHome) {
            mAudioManager.playSoundEffect(AudioManager.FX_HOME);
        }
        mIsLastTaskHome = isCurrentTaskHome;
    }
}
