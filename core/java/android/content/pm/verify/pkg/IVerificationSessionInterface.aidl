/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm.verify.pkg;

import android.content.pm.verify.pkg.VerificationStatus;
import android.os.PersistableBundle;

/**
 * Non-oneway interface that allows the verifier to communicate with the system.
 * @hide
 */
interface IVerificationSessionInterface {
    long getTimeoutTime(int verificationId);
    long extendTimeRemaining(int verificationId, long additionalMs);
    boolean setVerificationPolicy(int verificationId, int policy);
    void reportVerificationIncomplete(int verificationId, int reason);
    void reportVerificationComplete(int verificationId, in VerificationStatus status, in @nullable PersistableBundle extensionResponse);
}