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

package com.android.systemui.statusbar.notification.row;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;
import android.testing.TestableResources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.AnimatorTestRule;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;
import com.android.systemui.res.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@UiThreadTest
public class NotificationSnoozeTest extends SysuiTestCase {
    private static final int RES_DEFAULT = 2;
    private static final int[] RES_OPTIONS = {1, 2, 3};
    private final NotificationSwipeActionHelper mSnoozeListener = mock(
            NotificationSwipeActionHelper.class);
    private NotificationSnooze mUnderTest;

    @Rule
    public final AnimatorTestRule mAnimatorTestRule = new AnimatorTestRule(this);

    @Before
    public void setUp() throws Exception {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.NOTIFICATION_SNOOZE_OPTIONS, null);
        TestableResources resources = mContext.getOrCreateTestableResources();
        resources.addOverride(R.integer.config_notification_snooze_time_default, RES_DEFAULT);
        resources.addOverride(R.array.config_notification_snooze_times, RES_OPTIONS);

        mUnderTest = new NotificationSnooze(mContext, null);
        mUnderTest.setSnoozeListener(mSnoozeListener);
        mUnderTest.mExpandButton = mock(ImageView.class);
        mUnderTest.mSnoozeView = mock(View.class);
        mUnderTest.mSelectedOptionText = mock(TextView.class);
        mUnderTest.mDivider = mock(View.class);
        mUnderTest.mSnoozeOptionContainer = mock(ViewGroup.class);
        mUnderTest.mSnoozeOptions = mock(List.class);
    }

    @After
    public void tearDown() {
        // Make sure all animations are finished
        mAnimatorTestRule.advanceTimeBy(1000L);
    }

    @Test
    @EnableFlags(Flags.FLAG_NOTIFICATION_UNDO_GUTS_ON_CONFIG_CHANGED)
    public void closeControls_withoutSave_performsUndo() {
        ArrayList<SnoozeOption> options = mUnderTest.getDefaultSnoozeOptions();
        mUnderTest.mSelectedOption = options.getFirst();
        mUnderTest.showSnoozeOptions(true);

        assertThat(
                mUnderTest.handleCloseControls(/* save = */ false, /* force = */ false)).isFalse();

        assertThat(mUnderTest.mSelectedOption).isNull();
        assertThat(mUnderTest.isExpanded()).isFalse();
        verify(mSnoozeListener, times(0)).snooze(any(), any());
    }

    @Test
    public void closeControls_whenExpanded_collapsesOptions() {
        ArrayList<SnoozeOption> options = mUnderTest.getDefaultSnoozeOptions();
        mUnderTest.mSelectedOption = options.getFirst();
        mUnderTest.showSnoozeOptions(true);

        assertThat(mUnderTest.handleCloseControls(/* save = */ true, /* force = */ false)).isTrue();

        assertThat(mUnderTest.mSelectedOption).isNotNull();
        assertThat(mUnderTest.isExpanded()).isFalse();
    }

    @Test
    public void closeControls_whenCollapsed_commitsChanges() {
        ArrayList<SnoozeOption> options = mUnderTest.getDefaultSnoozeOptions();
        mUnderTest.mSelectedOption = options.getFirst();

        assertThat(mUnderTest.handleCloseControls(/* save = */ true, /* force = */ false)).isTrue();

        verify(mSnoozeListener).snooze(any(), any());
    }

    @Test
    public void closeControls_withForce_returnsFalse() {
        assertThat(mUnderTest.handleCloseControls(/* save = */ true, /* force = */ true)).isFalse();
    }

    @Test
    public void testGetOptionsWithNoConfig() {
        ArrayList<SnoozeOption> result = mUnderTest.getDefaultSnoozeOptions();
        assertEquals(3, result.size());
        assertEquals(1, result.get(0).getMinutesToSnoozeFor());  // respect order
        assertEquals(2, result.get(1).getMinutesToSnoozeFor());
        assertEquals(3, result.get(2).getMinutesToSnoozeFor());
        assertEquals(2, mUnderTest.getDefaultOption().getMinutesToSnoozeFor());
    }

    @Test
    public void testGetOptionsWithInvalidConfig() {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.NOTIFICATION_SNOOZE_OPTIONS,
                "this is garbage");
        ArrayList<SnoozeOption> result = mUnderTest.getDefaultSnoozeOptions();
        assertEquals(3, result.size());
        assertEquals(1, result.get(0).getMinutesToSnoozeFor());  // respect order
        assertEquals(2, result.get(1).getMinutesToSnoozeFor());
        assertEquals(3, result.get(2).getMinutesToSnoozeFor());
        assertEquals(2, mUnderTest.getDefaultOption().getMinutesToSnoozeFor());
    }

    @Test
    public void testGetOptionsWithValidDefault() {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.NOTIFICATION_SNOOZE_OPTIONS,
                "default=10,options_array=4:5:6:7");
        ArrayList<SnoozeOption> result = mUnderTest.getDefaultSnoozeOptions();
        assertNotNull(mUnderTest.getDefaultOption());  // pick one
    }

    @Test
    public void testGetOptionsWithValidConfig() {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.NOTIFICATION_SNOOZE_OPTIONS,
                "default=6,options_array=4:5:6:7");
        ArrayList<SnoozeOption> result = mUnderTest.getDefaultSnoozeOptions();
        assertEquals(4, result.size());
        assertEquals(4, result.get(0).getMinutesToSnoozeFor());  // respect order
        assertEquals(5, result.get(1).getMinutesToSnoozeFor());
        assertEquals(6, result.get(2).getMinutesToSnoozeFor());
        assertEquals(7, result.get(3).getMinutesToSnoozeFor());
        assertEquals(6, mUnderTest.getDefaultOption().getMinutesToSnoozeFor());
    }

    @Test
    public void testGetOptionsWithLongConfig() {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.NOTIFICATION_SNOOZE_OPTIONS,
                "default=6,options_array=4:5:6:7:8:9:10:11:12:13:14:15:16:17");
        ArrayList<SnoozeOption> result = mUnderTest.getDefaultSnoozeOptions();
        assertTrue(result.size() > 3);
        assertEquals(4, result.get(0).getMinutesToSnoozeFor());  // respect order
        assertEquals(5, result.get(1).getMinutesToSnoozeFor());
        assertEquals(6, result.get(2).getMinutesToSnoozeFor());
    }
}
