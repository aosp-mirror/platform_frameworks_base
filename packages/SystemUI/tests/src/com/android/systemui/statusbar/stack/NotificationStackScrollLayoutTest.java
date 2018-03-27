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

package com.android.systemui.statusbar.stack;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.FooterView;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBar;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationStackScrollLayoutTest extends SysuiTestCase {

    private NotificationStackScrollLayout mStackScroller;
    private StatusBar mBar;

    @Before
    @UiThreadTest
    public void setUp() throws Exception {
        mStackScroller = new NotificationStackScrollLayout(getContext());
        mBar = mock(StatusBar.class);
        mStackScroller.setStatusBar(mBar);
        mStackScroller.setScrimController(mock(ScrimController.class));
    }

    @Test
    public void testNotDimmedOnKeyguard() {
        when(mBar.getBarState()).thenReturn(StatusBarState.SHADE);
        mStackScroller.setDimmed(true /* dimmed */, false /* animate */);
        mStackScroller.setDimmed(true /* dimmed */, true /* animate */);
        Assert.assertFalse(mStackScroller.isDimmed());
    }

    @Test
    public void testAntiBurnInOffset() {
        final int burnInOffset = 30;
        mStackScroller.setAntiBurnInOffsetX(burnInOffset);
        mStackScroller.setDark(false /* dark */, false /* animated */, null /* touch */);
        Assert.assertEquals(0 /* expected */, mStackScroller.getTranslationX(), 0.01 /* delta */);
        mStackScroller.setDark(true /* dark */, false /* animated */, null /* touch */);
        Assert.assertEquals(burnInOffset /* expected */, mStackScroller.getTranslationX(),
                0.01 /* delta */);
    }

    @Test
    public void updateEmptyView_dndSuppressing() {
        EmptyShadeView view = mock(EmptyShadeView.class);
        mStackScroller.setEmptyShadeView(view);
        when(view.willBeGone()).thenReturn(true);
        when(mBar.areNotificationsHidden()).thenReturn(true);

        mStackScroller.updateEmptyShadeView(true);

        verify(view).setText(R.string.dnd_suppressing_shade_text);
    }

    @Test
    public void updateEmptyView_dndNotSuppressing() {
        EmptyShadeView view = mock(EmptyShadeView.class);
        mStackScroller.setEmptyShadeView(view);
        when(view.willBeGone()).thenReturn(true);
        when(mBar.areNotificationsHidden()).thenReturn(false);

        mStackScroller.updateEmptyShadeView(true);

        verify(view).setText(R.string.empty_shade_text);
    }

    @Test
    public void manageNotifications_visible() {
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        when(view.willBeGone()).thenReturn(true);
        when(view.isSecondaryVisible()).thenReturn(true);

        mStackScroller.updateFooterView(true, false);

        verify(view).setVisibility(View.VISIBLE);
        verify(view).performSecondaryVisibilityAnimation(false);
    }

    @Test
    public void clearAll_visible() {
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        when(view.willBeGone()).thenReturn(true);
        when(view.isSecondaryVisible()).thenReturn(false);

        mStackScroller.updateFooterView(true, true);

        verify(view).setVisibility(View.VISIBLE);
        verify(view).performSecondaryVisibilityAnimation(true);
    }
}
