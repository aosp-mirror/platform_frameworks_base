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
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class KeyChainProtectionParamsTest {

    private static final byte[] SALT = new byte[] { 0, 1, 2, 3, 4, 5 };
    private static final byte[] SECRET = new byte[] { 5, 4, 3, 2, 1, 0 };
    private static final int USER_SECRET_TYPE = KeyChainProtectionParams.TYPE_LOCKSCREEN;
    private static final int LOCK_SCREEN_UI_FORMAT = KeyChainProtectionParams.UI_FORMAT_PATTERN;

    @Test
    public void build_setsSecret() {
        assertArrayEquals(SECRET, createTestParams().getSecret());
    }

    @Test
    public void build_setsLockScreenUiFormat() {
        assertEquals(LOCK_SCREEN_UI_FORMAT, createTestParams().getLockScreenUiFormat());
    }

    @Test
    public void build_setsUserSecretType() {
        assertEquals(USER_SECRET_TYPE, createTestParams().getUserSecretType());
    }

    @Test
    public void build_setsKeyDerivationParams() {
        KeyChainProtectionParams protParams = createTestParams();
        KeyDerivationParams keyDerivationParams = protParams.getKeyDerivationParams();

        assertEquals(KeyDerivationParams.ALGORITHM_SHA256, keyDerivationParams.getAlgorithm());
        assertArrayEquals(SALT, keyDerivationParams.getSalt());
    }

    @Test
    public void writeToParcel_writesSecret() {
        KeyChainProtectionParams protParams = writeToThenReadFromParcel(createTestParams());

        assertArrayEquals(SECRET, protParams.getSecret());
    }

    @Test
    public void writeToParcel_writesUserSecretType() {
        KeyChainProtectionParams protParams = writeToThenReadFromParcel(createTestParams());

        assertEquals(USER_SECRET_TYPE, protParams.getUserSecretType());
    }

    @Test
    public void writeToParcel_writesLockScreenUiFormat() {
        KeyChainProtectionParams protParams = writeToThenReadFromParcel(createTestParams());

        assertEquals(LOCK_SCREEN_UI_FORMAT, protParams.getLockScreenUiFormat());
    }

    @Test
    public void writeToParcel_writesKeyDerivationParams() {
        KeyChainProtectionParams protParams = writeToThenReadFromParcel(createTestParams());
        KeyDerivationParams keyDerivationParams = protParams.getKeyDerivationParams();

        assertEquals(KeyDerivationParams.ALGORITHM_SHA256, keyDerivationParams.getAlgorithm());
        assertArrayEquals(SALT, keyDerivationParams.getSalt());
    }

    private KeyChainProtectionParams writeToThenReadFromParcel(KeyChainProtectionParams params) {
        Parcel parcel = Parcel.obtain();
        params.writeToParcel(parcel, /*flags=*/ 0);
        parcel.setDataPosition(0);
        KeyChainProtectionParams fromParcel =
                KeyChainProtectionParams.CREATOR.createFromParcel(parcel);
        parcel.recycle();
        return fromParcel;
    }

    private KeyChainProtectionParams createTestParams() {
        return new KeyChainProtectionParams.Builder()
                .setKeyDerivationParams(KeyDerivationParams.createSha256Params(SALT))
                .setSecret(SECRET)
                .setUserSecretType(USER_SECRET_TYPE)
                .setLockScreenUiFormat(LOCK_SCREEN_UI_FORMAT)
                .build();
    }
}
