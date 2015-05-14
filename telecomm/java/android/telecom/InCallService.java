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

package android.telecom;

import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecom.IInCallAdapter;
import com.android.internal.telecom.IInCallService;

import java.lang.String;
import java.util.Collections;
import java.util.List;

/**
 * This service is implemented by any app that wishes to provide the user-interface for managing
 * phone calls. Telecom binds to this service while there exists a live (active or incoming) call,
 * and uses it to notify the in-call app of any live and and recently disconnected calls.
 */
public abstract class InCallService extends Service {

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.InCallService";

    private static final int MSG_SET_IN_CALL_ADAPTER = 1;
    private static final int MSG_ADD_CALL = 2;
    private static final int MSG_UPDATE_CALL = 3;
    private static final int MSG_SET_POST_DIAL_WAIT = 4;
    private static final int MSG_ON_AUDIO_STATE_CHANGED = 5;
    private static final int MSG_BRING_TO_FOREGROUND = 6;
    private static final int MSG_ON_CAN_ADD_CALL_CHANGED = 7;

    /** Default Handler used to consolidate binder method calls onto a single thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (mPhone == null && msg.what != MSG_SET_IN_CALL_ADAPTER) {
                return;
            }

            switch (msg.what) {
                case MSG_SET_IN_CALL_ADAPTER:
                    mPhone = new Phone(new InCallAdapter((IInCallAdapter) msg.obj));
                    mPhone.addListener(mPhoneListener);
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
                    mPhone.internalAudioStateChanged((AudioState) msg.obj);
                    break;
                case MSG_BRING_TO_FOREGROUND:
                    mPhone.internalBringToForeground(msg.arg1 == 1);
                    break;
                case MSG_ON_CAN_ADD_CALL_CHANGED:
                    mPhone.internalSetCanAddCall(msg.arg1 == 1);
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
        public void onAudioStateChanged(AudioState audioState) {
            mHandler.obtainMessage(MSG_ON_AUDIO_STATE_CHANGED, audioState).sendToTarget();
        }

        @Override
        public void bringToForeground(boolean showDialpad) {
            mHandler.obtainMessage(MSG_BRING_TO_FOREGROUND, showDialpad ? 1 : 0, 0).sendToTarget();
        }

        @Override
        public void onCanAddCallChanged(boolean canAddCall) {
            mHandler.obtainMessage(MSG_ON_CAN_ADD_CALL_CHANGED, canAddCall ? 1 : 0, 0)
                    .sendToTarget();
        }
    }

    private Phone.Listener mPhoneListener = new Phone.Listener() {
        /** ${inheritDoc} */
        @Override
        public void onAudioStateChanged(Phone phone, AudioState audioState) {
            InCallService.this.onAudioStateChanged(audioState);
        }

        /** ${inheritDoc} */
        @Override
        public void onBringToForeground(Phone phone, boolean showDialpad) {
            InCallService.this.onBringToForeground(showDialpad);
        }

        /** ${inheritDoc} */
        @Override
        public void onCallAdded(Phone phone, Call call) {
            InCallService.this.onCallAdded(call);
        }

        /** ${inheritDoc} */
        @Override
        public void onCallRemoved(Phone phone, Call call) {
            InCallService.this.onCallRemoved(call);
        }

        /** ${inheritDoc} */
        @Override
        public void onCanAddCallChanged(Phone phone, boolean canAddCall) {
            InCallService.this.onCanAddCallChanged(canAddCall);
        }

    };

    private Phone mPhone;

    public InCallService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new InCallServiceBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (mPhone != null) {
            Phone oldPhone = mPhone;
            mPhone = null;

            oldPhone.destroy();
            // destroy sets all the calls to disconnected if any live ones still exist. Therefore,
            // it is important to remove the Listener *after* the call to destroy so that
            // InCallService.on* callbacks are appropriately called.
            oldPhone.removeListener(mPhoneListener);

            onPhoneDestroyed(oldPhone);
        }

        return false;
    }

    /**
     * Obtain the {@code Phone} associated with this {@code InCallService}.
     *
     * @return The {@code Phone} object associated with this {@code InCallService}, or {@code null}
     *         if the {@code InCallService} is not in a state where it has an associated
     *         {@code Phone}.
     * @hide
     * @deprecated Use direct methods on InCallService instead of {@link Phone}.
     */
    @SystemApi
    @Deprecated
    public Phone getPhone() {
        return mPhone;
    }

    /**
     * Obtains the current list of {@code Call}s to be displayed by this in-call experience.
     *
     * @return A list of the relevant {@code Call}s.
     */
    public final List<Call> getCalls() {
        return mPhone == null ? Collections.<Call>emptyList() : mPhone.getCalls();
    }

    /**
     * Returns if the device can support additional calls.
     *
     * @return Whether the phone supports adding more calls.
     */
    public final boolean canAddCall() {
        return mPhone == null ? false : mPhone.canAddCall();
    }

    /**
     * Obtains the current phone call audio state.
     *
     * @return An object encapsulating the audio state. Returns null if the service is not
     *         fully initialized.
     */
    public final AudioState getAudioState() {
        return mPhone == null ? null : mPhone.getAudioState();
    }

    /**
     * Sets the microphone mute state. When this request is honored, there will be change to
     * the {@link #getAudioState()}.
     *
     * @param state {@code true} if the microphone should be muted; {@code false} otherwise.
     */
    public final void setMuted(boolean state) {
        if (mPhone != null) {
            mPhone.setMuted(state);
        }
    }

    /**
     * Sets the audio route (speaker, bluetooth, etc...).  When this request is honored, there will
     * be change to the {@link #getAudioState()}.
     *
     * @param route The audio route to use.
     */
    public final void setAudioRoute(int route) {
        if (mPhone != null) {
            mPhone.setAudioRoute(route);
        }
    }

    /**
     * Invoked when the {@code Phone} has been created. This is a signal to the in-call experience
     * to start displaying in-call information to the user. Each instance of {@code InCallService}
     * will have only one {@code Phone}, and this method will be called exactly once in the lifetime
     * of the {@code InCallService}.
     *
     * @param phone The {@code Phone} object associated with this {@code InCallService}.
     * @hide
     * @deprecated Use direct methods on InCallService instead of {@link Phone}.
     */
    @SystemApi
    @Deprecated
    public void onPhoneCreated(Phone phone) {
    }

    /**
     * Invoked when a {@code Phone} has been destroyed. This is a signal to the in-call experience
     * to stop displaying in-call information to the user. This method will be called exactly once
     * in the lifetime of the {@code InCallService}, and it will always be called after a previous
     * call to {@link #onPhoneCreated(Phone)}.
     *
     * @param phone The {@code Phone} object associated with this {@code InCallService}.
     * @hide
     * @deprecated Use direct methods on InCallService instead of {@link Phone}.
     */
    @SystemApi
    @Deprecated
    public void onPhoneDestroyed(Phone phone) {
    }

    /**
     * Called when the audio state changes.
     *
     * @param audioState The new {@link AudioState}.
     */
    public void onAudioStateChanged(AudioState audioState) {
    }

    /**
     * Called to bring the in-call screen to the foreground. The in-call experience should
     * respond immediately by coming to the foreground to inform the user of the state of
     * ongoing {@code Call}s.
     *
     * @param showDialpad If true, put up the dialpad when the screen is shown.
     */
    public void onBringToForeground(boolean showDialpad) {
    }

    /**
     * Called when a {@code Call} has been added to this in-call session. The in-call user
     * experience should add necessary state listeners to the specified {@code Call} and
     * immediately start to show the user information about the existence
     * and nature of this {@code Call}. Subsequent invocations of {@link #getCalls()} will
     * include this {@code Call}.
     *
     * @param call A newly added {@code Call}.
     */
    public void onCallAdded(Call call) {
    }

    /**
     * Called when a {@code Call} has been removed from this in-call session. The in-call user
     * experience should remove any state listeners from the specified {@code Call} and
     * immediately stop displaying any information about this {@code Call}.
     * Subsequent invocations of {@link #getCalls()} will no longer include this {@code Call}.
     *
     * @param call A newly removed {@code Call}.
     */
    public void onCallRemoved(Call call) {
    }

    /**
     * Called when the ability to add more calls changes.  If the phone cannot
     * support more calls then {@code canAddCall} is set to {@code false}.  If it can, then it
     * is set to {@code true}. This can be used to control the visibility of UI to add more calls.
     *
     * @param canAddCall Indicates whether an additional call can be added.
     */
    public void onCanAddCallChanged(boolean canAddCall) {
    }

    /**
     * Class to invoke functionality related to video calls.
     */
    public static abstract class VideoCall {

        /** @hide */
        public abstract void destroy();

        /**
         * Registers a callback to receive commands and state changes for video calls.
         *
         * @param callback The video call callback.
         */
        public abstract void registerCallback(VideoCall.Callback callback);

        /**
         * Registers a callback to receive commands and state changes for video calls.
         *
         * @param callback The video call callback.
         * @param handler A handler which commands and status changes will be delivered to.
         */
        public abstract void registerCallback(VideoCall.Callback callback, Handler handler);

        /**
         * Clears the video call listener set via {@link #registerCallback}.
         */
        public abstract void unregisterCallback(VideoCall.Callback callback);

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
         * {@link VideoCall.Callback#onSessionModifyRequestReceived}.
         * Some examples of session modification requests: upgrade call from audio to video,
         * downgrade call from video to audio, pause video.
         *
         * @param requestProfile The requested call video properties.
         */
        public abstract void sendSessionModifyRequest(VideoProfile requestProfile);

        /**
         * Provides a response to a request to change the current call session video
         * properties.
         * This is in response to a request the InCall UI has received via
         * {@link VideoCall.Callback#onSessionModifyRequestReceived}.
         * The response is handled on the remove device by
         * {@link VideoCall.Callback#onSessionModifyResponseReceived}.
         *
         * @param responseProfile The response call video properties.
         */
        public abstract void sendSessionModifyResponse(VideoProfile responseProfile);

        /**
         * Issues a request to the video provider to retrieve the camera capabilities.
         * Camera capabilities are reported back to the caller via
         * {@link VideoCall.Callback#onCameraCapabilitiesChanged(CameraCapabilities)}.
         */
        public abstract void requestCameraCapabilities();

        /**
         * Issues a request to the video telephony framework to retrieve the cumulative data usage for
         * the current call.  Data usage is reported back to the caller via
         * {@link VideoCall.Callback#onCallDataUsageChanged}.
         */
        public abstract void requestCallDataUsage();

        /**
         * Provides the video telephony framework with the URI of an image to be displayed to remote
         * devices when the video signal is paused.
         *
         * @param uri URI of image to display.
         */
        public abstract void setPauseImage(Uri uri);

        /**
         * Callback class which invokes callbacks after video call actions occur.
         */
        public static abstract class Callback {
            /**
             * Called when a session modification request is received from the remote device.
             * The remote request is sent via
             * {@link Connection.VideoProvider#onSendSessionModifyRequest}. The InCall UI
             * is responsible for potentially prompting the user whether they wish to accept the new
             * call profile (e.g. prompt user if they wish to accept an upgrade from an audio to a
             * video call) and should call
             * {@link Connection.VideoProvider#onSendSessionModifyResponse} to indicate
             * the video settings the user has agreed to.
             *
             * @param videoProfile The requested video call profile.
             */
            public abstract void onSessionModifyRequestReceived(VideoProfile videoProfile);

            /**
             * Called when a response to a session modification request is received from the remote
             * device. The remote InCall UI sends the response using
             * {@link Connection.VideoProvider#onSendSessionModifyResponse}.
             *
             * @param status Status of the session modify request.  Valid values are
             *               {@link Connection.VideoProvider#SESSION_MODIFY_REQUEST_SUCCESS},
             *               {@link Connection.VideoProvider#SESSION_MODIFY_REQUEST_FAIL},
             *               {@link Connection.VideoProvider#SESSION_MODIFY_REQUEST_INVALID}
             * @param requestedProfile The original request which was sent to the remote device.
             * @param responseProfile The actual profile changes made by the remote device.
             */
            public abstract void onSessionModifyResponseReceived(int status,
                    VideoProfile requestedProfile, VideoProfile responseProfile);

            /**
             * Handles events related to the current session which the client may wish to handle.
             * These are separate from requested changes to the session due to the underlying
             * protocol or connection.
             *
             * Valid values are:
             * {@link Connection.VideoProvider#SESSION_EVENT_RX_PAUSE},
             * {@link Connection.VideoProvider#SESSION_EVENT_RX_RESUME},
             * {@link Connection.VideoProvider#SESSION_EVENT_TX_START},
             * {@link Connection.VideoProvider#SESSION_EVENT_TX_STOP},
             * {@link Connection.VideoProvider#SESSION_EVENT_CAMERA_FAILURE},
             * {@link Connection.VideoProvider#SESSION_EVENT_CAMERA_READY}
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
             * Handles a change to the video quality.
             *
             * @param videoQuality  The updated peer video quality.
             */
            public abstract void onVideoQualityChanged(int videoQuality);

            /**
             * Handles an update to the total data used for the current session.
             *
             * @param dataUsage The updated data usage.
             */
            public abstract void onCallDataUsageChanged(long dataUsage);

            /**
             * Handles a change in camera capabilities.
             *
             * @param cameraCapabilities The changed camera capabilities.
             */
            public abstract void onCameraCapabilitiesChanged(
                    VideoProfile.CameraCapabilities cameraCapabilities);
        }
    }
}
