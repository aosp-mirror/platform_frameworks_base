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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.FlakyTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.widget.RemoteViews;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationTestHelper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidJUnit4.class)
@FlakyTest
public class NotificationInflaterTest {

    private Context mContext;
    private NotificationInflater mNotificationInflater;
    private Notification.Builder mBuilder;
    private ExpandableNotificationRow mRow;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mBuilder = new Notification.Builder(mContext).setSmallIcon(
                R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .setStyle(new Notification.BigTextStyle().bigText("big text"));
        ExpandableNotificationRow row = new NotificationTestHelper(mContext).createRow(
                mBuilder.build());
        mRow = spy(row);
        mNotificationInflater = new NotificationInflater(mRow);
        mNotificationInflater.setInflationCallback(new NotificationInflater.InflationCallback() {
            @Override
            public void handleInflationException(StatusBarNotification notification,
                    Exception e) {
            }

            @Override
            public void onAsyncInflationFinished(NotificationData.Entry entry) {
            }
        });
    }

    @Test
    @UiThreadTest
    public void testIncreasedHeadsUpBeingUsed() {
        mNotificationInflater.setUsesIncreasedHeadsUpHeight(true);
        Notification.Builder builder = spy(mBuilder);
        mNotificationInflater.inflateNotificationViews(FLAG_REINFLATE_ALL, builder, mContext);
        verify(builder).createHeadsUpContentView(true);
    }

    @Test
    @UiThreadTest
    public void testIncreasedHeightBeingUsed() {
        mNotificationInflater.setUsesIncreasedHeight(true);
        Notification.Builder builder = spy(mBuilder);
        mNotificationInflater.inflateNotificationViews(FLAG_REINFLATE_ALL, builder, mContext);
        verify(builder).createContentView(true);
    }

    @Test
    public void testInflationCallsUpdated() throws Exception {
        runThenWaitForInflation(() -> mNotificationInflater.inflateNotificationViews(),
                mNotificationInflater);
        verify(mRow).onNotificationUpdated();
    }

    @Test
    public void testInflationCallsOnlyRightMethod() throws Exception {
        mRow.getPrivateLayout().removeAllViews();
        mRow.getEntry().cachedBigContentView = null;
        runThenWaitForInflation(() -> mNotificationInflater.inflateNotificationViews(
                NotificationInflater.FLAG_REINFLATE_EXPANDED_VIEW), mNotificationInflater);
        Assert.assertTrue(mRow.getPrivateLayout().getChildCount() == 1);
        Assert.assertTrue(mRow.getPrivateLayout().getChildAt(0)
                == mRow.getPrivateLayout().getExpandedChild());
        verify(mRow).onNotificationUpdated();
    }

    @Test
    public void testInflationThrowsErrorDoesntCallUpdated() throws Exception {
        mRow.getPrivateLayout().removeAllViews();
        mRow.getStatusBarNotification().getNotification().contentView
                = new RemoteViews(mContext.getPackageName(), R.layout.status_bar);
        runThenWaitForInflation(() -> mNotificationInflater.inflateNotificationViews(),
                true /* expectingException */, mNotificationInflater);
        Assert.assertTrue(mRow.getPrivateLayout().getChildCount() == 0);
        verify(mRow, times(0)).onNotificationUpdated();
    }

    @Test
    public void testAsyncTaskRemoved() throws Exception {
        mRow.getEntry().abortTask();
        runThenWaitForInflation(() -> mNotificationInflater.inflateNotificationViews(),
                mNotificationInflater);
        Assert.assertNull(mRow.getEntry().getRunningTask() );
    }

    public static void runThenWaitForInflation(Runnable block,
            NotificationInflater inflater) throws Exception {
        runThenWaitForInflation(block, false /* expectingException */, inflater);
    }

    private static void runThenWaitForInflation(Runnable block, boolean expectingException,
            NotificationInflater inflater) throws Exception {
        com.android.systemui.util.Assert.isNotMainThread();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        final ExceptionHolder exceptionHolder = new ExceptionHolder();
        inflater.setInflationCallback(new NotificationInflater.InflationCallback() {
            @Override
            public void handleInflationException(StatusBarNotification notification,
                    Exception e) {
                if (!expectingException) {
                    exceptionHolder.setException(e);
                }
                countDownLatch.countDown();
            }

            @Override
            public void onAsyncInflationFinished(NotificationData.Entry entry) {
                if (expectingException) {
                    exceptionHolder.setException(new RuntimeException(
                            "Inflation finished even though there should be an error"));
                }
                countDownLatch.countDown();
            }
        });
        block.run();
        countDownLatch.await();
        if (exceptionHolder.mException != null) {
            throw exceptionHolder.mException;
        }
    }

    private static class ExceptionHolder {
        private Exception mException;

        public void setException(Exception exception) {
            mException = exception;
        }
    }
}
