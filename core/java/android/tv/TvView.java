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

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.tv.TvInputManager;
import android.tv.TvInputManager.Session;
import android.tv.TvInputManager.SessionCreateCallback;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewTreeObserver;

/**
 * View playing TV
 */
public class TvView extends SurfaceView {
    private static final String TAG = "TvView";

    private final Handler mHandler = new Handler();
    private TvInputManager.Session mSession;
    private Surface mSurface;
    private boolean mOverlayViewCreated;
    private Rect mOverlayViewFrame;
    private boolean mGlobalListenersAdded;
    private TvInputManager mTvInputManager;
    private SessionCreateCallback mSessionCreateCallback;

    private SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "surfaceChanged(holder=" + holder + ", format=" + format + ", width=" + width
                    + ", height=" + height + ")");
            if (holder.getSurface() == mSurface) {
                return;
            }
            mSurface = holder.getSurface();
            setSessionSurface(mSurface);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            mSurface = holder.getSurface();
            setSessionSurface(mSurface);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            mSurface = null;
            setSessionSurface(null);
        }
    };

    public TvView(Context context) {
        this(context, null, 0);
    }

    public TvView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TvView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        getHolder().addCallback(mSurfaceHolderCallback);
        mTvInputManager = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
    }

    /**
     * Binds a TV input to this view. {@link SessionCreateCallback#onSessionCreated} will be
     * called to send the result of this binding with {@link TvInputManager.Session}.
     * If a TV input is already bound, the input will be unbound from this view and its session
     * will be released.
     *
     * @param name TV input name will be bound to this view.
     * @param callback called when TV input is bound. The callback sends
     *        {@link TvInputManager.Session}
     * @throws IllegalArgumentException if any of the arguments is {@code null}.
     */
    public void bindTvInput(ComponentName name, SessionCreateCallback callback) {
        if (name == null) {
            throw new IllegalArgumentException("name cannot be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        if (mSession != null) {
            release();
        }
        // When bindTvInput is called multiple times before the callback is called,
        // only the callback of the last bindTvInput call will be actually called back.
        // The previous callbacks will be ignored. For the logic, mSessionCreateCallback
        // is newly assigned for every bindTvInput call and compared with
        // MySessionCreateCallback.this.
        mSessionCreateCallback = new MySessionCreateCallback(callback);
        mTvInputManager.createSession(name, mSessionCreateCallback, mHandler);
    }

    /**
     * Unbinds a TV input currently bound. Its corresponding {@link TvInputManager.Session}
     * is released.
     */
    public void unbindTvInput() {
        if (mSession != null) {
            release();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        createSessionOverlayView();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeSessionOverlayView();
        super.onDetachedFromWindow();
    }

    /** @hide */
    @Override
    protected void updateWindow(boolean force, boolean redrawNeeded) {
        super.updateWindow(force, redrawNeeded);
        relayoutSessionOverlayView();
    }

    private void release() {
        setSessionSurface(null);
        removeSessionOverlayView();
        mSession.release();
        mSession = null;
    }

    private void setSessionSurface(Surface surface) {
        if (mSession == null) {
            return;
        }
        mSession.setSurface(surface);
    }

    private void createSessionOverlayView() {
        if (mSession == null || !isAttachedToWindow()
                || mOverlayViewCreated) {
            return;
        }
        mOverlayViewFrame = getViewFrameOnScreen();
        mSession.createOverlayView(this, mOverlayViewFrame);
        mOverlayViewCreated = true;
    }

    private void removeSessionOverlayView() {
        if (mSession == null || !mOverlayViewCreated) {
            return;
        }
        mSession.removeOverlayView();
        mOverlayViewCreated = false;
        mOverlayViewFrame = null;
    }

    private void relayoutSessionOverlayView() {
        if (mSession == null || !isAttachedToWindow()
                || !mOverlayViewCreated) {
            return;
        }
        Rect viewFrame = getViewFrameOnScreen();
        if (viewFrame.equals(mOverlayViewFrame)) {
            return;
        }
        mSession.relayoutOverlayView(viewFrame);
        mOverlayViewFrame = viewFrame;
    }

    private Rect getViewFrameOnScreen() {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Rect(location[0], location[1],
                location[0] + getWidth(), location[1] + getHeight());
    }

    private class MySessionCreateCallback implements SessionCreateCallback {
        final SessionCreateCallback mExternalCallback;

        MySessionCreateCallback(SessionCreateCallback externalCallback) {
            mExternalCallback = externalCallback;
        }

        @Override
        public void onSessionCreated(Session session) {
            if (this != mSessionCreateCallback) {
                // This callback is obsolete.
                session.release();
                return;
            }
            mSession = session;
            if (session != null) {
                // mSurface may not be ready yet as soon as starting an application.
                // In the case, we don't send Session.setSurface(null) unnecessarily.
                // setSessionSurface will be called in surfaceCreated.
                if (mSurface != null) {
                    setSessionSurface(mSurface);
                }
                createSessionOverlayView();
            }
            if (mExternalCallback != null) {
                mExternalCallback.onSessionCreated(session);
            }
        }
    }
}
