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
    private static final int REQUIRED_UID_1 = 1948;

    private static final int REQUIRED_UID_2 = 1949;

    private static final int SUFFICIENT_UID_1 = 1005;

    private static final int SUFFICIENT_UID_2 = 8938;

    public void testPackageVerificationState_OnlyRequiredVerifier_AllowedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_OnlyRequiredVerifier_DeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_AllowedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_DeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());

        // Nothing changes.
        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_FirstDeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());

        // Nothing changes.
        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_SecondDeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_SecondTimesOut_DefaultAllow() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        // Timeout with default ALLOW.
        processOnTimeout(state, PackageManager.VERIFICATION_ALLOW, REQUIRED_UID_2, true);
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_SecondTimesOut_DefaultReject() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        // Timeout with default REJECT.
        processOnTimeout(state, PackageManager.VERIFICATION_REJECT, REQUIRED_UID_2, false);
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_FirstTimesOut_DefaultAllow() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        // Timeout with default ALLOW.
        processOnTimeout(state, PackageManager.VERIFICATION_ALLOW, REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_FirstTimesOut_DefaultReject() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        // Timeout with default REJECT.
        processOnTimeout(state, PackageManager.VERIFICATION_REJECT, REQUIRED_UID_1);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());

        // Nothing changes.
        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_FirstTimesOut_SecondExtends_DefaultAllow() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.extendTimeout(REQUIRED_UID_2);

        // Timeout with default ALLOW.
        processOnTimeout(state, PackageManager.VERIFICATION_ALLOW, REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        assertTrue("Timeout is extended",
                state.timeoutExtended(REQUIRED_UID_2));

        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_TwoRequiredVerifiers_FirstTimesOut_SecondExtends_DefaultReject() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.extendTimeout(REQUIRED_UID_2);

        // Timeout with default REJECT.
        processOnTimeout(state, PackageManager.VERIFICATION_REJECT, REQUIRED_UID_1);

        assertFalse("Timeout should not be extended for this verifier",
                state.timeoutExtended(REQUIRED_UID_2));

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());

        // Nothing changes.
        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndOneSufficient_RequiredDeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndOneSufficient_OneRequiredDeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addRequiredVerifierUid(REQUIRED_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);
        state.setVerifierResponse(REQUIRED_UID_2, PackageManager.VERIFICATION_REJECT);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as denied",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndOneSufficient_SufficientDeniedInstall() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_REJECT);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertFalse("Installation should be marked as rejected",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAllow_SufficientTimesOut_DefaultAllow() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        // Required allows.
        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        // Timeout with default ALLOW.
        processOnTimeout(state, PackageManager.VERIFICATION_ALLOW, REQUIRED_UID_1, true);
    }

    public void testPackageVerificationState_RequiredExtendAllow_SufficientTimesOut_DefaultAllow() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        // Extend first.
        state.extendTimeout(REQUIRED_UID_1);

        // Required allows.
        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        // Timeout with default ALLOW.
        processOnTimeout(state, PackageManager.VERIFICATION_ALLOW, REQUIRED_UID_1, true);
    }

    public void testPackageVerificationState_RequiredAllow_SufficientTimesOut_DefaultReject() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        // Required allows.
        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        // Timeout with default REJECT.
        processOnTimeout(state, PackageManager.VERIFICATION_REJECT, REQUIRED_UID_1, true);
    }

    public void testPackageVerificationState_RequiredAndTwoSufficient_OneSufficientIsEnough() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);
        state.addSufficientVerifier(SUFFICIENT_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(SUFFICIENT_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());

        assertTrue("Installation should be marked as allowed",
                state.isInstallAllowed());
    }

    public void testPackageVerificationState_RequiredAndTwoSufficient_SecondSufficientIsEnough() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);
        state.addSufficientVerifier(SUFFICIENT_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

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
        state.addRequiredVerifierUid(REQUIRED_UID_1);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.addSufficientVerifier(SUFFICIENT_UID_1);
        state.addSufficientVerifier(SUFFICIENT_UID_2);

        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());

        state.setVerifierResponse(REQUIRED_UID_1,
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

    public void testAreAllVerificationsComplete_timeoutSuccessWithSufficient() {
        PackageVerificationState state = new PackageVerificationState(null);

        state.addRequiredVerifierUid(REQUIRED_UID_1);
        state.addSufficientVerifier(SUFFICIENT_UID_1);

        assertFalse(state.areAllVerificationsComplete());
        assertFalse(state.isVerificationComplete());
        assertFalse(state.isInstallAllowed());

        // Required verifier responded, but still waiting for sufficient.
        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);
        assertFalse(state.isVerificationComplete());

        // Timeout, verification complete and installation allowed.
        state.setVerifierResponse(REQUIRED_UID_1,
                PackageManager.VERIFICATION_ALLOW_WITHOUT_SUFFICIENT);
        assertTrue(state.isVerificationComplete());
        assertTrue(state.isInstallAllowed());
    }

    public void testAreAllVerificationsComplete_onlyVerificationPasses() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        assertFalse(state.areAllVerificationsComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertFalse(state.areAllVerificationsComplete());
    }

    public void testAreAllVerificationsComplete_onlyIntegrityCheckPasses() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        assertFalse(state.areAllVerificationsComplete());

        state.setIntegrityVerificationResult(PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);

        assertFalse(state.areAllVerificationsComplete());
    }

    public void testAreAllVerificationsComplete_bothPasses() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        assertFalse(state.areAllVerificationsComplete());

        state.setIntegrityVerificationResult(PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_ALLOW);

        assertTrue(state.areAllVerificationsComplete());
    }

    public void testAreAllVerificationsComplete_onlyVerificationFails() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        assertFalse(state.areAllVerificationsComplete());

        state.setVerifierResponse(REQUIRED_UID_1, PackageManager.VERIFICATION_REJECT);

        assertFalse(state.areAllVerificationsComplete());
    }

    public void testAreAllVerificationsComplete_onlyIntegrityCheckFails() {
        PackageVerificationState state = new PackageVerificationState(null);
        state.addRequiredVerifierUid(REQUIRED_UID_1);
        assertFalse(state.areAllVerificationsComplete());

        state.setIntegrityVerificationResult(PackageManagerInternal.INTEGRITY_VERIFICATION_REJECT);

        assertFalse(state.areAllVerificationsComplete());
    }

    private void processOnTimeout(PackageVerificationState state, int code, int uid) {
        // CHECK_PENDING_VERIFICATION handler.
        assertFalse("Verification should not be marked as complete yet",
                state.isVerificationComplete());
        assertFalse("Timeout should not be extended for this verifier",
                state.timeoutExtended(uid));

        PackageVerificationResponse response = new PackageVerificationResponse(code, uid);
        VerificationUtils.processVerificationResponseOnTimeout(-1, state, response, null);
    }

    private void processOnTimeout(PackageVerificationState state, int code, int uid,
            boolean expectAllow) {
        processOnTimeout(state, code, uid);

        assertTrue("Verification should be considered complete now",
                state.isVerificationComplete());
        assertEquals("Installation should be marked as " + (expectAllow ? "allowed" : "rejected"),
                expectAllow, state.isInstallAllowed());
    }
}
