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

package android.debug;

/**
 * Interface to communicate remotely with the {@code AdbService} in the system server.
 *
 * @hide
 */
interface IAdbManager {
    /**
     * Allow ADB debugging from the attached host. If {@code alwaysAllow} is
     * {@code true}, add {@code publicKey} to list of host keys that the
     * user has approved.
     *
     * @param alwaysAllow if true, add permanently to list of allowed keys
     * @param publicKey RSA key in mincrypt format and Base64-encoded
     */
    void allowDebugging(boolean alwaysAllow, String publicKey);

    /**
     * Deny ADB debugging from the attached host.
     */
    void denyDebugging();

    /**
     * Clear all public keys installed for secure ADB debugging.
     */
    void clearDebuggingKeys();
}
