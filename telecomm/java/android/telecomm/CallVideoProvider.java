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

import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.view.Surface;

import com.android.internal.telecomm.ICallVideoClient;
import com.android.internal.telecomm.ICallVideoProvider;

public abstract class CallVideoProvider {
    private static final int MSG_SET_CALL_VIDEO_CLIENT = 1;
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

    /**
     * Default handler used to consolidate binder method calls onto a single thread.
     */
    private final class CallVideoProviderHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CALL_VIDEO_CLIENT:
                    try {
                        ICallVideoClient callVideoClient =
                                ICallVideoClient.Stub.asInterface((IBinder) msg.obj);
                        RemoteCallVideoClient remoteCallVideoClient =
                                new RemoteCallVideoClient(callVideoClient);
                        onSetCallVideoClient(remoteCallVideoClient);
                    } catch (RemoteException ignored) {
                    }
                    break;
                case MSG_SET_CAMERA:
                    onSetCamera((String) msg.obj);
                    break;
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
                case MSG_SEND_SESSION_MODIFY_REQUEST:
                    onSendSessionModifyRequest((VideoCallProfile) msg.obj);
                    break;
                case MSG_SEND_SESSION_MODIFY_RESPONSE:
                    onSendSessionModifyResponse((VideoCallProfile) msg.obj);
                    break;
                case MSG_REQUEST_CAMERA_CAPABILITIES:
                    onRequestCameraCapabilities();
                    break;
                case MSG_REQUEST_CALL_DATA_USAGE:
                    onRequestCallDataUsage();
                    break;
                case MSG_SET_PAUSE_IMAGE:
                    onSetPauseImage((String) msg.obj);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Default ICallVideoProvider implementation.
     */
    private final class CallVideoProviderBinder extends ICallVideoProvider.Stub {
        public void setCallVideoClient(IBinder callVideoClientBinder) {
            mMessageHandler.obtainMessage(
                    MSG_SET_CALL_VIDEO_CLIENT, callVideoClientBinder).sendToTarget();
        }

        public void setCamera(String cameraId) {
            mMessageHandler.obtainMessage(MSG_SET_CAMERA, cameraId).sendToTarget();
        }

        public void setPreviewSurface(Surface surface) {
            mMessageHandler.obtainMessage(MSG_SET_PREVIEW_SURFACE, surface).sendToTarget();
        }

        public void setDisplaySurface(Surface surface) {
            mMessageHandler.obtainMessage(MSG_SET_DISPLAY_SURFACE, surface).sendToTarget();
        }

        public void setDeviceOrientation(int rotation) {
            mMessageHandler.obtainMessage(MSG_SET_DEVICE_ORIENTATION, rotation).sendToTarget();
        }

        public void setZoom(float value) {
            mMessageHandler.obtainMessage(MSG_SET_ZOOM, value).sendToTarget();
        }

        public void sendSessionModifyRequest(VideoCallProfile requestProfile) {
            mMessageHandler.obtainMessage(
                    MSG_SEND_SESSION_MODIFY_REQUEST, requestProfile).sendToTarget();
        }

        public void sendSessionModifyResponse(VideoCallProfile responseProfile) {
            mMessageHandler.obtainMessage(
                    MSG_SEND_SESSION_MODIFY_RESPONSE, responseProfile).sendToTarget();
        }

        public void requestCameraCapabilities() {
            mMessageHandler.obtainMessage(MSG_REQUEST_CAMERA_CAPABILITIES).sendToTarget();
        }

        public void requestCallDataUsage() {
            mMessageHandler.obtainMessage(MSG_REQUEST_CALL_DATA_USAGE).sendToTarget();
        }

        public void setPauseImage(String uri) {
            mMessageHandler.obtainMessage(MSG_SET_PAUSE_IMAGE, uri).sendToTarget();
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
     * Sets a remote interface for invoking callback methods in the InCallUI after performing
     * telephony actions.
     *
     * @param callVideoClient The call video client.
     */
    public abstract void onSetCallVideoClient(RemoteCallVideoClient callVideoClient);

    /**
     * Sets the camera to be used for video recording in a video call.
     *
     * @param cameraId The id of the camera.
     */
    public abstract void onSetCamera(String cameraId);

    /**
     * Sets the surface to be used for displaying a preview of what the user's camera is
     * currently capturing.  When video transmission is enabled, this is the video signal which is
     * sent to the remote device.
     *
     * @param surface The surface.
     */
    public abstract void onSetPreviewSurface(Surface surface);

    /**
     * Sets the surface to be used for displaying the video received from the remote device.
     *
     * @param surface The surface.
     */
    public abstract void onSetDisplaySurface(Surface surface);

    /**
     * Sets the device orientation, in degrees.  Assumes that a standard portrait orientation of the
     * device is 0 degrees.
     *
     * @param rotation The device orientation, in degrees.
     */
    public abstract void onSetDeviceOrientation(int rotation);

    /**
     * Sets camera zoom ratio.
     *
     * @param value The camera zoom ratio.
     */
    public abstract void onSetZoom(float value);

    /**
     * Issues a request to modify the properties of the current session.  The request is sent to
     * the remote device where it it handled by
     * {@link CallVideoClient#onReceiveSessionModifyRequest}.
     * Some examples of session modification requests: upgrade call from audio to video, downgrade
     * call from video to audio, pause video.
     *
     * @param requestProfile The requested call video properties.
     */
    public abstract void onSendSessionModifyRequest(VideoCallProfile requestProfile);

    /**
     * Provides a response to a request to change the current call session video
     * properties.
     * This is in response to a request the InCall UI has received via
     * {@link CallVideoClient#onReceiveSessionModifyRequest}.
     * The response is handled on the remove device by
     * {@link CallVideoClient#onReceiveSessionModifyResponse}.
     *
     * @param responseProfile The response call video properties.
     */
    public abstract void onSendSessionModifyResponse(VideoCallProfile responseProfile);

    /**
     * Issues a request to the video provider to retrieve the camera capabilities.
     * Camera capabilities are reported back to the caller via
     * {@link CallVideoClient#onHandleCameraCapabilitiesChange(CallCameraCapabilities)}.
     */
    public abstract void onRequestCameraCapabilities();

    /**
     * Issues a request to the video telephony framework to retrieve the cumulative data usage for
     * the current call.  Data usage is reported back to the caller via
     * {@link CallVideoClient#onUpdateCallDataUsage}.
     */
    public abstract void onRequestCallDataUsage();

    /**
     * Provides the video telephony framework with the URI of an image to be displayed to remote
     * devices when the video signal is paused.
     *
     * @param uri URI of image to display.
     */
    public abstract void onSetPauseImage(String uri);
}
