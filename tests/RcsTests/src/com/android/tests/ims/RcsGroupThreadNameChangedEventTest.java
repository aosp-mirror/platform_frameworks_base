/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.telephony.ims.RcsGroupThreadNameChangedEvent;
import android.telephony.ims.RcsGroupThreadNameChangedEventDescriptor;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsGroupThreadNameChangedEventTest {
    @Test
    public void testCanUnparcel() {
        String newName = "new name";

        int rcsGroupThreadId = 1;
        int rcsParticipantId = 2;

        RcsGroupThreadNameChangedEventDescriptor nameChangedEventDescriptor =
                new RcsGroupThreadNameChangedEventDescriptor(
                        1234567890, rcsGroupThreadId, rcsParticipantId, newName);

        Parcel parcel = Parcel.obtain();
        nameChangedEventDescriptor.writeToParcel(
                parcel, nameChangedEventDescriptor.describeContents());

        parcel.setDataPosition(0);

        nameChangedEventDescriptor = RcsGroupThreadNameChangedEventDescriptor.CREATOR
                .createFromParcel(parcel);

        RcsGroupThreadNameChangedEvent nameChangedEvent =
                nameChangedEventDescriptor.createRcsEvent(null);

        assertThat(nameChangedEvent.getNewName()).isEqualTo(newName);
        assertThat(nameChangedEvent.getRcsGroupThread().getThreadId()).isEqualTo(1);
        assertThat(nameChangedEvent.getOriginatingParticipant().getId()).isEqualTo(2);
        assertThat(nameChangedEvent.getTimestamp()).isEqualTo(1234567890);
    }
}
