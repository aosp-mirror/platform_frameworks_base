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

import static com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType.REGULAR_SMART_ACTIONS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
/**
 * Tests for exception handling and  bitmap configuration in adding smart actions to Screenshot
 * Notification.
 */
public class ScreenshotNotificationSmartActionsTest extends SysuiTestCase {
    private ScreenshotNotificationSmartActionsProvider mSmartActionsProvider;
    private ScreenshotSmartActions mScreenshotSmartActions;
    private Handler mHandler;

    @Before
    public void setup() {
        mSmartActionsProvider = mock(
                ScreenshotNotificationSmartActionsProvider.class);
        mScreenshotSmartActions = new ScreenshotSmartActions(() -> mSmartActionsProvider);
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
        when(smartActionsProvider.getActions(any(), any(), any(), any(), any(), any()))
                .thenThrow(RuntimeException.class);
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                mScreenshotSmartActions.getSmartActionsFuture(
                        "", Uri.parse("content://authority/data"), bitmap, smartActionsProvider,
                        REGULAR_SMART_ACTIONS,
                        true, UserHandle.of(UserHandle.myUserId()));
        assertNotNull(smartActionsFuture);
        List<Notification.Action> smartActions = smartActionsFuture.get(5, TimeUnit.MILLISECONDS);
        assertEquals(Collections.emptyList(), smartActions);
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
        List<Notification.Action> actions = mScreenshotSmartActions.getSmartActions(
                "", smartActionsFuture, timeoutMs, mSmartActionsProvider, REGULAR_SMART_ACTIONS);
        assertEquals(Collections.emptyList(), actions);
    }

    // Tests any exception thrown in notifying feedback does not affect regular screenshot flow.
    @Test
    public void testExceptionHandlingInNotifyingFeedback()
            throws Exception {
        doThrow(RuntimeException.class).when(mSmartActionsProvider).notifyOp(any(), any(), any(),
                anyLong());
        mScreenshotSmartActions.notifyScreenshotOp(null, mSmartActionsProvider, null, null, -1);
    }

    // Tests for a non-hardware bitmap, ScreenshotNotificationSmartActionsProvider is never invoked
    // and a completed future is returned.
    @Test
    public void testUnsupportedBitmapConfiguration()
            throws Exception {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.RGB_565);
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                mScreenshotSmartActions.getSmartActionsFuture(
                        "", Uri.parse("content://autority/data"), bitmap, mSmartActionsProvider,
                        REGULAR_SMART_ACTIONS,
                        true, UserHandle.of(UserHandle.myUserId()));
        verify(mSmartActionsProvider, never()).getActions(any(), any(), any(), any(), any(), any());
        assertNotNull(smartActionsFuture);
        List<Notification.Action> smartActions = smartActionsFuture.get(5, TimeUnit.MILLISECONDS);
        assertEquals(Collections.emptyList(), smartActions);
    }

    // Tests for a hardware bitmap, ScreenshotNotificationSmartActionsProvider is invoked once.
    @Test
    public void testScreenshotNotificationSmartActionsProviderInvokedOnce() {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.HARDWARE);
        mScreenshotSmartActions.getSmartActionsFuture(
                "", Uri.parse("content://autority/data"), bitmap, mSmartActionsProvider,
                REGULAR_SMART_ACTIONS, true,
                UserHandle.of(UserHandle.myUserId()));
        verify(mSmartActionsProvider, times(1)).getActions(
                any(), any(), any(), any(), any(), any());
    }

    // Tests for a hardware bitmap, a completed future is returned.
    @Test
    public void testSupportedBitmapConfiguration()
            throws Exception {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getConfig()).thenReturn(Bitmap.Config.HARDWARE);
        ScreenshotNotificationSmartActionsProvider actionsProvider =
                new ScreenshotNotificationSmartActionsProvider();
        CompletableFuture<List<Notification.Action>> smartActionsFuture =
                mScreenshotSmartActions.getSmartActionsFuture("", null, bitmap,
                        actionsProvider, REGULAR_SMART_ACTIONS,
                        true, UserHandle.of(UserHandle.myUserId()));
        assertNotNull(smartActionsFuture);
        List<Notification.Action> smartActions = smartActionsFuture.get(5, TimeUnit.MILLISECONDS);
        assertEquals(smartActions.size(), 0);
    }
}
