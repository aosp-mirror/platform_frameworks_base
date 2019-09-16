/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.notification.row;

import static android.provider.Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL;
import static android.provider.Settings.Secure.SHOW_NOTIFICATION_SNOOZE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.ViewUtils;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.statusbar.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NotificationMenuRowTest extends LeakCheckedTest {

    private ExpandableNotificationRow mRow;

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mRow = mock(ExpandableNotificationRow.class);
        NotificationEntry entry = new NotificationEntryBuilder().build();
        when(mRow.getEntry()).thenReturn(entry);
    }

    @After
    public void tearDown() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                NOTIFICATION_NEW_INTERRUPTION_MODEL, 0);
    }

    @Test
    public void testAttachDetach() {
        NotificationMenuRowPlugin row = new NotificationMenuRow(mContext);
        row.createMenu(mRow, null);
        ViewUtils.attachView(row.getMenuView());
        TestableLooper.get(this).processAllMessages();
        ViewUtils.detachView(row.getMenuView());
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testRecreateMenu() {
        NotificationMenuRowPlugin row = new NotificationMenuRow(mContext);
        row.createMenu(mRow, null);
        assertTrue(row.getMenuView() != null);
        row.createMenu(mRow, null);
        assertTrue(row.getMenuView() != null);
    }

    @Test
    public void testResetUncreatedMenu() {
        NotificationMenuRowPlugin row = new NotificationMenuRow(mContext);
        row.resetMenu();
    }


    @Test
    public void testNoAppOpsInSlowSwipe() {
        Settings.Secure.putInt(mContext.getContentResolver(), SHOW_NOTIFICATION_SNOOZE, 0);

        NotificationMenuRow row = new NotificationMenuRow(mContext);
        row.createMenu(mRow, null);

        ViewGroup container = (ViewGroup) row.getMenuView();
        // noti blocking
        assertEquals(1, container.getChildCount());
    }

    @Test
    public void testNoSnoozeInSlowSwipe() {
        Settings.Secure.putInt(mContext.getContentResolver(), SHOW_NOTIFICATION_SNOOZE, 0);

        NotificationMenuRow row = new NotificationMenuRow(mContext);
        row.createMenu(mRow, null);

        ViewGroup container = (ViewGroup) row.getMenuView();
        // just for noti blocking
        assertEquals(1, container.getChildCount());
    }

    @Test
    public void testSnoozeInSlowSwipe() {
        Settings.Secure.putInt(mContext.getContentResolver(), SHOW_NOTIFICATION_SNOOZE, 1);

        NotificationMenuRow row = new NotificationMenuRow(mContext);
        row.createMenu(mRow, null);

        ViewGroup container = (ViewGroup) row.getMenuView();
        // one for snooze and one for noti blocking
        assertEquals(2, container.getChildCount());
    }

    @Test
    public void testNoAppOpsInSlowSwipe_biDirectionalSwipe() {
        NotificationMenuRow row = new NotificationMenuRow(mContext, true);
        row.createMenu(mRow, null);

        ViewGroup container = (ViewGroup) row.getMenuView();
        // in the new interruption model there is only the blocking item
        assertEquals(1, container.getChildCount());
    }

    @Test
    public void testIsSnappedAndOnSameSide() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));

        when(row.isMenuVisible()).thenReturn(true);
        when(row.isMenuSnapped()).thenReturn(true);
        when(row.isMenuOnLeft()).thenReturn(true);
        when(row.isMenuSnappedOnLeft()).thenReturn(true);

        assertTrue("Showing on left and on left", row.isSnappedAndOnSameSide());


        when(row.isMenuOnLeft()).thenReturn(false);
        when(row.isMenuSnappedOnLeft()).thenReturn(false);
        assertTrue("Snapped to right and on right", row.isSnappedAndOnSameSide());

        when(row.isMenuOnLeft()).thenReturn(true);
        when(row.isMenuSnapped()).thenReturn(false);
        assertFalse("Snapped to right and on left", row.isSnappedAndOnSameSide());

        when(row.isMenuOnLeft()).thenReturn(true);
        when(row.isMenuSnappedOnLeft()).thenReturn(true);
        when(row.isMenuVisible()).thenReturn(false);
        assertFalse("Snapped to left and on left, but menu not visible",
                row.isSnappedAndOnSameSide());

        when(row.isMenuVisible()).thenReturn(true);
        when(row.isMenuSnapped()).thenReturn(false);
        assertFalse("Snapped to left and on left, but not actually snapped to",
                row.isSnappedAndOnSameSide());
    }

    @Test
    public void testGetMenuSnapTarget() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        when(row.isMenuOnLeft()).thenReturn(true);
        doReturn(30).when(row).getSpaceForMenu();

        assertEquals("When on left, snap target is space for menu",
                30, row.getMenuSnapTarget());

        when(row.isMenuOnLeft()).thenReturn(false);
        assertEquals("When on right, snap target is negative space for menu",
                -30, row.getMenuSnapTarget());
    }

    @Test
    public void testIsSwipedEnoughToShowMenu() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        when(row.isMenuVisible()).thenReturn(true);
        when(row.isMenuOnLeft()).thenReturn(true);
        doReturn(40f).when(row).getMinimumSwipeDistance();

        when(row.getTranslation()).thenReturn(30f);
        assertFalse("on left, translation is less than min", row.isSwipedEnoughToShowMenu());

        when(row.getTranslation()).thenReturn(50f);
        assertTrue("on left, translation is greater than min", row.isSwipedEnoughToShowMenu());

        when(row.isMenuOnLeft()).thenReturn(false);
        when(row.getTranslation()).thenReturn(-30f);
        assertFalse("on right, translation is greater than -min", row.isSwipedEnoughToShowMenu());

        when(row.getTranslation()).thenReturn(-50f);
        assertTrue("on right, translation is less than -min", row.isSwipedEnoughToShowMenu());

        when(row.isMenuVisible()).thenReturn(false);
        when(row.getTranslation()).thenReturn(30f);
        assertFalse("on left, translation greater than min, but not visible",
                row.isSwipedEnoughToShowMenu());
    }

    @Test
    public void testIsWithinSnapMenuThreshold() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        doReturn(30f).when(row).getSnapBackThreshold();
        doReturn(50f).when(row).getDismissThreshold();

        when(row.isMenuOnLeft()).thenReturn(true);
        when(row.getTranslation()).thenReturn(40f);
        assertTrue("When on left, translation is between min and max",
                row.isWithinSnapMenuThreshold());

        when(row.getTranslation()).thenReturn(20f);
        assertFalse("When on left, translation is less than min",
                row.isWithinSnapMenuThreshold());

        when(row.getTranslation()).thenReturn(60f);
        assertFalse("When on left, translation is greater than max",
                row.isWithinSnapMenuThreshold());

        when(row.isMenuOnLeft()).thenReturn(false);
        when(row.getTranslation()).thenReturn(-40f);
        assertTrue("When on right, translation is between -min and -max",
                row.isWithinSnapMenuThreshold());

        when(row.getTranslation()).thenReturn(-20f);
        assertFalse("When on right, translation is greater than -min",
                row.isWithinSnapMenuThreshold());

        when(row.getTranslation()).thenReturn(-60f);
        assertFalse("When on right, translation is less than -max",
                row.isWithinSnapMenuThreshold());
    }

    @Test
    public void testShouldSnapBack() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        doReturn(40f).when(row).getSnapBackThreshold();
        when(row.isMenuVisible()).thenReturn(false);
        when(row.isMenuOnLeft()).thenReturn(true);

        when(row.getTranslation()).thenReturn(50f);
        assertFalse("On left, translation greater than minimum target", row.shouldSnapBack());

        when(row.getTranslation()).thenReturn(30f);
        assertTrue("On left, translation less than minimum target", row.shouldSnapBack());

        when(row.isMenuOnLeft()).thenReturn(false);
        when(row.getTranslation()).thenReturn(-50f);
        assertFalse("On right, translation less than minimum target", row.shouldSnapBack());

        when(row.getTranslation()).thenReturn(-30f);
        assertTrue("On right, translation greater than minimum target", row.shouldSnapBack());
    }

    @Test
    public void testCanBeDismissed() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        ExpandableNotificationRow parent = mock(ExpandableNotificationRow.class);

        when(row.getParent()).thenReturn(parent);
        when(parent.canViewBeDismissed()).thenReturn(true);

        assertTrue("Row can be dismissed if parent can be dismissed", row.canBeDismissed());

        when(parent.canViewBeDismissed()).thenReturn(false);
        assertFalse("Row cannot be dismissed if parent cannot be dismissed",
                row.canBeDismissed());
    }

    @Test
    public void testIsTowardsMenu() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        when(row.isMenuVisible()).thenReturn(true);
        when(row.isMenuOnLeft()).thenReturn(true);

        assertTrue("menu on left, movement is negative", row.isTowardsMenu(-30f));
        assertFalse("menu on left, movement is positive", row.isTowardsMenu(30f));
        assertTrue("menu on left, movement is 0", row.isTowardsMenu(0f));

        when(row.isMenuOnLeft()).thenReturn(false);
        assertTrue("menu on right, movement is positive", row.isTowardsMenu(30f));
        assertFalse("menu on right, movement is negative", row.isTowardsMenu(-30f));
        assertTrue("menu on right, movement is 0", row.isTowardsMenu(0f));

        when(row.isMenuVisible()).thenReturn(false);
        assertFalse("menu on left, movement is negative, but menu not visible",
                row.isTowardsMenu(-30f));
    }

    @Test
    public void onSnapBack() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        NotificationMenuRowPlugin.OnMenuEventListener listener = mock(NotificationMenuRowPlugin
                .OnMenuEventListener.class);
        row.setMenuClickListener(listener);
        ExpandableNotificationRow parent = mock(ExpandableNotificationRow.class);
        when(row.getParent()).thenReturn(parent);
        doNothing().when(row).cancelDrag();

        row.onSnapOpen();

        assertTrue("before onSnapClosed, row is snapped to", row.isMenuSnapped());
        assertFalse("before onSnapClosed, row is not snapping", row.isSnapping());

        row.onSnapClosed();

        assertFalse("after onSnapClosed, row is not snapped to", row.isMenuSnapped());
        assertTrue("after onSnapClosed, row is snapping", row.isSnapping());
    }

    @Test
    public void testOnSnap() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        when(row.isMenuOnLeft()).thenReturn(true);
        NotificationMenuRowPlugin.OnMenuEventListener listener = mock(NotificationMenuRowPlugin
                .OnMenuEventListener.class);
        row.setMenuClickListener(listener);
        ExpandableNotificationRow parent = mock(ExpandableNotificationRow.class);
        when(row.getParent()).thenReturn(parent);

        assertFalse("before onSnapOpen, row is not snapped to", row.isMenuSnapped());
        assertFalse("before onSnapOpen, row is not snapped on left", row.isMenuSnappedOnLeft());

        row.onSnapOpen();

        assertTrue("after onSnapOpen, row is snapped to", row.isMenuSnapped());
        assertTrue("after onSnapOpen, row is snapped on left", row.isMenuSnapped());
        verify(listener, times(1)).onMenuShown(parent);
    }

    @Test
    public void testOnDismiss() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        doNothing().when(row).cancelDrag();
        row.onSnapOpen();

        assertFalse("before onDismiss, row is not dismissing", row.isDismissing());
        assertTrue("before onDismiss, row is showing", row.isMenuSnapped());

        row.onDismiss();

        verify(row, times(1)).cancelDrag();
        assertTrue("after onDismiss, row is dismissing", row.isDismissing());
        assertFalse("after onDismiss, row is not showing", row.isMenuSnapped());
    }

    @Test
    public void testOnDown() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        doNothing().when(row).beginDrag();

        row.onTouchStart();

        verify(row, times(1)).beginDrag();
    }

    @Test
    public void testOnUp() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        row.onTouchStart();

        assertTrue("before onTouchEnd, isUserTouching is true", row.isUserTouching());

        row.onTouchEnd();

        assertFalse("after onTouchEnd, isUserTouching is false", row.isUserTouching());
    }

    @Test
    public void testIsMenuVisible() {
        NotificationMenuRow row = Mockito.spy(new NotificationMenuRow((mContext)));
        row.setMenuAlpha(0);

        assertFalse("when alpha is 0, menu is not visible", row.isMenuVisible());

        row.setMenuAlpha(0.5f);
        assertTrue("when alpha is .5, menu is visible", row.isMenuVisible());
    }
}
