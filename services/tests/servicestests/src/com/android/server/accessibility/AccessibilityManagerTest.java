/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertSame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.os.UserHandle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.IntPair;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the AccessibilityManager by mocking the backing service.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityManagerTest {
    private static final boolean WITH_A11Y_ENABLED = true;
    private static final boolean WITH_A11Y_DISABLED = false;

    @Mock private IAccessibilityManager mMockService;
    private MessageCapturingHandler mHandler;
    private Instrumentation mInstrumentation;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHandler = new MessageCapturingHandler(null);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @After
    public void tearDown() {
        mHandler.removeAllMessages();
    }


    private AccessibilityManager createManager(boolean enabled) throws Exception {
        long serviceReturnValue = IntPair.of(
                (enabled) ? AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED : 0,
                AccessibilityEvent.TYPES_ALL_MASK);
        when(mMockService.addClient(any(IAccessibilityManagerClient.class), anyInt()))
                .thenReturn(serviceReturnValue);

        AccessibilityManager manager =
                new AccessibilityManager(mHandler, mMockService, UserHandle.USER_CURRENT);

        verify(mMockService).addClient(any(IAccessibilityManagerClient.class), anyInt());
        mHandler.setCallback(manager.getCallback());
        mHandler.sendAllMessages();
        return manager;
    }

    @Test
    public void testGetAccessibilityServiceList() throws Exception {
        // create a list of installed accessibility services the mock service returns
        List<AccessibilityServiceInfo> expectedServices = new ArrayList<>();
        AccessibilityServiceInfo accessibilityServiceInfo = new AccessibilityServiceInfo();
        accessibilityServiceInfo.packageNames = new String[] { "foo.bar" };
        expectedServices.add(accessibilityServiceInfo);

        // configure the mock service behavior
        when(mMockService.getInstalledAccessibilityServiceList(anyInt()))
                .thenReturn(expectedServices);

        // invoke the method under test
        AccessibilityManager manager = createManager(true);
        List<AccessibilityServiceInfo> receivedServices =
                manager.getInstalledAccessibilityServiceList();

        verify(mMockService).getInstalledAccessibilityServiceList(UserHandle.USER_CURRENT);
        // check expected result (list equals() compares it contents as well)
        assertEquals("All expected services must be returned", expectedServices, receivedServices);
    }

    @Test
    public void testInterrupt() throws Exception {
        AccessibilityManager manager = createManager(WITH_A11Y_ENABLED);
        manager.interrupt();

        verify(mMockService).interrupt(UserHandle.USER_CURRENT);
    }

    @Test
    public void testIsEnabled() throws Exception {
        // Create manager with a11y enabled
        AccessibilityManager manager = createManager(WITH_A11Y_ENABLED);
        assertTrue("Must be enabled since the mock service is enabled", manager.isEnabled());

        // Disable accessibility
        manager.getClient().setState(0);
        mHandler.sendAllMessages();
        assertFalse("Must be disabled since the mock service is disabled", manager.isEnabled());
    }

    @Test
    public void testSendAccessibilityEvent_AccessibilityEnabled() throws Exception {
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_ANNOUNCEMENT);

        AccessibilityManager manager = createManager(WITH_A11Y_ENABLED);
        manager.sendAccessibilityEvent(sentEvent);

        assertSame("The event should be recycled.", sentEvent, AccessibilityEvent.obtain());
    }

    @Test
    public void testSendAccessibilityEvent_AccessibilityDisabled() throws Exception {
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();

        AccessibilityManager manager = createManager(WITH_A11Y_DISABLED);
        mInstrumentation.runOnMainSync(() -> {
            try {
                manager.sendAccessibilityEvent(sentEvent);
                fail("No accessibility events are sent if accessibility is disabled");
            } catch (IllegalStateException ise) {
                // check expected result
                assertEquals("Accessibility off. Did you forget to check that?", ise.getMessage());
            }
        });
    }
}
