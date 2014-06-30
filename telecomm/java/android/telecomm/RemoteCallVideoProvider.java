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

    /**
     * Sets the camera to be used for video recording in a video call, using the binder.
     *
     * @param cameraId The id of the camera.
     */
    public void setCamera(String cameraId) throws RemoteException {
        mCallVideoProvider.setCamera(cameraId);
    }
}
