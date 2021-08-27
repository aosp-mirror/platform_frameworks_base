/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.vcn.persistablebundleutils;

import static org.junit.Assert.assertEquals;

import android.net.ipsec.ike.IkeDerAsn1DnIdentification;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeIdentification;
import android.net.ipsec.ike.IkeIpv4AddrIdentification;
import android.net.ipsec.ike.IkeIpv6AddrIdentification;
import android.net.ipsec.ike.IkeKeyIdIdentification;
import android.net.ipsec.ike.IkeRfc822AddrIdentification;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

import javax.security.auth.x500.X500Principal;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IkeIdentificationUtilsTest {
    private static void verifyPersistableBundleEncodeDecodeIsLossless(IkeIdentification id) {
        final PersistableBundle bundle = IkeIdentificationUtils.toPersistableBundle(id);
        final IkeIdentification result = IkeIdentificationUtils.fromPersistableBundle(bundle);

        assertEquals(result, id);
    }

    @Test
    public void testPersistableBundleEncodeDecodeIpv4AddressId() throws Exception {
        final Inet4Address ipv4Address = (Inet4Address) InetAddress.getByName("192.0.2.100");
        verifyPersistableBundleEncodeDecodeIsLossless(new IkeIpv4AddrIdentification(ipv4Address));
    }

    @Test
    public void testPersistableBundleEncodeDecodeIpv6AddressId() throws Exception {
        final Inet6Address ipv6Address = (Inet6Address) InetAddress.getByName("2001:db8:2::100");
        verifyPersistableBundleEncodeDecodeIsLossless(new IkeIpv6AddrIdentification(ipv6Address));
    }

    @Test
    public void testPersistableBundleEncodeDecodeRfc822AddrId() throws Exception {
        verifyPersistableBundleEncodeDecodeIsLossless(new IkeFqdnIdentification("ike.android.net"));
    }

    @Test
    public void testPersistableBundleEncodeDecodeFqdnId() throws Exception {
        verifyPersistableBundleEncodeDecodeIsLossless(
                new IkeRfc822AddrIdentification("androidike@example.com"));
    }

    @Test
    public void testPersistableBundleEncodeDecodeKeyId() throws Exception {
        verifyPersistableBundleEncodeDecodeIsLossless(
                new IkeKeyIdIdentification("androidIkeKeyId".getBytes()));
    }

    @Test
    public void testPersistableBundleEncodeDecodeDerAsn1DnId() throws Exception {
        verifyPersistableBundleEncodeDecodeIsLossless(
                new IkeDerAsn1DnIdentification(
                        new X500Principal("CN=small.server.test.android.net, O=Android, C=US")));
    }
}
