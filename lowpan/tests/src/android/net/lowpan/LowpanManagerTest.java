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

package android.net.lowpan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.test.TestLooper;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for android.net.lowpan.LowpanManager. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LowpanManagerTest {
    private static final String TEST_PACKAGE_NAME = "TestPackage";

    @Mock Context mContext;
    @Mock ILowpanManager mLowpanService;
    @Mock ILowpanInterface mLowpanInterfaceService;
    @Mock IBinder mLowpanInterfaceBinder;
    @Mock ApplicationInfo mApplicationInfo;
    @Mock IBinder mAppBinder;
    @Mock LowpanManager.Callback mLowpanManagerCallback;

    private Handler mHandler;
    private final TestLooper mTestLooper = new TestLooper();
    private LowpanManager mLowpanManager;

    private ILowpanManagerListener mManagerListener;
    private LowpanInterface mLowpanInterface;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mContext.getOpPackageName()).thenReturn(TEST_PACKAGE_NAME);

        mLowpanManager = new LowpanManager(mContext, mLowpanService, mTestLooper.getLooper());
    }

    @Test
    public void testGetEmptyInterfaceList() throws Exception {
        when(mLowpanService.getInterfaceList()).thenReturn(new String[0]);
        assertTrue(mLowpanManager.getInterfaceList().length == 0);
        assertTrue(mLowpanManager.getInterface() == null);
    }

    @Test
    public void testGetInterfaceList() throws Exception {
        when(mLowpanService.getInterfaceList()).thenReturn(new String[] {"wpan0"});
        when(mLowpanService.getInterface("wpan0")).thenReturn(mLowpanInterfaceService);
        when(mLowpanInterfaceService.getName()).thenReturn("wpan0");
        when(mLowpanInterfaceService.asBinder()).thenReturn(mLowpanInterfaceBinder);
        assertEquals(mLowpanManager.getInterfaceList().length, 1);

        LowpanInterface iface = mLowpanManager.getInterface();
        assertNotNull(iface);
        assertEquals(iface.getName(), "wpan0");
    }

    @Test
    public void testRegisterCallback() throws Exception {
        when(mLowpanInterfaceService.getName()).thenReturn("wpan0");
        when(mLowpanInterfaceService.asBinder()).thenReturn(mLowpanInterfaceBinder);

        // Register our callback
        mLowpanManager.registerCallback(mLowpanManagerCallback);

        // Verify a listener was added
        verify(mLowpanService)
                .addListener(
                        argThat(
                                listener -> {
                                    mManagerListener = listener;
                                    return listener instanceof ILowpanManagerListener;
                                }));

        // Add an interface
        mManagerListener.onInterfaceAdded(mLowpanInterfaceService);
        mTestLooper.dispatchAll();

        // Verify that the interface was added
        verify(mLowpanManagerCallback)
                .onInterfaceAdded(
                        argThat(
                                iface -> {
                                    mLowpanInterface = iface;
                                    return iface instanceof LowpanInterface;
                                }));
        verifyNoMoreInteractions(mLowpanManagerCallback);

        // This check causes the test to fail with a weird error, but I'm not sure why.
        assertEquals(mLowpanInterface.getService(), mLowpanInterfaceService);

        // Verify that calling getInterface on the LowpanManager object will yield the same
        // LowpanInterface object.
        when(mLowpanService.getInterfaceList()).thenReturn(new String[] {"wpan0"});
        when(mLowpanService.getInterface("wpan0")).thenReturn(mLowpanInterfaceService);
        assertEquals(mLowpanManager.getInterface(), mLowpanInterface);

        // Remove the service
        mManagerListener.onInterfaceRemoved(mLowpanInterfaceService);
        mTestLooper.dispatchAll();

        // Verify that the interface was removed
        verify(mLowpanManagerCallback).onInterfaceRemoved(mLowpanInterface);
    }

    @Test
    public void testUnregisterCallback() throws Exception {
        when(mLowpanInterfaceService.getName()).thenReturn("wpan0");
        when(mLowpanInterfaceService.asBinder()).thenReturn(mLowpanInterfaceBinder);

        // Register our callback
        mLowpanManager.registerCallback(mLowpanManagerCallback);

        // Verify a listener was added
        verify(mLowpanService)
                .addListener(
                        argThat(
                                listener -> {
                                    mManagerListener = listener;
                                    return listener instanceof ILowpanManagerListener;
                                }));

        // Add an interface
        mManagerListener.onInterfaceAdded(mLowpanInterfaceService);
        mTestLooper.dispatchAll();

        // Verify that the interface was added
        verify(mLowpanManagerCallback)
                .onInterfaceAdded(
                        argThat(
                                iface -> {
                                    mLowpanInterface = iface;
                                    return iface instanceof LowpanInterface;
                                }));
        verifyNoMoreInteractions(mLowpanManagerCallback);

        // Unregister our callback
        mLowpanManager.unregisterCallback(mLowpanManagerCallback);

        // Verify the listener was removed
        verify(mLowpanService).removeListener(mManagerListener);

        // Verify that the callback wasn't invoked.
        verifyNoMoreInteractions(mLowpanManagerCallback);
    }
}
