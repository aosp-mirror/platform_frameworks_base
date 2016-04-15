/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.hardware.fingerprint;

import android.hardware.fingerprint.IFingerprintDaemonCallback;

/**
 * Communication channel from FingerprintService to FingerprintDaemon (fingerprintd)
 * @hide
 */

interface IFingerprintDaemon {
    int authenticate(long sessionId, int groupId);
    int cancelAuthentication();
    int enroll(in byte [] token, int groupId, int timeout);
    int cancelEnrollment();
    long preEnroll();
    int remove(int fingerId, int groupId);
    long getAuthenticatorId();
    int setActiveGroup(int groupId, in byte[] path);
    long openHal();
    int closeHal();
    void init(IFingerprintDaemonCallback callback);
    int postEnroll();
    int enumerate();
    int cancelEnumeration();
}
