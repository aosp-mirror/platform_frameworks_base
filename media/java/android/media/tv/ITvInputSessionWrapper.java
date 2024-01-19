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

package android.media.tv;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.media.PlaybackParams;
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

/**
 * Implements the internal ITvInputSession interface to convert incoming calls on to it back to
 * calls on the public TvInputSession interface, scheduling them on the main thread of the process.
 *
 * @hide
 */
public class ITvInputSessionWrapper extends ITvInputSession.Stub implements HandlerCaller.Callback {
    private static final String TAG = "TvInputSessionWrapper";

    private static final int EXECUTE_MESSAGE_TIMEOUT_SHORT_MILLIS = 50;
    private static final int EXECUTE_MESSAGE_TUNE_TIMEOUT_MILLIS = 2000;
    private static final int EXECUTE_MESSAGE_TIMEOUT_LONG_MILLIS = 5 * 1000;

    private static final int DO_RELEASE = 1;
    private static final int DO_SET_MAIN = 2;
    private static final int DO_SET_SURFACE = 3;
    private static final int DO_DISPATCH_SURFACE_CHANGED = 4;
    private static final int DO_SET_STREAM_VOLUME = 5;
    private static final int DO_TUNE = 6;
    private static final int DO_SET_CAPTION_ENABLED = 7;
    private static final int DO_SELECT_TRACK = 8;
    private static final int DO_APP_PRIVATE_COMMAND = 9;
    private static final int DO_CREATE_OVERLAY_VIEW = 10;
    private static final int DO_RELAYOUT_OVERLAY_VIEW = 11;
    private static final int DO_REMOVE_OVERLAY_VIEW = 12;
    private static final int DO_UNBLOCK_CONTENT = 13;
    private static final int DO_TIME_SHIFT_PLAY = 14;
    private static final int DO_TIME_SHIFT_PAUSE = 15;
    private static final int DO_TIME_SHIFT_RESUME = 16;
    private static final int DO_TIME_SHIFT_SEEK_TO = 17;
    private static final int DO_TIME_SHIFT_SET_PLAYBACK_PARAMS = 18;
    private static final int DO_TIME_SHIFT_ENABLE_POSITION_TRACKING = 19;
    private static final int DO_START_RECORDING = 20;
    private static final int DO_STOP_RECORDING = 21;
    private static final int DO_PAUSE_RECORDING = 22;
    private static final int DO_RESUME_RECORDING = 23;
    private static final int DO_REQUEST_BROADCAST_INFO = 24;
    private static final int DO_REMOVE_BROADCAST_INFO = 25;
    private static final int DO_SET_IAPP_NOTIFICATION_ENABLED = 26;
    private static final int DO_REQUEST_AD = 27;
    private static final int DO_NOTIFY_AD_BUFFER = 28;
    private static final int DO_SELECT_AUDIO_PRESENTATION = 29;
    private static final int DO_TIME_SHIFT_SET_MODE = 30;
    private static final int DO_SET_TV_MESSAGE_ENABLED = 31;
    private static final int DO_NOTIFY_TV_MESSAGE = 32;
    private static final int DO_STOP_PLAYBACK = 33;
    private static final int DO_START_PLAYBACK = 34;
    private static final int DO_SET_VIDEO_FROZEN = 35;

    private final boolean mIsRecordingSession;
    private final HandlerCaller mCaller;

    private TvInputService.Session mTvInputSessionImpl;
    private TvInputService.RecordingSession mTvInputRecordingSessionImpl;

    private InputChannel mChannel;
    private TvInputEventReceiver mReceiver;

    public ITvInputSessionWrapper(Context context, TvInputService.Session sessionImpl,
            InputChannel channel) {
        mIsRecordingSession = false;
        mCaller = new HandlerCaller(context, null, this, true /* asyncHandler */);
        mTvInputSessionImpl = sessionImpl;
        mChannel = channel;
        if (channel != null) {
            mReceiver = new TvInputEventReceiver(channel, context.getMainLooper());
        }
    }

    // For the recording session
    public ITvInputSessionWrapper(Context context,
            TvInputService.RecordingSession recordingSessionImpl) {
        mIsRecordingSession = true;
        mCaller = new HandlerCaller(context, null, this, true /* asyncHandler */);
        mTvInputRecordingSessionImpl = recordingSessionImpl;
    }

    @Override
    public void executeMessage(Message msg) {
        if ((mIsRecordingSession && mTvInputRecordingSessionImpl == null)
                || (!mIsRecordingSession && mTvInputSessionImpl == null)) {
            return;
        }

        long startTime = System.nanoTime();
        switch (msg.what) {
            case DO_RELEASE: {
                if (mIsRecordingSession) {
                    mTvInputRecordingSessionImpl.release();
                    mTvInputRecordingSessionImpl = null;
                } else {
                    mTvInputSessionImpl.release();
                    mTvInputSessionImpl = null;
                    if (mReceiver != null) {
                        mReceiver.dispose();
                        mReceiver = null;
                    }
                    if (mChannel != null) {
                        mChannel.dispose();
                        mChannel = null;
                    }
                }
                break;
            }
            case DO_SET_MAIN: {
                mTvInputSessionImpl.setMain((Boolean) msg.obj);
                break;
            }
            case DO_SET_SURFACE: {
                mTvInputSessionImpl.setSurface((Surface) msg.obj);
                break;
            }
            case DO_DISPATCH_SURFACE_CHANGED: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.dispatchSurfaceChanged(args.argi1, args.argi2, args.argi3);
                args.recycle();
                break;
            }
            case DO_SET_STREAM_VOLUME: {
                mTvInputSessionImpl.setStreamVolume((Float) msg.obj);
                break;
            }
            case DO_TUNE: {
                SomeArgs args = (SomeArgs) msg.obj;
                if (mIsRecordingSession) {
                    mTvInputRecordingSessionImpl.tune((Uri) args.arg1, (Bundle) args.arg2);
                } else {
                    mTvInputSessionImpl.tune((Uri) args.arg1, (Bundle) args.arg2);
                }
                args.recycle();
                break;
            }
            case DO_SET_CAPTION_ENABLED: {
                mTvInputSessionImpl.setCaptionEnabled((Boolean) msg.obj);
                break;
            }
            case DO_SELECT_TRACK: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.selectTrack((Integer) args.arg1, (String) args.arg2);
                args.recycle();
                break;
            }
            case DO_APP_PRIVATE_COMMAND: {
                SomeArgs args = (SomeArgs) msg.obj;
                if (mIsRecordingSession) {
                    mTvInputRecordingSessionImpl.appPrivateCommand(
                            (String) args.arg1, (Bundle) args.arg2);
                } else {
                    mTvInputSessionImpl.appPrivateCommand((String) args.arg1, (Bundle) args.arg2);
                }
                args.recycle();
                break;
            }
            case DO_CREATE_OVERLAY_VIEW: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.createOverlayView((IBinder) args.arg1, (Rect) args.arg2);
                args.recycle();
                break;
            }
            case DO_RELAYOUT_OVERLAY_VIEW: {
                mTvInputSessionImpl.relayoutOverlayView((Rect) msg.obj);
                break;
            }
            case DO_REMOVE_OVERLAY_VIEW: {
                mTvInputSessionImpl.removeOverlayView(true);
                break;
            }
            case DO_UNBLOCK_CONTENT: {
                mTvInputSessionImpl.unblockContent((String) msg.obj);
                break;
            }
            case DO_TIME_SHIFT_PLAY: {
                mTvInputSessionImpl.timeShiftPlay((Uri) msg.obj);
                break;
            }
            case DO_TIME_SHIFT_PAUSE: {
                mTvInputSessionImpl.timeShiftPause();
                break;
            }
            case DO_TIME_SHIFT_RESUME: {
                mTvInputSessionImpl.timeShiftResume();
                break;
            }
            case DO_TIME_SHIFT_SEEK_TO: {
                mTvInputSessionImpl.timeShiftSeekTo((Long) msg.obj);
                break;
            }
            case DO_TIME_SHIFT_SET_PLAYBACK_PARAMS: {
                mTvInputSessionImpl.timeShiftSetPlaybackParams((PlaybackParams) msg.obj);
                break;
            }
            case DO_TIME_SHIFT_SET_MODE: {
                mTvInputSessionImpl.timeShiftSetMode(msg.arg1);
                break;
            }
            case DO_TIME_SHIFT_ENABLE_POSITION_TRACKING: {
                mTvInputSessionImpl.timeShiftEnablePositionTracking((Boolean) msg.obj);
                break;
            }
            case DO_START_RECORDING: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputRecordingSessionImpl.startRecording((Uri) args.arg1, (Bundle) args.arg2);
                args.recycle();
                break;
            }
            case DO_STOP_RECORDING: {
                mTvInputRecordingSessionImpl.stopRecording();
                break;
            }
            case DO_PAUSE_RECORDING: {
                mTvInputRecordingSessionImpl.pauseRecording((Bundle) msg.obj);
                break;
            }
            case DO_RESUME_RECORDING: {
                mTvInputRecordingSessionImpl.resumeRecording((Bundle) msg.obj);
                break;
            }
            case DO_SELECT_AUDIO_PRESENTATION: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.selectAudioPresentation(
                        (Integer) args.arg1, (Integer) args.arg2);
                args.recycle();
                break;
            }
            case DO_REQUEST_BROADCAST_INFO: {
                mTvInputSessionImpl.requestBroadcastInfo((BroadcastInfoRequest) msg.obj);
                break;
            }
            case DO_REMOVE_BROADCAST_INFO: {
                mTvInputSessionImpl.removeBroadcastInfo(msg.arg1);
                break;
            }
            case DO_SET_IAPP_NOTIFICATION_ENABLED: {
                mTvInputSessionImpl.setInteractiveAppNotificationEnabled((Boolean) msg.obj);
                break;
            }
            case DO_SET_TV_MESSAGE_ENABLED: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.setTvMessageEnabled((Integer) args.arg1, (Boolean) args.arg2);
                args.recycle();
                break;
            }
            case DO_REQUEST_AD: {
                mTvInputSessionImpl.requestAd((AdRequest) msg.obj);
                break;
            }
            case DO_NOTIFY_AD_BUFFER: {
                mTvInputSessionImpl.notifyAdBufferReady((AdBuffer) msg.obj);
                break;
            }
            case DO_NOTIFY_TV_MESSAGE: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.onTvMessageReceived((Integer) args.arg1, (Bundle) args.arg2);
                break;
            }
            case DO_STOP_PLAYBACK: {
                mTvInputSessionImpl.stopPlayback(msg.arg1);
                break;
            }
            case DO_START_PLAYBACK: {
                mTvInputSessionImpl.startPlayback();
                break;
            }
            case DO_SET_VIDEO_FROZEN: {
                mTvInputSessionImpl.setVideoFrozen((Boolean) msg.obj);
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
            if (msg.what == DO_TUNE && durationMs > EXECUTE_MESSAGE_TUNE_TIMEOUT_MILLIS) {
                throw new RuntimeException("Too much time to handle tune request. (" + durationMs
                        + "ms > " + EXECUTE_MESSAGE_TUNE_TIMEOUT_MILLIS + "ms) "
                        + "Consider handling the tune request in a separate thread.");
            }
            if (durationMs > EXECUTE_MESSAGE_TIMEOUT_LONG_MILLIS) {
                throw new RuntimeException("Too much time to handle a request. (type=" + msg.what
                    + ", " + durationMs + "ms > " + EXECUTE_MESSAGE_TIMEOUT_LONG_MILLIS + "ms).");
            }
        }
    }

    @Override
    public void release() {
        if (!mIsRecordingSession) {
            mTvInputSessionImpl.scheduleOverlayViewCleanup();
        }
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_RELEASE));
    }

    @Override
    public void setMain(boolean isMain) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_MAIN, isMain));
    }

    @Override
    public void setSurface(Surface surface) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_SURFACE, surface));
    }

    @Override
    public void dispatchSurfaceChanged(int format, int width, int height) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIIII(DO_DISPATCH_SURFACE_CHANGED,
                format, width, height, 0));
    }

    @Override
    public final void setVolume(float volume) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_STREAM_VOLUME, volume));
    }

    @Override
    public void tune(Uri channelUri, Bundle params) {
        // Clear the pending tune requests.
        mCaller.removeMessages(DO_TUNE);
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_TUNE, channelUri, params));
    }

    @Override
    public void setCaptionEnabled(boolean enabled) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_CAPTION_ENABLED, enabled));
    }

    @Override
    public void selectAudioPresentation(int presentationId, int programId) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_SELECT_AUDIO_PRESENTATION, presentationId, programId));
    }

    @Override
    public void selectTrack(int type, String trackId) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_SELECT_TRACK, type, trackId));
    }

    @Override
    public void setInteractiveAppNotificationEnabled(boolean enabled) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SET_IAPP_NOTIFICATION_ENABLED, enabled));
    }

    @Override
    public void appPrivateCommand(String action, Bundle data) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_APP_PRIVATE_COMMAND, action,
                data));
    }

    @Override
    public void createOverlayView(IBinder windowToken, Rect frame) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_CREATE_OVERLAY_VIEW, windowToken,
                frame));
    }

    @Override
    public void relayoutOverlayView(Rect frame) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_RELAYOUT_OVERLAY_VIEW, frame));
    }

    @Override
    public void removeOverlayView() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_REMOVE_OVERLAY_VIEW));
    }

    @Override
    public void unblockContent(String unblockedRating) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(
                DO_UNBLOCK_CONTENT, unblockedRating));
    }

    @Override
    public void timeShiftPlay(Uri recordedProgramUri) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(
                DO_TIME_SHIFT_PLAY, recordedProgramUri));
    }

    @Override
    public void timeShiftPause() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_TIME_SHIFT_PAUSE));
    }

    @Override
    public void timeShiftResume() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_TIME_SHIFT_RESUME));
    }

    @Override
    public void timeShiftSeekTo(long timeMs) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_TIME_SHIFT_SEEK_TO, timeMs));
    }

    @Override
    public void timeShiftSetPlaybackParams(PlaybackParams params) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_TIME_SHIFT_SET_PLAYBACK_PARAMS,
                params));
    }

    @Override
    public void timeShiftSetMode(int mode) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageI(DO_TIME_SHIFT_SET_MODE, mode));
    }

    @Override
    public void timeShiftEnablePositionTracking(boolean enable) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(
                DO_TIME_SHIFT_ENABLE_POSITION_TRACKING, enable));
    }

    @Override
    public void startRecording(@Nullable Uri programUri, @Nullable Bundle params) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_START_RECORDING, programUri,
                params));
    }

    @Override
    public void stopRecording() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_STOP_RECORDING));
    }

    @Override
    public void pauseRecording(@Nullable Bundle params) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_PAUSE_RECORDING, params));
    }

    @Override
    public void resumeRecording(@Nullable Bundle params) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_RESUME_RECORDING, params));
    }

    @Override
    public void requestBroadcastInfo(BroadcastInfoRequest request) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_REQUEST_BROADCAST_INFO, request));
    }

    @Override
    public void removeBroadcastInfo(int requestId) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageI(DO_REMOVE_BROADCAST_INFO, requestId));
    }

    @Override
    public void requestAd(AdRequest request) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_REQUEST_AD, request));
    }

    @Override
    public void notifyAdBufferReady(AdBuffer buffer) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_NOTIFY_AD_BUFFER, buffer));
    }

    @Override
    public void setVideoFrozen(boolean isFrozen) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_VIDEO_FROZEN, isFrozen));
    }

    @Override
    public void notifyTvMessage(int type, Bundle data) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_NOTIFY_TV_MESSAGE, type, data));
    }

    @Override
    public void setTvMessageEnabled(int type, boolean enabled) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_SET_TV_MESSAGE_ENABLED, type,
                enabled));
    }

    @Override
    public void stopPlayback(int mode) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_STOP_PLAYBACK, mode));
    }

    @Override
    public void startPlayback() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_START_PLAYBACK));
    }


    private final class TvInputEventReceiver extends InputEventReceiver {
        TvInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            if (mTvInputSessionImpl == null) {
                // The session has been finished.
                finishInputEvent(event, false);
                return;
            }

            int handled = mTvInputSessionImpl.dispatchInputEvent(event, this);
            if (handled != TvInputManager.Session.DISPATCH_IN_PROGRESS) {
                finishInputEvent(event, handled == TvInputManager.Session.DISPATCH_HANDLED);
            }
        }
    }
}
