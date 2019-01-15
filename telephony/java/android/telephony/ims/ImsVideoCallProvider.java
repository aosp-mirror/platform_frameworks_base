/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.Connection;
import android.telecom.VideoProfile;
import android.telecom.VideoProfile.CameraCapabilities;
import android.view.Surface;

import com.android.ims.internal.IImsVideoCallCallback;
import com.android.ims.internal.IImsVideoCallProvider;
import com.android.internal.os.SomeArgs;

/**
 * @hide
 */
@SystemApi
public abstract class ImsVideoCallProvider {
    private static final int MSG_SET_CALLBACK = 1;
    private static final int MSG_SET_CAMERA = 2;
    private static final int MSG_SET_PREVIEW_SURFACE = 3;
    private static final int MSG_SET_DISPLAY_SURFACE = 4;
    private static final int MSG_SET_DEVICE_ORIENTATION = 5;
    private static final int MSG_SET_ZOOM = 6;
    private static final int MSG_SEND_SESSION_MODIFY_REQUEST = 7;
    private static final int MSG_SEND_SESSION_MODIFY_RESPONSE = 8;
    private static final int MSG_REQUEST_CAMERA_CAPABILITIES = 9;
    private static final int MSG_REQUEST_CALL_DATA_USAGE = 10;
    private static final int MSG_SET_PAUSE_IMAGE = 11;

    private final ImsVideoCallProviderBinder mBinder;

    private IImsVideoCallCallback mCallback;

    /**
     * Default handler used to consolidate binder method calls onto a single thread.
     */
    private final Handler mProviderHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CALLBACK:
                    mCallback = (IImsVideoCallCallback) msg.obj;
                    break;
                case MSG_SET_CAMERA:
                {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        onSetCamera((String) args.arg1);
                        onSetCamera((String) args.arg1, args.argi1);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_PREVIEW_SURFACE:
                    onSetPreviewSurface((Surface) msg.obj);
                    break;
                case MSG_SET_DISPLAY_SURFACE:
                    onSetDisplaySurface((Surface) msg.obj);
                    break;
                case MSG_SET_DEVICE_ORIENTATION:
                    onSetDeviceOrientation(msg.arg1);
                    break;
                case MSG_SET_ZOOM:
                    onSetZoom((Float) msg.obj);
                    break;
                case MSG_SEND_SESSION_MODIFY_REQUEST: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        VideoProfile fromProfile = (VideoProfile) args.arg1;
                        VideoProfile toProfile = (VideoProfile) args.arg2;

                        onSendSessionModifyRequest(fromProfile, toProfile);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SEND_SESSION_MODIFY_RESPONSE:
                    onSendSessionModifyResponse((VideoProfile) msg.obj);
                    break;
                case MSG_REQUEST_CAMERA_CAPABILITIES:
                    onRequestCameraCapabilities();
                    break;
                case MSG_REQUEST_CALL_DATA_USAGE:
                    onRequestCallDataUsage();
                    break;
                case MSG_SET_PAUSE_IMAGE:
                    onSetPauseImage((Uri) msg.obj);
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * IImsVideoCallProvider stub implementation.
     */
    private final class ImsVideoCallProviderBinder extends IImsVideoCallProvider.Stub {
        public void setCallback(IImsVideoCallCallback callback) {
            mProviderHandler.obtainMessage(MSG_SET_CALLBACK, callback).sendToTarget();
        }

        public void setCamera(String cameraId, int uid) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = cameraId;
            args.argi1 = uid;
            mProviderHandler.obtainMessage(MSG_SET_CAMERA, args).sendToTarget();
        }

        public void setPreviewSurface(Surface surface) {
            mProviderHandler.obtainMessage(MSG_SET_PREVIEW_SURFACE, surface).sendToTarget();
        }

        public void setDisplaySurface(Surface surface) {
            mProviderHandler.obtainMessage(MSG_SET_DISPLAY_SURFACE, surface).sendToTarget();
        }

        public void setDeviceOrientation(int rotation) {
            mProviderHandler.obtainMessage(MSG_SET_DEVICE_ORIENTATION, rotation, 0).sendToTarget();
        }

        public void setZoom(float value) {
            mProviderHandler.obtainMessage(MSG_SET_ZOOM, value).sendToTarget();
        }

        public void sendSessionModifyRequest(VideoProfile fromProfile, VideoProfile toProfile) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = fromProfile;
            args.arg2 = toProfile;
            mProviderHandler.obtainMessage(MSG_SEND_SESSION_MODIFY_REQUEST, args).sendToTarget();
        }

        public void sendSessionModifyResponse(VideoProfile responseProfile) {
            mProviderHandler.obtainMessage(
                    MSG_SEND_SESSION_MODIFY_RESPONSE, responseProfile).sendToTarget();
        }

        public void requestCameraCapabilities() {
            mProviderHandler.obtainMessage(MSG_REQUEST_CAMERA_CAPABILITIES).sendToTarget();
        }

        public void requestCallDataUsage() {
            mProviderHandler.obtainMessage(MSG_REQUEST_CALL_DATA_USAGE).sendToTarget();
        }

        public void setPauseImage(Uri uri) {
            mProviderHandler.obtainMessage(MSG_SET_PAUSE_IMAGE, uri).sendToTarget();
        }
    }

    public ImsVideoCallProvider() {
        mBinder = new ImsVideoCallProviderBinder();
    }

    /**
     * Returns binder object which can be used across IPC methods.
     * @hide
     */
    @UnsupportedAppUsage
    public final IImsVideoCallProvider getInterface() {
        return mBinder;
    }

    /** @see Connection.VideoProvider#onSetCamera */
    public abstract void onSetCamera(String cameraId);

    /**
     * Similar to {@link #onSetCamera(String)}, except includes the UID of the calling process which
     * the IMS service uses when opening the camera.  This ensures camera permissions are verified
     * by the camera service.
     *
     * @param cameraId The id of the camera to be opened.
     * @param uid The uid of the caller, used when opening the camera for permission verification.
     * @see Connection.VideoProvider#onSetCamera
     */
    public void onSetCamera(String cameraId, int uid) {
    }

    /** @see Connection.VideoProvider#onSetPreviewSurface */
    public abstract void onSetPreviewSurface(Surface surface);

    /** @see Connection.VideoProvider#onSetDisplaySurface */
    public abstract void onSetDisplaySurface(Surface surface);

    /** @see Connection.VideoProvider#onSetDeviceOrientation */
    public abstract void onSetDeviceOrientation(int rotation);

    /** @see Connection.VideoProvider#onSetZoom */
    public abstract void onSetZoom(float value);

    /** @see Connection.VideoProvider#onSendSessionModifyRequest */
    public abstract void onSendSessionModifyRequest(VideoProfile fromProfile,
            VideoProfile toProfile);

    /** @see Connection.VideoProvider#onSendSessionModifyResponse */
    public abstract void onSendSessionModifyResponse(VideoProfile responseProfile);

    /** @see Connection.VideoProvider#onRequestCameraCapabilities */
    public abstract void onRequestCameraCapabilities();

    /** @see Connection.VideoProvider#onRequestCallDataUsage */
    public abstract void onRequestCallDataUsage();

    /** @see Connection.VideoProvider#onSetPauseImage */
    public abstract void onSetPauseImage(Uri uri);

    /** @see Connection.VideoProvider#receiveSessionModifyRequest */
    public void receiveSessionModifyRequest(VideoProfile VideoProfile) {
        if (mCallback != null) {
            try {
                mCallback.receiveSessionModifyRequest(VideoProfile);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** @see Connection.VideoProvider#receiveSessionModifyResponse */
    public void receiveSessionModifyResponse(
            int status, VideoProfile requestedProfile, VideoProfile responseProfile) {
        if (mCallback != null) {
            try {
                mCallback.receiveSessionModifyResponse(status, requestedProfile, responseProfile);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** @see Connection.VideoProvider#handleCallSessionEvent */
    public void handleCallSessionEvent(int event) {
        if (mCallback != null) {
            try {
                mCallback.handleCallSessionEvent(event);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** @see Connection.VideoProvider#changePeerDimensions */
    public void changePeerDimensions(int width, int height) {
        if (mCallback != null) {
            try {
                mCallback.changePeerDimensions(width, height);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** @see Connection.VideoProvider#changeCallDataUsage */
    public void changeCallDataUsage(long dataUsage) {
        if (mCallback != null) {
            try {
                mCallback.changeCallDataUsage(dataUsage);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** @see Connection.VideoProvider#changeCameraCapabilities */
    public void changeCameraCapabilities(CameraCapabilities CameraCapabilities) {
        if (mCallback != null) {
            try {
                mCallback.changeCameraCapabilities(CameraCapabilities);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** @see Connection.VideoProvider#changeVideoQuality */
    public void changeVideoQuality(int videoQuality) {
        if (mCallback != null) {
            try {
                mCallback.changeVideoQuality(videoQuality);
            } catch (RemoteException ignored) {
            }
        }
    }
}
