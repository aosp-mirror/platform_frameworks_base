/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.Parcel;
import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

public class ManifestDigestTest extends AndroidTestCase {
    private static final byte[] MESSAGE_1 = {
            (byte) 0x00, (byte) 0xAA, (byte) 0x55, (byte) 0xFF
    };

    public void testManifestDigest_FromInputStream_Null() {
        assertNull("Attributes were null, so ManifestDigest.fromAttributes should return null",
                ManifestDigest.fromInputStream(null));
    }

    public void testManifestDigest_FromInputStream_ThrowsIoException() {
        InputStream is = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException();
            }
        };

        assertNull("InputStream threw exception, so ManifestDigest should be null",
                ManifestDigest.fromInputStream(is));
    }

    public void testManifestDigest_Equals() throws Exception {
        InputStream is = new ByteArrayInputStream(MESSAGE_1);

        ManifestDigest expected =
                new ManifestDigest(MessageDigest.getInstance("SHA-256").digest(MESSAGE_1));

        ManifestDigest actual = ManifestDigest.fromInputStream(is);
        assertEquals(expected, actual);

        ManifestDigest unexpected = new ManifestDigest(new byte[0]);
        assertFalse(unexpected.equals(actual));
    }

    public void testManifestDigest_Parcel() throws Exception {
        InputStream is = new ByteArrayInputStream(MESSAGE_1);

        ManifestDigest digest = ManifestDigest.fromInputStream(is);

        Parcel p = Parcel.obtain();
        digest.writeToParcel(p, 0);
        p.setDataPosition(0);

        ManifestDigest fromParcel = ManifestDigest.CREATOR.createFromParcel(p);

        assertEquals("ManifestDigest going through parceling should be the same as before: "
                + digest.toString() + " and " + fromParcel.toString(), digest,
                fromParcel);
    }
}
