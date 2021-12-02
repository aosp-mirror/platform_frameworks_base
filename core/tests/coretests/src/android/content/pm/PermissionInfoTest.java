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

package android.content.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public final class PermissionInfoTest {
    private static final String KNOWN_CERT_DIGEST_1 =
            "6a8b96e278e58f62cfe3584022cec1d0527fcb85a9e5d2e1694eb0405be5b599";
    private static final String KNOWN_CERT_DIGEST_2 =
            "9369370ffcfdc1e92dae777252c05c483b8cbb55fa9d5fd9f6317f623ae6d8c6";

    @Test
    public void createFromParcel_returnsKnownCerts() {
        // The platform supports a knownSigner permission protection flag that allows one or more
        // trusted signing certificates to be specified with the permission declaration; if a
        // requesting app is signed by any of these trusted certificates the permission is granted.
        // This test verifies the Set of knownCerts is properly parceled / unparceled.
        PermissionInfo permissionInfo = new PermissionInfo();
        permissionInfo.protectionLevel =
                PermissionInfo.PROTECTION_SIGNATURE | PermissionInfo.PROTECTION_FLAG_KNOWN_SIGNER;
        permissionInfo.knownCerts = new ArraySet<>(2);
        permissionInfo.knownCerts.add(KNOWN_CERT_DIGEST_1);
        permissionInfo.knownCerts.add(KNOWN_CERT_DIGEST_2);
        Parcel parcel = Parcel.obtain();
        permissionInfo.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);
        PermissionInfo unparceledPermissionInfo = PermissionInfo.CREATOR.createFromParcel(parcel);

        assertNotNull(unparceledPermissionInfo.knownCerts);
        assertEquals(2, unparceledPermissionInfo.knownCerts.size());
        assertTrue(unparceledPermissionInfo.knownCerts.contains(KNOWN_CERT_DIGEST_1));
        assertTrue(unparceledPermissionInfo.knownCerts.contains(KNOWN_CERT_DIGEST_2));
    }
}
