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

package com.android.systemui.statusbar.notification.row;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.notification.logging.NotificationPanelLogger;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class ExpandableNotificationRowDragControllerTest extends SysuiTestCase {

    private ExpandableNotificationRow mRow;
    private ExpandableNotificationRow mGroupRow;
    private ExpandableNotificationRowDragController mController;
    private NotificationTestHelper mNotificationTestHelper;

    private NotificationGutsManager mGutsManager = mock(NotificationGutsManager.class);
    private HeadsUpManager mHeadsUpManager = mock(HeadsUpManager.class);
    private NotificationMenuRow mMenuRow = mock(NotificationMenuRow.class);
    private NotificationMenuRowPlugin.MenuItem mMenuItem =
            mock(NotificationMenuRowPlugin.MenuItem.class);
    private ShadeController mShadeController = mock(ShadeController.class);
    private NotificationPanelLogger mNotificationPanelLogger = mock(NotificationPanelLogger.class);

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();

        mDependency.injectMockDependency(ShadeController.class);

        mNotificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mRow = spy(mNotificationTestHelper.createRow());
        Notification notification = mRow.getEntry().getSbn().getNotification();
        notification.contentIntent = mock(PendingIntent.class);
        doReturn(true).when(mRow).startDragAndDrop(any(), any(), any(), anyInt());
        mGroupRow = mNotificationTestHelper.createGroup(4);
        when(mMenuRow.getLongpressMenuItem(any(Context.class))).thenReturn(mMenuItem);

        mController = new ExpandableNotificationRowDragController(mContext, mHeadsUpManager,
                mShadeController, mNotificationPanelLogger);
    }

    @Test
    public void testDoStartDragHeadsUpNotif_startDragAndDrop() throws Exception {
        ExpandableNotificationRowDragController controller = createSpyController();
        mRow.setDragController(controller);
        mRow.setHeadsUp(true);
        mRow.setPinned(true);

        mRow.doLongClickCallback(0, 0);
        mRow.doDragCallback(0, 0);
        verify(controller).startDragAndDrop(mRow);
        verify(mHeadsUpManager, times(1)).releaseAllImmediately();
        verify(mNotificationPanelLogger, times(1)).logNotificationDrag(any());
    }

    @Test
    public void testDoStartDragNotif() throws Exception {
        ExpandableNotificationRowDragController controller = createSpyController();
        mRow.setDragController(controller);

        mRow.doDragCallback(0, 0);
        verify(controller).startDragAndDrop(mRow);
        verify(mShadeController).animateCollapsePanels(eq(0), eq(true),
                eq(false), anyFloat());
        verify(mNotificationPanelLogger, times(1)).logNotificationDrag(any());
    }

    @Test
    public void testDoStartDrag_noLaunchIntent() throws Exception {
        ExpandableNotificationRowDragController controller = createSpyController();
        mRow.setDragController(controller);

        // Clear the intents
        Notification notification = mRow.getEntry().getSbn().getNotification();
        notification.contentIntent = null;
        notification.fullScreenIntent = null;

        mRow.doDragCallback(0, 0);
        verify(controller).startDragAndDrop(mRow);

        // Verify that we never start the actual drag since there is no content
        verify(mRow, never()).startDragAndDrop(any(), any(), any(), anyInt());
        verify(mNotificationPanelLogger, never()).logNotificationDrag(any());
    }

    private ExpandableNotificationRowDragController createSpyController() {
        return spy(mController);
    }
}
