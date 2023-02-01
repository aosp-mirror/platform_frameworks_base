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
 * limitations under the License.
 */

package com.android.server.accessibility;

import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.accessibilityservice.AccessibilityService;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.app.RemoteAction;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.ScreenshotHelper;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for SystemActionPerformer
 */
public class SystemActionPerformerTest {
    private static final int LATCH_TIMEOUT_MS = 500;
    private static final int LEGACY_SYSTEM_ACTION_COUNT = 8;
    private static final int NEW_ACTION_ID = 20;
    private static final String LABEL_1 = "label1";
    private static final String LABEL_2 = "label2";
    private static final String INTENT_ACTION1 = "TESTACTION1";
    private static final String INTENT_ACTION2 = "TESTACTION2";
    private static final String DESCRIPTION1 = "description1";
    private static final String DESCRIPTION2 = "description2";
    private static final PendingIntent TEST_PENDING_INTENT_1 = PendingIntent.getBroadcast(
            InstrumentationRegistry.getTargetContext(), 0, new Intent(INTENT_ACTION1), PendingIntent.FLAG_MUTABLE_UNAUDITED);
    private static final RemoteAction NEW_TEST_ACTION_1 = new RemoteAction(
            Icon.createWithContentUri("content://test"),
            LABEL_1,
            DESCRIPTION1,
            TEST_PENDING_INTENT_1);
    private static final PendingIntent TEST_PENDING_INTENT_2 = PendingIntent.getBroadcast(
            InstrumentationRegistry.getTargetContext(), 0, new Intent(INTENT_ACTION2), PendingIntent.FLAG_MUTABLE_UNAUDITED);
    private static final RemoteAction NEW_TEST_ACTION_2 = new RemoteAction(
            Icon.createWithContentUri("content://test"),
            LABEL_2,
            DESCRIPTION2,
            TEST_PENDING_INTENT_2);

    private static final AccessibilityAction NEW_ACCESSIBILITY_ACTION =
            new AccessibilityAction(NEW_ACTION_ID, LABEL_1);
    private static final AccessibilityAction LEGACY_NOTIFICATIONS_ACCESSIBILITY_ACTION =
            new AccessibilityAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, LABEL_1);
    private static final AccessibilityAction LEGACY_HOME_ACCESSIBILITY_ACTION =
            new AccessibilityAction(AccessibilityService.GLOBAL_ACTION_HOME, LABEL_2);

    private SystemActionPerformer mSystemActionPerformer;

    @Mock private Context mMockContext;
    @Mock private StatusBarManagerInternal mMockStatusBarManagerInternal;
    @Mock private WindowManagerInternal mMockWindowManagerInternal;
    @Mock private StatusBarManager mMockStatusBarManager;
    @Mock private ScreenshotHelper mMockScreenshotHelper;
    @Mock private SystemActionPerformer.SystemActionsChangedListener mMockListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mMockStatusBarManagerInternal);
    }

    private void setupWithMockContext() {
        doReturn(mMockStatusBarManager).when(
                mMockContext).getSystemService(android.app.Service.STATUS_BAR_SERVICE);
        doReturn(InstrumentationRegistry.getContext().getResources()).when(
                mMockContext).getResources();
        mSystemActionPerformer = new SystemActionPerformer(
                mMockContext,
                mMockWindowManagerInternal,
                () -> mMockScreenshotHelper,
                mMockListener);
    }

    private void setupWithRealContext() {
        mSystemActionPerformer = new SystemActionPerformer(
                InstrumentationRegistry.getContext(),
                mMockWindowManagerInternal,
                () -> mMockScreenshotHelper,
                mMockListener);
    }

    // We need below two help functions because AccessbilityAction.equals function only compares
    // action ids. To verify the test result here, we are also looking at action labels.
    private void assertHasLegacyAccessibilityAction(
            List<AccessibilityAction> actions, AccessibilityAction action) {
        boolean foundAction = false;
        for (AccessibilityAction a : actions) {
            if ((a.getId() == action.getId()) && (a.getLabel().equals(action.getLabel()))) {
                foundAction = true;
                break;
            }
        }
        assertTrue(foundAction);
    }

    private void assertHasNoLegacyAccessibilityAction(
            List<AccessibilityAction> actions, AccessibilityAction action) {
        boolean foundAction = false;
        for (AccessibilityAction a : actions) {
            if ((a.getId() == action.getId()) && (a.getLabel().equals(action.getLabel()))) {
                foundAction = true;
                break;
            }
        }
        assertFalse(foundAction);
    }

    @Test
    public void testRegisterSystemAction_addedIntoAvailableSystemActions() {
        setupWithRealContext();
        // Before any new system action is registered, getSystemActions returns all legacy actions
        List<AccessibilityAction> actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT, actions.size());
        // Register a new system action
        mSystemActionPerformer.registerSystemAction(NEW_ACTION_ID, NEW_TEST_ACTION_1);
        actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT + 1, actions.size());
        assertThat(actions, hasItem(NEW_ACCESSIBILITY_ACTION));
    }

    @Test
    public void testRegisterSystemAction_overrideLegacyAction() {
        setupWithRealContext();
        // Before any new system action is registered, getSystemActions returns all legacy actions
        List<AccessibilityAction> actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT, actions.size());
        // Overriding a legacy system action using legacy notification action id
        mSystemActionPerformer.registerSystemAction(
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, NEW_TEST_ACTION_1);
        actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT, actions.size());
        assertHasLegacyAccessibilityAction(actions, LEGACY_NOTIFICATIONS_ACCESSIBILITY_ACTION);
    }

    @Test
    public void testUnregisterSystemAction_removeFromAvailableSystemActions() {
        setupWithRealContext();
        // Before any new system action is registered, getSystemActions returns all legacy actions
        List<AccessibilityAction> actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT, actions.size());
        // Register a new system action
        mSystemActionPerformer.registerSystemAction(NEW_ACTION_ID, NEW_TEST_ACTION_1);
        actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT + 1, actions.size());

        mSystemActionPerformer.unregisterSystemAction(NEW_ACTION_ID);
        actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT, actions.size());
        assertThat(actions, is(not(hasItem(NEW_ACCESSIBILITY_ACTION))));
    }

    @Test
    public void testUnregisterSystemAction_removeOverrideForLegacyAction() {
        setupWithRealContext();

        // Overriding a legacy system action
        mSystemActionPerformer.registerSystemAction(
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, NEW_TEST_ACTION_1);
        List<AccessibilityAction> actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT, actions.size());
        assertHasLegacyAccessibilityAction(actions, LEGACY_NOTIFICATIONS_ACCESSIBILITY_ACTION);

        // Remove the overriding action using legacy action id
        mSystemActionPerformer.unregisterSystemAction(
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        actions = mSystemActionPerformer.getSystemActions();
        assertEquals(LEGACY_SYSTEM_ACTION_COUNT, actions.size());
        assertHasNoLegacyAccessibilityAction(actions, LEGACY_NOTIFICATIONS_ACCESSIBILITY_ACTION);
    }

    @Test
    public void testPerformSystemActionNewAction() throws CanceledException {
        setupWithRealContext();

        final CountDownLatch latch = new CountDownLatch(1);
        mSystemActionPerformer.registerSystemAction(NEW_ACTION_ID, NEW_TEST_ACTION_1);
        TestBroadcastReceiver br = new TestBroadcastReceiver(latch);
        br.register(InstrumentationRegistry.getTargetContext());
        mSystemActionPerformer.performSystemAction(NEW_ACTION_ID);
        try {
            latch.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail("RemoteAction should be triggered.");
        } finally {
            br.unregister(InstrumentationRegistry.getTargetContext());
        }
    }

    @Test
    public void testPerformSystemActionOverrideLegacyActionUsingLegacyActionId()
            throws CanceledException {
        setupWithRealContext();

        final CountDownLatch latch = new CountDownLatch(1);
        mSystemActionPerformer.registerSystemAction(
                AccessibilityService.GLOBAL_ACTION_RECENTS, NEW_TEST_ACTION_1);
        TestBroadcastReceiver br = new TestBroadcastReceiver(latch);
        br.register(InstrumentationRegistry.getTargetContext());
        mSystemActionPerformer.performSystemAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
        try {
            latch.await(LATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail("RemoteAction should be triggered.");
        } finally {
            br.unregister(InstrumentationRegistry.getTargetContext());
        }
        verify(mMockStatusBarManagerInternal, never()).toggleRecentApps();
        // Now revert to legacy action
        mSystemActionPerformer.unregisterSystemAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
        mSystemActionPerformer.performSystemAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
        verify(mMockStatusBarManagerInternal).toggleRecentApps();
    }

    @Test
    public void testNotifications_expandsNotificationPanel_legacy() {
        setupWithMockContext();
        mSystemActionPerformer
                .performSystemAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        verify(mMockStatusBarManager).expandNotificationsPanel();
    }

    @Test
    public void testQuickSettings_requestsQuickSettingsPanel_legacy() {
        setupWithMockContext();
        mSystemActionPerformer
                .performSystemAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
        verify(mMockStatusBarManager).expandSettingsPanel();
    }

    @Test
    public void testRecentApps_legacy() {
        setupWithRealContext();
        mSystemActionPerformer.performSystemAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
        verify(mMockStatusBarManagerInternal).toggleRecentApps();
    }

    @Test
    public void testPowerDialog_requestsFromWindowManager_legacy() {
        setupWithMockContext();
        mSystemActionPerformer.performSystemAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        verify(mMockWindowManagerInternal).showGlobalActions();
    }

    @Test
    public void testScreenshot_requestsFromScreenshotHelper_legacy() {
        setupWithMockContext();
        mSystemActionPerformer.performSystemAction(
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
        verify(mMockScreenshotHelper).takeScreenshot(
                eq(TAKE_SCREENSHOT_FULLSCREEN),
                eq(SCREENSHOT_ACCESSIBILITY_ACTIONS),
                any(Handler.class), any());
    }

    // PendingIntent is a final class and cannot be mocked. So we are using this
    // Broadcast receiver to verify the registered remote action is called correctly.
    private static final class TestBroadcastReceiver extends BroadcastReceiver {
        private CountDownLatch mLatch;
        private boolean mRegistered;
        private final IntentFilter mFilter;

        TestBroadcastReceiver(CountDownLatch latch) {
            mLatch = latch;
            mRegistered = false;
            mFilter = new IntentFilter(INTENT_ACTION1);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mLatch.countDown();
        }

        void register(Context context) {
            if (!mRegistered) {
                context.registerReceiver(this, mFilter);
                mRegistered = true;
            }
        }

        void unregister(Context context) {
            if (mRegistered) {
                context.unregisterReceiver(this);
            }
        }
    }
}
