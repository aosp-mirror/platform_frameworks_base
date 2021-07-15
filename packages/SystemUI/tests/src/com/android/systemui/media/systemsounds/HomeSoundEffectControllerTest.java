/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class HomeSoundEffectControllerTest extends SysuiTestCase {

    private static final String HOME_PACKAGE_NAME = "com.android.apps.home";
    private static final String NON_HOME_PACKAGE_NAME = "com.android.apps.not.home";
    private static final int HOME_TASK_ID = 0;
    private static final int NON_HOME_TASK_ID = 1;

    private @Mock AudioManager mAudioManager;
    private @Mock TaskStackChangeListeners mTaskStackChangeListeners;
    private @Mock ActivityManagerWrapper mActivityManagerWrapper;
    private @Mock PackageManager mPackageManager;

    private ActivityManager.RunningTaskInfo mTaskAStandardActivity;
    private ActivityManager.RunningTaskInfo mTaskAExceptionActivity;
    private ActivityManager.RunningTaskInfo mHomeTaskHomeActivity;
    private ActivityManager.RunningTaskInfo mHomeTaskStandardActivity;
    private ActivityManager.RunningTaskInfo mEmptyTask;
    private HomeSoundEffectController mController;
    private TaskStackChangeListener mTaskStackChangeListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTaskAStandardActivity = createRunningTaskInfo(NON_HOME_PACKAGE_NAME,
                WindowConfiguration.ACTIVITY_TYPE_STANDARD, NON_HOME_TASK_ID,
                true /* playHomeTransitionSound */);
        mTaskAExceptionActivity = createRunningTaskInfo(NON_HOME_PACKAGE_NAME,
                WindowConfiguration.ACTIVITY_TYPE_STANDARD, NON_HOME_TASK_ID,
                false /* playHomeTransitionSound */);
        mHomeTaskHomeActivity = createRunningTaskInfo(HOME_PACKAGE_NAME,
                WindowConfiguration.ACTIVITY_TYPE_HOME, HOME_TASK_ID,
                true /* playHomeTransitionSound */);
        mHomeTaskStandardActivity = createRunningTaskInfo(HOME_PACKAGE_NAME,
                WindowConfiguration.ACTIVITY_TYPE_STANDARD, HOME_TASK_ID,
                true /* playHomeTransitionSound */);
        mEmptyTask = new ActivityManager.RunningTaskInfo();
        mContext.setMockPackageManager(mPackageManager);
        when(mPackageManager.checkPermission(Manifest.permission.DISABLE_SYSTEM_SOUND_EFFECTS,
                NON_HOME_PACKAGE_NAME)).thenReturn(PackageManager.PERMISSION_GRANTED);
        mController = new HomeSoundEffectController(mContext, mAudioManager,
                mTaskStackChangeListeners, mActivityManagerWrapper, mPackageManager);
    }

    @Test
    public void testHomeSoundEffectNotPlayedWhenHomeFirstMovesToFront() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        // And the home task moves to the front,
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(mHomeTaskHomeActivity);
        mTaskStackChangeListener.onTaskStackChanged();

        // Then no home sound effect should be played.
        verify(mAudioManager, never()).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * Task A (playHomeTransitionSound = true) -> HOME
     * Expectation: Home sound is played
     */
    @Test
    public void testHomeSoundEffectPlayedWhenEnabled() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAStandardActivity,
                mHomeTaskHomeActivity);

        // And first a task different from the home task moves to front,
        mTaskStackChangeListener.onTaskStackChanged();

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then the home sound effect should be played.
        verify(mAudioManager).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * Task A (playHomeTransitionSound = true) -> HOME -> HOME
     * Expectation: Home sound is played once after HOME moves to front the first time
     */
    @Test
    public void testHomeSoundEffectNotPlayedTwiceInRow() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAStandardActivity,
                mHomeTaskHomeActivity,
                mHomeTaskHomeActivity);


        // And first a task different from the home task moves to front,
        mTaskStackChangeListener.onTaskStackChanged();

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then the home sound effect should be played.
        verify(mAudioManager).playSoundEffect(AudioManager.FX_HOME);

        // If the home task moves to front a second time in a row,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then no home sound effect should be played.
        verify(mAudioManager, times(1)).playSoundEffect(AudioManager.FX_HOME);
    }

    @Test
    public void testHomeSoundEffectNotPlayedWhenNonHomeTaskMovesToFront() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        // And a standard, non-home task, moves to the front,
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(mTaskAStandardActivity);
        mTaskStackChangeListener.onTaskStackChanged();

        // Then no home sound effect should be played.
        verify(mAudioManager, never()).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * Task A (playHomeTransitionSound = true) -> HOME -> HOME (activity type standard)
     * Expectation: Home sound is played once after HOME moves to front
     */
    @Test
    public void testHomeSoundEffectNotPlayedWhenOtherHomeActivityMovesToFront() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAStandardActivity,
                mHomeTaskHomeActivity,
                mHomeTaskStandardActivity);

        // And first a task different from the home task moves to front,
        mTaskStackChangeListener.onTaskStackChanged();

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then the home sound effect should be played.
        verify(mAudioManager).playSoundEffect(AudioManager.FX_HOME);

        // If the home task moves to front a second time in a row,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then no home sound effect should be played.
        verify(mAudioManager, times(1)).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * Task A (playHomeTransitionSound = true) -> HOME (activity type standard)
     * Expectation: Home sound is not played
     */
    @Test
    public void testHomeSoundEffectNotPlayedWhenOtherHomeActivityMovesToFrontOfOtherApp() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        // And first a task different from the home task moves to front,
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAStandardActivity,
                mHomeTaskStandardActivity);
        mTaskStackChangeListener.onTaskStackChanged();

        // And an activity from the home package but not the home root activity moves to front
        mTaskStackChangeListener.onTaskStackChanged();

        // Then no home sound effect should be played.
        verify(mAudioManager, never()).playSoundEffect(AudioManager.FX_HOME);
    }

    @Test
    public void testHomeSoundEffectDisabled() {
        // When HomeSoundEffectController is started and the home sound effect is disabled,
        startController(false /* isHomeSoundEffectEnabled */);

        // Then no TaskStackListener should be registered
        verify(mTaskStackChangeListeners, never()).registerTaskStackListener(
                any(TaskStackChangeListener.class));
    }

    /**
     * Task A (playHomeTransitionSound = false) -> HOME
     * Expectation: Home sound is not played
     */
    @Test
    public void testHomeSoundEffectNotPlayedWhenHomeActivityMovesToFrontAfterException() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        // And first a task different from the home task moves to front, which has
        // {@link ActivityInfo#PRIVATE_FLAG_HOME_TRANSITION_SOUND} set to <code>false</code>
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAExceptionActivity,
                mHomeTaskHomeActivity);
        mTaskStackChangeListener.onTaskStackChanged();

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then no home sound effect should be played because the last package is an exception.
        verify(mAudioManager, never()).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * HOME -> Task A (playHomeTransitionSound = true) -> Task A (playHomeTransitionSound = false)
     * -> HOME
     * Expectation: Home sound is not played
     */
    @Test
    public void testHomeSoundEffectNotPlayedWhenHomeActivityMovesToFrontAfterException2() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAStandardActivity,
                mTaskAExceptionActivity,
                mHomeTaskHomeActivity);

        // And first a task different from the home task moves to front
        mTaskStackChangeListener.onTaskStackChanged();

        // Then a different activity from the same task moves to front, which has
        // {@link ActivityInfo#PRIVATE_FLAG_HOME_TRANSITION_SOUND} set to <code>false</code>
        mTaskStackChangeListener.onTaskStackChanged();

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then no home sound effect should be played.
        verify(mAudioManager, never()).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * HOME -> Task A (playHomeTransitionSound = false) -> Task A (playHomeTransitionSound = true)
     * -> HOME
     * Expectation: Home sound is played
     */
    @Test
    public void testHomeSoundEffectPlayedWhenHomeActivityMovesToFrontAfterException() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAExceptionActivity,
                mTaskAStandardActivity,
                mHomeTaskHomeActivity);

        // And first a task different from the home task moves to front,
        // the topActivity of this task has {@link ActivityInfo#PRIVATE_FLAG_HOME_TRANSITION_SOUND}
        // set to <code>false</code>
        mTaskStackChangeListener.onTaskStackChanged();

        // Then a different activity from the same task moves to front, which has
        // {@link ActivityInfo#PRIVATE_FLAG_HOME_TRANSITION_SOUND} set to <code>true</code>
        mTaskStackChangeListener.onTaskStackChanged();

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then the home sound effect should be played.
        verify(mAudioManager).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * HOME -> Task A (playHomeTransitionSound = false) -> Task A (empty task, no top activity)
     * -> HOME
     * Expectation: Home sound is not played
     */
    @Test
    public void testHomeSoundEffectNotPlayedWhenEmptyTaskMovesToFrontAfterException() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAExceptionActivity,
                mEmptyTask,
                mHomeTaskHomeActivity);

        // And first a task different from the home task moves to front, whose topActivity has
        // {@link ActivityInfo#PRIVATE_FLAG_HOME_TRANSITION_SOUND} set to <code>false</code>
        mTaskStackChangeListener.onTaskStackChanged();

        // Then a task with no topActivity moves to front,
        mTaskStackChangeListener.onTaskStackChanged();

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then no home sound effect should be played.
        verify(mAudioManager, never()).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * HOME -> Task A (playHomeTransitionSound = true) -> Task A (empty task, no top activity)
     * -> HOME
     * Expectation: Home sound is played
     */
    @Test
    public void testHomeSoundEffectPlayedWhenEmptyTaskMovesToFrontAfterStandardActivity() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAStandardActivity,
                mEmptyTask,
                mHomeTaskHomeActivity);

        // And first a task different from the home task moves to front, whose topActivity has
        // {@link ActivityInfo#PRIVATE_FLAG_HOME_TRANSITION_SOUND} set to <code>false</code>
        mTaskStackChangeListener.onTaskStackChanged();

        // Then a task with no topActivity moves to front,
        mTaskStackChangeListener.onTaskStackChanged();

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then the home sound effect should be played.
        verify(mAudioManager).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * HOME -> Task A (playHomeTransitionSound = false, no permission) -> HOME
     * Expectation: Home sound is played
     */
    @Test
    public void testHomeSoundEffectPlayedWhenFlagSetButPermissionNotGranted() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);
        when(mActivityManagerWrapper.getRunningTask()).thenReturn(
                mTaskAStandardActivity,
                mHomeTaskHomeActivity);
        when(mPackageManager.checkPermission(Manifest.permission.DISABLE_SYSTEM_SOUND_EFFECTS,
                NON_HOME_PACKAGE_NAME)).thenReturn(PackageManager.PERMISSION_DENIED);

        // And first a task different from the home task moves to front, whose topActivity has
        // {@link ActivityInfo#PRIVATE_FLAG_HOME_TRANSITION_SOUND} set to <code>true</code>,
        // but the app doesn't have the right permission granted
        mTaskStackChangeListener.onTaskStackChanged();


        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskStackChanged();

        // Then the home sound effect should be played.
        verify(mAudioManager).playSoundEffect(AudioManager.FX_HOME);
    }

    /**
     * Sets {@link AudioManager#isHomeSoundEffectEnabled()} and starts HomeSoundEffectController.
     * If the home sound effect is enabled, the registered TaskStackChangeListener is extracted.
     */
    private void startController(boolean isHomeSoundEffectEnabled) {
        // Configure home sound effect to be enabled
        doReturn(isHomeSoundEffectEnabled).when(mAudioManager).isHomeSoundEffectEnabled();

        mController.start();

        if (isHomeSoundEffectEnabled) {
            // Construct controller. Save the TaskStackListener for injecting events.
            final ArgumentCaptor<TaskStackChangeListener> listenerCaptor =
                    ArgumentCaptor.forClass(TaskStackChangeListener.class);
            verify(mTaskStackChangeListeners).registerTaskStackListener(listenerCaptor.capture());
            mTaskStackChangeListener = listenerCaptor.getValue();
        }
    }

    private ActivityManager.RunningTaskInfo createRunningTaskInfo(String packageName,
            int activityType, int taskId, boolean playHomeTransitionSound) {
        ActivityManager.RunningTaskInfo res = new ActivityManager.RunningTaskInfo();
        res.topActivityInfo = new ActivityInfo();
        res.topActivityInfo.packageName = packageName;
        res.topActivityType = activityType;
        res.taskId = taskId;
        if (!playHomeTransitionSound) {
            // set the flag to 0
            res.topActivityInfo.privateFlags &=  ~ActivityInfo.PRIVATE_FLAG_HOME_TRANSITION_SOUND;
        } else {
            res.topActivityInfo.privateFlags |= ActivityInfo.PRIVATE_FLAG_HOME_TRANSITION_SOUND;
        }
        return res;
    }
}
