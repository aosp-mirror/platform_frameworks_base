/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.telecom;

import android.annotation.IntDef;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;

import com.android.internal.telecom.ICallEndpointSession;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * Provides method and necessary information for cross device call streaming app to streams calls
 * and updates to the status of the endpoint.
 *
 */
public class CallEndpointSession {
    /**
     * Indicates that this call endpoint session is activated by
     * {@link Call#answerCall(CallEndpoint, int)} from the original device.
     */
    public static final int ANSWER_REQUEST = 1;

    /**
     * Indicates that this call endpoint session is activated by {@link Call#pushCall(CallEndpoint)}
     * from the original device.
     */
    public static final int PUSH_REQUEST = 2;

    /**
     * Indicates that this call endpoint session is activated by
     * {@link TelecomManager#placeCall(Uri, Bundle)} with extra
     * {@link TelecomManager#EXTRA_START_CALL_ON_ENDPOINT} set.
     */
    public static final int PLACE_REQUEST = 3;

    /**
     * @hide
     */
    @IntDef(prefix = {"ACTIVATION_FAILURE_"},
            value = {ACTIVATION_FAILURE_REJECTED, ACTIVATION_FAILURE_UNAVAILABLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActivationFailureReason {}
    /**
     * Used as reason for {@link #setCallEndpointSessionActivationFailed(int)} to inform the
     * endpoint is no longer present on the network.
     */
    public static final int ACTIVATION_FAILURE_UNAVAILABLE = 0;

    /**
     * Used as reason for {@link #setCallEndpointSessionActivationFailed(int)} to inform the
     * remote endpoint rejected the request to start streaming a cross device call.
     */
    public static final int ACTIVATION_FAILURE_REJECTED = 1;

    private final ICallEndpointSession mCallEndpointSession;

    /**
     * {@hide}
     */
    public CallEndpointSession(ICallEndpointSession callEndpointSession) {
        mCallEndpointSession = callEndpointSession;
    }

    /**
     * Invoked by cross device call streaming app to inform telecom stack that the call endpoint is
     * now activated and that the call is being streamed to the endpoint.
     */
    public void setCallEndpointSessionActivated() {
        try {
            mCallEndpointSession.setCallEndpointSessionActivated();
        } catch (RemoteException e) {
        }
    }

    /**
     * Invoked by cross device call streaming app to inform telecom stack that the call endpoint
     * could not be activated due to error.
     * Possible errors are:
     * <ul>
     *     <li>{@link #ACTIVATION_FAILURE_UNAVAILABLE}</li>
     *     <li>{@link #ACTIVATION_FAILURE_REJECTED}</li>
     * </ul>
     *
     * @param reason The reason for activation failure
     */
    public void setCallEndpointSessionActivationFailed(@ActivationFailureReason int reason) {
        try {
            mCallEndpointSession.setCallEndpointSessionActivationFailed(reason);
        } catch (RemoteException e) {
        }
    }

    /**
     * Invoked by cross device call streaming app to inform telecom stack that the call endpoint is
     * no longer active.
     */
    public void setCallEndpointSessionDeactivated() {
        try {
            mCallEndpointSession.setCallEndpointSessionDeactivated();
        } catch (RemoteException e) {
        }
    }
}
