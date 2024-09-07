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

import android.Manifest;
import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.util.Slog;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.res.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import javax.inject.Inject;

/**
 * If a sound effect is defined for {@link android.media.AudioManager#FX_HOME}, a
 * {@link TaskStackChangeListener} is registered to play a home sound effect when conditions
 * documented at {@link #handleTaskStackChanged} apply.
 */
@SysUISingleton
public class HomeSoundEffectController implements CoreStartable {

    private static final String TAG = "HomeSoundEffectController";
    private final AudioManager mAudioManager;
    private final TaskStackChangeListeners mTaskStackChangeListeners;
    private final ActivityManagerWrapper mActivityManagerWrapper;
    private final PackageManager mPm;
    private final boolean mPlayHomeSoundAfterAssistant;
    private final boolean mPlayHomeSoundAfterDream;
    // Initialize true because home sound should not be played when the system boots.
    private boolean mIsLastTaskHome = true;
    // mLastHomePackageName could go out of sync in rare circumstances if launcher changes,
    // but it's cheaper than the alternative and potential impact is low
    private String mLastHomePackageName;
    private @WindowConfiguration.ActivityType int mLastActivityType;
    private boolean mLastActivityHasNoHomeSound = false;
    private int mLastTaskId;

    @Inject
    public HomeSoundEffectController(
            Context context,
            AudioManager audioManager,
            TaskStackChangeListeners taskStackChangeListeners,
            ActivityManagerWrapper activityManagerWrapper,
            PackageManager packageManager) {
        mAudioManager = audioManager;
        mTaskStackChangeListeners = taskStackChangeListeners;
        mActivityManagerWrapper = activityManagerWrapper;
        mPm = packageManager;
        mPlayHomeSoundAfterAssistant = context.getResources().getBoolean(
                R.bool.config_playHomeSoundAfterAssistant);
        mPlayHomeSoundAfterDream = context.getResources().getBoolean(
                R.bool.config_playHomeSoundAfterDream);
    }

    @Override
    public void start() {
        if (mAudioManager.isHomeSoundEffectEnabled()) {
            mTaskStackChangeListeners.registerTaskStackListener(
                    new TaskStackChangeListener() {
                        @Override
                        public void onTaskStackChanged() {
                            ActivityManager.RunningTaskInfo currentTask =
                                    mActivityManagerWrapper.getRunningTask();
                            if (currentTask == null || currentTask.topActivityInfo == null) {
                                return;
                            }
                            handleTaskStackChanged(currentTask);
                        }
                    });
        }
    }

    private boolean hasFlagNoSound(ActivityInfo activityInfo) {
        if ((activityInfo.privateFlags & ActivityInfo.PRIVATE_FLAG_HOME_TRANSITION_SOUND) == 0) {
            // Only allow flag if app has permission
            if (mPm.checkPermission(Manifest.permission.DISABLE_SYSTEM_SOUND_EFFECTS,
                    activityInfo.packageName) == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                Slog.w(TAG,
                        "Activity has flag playHomeTransition set to false but doesn't hold "
                                + "required permission "
                                + Manifest.permission.DISABLE_SYSTEM_SOUND_EFFECTS);
                return false;
            }
        }
        return false;
    }

    /**
     * The home sound is played if all of the following conditions are met:
     * <ul>
     * <li>The last task which moved to front was not home. This avoids playing the sound
     * e.g. after FallbackHome transitions to home, another activity of the home app like a
     * notification panel moved to front, or in case the home app crashed.</li>
     * <li>The current activity which moved to front is home</li>
     * <li>The topActivity of the last task has {@link android.R.attr#playHomeTransitionSound} set
     * to <code>true</code>.</li>
     * <li>The topActivity of the last task is not of type
     * {@link WindowConfiguration#ACTIVITY_TYPE_ASSISTANT} if config_playHomeSoundAfterAssistant is
     * set to <code>false</code> (default).</li>
     * <li>The topActivity of the last task is not of type
     * {@link WindowConfiguration#ACTIVITY_TYPE_DREAM} if config_playHomeSoundAfterDream is
     * set to <code>false</code> (default).</li>
     * </ul>
     */
    private boolean shouldPlayHomeSoundForCurrentTransition(
            ActivityManager.RunningTaskInfo currentTask) {
        boolean isHomeActivity =
                currentTask.topActivityType == WindowConfiguration.ACTIVITY_TYPE_HOME;
        if (currentTask.taskId == mLastTaskId) {
            return false;
        }
        if (mIsLastTaskHome || !isHomeActivity) {
            return false;
        }
        if (mLastActivityHasNoHomeSound) {
            return false;
        }
        if (mLastActivityType == WindowConfiguration.ACTIVITY_TYPE_ASSISTANT
                && !mPlayHomeSoundAfterAssistant) {
            return false;
        }
        if (mLastActivityType == WindowConfiguration.ACTIVITY_TYPE_DREAM
                && !mPlayHomeSoundAfterDream) {
            return false;
        }
        return true;
    }

    private void updateLastTaskInfo(ActivityManager.RunningTaskInfo currentTask) {
        mLastTaskId = currentTask.taskId;
        mLastActivityType = currentTask.topActivityType;
        mLastActivityHasNoHomeSound = hasFlagNoSound(currentTask.topActivityInfo);
        boolean isHomeActivity =
                currentTask.topActivityType == WindowConfiguration.ACTIVITY_TYPE_HOME;
        boolean isHomePackage = currentTask.topActivityInfo.packageName.equals(
                mLastHomePackageName);
        mIsLastTaskHome = isHomeActivity || isHomePackage;
        if (isHomeActivity && !isHomePackage) {
            mLastHomePackageName = currentTask.topActivityInfo.packageName;
        }
    }

    private void handleTaskStackChanged(ActivityManager.RunningTaskInfo frontTask) {
        if (shouldPlayHomeSoundForCurrentTransition(frontTask)) {
            mAudioManager.playSoundEffect(AudioManager.FX_HOME);
        }
        updateLastTaskInfo(frontTask);
    }
}
