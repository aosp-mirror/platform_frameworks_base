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
import android.telephony.ims.RcsParticipantAliasChangedEvent;
import android.telephony.ims.RcsParticipantAliasChangedEventDescriptor;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsParticipantAliasChangedEventTest {
    private static final String OLD_ALIAS = "old alias";
    private static final String NEW_ALIAS = "new alias";
    private int mParticipantId = 3;

    @Test
    public void testCanUnparcel() {
        RcsParticipantAliasChangedEventDescriptor aliasChangedEventDescriptor =
                new RcsParticipantAliasChangedEventDescriptor(
                        1234567890, mParticipantId, NEW_ALIAS);

        Parcel parcel = Parcel.obtain();
        aliasChangedEventDescriptor.writeToParcel(
                parcel, aliasChangedEventDescriptor.describeContents());

        parcel.setDataPosition(0);

        aliasChangedEventDescriptor = RcsParticipantAliasChangedEventDescriptor.CREATOR
                .createFromParcel(parcel);

        RcsParticipantAliasChangedEvent aliasChangedEvent =
                aliasChangedEventDescriptor.createRcsEvent();

        assertThat(aliasChangedEvent.getParticipant().getId()).isEqualTo(mParticipantId);
        assertThat(aliasChangedEvent.getNewAlias()).isEqualTo(NEW_ALIAS);
        assertThat(aliasChangedEvent.getTimestamp()).isEqualTo(1234567890);
    }
}
