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

import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_ALL;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_AMBIENT;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_EXPANDED;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_HEADS_UP;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_PUBLIC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.ArrayMap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationCallback;

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
public class NotificationContentInflaterTest extends SysuiTestCase {

    private NotificationContentInflater mNotificationInflater;
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
        mNotificationInflater = new NotificationContentInflater(mRow);
        mNotificationInflater.setInflationCallback(new InflationCallback() {
            @Override
            public void handleInflationException(StatusBarNotification notification,
                    Exception e) {
            }

            @Override
            public void onAsyncInflationFinished(NotificationEntry entry,
                    @NotificationContentInflater.InflationFlag int inflatedFlags) {
            }
        });
    }

    @Test
    public void testIncreasedHeadsUpBeingUsed() {
        mNotificationInflater.setUsesIncreasedHeadsUpHeight(true);
        Notification.Builder builder = spy(mBuilder);
        mNotificationInflater.inflateNotificationViews(
                false /* inflateSynchronously */,
                FLAG_CONTENT_VIEW_ALL,
                builder,
                mContext);
        verify(builder).createHeadsUpContentView(true);
    }

    @Test
    public void testIncreasedHeightBeingUsed() {
        mNotificationInflater.setUsesIncreasedHeight(true);
        Notification.Builder builder = spy(mBuilder);
        mNotificationInflater.inflateNotificationViews(
                false /* inflateSynchronously */,
                FLAG_CONTENT_VIEW_ALL,
                builder,
                mContext);
        verify(builder).createContentView(true);
    }

    @Test
    public void testInflationCallsUpdated() throws Exception {
        runThenWaitForInflation(() -> mNotificationInflater.inflateNotificationViews(),
                mNotificationInflater);
        verify(mRow).onNotificationUpdated();
    }

    @Test
    public void testInflationOnlyInflatesSetFlags() throws Exception {
        mNotificationInflater.updateInflationFlag(FLAG_CONTENT_VIEW_HEADS_UP,
                true /* shouldInflate */);
        runThenWaitForInflation(() -> mNotificationInflater.inflateNotificationViews(),
                mNotificationInflater);

        assertNotNull(mRow.getPrivateLayout().getHeadsUpChild());
        assertNull(mRow.getShowingLayout().getAmbientChild());
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
        NotificationContentInflater.InflationProgress result =
                new NotificationContentInflater.InflationProgress();
        result.packageContext = mContext;
        CountDownLatch countDownLatch = new CountDownLatch(1);
        NotificationContentInflater.applyRemoteView(
                false /* inflateSynchronously */,
                result,
                FLAG_CONTENT_VIEW_EXPANDED,
                0,
                new ArrayMap() /* cachedContentViews */, mRow, false /* redactAmbient */,
                true /* isNewView */, (v, p, r) -> true,
                new InflationCallback() {
                    @Override
                    public void handleInflationException(StatusBarNotification notification,
                            Exception e) {
                        countDownLatch.countDown();
                        throw new RuntimeException("No Exception expected");
                    }

                    @Override
                    public void onAsyncInflationFinished(NotificationEntry entry,
                            @NotificationContentInflater.InflationFlag int inflatedFlags) {
                        countDownLatch.countDown();
                    }
                }, mRow.getPrivateLayout(), null, null, new HashMap<>(),
                new NotificationContentInflater.ApplyCallback() {
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

    @Test
    public void testUpdateNeedsRedactionReinflatesChangedContentViews() {
        mNotificationInflater.updateInflationFlag(FLAG_CONTENT_VIEW_AMBIENT, true);
        mNotificationInflater.updateInflationFlag(FLAG_CONTENT_VIEW_PUBLIC, true);
        mNotificationInflater.updateNeedsRedaction(true);

        NotificationContentInflater.AsyncInflationTask asyncInflationTask =
                (NotificationContentInflater.AsyncInflationTask) mRow.getEntry().getRunningTask();
        assertEquals(FLAG_CONTENT_VIEW_AMBIENT | FLAG_CONTENT_VIEW_PUBLIC,
                asyncInflationTask.getReInflateFlags());
        asyncInflationTask.abort();
    }

    /* Cancelling requires us to be on the UI thread otherwise we might have a race */
    @Test
    public void testSupersedesExistingTask() {
        mNotificationInflater.addInflationFlags(FLAG_CONTENT_VIEW_ALL);
        mNotificationInflater.inflateNotificationViews();

        // Trigger inflation of content and expanded only.
        mNotificationInflater.setIsLowPriority(true);
        mNotificationInflater.setIsChildInGroup(true);

        InflationTask runningTask = mRow.getEntry().getRunningTask();
        NotificationContentInflater.AsyncInflationTask asyncInflationTask =
                (NotificationContentInflater.AsyncInflationTask) runningTask;
        assertEquals("Successive inflations don't inherit the previous flags!",
                FLAG_CONTENT_VIEW_ALL, asyncInflationTask.getReInflateFlags());
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
                NotificationContentInflater.canReapplyRemoteView(mediaView, decoratedMediaView));
    }

    public static void runThenWaitForInflation(Runnable block,
            NotificationContentInflater inflater) throws Exception {
        runThenWaitForInflation(block, false /* expectingException */, inflater);
    }

    private static void runThenWaitForInflation(Runnable block, boolean expectingException,
            NotificationContentInflater inflater) throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        final ExceptionHolder exceptionHolder = new ExceptionHolder();
        inflater.setInflateSynchronously(true);
        inflater.setInflationCallback(new InflationCallback() {
            @Override
            public void handleInflationException(StatusBarNotification notification,
                    Exception e) {
                if (!expectingException) {
                    exceptionHolder.setException(e);
                }
                countDownLatch.countDown();
            }

            @Override
            public void onAsyncInflationFinished(NotificationEntry entry,
                    @NotificationContentInflater.InflationFlag int inflatedFlags) {
                if (expectingException) {
                    exceptionHolder.setException(new RuntimeException(
                            "Inflation finished even though there should be an error"));
                }
                countDownLatch.countDown();
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
