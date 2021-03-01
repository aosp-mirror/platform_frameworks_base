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

package com.android.server.accessibility;

import static android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY;
import static android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;
import static android.accessibilityservice.AccessibilityService.KEY_ACCESSIBILITY_SCREENSHOT_STATUS;
import static android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION;
import static android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES;
import static android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS;
import static android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION;
import static android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT;
import static android.accessibilityservice.AccessibilityServiceInfo.DEFAULT;
import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_HAPTIC;
import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
import static android.content.pm.PackageManager.FEATURE_FINGERPRINT;
import static android.view.View.FOCUS_DOWN;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED;
import static android.view.accessibility.AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import static android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT;
import static android.view.accessibility.AccessibilityNodeInfo.ROOT_NODE_ID;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import com.android.server.accessibility.AccessibilityWindowManager.RemoteAccessibilityConnection;
import com.android.server.accessibility.magnification.FullScreenMagnificationController;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.wm.WindowManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Tests for the AbstractAccessibilityServiceConnection
 */
public class AbstractAccessibilityServiceConnectionTest {
    private static final ComponentName COMPONENT_NAME = new ComponentName(
            "com.android.server.accessibility", ".AbstractAccessibilityServiceConnectionTest");
    private static final String PACKAGE_NAME1 = "com.android.server.accessibility1";
    private static final String PACKAGE_NAME2 = "com.android.server.accessibility2";
    private static final String VIEWID_RESOURCE_NAME = "test_viewid_resource_name";
    private static final String VIEW_TEXT = "test_view_text";
    private static final int WINDOWID = 12;
    private static final int PIP_WINDOWID = 13;
    private static final int WINDOWID_ONSECONDDISPLAY = 14;
    private static final int SECONDARY_DISPLAY_ID = Display.DEFAULT_DISPLAY + 1;
    private static final int SERVICE_ID = 42;
    private static final int A11Y_SERVICE_CAPABILITY = CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
            | CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION
            | CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS
            | CAPABILITY_CAN_CONTROL_MAGNIFICATION
            | CAPABILITY_CAN_PERFORM_GESTURES;
    private static final int A11Y_SERVICE_FLAG = DEFAULT
            | FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            | FLAG_REPORT_VIEW_IDS
            | FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            | FLAG_REQUEST_FILTER_KEY_EVENTS
            | FLAG_REQUEST_FINGERPRINT_GESTURES
            | FLAG_REQUEST_ACCESSIBILITY_BUTTON
            | FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
    private static final int USER_ID = 1;
    private static final int USER_ID2 = 2;
    private static final int INTERACTION_ID = 199;
    private static final int PID = Process.myPid();
    private static final long TID = Process.myTid();
    private static final int UID = Process.myUid();

    private AbstractAccessibilityServiceConnection mServiceConnection;
    private MessageCapturingHandler mHandler = new MessageCapturingHandler(null);
    private final List<AccessibilityWindowInfo> mA11yWindowInfos = new ArrayList<>();
    private final List<AccessibilityWindowInfo> mA11yWindowInfosOnSecondDisplay = new ArrayList<>();
    private Callable[] mFindA11yNodesFunctions;
    private Callable<Boolean> mPerformA11yAction;
    private ArrayList<Integer> mDisplayList = new ArrayList<>(Arrays.asList(
            Display.DEFAULT_DISPLAY, SECONDARY_DISPLAY_ID));

    @Mock private Context mMockContext;
    @Mock private IPowerManager mMockIPowerManager;
    @Mock private IThermalService mMockIThermalService;
    @Mock private PackageManager mMockPackageManager;
    @Spy  private AccessibilityServiceInfo mSpyServiceInfo = new AccessibilityServiceInfo();
    @Mock private AccessibilitySecurityPolicy mMockSecurityPolicy;
    @Mock private AccessibilityWindowManager mMockA11yWindowManager;
    @Mock private AbstractAccessibilityServiceConnection.SystemSupport mMockSystemSupport;
    @Mock private WindowManagerInternal mMockWindowManagerInternal;
    @Mock private SystemActionPerformer mMockSystemActionPerformer;
    @Mock private IBinder mMockService;
    @Mock private IAccessibilityServiceClient mMockServiceInterface;
    @Mock private KeyEventDispatcher mMockKeyEventDispatcher;
    @Mock private IAccessibilityInteractionConnection mMockIA11yInteractionConnection;
    @Mock private IAccessibilityInteractionConnectionCallback mMockCallback;
    @Mock private FingerprintGestureDispatcher mMockFingerprintGestureDispatcher;
    @Mock private FullScreenMagnificationController mMockFullScreenMagnificationController;
    @Mock private RemoteCallback.OnResultListener mMockListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mMockSystemSupport.getCurrentUserIdLocked()).thenReturn(USER_ID);
        when(mMockSystemSupport.getKeyEventDispatcher()).thenReturn(mMockKeyEventDispatcher);
        when(mMockSystemSupport.getFingerprintGestureDispatcher())
                .thenReturn(mMockFingerprintGestureDispatcher);
        when(mMockSystemSupport.getFullScreenMagnificationController())
                .thenReturn(mMockFullScreenMagnificationController);

        PowerManager powerManager =
                new PowerManager(mMockContext, mMockIPowerManager, mMockIThermalService, mHandler);
        when(mMockContext.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.hasSystemFeature(FEATURE_FINGERPRINT)).thenReturn(true);

        // Fake a11yWindowInfo and remote a11y connection for tests.
        addA11yWindowInfo(mA11yWindowInfos, WINDOWID, false, Display.DEFAULT_DISPLAY);
        addA11yWindowInfo(mA11yWindowInfos, PIP_WINDOWID, true, Display.DEFAULT_DISPLAY);
        addA11yWindowInfo(mA11yWindowInfosOnSecondDisplay, WINDOWID_ONSECONDDISPLAY, false,
                SECONDARY_DISPLAY_ID);
        when(mMockA11yWindowManager.getDisplayListLocked()).thenReturn(mDisplayList);
        when(mMockA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY))
                .thenReturn(mA11yWindowInfos);
        when(mMockA11yWindowManager.findA11yWindowInfoByIdLocked(WINDOWID))
                .thenReturn(mA11yWindowInfos.get(0));
        when(mMockA11yWindowManager.findA11yWindowInfoByIdLocked(PIP_WINDOWID))
                .thenReturn(mA11yWindowInfos.get(1));
        when(mMockA11yWindowManager.getDisplayIdByUserIdAndWindowIdLocked(USER_ID,
            WINDOWID_ONSECONDDISPLAY)).thenReturn(SECONDARY_DISPLAY_ID);
        when(mMockA11yWindowManager.getWindowListLocked(SECONDARY_DISPLAY_ID))
            .thenReturn(mA11yWindowInfosOnSecondDisplay);
        when(mMockA11yWindowManager.findA11yWindowInfoByIdLocked(WINDOWID_ONSECONDDISPLAY))
            .thenReturn(mA11yWindowInfosOnSecondDisplay.get(0));
        final RemoteAccessibilityConnection conn = getRemoteA11yConnection(
                WINDOWID, mMockIA11yInteractionConnection, PACKAGE_NAME1);
        final RemoteAccessibilityConnection connPip = getRemoteA11yConnection(
                PIP_WINDOWID, mMockIA11yInteractionConnection, PACKAGE_NAME2);
        when(mMockA11yWindowManager.getConnectionLocked(USER_ID, WINDOWID)).thenReturn(conn);
        when(mMockA11yWindowManager.getConnectionLocked(USER_ID, PIP_WINDOWID)).thenReturn(connPip);
        when(mMockA11yWindowManager.getPictureInPictureActionReplacingConnection())
                .thenReturn(connPip);

        // Update a11yServiceInfo to full capability, full flags and target sdk jelly bean
        final ResolveInfo mockResolveInfo = mock(ResolveInfo.class);
        mockResolveInfo.serviceInfo = mock(ServiceInfo.class);
        mockResolveInfo.serviceInfo.applicationInfo = mock(ApplicationInfo.class);
        mockResolveInfo.serviceInfo.applicationInfo.targetSdkVersion =
                Build.VERSION_CODES.JELLY_BEAN;
        doReturn(mockResolveInfo).when(mSpyServiceInfo).getResolveInfo();
        mSpyServiceInfo.setCapabilities(A11Y_SERVICE_CAPABILITY);
        updateServiceInfo(mSpyServiceInfo, 0, 0, A11Y_SERVICE_FLAG, null, 0);

        mServiceConnection = new TestAccessibilityServiceConnection(mMockContext, COMPONENT_NAME,
                mSpyServiceInfo, SERVICE_ID, mHandler, new Object(), mMockSecurityPolicy,
                mMockSystemSupport, mMockWindowManagerInternal, mMockSystemActionPerformer,
                mMockA11yWindowManager);
        // Assume that the service is connected
        mServiceConnection.mService = mMockService;
        mServiceConnection.mServiceInterface = mMockServiceInterface;

        // Update security policy for this service
        when(mMockSecurityPolicy.checkAccessibilityAccess(mServiceConnection)).thenReturn(true);
        when(mMockSecurityPolicy.canRetrieveWindowsLocked(mServiceConnection)).thenReturn(true);
        when(mMockSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                eq(USER_ID), eq(mServiceConnection), anyInt())).thenReturn(true);
        when(mMockSecurityPolicy.canControlMagnification(mServiceConnection)).thenReturn(true);

        // init test functions for accessAccessibilityNodeInfo test case.
        initTestFunctions();
    }

    @Test
    public void getCapabilities() {
        assertThat(mServiceConnection.getCapabilities(), is(A11Y_SERVICE_CAPABILITY));
    }

    @Test
    public void onKeyEvent() throws RemoteException {
        final int sequenceNumber = 100;
        final KeyEvent mockKeyEvent = mock(KeyEvent.class);

        mServiceConnection.onKeyEvent(mockKeyEvent, sequenceNumber);
        verify(mMockServiceInterface).onKeyEvent(mockKeyEvent, sequenceNumber);
    }

    @Test
    public void setServiceInfo_invokeOnClientChange() {
        final AccessibilityServiceInfo serviceInfo = new AccessibilityServiceInfo();
        updateServiceInfo(serviceInfo,
                TYPE_VIEW_CLICKED | TYPE_VIEW_LONG_CLICKED,
                FEEDBACK_SPOKEN | FEEDBACK_HAPTIC,
                A11Y_SERVICE_FLAG,
                new String[] {PACKAGE_NAME1, PACKAGE_NAME2},
                1000);

        mServiceConnection.setServiceInfo(serviceInfo);
        verify(mMockSystemSupport).onClientChangeLocked(true);
    }

    @Test
    public void setServiceInfo_ChangePackageNames_updateSuccess() {
        assertTrue(mServiceConnection.mPackageNames.isEmpty());

        final AccessibilityServiceInfo serviceInfo = new AccessibilityServiceInfo();
        updateServiceInfo(serviceInfo, 0, 0, A11Y_SERVICE_FLAG,
                new String[] {PACKAGE_NAME1, PACKAGE_NAME2},
                1000);

        mServiceConnection.setServiceInfo(serviceInfo);
        assertEquals(serviceInfo.packageNames.length, mServiceConnection.mPackageNames.size());
        assertTrue(mServiceConnection.mPackageNames.containsAll(
                Arrays.asList(mServiceConnection.getServiceInfo().packageNames)));

        updateServiceInfo(serviceInfo, 0, 0, A11Y_SERVICE_FLAG, null, 1000);
        mServiceConnection.setServiceInfo(serviceInfo);
        assertTrue(mServiceConnection.mPackageNames.isEmpty());
    }

    @Test
    public void canReceiveEvents_hasEventType_returnTrue() {
        final AccessibilityServiceInfo serviceInfo = new AccessibilityServiceInfo();
        updateServiceInfo(serviceInfo,
                TYPE_VIEW_CLICKED | TYPE_VIEW_LONG_CLICKED, 0,
                0, null, 0);

        mServiceConnection.setServiceInfo(serviceInfo);
        assertThat(mServiceConnection.canReceiveEventsLocked(), is(true));
    }

    @Test
    public void setOnKeyEventResult() {
        final int sequenceNumber = 100;
        final boolean handled = true;
        mServiceConnection.setOnKeyEventResult(handled, sequenceNumber);

        verify(mMockKeyEventDispatcher).setOnKeyEventResult(
                mServiceConnection, handled, sequenceNumber);
    }

    @Test
    public void getWindows() {
        final AccessibilityWindowInfo.WindowListSparseArray allWindows =
                mServiceConnection.getWindows();

        assertEquals(2, allWindows.size());
        assertThat(allWindows.get(Display.DEFAULT_DISPLAY), is(mA11yWindowInfos));
        assertEquals(2, allWindows.get(Display.DEFAULT_DISPLAY).size());
        assertThat(allWindows.get(SECONDARY_DISPLAY_ID), is(mA11yWindowInfosOnSecondDisplay));
        assertEquals(1, allWindows.get(SECONDARY_DISPLAY_ID).size());
    }

    @Test
    public void getWindows_returnNull() {
        // no canRetrieveWindows, should return null
        when(mMockSecurityPolicy.canRetrieveWindowsLocked(mServiceConnection)).thenReturn(false);
        assertThat(mServiceConnection.getWindows(), is(nullValue()));

        // no checkAccessibilityAccess, should return null
        when(mMockSecurityPolicy.canRetrieveWindowsLocked(mServiceConnection)).thenReturn(true);
        when(mMockSecurityPolicy.checkAccessibilityAccess(mServiceConnection)).thenReturn(false);
        assertThat(mServiceConnection.getWindows(), is(nullValue()));
    }

    @Test
    public void getWindows_notTrackingWindows_invokeOnClientChange() {
        when(mMockA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY)).thenReturn(null);
        when(mMockA11yWindowManager.isTrackingWindowsLocked(Display.DEFAULT_DISPLAY))
                .thenReturn(false);

        mServiceConnection.getWindows();
        verify(mMockSystemSupport).onClientChangeLocked(false);
    }

    @Test
    public void getWindow() {
        assertThat(mServiceConnection.getWindow(WINDOWID), is(mA11yWindowInfos.get(0)));
    }

    @Test
    public void getWindow_returnNull() {
        // no canRetrieveWindows, should return null
        when(mMockSecurityPolicy.canRetrieveWindowsLocked(mServiceConnection)).thenReturn(false);
        assertThat(mServiceConnection.getWindow(WINDOWID), is(nullValue()));

        // no checkAccessibilityAccess, should return null
        when(mMockSecurityPolicy.canRetrieveWindowsLocked(mServiceConnection)).thenReturn(true);
        when(mMockSecurityPolicy.checkAccessibilityAccess(mServiceConnection)).thenReturn(false);
        assertThat(mServiceConnection.getWindow(WINDOWID), is(nullValue()));
    }

    @Test
    public void getWindow_notTrackingWindows_invokeOnClientChange() {
        when(mMockA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY)).thenReturn(null);
        when(mMockA11yWindowManager.isTrackingWindowsLocked(Display.DEFAULT_DISPLAY))
                .thenReturn(false);

        mServiceConnection.getWindow(WINDOWID);
        verify(mMockSystemSupport).onClientChangeLocked(false);
    }

    @Test
    public void getWindow_onNonDefaultDisplay() {
        assertThat(mServiceConnection.getWindow(WINDOWID_ONSECONDDISPLAY),
                is(mA11yWindowInfosOnSecondDisplay.get(0)));
    }

    @Test
    public void accessAccessibilityNodeInfo_whenCantGetInfo_returnNullOrFalse()
            throws Exception {
        when(mMockSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                USER_ID, mServiceConnection, WINDOWID)).thenReturn(false);
        for (int i = 0; i < mFindA11yNodesFunctions.length; i++) {
            assertThat(mFindA11yNodesFunctions[i].call(), is(nullValue()));
        }
        assertThat(mPerformA11yAction.call(), is(false));

        verifyNoMoreInteractions(mMockIA11yInteractionConnection);
        verify(mMockSecurityPolicy, never()).computeValidReportedPackages(any(), anyInt());
    }

    @Test
    public void accessAccessibilityNodeInfo_whenNoA11yAccess_returnNullOrFalse()
            throws Exception {
        when(mMockSecurityPolicy.checkAccessibilityAccess(mServiceConnection)).thenReturn(false);
        for (int i = 0; i < mFindA11yNodesFunctions.length; i++) {
            assertThat(mFindA11yNodesFunctions[i].call(), is(nullValue()));
        }
        assertThat(mPerformA11yAction.call(), is(false));

        verifyNoMoreInteractions(mMockIA11yInteractionConnection);
        verify(mMockSecurityPolicy, never()).computeValidReportedPackages(any(), anyInt());
    }

    @Test
    public void accessAccessibilityNodeInfo_whenNoRemoteA11yConnection_returnNullOrFalse()
            throws Exception {
        when(mMockA11yWindowManager.getConnectionLocked(USER_ID, WINDOWID)).thenReturn(null);
        for (int i = 0; i < mFindA11yNodesFunctions.length; i++) {
            assertThat(mFindA11yNodesFunctions[i].call(), is(nullValue()));
        }
        assertThat(mPerformA11yAction.call(), is(false));

        verifyNoMoreInteractions(mMockIA11yInteractionConnection);
        verify(mMockSecurityPolicy, never()).computeValidReportedPackages(any(), anyInt());
    }

    @Test
    public void findAccessibilityNodeInfosByViewId_withPipWindow_shouldReplaceCallback()
            throws RemoteException {
        final ArgumentCaptor<IAccessibilityInteractionConnectionCallback> captor =
                ArgumentCaptor.forClass(IAccessibilityInteractionConnectionCallback.class);
        mServiceConnection.findAccessibilityNodeInfosByViewId(PIP_WINDOWID, ROOT_NODE_ID,
                VIEWID_RESOURCE_NAME, INTERACTION_ID, mMockCallback, TID);
        verify(mMockIA11yInteractionConnection).findAccessibilityNodeInfosByViewId(
                eq(ROOT_NODE_ID), eq(VIEWID_RESOURCE_NAME), any(), eq(INTERACTION_ID),
                captor.capture(), anyInt(), eq(PID), eq(TID), any());
        verify(mMockSecurityPolicy).computeValidReportedPackages(any(), anyInt());
        verifyReplaceActions(captor.getValue());
    }

    @Test
    public void findAccessibilityNodeInfosByText_withPipWindow_shouldReplaceCallback()
            throws RemoteException {
        final ArgumentCaptor<IAccessibilityInteractionConnectionCallback> captor =
                ArgumentCaptor.forClass(IAccessibilityInteractionConnectionCallback.class);
        mServiceConnection.findAccessibilityNodeInfosByText(PIP_WINDOWID, ROOT_NODE_ID,
                VIEW_TEXT, INTERACTION_ID, mMockCallback, TID);
        verify(mMockIA11yInteractionConnection).findAccessibilityNodeInfosByText(
                eq(ROOT_NODE_ID), eq(VIEW_TEXT), any(), eq(INTERACTION_ID),
                captor.capture(), anyInt(), eq(PID), eq(TID), any());
        verify(mMockSecurityPolicy).computeValidReportedPackages(any(), anyInt());
        verifyReplaceActions(captor.getValue());
    }

    @Test
    public void findAccessibilityNodeInfoByAccessibilityId_withPipWindow_shouldReplaceCallback()
            throws RemoteException {
        final ArgumentCaptor<IAccessibilityInteractionConnectionCallback> captor =
                ArgumentCaptor.forClass(IAccessibilityInteractionConnectionCallback.class);
        mServiceConnection.findAccessibilityNodeInfoByAccessibilityId(PIP_WINDOWID, ROOT_NODE_ID,
                INTERACTION_ID, mMockCallback, 0, TID, null);
        verify(mMockIA11yInteractionConnection).findAccessibilityNodeInfoByAccessibilityId(
                eq(ROOT_NODE_ID), any(), eq(INTERACTION_ID), captor.capture(), anyInt(),
                eq(PID), eq(TID), any(), any());
        verify(mMockSecurityPolicy).computeValidReportedPackages(any(), anyInt());
        verifyReplaceActions(captor.getValue());
    }

    @Test
    public void findFocus_withPipWindow_shouldReplaceCallback()
            throws RemoteException {
        final ArgumentCaptor<IAccessibilityInteractionConnectionCallback> captor =
                ArgumentCaptor.forClass(IAccessibilityInteractionConnectionCallback.class);
        mServiceConnection.findFocus(PIP_WINDOWID, ROOT_NODE_ID, FOCUS_INPUT, INTERACTION_ID,
                mMockCallback, TID);
        verify(mMockIA11yInteractionConnection).findFocus(eq(ROOT_NODE_ID), eq(FOCUS_INPUT),
                any(), eq(INTERACTION_ID), captor.capture(), anyInt(), eq(PID), eq(TID), any());
        verify(mMockSecurityPolicy).computeValidReportedPackages(any(), anyInt());
        verifyReplaceActions(captor.getValue());
    }

    @Test
    public void focusSearch_withPipWindow_shouldReplaceCallback()
            throws RemoteException {
        final ArgumentCaptor<IAccessibilityInteractionConnectionCallback> captor =
                ArgumentCaptor.forClass(IAccessibilityInteractionConnectionCallback.class);
        mServiceConnection.focusSearch(PIP_WINDOWID, ROOT_NODE_ID, FOCUS_DOWN, INTERACTION_ID,
                mMockCallback, TID);
        verify(mMockIA11yInteractionConnection).focusSearch(eq(ROOT_NODE_ID), eq(FOCUS_DOWN),
                any(), eq(INTERACTION_ID), captor.capture(), anyInt(), eq(PID), eq(TID), any());
        verify(mMockSecurityPolicy).computeValidReportedPackages(any(), anyInt());
        verifyReplaceActions(captor.getValue());
    }

    @Test
    public void performAccessibilityAction_withPipWindow_invokeGetPipReplacingConnection()
            throws RemoteException {
        mServiceConnection.performAccessibilityAction(PIP_WINDOWID, ROOT_NODE_ID,
                ACTION_ACCESSIBILITY_FOCUS, null, INTERACTION_ID, mMockCallback, TID);

        verify(mMockIPowerManager).userActivity(eq(Display.DEFAULT_DISPLAY), anyLong(), anyInt(),
                anyInt());
        verify(mMockIA11yInteractionConnection).performAccessibilityAction(eq(ROOT_NODE_ID),
                eq(ACTION_ACCESSIBILITY_FOCUS), any(), eq(INTERACTION_ID), eq(mMockCallback),
                anyInt(), eq(PID), eq(TID));
        verify(mMockA11yWindowManager).getPictureInPictureActionReplacingConnection();
    }

    @Test
    public void performAccessibilityAction_withClick_shouldNotifyOutsideTouch()
            throws RemoteException {
        mServiceConnection.performAccessibilityAction(WINDOWID, ROOT_NODE_ID,
                ACTION_CLICK, null, INTERACTION_ID, mMockCallback, TID);
        mServiceConnection.performAccessibilityAction(PIP_WINDOWID, ROOT_NODE_ID,
                ACTION_LONG_CLICK, null, INTERACTION_ID, mMockCallback, TID);
        verify(mMockA11yWindowManager).notifyOutsideTouch(eq(USER_ID), eq(WINDOWID));
        verify(mMockA11yWindowManager).notifyOutsideTouch(eq(USER_ID), eq(PIP_WINDOWID));
    }

    @Test
    public void performGlobalAction() {
        mServiceConnection.performGlobalAction(GLOBAL_ACTION_HOME);
        verify(mMockSystemActionPerformer).performSystemAction(GLOBAL_ACTION_HOME);
    }

    @Test
    public void getSystemActions() {
        List<AccessibilityNodeInfo.AccessibilityAction> actions =
                mServiceConnection.getSystemActions();
        verify(mMockSystemActionPerformer).getSystemActions();
    }

    @Test
    public void isFingerprintGestureDetectionAvailable_hasFingerPrintSupport_returnTrue() {
        when(mMockFingerprintGestureDispatcher.isFingerprintGestureDetectionAvailable())
                .thenReturn(true);
        final boolean result = mServiceConnection.isFingerprintGestureDetectionAvailable();
        assertThat(result, is(true));
    }

    @Test
    public void isFingerprintGestureDetectionAvailable_noFingerPrintSupport_returnFalse() {
        when(mMockFingerprintGestureDispatcher.isFingerprintGestureDetectionAvailable())
                .thenReturn(true);

        // Return false if device does not support fingerprint
        when(mMockPackageManager.hasSystemFeature(FEATURE_FINGERPRINT)).thenReturn(false);
        boolean result = mServiceConnection.isFingerprintGestureDetectionAvailable();
        assertThat(result, is(false));

        // Return false if service does not have flag
        when(mMockPackageManager.hasSystemFeature(FEATURE_FINGERPRINT)).thenReturn(true);
        mSpyServiceInfo.flags = A11Y_SERVICE_FLAG & ~FLAG_REQUEST_FINGERPRINT_GESTURES;
        mServiceConnection.setServiceInfo(mSpyServiceInfo);
        result = mServiceConnection.isFingerprintGestureDetectionAvailable();
        assertThat(result, is(false));
    }

    @Test
    public void getMagnificationScale() {
        final int displayId = 1;
        final float scale = 2.0f;
        when(mMockFullScreenMagnificationController.getScale(displayId)).thenReturn(scale);

        final float result = mServiceConnection.getMagnificationScale(displayId);
        assertThat(result, is(scale));
    }

    @Test
    public void getMagnificationScale_serviceNotBelongCurrentUser_returnNoScale() {
        final int displayId = 1;
        final float scale = 2.0f;
        when(mMockFullScreenMagnificationController.getScale(displayId)).thenReturn(scale);
        when(mMockSystemSupport.getCurrentUserIdLocked()).thenReturn(USER_ID2);

        final float result = mServiceConnection.getMagnificationScale(displayId);
        assertThat(result, is(1.0f));
    }

    @Test
    public void getMagnificationRegion_notRegistered_shouldRegisterThenUnregister() {
        final int displayId = 1;
        final Region region = new Region(10, 20, 100, 200);
        doAnswer((invocation) -> {
            ((Region) invocation.getArguments()[1]).set(region);
            return null;
        }).when(mMockFullScreenMagnificationController).getMagnificationRegion(eq(displayId),
                any());
        when(mMockFullScreenMagnificationController.isRegistered(displayId)).thenReturn(false);

        final Region result = mServiceConnection.getMagnificationRegion(displayId);
        assertThat(result, is(region));
        verify(mMockFullScreenMagnificationController).register(displayId);
        verify(mMockFullScreenMagnificationController).unregister(displayId);
    }

    @Test
    public void getMagnificationRegion_serviceNotBelongCurrentUser_returnEmptyRegion() {
        final int displayId = 1;
        final Region region = new Region(10, 20, 100, 200);
        doAnswer((invocation) -> {
            ((Region) invocation.getArguments()[1]).set(region);
            return null;
        }).when(mMockFullScreenMagnificationController).getMagnificationRegion(eq(displayId),
                any());
        when(mMockSystemSupport.getCurrentUserIdLocked()).thenReturn(USER_ID2);

        final Region result = mServiceConnection.getMagnificationRegion(displayId);
        assertThat(result.isEmpty(), is(true));
    }

    @Test
    public void getMagnificationCenterX_notRegistered_shouldRegisterThenUnregister() {
        final int displayId = 1;
        final float centerX = 480.0f;
        when(mMockFullScreenMagnificationController.getCenterX(displayId)).thenReturn(centerX);
        when(mMockFullScreenMagnificationController.isRegistered(displayId)).thenReturn(false);

        final float result = mServiceConnection.getMagnificationCenterX(displayId);
        assertThat(result, is(centerX));
        verify(mMockFullScreenMagnificationController).register(displayId);
        verify(mMockFullScreenMagnificationController).unregister(displayId);
    }

    @Test
    public void getMagnificationCenterX_serviceNotBelongCurrentUser_returnZero() {
        final int displayId = 1;
        final float centerX = 480.0f;
        when(mMockFullScreenMagnificationController.getCenterX(displayId)).thenReturn(centerX);
        when(mMockSystemSupport.getCurrentUserIdLocked()).thenReturn(USER_ID2);

        final float result = mServiceConnection.getMagnificationCenterX(displayId);
        assertThat(result, is(0.0f));
    }

    @Test
    public void getMagnificationCenterY_notRegistered_shouldRegisterThenUnregister() {
        final int displayId = 1;
        final float centerY = 640.0f;
        when(mMockFullScreenMagnificationController.getCenterY(displayId)).thenReturn(centerY);
        when(mMockFullScreenMagnificationController.isRegistered(displayId)).thenReturn(false);

        final float result = mServiceConnection.getMagnificationCenterY(displayId);
        assertThat(result, is(centerY));
        verify(mMockFullScreenMagnificationController).register(displayId);
        verify(mMockFullScreenMagnificationController).unregister(displayId);
    }

    @Test
    public void getMagnificationCenterY_serviceNotBelongCurrentUser_returnZero() {
        final int displayId = 1;
        final float centerY = 640.0f;
        when(mMockFullScreenMagnificationController.getCenterY(displayId)).thenReturn(centerY);
        when(mMockSystemSupport.getCurrentUserIdLocked()).thenReturn(USER_ID2);

        final float result = mServiceConnection.getMagnificationCenterY(displayId);
        assertThat(result, is(0.0f));
    }

    @Test
    public void resetMagnification() {
        final int displayId = 1;
        when(mMockFullScreenMagnificationController.reset(displayId, true)).thenReturn(true);

        final boolean result = mServiceConnection.resetMagnification(displayId, true);
        assertThat(result, is(true));
    }

    @Test
    public void resetMagnification_cantControlMagnification_returnFalse() {
        final int displayId = 1;
        when(mMockFullScreenMagnificationController.reset(displayId, true)).thenReturn(true);
        when(mMockSecurityPolicy.canControlMagnification(mServiceConnection)).thenReturn(false);

        final boolean result = mServiceConnection.resetMagnification(displayId, true);
        assertThat(result, is(false));
    }

    @Test
    public void resetMagnification_serviceNotBelongCurrentUser_returnFalse() {
        final int displayId = 1;
        when(mMockFullScreenMagnificationController.reset(displayId, true)).thenReturn(true);
        when(mMockSystemSupport.getCurrentUserIdLocked()).thenReturn(USER_ID2);

        final boolean result = mServiceConnection.resetMagnification(displayId, true);
        assertThat(result, is(false));
    }

    @Test
    public void setMagnificationScaleAndCenter_notRegistered_shouldRegister() {
        final int displayId = 1;
        final float scale = 1.8f;
        final float centerX = 50.5f;
        final float centerY = 100.5f;
        when(mMockFullScreenMagnificationController.setScaleAndCenter(displayId,
                scale, centerX, centerY, true, SERVICE_ID)).thenReturn(true);
        when(mMockFullScreenMagnificationController.isRegistered(displayId)).thenReturn(false);

        final boolean result = mServiceConnection.setMagnificationScaleAndCenter(
                displayId, scale, centerX, centerY, true);
        assertThat(result, is(true));
        verify(mMockFullScreenMagnificationController).register(displayId);
    }

    @Test
    public void setMagnificationScaleAndCenter_cantControlMagnification_returnFalse() {
        final int displayId = 1;
        final float scale = 1.8f;
        final float centerX = 50.5f;
        final float centerY = 100.5f;
        when(mMockFullScreenMagnificationController.setScaleAndCenter(displayId,
                scale, centerX, centerY, true, SERVICE_ID)).thenReturn(true);
        when(mMockSecurityPolicy.canControlMagnification(mServiceConnection)).thenReturn(false);

        final boolean result = mServiceConnection.setMagnificationScaleAndCenter(
                displayId, scale, centerX, centerY, true);
        assertThat(result, is(false));
    }

    @Test
    public void setMagnificationScaleAndCenter_serviceNotBelongCurrentUser_returnFalse() {
        final int displayId = 1;
        final float scale = 1.8f;
        final float centerX = 50.5f;
        final float centerY = 100.5f;
        when(mMockFullScreenMagnificationController.setScaleAndCenter(displayId,
                scale, centerX, centerY, true, SERVICE_ID)).thenReturn(true);
        when(mMockSystemSupport.getCurrentUserIdLocked()).thenReturn(USER_ID2);

        final boolean result = mServiceConnection.setMagnificationScaleAndCenter(
                displayId, scale, centerX, centerY, true);
        assertThat(result, is(false));
    }

    @Test (expected = SecurityException.class)
    public void takeScreenshot_withoutCapability_throwSecurityException() {
        // no canTakeScreenshot, should throw security exception.
        when(mMockSecurityPolicy.canTakeScreenshotLocked(mServiceConnection)).thenReturn(false);
        mServiceConnection.takeScreenshot(Display.DEFAULT_DISPLAY, new RemoteCallback((result) -> {
        }));
    }

    @Test
    public void takeScreenshot_NoA11yAccess_returnErrorCode() throws InterruptedException {
        // no checkAccessibilityAccess, should return error code.
        when(mMockSecurityPolicy.canTakeScreenshotLocked(mServiceConnection)).thenReturn(true);
        when(mMockSecurityPolicy.checkAccessibilityAccess(mServiceConnection)).thenReturn(false);

        mServiceConnection.takeScreenshot(Display.DEFAULT_DISPLAY,
                new RemoteCallback(mMockListener));
        mHandler.sendLastMessage();

        verify(mMockListener).onResult(Mockito.argThat(
                bundle -> ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
                        == bundle.getInt(KEY_ACCESSIBILITY_SCREENSHOT_STATUS)));
    }

    @Test
    public void takeScreenshot_invalidDisplay_returnErrorCode() throws InterruptedException {
        when(mMockSecurityPolicy.canTakeScreenshotLocked(mServiceConnection)).thenReturn(true);
        when(mMockSecurityPolicy.checkAccessibilityAccess(mServiceConnection)).thenReturn(true);

        final DisplayManager displayManager = new DisplayManager(mMockContext);
        when(mMockContext.getSystemService(Context.DISPLAY_SERVICE)).thenReturn(displayManager);

        mServiceConnection.takeScreenshot(Display.DEFAULT_DISPLAY + 1,
                new RemoteCallback(mMockListener));
        mHandler.sendLastMessage();

        verify(mMockListener).onResult(Mockito.argThat(
                bundle -> ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY
                        == bundle.getInt(KEY_ACCESSIBILITY_SCREENSHOT_STATUS)));
    }

    private void updateServiceInfo(AccessibilityServiceInfo serviceInfo, int eventType,
            int feedbackType, int flags, String[] packageNames, int notificationTimeout) {
        serviceInfo.eventTypes = eventType;
        serviceInfo.feedbackType = feedbackType;
        serviceInfo.flags = flags;
        serviceInfo.packageNames = packageNames;
        serviceInfo.notificationTimeout = notificationTimeout;
    }

    private AccessibilityWindowInfo addA11yWindowInfo(List<AccessibilityWindowInfo> infos,
            int windowId, boolean isPip, int displayId) {
        final AccessibilityWindowInfo info = AccessibilityWindowInfo.obtain();
        info.setId(windowId);
        info.setDisplayId(displayId);
        info.setPictureInPicture(isPip);
        infos.add(info);
        return info;
    }

    private RemoteAccessibilityConnection getRemoteA11yConnection(int windowId,
            IAccessibilityInteractionConnection connection,
            String packageName) {
        return mMockA11yWindowManager.new RemoteAccessibilityConnection(
                windowId, connection, packageName, UID, USER_ID);
    }

    private void initTestFunctions() {
        // Init functions for accessibility nodes finding and searching by different filter rules.
        // We group them together for the tests because they have similar implementation.
        mFindA11yNodesFunctions = new Callable[] {
                // findAccessibilityNodeInfosByViewId
                () -> mServiceConnection.findAccessibilityNodeInfosByViewId(WINDOWID,
                        ROOT_NODE_ID, VIEWID_RESOURCE_NAME, INTERACTION_ID,
                        mMockCallback, TID),
                // findAccessibilityNodeInfosByText
                () -> mServiceConnection.findAccessibilityNodeInfosByText(WINDOWID,
                        ROOT_NODE_ID, VIEW_TEXT, INTERACTION_ID, mMockCallback, TID),
                // findAccessibilityNodeInfoByAccessibilityId
                () -> mServiceConnection.findAccessibilityNodeInfoByAccessibilityId(WINDOWID,
                        ROOT_NODE_ID, INTERACTION_ID, mMockCallback, 0, TID, null),
                // findFocus
                () -> mServiceConnection.findFocus(WINDOWID, ROOT_NODE_ID, FOCUS_INPUT,
                        INTERACTION_ID, mMockCallback, TID),
                // focusSearch
                () -> mServiceConnection.focusSearch(WINDOWID, ROOT_NODE_ID, FOCUS_DOWN,
                        INTERACTION_ID, mMockCallback, TID)
        };
        // performAccessibilityAction
        mPerformA11yAction = () ->  mServiceConnection.performAccessibilityAction(WINDOWID,
                ROOT_NODE_ID, ACTION_ACCESSIBILITY_FOCUS, null, INTERACTION_ID,
                mMockCallback, TID);
    }

    private void verifyReplaceActions(IAccessibilityInteractionConnectionCallback replacedCallback)
            throws RemoteException {
        final AccessibilityNodeInfo nodeFromApp = AccessibilityNodeInfo.obtain();
        nodeFromApp.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID, WINDOWID);

        final AccessibilityNodeInfo nodeFromReplacer = AccessibilityNodeInfo.obtain();
        nodeFromReplacer.setSourceNodeId(AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityWindowInfo.PICTURE_IN_PICTURE_ACTION_REPLACER_WINDOW_ID);
        nodeFromReplacer.addAction(AccessibilityAction.ACTION_CLICK);
        nodeFromReplacer.addAction(AccessibilityAction.ACTION_EXPAND);
        final List<AccessibilityNodeInfo> replacerList = Arrays.asList(nodeFromReplacer);

        replacedCallback.setFindAccessibilityNodeInfoResult(nodeFromApp, INTERACTION_ID);
        replacedCallback.setFindAccessibilityNodeInfosResult(replacerList, INTERACTION_ID + 1);

        final ArgumentCaptor<AccessibilityNodeInfo> captor =
                ArgumentCaptor.forClass(AccessibilityNodeInfo.class);
        verify(mMockCallback).setFindAccessibilityNodeInfoResult(captor.capture(),
                eq(INTERACTION_ID));
        assertThat(captor.getValue().getActionList(),
                hasItems(AccessibilityAction.ACTION_CLICK, AccessibilityAction.ACTION_EXPAND));
    }

    private static class TestAccessibilityServiceConnection
            extends AbstractAccessibilityServiceConnection {
        int mResolvedUserId;

        TestAccessibilityServiceConnection(Context context, ComponentName componentName,
                AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler,
                Object lock, AccessibilitySecurityPolicy securityPolicy,
                SystemSupport systemSupport, WindowManagerInternal windowManagerInternal,
                SystemActionPerformer systemActionPerfomer,
                AccessibilityWindowManager a11yWindowManager) {
            super(context, componentName, accessibilityServiceInfo, id, mainHandler, lock,
                    securityPolicy, systemSupport, windowManagerInternal, systemActionPerfomer,
                    a11yWindowManager);
            mResolvedUserId = USER_ID;
        }

        @Override
        protected boolean hasRightsToCurrentUserLocked() {
            return mResolvedUserId == mSystemSupport.getCurrentUserIdLocked();
        }

        @Override
        public void disableSelf() throws RemoteException {}

        @Override
        public boolean setSoftKeyboardShowMode(int showMode) throws RemoteException {
            return false;
        }

        @Override
        public int getSoftKeyboardShowMode() throws RemoteException {
            return 0;
        }

        @Override
        public boolean switchToInputMethod(String imeId) {
            return false;
        }

        @Override
        public boolean isAccessibilityButtonAvailable() throws RemoteException {
            return false;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {}

        @Override
        public void onServiceDisconnected(ComponentName name) {}

        @Override
        public void binderDied() {}

        @Override
        public boolean isCapturingFingerprintGestures() {
            return mCaptureFingerprintGestures;
        }

        @Override
        public void onFingerprintGestureDetectionActiveChanged(boolean active) {}

        @Override
        public void onFingerprintGesture(int gesture) {}
    }
}
