/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static android.content.Context.NOTIFICATION_SERVICE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.NotificationChannels;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tests for exception handling and bitmap configuration in adding smart actions to Screenshot
 * Notification.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ScreenshotNotificationSmartActionsTest extends SysuiTestCase {
    private ScreenshotNotificationSmartActionsProvider mSmartActionsProvider;
    private Handler mHandler;

    @Before
    public void setup() {
        mSmartActionsProvider = mock(
                ScreenshotNotificationSmartActionsProvider.class);
        mHandler = mock(Handler.class);
    }

    // Tests any exception thrown in getting smart actions future does not affect regular
    // screenshot flow.
    @Test
    public void testExceptionHandlingInGetSmartActionsFuture()
            throws Exception {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.HARDWARE);
        ScreenshotNotificationSmartActionsProvider smartActionsProvider = mock(
                ScreenshotNotificationSmartActionsProvider.class);
        when(smartActionsProvider.getActions(any(), any(), any(),
                eq(false))).thenThrow(RuntimeException.class);
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                GlobalScreenshot.getSmartActionsFuture("", bitmap,
                        smartActionsProvider, true, false);
        Assert.assertNotNull(smartActionsFuture);
        List<Notification.Action> smartActions = smartActionsFuture.get(5, TimeUnit.MILLISECONDS);
        Assert.assertEquals(Collections.emptyList(), smartActions);
    }

    // Tests any exception thrown in waiting for smart actions future to complete does
    // not affect regular screenshot flow.
    @Test
    public void testExceptionHandlingInGetSmartActions()
            throws Exception {
        CompletableFuture<List<Notification.Action>> smartActionsFuture = mock(
                CompletableFuture.class);
        int timeoutMs = 1000;
        when(smartActionsFuture.get(timeoutMs, TimeUnit.MILLISECONDS)).thenThrow(
                RuntimeException.class);
        List<Notification.Action> actions = GlobalScreenshot.getSmartActions(
                "", smartActionsFuture, timeoutMs, mSmartActionsProvider);
        Assert.assertEquals(Collections.emptyList(), actions);
    }

    // Tests any exception thrown in notifying feedback does not affect regular screenshot flow.
    @Test
    public void testExceptionHandlingInNotifyingFeedback() {
        doThrow(RuntimeException.class).when(mSmartActionsProvider).notifyOp(any(), any(), any(),
                anyLong());
        GlobalScreenshot.notifyScreenshotOp(null, mSmartActionsProvider, null, null, -1);
    }

    // Tests for a non-hardware bitmap, ScreenshotNotificationSmartActionsProvider is never invoked
    // and a completed future is returned.
    @Test
    public void testUnsupportedBitmapConfiguration()
            throws Exception {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.RGB_565);
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                GlobalScreenshot.getSmartActionsFuture("", bitmap,
                        mSmartActionsProvider, true, true);
        verify(mSmartActionsProvider, never()).getActions(any(), any(), any(),
                eq(false));
        Assert.assertNotNull(smartActionsFuture);
        List<Notification.Action> smartActions = smartActionsFuture.get(5, TimeUnit.MILLISECONDS);
        Assert.assertEquals(Collections.emptyList(), smartActions);
    }

    // Tests for a hardware bitmap, ScreenshotNotificationSmartActionsProvider is invoked once.
    @Test
    public void testScreenshotNotificationSmartActionsProviderInvokedOnce() {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.HARDWARE);
        GlobalScreenshot.getSmartActionsFuture("", bitmap, mSmartActionsProvider,
                true, true);
        verify(mSmartActionsProvider, times(1))
                .getActions(any(), any(), any(), eq(true));
    }

    // Tests for a hardware bitmap, a completed future is returned.
    @Test
    public void testSupportedBitmapConfiguration()
            throws Exception {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.HARDWARE);
        ScreenshotNotificationSmartActionsProvider actionsProvider =
                SystemUIFactory.getInstance().createScreenshotNotificationSmartActionsProvider(
                        mContext, null, mHandler);
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                GlobalScreenshot.getSmartActionsFuture("", bitmap,
                        actionsProvider,
                        true, true);
        Assert.assertNotNull(smartActionsFuture);
        List<Notification.Action> smartActions = smartActionsFuture.get(5, TimeUnit.MILLISECONDS);
        Assert.assertEquals(smartActions.size(), 0);
    }

    // Tests for notification action extras.
    @Test
    public void testNotificationActionExtras() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        NotificationManager notificationManager =
                (NotificationManager) mContext.getSystemService(NOTIFICATION_SERVICE);
        SaveImageInBackgroundData data = new SaveImageInBackgroundData();
        data.context = mContext;
        data.image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        data.iconSize = 10;
        data.finisher = null;
        data.previewWidth = 10;
        data.previewheight = 10;
        SaveImageInBackgroundTask task = new SaveImageInBackgroundTask(mContext, data,
                notificationManager);
        Uri uri = Uri.parse("Screenshot_123.png");
        Notification.Builder notificationBuilder = new Notification.Builder(mContext,
                NotificationChannels.SCREENSHOTS_HEADSUP);
        task.populateNotificationActions(mContext, mContext.getResources(),
                uri,
                CompletableFuture.completedFuture(Collections.emptyList()), notificationBuilder);

        Notification notification = notificationBuilder.build();
        Assert.assertEquals(notification.actions.length, 3);
        boolean isShareFound = false;
        boolean isEditFound = false;
        boolean isDeleteFound = false;
        for (Notification.Action action : notification.actions) {
            Intent intent = action.actionIntent.getIntent();
            Intent actionIntent = intent.getParcelableExtra(GlobalScreenshot.EXTRA_ACTION_INTENT);
            Assert.assertNotNull(intent);
            Bundle bundle = intent.getExtras();
            Assert.assertTrue(bundle.containsKey(GlobalScreenshot.EXTRA_ID));
            Assert.assertTrue(bundle.containsKey(GlobalScreenshot.EXTRA_SMART_ACTIONS_ENABLED));
            if (uri.toString().equals(bundle.getString(GlobalScreenshot.SCREENSHOT_URI_ID))) {
                isDeleteFound = true;
            } else if (Intent.ACTION_EDIT.equals(actionIntent.getAction())) {
                isEditFound = true;
            } else if (Intent.ACTION_CHOOSER.equals(actionIntent.getAction())) {
                isShareFound = true;
            }
        }

        Assert.assertTrue(isEditFound);
        Assert.assertTrue(isShareFound);
        Assert.assertTrue(isDeleteFound);
    }
}
