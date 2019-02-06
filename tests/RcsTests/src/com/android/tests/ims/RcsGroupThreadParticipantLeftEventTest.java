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
import android.telephony.ims.RcsGroupThread;
import android.telephony.ims.RcsGroupThreadParticipantLeftEvent;
import android.telephony.ims.RcsParticipant;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsGroupThreadParticipantLeftEventTest {
    @Test
    public void testCanUnparcel() {
        RcsGroupThread rcsGroupThread = new RcsGroupThread(1);
        RcsParticipant rcsParticipant = new RcsParticipant(2);

        RcsGroupThreadParticipantLeftEvent participantLeftEvent =
                new RcsGroupThreadParticipantLeftEvent(1234567890, rcsGroupThread, rcsParticipant,
                        rcsParticipant);

        Parcel parcel = Parcel.obtain();
        participantLeftEvent.writeToParcel(parcel, participantLeftEvent.describeContents());

        parcel.setDataPosition(0);

        // create from parcel
        parcel.setDataPosition(0);
        participantLeftEvent = RcsGroupThreadParticipantLeftEvent.CREATOR.createFromParcel(
                parcel);
        assertThat(participantLeftEvent.getRcsGroupThread().getThreadId()).isEqualTo(1);
        assertThat(participantLeftEvent.getLeavingParticipantId().getId()).isEqualTo(2);
        assertThat(participantLeftEvent.getTimestamp()).isEqualTo(1234567890);
    }
}
