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

public class RemoteCallVideoClient implements IBinder.DeathRecipient {
    private final ICallVideoClient mCallVideoClient;

    RemoteCallVideoClient(ICallVideoClient callVideoProvider) throws RemoteException {
        mCallVideoClient = callVideoProvider;
        mCallVideoClient.asBinder().linkToDeath(this, 0);
    }

    @Override
    public void binderDied() {
        mCallVideoClient.asBinder().unlinkToDeath(this, 0);
    }

    public void receiveSessionModifyRequest(VideoCallProfile videoCallProfile)
            throws RemoteException {
        mCallVideoClient.receiveSessionModifyRequest(videoCallProfile);
    }

    public void receiveSessionModifyResponse(int status, VideoCallProfile requestedProfile,
            VideoCallProfile responseProfile) throws RemoteException {
        mCallVideoClient.receiveSessionModifyResponse(status, requestedProfile, responseProfile);
    }

    public void handleCallSessionEvent(int event) throws RemoteException {
        mCallVideoClient.handleCallSessionEvent(event);
    }

    public void updatePeerDimensions(int width, int height) throws RemoteException {
        mCallVideoClient.updatePeerDimensions(width, height);
    }

    public void updateCallDataUsage(int dataUsage) throws RemoteException {
        mCallVideoClient.updateCallDataUsage(dataUsage);
    }

    public void handleCameraCapabilitiesChange(CallCameraCapabilities callCameraCapabilities)
            throws RemoteException {
        mCallVideoClient.handleCameraCapabilitiesChange(callCameraCapabilities);
    }
}