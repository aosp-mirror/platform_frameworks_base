/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.security.keystore.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.cert.CertificateException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RecoveryCertPathTest {

    @Test
    public void createRecoveryCertPath_getCertPath_succeeds() throws Exception {
        RecoveryCertPath recoveryCertPath = RecoveryCertPath.createRecoveryCertPath(
                TestData.getThmCertPath());
        assertEquals(TestData.getThmCertPath(), recoveryCertPath.getCertPath());
    }

    @Test
    public void getCertPath_throwsIfCannnotDecode() {
        Parcel parcel = Parcel.obtain();
        parcel.writeByteArray(new byte[]{0, 1, 2, 3});
        parcel.setDataPosition(0);
        RecoveryCertPath recoveryCertPath = RecoveryCertPath.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        try {
            recoveryCertPath.getCertPath();
            fail("Did not throw when attempting to decode invalid cert path");
        } catch (CertificateException e) {
            // Expected
        }
    }

    @Test
    public void writeToParcel_writesCertPath() throws Exception {
        RecoveryCertPath recoveryCertPath =
                writeToThenReadFromParcel(
                        RecoveryCertPath.createRecoveryCertPath(TestData.getThmCertPath()));
        assertEquals(TestData.getThmCertPath(), recoveryCertPath.getCertPath());
    }

    private RecoveryCertPath writeToThenReadFromParcel(RecoveryCertPath recoveryCertPath) {
        Parcel parcel = Parcel.obtain();
        recoveryCertPath.writeToParcel(parcel, /*flags=*/ 0);
        parcel.setDataPosition(0);
        RecoveryCertPath fromParcel = RecoveryCertPath.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return fromParcel;
    }
}
