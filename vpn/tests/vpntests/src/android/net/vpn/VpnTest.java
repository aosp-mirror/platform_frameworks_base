/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.net.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.vpn.L2tpProfile;
import android.net.vpn.L2tpIpsecProfile;
import android.net.vpn.L2tpIpsecPskProfile;
import android.net.vpn.PptpProfile;
import android.net.vpn.VpnManager;
import android.net.vpn.VpnProfile;
import android.net.vpn.VpnState;
import android.net.vpn.VpnType;
import android.os.ConditionVariable;
import android.os.Parcel;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;

/**
 * Unit test class to test VPN api
 * Use the below command to run the vpn unit test only
 * runtest vpntest or
 * adb shell am instrument -e class 'com.android.unit_tests.VpnTest'
 *   -w com.android.unit_tests/android.test.InstrumentationTestRunner
 */
public class VpnTest extends AndroidTestCase {
    private static final String NAME = "a name";
    private static final String SERVER_NAME = "a server name";
    private static final String ID = "some id";
    private static final String SUFFICES = "some suffices";
    private static final String ROUTES = "some routes";
    private static final String SAVED_NAME = "some name";

    @Override
    public void setUp() {
    }

    @Override
    public void tearDown() {
    }

    @SmallTest
    public void testVpnType() {
        testVpnType(VpnType.L2TP);
        testVpnType(VpnType.L2TP_IPSEC);
        testVpnType(VpnType.L2TP_IPSEC_PSK);
        testVpnType(VpnType.PPTP);
    }

    @SmallTest
    public void testVpnProfile() {
        VpnState state = VpnState.CONNECTING;
        testVpnProfile(createTestProfile(state), state);
    }

    @SmallTest
    public void testGetType() {
        assertEquals(VpnType.L2TP, new L2tpProfile().getType());
        assertEquals(VpnType.L2TP_IPSEC, new L2tpIpsecProfile().getType());
        assertEquals(VpnType.L2TP_IPSEC_PSK, 
                new L2tpIpsecPskProfile().getType());
        assertEquals(VpnType.PPTP, new PptpProfile().getType());
    }

    @SmallTest
    public void testVpnTypes() {
        assertTrue(VpnManager.getSupportedVpnTypes().length > 0);
    }

    @SmallTest
    public void testGetTypeFromManager() {
        VpnManager m = new VpnManager(getContext());
        VpnType[] types = VpnManager.getSupportedVpnTypes();
        for (VpnType t : types) {
            assertEquals(t, m.createVpnProfile(t).getType());
        }
    }

    @SmallTest
    public void testParcelable() {
        VpnProfile p = createTestProfile(VpnState.CONNECTED);
        Parcel parcel = Parcel.obtain();
        p.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        // VpnState is transient and not saved in the parcel
        testVpnProfile(VpnProfile.CREATOR.createFromParcel(parcel), null);
    }

    @SmallTest
    public void testReceiver() {
        final String profileName = "whatever";
        final VpnState state = VpnState.DISCONNECTING;
        final ConditionVariable cv = new ConditionVariable();
        cv.close();
        BroadcastReceiver r = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                assertEquals(profileName,
                        i.getStringExtra(VpnManager.BROADCAST_PROFILE_NAME));
                assertEquals(state, i.getSerializableExtra(
                        VpnManager.BROADCAST_CONNECTION_STATE));
                cv.open();
            }
        };

        VpnManager m = new VpnManager(getContext());
        m.registerConnectivityReceiver(r);
        m.broadcastConnectivity(profileName, state);

        // fail it if onReceive() doesn't get executed in 5 sec
        assertTrue(cv.block(5000));
    }

    private void testVpnType(VpnType type) {
        assertFalse(TextUtils.isEmpty(type.getDisplayName()));
        assertNotNull(type.getProfileClass());
    }

    private VpnProfile createTestProfile(VpnState state) {
        VpnProfile p = new L2tpProfile();
        p.setName(NAME);
        p.setServerName(SERVER_NAME);
        p.setId(ID);
        p.setDomainSuffices(SUFFICES);
        p.setRouteList(ROUTES);
        p.setSavedUsername(SAVED_NAME);
        p.setState(state);
        return p;
    }

    private void testVpnProfile(VpnProfile p, VpnState state) {
        assertEquals(NAME, p.getName());
        assertEquals(SERVER_NAME, p.getServerName());
        assertEquals(ID, p.getId());
        assertEquals(SUFFICES, p.getDomainSuffices());
        assertEquals(ROUTES, p.getRouteList());
        assertEquals(SAVED_NAME, p.getSavedUsername());
        if (state != null) assertEquals(state, p.getState());
    }
}
