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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Handler;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;

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
        when(smartActionsProvider.getActions(any(), any(), any(), any(), any(),
                eq(false))).thenThrow(
                RuntimeException.class);
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                GlobalScreenshot.getSmartActionsFuture(mContext, bitmap,
                        smartActionsProvider, mHandler, true, false);
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
                smartActionsFuture, timeoutMs);
        Assert.assertEquals(Collections.emptyList(), actions);
    }

    // Tests for a non-hardware bitmap, ScreenshotNotificationSmartActionsProvider is never invoked
    // and a completed future is returned.
    @Test
    public void testUnsupportedBitmapConfiguration()
            throws Exception {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.RGB_565);
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                GlobalScreenshot.getSmartActionsFuture(mContext, bitmap,
                        mSmartActionsProvider, mHandler, true, true);
        verify(mSmartActionsProvider, never()).getActions(any(), any(), any(), any(), any(),
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
        GlobalScreenshot.getSmartActionsFuture(mContext, bitmap, mSmartActionsProvider,
                mHandler, true, true);
        verify(mSmartActionsProvider, times(1))
                .getActions(any(), any(), any(), any(), any(), eq(true));
    }

    // Tests for a hardware bitmap, a completed future is returned.
    @Test
    public void testSupportedBitmapConfiguration()
            throws Exception {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.HARDWARE);
        ScreenshotNotificationSmartActionsProvider actionsProvider =
                SystemUIFactory.getInstance().createScreenshotNotificationSmartActionsProvider();
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                GlobalScreenshot.getSmartActionsFuture(mContext, bitmap,
                        actionsProvider,
                        mHandler, true, true);
        Assert.assertNotNull(smartActionsFuture);
        List<Notification.Action> smartActions = smartActionsFuture.get(5, TimeUnit.MILLISECONDS);
        Assert.assertEquals(smartActions.size(), 0);
    }
}
