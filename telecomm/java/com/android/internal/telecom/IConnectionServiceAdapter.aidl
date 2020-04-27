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

package com.android.internal.telecom;

import android.app.PendingIntent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.Logging.Session;
import android.telecom.ParcelableConnection;
import android.telecom.ParcelableConference;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;

import com.android.internal.telecom.IVideoProvider;
import com.android.internal.telecom.RemoteServiceCallback;

/**
 * Internal remote callback interface for connection services.
 *
 * @see android.telecom.ConnectionServiceAdapter
 *
 * {@hide}
 */
oneway interface IConnectionServiceAdapter {
    void handleCreateConnectionComplete(
            String callId,
            in ConnectionRequest request,
            in ParcelableConnection connection,
            in Session.Info sessionInfo);

    void handleCreateConferenceComplete(
            String callId,
            in ConnectionRequest request,
            in ParcelableConference connection,
            in Session.Info sessionInfo);

    void setActive(String callId, in Session.Info sessionInfo);

    void setRinging(String callId, in Session.Info sessionInfo);

    void setDialing(String callId, in Session.Info sessionInfo);

    void setPulling(String callId, in Session.Info sessionInfo);

    void setDisconnected(String callId, in DisconnectCause disconnectCause,
    in Session.Info sessionInfo);

    void setOnHold(String callId, in Session.Info sessionInfo);

    void setRingbackRequested(String callId, boolean ringing, in Session.Info sessionInfo);

    void setConnectionCapabilities(String callId, int connectionCapabilities,
    in Session.Info sessionInfo);

    void setConnectionProperties(String callId, int connectionProperties,
    in Session.Info sessionInfo);

    void setIsConferenced(String callId, String conferenceCallId, in Session.Info sessionInfo);

    void setConferenceMergeFailed(String callId, in Session.Info sessionInfo);

    void addConferenceCall(String callId, in ParcelableConference conference,
    in Session.Info sessionInfo);

    void removeCall(String callId, in Session.Info sessionInfo);

    void onPostDialWait(String callId, String remaining, in Session.Info sessionInfo);

    void onPostDialChar(String callId, char nextChar, in Session.Info sessionInfo);

    void queryRemoteConnectionServices(RemoteServiceCallback callback, String callingPackage,
    in Session.Info sessionInfo);

    void setVideoProvider(String callId, IVideoProvider videoProvider, in Session.Info sessionInfo);

    void setVideoState(String callId, int videoState, in Session.Info sessionInfo);

    void setIsVoipAudioMode(String callId, boolean isVoip, in Session.Info sessionInfo);

    void setStatusHints(String callId, in StatusHints statusHints, in Session.Info sessionInfo);

    void setAddress(String callId, in Uri address, int presentation, in Session.Info sessionInfo);

    void setCallerDisplayName(String callId, String callerDisplayName, int presentation,
    in Session.Info sessionInfo);

    void setConferenceableConnections(String callId, in List<String> conferenceableCallIds,
    in Session.Info sessionInfo);

    void addExistingConnection(String callId, in ParcelableConnection connection,
    in Session.Info sessionInfo);

    void putExtras(String callId, in Bundle extras, in Session.Info sessionInfo);

    void removeExtras(String callId, in List<String> keys, in Session.Info sessionInfo);

    void setAudioRoute(String callId, int audioRoute, String bluetoothAddress,
            in Session.Info sessionInfo);

    void onConnectionEvent(String callId, String event, in Bundle extras,
    in Session.Info sessionInfo);

    void onRttInitiationSuccess(String callId, in Session.Info sessionInfo);

    void onRttInitiationFailure(String callId, int reason, in Session.Info sessionInfo);

    void onRttSessionRemotelyTerminated(String callId, in Session.Info sessionInfo);

    void onRemoteRttRequest(String callId, in Session.Info sessionInfo);

    void onPhoneAccountChanged(String callId, in PhoneAccountHandle pHandle,
    in Session.Info sessionInfo);

    void onConnectionServiceFocusReleased(in Session.Info sessionInfo);

    void resetConnectionTime(String callIdi, in Session.Info sessionInfo);

    void setConferenceState(String callId, boolean isConference, in Session.Info sessionInfo);

    void setCallDirection(String callId, int direction, in Session.Info sessionInfo);
}
