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

import static android.telephony.ims.RcsThreadQueryParams.SORT_BY_TIMESTAMP;
import static android.telephony.ims.RcsThreadQueryParams.THREAD_TYPE_GROUP;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.ims.RcsParticipant;
import android.telephony.ims.RcsThreadQueryParams;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsThreadQueryParamsTest {

    @Test
    public void testCanUnparcel() {
        RcsParticipant rcsParticipant = new RcsParticipant(1);
        RcsThreadQueryParams rcsThreadQueryParams = new RcsThreadQueryParams.Builder()
                .setThreadType(THREAD_TYPE_GROUP)
                .setParticipant(rcsParticipant)
                .setResultLimit(50)
                .setSortProperty(SORT_BY_TIMESTAMP)
                .setSortDirection(true)
                .build();

        Parcel parcel = Parcel.obtain();
        rcsThreadQueryParams.writeToParcel(parcel, rcsThreadQueryParams.describeContents());

        parcel.setDataPosition(0);
        rcsThreadQueryParams = RcsThreadQueryParams.CREATOR.createFromParcel(parcel);

        assertThat(rcsThreadQueryParams.getThreadType()).isEqualTo(THREAD_TYPE_GROUP);
        assertThat(rcsThreadQueryParams.getRcsParticipantsIds())
                .contains(rcsParticipant.getId());
        assertThat(rcsThreadQueryParams.getLimit()).isEqualTo(50);
        assertThat(rcsThreadQueryParams.getSortingProperty()).isEqualTo(SORT_BY_TIMESTAMP);
        assertThat(rcsThreadQueryParams.getSortDirection()).isTrue();
    }
}
