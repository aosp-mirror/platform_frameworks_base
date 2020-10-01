/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.HeadsUpStatusBarView;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class HeadsUpAppearanceControllerTest extends SysuiTestCase {

    private final NotificationStackScrollLayout mStackScroller =
            mock(NotificationStackScrollLayout.class);
    private final NotificationPanelViewController mPanelView =
            mock(NotificationPanelViewController.class);
    private final DarkIconDispatcher mDarkIconDispatcher = mock(DarkIconDispatcher.class);
    private HeadsUpAppearanceController mHeadsUpAppearanceController;
    private ExpandableNotificationRow mFirst;
    private HeadsUpStatusBarView mHeadsUpStatusBarView;
    private HeadsUpManagerPhone mHeadsUpManager;
    private View mOperatorNameView;
    private StatusBarStateController mStatusbarStateController;
    private KeyguardBypassController mBypassController;
    private NotificationWakeUpCoordinator mWakeUpCoordinator;
    private KeyguardStateController mKeyguardStateController;
    private CommandQueue mCommandQueue;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        NotificationTestHelper testHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mFirst = testHelper.createRow();
        mDependency.injectTestDependency(DarkIconDispatcher.class, mDarkIconDispatcher);
        mHeadsUpStatusBarView = new HeadsUpStatusBarView(mContext, mock(View.class),
                mock(TextView.class));
        mHeadsUpManager = mock(HeadsUpManagerPhone.class);
        mOperatorNameView = new View(mContext);
        mStatusbarStateController = mock(StatusBarStateController.class);
        mBypassController = mock(KeyguardBypassController.class);
        mWakeUpCoordinator = mock(NotificationWakeUpCoordinator.class);
        mKeyguardStateController = mock(KeyguardStateController.class);
        mCommandQueue = mock(CommandQueue.class);
        mHeadsUpAppearanceController = new HeadsUpAppearanceController(
                mock(NotificationIconAreaController.class),
                mHeadsUpManager,
                mStatusbarStateController,
                mBypassController,
                mWakeUpCoordinator,
                mKeyguardStateController,
                mCommandQueue,
                mHeadsUpStatusBarView,
                mStackScroller,
                mPanelView,
                new View(mContext),
                mOperatorNameView,
                new View(mContext));
        mHeadsUpAppearanceController.setAppearFraction(0.0f, 0.0f);
    }

    @Test
    public void testShowinEntryUpdated() {
        mFirst.setPinned(true);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        when(mHeadsUpManager.getTopEntry()).thenReturn(mFirst.getEntry());
        mHeadsUpAppearanceController.onHeadsUpPinned(mFirst.getEntry());
        Assert.assertEquals(mFirst.getEntry(), mHeadsUpStatusBarView.getShowingEntry());

        mFirst.setPinned(false);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        mHeadsUpAppearanceController.onHeadsUpUnPinned(mFirst.getEntry());
        Assert.assertEquals(null, mHeadsUpStatusBarView.getShowingEntry());
    }

    @Test
    public void testShownUpdated() {
        mFirst.setPinned(true);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        when(mHeadsUpManager.getTopEntry()).thenReturn(mFirst.getEntry());
        mHeadsUpAppearanceController.onHeadsUpPinned(mFirst.getEntry());
        Assert.assertTrue(mHeadsUpAppearanceController.isShown());

        mFirst.setPinned(false);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        mHeadsUpAppearanceController.onHeadsUpUnPinned(mFirst.getEntry());
        Assert.assertFalse(mHeadsUpAppearanceController.isShown());
    }

    @Test
    public void testHeaderUpdated() {
        mFirst.setPinned(true);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        when(mHeadsUpManager.getTopEntry()).thenReturn(mFirst.getEntry());
        mHeadsUpAppearanceController.onHeadsUpPinned(mFirst.getEntry());
        Assert.assertEquals(mFirst.getHeaderVisibleAmount(), 0.0f, 0.0f);

        mFirst.setPinned(false);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        mHeadsUpAppearanceController.onHeadsUpUnPinned(mFirst.getEntry());
        Assert.assertEquals(mFirst.getHeaderVisibleAmount(), 1.0f, 0.0f);
    }

    @Test
    public void testOperatorNameViewUpdated() {
        mHeadsUpAppearanceController.setAnimationsEnabled(false);

        mFirst.setPinned(true);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(true);
        when(mHeadsUpManager.getTopEntry()).thenReturn(mFirst.getEntry());
        mHeadsUpAppearanceController.onHeadsUpPinned(mFirst.getEntry());
        Assert.assertEquals(View.INVISIBLE, mOperatorNameView.getVisibility());

        mFirst.setPinned(false);
        when(mHeadsUpManager.hasPinnedHeadsUp()).thenReturn(false);
        mHeadsUpAppearanceController.onHeadsUpUnPinned(mFirst.getEntry());
        Assert.assertEquals(View.VISIBLE, mOperatorNameView.getVisibility());
    }

    @Test
    public void testHeaderReadFromOldController() {
        mHeadsUpAppearanceController.setAppearFraction(1.0f, 1.0f);

        HeadsUpAppearanceController newController = new HeadsUpAppearanceController(
                mock(NotificationIconAreaController.class),
                mHeadsUpManager,
                mStatusbarStateController,
                mBypassController,
                mWakeUpCoordinator,
                mKeyguardStateController,
                mCommandQueue,
                mHeadsUpStatusBarView,
                mStackScroller,
                mPanelView,
                new View(mContext),
                new View(mContext),
                new View(mContext));
        newController.readFrom(mHeadsUpAppearanceController);

        Assert.assertEquals(mHeadsUpAppearanceController.mExpandedHeight,
                newController.mExpandedHeight, 0.0f);
        Assert.assertEquals(mHeadsUpAppearanceController.mAppearFraction,
                newController.mAppearFraction, 0.0f);
        Assert.assertEquals(mHeadsUpAppearanceController.mIsExpanded,
                newController.mIsExpanded);
    }

    @Test
    public void testDestroy() {
        reset(mHeadsUpManager);
        reset(mDarkIconDispatcher);
        reset(mPanelView);
        reset(mStackScroller);
        mHeadsUpAppearanceController.destroy();
        verify(mHeadsUpManager).removeListener(any());
        verify(mDarkIconDispatcher).removeDarkReceiver((DarkIconDispatcher.DarkReceiver) any());
        verify(mPanelView).removeVerticalTranslationListener(any());
        verify(mPanelView).removeTrackingHeadsUpListener(any());
        verify(mPanelView).setHeadsUpAppearanceController(any());
        verify(mStackScroller).removeOnExpandedHeightChangedListener(any());
        verify(mStackScroller).removeOnLayoutChangeListener(any());
    }
}
