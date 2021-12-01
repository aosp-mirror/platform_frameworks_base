/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.platform.test.annotations.Presubmit;
import android.test.AndroidTestCase;

@Presubmit
public class PackageVerificationStateTest extends AndroidTestCase {
    private static final int REQUIRED_UID = 1948;

    private static final int SUFFICIENT_UID_1 = 1005;

    private static final int SUFFICIENT_UID_2 = 8938;

    public void testPackageVerificationState_OnlyRequiredVerifier_AllowedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_OnlyRequiredVerifier_DeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndOneSufficient_RequiredDeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndOneSufficient_SufficientDeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_REJECT);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndTwoSufficient_OneSufficientIsEnough() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);
        state.addSufficientVerifier(SUFFICIENT_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndTwoSufficient_SecondSufficientIsEnough() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);
        state.addSufficientVerifier(SUFFICIENT_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_REJECT);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_2, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndTwoSufficient_RequiredOverrides() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);
        state.addSufficientVerifier(SUFFICIENT_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID,
                PackageManager.VERIFICATION_ALLOW_WITHOUT_SUFFICIENT);

        assertTrue("Verification should be marked as complete immediately",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should still be marked as completed",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed still",
                state.isInstallAllowed());

        state.setVerifierResponse(SUFFICIENT_UID_2, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should still be complete",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed still",
                state.isInstallAllowed());
    }

    public void testAreAllVerificationsComplete_onlyVerificationPasses() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);
        assertFalse(state.areAllVerificationsComplete());

        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_ALLOW);

        assertFalse(state.areAllVerificationsComplete());
    }

    public void testAreAllVerificationsComplete_onlyIntegrityCheckPasses() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);
        assertFalse(state.areAllVerificationsComplete());

        state.setIntegrityVerificationResult(PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);

        assertFalse(state.areAllVerificationsComplete());
    }

    public void testAreAllVerificationsComplete_bothPasses() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);
        assertFalse(state.areAllVerificationsComplete());

        state.setIntegrityVerificationResult(PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_ALLOW);

        assertTrue(state.areAllVerificationsComplete());
    }

    public void testAreAllVerificationsComplete_onlyVerificationFails() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);
        assertFalse(state.areAllVerificationsComplete());

        state.setVerifierResponse(REQUIRED_UID, PackageManager.VERIFICATION_REJECT);

        assertFalse(state.areAllVerificationsComplete());
    }

    public void testAreAllVerificationsComplete_onlyIntegrityCheckFails() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.setRequiredVerifierUid(REQUIRED_UID);
        assertFalse(state.areAllVerificationsComplete());

        state.setIntegrityVerificationResult(PackageManagerInternal.INTEGRITY_VERIFICATION_REJECT);

        assertFalse(state.areAllVerificationsComplete());
    }
}
