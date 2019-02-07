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
import android.support.test.runner.AndroidJUnit4;
import android.telephony.ims.RcsParticipantQueryParameters;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsParticipantQueryParametersTest {

    @Test
    public void testCanUnparcel() {
        RcsParticipantQueryParameters rcsParticipantQueryParameters =
                new RcsParticipantQueryParameters.Builder()
                        .setAliasLike("%alias_")
                        .setCanonicalAddressLike("_canonical%")
                        .setSortProperty(RcsParticipantQueryParameters.SORT_BY_CANONICAL_ADDRESS)
                        .setSortDirection(true)
                        .setResultLimit(432)
                        .build();


        Parcel parcel = Parcel.obtain();
        rcsParticipantQueryParameters.writeToParcel(parcel,
                rcsParticipantQueryParameters.describeContents());

        parcel.setDataPosition(0);
        rcsParticipantQueryParameters = RcsParticipantQueryParameters.CREATOR.createFromParcel(
                parcel);

        assertThat(rcsParticipantQueryParameters.getAliasLike()).isEqualTo("%alias_");
        assertThat(rcsParticipantQueryParameters.getCanonicalAddressLike()).contains("_canonical%");
        assertThat(rcsParticipantQueryParameters.getLimit()).isEqualTo(432);
        assertThat(rcsParticipantQueryParameters.getSortingProperty()).isEqualTo(
                RcsParticipantQueryParameters.SORT_BY_CANONICAL_ADDRESS);
        assertThat(rcsParticipantQueryParameters.getSortDirection()).isTrue();
    }
}
