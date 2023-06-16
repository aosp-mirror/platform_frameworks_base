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

package android.view.accessibility;

import static junit.framework.TestCase.assertFalse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.drawable.Icon;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.util.IntPair;
import com.android.server.accessibility.test.MessageCapturingHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Tests for the AccessibilityManager by mocking the backing service.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityManagerTest {
    private static final boolean WITH_A11Y_ENABLED = true;
    private static final boolean WITH_A11Y_DISABLED = false;
    private static final String LABEL = "label";
    private static final String INTENT_ACTION = "TESTACTION";
    private static final String DESCRIPTION = "description";
    private static final PendingIntent TEST_PENDING_INTENT = PendingIntent.getBroadcast(
            InstrumentationRegistry.getTargetContext(), 0, new Intent(INTENT_ACTION), 
            PendingIntent.FLAG_IMMUTABLE);
    private static final RemoteAction TEST_ACTION = new RemoteAction(
            Icon.createWithContentUri("content://test"),
            LABEL,
            DESCRIPTION,
            TEST_PENDING_INTENT);
    private static final int DISPLAY_ID = 22;

    @Mock private IAccessibilityManager mMockService;
    private MessageCapturingHandler mHandler;
    private Instrumentation mInstrumentation;
    private int mFocusStrokeWidthDefaultValue;
    private int mFocusColorDefaultValue;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHandler = new MessageCapturingHandler(null);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mFocusStrokeWidthDefaultValue = mInstrumentation.getContext().getResources()
                .getDimensionPixelSize(R.dimen.accessibility_focus_highlight_stroke_width);
        mFocusColorDefaultValue = mInstrumentation.getContext().getResources().getColor(
                R.color.accessibility_focus_highlight_color);
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

        when(mMockService.getFocusStrokeWidth()).thenReturn(mFocusStrokeWidthDefaultValue);
        when(mMockService.getFocusColor()).thenReturn(mFocusColorDefaultValue);

        AccessibilityManager manager =
                new AccessibilityManager(mInstrumentation.getContext(), mHandler, mMockService,
                        UserHandle.USER_CURRENT, true);

        verify(mMockService).addClient(any(IAccessibilityManagerClient.class), anyInt());
        mHandler.setCallback(manager.getCallback());
        mHandler.sendAllMessages();
        return manager;
    }

    @Test
    public void testRemoveManager() throws Exception {
        AccessibilityManager manager = createManager(WITH_A11Y_ENABLED);
        manager.removeClient();
        verify(mMockService).removeClient(manager.getClient(), UserHandle.USER_CURRENT);
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
                .thenReturn(new ParceledListSlice<>(expectedServices));

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
    public void testRegisterSystemAction() throws Exception {
        AccessibilityManager manager = createManager(WITH_A11Y_ENABLED);
        RemoteAction action = new RemoteAction(
                Icon.createWithContentUri("content://test"),
                LABEL,
                DESCRIPTION,
                TEST_PENDING_INTENT);
        final int actionId = 0;
        manager.registerSystemAction(TEST_ACTION, actionId);

        verify(mMockService).registerSystemAction(TEST_ACTION, actionId);
    }

    @Test
    public void testUnregisterSystemAction() throws Exception {
        AccessibilityManager manager = createManager(WITH_A11Y_ENABLED);
        final int actionId = 0;
        manager.unregisterSystemAction(actionId);

        verify(mMockService).unregisterSystemAction(actionId);
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

    @Test
    public void testSetWindowMagnificationConnection() throws Exception {
        AccessibilityManager manager = createManager(WITH_A11Y_ENABLED);
        IWindowMagnificationConnection connection = Mockito.mock(
                IWindowMagnificationConnection.class);

        manager.setWindowMagnificationConnection(connection);

        verify(mMockService).setWindowMagnificationConnection(connection);
    }

    @Test
    public void testGetDefaultValueOfFocusAppearanceData() {
        AccessibilityManager manager =
                new AccessibilityManager(mInstrumentation.getContext(), mHandler, null,
                        UserHandle.USER_CURRENT, false);

        assertEquals(mFocusStrokeWidthDefaultValue,
                manager.getAccessibilityFocusStrokeWidth());
        assertEquals(mFocusColorDefaultValue,
                manager.getAccessibilityFocusColor());
    }

    @Test
    public void testRegisterAccessibilityProxy() throws Exception {
        // Accessibility does not need to be enabled for a proxy to be registered.
        AccessibilityManager manager =
                new AccessibilityManager(mInstrumentation.getContext(), mHandler, mMockService,
                        UserHandle.USER_CURRENT, true);


        ArrayList<AccessibilityServiceInfo> infos = new ArrayList<>();
        infos.add(new AccessibilityServiceInfo());
        AccessibilityDisplayProxy proxy = new MyAccessibilityProxy(DISPLAY_ID, infos);
        manager.registerDisplayProxy(proxy);
        // Cannot access proxy.mServiceClient directly due to visibility.
        verify(mMockService).registerProxyForDisplay(any(IAccessibilityServiceClient.class),
                any(Integer.class));
    }

    @Test
    public void testUnregisterAccessibilityProxy() throws Exception {
        // Accessibility does not need to be enabled for a proxy to be registered.
        final AccessibilityManager manager =
                new AccessibilityManager(mInstrumentation.getContext(), mHandler, mMockService,
                        UserHandle.USER_CURRENT, true);

        final ArrayList<AccessibilityServiceInfo> infos = new ArrayList<>();
        infos.add(new AccessibilityServiceInfo());

        final AccessibilityDisplayProxy proxy = new MyAccessibilityProxy(DISPLAY_ID, infos);
        manager.registerDisplayProxy(proxy);
        manager.unregisterDisplayProxy(proxy);
        verify(mMockService).unregisterProxyForDisplay(proxy.getDisplayId());
    }

    private class MyAccessibilityProxy extends AccessibilityDisplayProxy {
        MyAccessibilityProxy(int displayId,
                @NonNull List<AccessibilityServiceInfo> serviceInfos) {
            super(displayId, Executors.newSingleThreadExecutor(), serviceInfos);
        }

        @Override
        public void onAccessibilityEvent(@NonNull AccessibilityEvent event) {

        }

        @Override
        public void onProxyConnected() {

        }

        @Override
        public void interrupt() {

        }
    }
}
