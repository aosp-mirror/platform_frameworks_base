/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.server.accessibility.ProxyAccessibilityServiceConnectionTest.INTERACTIVE_UI_TIMEOUT_100MS;
import static com.android.server.accessibility.ProxyAccessibilityServiceConnectionTest.INTERACTIVE_UI_TIMEOUT_200MS;
import static com.android.server.accessibility.ProxyAccessibilityServiceConnectionTest.NON_INTERACTIVE_UI_TIMEOUT_100MS;
import static com.android.server.accessibility.ProxyAccessibilityServiceConnectionTest.NON_INTERACTIVE_UI_TIMEOUT_200MS;
import static com.android.server.accessibility.ProxyManager.PROXY_COMPONENT_CLASS_NAME;
import static com.android.server.accessibility.ProxyManager.PROXY_COMPONENT_PACKAGE_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.accessibilityservice.MagnificationConfig;
import android.companion.virtual.IVirtualDeviceListener;
import android.companion.virtual.IVirtualDeviceManager;
import android.companion.virtual.VirtualDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Region;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.test.FakePermissionEnforcer;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArraySet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;
import android.view.inputmethod.EditorInfo;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IAccessibilityInputMethodSessionCallback;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.util.IntPair;
import com.android.server.LocalServices;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tests for ProxyManager.
 */
public class ProxyManagerTest {
    private static final int DISPLAY_ID = 1000;
    private static final int DISPLAY_2_ID = 1001;
    private static final int DEVICE_ID = 10;
    private static final int STREAMED_CALLING_UID = 9876;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private Context mMockContext;
    @Mock private AccessibilitySecurityPolicy mMockSecurityPolicy;
    @Mock private AccessibilityWindowManager mMockA11yWindowManager;
    @Mock private ProxyManager.SystemSupport mMockProxySystemSupport;
    @Mock private AbstractAccessibilityServiceConnection.SystemSupport mMockConnectionSystemSupport;
    @Mock private AccessibilityTrace mMockA11yTrace;
    @Mock private WindowManagerInternal mMockWindowManagerInternal;
    @Mock private IAccessibilityServiceClient mMockAccessibilityServiceClient;
    @Mock private IBinder mMockServiceAsBinder;
    @Mock private VirtualDeviceManagerInternal mMockVirtualDeviceManagerInternal;
    @Mock private IVirtualDeviceManager mMockIVirtualDeviceManager;
    FakePermissionEnforcer mFakePermissionEnforcer  = new FakePermissionEnforcer();

    private int mFocusStrokeWidthDefaultValue;
    private int mFocusColorDefaultValue;

    private MessageCapturingHandler mMessageCapturingHandler  = new MessageCapturingHandler(null);
    private ProxyManager mProxyManager;

    @Before
    public void setup() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        final Resources resources = InstrumentationRegistry.getContext().getResources();

        mFocusStrokeWidthDefaultValue =
                resources.getDimensionPixelSize(R.dimen.accessibility_focus_highlight_stroke_width);
        mFocusColorDefaultValue = resources.getColor(R.color.accessibility_focus_highlight_color);
        when(mMockContext.getResources()).thenReturn(resources);
        when(mMockContext.getMainExecutor())
                .thenReturn(InstrumentationRegistry.getTargetContext().getMainExecutor());

        when(mMockContext.getSystemService(Context.PERMISSION_ENFORCER_SERVICE))
                .thenReturn(mFakePermissionEnforcer);
        when(mMockVirtualDeviceManagerInternal.getDeviceIdsForUid(anyInt())).thenReturn(
                new ArraySet(Set.of(DEVICE_ID)));
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal.class);
        LocalServices.addService(VirtualDeviceManagerInternal.class,
                mMockVirtualDeviceManagerInternal);

        when(mMockIVirtualDeviceManager.getDeviceIdForDisplayId(anyInt())).thenReturn(DEVICE_ID);
        final VirtualDeviceManager virtualDeviceManager =
                new VirtualDeviceManager(mMockIVirtualDeviceManager, mMockContext);
        when(mMockContext.getSystemServiceName(VirtualDeviceManager.class)).thenReturn(
                Context.VIRTUAL_DEVICE_SERVICE);
        when(mMockContext.getSystemService(VirtualDeviceManager.class))
                .thenReturn(virtualDeviceManager);

        when(mMockA11yTrace.isA11yTracingEnabled()).thenReturn(false);

        final RemoteCallbackList<IAccessibilityManagerClient> userClients =
                new RemoteCallbackList<>();
        final RemoteCallbackList<IAccessibilityManagerClient> globalClients =
                new RemoteCallbackList<>();
        when(mMockProxySystemSupport.getCurrentUserClientsLocked()).thenReturn(userClients);
        when(mMockProxySystemSupport.getGlobalClientsLocked()).thenReturn(globalClients);

        when(mMockAccessibilityServiceClient.asBinder()).thenReturn(mMockServiceAsBinder);

        mProxyManager = new ProxyManager(new Object(), mMockA11yWindowManager, mMockContext,
                mMessageCapturingHandler, new UiAutomationManager(new Object()),
                mMockProxySystemSupport);
    }

    @After
    public void tearDown() {
        mMessageCapturingHandler.removeAllMessages();
    }

    /**
     * Tests that the proxy’s backing AccessibilityServiceClient is initialized when registering a
     * proxy.
     */
    @Test
    public void registerProxy_always_connectsServiceClient() throws RemoteException {
        registerProxy(DISPLAY_ID);
        verify(mMockAccessibilityServiceClient).init(any(), anyInt(), any());
    }

    /** Tests that unregistering a proxy removes its display from tracking. */
    @Test
    public void unregisterProxy_always_stopsTrackingDisplay() {
        registerProxy(DISPLAY_ID);

        mProxyManager.unregisterProxy(DISPLAY_ID);

        verify(mMockA11yWindowManager).stopTrackingDisplayProxy(DISPLAY_ID);
        assertThat(mProxyManager.isProxyedDisplay(DISPLAY_ID)).isFalse();
    }
    /**
     * Tests that unregistering a proxied display of a virtual device, where that virtual device
     * owned only that one proxied display, removes the device from tracking.
     */
    @Test
    public void unregisterProxy_deviceAssociatedWithSingleDisplay_stopsTrackingDevice() {
        registerProxy(DISPLAY_ID);

        mProxyManager.unregisterProxy(DISPLAY_ID);

        assertThat(mProxyManager.isProxyedDeviceId(DEVICE_ID)).isFalse();
        verify(mMockProxySystemSupport).removeDeviceIdLocked(DEVICE_ID);
    }

    /**
     * Tests that unregistering a proxied display of a virtual device, where that virtual device
     * owns more than one proxied display, does not remove the device from tracking.
     */
    @Test
    public void unregisterProxy_deviceAssociatedWithMultipleDisplays_tracksRemainingProxy() {
        registerProxy(DISPLAY_ID);
        registerProxy(DISPLAY_2_ID);

        mProxyManager.unregisterProxy(DISPLAY_ID);

        assertThat(mProxyManager.isProxyedDeviceId(DEVICE_ID)).isTrue();
        verify(mMockProxySystemSupport, never()).removeDeviceIdLocked(DEVICE_ID);
    }

    /**
     * Tests that changing a proxy, e.g. registering/unregistering a proxy or updating its service
     * info, notifies the apps being streamed and AccessibilityManagerService.
     */
    @Test
    public void testOnProxyChanged_always_propagatesChange() {
        registerProxy(DISPLAY_ID);
        mMessageCapturingHandler.sendAllMessages();

        mProxyManager.onProxyChanged(DEVICE_ID);

        // Messages to notify IAccessibilityManagerClients should be posted.
        assertThat(mMessageCapturingHandler.hasMessages()).isTrue();

        verify(mMockProxySystemSupport).updateWindowsForAccessibilityCallbackLocked();
        verify(mMockProxySystemSupport).notifyClearAccessibilityCacheLocked();
    }

    /**
     * Tests that the manager's AppsOnVirtualDeviceListener implementation propagates the running
     * app changes to the proxy device.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PROXY_USE_APPS_ON_VIRTUAL_DEVICE_LISTENER)
    public void testUpdateProxyOfRunningAppsChange_changedUidIsStreamedApp_propagatesChange() {
        final VirtualDeviceManagerInternal localVdm =
                Mockito.mock(VirtualDeviceManagerInternal.class);
        when(localVdm.getDeviceIdsForUid(anyInt())).thenReturn(new ArraySet(Set.of(DEVICE_ID)));

        mProxyManager.setLocalVirtualDeviceManager(localVdm);
        registerProxy(DISPLAY_ID);
        verify(localVdm).registerAppsOnVirtualDeviceListener(any());

        final ArraySet<Integer> runningUids = new ArraySet(Set.of(STREAMED_CALLING_UID));

        // Flush any existing messages. The messages after this come from onProxyChanged.
        mMessageCapturingHandler.sendAllMessages();

        // The virtual device has been updated with the streamed app's UID, so the proxy is
        // updated.
        mProxyManager.notifyProxyOfRunningAppsChange(runningUids);

        verify(localVdm).getDeviceIdsForUid(STREAMED_CALLING_UID);
        verify(mMockProxySystemSupport).getCurrentUserClientsLocked();
        verify(mMockProxySystemSupport).getGlobalClientsLocked();
        // Messages to notify IAccessibilityManagerClients should be posted.
        assertThat(mMessageCapturingHandler.hasMessages()).isTrue();

        mProxyManager.unregisterProxy(DISPLAY_ID);
        verify(localVdm).unregisterAppsOnVirtualDeviceListener(any());
    }

    /**
     * Tests that getting the first device id for an app uid, such as when an app queries for
     * device-specific state, returns the right device id.
     */
    @Test
    public void testGetFirstDeviceForUid_streamedAppQueriesState_getsHostDeviceId() {
        registerProxy(DISPLAY_ID);
        assertThat(mProxyManager.getFirstDeviceIdForUidLocked(STREAMED_CALLING_UID))
                .isEqualTo(DEVICE_ID);
    }

    /**
     * Tests that the app client state retrieved for a device reflects that touch exploration is
     * enabled since a proxy info has requested touch exploration.
     */
    @Test
    public void testGetClientState_proxyWantsTouchExploration_returnsTouchExplorationEnabled() {
        registerProxy(DISPLAY_ID);

        final AccessibilityServiceInfo secondDisplayInfo = new AccessibilityServiceInfo();
        secondDisplayInfo.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        AccessibilityServiceClientImpl client = new AccessibilityServiceClientImpl(
                secondDisplayInfo);
        registerProxy(DISPLAY_2_ID, client);

        final int deviceClientState = mProxyManager.getStateLocked(DEVICE_ID);
        assertThat((deviceClientState
                & AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED) != 0).isTrue();
    }

    /**
     * Tests that the highest interactive and non-interactive timeout is returned if there are
     * multiple proxied displays belonging to a device.
     */
    @Test
    public void testGetRecommendedTimeout_multipleProxies_returnsHighestTimeout() {
        final AccessibilityServiceInfo firstDisplayInfo = new AccessibilityServiceInfo();
        firstDisplayInfo.setInteractiveUiTimeoutMillis(INTERACTIVE_UI_TIMEOUT_100MS);
        firstDisplayInfo.setNonInteractiveUiTimeoutMillis(NON_INTERACTIVE_UI_TIMEOUT_200MS);

        final AccessibilityServiceInfo secondDisplayInfo = new AccessibilityServiceInfo();
        secondDisplayInfo.setInteractiveUiTimeoutMillis(INTERACTIVE_UI_TIMEOUT_200MS);
        secondDisplayInfo.setNonInteractiveUiTimeoutMillis(NON_INTERACTIVE_UI_TIMEOUT_100MS);

        registerProxy(DISPLAY_ID, new AccessibilityServiceClientImpl(firstDisplayInfo));
        registerProxy(DISPLAY_2_ID, new AccessibilityServiceClientImpl(secondDisplayInfo));

        final long timeout = mProxyManager.getRecommendedTimeoutMillisLocked(DEVICE_ID);
        final int interactiveTimeout = IntPair.first(timeout);
        final int nonInteractiveTimeout = IntPair.second(timeout);

        assertThat(interactiveTimeout).isEqualTo(INTERACTIVE_UI_TIMEOUT_200MS);
        assertThat(nonInteractiveTimeout).isEqualTo(NON_INTERACTIVE_UI_TIMEOUT_200MS);
    }
    /**
     * Tests that getting the installed and enabled services returns the info of the registered
     * proxy. (The component name reflects the display id.)
     */
    @Test
    public void testGetInstalledAndEnabledServices_defaultInfo_returnsInfoForDisplayId() {
        final AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        registerProxy(DISPLAY_ID, new AccessibilityServiceClientImpl(info));
        final List<AccessibilityServiceInfo> installedAndEnabledServices =
                mProxyManager.getInstalledAndEnabledServiceInfosLocked(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK, DEVICE_ID);
        assertThat(installedAndEnabledServices.size()).isEqualTo(1);
        AccessibilityServiceInfo proxyInfo = installedAndEnabledServices.get(0);

        assertThat(proxyInfo.getComponentName()).isEqualTo(new ComponentName(
                PROXY_COMPONENT_PACKAGE_NAME, PROXY_COMPONENT_CLASS_NAME + DISPLAY_ID));
    }

    /**
     * Tests that the app client state retrieved for a device reflects that accessibility is
     * enabled.
     */
    @Test
    public void testGetClientState_always_returnsAccessibilityEnabled() {
        registerProxy(DISPLAY_ID);

        final int deviceClientState = mProxyManager.getStateLocked(DEVICE_ID);
        assertThat((deviceClientState
                & AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED) != 0).isTrue();
    }

    /**
     * Tests that the manager can retrieve interactive windows if a proxy sets
     * AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.
     */
    @Test
    public void testCanRetrieveInteractiveWindows_atLeastOneProxyWantsWindows_returnsTrue() {
        registerProxy(DISPLAY_ID);

        final AccessibilityServiceInfo secondDisplayInfo = new AccessibilityServiceInfo();
        secondDisplayInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        registerProxy(DISPLAY_2_ID, new AccessibilityServiceClientImpl(secondDisplayInfo));

        assertThat(mProxyManager.canRetrieveInteractiveWindowsLocked()).isTrue();
    }

    /**
     * Tests that getting service interfaces to interrupt when AccessibilityManager#interrupt
     * returns the registered proxy interface.
     */
    @Test
    public void testGetServiceInterfacesForInterrupt_defaultProxy_returnsProxyInterface() {
        registerProxy(DISPLAY_ID);
        final List<IAccessibilityServiceClient> interfacesToInterrupt = new ArrayList<>();
        mProxyManager.addServiceInterfacesLocked(interfacesToInterrupt, DEVICE_ID);

        assertThat(interfacesToInterrupt.size()).isEqualTo(1);
        assertThat(interfacesToInterrupt.get(0).asBinder()).isEqualTo(mMockServiceAsBinder);
    }

    /** Tests that the default timeout (0) is returned when the proxy is registered. */
    @Test
    public void getRecommendedTimeout_defaultProxyInfo_getsDefaultTimeout() {
        registerProxy(DISPLAY_ID);
        final long timeout = mProxyManager.getRecommendedTimeoutMillisLocked(DEVICE_ID);
        final int interactiveTimeout = IntPair.first(timeout);
        final int nonInteractiveTimeout = IntPair.second(timeout);

        assertThat(interactiveTimeout).isEqualTo(0);
        assertThat(nonInteractiveTimeout).isEqualTo(0);
    }

    /** Tests that the manager returns the updated timeout when the proxy’s timeout is updated. */
    @Test
    public void getRecommendedTimeout_updateTimeout_getsUpdatedTimeout() {
        registerProxy(DISPLAY_ID);

        mProxyManager.updateTimeoutsIfNeeded(NON_INTERACTIVE_UI_TIMEOUT_100MS,
                INTERACTIVE_UI_TIMEOUT_200MS);

        final long updatedTimeout = mProxyManager.getRecommendedTimeoutMillisLocked(DEVICE_ID);
        final int updatedInteractiveTimeout = IntPair.first(updatedTimeout);
        final int updatedNonInteractiveTimeout = IntPair.second(updatedTimeout);

        assertThat(updatedInteractiveTimeout).isEqualTo(INTERACTIVE_UI_TIMEOUT_200MS);
        assertThat(updatedNonInteractiveTimeout).isEqualTo(NON_INTERACTIVE_UI_TIMEOUT_100MS);
    }

    /** Tests that the system’s default focus color is returned. */
    @Test
    public void testGetFocusColor_defaultProxy_getsDefaultSystemColor() {
        registerProxy(DISPLAY_ID);
        final int focusColor = mProxyManager.getFocusColorLocked(DEVICE_ID);
        assertThat(focusColor).isEqualTo(mFocusColorDefaultValue);
    }

    /** Tests that the system’s default focus stroke width is returned. */
    @Test
    public void testGetFocusStrokeWidth_defaultProxy_getsDefaultSystemWidth() {
        registerProxy(DISPLAY_ID);
        final int focusStrokeWidth = mProxyManager.getFocusStrokeWidthLocked(DEVICE_ID);
        assertThat(focusStrokeWidth).isEqualTo(mFocusStrokeWidthDefaultValue);
    }

    @Test
    public void testRegisterProxy_registersVirtualDeviceListener() throws RemoteException {
        mSetFlagsRule.enableFlags(android.companion.virtual.flags.Flags.FLAG_VDM_PUBLIC_APIS);
        registerProxy(DISPLAY_ID);

        verify(mMockIVirtualDeviceManager, times(1)).registerVirtualDeviceListener(any());
    }

    @Test
    public void testRegisterMultipleProxies_registersOneVirtualDeviceListener()
            throws RemoteException {
        mSetFlagsRule.enableFlags(android.companion.virtual.flags.Flags.FLAG_VDM_PUBLIC_APIS);
        registerProxy(DISPLAY_ID);
        registerProxy(DISPLAY_2_ID);

        verify(mMockIVirtualDeviceManager, times(1)).registerVirtualDeviceListener(any());
    }

    @Test
    public void testUnregisterProxy_unregistersVirtualDeviceListener() throws RemoteException {
        mSetFlagsRule.enableFlags(android.companion.virtual.flags.Flags.FLAG_VDM_PUBLIC_APIS);
        registerProxy(DISPLAY_ID);

        mProxyManager.unregisterProxy(DISPLAY_ID);

        verify(mMockIVirtualDeviceManager, times(1)).unregisterVirtualDeviceListener(any());
    }

    @Test
    public void testUnregisterProxy_onlyUnregistersVirtualDeviceListenerOnLastProxyRemoval()
            throws RemoteException {
        mSetFlagsRule.enableFlags(android.companion.virtual.flags.Flags.FLAG_VDM_PUBLIC_APIS);
        registerProxy(DISPLAY_ID);
        registerProxy(DISPLAY_2_ID);

        mProxyManager.unregisterProxy(DISPLAY_ID);
        verify(mMockIVirtualDeviceManager, never()).unregisterVirtualDeviceListener(any());

        mProxyManager.unregisterProxy(DISPLAY_2_ID);
        verify(mMockIVirtualDeviceManager, times(1)).unregisterVirtualDeviceListener(any());
    }

    @Test
    public void testRegisteredProxy_virtualDeviceClosed_proxyClosed()
            throws RemoteException {
        mSetFlagsRule.enableFlags(android.companion.virtual.flags.Flags.FLAG_VDM_PUBLIC_APIS);
        registerProxy(DISPLAY_ID);

        assertThat(mProxyManager.isProxyedDeviceId(DEVICE_ID)).isTrue();
        assertThat(mProxyManager.isProxyedDisplay(DISPLAY_ID)).isTrue();

        ArgumentCaptor<IVirtualDeviceListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(IVirtualDeviceListener.class);
        verify(mMockIVirtualDeviceManager, times(1))
                .registerVirtualDeviceListener(listenerArgumentCaptor.capture());

        listenerArgumentCaptor.getValue().onVirtualDeviceClosed(DEVICE_ID);

        verify(mMockProxySystemSupport, timeout(5_000)).removeDeviceIdLocked(DEVICE_ID);

        assertThat(mProxyManager.isProxyedDeviceId(DEVICE_ID)).isFalse();
        assertThat(mProxyManager.isProxyedDisplay(DISPLAY_ID)).isFalse();
    }

    @Test
    public void testRegisteredProxy_unrelatedVirtualDeviceClosed_proxyNotClosed()
            throws RemoteException {
        mSetFlagsRule.enableFlags(android.companion.virtual.flags.Flags.FLAG_VDM_PUBLIC_APIS);
        registerProxy(DISPLAY_ID);

        assertThat(mProxyManager.isProxyedDeviceId(DEVICE_ID)).isTrue();
        assertThat(mProxyManager.isProxyedDisplay(DISPLAY_ID)).isTrue();

        ArgumentCaptor<IVirtualDeviceListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(IVirtualDeviceListener.class);
        verify(mMockIVirtualDeviceManager, times(1))
                .registerVirtualDeviceListener(listenerArgumentCaptor.capture());

        listenerArgumentCaptor.getValue().onVirtualDeviceClosed(DEVICE_ID + 1);

        assertThat(mProxyManager.isProxyedDeviceId(DEVICE_ID)).isTrue();
        assertThat(mProxyManager.isProxyedDisplay(DISPLAY_ID)).isTrue();
    }

    @Test
    public void testRegisterProxy_doesNotRegisterVirtualDeviceListener_flagDisabled()
            throws RemoteException {
        mSetFlagsRule.disableFlags(android.companion.virtual.flags.Flags.FLAG_VDM_PUBLIC_APIS);
        registerProxy(DISPLAY_ID);
        mProxyManager.unregisterProxy(DISPLAY_ID);

        verify(mMockIVirtualDeviceManager, never()).registerVirtualDeviceListener(any());
        verify(mMockIVirtualDeviceManager, never()).unregisterVirtualDeviceListener(any());
    }

    private void registerProxy(int displayId) {
        try {
            mProxyManager.registerProxy(mMockAccessibilityServiceClient, displayId, anyInt(),
                    mMockSecurityPolicy, mMockConnectionSystemSupport,
                    mMockA11yTrace, mMockWindowManagerInternal);
        } catch (RemoteException e) {
            fail("Failed to register proxy " + e);
        }
    }

    private void registerProxy(int displayId, AccessibilityServiceClientImpl serviceClient) {
        try {
            mProxyManager.registerProxy(serviceClient, displayId, anyInt(),
                    mMockSecurityPolicy, mMockConnectionSystemSupport,
                    mMockA11yTrace, mMockWindowManagerInternal);
        } catch (RemoteException e) {
            fail("Failed to register proxy " + e);
        }
    }

    /**
     * IAccessibilityServiceClient implementation.
     * A proxy connection does not populate non-default AccessibilityServiceInfo values until the
     * proxy is connected in A11yDisplayProxy#onServiceConnected. For tests that check for
     * non-default values, populate immediately in this testing class, since a real Service is not
     * being used and connected.
     */
    static class AccessibilityServiceClientImpl extends IAccessibilityServiceClient.Stub {
        List<AccessibilityServiceInfo> mInstalledAndEnabledServices;

        AccessibilityServiceClientImpl(AccessibilityServiceInfo
                installedAndEnabledService) {
            mInstalledAndEnabledServices = List.of(installedAndEnabledService);
        }

        @Override
        public void init(IAccessibilityServiceConnection connection, int connectionId,
                IBinder windowToken) throws RemoteException {
            connection.setInstalledAndEnabledServices(mInstalledAndEnabledServices);
        }

        @Override
        public void onAccessibilityEvent(AccessibilityEvent event, boolean serviceWantsEvent)
                throws RemoteException {

        }

        @Override
        public void onInterrupt() throws RemoteException {

        }

        @Override
        public void onGesture(AccessibilityGestureEvent gestureEvent) throws RemoteException {

        }

        @Override
        public void clearAccessibilityCache() throws RemoteException {

        }

        @Override
        public void onKeyEvent(KeyEvent event, int sequence) throws RemoteException {

        }

        @Override
        public void onMagnificationSystemUIConnectionChanged(boolean connected)
                throws RemoteException {

        }

        @Override
        public void onMagnificationChanged(int displayId, Region region, MagnificationConfig config)
                throws RemoteException {

        }

        @Override
        public void onMotionEvent(MotionEvent event) throws RemoteException {

        }

        @Override
        public void onTouchStateChanged(int displayId, int state) throws RemoteException {

        }

        @Override
        public void onSoftKeyboardShowModeChanged(int showMode) throws RemoteException {

        }

        @Override
        public void onPerformGestureResult(int sequence, boolean completedSuccessfully)
                throws RemoteException {

        }

        @Override
        public void onFingerprintCapturingGesturesChanged(boolean capturing)
                throws RemoteException {

        }

        @Override
        public void onFingerprintGesture(int gesture) throws RemoteException {

        }

        @Override
        public void onAccessibilityButtonClicked(int displayId) throws RemoteException {

        }

        @Override
        public void onAccessibilityButtonAvailabilityChanged(boolean available)
                throws RemoteException {

        }

        @Override
        public void onSystemActionsChanged() throws RemoteException {

        }

        @Override
        public void createImeSession(IAccessibilityInputMethodSessionCallback callback)
                throws RemoteException {

        }

        @Override
        public void setImeSessionEnabled(IAccessibilityInputMethodSession session, boolean enabled)
                throws RemoteException {

        }

        @Override
        public void bindInput() throws RemoteException {

        }

        @Override
        public void unbindInput() throws RemoteException {

        }

        @Override
        public void startInput(IRemoteAccessibilityInputConnection connection,
                EditorInfo editorInfo, boolean restarting) throws RemoteException {

        }
    }
}
