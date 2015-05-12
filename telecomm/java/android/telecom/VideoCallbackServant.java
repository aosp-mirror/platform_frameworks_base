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
 R* limitations under the License.
 */

package android.telecom;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IVideoCallback;

import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;

/**
 * A component that provides an RPC servant implementation of {@link IVideoCallback},
 * posting incoming messages on the main thread on a client-supplied delegate object.
 *
 * TODO: Generate this and similar classes using a compiler starting from AIDL interfaces.
 *
 * @hide
 */
final class VideoCallbackServant {
    private static final int MSG_RECEIVE_SESSION_MODIFY_REQUEST = 0;
    private static final int MSG_RECEIVE_SESSION_MODIFY_RESPONSE = 1;
    private static final int MSG_HANDLE_CALL_SESSION_EVENT = 2;
    private static final int MSG_CHANGE_PEER_DIMENSIONS = 3;
    private static final int MSG_CHANGE_CALL_DATA_USAGE = 4;
    private static final int MSG_CHANGE_CAMERA_CAPABILITIES = 5;
    private static final int MSG_CHANGE_VIDEO_QUALITY = 6;

    private final IVideoCallback mDelegate;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            try {
                internalHandleMessage(msg);
            } catch (RemoteException e) {
            }
        }

        // Internal method defined to centralize handling of RemoteException
        private void internalHandleMessage(Message msg) throws RemoteException {
            switch (msg.what) {
                case MSG_RECEIVE_SESSION_MODIFY_REQUEST: {
                    mDelegate.receiveSessionModifyRequest((VideoProfile) msg.obj);
                    break;
                }
                case MSG_RECEIVE_SESSION_MODIFY_RESPONSE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        mDelegate.receiveSessionModifyResponse(
                                args.argi1,
                                (VideoProfile) args.arg1,
                                (VideoProfile) args.arg2);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_HANDLE_CALL_SESSION_EVENT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        mDelegate.handleCallSessionEvent(args.argi1);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_CHANGE_PEER_DIMENSIONS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        mDelegate.changePeerDimensions(args.argi1, args.argi2);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_CHANGE_CALL_DATA_USAGE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        mDelegate.changeCallDataUsage((long) args.arg1);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_CHANGE_CAMERA_CAPABILITIES: {
                    mDelegate.changeCameraCapabilities((VideoProfile.CameraCapabilities) msg.obj);
                    break;
                }
                case MSG_CHANGE_VIDEO_QUALITY: {
                    mDelegate.changeVideoQuality(msg.arg1);
                    break;
                }
            }
        }
    };

    private final IVideoCallback mStub = new IVideoCallback.Stub() {
        @Override
        public void receiveSessionModifyRequest(VideoProfile videoProfile) throws RemoteException {
            mHandler.obtainMessage(MSG_RECEIVE_SESSION_MODIFY_REQUEST, videoProfile).sendToTarget();
        }

        @Override
        public void receiveSessionModifyResponse(int status, VideoProfile requestedProfile,
                VideoProfile responseProfile) throws RemoteException {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = status;
            args.arg1 = requestedProfile;
            args.arg2 = responseProfile;
            mHandler.obtainMessage(MSG_RECEIVE_SESSION_MODIFY_RESPONSE, args).sendToTarget();
        }

        @Override
        public void handleCallSessionEvent(int event) throws RemoteException {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = event;
            mHandler.obtainMessage(MSG_HANDLE_CALL_SESSION_EVENT, args).sendToTarget();
        }

        @Override
        public void changePeerDimensions(int width, int height) throws RemoteException {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = width;
            args.argi2 = height;
            mHandler.obtainMessage(MSG_CHANGE_PEER_DIMENSIONS, args).sendToTarget();
        }

        @Override
        public void changeCallDataUsage(long dataUsage) throws RemoteException {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = dataUsage;
            mHandler.obtainMessage(MSG_CHANGE_CALL_DATA_USAGE, args).sendToTarget();
        }

        @Override
        public void changeCameraCapabilities(
                VideoProfile.CameraCapabilities cameraCapabilities)
                throws RemoteException {
            mHandler.obtainMessage(MSG_CHANGE_CAMERA_CAPABILITIES, cameraCapabilities)
                    .sendToTarget();
        }

        @Override
        public void changeVideoQuality(int videoQuality) throws RemoteException {
            mHandler.obtainMessage(MSG_CHANGE_VIDEO_QUALITY, videoQuality, 0).sendToTarget();
        }
    };

    public VideoCallbackServant(IVideoCallback delegate) {
        mDelegate = delegate;
    }

    public IVideoCallback getStub() {
        return mStub;
    }
}
