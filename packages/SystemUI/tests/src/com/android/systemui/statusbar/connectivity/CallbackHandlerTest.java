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
package com.android.systemui.statusbar.connectivity;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.eq;

import android.os.HandlerThread;
import android.telephony.SubscriptionInfo;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.settingslib.mobile.TelephonyIcons;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.connectivity.NetworkController.EmergencyListener;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

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
        WifiIndicators indicators = new WifiIndicators(
                enabled, status, qs, in, out, description, true, secondaryLabel);
        mHandler.setWifiIndicators(indicators);
        waitForCallbacks();

        ArgumentCaptor<WifiIndicators> indicatorArg =
                ArgumentCaptor.forClass(WifiIndicators.class);
        Mockito.verify(mSignalCallback).setWifiIndicators(indicatorArg.capture());
        WifiIndicators expected = indicatorArg.getValue();

        assertEquals(enabled, expected.enabled);
        assertEquals(status, expected.statusIcon);
        assertEquals(qs, expected.qsIcon);
        assertEquals(in, expected.activityIn);
        assertEquals(out, expected.activityOut);
        assertEquals(description, expected.description);
        assertTrue(expected.isTransient);
        assertEquals(secondaryLabel, expected.statusLabel);
    }

    @Test
    public void testSignalCallback_setMobileDataIndicators() {
        IconState status = new IconState(true, 0, "");
        IconState qs = new IconState(true, 1, "");
        boolean in = true;
        boolean out = true;
        CharSequence typeDescription = "Test 1";
        CharSequence typeDescriptionHtml = "<b>Test 1</b>";
        CharSequence description = "Test 2";
        int type = TelephonyIcons.ICON_1X;
        int qsType = TelephonyIcons.ICON_1X;
        boolean wide = true;
        int subId = 5;
        boolean roaming = true;
        MobileDataIndicators indicators = new MobileDataIndicators(
                status, qs, type, qsType, in, out, typeDescription,
                typeDescriptionHtml, description, subId, roaming, true);
        mHandler.setMobileDataIndicators(indicators);
        waitForCallbacks();

        ArgumentCaptor<MobileDataIndicators> indicatorArg =
                ArgumentCaptor.forClass(MobileDataIndicators.class);
        Mockito.verify(mSignalCallback).setMobileDataIndicators(indicatorArg.capture());
        MobileDataIndicators expected = indicatorArg.getValue();

        assertEquals(status, expected.statusIcon);
        assertEquals(qs, expected.qsIcon);
        assertEquals(type, expected.statusType);
        assertEquals(qsType, expected.qsType);
        assertEquals(in, expected.activityIn);
        assertEquals(out, expected.activityOut);
        assertEquals(typeDescription, expected.typeContentDescription);
        assertEquals(typeDescriptionHtml, expected.typeContentDescriptionHtml);
        assertEquals(description, expected.qsDescription);
        assertEquals(subId, expected.subId);
        assertTrue(expected.roaming);
        assertTrue(expected.showTriangle);
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
        IconState state =
                new IconState(true, com.android.settingslib.R.drawable.stat_sys_airplane_mode, "Test Description");
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
