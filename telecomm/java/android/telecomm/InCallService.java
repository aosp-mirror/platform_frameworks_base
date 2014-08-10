/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IInCallAdapter;
import com.android.internal.telecomm.IInCallService;

import java.lang.String;

/**
 * This service is implemented by any app that wishes to provide the user-interface for managing
 * phone calls. Telecomm binds to this service while there exists a live (active or incoming) call,
 * and uses it to notify the in-call app of any live and and recently disconnected calls.
 * TODO: What happens if two or more apps on a given device implement this interface?
 */
public abstract class InCallService extends Service {
    private static final int MSG_SET_IN_CALL_ADAPTER = 1;
    private static final int MSG_ADD_CALL = 2;
    private static final int MSG_UPDATE_CALL = 3;
    private static final int MSG_SET_POST_DIAL_WAIT = 4;
    private static final int MSG_ON_AUDIO_STATE_CHANGED = 5;
    private static final int MSG_BRING_TO_FOREGROUND = 6;
    private static final int MSG_START_ACTIVITY = 7;

    /** Default Handler used to consolidate binder method calls onto a single thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_IN_CALL_ADAPTER:
                    mPhone = new Phone(new InCallAdapter((IInCallAdapter) msg.obj));
                    onPhoneCreated(mPhone);
                    break;
                case MSG_ADD_CALL:
                    mPhone.internalAddCall((ParcelableCall) msg.obj);
                    break;
                case MSG_UPDATE_CALL:
                    mPhone.internalUpdateCall((ParcelableCall) msg.obj);
                    break;
                case MSG_SET_POST_DIAL_WAIT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        String remaining = (String) args.arg2;
                        mPhone.internalSetPostDialWait(callId, remaining);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ON_AUDIO_STATE_CHANGED:
                    mPhone.internalAudioStateChanged((CallAudioState) msg.obj);
                    break;
                case MSG_BRING_TO_FOREGROUND:
                    mPhone.internalBringToForeground(msg.arg1 == 1);
                    break;
                case MSG_START_ACTIVITY:
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        mPhone.internalStartActivity(
                                (String) args.arg1, (PendingIntent) args.arg2);
                    } finally {
                        args.recycle();
                    }
                    break;
                default:
                    break;
            }
        }
    };

    /** Manages the binder calls so that the implementor does not need to deal with it. */
    private final class InCallServiceBinder extends IInCallService.Stub {
        @Override
        public void setInCallAdapter(IInCallAdapter inCallAdapter) {
            mHandler.obtainMessage(MSG_SET_IN_CALL_ADAPTER, inCallAdapter).sendToTarget();
        }

        @Override
        public void addCall(ParcelableCall call) {
            mHandler.obtainMessage(MSG_ADD_CALL, call).sendToTarget();
        }

        @Override
        public void updateCall(ParcelableCall call) {
            mHandler.obtainMessage(MSG_UPDATE_CALL, call).sendToTarget();
        }

        @Override
        public void setPostDial(String callId, String remaining) {
            // TODO: Unused
        }

        @Override
        public void setPostDialWait(String callId, String remaining) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = remaining;
            mHandler.obtainMessage(MSG_SET_POST_DIAL_WAIT, args).sendToTarget();
        }

        @Override
        public void onAudioStateChanged(CallAudioState audioState) {
            mHandler.obtainMessage(MSG_ON_AUDIO_STATE_CHANGED, audioState).sendToTarget();
        }

        @Override
        public void bringToForeground(boolean showDialpad) {
            mHandler.obtainMessage(MSG_BRING_TO_FOREGROUND, showDialpad ? 1 : 0, 0).sendToTarget();
        }

        @Override
        public void startActivity(String callId, PendingIntent intent) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = intent;
            mHandler.obtainMessage(MSG_START_ACTIVITY, args).sendToTarget();
        }
    }

    private Phone mPhone;

    public InCallService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new InCallServiceBinder();
    }

    /**
     * Obtain the {@code Phone} associated with this {@code InCallService}.
     *
     * @return The {@code Phone} object associated with this {@code InCallService}, or {@code null}
     *         if the {@code InCallService} is not in a state where it has an associated
     *         {@code Phone}.
     */
    public Phone getPhone() {
        return mPhone;
    }

    /**
     * Invoked when the {@code Phone} has been created. This is a signal to the in-call experience
     * to start displaying in-call information to the user. Each instance of {@code InCallService}
     * will have only one {@code Phone}, and this method will be called exactly once in the lifetime
     * of the {@code InCallService}.
     *
     * @param phone The {@code Phone} object associated with this {@code InCallService}.
     */
    public void onPhoneCreated(Phone phone) {
    }

    /**
     * Invoked when a {@code Phone} has been destroyed. This is a signal to the in-call experience
     * to stop displaying in-call information to the user. This method will be called exactly once
     * in the lifetime of the {@code InCallService}, and it will always be called after a previous
     * call to {@link #onPhoneCreated(Phone)}.
     *
     * @param phone The {@code Phone} object associated with this {@code InCallService}.
     */
    public void onPhoneDestroyed(Phone phone) {
    }

    /**
     * Class to invoke functionality related to video calls.
     */
    public static abstract class VideoCall {

        /**
         * Video is not being received (no protocol pause was issued).
         */
        public static final int SESSION_EVENT_RX_PAUSE = 1;

        /**
         * Video reception has resumed after a SESSION_EVENT_RX_PAUSE.
         */
        public static final int SESSION_EVENT_RX_RESUME = 2;

        /**
         * Video transmission has begun. This occurs after a negotiated start of video transmission
         * when the underlying protocol has actually begun transmitting video to the remote party.
         */
        public static final int SESSION_EVENT_TX_START = 3;

        /**
         * Video transmission has stopped. This occurs after a negotiated stop of video transmission
         * when the underlying protocol has actually stopped transmitting video to the remote party.
         */
        public static final int SESSION_EVENT_TX_STOP = 4;

        /**
         * A camera failure has occurred for the selected camera.  The In-Call UI can use this as a
         * cue to inform the user the camera is not available.
         */
        public static final int SESSION_EVENT_CAMERA_FAILURE = 5;

        /**
         * Issued after {@code SESSION_EVENT_CAMERA_FAILURE} when the camera is once again ready for
         * operation.  The In-Call UI can use this as a cue to inform the user that the camera has
         * become available again.
         */
        public static final int SESSION_EVENT_CAMERA_READY = 6;

        /**
         * Session modify request was successful.
         */
        public static final int SESSION_MODIFY_REQUEST_SUCCESS = 1;

        /**
         * Session modify request failed.
         */
        public static final int SESSION_MODIFY_REQUEST_FAIL = 2;

        /**
         * Session modify request ignored due to invalid parameters.
         */
        public static final int SESSION_MODIFY_REQUEST_INVALID = 3;

        /**
         * Sets a listener to invoke callback methods in the InCallUI after performing video
         * telephony actions.
         *
         * @param videoCallListener The call video client.
         */
        public abstract void setVideoCallListener(VideoCall.Listener videoCallListener);

        /**
         * Sets the camera to be used for video recording in a video call.
         *
         * @param cameraId The id of the camera.
         */
        public abstract void setCamera(String cameraId);

        /**
         * Sets the surface to be used for displaying a preview of what the user's camera is
         * currently capturing.  When video transmission is enabled, this is the video signal which
         * is sent to the remote device.
         *
         * @param surface The surface.
         */
        public abstract void setPreviewSurface(Surface surface);

        /**
         * Sets the surface to be used for displaying the video received from the remote device.
         *
         * @param surface The surface.
         */
        public abstract void setDisplaySurface(Surface surface);

        /**
         * Sets the device orientation, in degrees.  Assumes that a standard portrait orientation of
         * the device is 0 degrees.
         *
         * @param rotation The device orientation, in degrees.
         */
        public abstract void setDeviceOrientation(int rotation);

        /**
         * Sets camera zoom ratio.
         *
         * @param value The camera zoom ratio.
         */
        public abstract void setZoom(float value);

        /**
         * Issues a request to modify the properties of the current session.  The request is sent to
         * the remote device where it it handled by
         * {@link VideoCall.Listener#onSessionModifyRequestReceived}.
         * Some examples of session modification requests: upgrade call from audio to video,
         * downgrade call from video to audio, pause video.
         *
         * @param requestProfile The requested call video properties.
         */
        public abstract void sendSessionModifyRequest(VideoCallProfile requestProfile);

        /**
         * Provides a response to a request to change the current call session video
         * properties.
         * This is in response to a request the InCall UI has received via
         * {@link VideoCall.Listener#onSessionModifyRequestReceived}.
         * The response is handled on the remove device by
         * {@link VideoCall.Listener#onSessionModifyResponseReceived}.
         *
         * @param responseProfile The response call video properties.
         */
        public abstract void sendSessionModifyResponse(VideoCallProfile responseProfile);

        /**
         * Issues a request to the video provider to retrieve the camera capabilities.
         * Camera capabilities are reported back to the caller via
         * {@link VideoCall.Listener#onCameraCapabilitiesChanged(CallCameraCapabilities)}.
         */
        public abstract void requestCameraCapabilities();

        /**
         * Issues a request to the video telephony framework to retrieve the cumulative data usage for
         * the current call.  Data usage is reported back to the caller via
         * {@link VideoCall.Listener#onCallDataUsageChanged}.
         */
        public abstract void requestCallDataUsage();

        /**
         * Provides the video telephony framework with the URI of an image to be displayed to remote
         * devices when the video signal is paused.
         *
         * @param uri URI of image to display.
         */
        public abstract void setPauseImage(String uri);

        /**
         * Listener class which invokes callbacks after video call actions occur.
         */
        public static abstract class Listener {
            /**
             * Called when a session modification request is received from the remote device.
             * The remote request is sent via
             * {@link ConnectionService.VideoCallProvider#onSendSessionModifyRequest}. The InCall UI
             * is responsible for potentially prompting the user whether they wish to accept the new
             * call profile (e.g. prompt user if they wish to accept an upgrade from an audio to a
             * video call) and should call
             * {@link ConnectionService.VideoCallProvider#onSendSessionModifyResponse} to indicate
             * the video settings the user has agreed to.
             *
             * @param videoCallProfile The requested video call profile.
             */
            public abstract void onSessionModifyRequestReceived(VideoCallProfile videoCallProfile);

            /**
             * Called when a response to a session modification request is received from the remote
             * device. The remote InCall UI sends the response using
             * {@link ConnectionService.VideoCallProvider#onSendSessionModifyResponse}.
             *
             * @param status Status of the session modify request.  Valid values are
             *               {@link VideoCall#SESSION_MODIFY_REQUEST_SUCCESS},
             *               {@link VideoCall#SESSION_MODIFY_REQUEST_FAIL},
             *               {@link VideoCall#SESSION_MODIFY_REQUEST_INVALID}
             * @param requestedProfile The original request which was sent to the remote device.
             * @param responseProfile The actual profile changes made by the remote device.
             */
            public abstract void onSessionModifyResponseReceived(int status,
                    VideoCallProfile requestedProfile, VideoCallProfile responseProfile);

            /**
             * Handles events related to the current session which the client may wish to handle.
             * These are separate from requested changes to the session due to the underlying
             * protocol or connection.
             *
             * Valid values are: {@link VideoCall#SESSION_EVENT_RX_PAUSE},
             * {@link VideoCall#SESSION_EVENT_RX_RESUME},
             * {@link VideoCall#SESSION_EVENT_TX_START},
             * {@link VideoCall#SESSION_EVENT_TX_STOP},
             * {@link VideoCall#SESSION_EVENT_CAMERA_FAILURE},
             * {@link VideoCall#SESSION_EVENT_CAMERA_READY}
             *
             * @param event The event.
             */
            public abstract void onCallSessionEvent(int event);

            /**
             * Handles a change to the video dimensions from the remote caller (peer). This could
             * happen if, for example, the peer changes orientation of their device.
             *
             * @param width  The updated peer video width.
             * @param height The updated peer video height.
             */
            public abstract void onPeerDimensionsChanged(int width, int height);

            /**
             * Handles an update to the total data used for the current session.
             *
             * @param dataUsage The updated data usage.
             */
            public abstract void onCallDataUsageChanged(int dataUsage);

            /**
             * Handles a change in camera capabilities.
             *
             * @param callCameraCapabilities The changed camera capabilities.
             */
            public abstract void onCameraCapabilitiesChanged(
                    CallCameraCapabilities callCameraCapabilities);
        }
    }
}
