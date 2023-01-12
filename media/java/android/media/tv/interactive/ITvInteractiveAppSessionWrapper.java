/*
 * Copyright 2021 The Android Open Source Project
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

package android.media.tv.interactive;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.media.PlaybackParams;
import android.media.tv.AdBuffer;
import android.media.tv.AdResponse;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.TvContentRating;
import android.media.tv.TvRecordingInfo;
import android.media.tv.TvTrackInfo;
import android.media.tv.interactive.TvInteractiveAppService.Session;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.Surface;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

import java.util.List;

/**
 * Implements the internal ITvInteractiveAppSession interface.
 * @hide
 */
public class ITvInteractiveAppSessionWrapper
        extends ITvInteractiveAppSession.Stub implements HandlerCaller.Callback {
    private static final String TAG = "ITvInteractiveAppSessionWrapper";

    private static final int EXECUTE_MESSAGE_TIMEOUT_SHORT_MILLIS = 1000;
    private static final int EXECUTE_MESSAGE_TIMEOUT_LONG_MILLIS = 5 * 1000;

    private static final int DO_RELEASE = 1;
    private static final int DO_START_INTERACTIVE_APP = 2;
    private static final int DO_STOP_INTERACTIVE_APP = 3;
    private static final int DO_RESET_INTERACTIVE_APP = 4;
    private static final int DO_CREATE_BI_INTERACTIVE_APP = 5;
    private static final int DO_DESTROY_BI_INTERACTIVE_APP = 6;
    private static final int DO_SET_TELETEXT_APP_ENABLED = 7;
    private static final int DO_SEND_CURRENT_CHANNEL_URI = 8;
    private static final int DO_SEND_CURRENT_CHANNEL_LCN = 9;
    private static final int DO_SEND_STREAM_VOLUME = 10;
    private static final int DO_SEND_TRACK_INFO_LIST = 11;
    private static final int DO_SEND_CURRENT_TV_INPUT_ID = 12;
    private static final int DO_SEND_SIGNING_RESULT = 13;
    private static final int DO_NOTIFY_ERROR = 14;
    private static final int DO_NOTIFY_TUNED = 15;
    private static final int DO_NOTIFY_TRACK_SELECTED = 16;
    private static final int DO_NOTIFY_TRACKS_CHANGED = 17;
    private static final int DO_NOTIFY_VIDEO_AVAILABLE = 18;
    private static final int DO_NOTIFY_VIDEO_UNAVAILABLE = 19;
    private static final int DO_NOTIFY_CONTENT_ALLOWED = 20;
    private static final int DO_NOTIFY_CONTENT_BLOCKED = 21;
    private static final int DO_NOTIFY_SIGNAL_STRENGTH = 22;
    private static final int DO_SET_SURFACE = 23;
    private static final int DO_DISPATCH_SURFACE_CHANGED = 24;
    private static final int DO_NOTIFY_BROADCAST_INFO_RESPONSE = 25;
    private static final int DO_NOTIFY_AD_RESPONSE = 26;
    private static final int DO_CREATE_MEDIA_VIEW = 27;
    private static final int DO_RELAYOUT_MEDIA_VIEW = 28;
    private static final int DO_REMOVE_MEDIA_VIEW = 29;
    private static final int DO_NOTIFY_RECORDING_STARTED = 30;
    private static final int DO_NOTIFY_RECORDING_STOPPED = 31;
    private static final int DO_NOTIFY_AD_BUFFER_CONSUMED = 32;
    private static final int DO_NOTIFY_TV_MESSAGE = 33;
    private static final int DO_SEND_RECORDING_INFO = 34;
    private static final int DO_SEND_RECORDING_INFO_LIST = 35;
    private static final int DO_NOTIFY_TIME_SHIFT_PLAYBACK_PARAMS = 36;
    private static final int DO_NOTIFY_TIME_SHIFT_STATUS_CHANGED = 37;
    private static final int DO_NOTIFY_TIME_SHIFT_START_POSITION_CHANGED = 38;
    private static final int DO_NOTIFY_TIME_SHIFT_CURRENT_POSITION_CHANGED = 39;

    private final HandlerCaller mCaller;
    private Session mSessionImpl;
    private InputChannel mChannel;
    private TvInteractiveAppEventReceiver mReceiver;

    public ITvInteractiveAppSessionWrapper(
            Context context, Session mSessionImpl, InputChannel channel) {
        this.mSessionImpl = mSessionImpl;
        mCaller = new HandlerCaller(context, null, this, true /* asyncHandler */);
        mChannel = channel;
        if (channel != null) {
            mReceiver = new TvInteractiveAppEventReceiver(channel, context.getMainLooper());
        }
    }

    @Override
    public void executeMessage(Message msg) {
        if (mSessionImpl == null) {
            return;
        }

        long startTime = System.nanoTime();
        switch (msg.what) {
            case DO_RELEASE: {
                mSessionImpl.release();
                mSessionImpl = null;
                if (mReceiver != null) {
                    mReceiver.dispose();
                    mReceiver = null;
                }
                if (mChannel != null) {
                    mChannel.dispose();
                    mChannel = null;
                }
                break;
            }
            case DO_START_INTERACTIVE_APP: {
                mSessionImpl.startInteractiveApp();
                break;
            }
            case DO_STOP_INTERACTIVE_APP: {
                mSessionImpl.stopInteractiveApp();
                break;
            }
            case DO_RESET_INTERACTIVE_APP: {
                mSessionImpl.resetInteractiveApp();
                break;
            }
            case DO_CREATE_BI_INTERACTIVE_APP: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.createBiInteractiveApp((Uri) args.arg1, (Bundle) args.arg2);
                args.recycle();
                break;
            }
            case DO_DESTROY_BI_INTERACTIVE_APP: {
                mSessionImpl.destroyBiInteractiveApp((String) msg.obj);
                break;
            }
            case DO_SET_TELETEXT_APP_ENABLED: {
                mSessionImpl.setTeletextAppEnabled((Boolean) msg.obj);
                break;
            }
            case DO_SEND_CURRENT_CHANNEL_URI: {
                mSessionImpl.sendCurrentChannelUri((Uri) msg.obj);
                break;
            }
            case DO_SEND_CURRENT_CHANNEL_LCN: {
                mSessionImpl.sendCurrentChannelLcn((Integer) msg.obj);
                break;
            }
            case DO_SEND_STREAM_VOLUME: {
                mSessionImpl.sendStreamVolume((Float) msg.obj);
                break;
            }
            case DO_SEND_TRACK_INFO_LIST: {
                mSessionImpl.sendTrackInfoList((List<TvTrackInfo>) msg.obj);
                break;
            }
            case DO_SEND_CURRENT_TV_INPUT_ID: {
                mSessionImpl.sendCurrentTvInputId((String) msg.obj);
                break;
            }
            case DO_SEND_RECORDING_INFO: {
                mSessionImpl.sendTvRecordingInfo((TvRecordingInfo) msg.obj);
                break;
            }
            case DO_SEND_RECORDING_INFO_LIST: {
                mSessionImpl.sendTvRecordingInfoList((List<TvRecordingInfo>) msg.obj);
                break;
            }
            case DO_NOTIFY_RECORDING_STARTED: {
                mSessionImpl.notifyRecordingStarted((String) msg.obj);
                break;
            }
            case DO_NOTIFY_RECORDING_STOPPED: {
                mSessionImpl.notifyRecordingStopped((String) msg.obj);
                break;
            }
            case DO_SEND_SIGNING_RESULT: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.sendSigningResult((String) args.arg1, (byte[]) args.arg2);
                args.recycle();
                break;
            }
            case DO_NOTIFY_ERROR: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.notifyError((String) args.arg1, (Bundle) args.arg2);
                args.recycle();
                break;
            }
            case DO_NOTIFY_TUNED: {
                mSessionImpl.notifyTuned((Uri) msg.obj);
                break;
            }
            case DO_NOTIFY_TRACK_SELECTED: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.notifyTrackSelected((Integer) args.arg1, (String) args.arg2);
                args.recycle();
                break;
            }
            case DO_NOTIFY_TRACKS_CHANGED: {
                mSessionImpl.notifyTracksChanged((List<TvTrackInfo>) msg.obj);
                break;
            }
            case DO_NOTIFY_TV_MESSAGE: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.notifyTvMessage((String) args.arg1, (Bundle) args.arg2);
                args.recycle();
                break;
            }
            case DO_NOTIFY_VIDEO_AVAILABLE: {
                mSessionImpl.notifyVideoAvailable();
                break;
            }
            case DO_NOTIFY_VIDEO_UNAVAILABLE: {
                mSessionImpl.notifyVideoUnavailable((Integer) msg.obj);
                break;
            }
            case DO_NOTIFY_CONTENT_ALLOWED: {
                mSessionImpl.notifyContentAllowed();
                break;
            }
            case DO_NOTIFY_CONTENT_BLOCKED: {
                mSessionImpl.notifyContentBlocked((TvContentRating) msg.obj);
                break;
            }
            case DO_NOTIFY_SIGNAL_STRENGTH: {
                mSessionImpl.notifySignalStrength((Integer) msg.obj);
                break;
            }
            case DO_SET_SURFACE: {
                mSessionImpl.setSurface((Surface) msg.obj);
                break;
            }
            case DO_DISPATCH_SURFACE_CHANGED: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.dispatchSurfaceChanged(
                        (Integer) args.argi1, (Integer) args.argi2, (Integer) args.argi3);
                args.recycle();
                break;
            }
            case DO_NOTIFY_BROADCAST_INFO_RESPONSE: {
                mSessionImpl.notifyBroadcastInfoResponse((BroadcastInfoResponse) msg.obj);
                break;
            }
            case DO_NOTIFY_AD_RESPONSE: {
                mSessionImpl.notifyAdResponse((AdResponse) msg.obj);
                break;
            }
            case DO_CREATE_MEDIA_VIEW: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.createMediaView((IBinder) args.arg1, (Rect) args.arg2);
                args.recycle();
                break;
            }
            case DO_RELAYOUT_MEDIA_VIEW: {
                mSessionImpl.relayoutMediaView((Rect) msg.obj);
                break;
            }
            case DO_REMOVE_MEDIA_VIEW: {
                mSessionImpl.removeMediaView(true);
                break;
            }
            case DO_NOTIFY_AD_BUFFER_CONSUMED: {
                mSessionImpl.notifyAdBufferConsumed((AdBuffer) msg.obj);
                break;
            }
            case DO_NOTIFY_TIME_SHIFT_PLAYBACK_PARAMS: {
                mSessionImpl.notifyTimeShiftPlaybackParams((PlaybackParams) msg.obj);
                break;
            }
            case DO_NOTIFY_TIME_SHIFT_STATUS_CHANGED: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.notifyTimeShiftStatusChanged((String) args.arg1, (Integer) args.arg2);
                args.recycle();
                break;
            }
            case DO_NOTIFY_TIME_SHIFT_START_POSITION_CHANGED: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.notifyTimeShiftStartPositionChanged(
                        (String) args.arg1, (Long) args.arg2);
                args.recycle();
                break;
            }
            case DO_NOTIFY_TIME_SHIFT_CURRENT_POSITION_CHANGED: {
                SomeArgs args = (SomeArgs) msg.obj;
                mSessionImpl.notifyTimeShiftCurrentPositionChanged(
                        (String) args.arg1, (Long) args.arg2);
                args.recycle();
                break;
            }
            default: {
                Log.w(TAG, "Unhandled message code: " + msg.what);
                break;
            }
        }
        long durationMs = (System.nanoTime() - startTime) / (1000 * 1000);
        if (durationMs > EXECUTE_MESSAGE_TIMEOUT_SHORT_MILLIS) {
            Log.w(TAG, "Handling message (" + msg.what + ") took too long time (duration="
                    + durationMs + "ms)");
            if (durationMs > EXECUTE_MESSAGE_TIMEOUT_LONG_MILLIS) {
                // TODO: handle timeout
            }
        }
    }

    @Override
    public void startInteractiveApp() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_START_INTERACTIVE_APP));
    }

    @Override
    public void stopInteractiveApp() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_STOP_INTERACTIVE_APP));
    }

    @Override
    public void resetInteractiveApp() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_RESET_INTERACTIVE_APP));
    }

    @Override
    public void createBiInteractiveApp(@NonNull Uri biIAppUri, @Nullable Bundle params) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_CREATE_BI_INTERACTIVE_APP, biIAppUri, params));
    }

    @Override
    public void destroyBiInteractiveApp(@NonNull String biIAppId) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_DESTROY_BI_INTERACTIVE_APP, biIAppId));
    }

    @Override
    public void setTeletextAppEnabled(boolean enable) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SET_TELETEXT_APP_ENABLED, enable));
    }

    @Override
    public void sendCurrentChannelUri(@Nullable Uri channelUri) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SEND_CURRENT_CHANNEL_URI, channelUri));
    }

    @Override
    public void sendCurrentChannelLcn(int lcn) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SEND_CURRENT_CHANNEL_LCN, lcn));
    }

    @Override
    public void sendStreamVolume(float volume) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SEND_STREAM_VOLUME, volume));
    }

    @Override
    public void sendTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SEND_TRACK_INFO_LIST, tracks));
    }

    @Override
    public void sendCurrentTvInputId(@Nullable String inputId) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SEND_CURRENT_TV_INPUT_ID, inputId));
    }

    @Override
    public void sendTvRecordingInfo(@Nullable TvRecordingInfo recordingInfo) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SEND_RECORDING_INFO, recordingInfo));
    }

    @Override
    public void sendTvRecordingInfoList(@Nullable List<TvRecordingInfo> recordingInfoList) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SEND_RECORDING_INFO_LIST, recordingInfoList));
    }

    @Override
    public void sendSigningResult(@NonNull String signingId, @NonNull byte[] result) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_SEND_SIGNING_RESULT, signingId, result));
    }

    @Override
    public void notifyError(@NonNull String errMsg, @NonNull Bundle params) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_NOTIFY_ERROR, errMsg, params));
    }

    @Override
    public void notifyTimeShiftPlaybackParams(@NonNull PlaybackParams params) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_NOTIFY_TIME_SHIFT_PLAYBACK_PARAMS, params));
    }

    @Override
    public void notifyTimeShiftStatusChanged(@NonNull String inputId, int status) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_NOTIFY_TIME_SHIFT_STATUS_CHANGED, inputId, status));
    }

    @Override
    public void notifyTimeShiftStartPositionChanged(@NonNull String inputId, long timeMs) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(
                DO_NOTIFY_TIME_SHIFT_START_POSITION_CHANGED, inputId, timeMs));
    }

    @Override
    public void notifyTimeShiftCurrentPositionChanged(@NonNull String inputId, long timeMs) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(
                DO_NOTIFY_TIME_SHIFT_CURRENT_POSITION_CHANGED, inputId, timeMs));
    }

    @Override
    public void release() {
        mSessionImpl.scheduleMediaViewCleanup();
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_RELEASE));
    }

    @Override
    public void notifyTuned(Uri channelUri) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_NOTIFY_TUNED, channelUri));
    }

    @Override
    public void notifyTrackSelected(int type, final String trackId) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_NOTIFY_TRACK_SELECTED, type, trackId));
    }

    @Override
    public void notifyTvMessage(String type, Bundle data) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_NOTIFY_TRACK_SELECTED, type, data));
    }

    @Override
    public void notifyTracksChanged(List<TvTrackInfo> tracks) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_NOTIFY_TRACKS_CHANGED, tracks));
    }

    @Override
    public void notifyVideoAvailable() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_NOTIFY_VIDEO_AVAILABLE));
    }

    @Override
    public void notifyVideoUnavailable(int reason) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_NOTIFY_VIDEO_UNAVAILABLE, reason));
    }

    @Override
    public void notifyContentAllowed() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_NOTIFY_CONTENT_ALLOWED));
    }

    @Override
    public void notifyContentBlocked(String rating) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_NOTIFY_CONTENT_BLOCKED, rating));
    }

    @Override
    public void notifySignalStrength(int strength) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_NOTIFY_SIGNAL_STRENGTH, strength));
    }

    @Override
    public void notifyRecordingStarted(String recordingId) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(
                DO_NOTIFY_RECORDING_STARTED, recordingId));
    }

    @Override
    public void notifyRecordingStopped(String recordingId) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(
                DO_NOTIFY_RECORDING_STOPPED, recordingId));
    }

    @Override
    public void setSurface(Surface surface) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_SURFACE, surface));
    }

    @Override
    public void dispatchSurfaceChanged(int format, int width, int height) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageIIII(DO_DISPATCH_SURFACE_CHANGED, format, width, height, 0));
    }

    @Override
    public void notifyBroadcastInfoResponse(BroadcastInfoResponse response) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_NOTIFY_BROADCAST_INFO_RESPONSE, response));
    }

    @Override
    public void notifyAdResponse(AdResponse response) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_NOTIFY_AD_RESPONSE, response));
    }

    @Override
    public void notifyAdBufferConsumed(AdBuffer buffer) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_NOTIFY_AD_BUFFER_CONSUMED, buffer));
    }

    @Override
    public void createMediaView(IBinder windowToken, Rect frame) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_CREATE_MEDIA_VIEW, windowToken, frame));
    }

    @Override
    public void relayoutMediaView(Rect frame) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_RELAYOUT_MEDIA_VIEW, frame));
    }

    @Override
    public void removeMediaView() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_REMOVE_MEDIA_VIEW));
    }

    private final class TvInteractiveAppEventReceiver extends InputEventReceiver {
        TvInteractiveAppEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            if (mSessionImpl == null) {
                // The session has been finished.
                finishInputEvent(event, false);
                return;
            }

            int handled = mSessionImpl.dispatchInputEvent(event, this);
            if (handled != TvInteractiveAppManager.Session.DISPATCH_IN_PROGRESS) {
                finishInputEvent(
                        event, handled == TvInteractiveAppManager.Session.DISPATCH_HANDLED);
            }
        }
    }
}
