/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at * *      http://www.apache.org/licenses/LICENSE-2.0 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.stack;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SwipeHelper;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/**
 * Tests for {@link NotificationSwipeHelper}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class NotificationSwipeHelperTest extends SysuiTestCase {

    private NotificationSwipeHelper mSwipeHelper;
    private NotificationSwipeHelper.NotificationCallback mCallback;
    private NotificationMenuRowPlugin.OnMenuEventListener mListener;
    private View mView;
    private MotionEvent mEvent;
    private NotificationMenuRowPlugin mMenuRow;
    private Handler mHandler;
    private ExpandableNotificationRow mNotificationRow;
    private Runnable mFalsingCheck;

    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setUp() throws Exception {
        mCallback = mock(NotificationSwipeHelper.NotificationCallback.class);
        mListener = mock(NotificationMenuRowPlugin.OnMenuEventListener.class);
        mSwipeHelper = spy(new NotificationSwipeHelper(
                SwipeHelper.X, mCallback, mContext, mListener, new FalsingManagerFake()));
        mView = mock(View.class);
        mEvent = mock(MotionEvent.class);
        mMenuRow = mock(NotificationMenuRowPlugin.class);
        mNotificationRow = mock(ExpandableNotificationRow.class);
        mHandler = mock(Handler.class);
        mFalsingCheck = mock(Runnable.class);
    }

    @Test
    public void testSetExposedMenuView() {
        assertEquals("intialized with null exposed menu view", null,
                mSwipeHelper.getExposedMenuView());
        mSwipeHelper.setExposedMenuView(mView);
        assertEquals("swipe helper has correct exposedMenuView after setExposedMenuView to a view",
                mView, mSwipeHelper.getExposedMenuView());
        mSwipeHelper.setExposedMenuView(null);
        assertEquals("swipe helper has null exposedMenuView after setExposedMenuView to null",
                null, mSwipeHelper.getExposedMenuView());
    }

    @Test
    public void testClearExposedMenuView() {
        doNothing().when(mSwipeHelper).setExposedMenuView(mView);
        mSwipeHelper.clearExposedMenuView();
        verify(mSwipeHelper, times(1)).setExposedMenuView(null);
    }

    @Test
    public void testGetTranslatingParentView() {
        assertEquals("intialized with null translating parent view", null,
                mSwipeHelper.getTranslatingParentView());
        mSwipeHelper.setTranslatingParentView(mView);
        assertEquals("has translating parent view after setTranslatingParentView with a view",
                mView, mSwipeHelper.getTranslatingParentView());
    }

    @Test
    public void testClearTranslatingParentView() {
        doNothing().when(mSwipeHelper).setTranslatingParentView(null);
        mSwipeHelper.clearTranslatingParentView();
        verify(mSwipeHelper, times(1)).setTranslatingParentView(null);
    }

    @Test
    public void testSetCurrentMenuRow() {
        assertEquals("currentMenuRow initializes to null", null,
                mSwipeHelper.getCurrentMenuRow());
        mSwipeHelper.setCurrentMenuRow(mMenuRow);
        assertEquals("currentMenuRow set correctly after setCurrentMenuRow", mMenuRow,
                mSwipeHelper.getCurrentMenuRow());
        mSwipeHelper.setCurrentMenuRow(null);
        assertEquals("currentMenuRow set to null after setCurrentMenuRow to null",
                null, mSwipeHelper.getCurrentMenuRow());
    }

    @Test
    public void testClearCurrentMenuRow() {
        doNothing().when(mSwipeHelper).setCurrentMenuRow(null);
        mSwipeHelper.clearCurrentMenuRow();
        verify(mSwipeHelper, times(1)).setCurrentMenuRow(null);
    }

    @Test
    public void testOnDownUpdate_ExpandableNotificationRow() {
        when(mSwipeHelper.getHandler()).thenReturn(mHandler);
        when(mSwipeHelper.getFalsingCheck()).thenReturn(mFalsingCheck);
        doNothing().when(mSwipeHelper).resetExposedMenuView(true, false);
        doNothing().when(mSwipeHelper).clearCurrentMenuRow();
        doNothing().when(mSwipeHelper).initializeRow(any());

        mSwipeHelper.onDownUpdate(mNotificationRow, mEvent);

        verify(mSwipeHelper, times(1)).clearCurrentMenuRow();
        verify(mHandler, times(1)).removeCallbacks(mFalsingCheck);
        verify(mSwipeHelper, times(1)).resetExposedMenuView(true, false);
        verify(mSwipeHelper, times(1)).initializeRow(mNotificationRow);
    }

    @Test
    public void testOnDownUpdate_notExpandableNotificationRow() {
        when(mSwipeHelper.getHandler()).thenReturn(mHandler);
        when(mSwipeHelper.getFalsingCheck()).thenReturn(mFalsingCheck);
        doNothing().when(mSwipeHelper).resetExposedMenuView(true, false);
        doNothing().when(mSwipeHelper).clearCurrentMenuRow();
        doNothing().when(mSwipeHelper).initializeRow(any());

        mSwipeHelper.onDownUpdate(mView, mEvent);

        verify(mSwipeHelper, times(1)).clearCurrentMenuRow();
        verify(mHandler, times(1)).removeCallbacks(mFalsingCheck);
        verify(mSwipeHelper, times(1)).resetExposedMenuView(true, false);
        verify(mSwipeHelper, times(0)).initializeRow(any());
    }

    @Test
    public void testOnMoveUpdate_menuRow() {
        when(mSwipeHelper.getCurrentMenuRow()).thenReturn(mMenuRow);
        when(mSwipeHelper.getHandler()).thenReturn(mHandler);
        when(mSwipeHelper.getFalsingCheck()).thenReturn(mFalsingCheck);

        mSwipeHelper.onMoveUpdate(mView, mEvent, 0, 10);

        verify(mHandler, times(1)).removeCallbacks(mFalsingCheck);
        verify(mMenuRow, times(1)).onTouchMove(10);
    }

    @Test
    public void testOnMoveUpdate_noMenuRow() {
        when(mSwipeHelper.getHandler()).thenReturn(mHandler);
        when(mSwipeHelper.getFalsingCheck()).thenReturn(mFalsingCheck);

        mSwipeHelper.onMoveUpdate(mView, mEvent, 0, 10);

        verify(mHandler, times(1)).removeCallbacks(mFalsingCheck);
    }

    @Test
    public void testHandleUpEvent_noMenuRow() {
        assertFalse("Menu row does not exist",
                mSwipeHelper.handleUpEvent(mEvent, mView, 0, 0));
    }

    @Test
    public void testHandleUpEvent_menuRow() {
        when(mSwipeHelper.getCurrentMenuRow()).thenReturn(mMenuRow);
        doNothing().when(mSwipeHelper).handleMenuRowSwipe(mEvent, mView, 0, mMenuRow);

        assertTrue("Menu row exists",
                mSwipeHelper.handleUpEvent(mEvent, mView, 0, 0));
        verify(mMenuRow, times(1)).onTouchEnd();
        verify(mSwipeHelper, times(1)).handleMenuRowSwipe(mEvent, mView, 0, mMenuRow);
    }

    @Test
    public void testDismissChild_notExpanded() {
        when(mCallback.shouldDismissQuickly()).thenReturn(false);
        doNothing().when(mSwipeHelper).superDismissChild(mView, 0, false);
        doNothing().when(mSwipeHelper).handleMenuCoveredOrDismissed();

        mSwipeHelper.dismissChild(mView, 0, false);

        verify(mSwipeHelper, times(1)).superDismissChild(mView, 0, false);
        verify(mCallback, times(0)).handleChildViewDismissed(mView);
        verify(mCallback, times(1)).onDismiss();
        verify(mSwipeHelper, times(1)).handleMenuCoveredOrDismissed();
    }

    @Test
    public void testSnapchild_targetIsZero() {
        doNothing().when(mSwipeHelper).superSnapChild(mView, 0, 0);
        mSwipeHelper.snapChild(mView, 0, 0);

        verify(mCallback, times(1)).onDragCancelled(mView);
        verify(mSwipeHelper, times(1)).superSnapChild(mView, 0, 0);
        verify(mSwipeHelper, times(1)).handleMenuCoveredOrDismissed();
    }


    @Test
    public void testSnapchild_targetNotZero() {
        doNothing().when(mSwipeHelper).superSnapChild(mView, 10, 0);
        mSwipeHelper.snapChild(mView, 10, 0);

        verify(mCallback, times(1)).onDragCancelled(mView);
        verify(mSwipeHelper, times(1)).superSnapChild(mView, 10, 0);
        verify(mSwipeHelper, times(0)).handleMenuCoveredOrDismissed();
    }

    @Test
    public void testSnooze() {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        SnoozeOption snoozeOption = mock(SnoozeOption.class);
        mSwipeHelper.snooze(sbn, snoozeOption);
        verify(mCallback, times(1)).onSnooze(sbn, snoozeOption);
    }

    @Test
    public void testGetViewTranslationAnimator_notExpandableNotificationRow() {
        Animator animator = mock(Animator.class);
        AnimatorUpdateListener listener = mock(AnimatorUpdateListener.class);
        doReturn(animator).when(mSwipeHelper).superGetViewTranslationAnimator(mView, 0, listener);

        assertEquals("returns the correct animator from super", animator,
                mSwipeHelper.getViewTranslationAnimator(mView, 0, listener));

        verify(mSwipeHelper, times(1)).superGetViewTranslationAnimator(mView, 0, listener);
    }

    @Test
    public void testGetViewTranslationAnimator_expandableNotificationRow() {
        Animator animator = mock(Animator.class);
        AnimatorUpdateListener listener = mock(AnimatorUpdateListener.class);
        doReturn(animator).when(mNotificationRow).getTranslateViewAnimator(0, listener);

        assertEquals("returns the correct animator from super when view is an ENR", animator,
                mSwipeHelper.getViewTranslationAnimator(mNotificationRow, 0, listener));

        verify(mNotificationRow, times(1)).getTranslateViewAnimator(0, listener);
    }

    @Test
    public void testSetTranslation() {
        mSwipeHelper.setTranslation(mNotificationRow, 0);
        verify(mNotificationRow, times(1)).setTranslation(0);
    }

    @Test
    public void testGetTranslation() {
        doReturn(30f).when(mNotificationRow).getTranslation();

        assertEquals("Returns getTranslation for the ENR",
                mSwipeHelper.getTranslation(mNotificationRow), 30f);

        verify(mNotificationRow, times(1)).getTranslation();
    }

    @Test
    public void testDismiss() {
        doNothing().when(mSwipeHelper).dismissChild(mView, 0, true);
        doReturn(false).when(mSwipeHelper).swipedFastEnough();

        mSwipeHelper.dismiss(mView, 0);

        verify(mSwipeHelper, times(1)).swipedFastEnough();
        verify(mSwipeHelper, times(1)).dismissChild(mView, 0, true);
    }

    @Test
    public void testSnapOpen() {
        doNothing().when(mSwipeHelper).snapChild(mView, 30, 0);

        mSwipeHelper.snapOpen(mView, 30, 0);

        verify(mSwipeHelper, times(1)).snapChild(mView, 30, 0);
    }

    @Test
    public void testSnapClosed() {
        doNothing().when(mSwipeHelper).snapChild(mView, 0, 0);

        mSwipeHelper.snapClosed(mView, 0);

        verify(mSwipeHelper, times(1)).snapChild(mView, 0, 0);
    }

    @Test
    public void testGetMinDismissVelocity() {
        doReturn(30f).when(mSwipeHelper).getEscapeVelocity();

        assertEquals("Returns getEscapeVelocity", 30f, mSwipeHelper.getMinDismissVelocity());
    }

    @Test
    public void onMenuShown_noAntiFalsing() {
        doNothing().when(mSwipeHelper).setExposedMenuView(mView);
        doReturn(mView).when(mSwipeHelper).getTranslatingParentView();
        doReturn(mHandler).when(mSwipeHelper).getHandler();
        doReturn(false).when(mCallback).isAntiFalsingNeeded();
        doReturn(mFalsingCheck).when(mSwipeHelper).getFalsingCheck();

        mSwipeHelper.onMenuShown(mView);

        verify(mSwipeHelper, times(1)).setExposedMenuView(mView);
        verify(mCallback, times(1)).onDragCancelled(mView);
        verify(mCallback, times(1)).isAntiFalsingNeeded();

        verify(mHandler, times(0)).removeCallbacks(mFalsingCheck);
        verify(mHandler, times(0)).postDelayed(mFalsingCheck, mSwipeHelper.COVER_MENU_DELAY);
    }

    @Test
    public void onMenuShown_antiFalsing() {
        doNothing().when(mSwipeHelper).setExposedMenuView(mView);
        doReturn(mView).when(mSwipeHelper).getTranslatingParentView();
        doReturn(mHandler).when(mSwipeHelper).getHandler();
        doReturn(true).when(mCallback).isAntiFalsingNeeded();
        doReturn(mFalsingCheck).when(mSwipeHelper).getFalsingCheck();

        mSwipeHelper.onMenuShown(mView);

        verify(mSwipeHelper, times(1)).setExposedMenuView(mView);
        verify(mCallback, times(1)).onDragCancelled(mView);
        verify(mCallback, times(1)).isAntiFalsingNeeded();

        verify(mHandler, times(1)).removeCallbacks(mFalsingCheck);
        verify(mHandler, times(1)).postDelayed(mFalsingCheck, mSwipeHelper.COVER_MENU_DELAY);
    }

    @Test
    public void testResetExposedMenuView_noReset() {
        doReturn(false).when(mSwipeHelper).shouldResetMenu(false);
        doNothing().when(mSwipeHelper).clearExposedMenuView();

        mSwipeHelper.resetExposedMenuView(false, false);

        verify(mSwipeHelper, times(1)).shouldResetMenu(false);

        // should not clear exposed menu row
        verify(mSwipeHelper, times(0)).clearExposedMenuView();
    }

    @Test
    public void testResetExposedMenuView_animate() {
        Animator animator = mock(Animator.class);

        doReturn(true).when(mSwipeHelper).shouldResetMenu(false);
        doReturn(mNotificationRow).when(mSwipeHelper).getExposedMenuView();
        doReturn(false).when(mNotificationRow).isRemoved();
        doReturn(animator).when(mSwipeHelper).getViewTranslationAnimator(mNotificationRow, 0, null);
        doNothing().when(mSwipeHelper).clearExposedMenuView();

        mSwipeHelper.resetExposedMenuView(true, false);

        verify(mSwipeHelper, times(1)).shouldResetMenu(false);

        // should retrieve and start animator
        verify(mSwipeHelper, times(1)).getViewTranslationAnimator(mNotificationRow, 0, null);
        verify(animator, times(1)).start();

        // should not reset translation on row directly
        verify(mNotificationRow, times(0)).resetTranslation();

        // should clear exposed menu row
        verify(mSwipeHelper, times(1)).clearExposedMenuView();
    }


    @Test
    public void testResetExposedMenuView_noAnimate() {
        Animator animator = mock(Animator.class);

        doReturn(true).when(mSwipeHelper).shouldResetMenu(false);
        doReturn(mNotificationRow).when(mSwipeHelper).getExposedMenuView();
        doReturn(false).when(mNotificationRow).isRemoved();
        doReturn(animator).when(mSwipeHelper).getViewTranslationAnimator(mNotificationRow, 0, null);
        doNothing().when(mSwipeHelper).clearExposedMenuView();

        mSwipeHelper.resetExposedMenuView(false, false);

        verify(mSwipeHelper, times(1)).shouldResetMenu(false);

        // should not retrieve and start animator
        verify(mSwipeHelper, times(0)).getViewTranslationAnimator(mNotificationRow, 0, null);
        verify(animator, times(0)).start();

        // should reset translation on row directly
        verify(mNotificationRow, times(1)).resetTranslation();

        // should clear exposed menu row
        verify(mSwipeHelper, times(1)).clearExposedMenuView();
    }

    @Test
    public void testIsTouchInView() {
        assertEquals("returns false when view is null", false,
                NotificationSwipeHelper.isTouchInView(mEvent, null));

        doReturn(5f).when(mEvent).getX();
        doReturn(10f).when(mEvent).getY();

        doReturn(20).when(mView).getWidth();
        doReturn(20).when(mView).getHeight();

        Answer answer = (Answer) invocation -> {
            int[] arr = invocation.getArgument(0);
            arr[0] = 0;
            arr[1] = 0;
            return null;
        };
        doAnswer(answer).when(mView).getLocationOnScreen(any());

        assertTrue("Touch is within the view",
                mSwipeHelper.isTouchInView(mEvent, mView));

        doReturn(50f).when(mEvent).getX();

        assertFalse("Touch is not within the view",
                mSwipeHelper.isTouchInView(mEvent, mView));
    }

    @Test
    public void testIsTouchInView_expandable() {
        assertEquals("returns false when view is null", false,
                NotificationSwipeHelper.isTouchInView(mEvent, null));

        doReturn(5f).when(mEvent).getX();
        doReturn(10f).when(mEvent).getY();

        doReturn(20).when(mNotificationRow).getWidth();
        doReturn(20).when(mNotificationRow).getActualHeight();

        Answer answer = (Answer) invocation -> {
            int[] arr = invocation.getArgument(0);
            arr[0] = 0;
            arr[1] = 0;
            return null;
        };
        doAnswer(answer).when(mNotificationRow).getLocationOnScreen(any());

        assertTrue("Touch is within the view",
                mSwipeHelper.isTouchInView(mEvent, mNotificationRow));

        doReturn(50f).when(mEvent).getX();

        assertFalse("Touch is not within the view",
                mSwipeHelper.isTouchInView(mEvent, mNotificationRow));
    }
}
