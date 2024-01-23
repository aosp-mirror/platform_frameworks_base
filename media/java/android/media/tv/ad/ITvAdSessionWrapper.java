/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.tv.ad;

import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.Surface;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

/**
 * Implements the internal ITvAdSession interface.
 * @hide
 */
public class ITvAdSessionWrapper
        extends ITvAdSession.Stub implements HandlerCaller.Callback {

    private static final String TAG = "ITvAdSessionWrapper";

    private static final int EXECUTE_MESSAGE_TIMEOUT_SHORT_MILLIS = 1000;
    private static final int EXECUTE_MESSAGE_TIMEOUT_LONG_MILLIS = 5 * 1000;
    private static final int DO_RELEASE = 1;
    private static final int DO_SET_SURFACE = 2;
    private static final int DO_DISPATCH_SURFACE_CHANGED = 3;
    private static final int DO_CREATE_MEDIA_VIEW = 4;
    private static final int DO_RELAYOUT_MEDIA_VIEW = 5;
    private static final int DO_REMOVE_MEDIA_VIEW = 6;

    private final HandlerCaller mCaller;
    private TvAdService.Session mSessionImpl;
    private InputChannel mChannel;
    private TvAdEventReceiver mReceiver;

    public ITvAdSessionWrapper(
            Context context, TvAdService.Session mSessionImpl, InputChannel channel) {
        this.mSessionImpl = mSessionImpl;
        mCaller = new HandlerCaller(context, null, this, true /* asyncHandler */);
        mChannel = channel;
        if (channel != null) {
            mReceiver = new TvAdEventReceiver(channel, context.getMainLooper());
        }
    }

    @Override
    public void release() {
        mSessionImpl.scheduleMediaViewCleanup();
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_RELEASE));
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
    public void startAdService() throws RemoteException {

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

    private final class TvAdEventReceiver extends InputEventReceiver {
        TvAdEventReceiver(InputChannel inputChannel, Looper looper) {
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
            if (handled != TvAdManager.Session.DISPATCH_IN_PROGRESS) {
                finishInputEvent(
                        event, handled == TvAdManager.Session.DISPATCH_HANDLED);
            }
        }
    }
}
