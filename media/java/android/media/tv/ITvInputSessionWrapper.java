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
    private static final int DO_SET_SURFACE = 2;
    private static final int DO_SET_VOLUME = 3;
    private static final int DO_TUNE = 4;
    private static final int DO_CREATE_OVERLAY_VIEW = 5;
    private static final int DO_RELAYOUT_OVERLAY_VIEW = 6;
    private static final int DO_REMOVE_OVERLAY_VIEW = 7;

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
            case DO_SET_SURFACE: {
                mTvInputSessionImpl.setSurface((Surface) msg.obj);
                return;
            }
            case DO_SET_VOLUME: {
                mTvInputSessionImpl.setVolume((Float) msg.obj);
                return;
            }
            case DO_TUNE: {
                mTvInputSessionImpl.tune((Uri) msg.obj);
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
    public void setSurface(Surface surface) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_SURFACE, surface));
    }

    @Override
    public final void setVolume(float volume) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_VOLUME, volume));
    }

    @Override
    public void tune(Uri channelUri) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_TUNE, channelUri));
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
