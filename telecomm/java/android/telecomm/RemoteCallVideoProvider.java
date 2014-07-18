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

package android.telecomm;

import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;

import com.android.internal.telecomm.ICallVideoProvider;

/**
 * Remote class for InCallUI to invoke functionality provided for video in calls.
 */
public class RemoteCallVideoProvider {
    private final ICallVideoProvider mCallVideoProvider;

    private IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mCallVideoProvider.asBinder().unlinkToDeath(this, 0);
        }
    };

    /** {@hide} */
    RemoteCallVideoProvider(ICallVideoProvider callVideoProvider) throws RemoteException {
        mCallVideoProvider = callVideoProvider;
        mCallVideoProvider.asBinder().linkToDeath(mDeathRecipient, 0);
    }

    /**
     * Sets a remote interface for invoking callback methods in the InCallUI after performing
     * telephony actions.
     *
     * @param callVideoClient The call video client.
     */
    public void setCallVideoClient(CallVideoClient callVideoClient) {
        try {
            mCallVideoProvider.setCallVideoClient(callVideoClient.getBinder());
        } catch (RemoteException e) {
        }
    }

    /**
     * Sets the camera to be used for video recording in a video call.
     *
     * @param cameraId The id of the camera.
     */
    public void setCamera(String cameraId) throws RemoteException {
        mCallVideoProvider.setCamera(cameraId);
    }

    /**
     * Sets the surface to be used for displaying a preview of what the user's camera is
     * currently capturing.  When video transmission is enabled, this is the video signal which is
     * sent to the remote device.
     *
     * @param surface The surface.
     */
    public void setPreviewSurface(Surface surface) {
        try {
            mCallVideoProvider.setPreviewSurface(surface);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sets the surface to be used for displaying the video received from the remote device.
     *
     * @param surface The surface.
     */
    public void setDisplaySurface(Surface surface) {
        try {
            mCallVideoProvider.setDisplaySurface(surface);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sets the device orientation, in degrees.  Assumes that a standard portrait orientation of the
     * device is 0 degrees.
     *
     * @param rotation The device orientation, in degrees.
     */
    public void setDeviceOrientation(int rotation) {
        try {
            mCallVideoProvider.setDeviceOrientation(rotation);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sets camera zoom ratio.
     *
     * @param value The camera zoom ratio.
     */
    public void setZoom(float value) throws RemoteException {
        mCallVideoProvider.setZoom(value);
    }

    /**
     * Issues a request to modify the properties of the current session.  The request is sent to
     * the remote device where it it handled by
     * {@link CallVideoClient#onReceiveSessionModifyRequest}.
     * Some examples of session modification requests: upgrade call from audio to video, downgrade
     * call from video to audio, pause video.
     *
     * @param requestProfile The requested call video properties.
     */
    public void sendSessionModifyRequest(VideoCallProfile requestProfile) {
        try {
            mCallVideoProvider.sendSessionModifyRequest(requestProfile);
        } catch (RemoteException e) {
        }
    }

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
    public void sendSessionModifyResponse(VideoCallProfile responseProfile) {
        try {
            mCallVideoProvider.sendSessionModifyResponse(responseProfile);
        } catch (RemoteException e) {
        }
    }

    /**
     * Issues a request to the video provider to retrieve the camera capabilities.
     * Camera capabilities are reported back to the caller via
     * {@link CallVideoClient#onHandleCameraCapabilitiesChange(CallCameraCapabilities)}.
     */
    public void requestCameraCapabilities() {
        try {
            mCallVideoProvider.requestCameraCapabilities();
        } catch (RemoteException e) {
        }
    }

    /**
     * Issues a request to the video telephony framework to retrieve the cumulative data usage for
     * the current call.  Data usage is reported back to the caller via
     * {@link CallVideoClient#onUpdateCallDataUsage}.
     */
    public void requestCallDataUsage() {
        try {
            mCallVideoProvider.requestCallDataUsage();
        } catch (RemoteException e) {
        }
    }

    /**
     * Provides the video telephony framework with the URI of an image to be displayed to remote
     * devices when the video signal is paused.
     *
     * @param uri URI of image to display.
     */
    public void setPauseImage(String uri) {
        try {
            mCallVideoProvider.setPauseImage(uri);
        } catch (RemoteException e) {
        }
    }
}