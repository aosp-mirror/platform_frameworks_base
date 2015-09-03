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

package com.android.server;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the AccessibilityManager which mocking the backing service.
 */
public class AccessibilityManagerTest extends AndroidTestCase {

    /**
     * Timeout required for pending Binder calls or event processing to
     * complete.
     */
    public static final long TIMEOUT_BINDER_CALL = 50;

    @Mock
    private IAccessibilityManager mMockService;

    @Override
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    private AccessibilityManager createManager(boolean enabled) throws Exception {
        if (enabled) {
            when(mMockService.addClient(any(IAccessibilityManagerClient.class), anyInt()))
                    .thenReturn(AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED);
        } else {
            when(mMockService.addClient(any(IAccessibilityManagerClient.class), anyInt()))
                    .thenReturn(0);
        }

        AccessibilityManager manager =
                new AccessibilityManager(mContext, mMockService, UserHandle.USER_CURRENT);

        verify(mMockService).addClient(any(IAccessibilityManagerClient.class), anyInt());

        return manager;
    }

    @MediumTest
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

    @MediumTest
    public void testInterrupt() throws Exception {
        AccessibilityManager manager = createManager(true);
        manager.interrupt();

        verify(mMockService).interrupt(UserHandle.USER_CURRENT);
    }

    @LargeTest
    public void testIsEnabled() throws Exception {
        // invoke the method under test
        AccessibilityManager manager = createManager(true);
        boolean isEnabledServiceEnabled = manager.isEnabled();

        // check expected result
        assertTrue("Must be enabled since the mock service is enabled", isEnabledServiceEnabled);

        // disable accessibility
        manager.getClient().setState(0);

        // wait for the asynchronous IBinder call to complete
        Thread.sleep(TIMEOUT_BINDER_CALL);

        // invoke the method under test
        boolean isEnabledServcieDisabled = manager.isEnabled();

        // check expected result
        assertFalse("Must be disabled since the mock service is disabled",
                isEnabledServcieDisabled);
    }

    @MediumTest
    public void testSendAccessibilityEvent_AccessibilityEnabled() throws Exception {
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();

        when(mMockService.sendAccessibilityEvent(eq(sentEvent), anyInt()))
                .thenReturn(true  /* should recycle event object */)
                .thenReturn(false /* should not recycle event object */);

        AccessibilityManager manager = createManager(true);
        manager.sendAccessibilityEvent(sentEvent);

        assertSame("The event should be recycled.", sentEvent, AccessibilityEvent.obtain());

        manager.sendAccessibilityEvent(sentEvent);

        assertNotSame("The event should not be recycled.", sentEvent, AccessibilityEvent.obtain());
    }

    @MediumTest
    public void testSendAccessibilityEvent_AccessibilityDisabled() throws Exception {
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();

        AccessibilityManager manager = createManager(false  /* disabled */);

        try {
            manager.sendAccessibilityEvent(sentEvent);
            fail("No accessibility events are sent if accessibility is disabled");
        } catch (IllegalStateException ise) {
            // check expected result
            assertEquals("Accessibility off. Did you forget to check that?", ise.getMessage());
        }
    }
}
