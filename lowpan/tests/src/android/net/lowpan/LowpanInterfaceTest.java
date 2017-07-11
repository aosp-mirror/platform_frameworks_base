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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
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
import java.util.Map;
import java.util.HashMap;

/** Unit tests for android.net.lowpan.LowpanInterface. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class LowpanInterfaceTest {
    private static final String TEST_PACKAGE_NAME = "TestPackage";

    @Mock Context mContext;
    @Mock ILowpanInterface mLowpanInterfaceService;
    @Mock IBinder mLowpanInterfaceBinder;
    @Mock ApplicationInfo mApplicationInfo;
    @Mock IBinder mAppBinder;
    @Mock LowpanInterface.Callback mLowpanInterfaceCallback;

    private Handler mHandler;
    private final TestLooper mTestLooper = new TestLooper();
    private ILowpanInterfaceListener mInterfaceListener;
    private LowpanInterface mLowpanInterface;
    private Map<String, Object> mPropertyMap;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getApplicationInfo()).thenReturn(mApplicationInfo);
        when(mContext.getOpPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(mLowpanInterfaceService.getName()).thenReturn("wpan0");
        when(mLowpanInterfaceService.asBinder()).thenReturn(mLowpanInterfaceBinder);

        mLowpanInterface = new LowpanInterface(mContext, mLowpanInterfaceService, mTestLooper.getLooper());
    }

    @Test
    public void testStateChangedCallback() throws Exception {
        // Register our callback
        mLowpanInterface.registerCallback(mLowpanInterfaceCallback);

        // Verify a listener was added
        verify(mLowpanInterfaceService)
                .addListener(
                        argThat(
                                listener -> {
                                    mInterfaceListener = listener;
                                    return listener instanceof ILowpanInterfaceListener;
                                }));

        // Build a changed property map
        Map<String, Object> changedProperties = new HashMap<>();
        LowpanProperties.KEY_INTERFACE_STATE.putInMap(changedProperties, LowpanInterface.STATE_OFFLINE);

        // Change some properties
        mInterfaceListener.onPropertiesChanged(changedProperties);
        mTestLooper.dispatchAll();

        // Verify that the property was changed
        verify(mLowpanInterfaceCallback)
                .onStateChanged(
                        argThat(stateString -> stateString.equals(LowpanInterface.STATE_OFFLINE)));
    }
}
