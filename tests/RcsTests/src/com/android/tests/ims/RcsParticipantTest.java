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

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RcsParticipantTest {
    private static final int ID = 123;
    private static final String ALIAS = "alias";
    private static final String CANONICAL_ADDRESS = "+1234567890";

    @Test
    public void testCanUnparcel() {
        RcsParticipant rcsParticipant = new RcsParticipant(ID, CANONICAL_ADDRESS);
        rcsParticipant.setAlias(ALIAS);

        Bundle bundle = new Bundle();
        bundle.putParcelable("Some key", rcsParticipant);
        rcsParticipant = bundle.getParcelable("Some key");

        assertThat(rcsParticipant.getId()).isEqualTo(ID);
        assertThat(rcsParticipant.getAlias()).isEqualTo(ALIAS);
        assertThat(rcsParticipant.getCanonicalAddress()).isEqualTo(CANONICAL_ADDRESS);
    }
}
