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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class KeyDerivationParamsTest {

    private static final byte[] SALT = new byte[] { 0, 1, 2, 3 };

    @Test
    public void createSha256Params_setsAlgorithm() {
        KeyDerivationParams keyDerivationParams = KeyDerivationParams.createSha256Params(SALT);

        assertEquals(KeyDerivationParams.ALGORITHM_SHA256, keyDerivationParams.getAlgorithm());
    }

    @Test
    public void createSha256Params_setsSalt() {
        KeyDerivationParams keyDerivationParams = KeyDerivationParams.createSha256Params(SALT);

        assertArrayEquals(SALT, keyDerivationParams.getSalt());
    }

    @Test
    public void writeToParcel_writesAlgorithm() {
        KeyDerivationParams keyDerivationParams =
                writeToThenReadFromParcel(KeyDerivationParams.createSha256Params(SALT));

        assertEquals(KeyDerivationParams.ALGORITHM_SHA256, keyDerivationParams.getAlgorithm());
    }

    @Test
    public void writeToParcel_writesSalt() {
        KeyDerivationParams keyDerivationParams =
                writeToThenReadFromParcel(KeyDerivationParams.createSha256Params(SALT));

        assertArrayEquals(SALT, keyDerivationParams.getSalt());
    }

    private KeyDerivationParams writeToThenReadFromParcel(KeyDerivationParams params) {
        Parcel parcel = Parcel.obtain();
        params.writeToParcel(parcel, /*flags=*/ 0);
        parcel.setDataPosition(0);
        KeyDerivationParams fromParcel =
                KeyDerivationParams.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return fromParcel;
    }
}
