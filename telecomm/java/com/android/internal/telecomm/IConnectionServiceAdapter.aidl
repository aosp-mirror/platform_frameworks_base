/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telecomm;

import android.app.PendingIntent;
import android.net.Uri;
import android.telecomm.ConnectionRequest;
import android.telecomm.StatusHints;

import com.android.internal.telecomm.ICallVideoProvider;
import com.android.internal.telecomm.RemoteServiceCallback;

/**
 * Internal remote callback interface for connection services.
 *
 * @see android.telecomm.ConnectionServiceAdapter
 *
 * {@hide}
 */
oneway interface IConnectionServiceAdapter {
    void handleCreateConnectionSuccessful(in ConnectionRequest request);

    void handleCreateConnectionFailed(
            in ConnectionRequest request, int errorCode, String errorMessage);

    void handleCreateConnectionCancelled(in ConnectionRequest request);

    void setActive(String callId);

    void setRinging(String callId);

    void setDialing(String callId);

    void setDisconnected(String callId, int disconnectCause, String disconnectMessage);

    void setOnHold(String callId);

    void setRequestingRingback(String callId, boolean ringing);

    void setCallCapabilities(String callId, int callCapabilities);

    void setIsConferenced(String callId, String conferenceCallId);

    void addConferenceCall(String callId);

    void removeCall(String callId);

    void onPostDialWait(String callId, String remaining);

    void queryRemoteConnectionServices(RemoteServiceCallback callback);

    void setCallVideoProvider(String callId, ICallVideoProvider callVideoProvider);

    void setVideoState(String callId, int videoState);

    void setAudioModeIsVoip(String callId, boolean isVoip);

    void setStatusHints(String callId, in StatusHints statusHints);

    void setHandle(String callId, in Uri handle, int presentation);

    void setCallerDisplayName(String callId, String callerDisplayName, int presentation);

    void startActivityFromInCall(String callId, in PendingIntent intent);
}
