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

package com.android.systemui.statusbar.notification;

import static com.android.systemui.statusbar.notification.NotificationInflater.FLAG_REINFLATE_ALL;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationinflaterTest {

    private Context mContext;
    private NotificationInflater mNotificationInflater;
    private Notification.Builder mBuilder;

    @Before
    @UiThreadTest
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mBuilder = new Notification.Builder(mContext).setSmallIcon(
                R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");
        ExpandableNotificationRow row = new NotificationTestHelper(mContext).createRow(
                mBuilder.build());
        mNotificationInflater = new NotificationInflater(row);
    }

    @Test
    public void testIncreasedHeadsUpBeingUsed() {
        mNotificationInflater.setUsesIncreasedHeadsUpHeight(true);
        Notification.Builder builder = spy(mBuilder);
        mNotificationInflater.inflateNotificationViews(FLAG_REINFLATE_ALL, builder, mContext);
        verify(builder).createHeadsUpContentView(true);
    }

    @Test
    public void testIncreasedHeightBeingUsed() {
        mNotificationInflater.setUsesIncreasedHeight(true);
        Notification.Builder builder = spy(mBuilder);
        mNotificationInflater.inflateNotificationViews(FLAG_REINFLATE_ALL, builder, mContext);
        verify(builder).createContentView(true);
    }
}
