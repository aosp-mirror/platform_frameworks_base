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

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.ICallVideoClient;

/**
 * Base implementation of a CallVideoClient which communicates changes to video properties of a call
 * from the framework to the current InCall-UI.
 */
public abstract class CallVideoClient {

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
     * Video transmission has stopped. This occur after a negotiated stop of video transmission when
     * the underlying protocol has actually stopped transmitting video to the remote party.
     */
    public static final int SESSION_EVENT_TX_STOP = 4;

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

    private static final int MSG_RECEIVE_SESSION_MODIFY_REQUEST = 1;
    private static final int MSG_RECEIVE_SESSION_MODIFY_RESPONSE = 2;
    private static final int MSG_HANDLE_CALL_SESSION_EVENT = 3;
    private static final int MSG_UPDATE_PEER_DIMENSIONS = 4;
    private static final int MSG_UPDATE_CALL_DATA_USAGE = 5;
    private static final int MSG_HANDLE_CAMERA_CAPABILITIES_CHANGE = 6;

    /** Default Handler used to consolidate binder method calls onto a single thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RECEIVE_SESSION_MODIFY_REQUEST:
                    onReceiveSessionModifyRequest((VideoCallProfile) msg.obj);
                    break;
                case MSG_RECEIVE_SESSION_MODIFY_RESPONSE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        int status = (int) args.arg1;
                        VideoCallProfile requestProfile = (VideoCallProfile) args.arg2;
                        VideoCallProfile responseProfile = (VideoCallProfile) args.arg3;

                        onReceiveSessionModifyResponse(status, requestProfile,
                                responseProfile);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_HANDLE_CALL_SESSION_EVENT:
                    onHandleCallSessionEvent((int) msg.obj);
                    break;
                case MSG_UPDATE_PEER_DIMENSIONS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        int width = (int) args.arg1;
                        int height = (int) args.arg2;
                        onUpdatePeerDimensions(width, height);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_UPDATE_CALL_DATA_USAGE:
                    onUpdateCallDataUsage(msg.arg1);
                    break;
                case MSG_HANDLE_CAMERA_CAPABILITIES_CHANGE:
                    onHandleCameraCapabilitiesChange((CallCameraCapabilities) msg.obj);
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * Default ICallVideoClient implementation.
     */
    private final class CallVideoClientBinder extends ICallVideoClient.Stub {
        @Override
        public void receiveSessionModifyRequest(VideoCallProfile videoCallProfile) {
            mHandler.obtainMessage(MSG_RECEIVE_SESSION_MODIFY_REQUEST,
                    videoCallProfile).sendToTarget();
        }

        @Override
        public void receiveSessionModifyResponse(int status,
                VideoCallProfile requestProfile, VideoCallProfile responseProfile) {
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
        public void updatePeerDimensions(int width, int height) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = width;
            args.arg2 = height;
            mHandler.obtainMessage(MSG_UPDATE_PEER_DIMENSIONS, args).sendToTarget();
        }

        @Override
        public void updateCallDataUsage(int dataUsage) {
            mHandler.obtainMessage(MSG_UPDATE_CALL_DATA_USAGE, dataUsage).sendToTarget();
        }

        @Override
        public void handleCameraCapabilitiesChange(CallCameraCapabilities cameraCapabilities) {
            mHandler.obtainMessage(MSG_HANDLE_CAMERA_CAPABILITIES_CHANGE,
                    cameraCapabilities).sendToTarget();
        }
    }

    private final CallVideoClientBinder mBinder;

    protected CallVideoClient() {
        mBinder = new CallVideoClientBinder();
    }

    /**
     * Returns binder object which can be used across IPC methods.
     * @hide
     */
    public final IBinder getBinder() {
        return mBinder;
    }

    /**
     * Called when a session modification request is received from the remote device.
     * The remote request is sent via {@link CallVideoProvider#onSendSessionModifyRequest}.
     * The InCall UI is responsible for potentially prompting the user whether they wish to accept
     * the new call profile (e.g. prompt user if they wish to accept an upgrade from an audio to a
     * video call) and should call {@link CallVideoProvider#onSendSessionModifyResponse} to indicate
     * the video settings the user has agreed to.
     *
     * @param videoCallProfile The requested video call profile.
     */
    public abstract void onReceiveSessionModifyRequest(VideoCallProfile videoCallProfile);

    /**
     * Called when a response to a session modification request is received from the remote device.
     * The remote InCall UI sends the response using
     * {@link CallVideoProvider#onSendSessionModifyResponse}.
     *
     * @param status Status of the session modify request.  Valid values are
     *               {@link CallVideoClient#SESSION_MODIFY_REQUEST_SUCCESS},
     *               {@link CallVideoClient#SESSION_MODIFY_REQUEST_FAIL},
     *               {@link CallVideoClient#SESSION_MODIFY_REQUEST_INVALID}
     * @param requestProfile The original request which was sent to the remote device.
     * @param responseProfile The actual profile changes made by the remote device.
     */
    public abstract void onReceiveSessionModifyResponse(int status,
            VideoCallProfile requestProfile, VideoCallProfile responseProfile);

    /**
     * Handles events related to the current session which the client may wish to handle.  These
     * are separate from requested changes to the session due to the underlying protocol or
     * connection.
     * Valid values are: {@link CallVideoClient#SESSION_EVENT_RX_PAUSE},
     * {@link CallVideoClient#SESSION_EVENT_RX_RESUME},
     * {@link CallVideoClient#SESSION_EVENT_TX_START}, {@link CallVideoClient#SESSION_EVENT_TX_STOP}
     *
     * @param event The event.
     */
    public abstract void onHandleCallSessionEvent(int event);

    /**
     * Handles a change to the video dimensions from the remote caller (peer).  This could happen
     * if, for example, the peer changes orientation of their device.
     *
     * @param width  The updated peer video width.
     * @param height The updated peer video height.
     */
    public abstract void onUpdatePeerDimensions(int width, int height);

    /**
     * Handles an update to the total data used for the current session.
     *
     * @param dataUsage The updated data usage.
     */
    public abstract void onUpdateCallDataUsage(int dataUsage);

    /**
     * Handles a change in camera capabilities.
     *
     * @param callCameraCapabilities The changed camera capabilities.
     */
    public abstract void onHandleCameraCapabilitiesChange(
            CallCameraCapabilities callCameraCapabilities);
}

