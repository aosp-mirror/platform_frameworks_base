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

package android.telecomm;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.android.internal.telecomm.ICallVideoProvider;

public abstract class CallVideoProvider {
    private static final int MSG_SET_CAMERA = 1;

    /**
     * Default handler used to consolidate binder method calls onto a single thread.
     */
    private final class CallVideoProviderHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CAMERA:
                    setCamera((String) msg.obj);
                default:
                    break;
            }
        }
    }

    /**
     * Default ICallVideoProvider implementation.
     */
    private final class CallVideoProviderBinder extends ICallVideoProvider.Stub {
        public void setCamera(String cameraId) {
            mMessageHandler.obtainMessage(MSG_SET_CAMERA, cameraId).sendToTarget();
        }
    }

    private final CallVideoProviderHandler mMessageHandler = new CallVideoProviderHandler();
    private final CallVideoProviderBinder mBinder;

    protected CallVideoProvider() {
        mBinder = new CallVideoProviderBinder();
    }

    /**
     * Returns binder object which can be used across IPC methods.
     * @hide
     */
    public final ICallVideoProvider getInterface() {
        return mBinder;
    }

    /**
     * Sets the camera to be used for video recording in a video call.
     *
     * @param cameraId The id of the camera.
     */
    public abstract void setCamera(String cameraId);
}
