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
 * Adapter class for {@link ISipSessionListener}. Default implementation of all
 * callback methods is no-op.
 * @hide
 */
public class SipSessionAdapter extends ISipSessionListener.Stub {
    public void onCalling(ISipSession session) {
    }

    public void onRinging(ISipSession session, SipProfile caller,
            String sessionDescription) {
    }

    public void onRingingBack(ISipSession session) {
    }

    public void onCallEstablished(ISipSession session,
            String sessionDescription) {
    }

    public void onCallEnded(ISipSession session) {
    }

    public void onCallBusy(ISipSession session) {
    }

    public void onCallTransferring(ISipSession session,
            String sessionDescription) {
    }

    public void onCallChangeFailed(ISipSession session, int errorCode,
            String message) {
    }

    public void onError(ISipSession session, int errorCode, String message) {
    }

    public void onRegistering(ISipSession session) {
    }

    public void onRegistrationDone(ISipSession session, int duration) {
    }

    public void onRegistrationFailed(ISipSession session, int errorCode,
            String message) {
    }

    public void onRegistrationTimeout(ISipSession session) {
    }
}
