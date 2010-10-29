/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.net.sip;

/**
 * Listener for SIP registration events.
 */
public interface SipRegistrationListener {
    /**
     * Called when a registration request is sent.
     *
     * @param localProfileUri the URI string of the SIP profile to register with
     */
    void onRegistering(String localProfileUri);

    /**
     * Called when the registration succeeded.
     *
     * @param localProfileUri the URI string of the SIP profile to register with
     * @param expiryTime duration in seconds before the registration expires
     */
    void onRegistrationDone(String localProfileUri, long expiryTime);

    /**
     * Called when the registration failed.
     *
     * @param localProfileUri the URI string of the SIP profile to register with
     * @param errorCode error code of this error
     * @param errorMessage error message
     * @see SipErrorCode
     */
    void onRegistrationFailed(String localProfileUri, int errorCode,
            String errorMessage);
}
