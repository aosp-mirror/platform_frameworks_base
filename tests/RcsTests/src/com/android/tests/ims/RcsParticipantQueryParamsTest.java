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

import android.os.Parcel;
import android.telephony.ims.RcsParticipantQueryParams;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsParticipantQueryParamsTest {

    @Test
    public void testCanUnparcel() {
        RcsParticipantQueryParams rcsParticipantQueryParams =
                new RcsParticipantQueryParams.Builder()
                        .setAliasLike("%alias_")
                        .setCanonicalAddressLike("_canonical%")
                        .setSortProperty(RcsParticipantQueryParams.SORT_BY_CANONICAL_ADDRESS)
                        .setSortDirection(true)
                        .setResultLimit(432)
                        .build();


        Parcel parcel = Parcel.obtain();
        rcsParticipantQueryParams.writeToParcel(parcel,
                rcsParticipantQueryParams.describeContents());

        parcel.setDataPosition(0);
        rcsParticipantQueryParams = RcsParticipantQueryParams.CREATOR.createFromParcel(
                parcel);

        assertThat(rcsParticipantQueryParams.getAliasLike()).isEqualTo("%alias_");
        assertThat(rcsParticipantQueryParams.getCanonicalAddressLike()).contains("_canonical%");
        assertThat(rcsParticipantQueryParams.getLimit()).isEqualTo(432);
        assertThat(rcsParticipantQueryParams.getSortingProperty()).isEqualTo(
                RcsParticipantQueryParams.SORT_BY_CANONICAL_ADDRESS);
        assertThat(rcsParticipantQueryParams.getSortDirection()).isTrue();
    }
}
