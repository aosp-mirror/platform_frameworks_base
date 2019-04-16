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

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.NotificationTestHelper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class NotificationInflaterTest extends SysuiTestCase {

    private NotificationInflater mNotificationInflater;
    private Notification.Builder mBuilder;
    private ExpandableNotificationRow mRow;

    @Before
    public void setUp() throws Exception {
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
        assertTrue(mRow.getPrivateLayout().getChildCount() == 1);
        assertTrue(mRow.getPrivateLayout().getChildAt(0)
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
        assertTrue(mRow.getPrivateLayout().getChildCount() == 0);
        verify(mRow, times(0)).onNotificationUpdated();
    }

    @Test
    public void testAsyncTaskRemoved() throws Exception {
        mRow.getEntry().abortTask();
        runThenWaitForInflation(() -> mNotificationInflater.inflateNotificationViews(),
                mNotificationInflater);
        verify(mRow).onNotificationUpdated();
    }

    @Test
    public void testRemovedNotInflated() throws Exception {
        mRow.setRemoved();
        mNotificationInflater.inflateNotificationViews();
        Assert.assertNull(mRow.getEntry().getRunningTask());
    }

    @Test
    @Ignore
    public void testInflationIsRetriedIfAsyncFails() throws Exception {
        NotificationInflater.InflationProgress result =
                new NotificationInflater.InflationProgress();
        result.packageContext = mContext;
        CountDownLatch countDownLatch = new CountDownLatch(1);
        NotificationInflater.applyRemoteView(result,
                NotificationInflater.FLAG_REINFLATE_EXPANDED_VIEW, 0, mRow,
                false /* redactAmbient */, true /* isNewView */, new RemoteViews.OnClickHandler(),
                new NotificationInflater.InflationCallback() {
                    @Override
                    public void handleInflationException(StatusBarNotification notification,
                            Exception e) {
                        countDownLatch.countDown();
                        throw new RuntimeException("No Exception expected");
                    }

                    @Override
                    public void onAsyncInflationFinished(NotificationData.Entry entry) {
                        countDownLatch.countDown();
                    }
                }, mRow.getEntry(), mRow.getPrivateLayout(), null, null, new HashMap<>(),
                new NotificationInflater.ApplyCallback() {
                    @Override
                    public void setResultView(View v) {
                    }

                    @Override
                    public RemoteViews getRemoteView() {
                        return new AsyncFailRemoteView(mContext.getPackageName(),
                                R.layout.custom_view_dark);
                    }
                });
        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS));
    }

    /* Cancelling requires us to be on the UI thread otherwise we might have a race */
    @Test
    public void testSupersedesExistingTask() throws Exception {
        mNotificationInflater.inflateNotificationViews();
        mNotificationInflater.setIsLowPriority(true);
        mNotificationInflater.setIsChildInGroup(true);
        InflationTask runningTask = mRow.getEntry().getRunningTask();
        NotificationInflater.AsyncInflationTask asyncInflationTask =
                (NotificationInflater.AsyncInflationTask) runningTask;
        Assert.assertSame("Successive inflations don't inherit the previous flags!",
                asyncInflationTask.getReInflateFlags(),
                NotificationInflater.FLAG_REINFLATE_ALL);
        runningTask.abort();
    }

    @Test
    public void doesntReapplyDisallowedRemoteView() throws Exception {
        mBuilder.setStyle(new Notification.MediaStyle());
        RemoteViews mediaView = mBuilder.createContentView();
        mBuilder.setStyle(new Notification.DecoratedCustomViewStyle());
        mBuilder.setCustomContentView(new RemoteViews(getContext().getPackageName(),
                R.layout.custom_view_dark));
        RemoteViews decoratedMediaView = mBuilder.createContentView();
        Assert.assertFalse("The decorated media style doesn't allow a view to be reapplied!",
                NotificationInflater.canReapplyRemoteView(mediaView, decoratedMediaView));
    }

    public static void runThenWaitForInflation(Runnable block,
            NotificationInflater inflater) throws Exception {
        runThenWaitForInflation(block, false /* expectingException */, inflater);
    }

    private static void runThenWaitForInflation(Runnable block, boolean expectingException,
            NotificationInflater inflater) throws Exception {
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

            @Override
            public boolean doInflateSynchronous() {
                return true;
            }
        });
        block.run();
        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS));
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

    private class AsyncFailRemoteView extends RemoteViews {
        Handler mHandler = Handler.createAsync(Looper.getMainLooper());

        public AsyncFailRemoteView(String packageName, int layoutId) {
            super(packageName, layoutId);
        }

        @Override
        public View apply(Context context, ViewGroup parent) {
            return super.apply(context, parent);
        }

        @Override
        public CancellationSignal applyAsync(Context context, ViewGroup parent, Executor executor,
                OnViewAppliedListener listener, OnClickHandler handler) {
            mHandler.post(() -> listener.onError(new RuntimeException("Failed to inflate async")));
            return new CancellationSignal();
        }

        @Override
        public CancellationSignal applyAsync(Context context, ViewGroup parent, Executor executor,
                OnViewAppliedListener listener) {
            return applyAsync(context, parent, executor, listener, null);
        }
    }
}
