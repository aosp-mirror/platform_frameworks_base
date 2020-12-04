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

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.media.AudioManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
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

    private @Mock Context mContext;
    private @Mock AudioManager mAudioManager;
    private @Mock TaskStackChangeListeners mTaskStackChangeListeners;
    private @Mock ActivityManager.RunningTaskInfo mStandardActivityTaskInfo;
    private @Mock ActivityManager.RunningTaskInfo mHomeActivityTaskInfo;

    private HomeSoundEffectController mController;
    private TaskStackChangeListener mTaskStackChangeListener;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doReturn(WindowConfiguration.ACTIVITY_TYPE_STANDARD).when(
                mStandardActivityTaskInfo).getActivityType();
        doReturn(WindowConfiguration.ACTIVITY_TYPE_HOME).when(
                mHomeActivityTaskInfo).getActivityType();

        mController = new HomeSoundEffectController(mContext, mAudioManager,
                mTaskStackChangeListeners);
    }

    @Test
    public void testHomeSoundEffectNotPlayedWhenHomeFirstMovesToFront() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskMovedToFront(mHomeActivityTaskInfo);

        // Then no home sound effect should be played.
        verify(mAudioManager, never()).playSoundEffect(AudioManager.FX_HOME);
    }

    @Test
    public void testHomeSoundEffectPlayedWhenEnabled() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        // And first a task different from the home task moves to front,
        mTaskStackChangeListener.onTaskMovedToFront(mStandardActivityTaskInfo);

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskMovedToFront(mHomeActivityTaskInfo);

        // Then the home sound effect should be played.
        verify(mAudioManager).playSoundEffect(AudioManager.FX_HOME);
    }

    @Test
    public void testHomeSoundEffectNotPlayedTwiceInRow() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        // And first a task different from the home task moves to front,
        mTaskStackChangeListener.onTaskMovedToFront(mStandardActivityTaskInfo);

        // And the home task moves to the front,
        mTaskStackChangeListener.onTaskMovedToFront(mHomeActivityTaskInfo);

        // Then the home sound effect should be played.
        verify(mAudioManager).playSoundEffect(AudioManager.FX_HOME);

        // If the home task moves to front a second time in a row,
        mTaskStackChangeListener.onTaskMovedToFront(mHomeActivityTaskInfo);

        // Then no home sound effect should be played.
        verify(mAudioManager, times(1)).playSoundEffect(AudioManager.FX_HOME);
    }

    @Test
    public void testHomeSoundEffectNotPlayedWhenNonHomeTaskMovesToFront() {
        // When HomeSoundEffectController is started and the home sound effect is enabled,
        startController(true /* isHomeSoundEffectEnabled */);

        // And a standard, non-home task, moves to the front,
        mTaskStackChangeListener.onTaskMovedToFront(mStandardActivityTaskInfo);

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
}
