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

import android.compat.annotation.UnsupportedAppUsage;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.InCallService.VideoCall;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IVideoCallback;
import com.android.internal.telecom.IVideoProvider;

import java.util.NoSuchElementException;

/**
 * Implementation of a Video Call, which allows InCallUi to communicate commands to the underlying
 * {@link Connection.VideoProvider}, and direct callbacks from the
 * {@link Connection.VideoProvider} to the appropriate {@link VideoCall.Listener}.
 *
 * {@hide}
 */
public class VideoCallImpl extends VideoCall {

    private final IVideoProvider mVideoProvider;
    private final VideoCallListenerBinder mBinder;
    private VideoCall.Callback mCallback;
    private int mVideoQuality = VideoProfile.QUALITY_UNKNOWN;
    private int mVideoState = VideoProfile.STATE_AUDIO_ONLY;
    private final String mCallingPackageName;

    private int mTargetSdkVersion;

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            try {
                mVideoProvider.asBinder().unlinkToDeath(this, 0);
            } catch (NoSuchElementException nse) {
                // Already unlinked in destroy below.
            }
        }
    };

    /**
     * IVideoCallback stub implementation.
     */
    private final class VideoCallListenerBinder extends IVideoCallback.Stub {
        @Override
        public void receiveSessionModifyRequest(VideoProfile videoProfile) {
            if (mHandler == null) {
                return;
            }
            mHandler.obtainMessage(MessageHandler.MSG_RECEIVE_SESSION_MODIFY_REQUEST,
                    videoProfile).sendToTarget();

        }

        @Override
        public void receiveSessionModifyResponse(int status, VideoProfile requestProfile,
                VideoProfile responseProfile) {
            if (mHandler == null) {
                return;
            }
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = status;
            args.arg2 = requestProfile;
            args.arg3 = responseProfile;
            mHandler.obtainMessage(MessageHandler.MSG_RECEIVE_SESSION_MODIFY_RESPONSE, args)
                    .sendToTarget();
        }

        @Override
        public void handleCallSessionEvent(int event) {
            if (mHandler == null) {
                return;
            }
            mHandler.obtainMessage(MessageHandler.MSG_HANDLE_CALL_SESSION_EVENT, event)
                    .sendToTarget();
        }

        @Override
        public void changePeerDimensions(int width, int height) {
            if (mHandler == null) {
                return;
            }
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = width;
            args.arg2 = height;
            mHandler.obtainMessage(MessageHandler.MSG_CHANGE_PEER_DIMENSIONS, args).sendToTarget();
        }

        @Override
        public void changeVideoQuality(int videoQuality) {
            if (mHandler == null) {
                return;
            }
            mHandler.obtainMessage(MessageHandler.MSG_CHANGE_VIDEO_QUALITY, videoQuality, 0)
                    .sendToTarget();
        }

        @Override
        public void changeCallDataUsage(long dataUsage) {
            if (mHandler == null) {
                return;
            }
            mHandler.obtainMessage(MessageHandler.MSG_CHANGE_CALL_DATA_USAGE, dataUsage)
                    .sendToTarget();
        }

        @Override
        public void changeCameraCapabilities(VideoProfile.CameraCapabilities cameraCapabilities) {
            if (mHandler == null) {
                return;
            }
            mHandler.obtainMessage(MessageHandler.MSG_CHANGE_CAMERA_CAPABILITIES,
                    cameraCapabilities).sendToTarget();
        }
    }

    /** Default handler used to consolidate binder method calls onto a single thread. */
    private final class MessageHandler extends Handler {
        private static final int MSG_RECEIVE_SESSION_MODIFY_REQUEST = 1;
        private static final int MSG_RECEIVE_SESSION_MODIFY_RESPONSE = 2;
        private static final int MSG_HANDLE_CALL_SESSION_EVENT = 3;
        private static final int MSG_CHANGE_PEER_DIMENSIONS = 4;
        private static final int MSG_CHANGE_CALL_DATA_USAGE = 5;
        private static final int MSG_CHANGE_CAMERA_CAPABILITIES = 6;
        private static final int MSG_CHANGE_VIDEO_QUALITY = 7;

        public MessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (mCallback == null) {
                return;
            }

            SomeArgs args;
            switch (msg.what) {
                case MSG_RECEIVE_SESSION_MODIFY_REQUEST:
                    mCallback.onSessionModifyRequestReceived((VideoProfile) msg.obj);
                    break;
                case MSG_RECEIVE_SESSION_MODIFY_RESPONSE:
                    args = (SomeArgs) msg.obj;
                    try {
                        int status = (int) args.arg1;
                        VideoProfile requestProfile = (VideoProfile) args.arg2;
                        VideoProfile responseProfile = (VideoProfile) args.arg3;

                        mCallback.onSessionModifyResponseReceived(
                                status, requestProfile, responseProfile);
                    } finally {
                        args.recycle();
                    }
                    break;
                case MSG_HANDLE_CALL_SESSION_EVENT:
                    mCallback.onCallSessionEvent((int) msg.obj);
                    break;
                case MSG_CHANGE_PEER_DIMENSIONS:
                    args = (SomeArgs) msg.obj;
                    try {
                        int width = (int) args.arg1;
                        int height = (int) args.arg2;
                        mCallback.onPeerDimensionsChanged(width, height);
                    } finally {
                        args.recycle();
                    }
                    break;
                case MSG_CHANGE_CALL_DATA_USAGE:
                    mCallback.onCallDataUsageChanged((long) msg.obj);
                    break;
                case MSG_CHANGE_CAMERA_CAPABILITIES:
                    mCallback.onCameraCapabilitiesChanged(
                            (VideoProfile.CameraCapabilities) msg.obj);
                    break;
                case MSG_CHANGE_VIDEO_QUALITY:
                    mVideoQuality = msg.arg1;
                    mCallback.onVideoQualityChanged(msg.arg1);
                    break;
                default:
                    break;
            }
        }
    };

    private Handler mHandler;

    VideoCallImpl(IVideoProvider videoProvider, String callingPackageName, int targetSdkVersion)
            throws RemoteException {
        mVideoProvider = videoProvider;
        mVideoProvider.asBinder().linkToDeath(mDeathRecipient, 0);

        mBinder = new VideoCallListenerBinder();
        mVideoProvider.addVideoCallback(mBinder);
        mCallingPackageName = callingPackageName;
        setTargetSdkVersion(targetSdkVersion);
    }

    @VisibleForTesting
    public void setTargetSdkVersion(int sdkVersion) {
        mTargetSdkVersion = sdkVersion;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 127403196)
    public void destroy() {
        unregisterCallback(mCallback);
        try {
            mVideoProvider.asBinder().unlinkToDeath(mDeathRecipient, 0);
        } catch (NoSuchElementException nse) {
            // Already unlinked in binderDied above.
        }
    }

    /** {@inheritDoc} */
    public void registerCallback(VideoCall.Callback callback) {
        registerCallback(callback, null);
    }

    /** {@inheritDoc} */
    public void registerCallback(VideoCall.Callback callback, Handler handler) {
        mCallback = callback;
        if (handler == null) {
            mHandler = new MessageHandler(Looper.getMainLooper());
        } else {
            mHandler = new MessageHandler(handler.getLooper());
        }
    }

    /** {@inheritDoc} */
    public void unregisterCallback(VideoCall.Callback callback) {
        if (callback != mCallback) {
            return;
        }

        mCallback = null;
        try {
            mVideoProvider.removeVideoCallback(mBinder);
        } catch (RemoteException e) {
        }
    }

    /** {@inheritDoc} */
    public void setCamera(String cameraId) {
        try {
            Log.w(this, "setCamera: cameraId=%s, calling=%s", cameraId, mCallingPackageName);
            mVideoProvider.setCamera(cameraId, mCallingPackageName, mTargetSdkVersion);
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

    /**
     * Sends a session modification request to the video provider.
     * <p>
     * The {@link InCallService} will create the {@code requestProfile} based on the current
     * video state (i.e. {@link Call.Details#getVideoState()}).  It is, however, possible that the
     * video state maintained by the {@link InCallService} could get out of sync with what is known
     * by the {@link android.telecom.Connection.VideoProvider}.  To remove ambiguity, the
     * {@link VideoCallImpl} passes along the pre-modify video profile to the {@code VideoProvider}
     * to ensure it has full context of the requested change.
     *
     * @param requestProfile The requested video profile.
     */
    public void sendSessionModifyRequest(VideoProfile requestProfile) {
        try {
            VideoProfile originalProfile = new VideoProfile(mVideoState, mVideoQuality);

            mVideoProvider.sendSessionModifyRequest(originalProfile, requestProfile);
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
    public void setPauseImage(Uri uri) {
        try {
            mVideoProvider.setPauseImage(uri);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sets the video state for the current video call.
     * @param videoState the new video state.
     */
    public void setVideoState(int videoState) {
        mVideoState = videoState;
    }

    /**
     * Get the video provider binder.
     * @return the video provider binder.
     */
    public IVideoProvider getVideoProvider() {
        return mVideoProvider;
    }
}
