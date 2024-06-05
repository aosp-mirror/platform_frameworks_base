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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link DisplayAreaGroup} container.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayAreaGroupTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayAreaGroupTest extends WindowTestsBase {

    private DisplayAreaGroup mDisplayAreaGroup;
    private TaskDisplayArea mTaskDisplayArea;

    @Before
    public void setUp() {
        mDisplayAreaGroup = new DisplayAreaGroup(
                mWm, "DisplayAreaGroup", FEATURE_VENDOR_FIRST);
        final TaskDisplayArea defaultTda = mDisplayContent.getDefaultTaskDisplayArea();
        final WindowContainer parentDA = defaultTda.getParent();
        parentDA.addChild(mDisplayAreaGroup, parentDA.mChildren.indexOf(defaultTda) + 1);
        mTaskDisplayArea = new TaskDisplayArea(
                mDisplayContent, mWm, "TDA1", FEATURE_VENDOR_FIRST + 1);
        mDisplayAreaGroup.addChild(mTaskDisplayArea, POSITION_TOP);
        mDisplayContent.onLastFocusedTaskDisplayAreaChanged(mTaskDisplayArea);
    }

    @Test
    public void testIsOrientationDifferentFromDisplay() {
        // Display is portrait, DisplayAreaGroup inherits that
        mDisplayContent.setBounds(0, 0, 600, 900);

        assertThat(mDisplayAreaGroup.isOrientationDifferentFromDisplay()).isFalse();

        // DisplayAreaGroup is landscape, different Display
        mDisplayAreaGroup.setBounds(0, 0, 600, 450);

        assertThat(mDisplayAreaGroup.isOrientationDifferentFromDisplay()).isTrue();

        // DisplayAreaGroup is portrait, same as Display
        mDisplayAreaGroup.setBounds(0, 0, 300, 900);

        assertThat(mDisplayAreaGroup.isOrientationDifferentFromDisplay()).isFalse();
    }

    @Test
    public void testGetRequestedOrientationForDisplay() {
        final Task task = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(mTaskDisplayArea).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        doReturn(true).when(mDisplayContent).onDescendantOrientationChanged(any());
        activity.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);

        // Display is portrait, DisplayAreaGroup inherits that
        mDisplayContent.setBounds(0, 0, 600, 900);

        assertThat(mDisplayAreaGroup.getOrientation()).isEqualTo(SCREEN_ORIENTATION_PORTRAIT);
        assertThat(activity.getRequestedConfigurationOrientation(true /* forDisplay */))
                .isEqualTo(ORIENTATION_PORTRAIT);

        // DisplayAreaGroup is landscape, different from Display
        mDisplayAreaGroup.setBounds(0, 0, 600, 450);

        assertThat(mDisplayAreaGroup.getOrientation()).isEqualTo(SCREEN_ORIENTATION_LANDSCAPE);
        assertThat(activity.getRequestedConfigurationOrientation(true /* forDisplay */))
                .isEqualTo(ORIENTATION_LANDSCAPE);

        // DisplayAreaGroup is portrait, same as Display
        mDisplayAreaGroup.setBounds(0, 0, 300, 900);

        assertThat(mDisplayAreaGroup.getOrientation()).isEqualTo(SCREEN_ORIENTATION_PORTRAIT);
        assertThat(activity.getRequestedConfigurationOrientation(true /* forDisplay */))
                .isEqualTo(ORIENTATION_PORTRAIT);
    }

    @Test
    public void testResolveOverrideConfiguration_reverseOrientationWhenDifferentFromParentRoot() {
        // Rotate the display to portrait.
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        displayRotation.setRotation(displayRotation.getPortraitRotation());
        mDisplayContent.sendNewConfiguration();

        // DAG fills Display
        assertThat(mDisplayAreaGroup.getConfiguration().orientation)
                .isEqualTo(ORIENTATION_PORTRAIT);

        // DisplayAreaGroup is landscape, different from Display
        mDisplayAreaGroup.setBounds(0, 0, 600, 450);

        assertThat(mDisplayAreaGroup.getConfiguration().orientation)
                .isEqualTo(ORIENTATION_LANDSCAPE);

        // DisplayAreaGroup is portrait, same as Display
        mDisplayAreaGroup.setBounds(0, 0, 300, 450);

        assertThat(mDisplayAreaGroup.getConfiguration().orientation)
                .isEqualTo(ORIENTATION_PORTRAIT);
    }
}
