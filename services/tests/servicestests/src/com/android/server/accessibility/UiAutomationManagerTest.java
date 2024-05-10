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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for UiAutomationManager
 */
public class UiAutomationManagerTest {
    static final int SERVICE_ID = 42;

    final UiAutomationManager mUiAutomationManager = new UiAutomationManager(new Object());

    MessageCapturingHandler mMessageCapturingHandler;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock Context mMockContext;
    @Mock AccessibilityServiceInfo mMockServiceInfo;
    @Mock ResolveInfo mMockResolveInfo;
    @Mock AccessibilitySecurityPolicy mMockSecurityPolicy;
    @Mock AccessibilityWindowManager mMockA11yWindowManager;
    @Mock AbstractAccessibilityServiceConnection.SystemSupport mMockSystemSupport;
    @Mock AccessibilityTrace mMockA11yTrace;
    @Mock WindowManagerInternal mMockWindowManagerInternal;
    @Mock SystemActionPerformer mMockSystemActionPerformer;
    @Mock IBinder mMockOwner;
    @Mock IAccessibilityServiceClient mMockAccessibilityServiceClient;
    @Mock IBinder mMockServiceAsBinder;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mMockSystemSupport.getKeyEventDispatcher()).thenReturn(mock(KeyEventDispatcher.class));

        when(mMockServiceInfo.getResolveInfo()).thenReturn(mMockResolveInfo);
        mMockResolveInfo.serviceInfo = mock(ServiceInfo.class);
        mMockResolveInfo.serviceInfo.applicationInfo = mock(ApplicationInfo.class);

        when(mMockAccessibilityServiceClient.asBinder()).thenReturn(mMockServiceAsBinder);
        when(mMockA11yTrace.isA11yTracingEnabled()).thenReturn(false);

        final Context context = getInstrumentation().getTargetContext();
        when(mMockContext.getSystemService(Context.DISPLAY_SERVICE)).thenReturn(
                context.getSystemService(
                        DisplayManager.class));

        mMessageCapturingHandler = new MessageCapturingHandler(null);
    }

    @After
    public void tearDown() {
        mMessageCapturingHandler.removeAllMessages();
    }


    @Test
    public void isRunning_returnsTrueOnlyWhenRunning() {
        assertFalse(mUiAutomationManager.isUiAutomationRunningLocked());
        register(0);
        assertTrue(mUiAutomationManager.isUiAutomationRunningLocked());
        unregister();
        assertFalse(mUiAutomationManager.isUiAutomationRunningLocked());
    }

    @Test
    public void suppressingAccessibilityServicesLocked_dependsOnFlags() {
        assertFalse(mUiAutomationManager.suppressingAccessibilityServicesLocked());
        register(0);
        assertTrue(mUiAutomationManager.suppressingAccessibilityServicesLocked());
        unregister();
        assertFalse(mUiAutomationManager.suppressingAccessibilityServicesLocked());
        register(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertFalse(mUiAutomationManager.suppressingAccessibilityServicesLocked());
        unregister();
        assertFalse(mUiAutomationManager.suppressingAccessibilityServicesLocked());
    }

    @Test
    public void isTouchExplorationEnabledLocked_dependsOnInfoFlags() {
        assertFalse(mUiAutomationManager.isTouchExplorationEnabledLocked());
        register(0);
        assertFalse(mUiAutomationManager.isTouchExplorationEnabledLocked());
        unregister();
        mMockServiceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        register(0);
        assertTrue(mUiAutomationManager.isTouchExplorationEnabledLocked());
        unregister();
        assertFalse(mUiAutomationManager.isTouchExplorationEnabledLocked());
    }

    @Test
    public void canRetrieveInteractiveWindowsLocked_dependsOnInfoFlags() {
        assertFalse(mUiAutomationManager.canRetrieveInteractiveWindowsLocked());
        register(0);
        assertFalse(mUiAutomationManager.canRetrieveInteractiveWindowsLocked());
        unregister();
        mMockServiceInfo.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        register(0);
        assertTrue(mUiAutomationManager.canRetrieveInteractiveWindowsLocked());
        unregister();
        assertFalse(mUiAutomationManager.canRetrieveInteractiveWindowsLocked());
    }

    @Test
    public void getRequestedEventMaskLocked_dependsOnInfoEventTypes() {
        assertEquals(0, mUiAutomationManager.getRequestedEventMaskLocked());
        mMockServiceInfo.eventTypes = 0;
        register(0);
        assertEquals(mMockServiceInfo.eventTypes,
                mUiAutomationManager.getRequestedEventMaskLocked());
        unregister();
        mMockServiceInfo.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        register(0);
        assertEquals(mMockServiceInfo.eventTypes,
                mUiAutomationManager.getRequestedEventMaskLocked());
        unregister();
        assertEquals(0, mUiAutomationManager.getRequestedEventMaskLocked());
    }

    @Test
    public void uiAutomationBinderDiesBeforeConnecting_notifiesSystem() throws Exception {
        register(0);
        ArgumentCaptor<IBinder.DeathRecipient> captor = ArgumentCaptor.forClass(
                IBinder.DeathRecipient.class);
        verify(mMockOwner).linkToDeath(captor.capture(), anyInt());
        captor.getValue().binderDied();
        mMessageCapturingHandler.sendAllMessages();
        verify(mMockSystemSupport).onClientChangeLocked(false);
    }

    @Test
    public void uiAutomationWithDontUseAccessibilityFlagAfterUnregistering_notifiesSystem()
            throws Exception {
        register(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        unregister();
        verify(mMockSystemSupport).onClientChangeLocked(false);
    }

    @Test
    public void uiAutomationWithDontUseAccessibilityFlag_disableAccessibilityFunctions()
            throws Exception {
        register(0);
        assertTrue(mUiAutomationManager.isUiAutomationRunningLocked());
        unregister();
        assertFalse(mUiAutomationManager.isUiAutomationRunningLocked());
        register(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        assertTrue(mUiAutomationManager.isUiAutomationRunningLocked());
        assertFalse(mUiAutomationManager.useAccessibility());
        assertFalse(mUiAutomationManager.canRetrieveInteractiveWindowsLocked());
        assertFalse(mUiAutomationManager.isTouchExplorationEnabledLocked());
        assertEquals(0, mUiAutomationManager.getRelevantEventTypes());
        assertEquals(0, mUiAutomationManager.getRequestedEventMaskLocked());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ADD_WINDOW_TOKEN_WITHOUT_LOCK)
    public void registerUiAutomationService_callsAddWindowTokenUsingHandler() {
        register(0);
        // registerUiTestAutomationServiceLocked() should not directly call addWindowToken.
        verify(mMockWindowManagerInternal, never()).addWindowToken(
                any(), anyInt(), anyInt(), any());

        // Advance UiAutomationManager#UiAutomationService's handler.
        mMessageCapturingHandler.sendAllMessages();

        // After advancing the handler we expect addWindowToken to have been called
        // by the UiAutomationService instance.
        verify(mMockWindowManagerInternal).addWindowToken(
                any(), eq(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY),
                anyInt(), eq(null));
    }

    private void register(int flags) {
        mUiAutomationManager.registerUiTestAutomationServiceLocked(mMockOwner,
                mMockAccessibilityServiceClient, mMockContext, mMockServiceInfo, SERVICE_ID,
                mMessageCapturingHandler, mMockSecurityPolicy, mMockSystemSupport, mMockA11yTrace,
                mMockWindowManagerInternal, mMockSystemActionPerformer,
                mMockA11yWindowManager, flags);
    }

    private void unregister() {
        mUiAutomationManager.unregisterUiTestAutomationServiceLocked(
                mMockAccessibilityServiceClient);
    }
}
