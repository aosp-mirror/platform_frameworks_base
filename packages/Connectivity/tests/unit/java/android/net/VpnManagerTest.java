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

package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Intent;
import android.test.mock.MockContext;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.net.VpnProfile;
import com.android.internal.util.MessageUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link VpnManager}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VpnManagerTest {
    private static final String PKG_NAME = "fooPackage";

    private static final String SESSION_NAME_STRING = "testSession";
    private static final String SERVER_ADDR_STRING = "1.2.3.4";
    private static final String IDENTITY_STRING = "Identity";
    private static final byte[] PSK_BYTES = "preSharedKey".getBytes();

    private IVpnManager mMockService;
    private VpnManager mVpnManager;
    private final MockContext mMockContext =
            new MockContext() {
                @Override
                public String getOpPackageName() {
                    return PKG_NAME;
                }
            };

    @Before
    public void setUp() throws Exception {
        mMockService = mock(IVpnManager.class);
        mVpnManager = new VpnManager(mMockContext, mMockService);
    }

    @Test
    public void testProvisionVpnProfilePreconsented() throws Exception {
        final PlatformVpnProfile profile = getPlatformVpnProfile();
        when(mMockService.provisionVpnProfile(any(VpnProfile.class), eq(PKG_NAME)))
                .thenReturn(true);

        // Expect there to be no intent returned, as consent has already been granted.
        assertNull(mVpnManager.provisionVpnProfile(profile));
        verify(mMockService).provisionVpnProfile(eq(profile.toVpnProfile()), eq(PKG_NAME));
    }

    @Test
    public void testProvisionVpnProfileNeedsConsent() throws Exception {
        final PlatformVpnProfile profile = getPlatformVpnProfile();
        when(mMockService.provisionVpnProfile(any(VpnProfile.class), eq(PKG_NAME)))
                .thenReturn(false);

        // Expect intent to be returned, as consent has not already been granted.
        final Intent intent = mVpnManager.provisionVpnProfile(profile);
        assertNotNull(intent);

        final ComponentName expectedComponentName =
                ComponentName.unflattenFromString(
                        "com.android.vpndialogs/com.android.vpndialogs.PlatformVpnConfirmDialog");
        assertEquals(expectedComponentName, intent.getComponent());
        verify(mMockService).provisionVpnProfile(eq(profile.toVpnProfile()), eq(PKG_NAME));
    }

    @Test
    public void testDeleteProvisionedVpnProfile() throws Exception {
        mVpnManager.deleteProvisionedVpnProfile();
        verify(mMockService).deleteVpnProfile(eq(PKG_NAME));
    }

    @Test
    public void testStartProvisionedVpnProfile() throws Exception {
        mVpnManager.startProvisionedVpnProfile();
        verify(mMockService).startVpnProfile(eq(PKG_NAME));
    }

    @Test
    public void testStopProvisionedVpnProfile() throws Exception {
        mVpnManager.stopProvisionedVpnProfile();
        verify(mMockService).stopVpnProfile(eq(PKG_NAME));
    }

    private Ikev2VpnProfile getPlatformVpnProfile() throws Exception {
        return new Ikev2VpnProfile.Builder(SERVER_ADDR_STRING, IDENTITY_STRING)
                .setBypassable(true)
                .setMaxMtu(1300)
                .setMetered(true)
                .setAuthPsk(PSK_BYTES)
                .build();
    }

    @Test
    public void testVpnTypesEqual() throws Exception {
        SparseArray<String> vmVpnTypes = MessageUtils.findMessageNames(
                new Class[] { VpnManager.class }, new String[]{ "TYPE_VPN_" });
        SparseArray<String> nativeVpnType = MessageUtils.findMessageNames(
                new Class[] { NativeVpnType.class }, new String[]{ "" });

        // TYPE_VPN_NONE = -1 is only defined in VpnManager.
        assertEquals(vmVpnTypes.size() - 1, nativeVpnType.size());
        for (int i = VpnManager.TYPE_VPN_SERVICE; i < vmVpnTypes.size(); i++) {
            assertEquals(vmVpnTypes.get(i), "TYPE_VPN_" + nativeVpnType.get(i));
        }
    }
}
