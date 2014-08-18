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

import android.content.Context;
import android.graphics.Rect;
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
    private static final int DO_REQUEST_UNBLOCK_CONTENT = 13;

    private final HandlerCaller mCaller;

    private TvInputService.Session mTvInputSessionImpl;
    private InputChannel mChannel;
    private TvInputEventReceiver mReceiver;

    public ITvInputSessionWrapper(Context context, TvInputService.Session sessionImpl,
            InputChannel channel) {
        mCaller = new HandlerCaller(context, null, this, true /* asyncHandler */);
        mTvInputSessionImpl = sessionImpl;
        mChannel = channel;
        if (channel != null) {
            mReceiver = new TvInputEventReceiver(channel, context.getMainLooper());
        }
    }

    @Override
    public void executeMessage(Message msg) {
        if (mTvInputSessionImpl == null) {
            return;
        }

        switch (msg.what) {
            case DO_RELEASE: {
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
                return;
            }
            case DO_SET_MAIN: {
                mTvInputSessionImpl.setMain((Boolean) msg.obj);
                return;
            }
            case DO_SET_SURFACE: {
                mTvInputSessionImpl.setSurface((Surface) msg.obj);
                return;
            }
            case DO_DISPATCH_SURFACE_CHANGED: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.dispatchSurfaceChanged(args.argi1, args.argi2, args.argi3);
                args.recycle();
                return;
            }
            case DO_SET_STREAM_VOLUME: {
                mTvInputSessionImpl.setStreamVolume((Float) msg.obj);
                return;
            }
            case DO_TUNE: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.tune((Uri) args.arg1, (Bundle) args.arg2);
                args.recycle();
                return;
            }
            case DO_SET_CAPTION_ENABLED: {
                mTvInputSessionImpl.setCaptionEnabled((Boolean) msg.obj);
                return;
            }
            case DO_SELECT_TRACK: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.selectTrack((Integer) args.arg1, (String) args.arg2);
                args.recycle();
                return;
            }
            case DO_APP_PRIVATE_COMMAND: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.appPrivateCommand((String) args.arg1, (Bundle) args.arg2);
                args.recycle();
                return;
            }
            case DO_CREATE_OVERLAY_VIEW: {
                SomeArgs args = (SomeArgs) msg.obj;
                mTvInputSessionImpl.createOverlayView((IBinder) args.arg1, (Rect) args.arg2);
                args.recycle();
                return;
            }
            case DO_RELAYOUT_OVERLAY_VIEW: {
                mTvInputSessionImpl.relayoutOverlayView((Rect) msg.obj);
                return;
            }
            case DO_REMOVE_OVERLAY_VIEW: {
                mTvInputSessionImpl.removeOverlayView(true);
                return;
            }
            case DO_REQUEST_UNBLOCK_CONTENT: {
                mTvInputSessionImpl.unblockContent((String) msg.obj);
                return;
            }
            default: {
                Log.w(TAG, "Unhandled message code: " + msg.what);
                return;
            }
        }
    }

    @Override
    public void release() {
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
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_TUNE, channelUri, params));
    }

    @Override
    public void setCaptionEnabled(boolean enabled) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_CAPTION_ENABLED, enabled));
    }

    @Override
    public void selectTrack(int type, String trackId) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_SELECT_TRACK, type, trackId));
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
    public void requestUnblockContent(String unblockedRating) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(
                DO_REQUEST_UNBLOCK_CONTENT, unblockedRating));
    }

    private final class TvInputEventReceiver extends InputEventReceiver {
        public TvInputEventReceiver(InputChannel inputChannel, Looper looper) {
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
