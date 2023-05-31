/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.datatransfer.contextsync;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.ComponentName;
import android.platform.test.annotations.Presubmit;
import android.telecom.Call;
import android.telecom.ParcelableCall;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.testing.AndroidTestingRunner;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Set;

@Presubmit
@RunWith(AndroidTestingRunner.class)
public class CrossDeviceCallTest {

    private static final String CALLER_DISPLAY_NAME = "name";
    private static final String CONTACT_DISPLAY_NAME = "contact";
    private final Call.Details mUninitializedCallDetails = createCallDetails(
            /* state= */ -1, /* capabilities= */ 0);

    @Test
    public void updateCallDetails_uninitialized() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        assertWithMessage("Wrong status").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.UNKNOWN_STATUS);
        assertWithMessage("Wrong controls").that(crossDeviceCall.getControls()).isEmpty();
    }

    @Test
    public void updateCallDetails_ringing() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_RINGING,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        assertWithMessage("Wrong status").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.RINGING);
        assertWithMessage("Wrong controls").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.ACCEPT,
                        android.companion.Telecom.REJECT,
                        android.companion.Telecom.SILENCE));
    }

    @Test
    public void updateCallDetails_ongoing() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_ACTIVE,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        assertWithMessage("Wrong status").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.ONGOING);
        assertWithMessage("Wrong controls").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.END,
                        android.companion.Telecom.MUTE,
                        android.companion.Telecom.PUT_ON_HOLD));
    }

    @Test
    public void updateCallDetails_holding() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_HOLDING,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        assertWithMessage("Wrong status").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.ON_HOLD);
        assertWithMessage("Wrong controls").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.END,
                        android.companion.Telecom.TAKE_OFF_HOLD));
    }

    @Test
    public void updateCallDetails_cannotHold() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, Call.Details.CAPABILITY_MUTE));
        assertWithMessage("Wrong status").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.ONGOING);
        assertWithMessage("Wrong controls").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.END,
                        android.companion.Telecom.MUTE));
    }

    @Test
    public void updateCallDetails_cannotMute() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, Call.Details.CAPABILITY_HOLD));
        assertWithMessage("Wrong status").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.ONGOING);
        assertWithMessage("Wrong controls").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.END,
                        android.companion.Telecom.PUT_ON_HOLD));
    }

    @Test
    public void updateCallDetails_transitionRingingToOngoing() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_RINGING,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        assertWithMessage("Wrong status for ringing state").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.RINGING);
        assertWithMessage("Wrong controls for ringing state").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.ACCEPT,
                        android.companion.Telecom.REJECT,
                        android.companion.Telecom.SILENCE));
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_ACTIVE,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        assertWithMessage("Wrong status for active state").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.ONGOING);
        assertWithMessage("Wrong controls for active state").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.END,
                        android.companion.Telecom.MUTE,
                        android.companion.Telecom.PUT_ON_HOLD));
    }

    @Test
    public void updateCallDetails_transitionDialingToOngoing() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_DIALING,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        assertWithMessage("Wrong status for dialing state").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.DIALING);
        assertWithMessage("Wrong controls for dialing state").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.END));
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_ACTIVE,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        assertWithMessage("Wrong status for active state").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.ONGOING);
        assertWithMessage("Wrong controls for active state").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.END,
                        android.companion.Telecom.MUTE,
                        android.companion.Telecom.PUT_ON_HOLD));
    }

    @Test
    public void updateSilencedIfRinging_ringing_silenced() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_RINGING,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        crossDeviceCall.updateSilencedIfRinging();
        assertWithMessage("Wrong status").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.RINGING_SILENCED);
        assertWithMessage("Wrong controls").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.ACCEPT,
                        android.companion.Telecom.REJECT));
    }

    @Test
    public void updateSilencedIfRinging_notRinging_notSilenced() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.updateCallDetails(createCallDetails(Call.STATE_ACTIVE,
                Call.Details.CAPABILITY_HOLD | Call.Details.CAPABILITY_MUTE));
        crossDeviceCall.updateSilencedIfRinging();
        assertWithMessage("Wrong status").that(crossDeviceCall.getStatus())
                .isEqualTo(android.companion.Telecom.Call.ONGOING);
        assertWithMessage("Wrong controls").that(crossDeviceCall.getControls())
                .isEqualTo(Set.of(android.companion.Telecom.END,
                        android.companion.Telecom.MUTE,
                        android.companion.Telecom.PUT_ON_HOLD));
    }

    @Test
    public void getReadableCallerId_enterpriseCall_adminBlocked_hasContact() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.mIsEnterprise = true;
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, /* capabilities= */ 0));

        final String result = crossDeviceCall.getReadableCallerId(true);

        assertWithMessage("Wrong caller id").that(result)
                .isEqualTo(CALLER_DISPLAY_NAME);
    }

    @Test
    public void getReadableCallerId_enterpriseCall_adminUnblocked_hasContact() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.mIsEnterprise = true;
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, /* capabilities= */ 0));

        final String result = crossDeviceCall.getReadableCallerId(false);

        assertWithMessage("Wrong caller id").that(result)
                .isEqualTo(CONTACT_DISPLAY_NAME);
    }

    @Test
    public void getReadableCallerId_enterpriseCall_adminBlocked_noContact() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.mIsEnterprise = true;
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, /* capabilities= */ 0, /* hasContactName= */
                        false));

        final String result = crossDeviceCall.getReadableCallerId(true);

        assertWithMessage("Wrong caller id").that(result)
                .isEqualTo(CALLER_DISPLAY_NAME);
    }

    @Test
    public void getReadableCallerId_nonEnterpriseCall_adminBlocked_noContact() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.mIsEnterprise = false;
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, /* capabilities= */ 0, /* hasContactName= */
                        false));

        final String result = crossDeviceCall.getReadableCallerId(true);

        assertWithMessage("Wrong caller id").that(result)
                .isEqualTo(CALLER_DISPLAY_NAME);
    }

    @Test
    public void getReadableCallerId_nonEnterpriseCall_adminUnblocked_noContact() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.mIsEnterprise = false;
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, /* capabilities= */ 0, /* hasContactName= */
                        false));

        final String result = crossDeviceCall.getReadableCallerId(false);

        assertWithMessage("Wrong caller id").that(result)
                .isEqualTo(CALLER_DISPLAY_NAME);
    }

    @Test
    public void getReadableCallerId_nonEnterpriseCall_adminBlocked_hasContact() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.mIsEnterprise = false;
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, /* capabilities= */ 0));

        final String result = crossDeviceCall.getReadableCallerId(true);

        assertWithMessage("Wrong caller id").that(result)
                .isEqualTo(CONTACT_DISPLAY_NAME);
    }

    @Test
    public void getReadableCallerId_nonEnterpriseCall_adminUnblocked_hasContact() {
        final CrossDeviceCall crossDeviceCall = new CrossDeviceCall(
                InstrumentationRegistry.getTargetContext(),
                mUninitializedCallDetails, /* callAudioState= */ null);
        crossDeviceCall.mIsEnterprise = false;
        crossDeviceCall.updateCallDetails(
                createCallDetails(Call.STATE_ACTIVE, /* capabilities= */ 0));

        final String result = crossDeviceCall.getReadableCallerId(false);

        assertWithMessage("Wrong caller id").that(result)
                .isEqualTo(CONTACT_DISPLAY_NAME);
    }

    private Call.Details createCallDetails(int state, int capabilities) {
        return createCallDetails(state, capabilities, /* hasContactName= */ true);
    }

    private Call.Details createCallDetails(int state, int capabilities, boolean hasContactName) {
        final ParcelableCall.ParcelableCallBuilder parcelableCallBuilder =
                new ParcelableCall.ParcelableCallBuilder();
        parcelableCallBuilder.setCallerDisplayName(CALLER_DISPLAY_NAME);
        if (hasContactName) {
            parcelableCallBuilder.setContactDisplayName(CONTACT_DISPLAY_NAME);
        }
        parcelableCallBuilder.setCallerDisplayNamePresentation(TelecomManager.PRESENTATION_ALLOWED);
        parcelableCallBuilder.setCapabilities(capabilities);
        parcelableCallBuilder.setState(state);
        parcelableCallBuilder.setConferenceableCallIds(Collections.emptyList());
        parcelableCallBuilder.setAccountHandle(new PhoneAccountHandle(
                new ComponentName("com.google.test", "com.google.test.Activity"), "label"));
        return Call.Details.createFromParcelableCall(parcelableCallBuilder.createParcelableCall());
    }
}
