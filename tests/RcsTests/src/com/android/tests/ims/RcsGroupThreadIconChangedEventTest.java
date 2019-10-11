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

import android.net.Uri;
import android.os.Parcel;
import android.telephony.ims.RcsGroupThreadIconChangedEvent;
import android.telephony.ims.RcsGroupThreadIconChangedEventDescriptor;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsGroupThreadIconChangedEventTest {

    @Test
    public void testCanUnparcel() {
        int rcsGroupThreadId = 1;
        int rcsParticipantId = 2;
        Uri newIconUri = Uri.parse("content://new_icon");

        RcsGroupThreadIconChangedEventDescriptor iconChangedEventDescriptor =
                new RcsGroupThreadIconChangedEventDescriptor(1234567890, rcsGroupThreadId,
                        rcsParticipantId, newIconUri);

        Parcel parcel = Parcel.obtain();
        iconChangedEventDescriptor.writeToParcel(
                parcel, iconChangedEventDescriptor.describeContents());

        parcel.setDataPosition(0);

        iconChangedEventDescriptor =
                RcsGroupThreadIconChangedEventDescriptor.CREATOR.createFromParcel(parcel);

        RcsGroupThreadIconChangedEvent iconChangedEvent =
                iconChangedEventDescriptor.createRcsEvent(null);

        assertThat(iconChangedEvent.getNewIcon()).isEqualTo(newIconUri);
        assertThat(iconChangedEvent.getRcsGroupThread().getThreadId()).isEqualTo(1);
        assertThat(iconChangedEvent.getOriginatingParticipant().getId()).isEqualTo(2);
        assertThat(iconChangedEvent.getTimestamp()).isEqualTo(1234567890);
    }
}
