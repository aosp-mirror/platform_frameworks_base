/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm.verify;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.verify.pkg.VerificationStatus;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VerificationStatusTest {
    private static final boolean TEST_VERIFIED = true;
    private static final int TEST_ASL_STATUS = VerificationStatus.VERIFIER_STATUS_ASL_GOOD;
    private static final String TEST_FAILURE_MESSAGE = "test test";
    private static final String TEST_KEY = "test key";
    private static final String TEST_VALUE = "test value";
    private final PersistableBundle mTestExtras = new PersistableBundle();
    private VerificationStatus mStatus;

    @Before
    public void setUpWithBuilder() {
        mTestExtras.putString(TEST_KEY, TEST_VALUE);
        mStatus = new VerificationStatus.Builder()
                .setAslStatus(TEST_ASL_STATUS)
                .setFailureMessage(TEST_FAILURE_MESSAGE)
                .setVerified(TEST_VERIFIED)
                .build();
    }

    @Test
    public void testGetters() {
        assertThat(mStatus.isVerified()).isEqualTo(TEST_VERIFIED);
        assertThat(mStatus.getAslStatus()).isEqualTo(TEST_ASL_STATUS);
        assertThat(mStatus.getFailureMessage()).isEqualTo(TEST_FAILURE_MESSAGE);
    }

    @Test
    public void testParcel() {
        Parcel parcel = Parcel.obtain();
        mStatus.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        VerificationStatus statusFromParcel = VerificationStatus.CREATOR.createFromParcel(parcel);
        assertThat(statusFromParcel.isVerified()).isEqualTo(TEST_VERIFIED);
        assertThat(statusFromParcel.getAslStatus()).isEqualTo(TEST_ASL_STATUS);
        assertThat(statusFromParcel.getFailureMessage()).isEqualTo(TEST_FAILURE_MESSAGE);
    }
}
