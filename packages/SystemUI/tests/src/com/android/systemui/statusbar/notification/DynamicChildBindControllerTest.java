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

package com.android.systemui.statusbar.notification;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DynamicChildBindControllerTest extends SysuiTestCase {

    private DynamicChildBindController mDynamicChildBindController;
    private Map<NotificationEntry, List<NotificationEntry>> mGroupNotifs = new ArrayMap<>();
    private static final int TEST_CHILD_BIND_CUTOFF = 5;

    @Mock private RowContentBindStage mBindStage;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();
        when(mBindStage.getStageParams(any())).thenReturn(new RowContentBindParams());
        mDynamicChildBindController =
                new DynamicChildBindController(mBindStage, TEST_CHILD_BIND_CUTOFF);
    }

    @Test
    public void testContentViewsOfChildrenBeyondCutoffAreFreed() {
        // GIVEN a group notification with one view beyond the cutoff with content bound
        NotificationEntry summary = addGroup(TEST_CHILD_BIND_CUTOFF + 1);
        NotificationEntry lastChild = mGroupNotifs.get(summary).get(TEST_CHILD_BIND_CUTOFF);

        RowContentBindParams bindParams = mock(RowContentBindParams.class);
        when(mBindStage.getStageParams(lastChild)).thenReturn(bindParams);

        // WHEN the controller gets the list
        mDynamicChildBindController.updateChildContentViews(mGroupNotifs);

        // THEN we free content views
        verify(bindParams).markContentViewsFreeable(FLAG_CONTENT_VIEW_CONTRACTED);
        verify(bindParams).markContentViewsFreeable(FLAG_CONTENT_VIEW_EXPANDED);
        verify(mBindStage).requestRebind(eq(lastChild), any());
    }

    @Test
    public void testContentViewsBeforeCutoffAreBound() {
        // GIVEN a group notification with one view before the cutoff with content unbound
        NotificationEntry summary = addGroup(TEST_CHILD_BIND_CUTOFF);
        NotificationEntry lastChild = mGroupNotifs.get(summary).get(TEST_CHILD_BIND_CUTOFF - 1);

        lastChild.getRow().getPrivateLayout().setContractedChild(null);
        lastChild.getRow().getPrivateLayout().setExpandedChild(null);

        RowContentBindParams bindParams = mock(RowContentBindParams.class);
        when(mBindStage.getStageParams(lastChild)).thenReturn(bindParams);

        // WHEN the controller gets the list
        mDynamicChildBindController.updateChildContentViews(mGroupNotifs);

        // THEN we bind content views
        verify(bindParams).requireContentViews(FLAG_CONTENT_VIEW_CONTRACTED);
        verify(bindParams).requireContentViews(FLAG_CONTENT_VIEW_EXPANDED);
        verify(mBindStage).requestRebind(eq(lastChild), any());
    }

    private NotificationEntry addGroup(int size) {
        NotificationEntry summary = new NotificationEntryBuilder().build();
        summary.setRow(createRow());
        ArrayList<NotificationEntry> children = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            NotificationEntry child = new NotificationEntryBuilder().build();
            child.setRow(createRow());
            children.add(child);
        }
        mGroupNotifs.put(summary, children);
        return summary;
    }

    private ExpandableNotificationRow createRow() {
        ExpandableNotificationRow row = (ExpandableNotificationRow)
                LayoutInflater.from(mContext).inflate(R.layout.status_bar_notification_row, null);
        row.getPrivateLayout().setContractedChild(new View(mContext));
        row.getPrivateLayout().setExpandedChild(new View(mContext));
        return row;
    }
}

