/*
 ** Copyright 2017, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityService;
import android.app.StatusBarManager;
import android.content.Context;
import android.os.Handler;

import com.android.internal.util.ScreenshotHelper;
import com.android.server.wm.WindowManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for GlobalActionPerformer
 */
public class GlobalActionPerformerTest {
    GlobalActionPerformer mGlobalActionPerformer;

    @Mock Context mMockContext;
    @Mock WindowManagerInternal mMockWindowManagerInternal;
    @Mock StatusBarManager mMockStatusBarManager;
    @Mock ScreenshotHelper mMockScreenshotHelper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mMockContext.getSystemService(android.app.Service.STATUS_BAR_SERVICE))
                .thenReturn(mMockStatusBarManager);

        mGlobalActionPerformer =
                new GlobalActionPerformer(mMockContext, mMockWindowManagerInternal,
                        () -> mMockScreenshotHelper);
    }

    @Test
    public void testNotifications_expandsNotificationPanel() {
        mGlobalActionPerformer
                .performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        verify(mMockStatusBarManager).expandNotificationsPanel();
    }

    @Test
    public void testQuickSettings_requestsQuickSettingsPanel() {
        mGlobalActionPerformer
                .performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
        verify(mMockStatusBarManager).expandSettingsPanel();
    }

    @Test
    public void testPowerDialog_requestsFromWindowManager() {
        mGlobalActionPerformer.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        verify(mMockWindowManagerInternal).showGlobalActions();
    }

    @Test
    public void testScreenshot_requestsFromScreenshotHelper() {
        mGlobalActionPerformer.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
        verify(mMockScreenshotHelper).takeScreenshot(
                eq(android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN), anyBoolean(),
                anyBoolean(), any(Handler.class));
    }
}
