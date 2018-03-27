/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.policy;

import android.os.HandlerThread;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.SubscriptionInfo;
import android.test.suitebuilder.annotation.SmallTest;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.NetworkController.EmergencyListener;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertEquals;

import static org.mockito.Matchers.eq;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CallbackHandlerTest extends SysuiTestCase {

    private CallbackHandler mHandler;
    private HandlerThread mHandlerThread;

    @Mock
    private EmergencyListener mEmengencyListener;
    @Mock
    private SignalCallback mSignalCallback;

    @Before
    public void setUp() throws Exception {
        mHandlerThread = new HandlerThread("TestThread");
        mHandlerThread.start();
        mHandler = new CallbackHandler(mHandlerThread.getLooper());

        MockitoAnnotations.initMocks(this);
        mHandler.setListening(mEmengencyListener, true);
        mHandler.setListening(mSignalCallback, true);
    }

    @Test
    public void testEmergencyListener() {
        mHandler.setEmergencyCallsOnly(true);
        waitForCallbacks();

        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(mEmengencyListener).setEmergencyCallsOnly(captor.capture());
        assertTrue(captor.getValue());
    }

    @Test
    public void testSignalCallback_setWifiIndicators() {
        boolean enabled = true;
        IconState status = new IconState(true, 0, "");
        IconState qs = new IconState(true, 1, "");
        boolean in = true;
        boolean out = true;
        String description = "Test";
        String secondaryLabel = "Secondary label";
        mHandler.setWifiIndicators(enabled, status, qs, in, out, description, true, secondaryLabel);
        waitForCallbacks();

        ArgumentCaptor<Boolean> enableArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<IconState> statusArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<IconState> qsArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Boolean> inArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> outArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> descArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> isTransient = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> secondary = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mSignalCallback).setWifiIndicators(enableArg.capture(),
                statusArg.capture(), qsArg.capture(), inArg.capture(), outArg.capture(),
                descArg.capture(), isTransient.capture(), secondary.capture());
        assertEquals(enabled, (boolean) enableArg.getValue());
        assertEquals(status, statusArg.getValue());
        assertEquals(qs, qsArg.getValue());
        assertEquals(in, (boolean) inArg.getValue());
        assertEquals(out, (boolean) outArg.getValue());
        assertEquals(description, descArg.getValue());
        assertTrue(isTransient.getValue());
        assertEquals(secondaryLabel, secondary.getValue());
    }

    @Test
    public void testSignalCallback_setMobileDataIndicators() {
        IconState status = new IconState(true, 0, "");
        IconState qs = new IconState(true, 1, "");
        boolean in = true;
        boolean out = true;
        String typeDescription = "Test 1";
        String description = "Test 2";
        int type = TelephonyIcons.ICON_1X;
        int qsType = TelephonyIcons.ICON_1X;
        boolean wide = true;
        int subId = 5;
        boolean roaming = true;
        mHandler.setMobileDataIndicators(status, qs, type, qsType, in, out, typeDescription,
                description, wide, subId, roaming);
        waitForCallbacks();

        ArgumentCaptor<IconState> statusArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<IconState> qsArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> qsTypeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> inArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> outArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> typeContentArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> descArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> wideArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> subIdArg = ArgumentCaptor.forClass(Integer.class);
        Mockito.verify(mSignalCallback).setMobileDataIndicators(statusArg.capture(),
                qsArg.capture(), typeIconArg.capture(), qsTypeIconArg.capture(), inArg.capture(),
                outArg.capture(), typeContentArg.capture(), descArg.capture(), wideArg.capture(),
                subIdArg.capture(), eq(roaming));
        assertEquals(status, statusArg.getValue());
        assertEquals(qs, qsArg.getValue());
        assertEquals(type, (int) typeIconArg.getValue());
        assertEquals(qsType, (int) qsTypeIconArg.getValue());
        assertEquals(in, (boolean) inArg.getValue());
        assertEquals(out, (boolean) outArg.getValue());
        assertEquals(typeDescription, typeContentArg.getValue());
        assertEquals(description, descArg.getValue());
        assertEquals(wide, (boolean) wideArg.getValue());
        assertEquals(subId, (int) subIdArg.getValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSignalCallback_setSubs() {
        List<SubscriptionInfo> subs = new ArrayList<>();
        mHandler.setSubs(subs);
        waitForCallbacks();

        ArgumentCaptor<ArrayList> subsArg = ArgumentCaptor.forClass(ArrayList.class);
        Mockito.verify(mSignalCallback).setSubs(subsArg.capture());
        assertTrue(subs == subsArg.getValue());
    }

    @Test
    public void testSignalCallback_setNoSims() {
        boolean noSims = true;
        boolean simDetected = false;
        mHandler.setNoSims(noSims, simDetected);
        waitForCallbacks();

        Mockito.verify(mSignalCallback).setNoSims(eq(noSims), eq(simDetected));
    }

    @Test
    public void testSignalCallback_setEthernetIndicators() {
        IconState state = new IconState(true, R.drawable.stat_sys_ethernet, "Test Description");
        mHandler.setEthernetIndicators(state);
        waitForCallbacks();

        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        Mockito.verify(mSignalCallback).setEthernetIndicators(iconArg.capture());
        assertEquals(state, iconArg.getValue());
    }

    @Test
    public void testSignalCallback_setIsAirplaneMode() {
        IconState state = new IconState(true, R.drawable.stat_sys_airplane_mode, "Test Description");
        mHandler.setIsAirplaneMode(state);
        waitForCallbacks();

        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        Mockito.verify(mSignalCallback).setIsAirplaneMode(iconArg.capture());
        assertEquals(state, iconArg.getValue());
    }

    private void waitForCallbacks() {
        mHandlerThread.quitSafely();
        try {
            mHandlerThread.join();
        } catch (InterruptedException e) {
        }
    }

}
