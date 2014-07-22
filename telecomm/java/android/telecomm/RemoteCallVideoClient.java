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
 * limitations under the License
 */

package android.telecomm;

import android.os.IBinder;
import android.os.RemoteException;
import android.telecomm.CallCameraCapabilities;
import android.telecomm.VideoCallProfile;

import com.android.internal.telecomm.ICallVideoClient;

/**
 * Remote class to invoke callbacks in InCallUI related to supporting video in calls.
 */
public class RemoteCallVideoClient {
    private final ICallVideoClient mCallVideoClient;

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mCallVideoClient.asBinder().unlinkToDeath(this, 0);
        }
    };

    /** {@hide} */
    RemoteCallVideoClient(ICallVideoClient callVideoProvider) throws RemoteException {
        mCallVideoClient = callVideoProvider;
        mCallVideoClient.asBinder().linkToDeath(mDeathRecipient, 0);
    }

    /**
     * Called when a session modification request is received from the remote device.
     * The remote request is sent via {@link CallVideoProvider#onSendSessionModifyRequest}.
     * The InCall UI is responsible for potentially prompting the user whether they wish to accept
     * the new call profile (e.g. prompt user if they wish to accept an upgrade from an audio to a
     * video call) and should call {@link CallVideoProvider#onSendSessionModifyResponse} to indicate
     * the video settings the user has agreed to.
     *
     * @param videoCallProfile The requested video call profile.
     */
    public void receiveSessionModifyRequest(VideoCallProfile videoCallProfile) {
        try {
            mCallVideoClient.receiveSessionModifyRequest(videoCallProfile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Called when a response to a session modification request is received from the remote device.
     * The remote InCall UI sends the response using
     * {@link CallVideoProvider#onSendSessionModifyResponse}.
     *
     * @param status Status of the session modify request.  Valid values are
     *               {@link CallVideoClient#SESSION_MODIFY_REQUEST_SUCCESS},
     *               {@link CallVideoClient#SESSION_MODIFY_REQUEST_FAIL},
     *               {@link CallVideoClient#SESSION_MODIFY_REQUEST_INVALID}
     * @param requestedProfile The original request which was sent to the remote device.
     * @param responseProfile The actual profile changes made by the remote device.
     */
    public void receiveSessionModifyResponse(
            int status, VideoCallProfile requestedProfile, VideoCallProfile responseProfile) {
        try {
            mCallVideoClient.receiveSessionModifyResponse(
                    status, requestedProfile, responseProfile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Handles events related to the current session which the client may wish to handle.  These
     * are separate from requested changes to the session due to the underlying protocol or
     * connection.
     * Valid values are: {@link CallVideoClient#SESSION_EVENT_RX_PAUSE},
     * {@link CallVideoClient#SESSION_EVENT_RX_RESUME},
     * {@link CallVideoClient#SESSION_EVENT_TX_START}, {@link CallVideoClient#SESSION_EVENT_TX_STOP}
     *
     * @param event The event.
     */
    public void handleCallSessionEvent(int event) {
        try {
            mCallVideoClient.handleCallSessionEvent(event);
        } catch (RemoteException e) {
        }
    }

    /**
     * Handles a change to the video dimensions from the remote caller (peer).  This could happen
     * if, for example, the peer changes orientation of their device.
     *
     * @param width  The updated peer video width.
     * @param height The updated peer video height.
     */
    public void updatePeerDimensions(int width, int height) {
        try {
            mCallVideoClient.updatePeerDimensions(width, height);
        } catch (RemoteException e) {
        }
    }

    /**
     * Handles an update to the total data used for the current session.
     *
     * @param dataUsage The updated data usage.
     */
    public void updateCallDataUsage(int dataUsage) {
        try {
            mCallVideoClient.updateCallDataUsage(dataUsage);
        } catch (RemoteException e) {
        }
    }

    /**
     * Handles a change in camera capabilities.
     *
     * @param callCameraCapabilities The changed camera capabilities.
     */
    public void handleCameraCapabilitiesChange(CallCameraCapabilities callCameraCapabilities) {
        try {
            mCallVideoClient.handleCameraCapabilitiesChange(callCameraCapabilities);
        } catch (RemoteException e) {
        }
    }
}