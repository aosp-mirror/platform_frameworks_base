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

import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reportMatcher;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;

import org.easymock.IArgumentMatcher;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

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

    /**
     * The reusable mock {@link IAccessibilityManager}.
     */
    private final IAccessibilityManager mMockServiceInterface =
        createStrictMock(IAccessibilityManager.class);

    @Override
    public void setUp() throws Exception {
        reset(mMockServiceInterface);
    }

    @MediumTest
    public void testGetAccessibilityServiceList() throws Exception {
        // create a list of installed accessibility services the mock service returns
        List<AccessibilityServiceInfo> expectedServices = new ArrayList<AccessibilityServiceInfo>();
        AccessibilityServiceInfo accessibilityServiceInfo = new AccessibilityServiceInfo();
        accessibilityServiceInfo.packageNames = new String[] { "foo.bar" };
        expectedServices.add(accessibilityServiceInfo);

        // configure the mock service behavior
        IAccessibilityManager mockServiceInterface = mMockServiceInterface;
        expect(mockServiceInterface.addClient(anyIAccessibilityManagerClient(),
                UserHandle.USER_OWNER)).andReturn(
                AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED);
        expect(mockServiceInterface.getInstalledAccessibilityServiceList(UserHandle.USER_OWNER))
                .andReturn(expectedServices);
        replay(mockServiceInterface);

        // invoke the method under test
        AccessibilityManager manager = new AccessibilityManager(mContext, mockServiceInterface,
                UserHandle.USER_OWNER);
        List<AccessibilityServiceInfo> receivedServices =
            manager.getInstalledAccessibilityServiceList();

        // check expected result (list equals() compares it contents as well)
        assertEquals("All expected services must be returned", receivedServices, expectedServices);

        // verify the mock service was properly called
        verify(mockServiceInterface);
    }

    @MediumTest
    public void testInterrupt() throws Exception {
        // configure the mock service behavior
        IAccessibilityManager mockServiceInterface = mMockServiceInterface;
        expect(mockServiceInterface.addClient(anyIAccessibilityManagerClient(),
                UserHandle.USER_OWNER)).andReturn(
                        AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED);
        mockServiceInterface.interrupt(UserHandle.USER_OWNER);
        replay(mockServiceInterface);

        // invoke the method under test
        AccessibilityManager manager = new AccessibilityManager(mContext, mockServiceInterface,
                UserHandle.USER_OWNER);
        manager.interrupt();

        // verify the mock service was properly called
        verify(mockServiceInterface);
    }

    @LargeTest
    public void testIsEnabled() throws Exception {
        // configure the mock service behavior
        IAccessibilityManager mockServiceInterface = mMockServiceInterface;
        expect(mockServiceInterface.addClient(anyIAccessibilityManagerClient(),
                UserHandle.USER_OWNER)).andReturn(
                        AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED);
        replay(mockServiceInterface);

        // invoke the method under test
        AccessibilityManager manager = new AccessibilityManager(mContext, mockServiceInterface,
                UserHandle.USER_OWNER);
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

        // verify the mock service was properly called
        verify(mockServiceInterface);
    }

    @MediumTest
    public void testSendAccessibilityEvent_AccessibilityEnabled() throws Exception {
        // create an event to be dispatched
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();

        // configure the mock service behavior
        IAccessibilityManager mockServiceInterface = mMockServiceInterface;
        expect(mockServiceInterface.addClient(anyIAccessibilityManagerClient(),
                UserHandle.USER_OWNER)).andReturn(
                        AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED);
        expect(mockServiceInterface.sendAccessibilityEvent(eqAccessibilityEvent(sentEvent),
                UserHandle.USER_OWNER)).andReturn(true);
        expect(mockServiceInterface.sendAccessibilityEvent(eqAccessibilityEvent(sentEvent),
                UserHandle.USER_OWNER)).andReturn(false);
        replay(mockServiceInterface);

        // invoke the method under test (manager and service in different processes)
        AccessibilityManager manager = new AccessibilityManager(mContext, mockServiceInterface,
                UserHandle.USER_OWNER);
        manager.sendAccessibilityEvent(sentEvent);

        // check expected result
        AccessibilityEvent nextEventDifferentProcesses = AccessibilityEvent.obtain();
        assertSame("The manager and the service are in different processes, so the event must be " +
                "recycled", sentEvent, nextEventDifferentProcesses);

        // invoke the method under test (manager and service in the same process)
        manager.sendAccessibilityEvent(sentEvent);

        // check expected result
        AccessibilityEvent nextEventSameProcess = AccessibilityEvent.obtain();
        assertNotSame("The manager and the service are in the same process, so the event must not" +
                "be recycled", sentEvent, nextEventSameProcess);

        // verify the mock service was properly called
        verify(mockServiceInterface);
    }

    @MediumTest
    public void testSendAccessibilityEvent_AccessibilityDisabled() throws Exception {
        // create an event to be dispatched
        AccessibilityEvent sentEvent = AccessibilityEvent.obtain();

        // configure the mock service behavior
        IAccessibilityManager mockServiceInterface = mMockServiceInterface;
        expect(mockServiceInterface.addClient(anyIAccessibilityManagerClient(),
                UserHandle.USER_OWNER)).andReturn(0);
        replay(mockServiceInterface);

        // invoke the method under test (accessibility disabled)
        AccessibilityManager manager = new AccessibilityManager(mContext, mockServiceInterface,
                UserHandle.USER_OWNER);
        try {
            manager.sendAccessibilityEvent(sentEvent);
            fail("No accessibility events are sent if accessibility is disabled");
        } catch (IllegalStateException ise) {
            // check expected result
            assertEquals("Accessibility off. Did you forget to check that?", ise.getMessage());
        }

        // verify the mock service was properly called
        verify(mockServiceInterface);
    }

    /**
     * Determines if an {@link AccessibilityEvent} passed as a method argument
     * matches expectations.
     *
     * @param matched The event to check.
     * @return True if expectations are matched.
     */
    private static AccessibilityEvent eqAccessibilityEvent(AccessibilityEvent matched) {
        reportMatcher(new AccessibilityEventMather(matched));
        return null;
    }

    /**
     * Determines if an {@link IAccessibilityManagerClient} passed as a method argument
     * matches expectations which in this case are that any instance is accepted.
     *
     * @return <code>null</code>.
     */
    private static IAccessibilityManagerClient anyIAccessibilityManagerClient() {
        reportMatcher(new AnyIAccessibilityManagerClientMather());
        return null;
    }

    /**
     * Matcher for {@link AccessibilityEvent}s.
     */
    private static class AccessibilityEventMather implements IArgumentMatcher {
        private AccessibilityEvent mExpectedEvent;

        public AccessibilityEventMather(AccessibilityEvent expectedEvent) {
            mExpectedEvent = expectedEvent;
        }

        public boolean matches(Object matched) {
            if (!(matched instanceof AccessibilityEvent)) {
                return false;
            }
            AccessibilityEvent receivedEvent = (AccessibilityEvent) matched;
            return mExpectedEvent.getEventType() == receivedEvent.getEventType();
        }

        public void appendTo(StringBuffer buffer) {
            buffer.append("sendAccessibilityEvent()");
            buffer.append(" with event type \"");
            buffer.append(mExpectedEvent.getEventType());
            buffer.append("\"");
        }
    }

    /**
     * Matcher for {@link IAccessibilityManagerClient}s.
     */
    private static class AnyIAccessibilityManagerClientMather implements IArgumentMatcher {
        public boolean matches(Object matched) {
            if (!(matched instanceof IAccessibilityManagerClient)) {
                return false;
            }
            return true;
        }

        public void appendTo(StringBuffer buffer) {
            buffer.append("addClient() with any IAccessibilityManagerClient");
        }
    }
}
