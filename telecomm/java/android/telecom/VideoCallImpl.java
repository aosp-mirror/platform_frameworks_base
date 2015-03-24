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

package android.telecom;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.InCallService.VideoCall;
import android.view.Surface;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IVideoCallback;
import com.android.internal.telecom.IVideoProvider;

/**
 * Implementation of a Video Call, which allows InCallUi to communicate commands to the underlying
 * {@link Connection.VideoProvider}, and direct callbacks from the
 * {@link Connection.VideoProvider} to the appropriate {@link VideoCall.Listener}.
 *
 * {@hide}
 */
public class VideoCallImpl extends VideoCall {
    private static final int MSG_RECEIVE_SESSION_MODIFY_REQUEST = 1;
    private static final int MSG_RECEIVE_SESSION_MODIFY_RESPONSE = 2;
    private static final int MSG_HANDLE_CALL_SESSION_EVENT = 3;
    private static final int MSG_CHANGE_PEER_DIMENSIONS = 4;
    private static final int MSG_CHANGE_CALL_DATA_USAGE = 5;
    private static final int MSG_CHANGE_CAMERA_CAPABILITIES = 6;
    private static final int MSG_CHANGE_VIDEO_QUALITY = 7;

    private final IVideoProvider mVideoProvider;
    private final VideoCallListenerBinder mBinder;
    private VideoCall.Listener mVideoCallListener;

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mVideoProvider.asBinder().unlinkToDeath(this, 0);
        }
    };

    /**
     * IVideoCallback stub implementation.
     */
    private final class VideoCallListenerBinder extends IVideoCallback.Stub {
        @Override
        public void receiveSessionModifyRequest(VideoProfile videoProfile) {
            mHandler.obtainMessage(MSG_RECEIVE_SESSION_MODIFY_REQUEST,
                    videoProfile).sendToTarget();
        }

        @Override
        public void receiveSessionModifyResponse(int status, VideoProfile requestProfile,
                VideoProfile responseProfile) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = status;
            args.arg2 = requestProfile;
            args.arg3 = responseProfile;
            mHandler.obtainMessage(MSG_RECEIVE_SESSION_MODIFY_RESPONSE, args).sendToTarget();
        }

        @Override
        public void handleCallSessionEvent(int event) {
            mHandler.obtainMessage(MSG_HANDLE_CALL_SESSION_EVENT, event).sendToTarget();
        }

        @Override
        public void changePeerDimensions(int width, int height) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = width;
            args.arg2 = height;
            mHandler.obtainMessage(MSG_CHANGE_PEER_DIMENSIONS, args).sendToTarget();
        }

        @Override
        public void changeVideoQuality(int videoQuality) {
            mHandler.obtainMessage(MSG_CHANGE_VIDEO_QUALITY, videoQuality, 0).sendToTarget();
        }

        @Override
        public void changeCallDataUsage(long dataUsage) {
            mHandler.obtainMessage(MSG_CHANGE_CALL_DATA_USAGE, dataUsage).sendToTarget();
        }

        @Override
        public void changeCameraCapabilities(CameraCapabilities cameraCapabilities) {
            mHandler.obtainMessage(MSG_CHANGE_CAMERA_CAPABILITIES,
                    cameraCapabilities).sendToTarget();
        }
    }

    /** Default handler used to consolidate binder method calls onto a single thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (mVideoCallListener == null) {
                return;
            }

            SomeArgs args;
            switch (msg.what) {
                case MSG_RECEIVE_SESSION_MODIFY_REQUEST:
                    mVideoCallListener.onSessionModifyRequestReceived((VideoProfile) msg.obj);
                    break;
                case MSG_RECEIVE_SESSION_MODIFY_RESPONSE:
                    args = (SomeArgs) msg.obj;
                    try {
                        int status = (int) args.arg1;
                        VideoProfile requestProfile = (VideoProfile) args.arg2;
                        VideoProfile responseProfile = (VideoProfile) args.arg3;

                        mVideoCallListener.onSessionModifyResponseReceived(
                                status, requestProfile, responseProfile);
                    } finally {
                        args.recycle();
                    }
                    break;
                case MSG_HANDLE_CALL_SESSION_EVENT:
                    mVideoCallListener.onCallSessionEvent((int) msg.obj);
                    break;
                case MSG_CHANGE_PEER_DIMENSIONS:
                    args = (SomeArgs) msg.obj;
                    try {
                        int width = (int) args.arg1;
                        int height = (int) args.arg2;
                        mVideoCallListener.onPeerDimensionsChanged(width, height);
                    } finally {
                        args.recycle();
                    }
                    break;
                case MSG_CHANGE_CALL_DATA_USAGE:
                    mVideoCallListener.onCallDataUsageChanged((long) msg.obj);
                    break;
                case MSG_CHANGE_CAMERA_CAPABILITIES:
                    mVideoCallListener.onCameraCapabilitiesChanged(
                            (CameraCapabilities) msg.obj);
                    break;
                case MSG_CHANGE_VIDEO_QUALITY:
                    mVideoCallListener.onVideoQualityChanged(msg.arg1);
                    break;
                default:
                    break;
            }
        }
    };

    /** {@hide} */
    VideoCallImpl(IVideoProvider videoProvider) throws RemoteException {
        mVideoProvider = videoProvider;
        mVideoProvider.asBinder().linkToDeath(mDeathRecipient, 0);

        mBinder = new VideoCallListenerBinder();
        mVideoProvider.setVideoCallback(mBinder);
    }

    /** {@inheritDoc} */
    public void setVideoCallListener(VideoCall.Listener videoCallListener) {
        mVideoCallListener = videoCallListener;
    }

    /** {@inheritDoc} */
    public void setCamera(String cameraId) {
        try {
            mVideoProvider.setCamera(cameraId);
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void setPreviewSurface(Surface surface) {
        try {
            mVideoProvider.setPreviewSurface(surface);
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void setDisplaySurface(Surface surface) {
        try {
            mVideoProvider.setDisplaySurface(surface);
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void setDeviceOrientation(int rotation) {
        try {
            mVideoProvider.setDeviceOrientation(rotation);
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void setZoom(float value) {
        try {
            mVideoProvider.setZoom(value);
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void sendSessionModifyRequest(VideoProfile requestProfile) {
        try {
            mVideoProvider.sendSessionModifyRequest(requestProfile);
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void sendSessionModifyResponse(VideoProfile responseProfile) {
        try {
            mVideoProvider.sendSessionModifyResponse(responseProfile);
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void requestCameraCapabilities() {
        try {
            mVideoProvider.requestCameraCapabilities();
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void requestCallDataUsage() {
        try {
            mVideoProvider.requestCallDataUsage();
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void setPauseImage(String uri) {
        try {
            mVideoProvider.setPauseImage(uri);
        } catch (RemoteException e) {
        }
    }
}
