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

package android.tv;

import android.content.Context;
import android.net.Uri;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import com.android.internal.os.HandlerCaller;

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

    private TvInputSession mTvInputSession;
    private final HandlerCaller mCaller;

    public ITvInputSessionWrapper(Context context, TvInputSession session) {
        mCaller = new HandlerCaller(context, null, this, true /* asyncHandler */);
        mTvInputSession = session;
    }

    @Override
    public void executeMessage(Message msg) {
        if (mTvInputSession == null) {
            return;
        }

        switch (msg.what) {
            case DO_RELEASE: {
                mTvInputSession.release();
                mTvInputSession = null;
                return;
            }
            case DO_SET_SURFACE: {
                mTvInputSession.setSurface((Surface) msg.obj);
                return;
            }
            case DO_SET_VOLUME: {
                mTvInputSession.setVolume((Float) msg.obj);
                return;
            }
            case DO_TUNE: {
                mTvInputSession.tune((Uri) msg.obj);
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
}
