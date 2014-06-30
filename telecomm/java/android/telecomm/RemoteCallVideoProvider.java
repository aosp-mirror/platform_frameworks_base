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
import android.view.Surface;

import com.android.internal.telecomm.ICallVideoClient;
import com.android.internal.telecomm.ICallVideoProvider;

public class RemoteCallVideoProvider implements IBinder.DeathRecipient {
    private final ICallVideoProvider mCallVideoProvider;

    RemoteCallVideoProvider(ICallVideoProvider callVideoProvider) throws RemoteException {
        mCallVideoProvider = callVideoProvider;
        mCallVideoProvider.asBinder().linkToDeath(this, 0);
    }

    @Override
    public void binderDied() {
        mCallVideoProvider.asBinder().unlinkToDeath(this, 0);
    }

    public void setCallVideoClient(CallVideoClient callVideoClient) throws RemoteException {
        mCallVideoProvider.setCallVideoClient(callVideoClient.getBinder());
    }

    public void setCamera(String cameraId) throws RemoteException {
        mCallVideoProvider.setCamera(cameraId);
    }

    public void setPreviewSurface(Surface surface) throws RemoteException {
        mCallVideoProvider.setPreviewSurface(surface);
    }

    public void setDisplaySurface(Surface surface) throws RemoteException {
        mCallVideoProvider.setDisplaySurface(surface);
    }

    public void setDeviceOrientation(int rotation) throws RemoteException {
        mCallVideoProvider.setDeviceOrientation(rotation);
    }

    public void setZoom(float value) throws RemoteException {
        mCallVideoProvider.setZoom(value);
    }

    public void sendSessionModifyRequest(VideoCallProfile requestProfile) throws RemoteException {
        mCallVideoProvider.sendSessionModifyRequest(requestProfile);
    }

    public void sendSessionModifyResponse(VideoCallProfile responseProfile) throws RemoteException {
        mCallVideoProvider.sendSessionModifyResponse(responseProfile);
    }

    public void requestCameraCapabilities() throws RemoteException {
        mCallVideoProvider.requestCameraCapabilities();
    }

    public void requestCallDataUsage() throws RemoteException {
        mCallVideoProvider.requestCallDataUsage();
    }

    public void onSetPauseImage(String uri) throws RemoteException {
        mCallVideoProvider.setPauseImage(uri);
    }
}