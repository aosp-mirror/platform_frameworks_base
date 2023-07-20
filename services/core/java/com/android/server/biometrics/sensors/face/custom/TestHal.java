/*
* Copyright (C) 2022 The Pixel Experience Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.server.biometrics.sensors.face.custom;

import android.content.Context;
import android.hardware.face.Face;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.internal.util.custom.faceunlock.IFaceServiceReceiver;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.List;

class TestHal extends IFaceService.Stub {
    private static final String TAG = "FaceService.TestHal";
    private final Context mContext;
    private final int mSensorId;
    private final int mUserId;
    private IFaceServiceReceiver mCallback;

    TestHal(int userId, Context context, int sensorId) {
        mUserId = userId;
        mContext = context;
        mSensorId = sensorId;
    }

    @Override
    public void setCallback(IFaceServiceReceiver clientCallback) throws RemoteException {
        mCallback = clientCallback;
    }

    @Override
    public int revokeChallenge() {
        return 0;
    }

    @Override
    public int getAuthenticatorId() throws RemoteException {
        return 0;
    }

    @Override
    public boolean getFeature(int i, int i1) throws RemoteException {
        return false;
    }

    @Override
    public int getFeatureCount() throws RemoteException {
        return 0;
    }

    @Override
    public long generateChallenge(int i) throws RemoteException {
        Slog.w(TAG, "generateChallenge");
        return 0;
    }

    @Override
    public void resetLockout(byte[] bytes) throws RemoteException {
    }

    @Override
    public void setFeature(int i, boolean b, byte[] bytes, int i1) throws RemoteException {
    }

    @Override
    public int enumerate() throws RemoteException {
        Slog.w(TAG, "enumerate");
        if (mCallback != null) {
            mCallback.onEnumerate(new int[0], 0);
        }
        return 0;
    }

    @Override
    public void enroll(byte[] bytes, int i, int[] ints) throws RemoteException {
        Slog.w(TAG, "enroll");
    }

    @Override
    public void authenticate(long l) throws RemoteException {
        Slog.w(TAG, "authenticate");
    }

    @Override
    public void cancel() throws RemoteException {
        if (mCallback != null) {
            mCallback.onError(5, 0);
        }
    }

    @Override
    public void remove(int faceId) throws RemoteException {
        if (mCallback != null) {
            Slog.d(TAG, " remove : faceId = " + faceId);
            if (faceId == 0) {
                List<Face> faces = FaceUtils.getInstance(mSensorId).getBiometricsForUser(mContext, mUserId);
                if (faces.size() <= 0) {
                    mCallback.onError(6, 0);
                    return;
                }
                int[] faceIds = new int[faces.size()];
                for (int i = 0; i < faces.size(); i++) {
                    faceIds[i] = faces.get(i).getBiometricId();
                }
                mCallback.onRemoved(faceIds, mUserId);
                return;
            }
            mCallback.onRemoved(new int[]{faceId}, mUserId);
        }
    }
}
