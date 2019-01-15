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
package com.android.tests.ims;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.ims.RcsParticipant;
import android.telephony.ims.RcsThreadQueryParameters;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class RcsThreadQueryParametersTest {
    private RcsThreadQueryParameters mRcsThreadQueryParameters;
    @Mock RcsParticipant mMockParticipant;

    @Test
    public void testUnparceling() {
        String key = "some key";
        mRcsThreadQueryParameters = RcsThreadQueryParameters.builder()
                .isGroupThread(true)
                .withParticipant(mMockParticipant)
                .limitResultsTo(50)
                .sort(true)
                .build();

        Bundle bundle = new Bundle();
        bundle.putParcelable(key, mRcsThreadQueryParameters);
        mRcsThreadQueryParameters = bundle.getParcelable(key);

        assertThat(mRcsThreadQueryParameters.isGroupThread()).isTrue();
        assertThat(mRcsThreadQueryParameters.getRcsParticipants()).contains(mMockParticipant);
        assertThat(mRcsThreadQueryParameters.getLimit()).isEqualTo(50);
        assertThat(mRcsThreadQueryParameters.isAscending()).isTrue();
    }
}
