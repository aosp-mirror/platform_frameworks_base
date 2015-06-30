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
import android.hardware.camera2.CameraManager;
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
 * and uses it to notify the in-call app of any live and recently disconnected calls. An app must
 * first be set as the default phone app (See {@link TelecomManager#getDefaultDialerPackage()})
 * before the telecom service will bind to its {@code InCallService} implementation.
 * <p>
 * Below is an example manifest registration for an {@code InCallService}. The meta-data
 * ({@link TelecomManager#METADATA_IN_CALL_SERVICE_UI}) indicates that this particular
 * {@code InCallService} implementation intends to replace the built-in in-call UI.
 * <pre>
 * {@code
 * &lt;service android:name="your.package.YourInCallServiceImplementation"
 *          android:permission="android.permission.BIND_IN_CALL_SERVICE"&gt;
 *      &lt;meta-data android:name="android.telecom.IN_CALL_SERVICE_UI" android:value="true" /&gt;
 *      &lt;intent-filter&gt;
 *          &lt;action android:name="android.telecom.InCallService"/&gt;
 *      &lt;/intent-filter&gt;
 * &lt;/service&gt;
 * }
 * </pre>
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
    private static final int MSG_ON_CALL_AUDIO_STATE_CHANGED = 5;
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
                case MSG_ON_CALL_AUDIO_STATE_CHANGED:
                    mPhone.internalCallAudioStateChanged((CallAudioState) msg.obj);
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
        public void onCallAudioStateChanged(CallAudioState callAudioState) {
            mHandler.obtainMessage(MSG_ON_CALL_AUDIO_STATE_CHANGED, callAudioState).sendToTarget();
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

        public void onCallAudioStateChanged(Phone phone, CallAudioState callAudioState) {
            InCallService.this.onCallAudioStateChanged(callAudioState);
        };

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
     * Obtains the current list of {@code Call}s to be displayed by this in-call service.
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
     * @deprecated Use {@link #getCallAudioState()} instead.
     * @hide
     */
    @Deprecated
    public final AudioState getAudioState() {
        return mPhone == null ? null : mPhone.getAudioState();
    }

    /**
     * Obtains the current phone call audio state.
     *
     * @return An object encapsulating the audio state. Returns null if the service is not
     *         fully initialized.
     */
    public final CallAudioState getCallAudioState() {
        return mPhone == null ? null : mPhone.getCallAudioState();
    }

    /**
     * Sets the microphone mute state. When this request is honored, there will be change to
     * the {@link #getCallAudioState()}.
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
     * be change to the {@link #getCallAudioState()}.
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
     * @deprecated Use {@link #onCallAudioStateChanged(CallAudioState) instead}.
     * @hide
     */
    @Deprecated
    public void onAudioStateChanged(AudioState audioState) {
    }

    /**
     * Called when the audio state changes.
     *
     * @param audioState The new {@link CallAudioState}.
     */
    public void onCallAudioStateChanged(CallAudioState audioState) {
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
     * Used to issue commands to the {@link Connection.VideoProvider} associated with a
     * {@link Call}.
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
         * Clears the video call callback set via {@link #registerCallback}.
         *
         * @param callback The video call callback to clear.
         */
        public abstract void unregisterCallback(VideoCall.Callback callback);

        /**
         * Sets the camera to be used for the outgoing video.
         * <p>
         * Handled by {@link Connection.VideoProvider#onSetCamera(String)}.
         *
         * @param cameraId The id of the camera (use ids as reported by
         * {@link CameraManager#getCameraIdList()}).
         */
        public abstract void setCamera(String cameraId);

        /**
         * Sets the surface to be used for displaying a preview of what the user's camera is
         * currently capturing.  When video transmission is enabled, this is the video signal which
         * is sent to the remote device.
         * <p>
         * Handled by {@link Connection.VideoProvider#onSetPreviewSurface(Surface)}.
         *
         * @param surface The {@link Surface}.
         */
        public abstract void setPreviewSurface(Surface surface);

        /**
         * Sets the surface to be used for displaying the video received from the remote device.
         * <p>
         * Handled by {@link Connection.VideoProvider#onSetDisplaySurface(Surface)}.
         *
         * @param surface The {@link Surface}.
         */
        public abstract void setDisplaySurface(Surface surface);

        /**
         * Sets the device orientation, in degrees.  Assumes that a standard portrait orientation of
         * the device is 0 degrees.
         * <p>
         * Handled by {@link Connection.VideoProvider#onSetDeviceOrientation(int)}.
         *
         * @param rotation The device orientation, in degrees.
         */
        public abstract void setDeviceOrientation(int rotation);

        /**
         * Sets camera zoom ratio.
         * <p>
         * Handled by {@link Connection.VideoProvider#onSetZoom(float)}.
         *
         * @param value The camera zoom ratio.
         */
        public abstract void setZoom(float value);

        /**
         * Issues a request to modify the properties of the current video session.
         * <p>
         * Example scenarios include: requesting an audio-only call to be upgraded to a
         * bi-directional video call, turning on or off the user's camera, sending a pause signal
         * when the {@link InCallService} is no longer the foreground application.
         * <p>
         * Handled by
         * {@link Connection.VideoProvider#onSendSessionModifyRequest(VideoProfile, VideoProfile)}.
         *
         * @param requestProfile The requested call video properties.
         */
        public abstract void sendSessionModifyRequest(VideoProfile requestProfile);

        /**
         * Provides a response to a request to change the current call video session
         * properties.  This should be called in response to a request the {@link InCallService} has
         * received via {@link VideoCall.Callback#onSessionModifyRequestReceived}.
         * <p>
         * Handled by
         * {@link Connection.VideoProvider#onSendSessionModifyResponse(VideoProfile)}.
         *
         * @param responseProfile The response call video properties.
         */
        public abstract void sendSessionModifyResponse(VideoProfile responseProfile);

        /**
         * Issues a request to the {@link Connection.VideoProvider} to retrieve the capabilities
         * of the current camera.  The current camera is selected using
         * {@link VideoCall#setCamera(String)}.
         * <p>
         * Camera capabilities are reported to the caller via
         * {@link VideoCall.Callback#onCameraCapabilitiesChanged(VideoProfile.CameraCapabilities)}.
         * <p>
         * Handled by {@link Connection.VideoProvider#onRequestCameraCapabilities()}.
         */
        public abstract void requestCameraCapabilities();

        /**
         * Issues a request to the {@link Connection.VideoProvider} to retrieve the cumulative data
         * usage for the video component of the current call (in bytes).  Data usage is reported
         * to the caller via {@link VideoCall.Callback#onCallDataUsageChanged}.
         * <p>
         * Handled by {@link Connection.VideoProvider#onRequestConnectionDataUsage()}.
         */
        public abstract void requestCallDataUsage();

        /**
         * Provides the {@link Connection.VideoProvider} with the {@link Uri} of an image to be
         * displayed to the peer device when the video signal is paused.
         * <p>
         * Handled by {@link Connection.VideoProvider#onSetPauseImage(Uri)}.
         *
         * @param uri URI of image to display.
         */
        public abstract void setPauseImage(Uri uri);

        /**
         * The {@link InCallService} extends this class to provide a means of receiving callbacks
         * from the {@link Connection.VideoProvider}.
         * <p>
         * When the {@link InCallService} receives the
         * {@link Call.Callback#onVideoCallChanged(Call, VideoCall)} callback, it should create an
         * instance its {@link VideoCall.Callback} implementation and set it on the
         * {@link VideoCall} using {@link VideoCall#registerCallback(Callback)}.
         */
        public static abstract class Callback {
            /**
             * Called when the {@link Connection.VideoProvider} receives a session modification
             * request from the peer device.
             * <p>
             * The {@link InCallService} may potentially prompt the user to confirm whether they
             * wish to accept the request, or decide to automatically accept the request.  In either
             * case the {@link InCallService} should call
             * {@link VideoCall#sendSessionModifyResponse(VideoProfile)} to indicate the video
             * profile agreed upon.
             * <p>
             * Callback originates from
             * {@link Connection.VideoProvider#receiveSessionModifyRequest(VideoProfile)}.
             *
             * @param videoProfile The requested video profile.
             */
            public abstract void onSessionModifyRequestReceived(VideoProfile videoProfile);

            /**
             * Called when the {@link Connection.VideoProvider} receives a response to a session
             * modification request previously sent to the peer device.
             * <p>
             * The new video state should not be considered active by the {@link InCallService}
             * until the {@link Call} video state changes (the
             * {@link Call.Callback#onDetailsChanged(Call, Call.Details)} callback is triggered
             * when the video state changes).
             * <p>
             * Callback originates from
             * {@link Connection.VideoProvider#receiveSessionModifyResponse(int, VideoProfile,
             *      VideoProfile)}.
             *
             * @param status Status of the session modify request.  Valid values are
             *      {@link Connection.VideoProvider#SESSION_MODIFY_REQUEST_SUCCESS},
             *      {@link Connection.VideoProvider#SESSION_MODIFY_REQUEST_FAIL},
             *      {@link Connection.VideoProvider#SESSION_MODIFY_REQUEST_INVALID},
             *      {@link Connection.VideoProvider#SESSION_MODIFY_REQUEST_TIMED_OUT},
             *      {@link Connection.VideoProvider#SESSION_MODIFY_REQUEST_REJECTED_BY_REMOTE}.
             * @param requestedProfile The original request which was sent to the peer device.
             * @param responseProfile The actual profile changes made by the peer device.
             */
            public abstract void onSessionModifyResponseReceived(int status,
                    VideoProfile requestedProfile, VideoProfile responseProfile);

            /**
             * Handles events related to the current video session which the {@link InCallService}
             * may wish to handle. These are separate from requested changes to the session due to
             * the underlying protocol or connection.
             * <p>
             * Callback originates from
             * {@link Connection.VideoProvider#handleCallSessionEvent(int)}.
             *
             * @param event The event.  Valid values are:
             *      {@link Connection.VideoProvider#SESSION_EVENT_RX_PAUSE},
             *      {@link Connection.VideoProvider#SESSION_EVENT_RX_RESUME},
             *      {@link Connection.VideoProvider#SESSION_EVENT_TX_START},
             *      {@link Connection.VideoProvider#SESSION_EVENT_TX_STOP},
             *      {@link Connection.VideoProvider#SESSION_EVENT_CAMERA_FAILURE},
             *      {@link Connection.VideoProvider#SESSION_EVENT_CAMERA_READY}.
             */
            public abstract void onCallSessionEvent(int event);

            /**
             * Handles a change to the video dimensions from the peer device. This could happen if,
             * for example, the peer changes orientation of their device, or switches cameras.
             * <p>
             * Callback originates from
             * {@link Connection.VideoProvider#changePeerDimensions(int, int)}.
             *
             * @param width  The updated peer video width.
             * @param height The updated peer video height.
             */
            public abstract void onPeerDimensionsChanged(int width, int height);

            /**
             * Handles a change to the video quality.
             * <p>
             * Callback originates from {@link Connection.VideoProvider#changeVideoQuality(int)}.
             *
             * @param videoQuality  The updated peer video quality.  Valid values:
             *      {@link VideoProfile#QUALITY_HIGH},
             *      {@link VideoProfile#QUALITY_MEDIUM},
             *      {@link VideoProfile#QUALITY_LOW},
             *      {@link VideoProfile#QUALITY_DEFAULT}.
             */
            public abstract void onVideoQualityChanged(int videoQuality);

            /**
             * Handles an update to the total data used for the current video session.
             * <p>
             * Used by the {@link Connection.VideoProvider} in response to
             * {@link VideoCall#requestCallDataUsage()}.  May also be called periodically by the
             * {@link Connection.VideoProvider}.
             * <p>
             * Callback originates from {@link Connection.VideoProvider#setCallDataUsage(long)}.
             *
             * @param dataUsage The updated data usage (in bytes).
             */
            public abstract void onCallDataUsageChanged(long dataUsage);

            /**
             * Handles a change in the capabilities of the currently selected camera.
             * <p>
             * Used by the {@link Connection.VideoProvider} in response to
             * {@link VideoCall#requestCameraCapabilities()}.  The {@link Connection.VideoProvider}
             * may also report the camera capabilities after a call to
             * {@link VideoCall#setCamera(String)}.
             * <p>
             * Callback originates from
             * {@link Connection.VideoProvider#changeCameraCapabilities(
             *      VideoProfile.CameraCapabilities)}.
             *
             * @param cameraCapabilities The changed camera capabilities.
             */
            public abstract void onCameraCapabilitiesChanged(
                    VideoProfile.CameraCapabilities cameraCapabilities);
        }
    }
}
