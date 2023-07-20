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
import android.hardware.face.IFaceServiceReceiver;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.Preconditions;
import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.GenerateChallengeClient;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

class FaceGenerateChallengeClient extends GenerateChallengeClient<IFaceService> {
    static final int CHALLENGE_TIMEOUT_SEC = 600;
    private static final ClientMonitorCallback EMPTY_CALLBACK = new ClientMonitorCallback() {
    };
    private static final String TAG = "FaceGenerateChallengeClient";
    private final long mCreatedAt;
    private Long mChallengeResult;
    private List<IFaceServiceReceiver> mWaiting = new ArrayList();

    FaceGenerateChallengeClient(Context context, Supplier<IFaceService> lazyDaemon, IBinder token, ClientMonitorCallbackConverter listener, int userId, String owner, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext, long now) {
        super(context, lazyDaemon, token, listener, userId, owner, sensorId, biometricLogger, biometricContext);
        mCreatedAt = now;
    }

    @Override
    protected void startHalOperation() {
        mChallengeResult = null;
        try {
            try {
                mChallengeResult = getFreshDaemon().generateChallenge(600);
                sendChallengeResult(getListener(), mCallback);
                for (IFaceServiceReceiver receiver : mWaiting) {
                    sendChallengeResult(new ClientMonitorCallbackConverter(receiver), EMPTY_CALLBACK);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "generateChallenge failed", e);
                mCallback.onClientFinished(this, false);
            }
        } finally {
            mWaiting = null;
        }
    }

    public long getCreatedAt() {
        return mCreatedAt;
    }

    public void reuseResult(IFaceServiceReceiver receiver) {
        List<IFaceServiceReceiver> list = mWaiting;
        if (list != null) {
            list.add(receiver);
        } else {
            sendChallengeResult(new ClientMonitorCallbackConverter(receiver), EMPTY_CALLBACK);
        }
    }

    private void sendChallengeResult(ClientMonitorCallbackConverter receiver, ClientMonitorCallback ownerCallback) {
        Preconditions.checkState(mChallengeResult != null, "result not available");
        try {
            receiver.onChallengeGenerated(getSensorId(), getTargetUserId(), mChallengeResult);
            ownerCallback.onClientFinished(this, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception", e);
            ownerCallback.onClientFinished(this, false);
        }
    }
}
