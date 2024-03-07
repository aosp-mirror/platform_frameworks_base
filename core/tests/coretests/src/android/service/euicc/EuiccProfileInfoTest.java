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

package android.service.euicc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.service.carrier.CarrierIdentifier;
import android.telephony.UiccAccessRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class EuiccProfileInfoTest {
    @Test
    public void testWriteToParcel() {
        EuiccProfileInfo p =
                new EuiccProfileInfo.Builder("21430000000000006587")
                        .setNickname("profile nickname")
                        .setServiceProviderName("service provider")
                        .setProfileName("profile name")
                        .setProfileClass(EuiccProfileInfo.PROFILE_CLASS_OPERATIONAL)
                        .setState(EuiccProfileInfo.PROFILE_STATE_ENABLED)
                        .setCarrierIdentifier(
                                new CarrierIdentifier(
                                        new byte[] {0x23, 0x45, 0x67},
                                        "123",
                                        "45"))
                        .setPolicyRules(EuiccProfileInfo.POLICY_RULE_DO_NOT_DELETE)
                        .setUiccAccessRule(
                                Arrays.asList(new UiccAccessRule(new byte[] {}, "package", 12345L)))
                        .build();

        Parcel parcel = Parcel.obtain();
        assertTrue(parcel != null);
        p.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        EuiccProfileInfo fromParcel = EuiccProfileInfo.CREATOR.createFromParcel(parcel);

        assertEquals(p, fromParcel);
    }

    @Test
    public void testWriteToParcelNullCarrierId() {
        EuiccProfileInfo p =
                new EuiccProfileInfo.Builder("21430000000000006587")
                        .setNickname("profile nickname")
                        .setServiceProviderName("service provider")
                        .setProfileName("profile name")
                        .setProfileClass(EuiccProfileInfo.PROFILE_CLASS_OPERATIONAL)
                        .setState(EuiccProfileInfo.PROFILE_STATE_ENABLED)
                        .setPolicyRules(EuiccProfileInfo.POLICY_RULE_DO_NOT_DELETE)
                        .setUiccAccessRule(
                                Arrays.asList(new UiccAccessRule(new byte[] {}, "package", 12345L))
                        )
                        .build();

        Parcel parcel = Parcel.obtain();
        assertTrue(parcel != null);
        p.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        EuiccProfileInfo fromParcel = EuiccProfileInfo.CREATOR.createFromParcel(parcel);

        assertEquals(p, fromParcel);
    }

    @Test
    public void testBuilderAndGetters() {
        EuiccProfileInfo p =
                new EuiccProfileInfo.Builder("21430000000000006587")
                        .setNickname("profile nickname")
                        .setProfileName("profile name")
                        .setServiceProviderName("service provider")
                        .setCarrierIdentifier(
                                new CarrierIdentifier(
                                        new byte[] {0x23, 0x45, 0x67},
                                        "123",
                                        "45"))
                        .setState(EuiccProfileInfo.PROFILE_STATE_ENABLED)
                        .setProfileClass(EuiccProfileInfo.PROFILE_CLASS_OPERATIONAL)
                        .setPolicyRules(EuiccProfileInfo.POLICY_RULE_DO_NOT_DELETE)
                        .setUiccAccessRule(Arrays.asList(new UiccAccessRule(new byte[0], null, 0)))
                        .build();

        assertEquals("21430000000000006587", p.getIccid());
        assertEquals("profile nickname", p.getNickname());
        assertEquals("profile name", p.getProfileName());
        assertEquals("service provider", p.getServiceProviderName());
        assertEquals("325", p.getCarrierIdentifier().getMcc());
        assertEquals("764", p.getCarrierIdentifier().getMnc());
        assertEquals("123", p.getCarrierIdentifier().getGid1());
        assertEquals("45", p.getCarrierIdentifier().getGid2());
        assertEquals(EuiccProfileInfo.PROFILE_STATE_ENABLED, p.getState());
        assertEquals(EuiccProfileInfo.PROFILE_CLASS_OPERATIONAL, p.getProfileClass());
        assertEquals(EuiccProfileInfo.POLICY_RULE_DO_NOT_DELETE, p.getPolicyRules());
        assertTrue(p.hasPolicyRules());
        assertTrue(p.hasPolicyRule(EuiccProfileInfo.POLICY_RULE_DO_NOT_DELETE));
        assertFalse(p.hasPolicyRule(EuiccProfileInfo.POLICY_RULE_DO_NOT_DISABLE));
        assertArrayEquals(
                new UiccAccessRule[] {new UiccAccessRule(new byte[0], null, 0)},
                p.getUiccAccessRules().toArray());
    }

    @Test
    public void testBuilder_BasedOnAnotherProfile() {
        EuiccProfileInfo p =
                new EuiccProfileInfo.Builder("21430000000000006587")
                        .setNickname("profile nickname")
                        .setProfileName("profile name")
                        .setServiceProviderName("service provider")
                        .setCarrierIdentifier(
                                new CarrierIdentifier(
                                        new byte[] {0x23, 0x45, 0x67},
                                        "123",
                                        "45"))
                        .setState(EuiccProfileInfo.PROFILE_STATE_ENABLED)
                        .setProfileClass(EuiccProfileInfo.PROFILE_CLASS_OPERATIONAL)
                        .setPolicyRules(EuiccProfileInfo.POLICY_RULE_DO_NOT_DELETE)
                        .setUiccAccessRule(
                                Arrays.asList(new UiccAccessRule(new byte[] {}, "package", 12345L)))
                        .build();

        EuiccProfileInfo copied = new EuiccProfileInfo.Builder(p).build();

        assertEquals(p, copied);
        assertEquals(p.hashCode(), copied.hashCode());
    }

    @Test
    public void testBuilder_BasedOnAnotherProfileWithEmptyAccessRules() {
        EuiccProfileInfo p =
                new EuiccProfileInfo.Builder("21430000000000006587")
                        .setNickname("profile nickname")
                        .setProfileName("profile name")
                        .setServiceProviderName("service provider")
                        .setCarrierIdentifier(
                                new CarrierIdentifier(
                                        new byte[] {0x23, 0x45, 0x67},
                                        "123",
                                        "45"))
                        .setState(EuiccProfileInfo.PROFILE_STATE_ENABLED)
                        .setProfileClass(EuiccProfileInfo.PROFILE_CLASS_OPERATIONAL)
                        .setPolicyRules(EuiccProfileInfo.POLICY_RULE_DO_NOT_DELETE)
                        .setUiccAccessRule(null)
                        .build();

        EuiccProfileInfo copied = new EuiccProfileInfo.Builder(p).build();

        assertEquals(null, copied.getUiccAccessRules());
    }

    @Test
    public void testEqualsHashCode() {
        EuiccProfileInfo p =
                new EuiccProfileInfo.Builder("21430000000000006587")
                        .setNickname("profile nickname")
                        .setProfileName("profile name")
                        .setServiceProviderName("service provider")
                        .setCarrierIdentifier(
                                new CarrierIdentifier(
                                        new byte[] {0x23, 0x45, 0x67},
                                        "123",
                                        "45"))
                        .setState(EuiccProfileInfo.PROFILE_STATE_ENABLED)
                        .setProfileClass(EuiccProfileInfo.PROFILE_STATE_ENABLED)
                        .setPolicyRules(EuiccProfileInfo.POLICY_RULE_DO_NOT_DELETE)
                        .setUiccAccessRule(Arrays.asList(new UiccAccessRule(new byte[0], null, 0)))
                        .build();

        assertTrue(p.equals(p));
        assertFalse(p.equals(new Object()));

        EuiccProfileInfo t = null;
        assertFalse(p.equals(t));

        t = new EuiccProfileInfo.Builder(p).setIccid("21").build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());

        t = new EuiccProfileInfo.Builder(p).setNickname(null).build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());

        t = new EuiccProfileInfo.Builder(p).setProfileName(null).build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());

        t = new EuiccProfileInfo.Builder(p).setServiceProviderName(null).build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());

        t = new EuiccProfileInfo.Builder(p).setCarrierIdentifier(null).build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());

        t = new EuiccProfileInfo.Builder(p)
                .setState(EuiccProfileInfo.PROFILE_STATE_DISABLED).build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());

        t = new EuiccProfileInfo.Builder(p)
                .setProfileClass(EuiccProfileInfo.PROFILE_CLASS_TESTING).build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());

        t = new EuiccProfileInfo.Builder(p).setPolicyRules(0).build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());

        t = new EuiccProfileInfo.Builder(p).setUiccAccessRule(null).build();
        assertFalse(p.equals(t));
        assertNotEquals(p.hashCode(), t.hashCode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuilderBuild_IllegalIccid() {
        new EuiccProfileInfo.Builder("abc").build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuilderSetOperatorMccMnc_Illegal() {
        new EuiccProfileInfo.Builder("21430000000000006587")
                .setCarrierIdentifier(new CarrierIdentifier(new byte[] {1, 2, 3, 4}, null, null));
    }

    @Test
    public void testCreatorNewArray() {
        EuiccProfileInfo[] profiles = EuiccProfileInfo.CREATOR.newArray(123);
        assertEquals(123, profiles.length);
    }
}
