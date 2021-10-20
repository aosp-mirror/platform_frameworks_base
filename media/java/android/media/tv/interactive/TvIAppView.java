/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.Context;
import android.media.tv.interactive.TvIAppManager.Session;
import android.media.tv.interactive.TvIAppManager.SessionCallback;
import android.os.Handler;
import android.util.Log;
import android.view.ViewGroup;

/**
 * Displays contents of interactive TV applications.
 * @hide
 */
public class TvIAppView extends ViewGroup {
    private static final String TAG = "TvIAppView";
    private static final boolean DEBUG = false;

    private final TvIAppManager mTvIAppManager;
    private final Handler mHandler = new Handler();
    private Session mSession;
    private MySessionCallback mSessionCallback;

    public TvIAppView(Context context) {
        super(context, /* attrs = */null, /* defStyleAttr = */0);
        mTvIAppManager = (TvIAppManager) getContext().getSystemService("tv_interactive_app");
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (DEBUG) {
            Log.d(TAG,
                    "onLayout (left=" + l + ", top=" + t + ", right=" + r + ", bottom=" + b + ",)");
        }
    }

    /**
     * Prepares the interactive application.
     */
    public void prepareIApp(String iAppServiceId, int type) {
        // TODO: document and handle the cases that this method is called multiple times.
        if (DEBUG) {
            Log.d(TAG, "prepareIApp");
        }
        mSessionCallback = new MySessionCallback(iAppServiceId, type);
        if (mTvIAppManager != null) {
            mTvIAppManager.createSession(iAppServiceId, type, mSessionCallback, mHandler);
        }
    }

    /**
     * Starts the interactive application.
     */
    public void startIApp() {
        if (DEBUG) {
            Log.d(TAG, "startIApp");
        }
        if (mSession != null) {
            mSession.startIApp();
        }
    }

    private class MySessionCallback extends SessionCallback {
        final String mIAppServiceId;
        int mType;

        MySessionCallback(String iAppServiceId, int type) {
            mIAppServiceId = iAppServiceId;
            mType = type;
        }

        @Override
        public void onSessionCreated(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onSessionCreated()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSessionCreated - session already created");
                // This callback is obsolete.
                if (session != null) {
                    session.release();
                }
                return;
            }
            mSession = session;
            if (session != null) {
                // TODO: handle SurfaceView and InputChannel.
            } else {
                // Failed to create
                // Todo: forward error to Tv App
                mSessionCallback = null;
            }
        }

        @Override
        public void onSessionReleased(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onSessionReleased()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSessionReleased - session not created");
                return;
            }
            mSessionCallback = null;
            mSession = null;
        }
    }
}
