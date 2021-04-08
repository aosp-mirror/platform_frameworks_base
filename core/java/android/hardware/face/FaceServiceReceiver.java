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

package android.hardware.face;

import android.os.RemoteException;

/**
 * Provides default methods for callers who only need a subset of the functionality.
 * @hide
 */
public class FaceServiceReceiver extends IFaceServiceReceiver.Stub {
    @Override
    public void onEnrollResult(Face face, int remaining) throws RemoteException {

    }

    @Override
    public void onAcquired(int acquiredInfo, int vendorCode) throws RemoteException {

    }

    @Override
    public void onAuthenticationSucceeded(Face face, int userId, boolean isStrongBiometric)
            throws RemoteException {

    }

    @Override
    public void onFaceDetected(int sensorId, int userId, boolean isStrongBiometric)
            throws RemoteException {

    }

    @Override
    public void onAuthenticationFailed() throws RemoteException {

    }

    @Override
    public void onError(int error, int vendorCode) throws RemoteException {

    }

    @Override
    public void onRemoved(Face face, int remaining) throws RemoteException {

    }

    @Override
    public void onFeatureSet(boolean success, int feature) throws RemoteException {

    }

    @Override
    public void onFeatureGet(boolean success, int feature, boolean value) throws RemoteException {

    }

    @Override
    public void onChallengeGenerated(int sensorId, long challenge) throws RemoteException {

    }

    @Override
    public void onChallengeInterrupted(int sensorId) throws RemoteException {

    }

    @Override
    public void onChallengeInterruptFinished(int sensorId) throws RemoteException {

    }

    @Override
    public void onAuthenticationFrame(FaceAuthenticationFrame frame) throws RemoteException {

    }

    @Override
    public void onEnrollmentFrame(FaceEnrollFrame frame) throws RemoteException {

    }
}
