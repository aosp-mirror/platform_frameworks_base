/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Buffer for portSelect/deviceSelect requests. Requests made before the address allocation
 * are stored in this buffer, and processed when the allocation is completed.
 *
 * <p>This is put into action only if we are a TV device.
 */
public class SelectRequestBuffer {
    private static final String TAG = "SelectRequestBuffer";

    public static final SelectRequestBuffer EMPTY_BUFFER = new SelectRequestBuffer() {
        @Override
        public void process() {
            // Do nothing.
        }
    };

    /**
     * Parent class from which buffer for select requests are inherited. Keeps the callback
     * and the device/port ID.
     */
    public static abstract class SelectRequest {
        protected final HdmiControlService mService;
        protected final IHdmiControlCallback mCallback;
        protected final int mId;

        public SelectRequest(HdmiControlService service, int id, IHdmiControlCallback callback) {
            mService = service;
            mId = id;
            mCallback = callback;
        }

        protected HdmiCecLocalDeviceTv tv() {
            return mService.tv();
        }

        protected HdmiCecLocalDeviceAudioSystem audioSystem() {
            return mService.audioSystem();
        }

        protected boolean isLocalDeviceReady() {
            if (tv() == null) {
                Slog.e(TAG, "Local tv device not available");
                invokeCallback(HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
                return false;
            }
            return true;
        }

        private void invokeCallback(int reason) {
            try {
                if (mCallback != null) {
                    mCallback.onComplete(reason);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Invoking callback failed:" + e);
            }
        }

        /**
         * Implement this method with a customize action to perform when the request gets
         * invoked in a deferred manner.
         */
        public abstract void process();
    }

    public static class DeviceSelectRequest extends SelectRequest {
        private DeviceSelectRequest(HdmiControlService srv, int id, IHdmiControlCallback callback) {
            super(srv, id, callback);
        }

        @Override
        public void process() {
            if (isLocalDeviceReady()) {
                Slog.v(TAG, "calling delayed deviceSelect id:" + mId);
                tv().deviceSelect(mId, mCallback);
            }
        }
    }

    public static class PortSelectRequest extends SelectRequest {
        private PortSelectRequest(HdmiControlService srv, int id, IHdmiControlCallback callback) {
            super(srv, id, callback);
        }

        @Override
        public void process() {
            if (isLocalDeviceReady()) {
                Slog.v(TAG, "calling delayed portSelect id:" + mId);
                HdmiCecLocalDeviceTv tv = tv();
                if (tv != null) {
                    tv.doManualPortSwitching(mId, mCallback);
                    return;
                }
                HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
                if (audioSystem != null) {
                    audioSystem.doManualPortSwitching(mId, mCallback);
                }
            }
        }
    }

    public static DeviceSelectRequest newDeviceSelect(HdmiControlService srv, int id,
            IHdmiControlCallback callback) {
        return new DeviceSelectRequest(srv, id, callback);
    }

    public static PortSelectRequest newPortSelect(HdmiControlService srv, int id,
            IHdmiControlCallback callback) {
        return new PortSelectRequest(srv, id, callback);
    }

    // The last select request made by system/app. Note that we do not manage a list of requests
    // but just keep only the last one since it already invalidates the older ones.
    private SelectRequest mRequest;

    public void set(SelectRequest request) {
        mRequest = request;
    }

    public void process() {
        if (mRequest != null) {
            mRequest.process();
            clear();
        }
    }

    public void clear() {
        mRequest = null;
    }
}
