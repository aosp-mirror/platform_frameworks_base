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
import android.support.test.runner.AndroidJUnit4;
import android.telephony.ims.RcsGroupThreadParticipantLeftEvent;
import android.telephony.ims.RcsGroupThreadParticipantLeftEventDescriptor;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsGroupThreadParticipantLeftEventTest {
    @Test
    public void testCanUnparcel() {
        int rcsGroupThreadId = 1;
        int rcsParticipantId = 2;

        RcsGroupThreadParticipantLeftEventDescriptor participantLeftEventDescriptor =
                new RcsGroupThreadParticipantLeftEventDescriptor(
                        1234567890, rcsGroupThreadId, rcsParticipantId, rcsParticipantId);

        Parcel parcel = Parcel.obtain();
        participantLeftEventDescriptor.writeToParcel(
                parcel, participantLeftEventDescriptor.describeContents());

        parcel.setDataPosition(0);

        // create from parcel
        parcel.setDataPosition(0);
        participantLeftEventDescriptor = RcsGroupThreadParticipantLeftEventDescriptor.CREATOR
                .createFromParcel(parcel);

        RcsGroupThreadParticipantLeftEvent participantLeftEvent =
                participantLeftEventDescriptor.createRcsEvent();

        assertThat(participantLeftEvent.getRcsGroupThread().getThreadId()).isEqualTo(1);
        assertThat(participantLeftEvent.getLeavingParticipant().getId()).isEqualTo(2);
        assertThat(participantLeftEvent.getTimestamp()).isEqualTo(1234567890);
    }
}
