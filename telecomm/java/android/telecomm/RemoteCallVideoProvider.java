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

import com.android.internal.telecomm.ICallVideoProvider;

public class RemoteCallVideoProvider {
    private final ICallVideoProvider mCallVideoProvider;

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mCallVideoProvider.asBinder().unlinkToDeath(this, 0);
        }
    };

    /** {@hide} */
    RemoteCallVideoProvider(ICallVideoProvider callVideoProvider) throws RemoteException {
        mCallVideoProvider = callVideoProvider;
        mCallVideoProvider.asBinder().linkToDeath(mDeathRecipient, 0);
    }

    public void setCallVideoClient(CallVideoClient callVideoClient) {
        try {
            mCallVideoProvider.setCallVideoClient(callVideoClient.getBinder());
        } catch (RemoteException e) {
        }
    }

    public void setCamera(String cameraId) throws RemoteException {
        mCallVideoProvider.setCamera(cameraId);
    }

    public void setPreviewSurface(Surface surface) {
        try {
            mCallVideoProvider.setPreviewSurface(surface);
        } catch (RemoteException e) {
        }
    }

    public void setDisplaySurface(Surface surface) {
        try {
            mCallVideoProvider.setDisplaySurface(surface);
        } catch (RemoteException e) {
        }
    }

    public void setDeviceOrientation(int rotation) {
        try {
            mCallVideoProvider.setDeviceOrientation(rotation);
        } catch (RemoteException e) {
        }
    }

    public void setZoom(float value) throws RemoteException {
        mCallVideoProvider.setZoom(value);
    }

    public void sendSessionModifyRequest(VideoCallProfile requestProfile) {
        try {
            mCallVideoProvider.sendSessionModifyRequest(requestProfile);
        } catch (RemoteException e) {
        }
    }

    public void sendSessionModifyResponse(VideoCallProfile responseProfile) {
        try {
            mCallVideoProvider.sendSessionModifyResponse(responseProfile);
        } catch (RemoteException e) {
        }
    }

    public void requestCameraCapabilities() {
        try {
            mCallVideoProvider.requestCameraCapabilities();
        } catch (RemoteException e) {
        }
    }

    public void requestCallDataUsage() {
        try {
            mCallVideoProvider.requestCallDataUsage();
        } catch (RemoteException e) {
        }
    }

    public void setPauseImage(String uri) {
        try {
            mCallVideoProvider.setPauseImage(uri);
        } catch (RemoteException e) {
        }
    }
}