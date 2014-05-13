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
import android.graphics.Rect;
import android.os.Handler;
import android.text.TextUtils;
import android.tv.TvInputManager.Session;
import android.tv.TvInputManager.Session.FinishedInputEventCallback;
import android.tv.TvInputManager.SessionCallback;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * View playing TV
 */
public class TvView extends SurfaceView {
    // STOPSHIP: Turn debugging off.
    private static final boolean DEBUG = true;
    private static final String TAG = "TvView";

    private final Handler mHandler = new Handler();
    private TvInputManager.Session mSession;
    private Surface mSurface;
    private boolean mOverlayViewCreated;
    private Rect mOverlayViewFrame;
    private final TvInputManager mTvInputManager;
    private SessionCallback mSessionCallback;
    private OnUnhandledInputEventListener mOnUnhandledInputEventListener;

    private final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
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

    private final FinishedInputEventCallback mFinishedInputEventCallback =
            new FinishedInputEventCallback() {
        @Override
        public void onFinishedInputEvent(Object token, boolean handled) {
            if (DEBUG) {
                Log.d(TAG, "onFinishedInputEvent(token=" + token + ", handled=" + handled + ")");
            }
            if (handled) {
                return;
            }
            // TODO: Re-order unhandled events.
            InputEvent event = (InputEvent) token;
            if (dispatchUnhandledInputEvent(event)) {
                return;
            }
            getViewRootImpl().dispatchUnhandledInputEvent(event);
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
     * Binds a TV input to this view. {@link SessionCallback#onSessionCreated} will be
     * called to send the result of this binding with {@link TvInputManager.Session}.
     * If a TV input is already bound, the input will be unbound from this view and its session
     * will be released.
     *
     * @param inputId the id of TV input which will be bound to this view.
     * @param callback called when TV input is bound. The callback sends
     *        {@link TvInputManager.Session}
     * @throws IllegalArgumentException if any of the arguments is {@code null}.
     */
    public void bindTvInput(String inputId, SessionCallback callback) {
        if (TextUtils.isEmpty(inputId)) {
            throw new IllegalArgumentException("inputId cannot be null or an empty string");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        if (mSession != null) {
            release();
        }
        // When bindTvInput is called multiple times before the callback is called,
        // only the callback of the last bindTvInput call will be actually called back.
        // The previous callbacks will be ignored. For the logic, mSessionCallback
        // is newly assigned for every bindTvInput call and compared with
        // MySessionCreateCallback.this.
        mSessionCallback = new MySessionCallback(callback);
        mTvInputManager.createSession(inputId, mSessionCallback, mHandler);
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

    /**
     * Dispatches an unhandled input event to the next receiver.
     * <p>
     * Except system keys, TvView always consumes input events in the normal flow. This is called
     * asynchronously from where the event is dispatched. It gives the host application a chance to
     * dispatch the unhandled input events.
     *
     * @param event The input event.
     * @return {@code true} if the event was handled by the view, {@code false} otherwise.
     */
    public boolean dispatchUnhandledInputEvent(InputEvent event) {
        if (mOnUnhandledInputEventListener != null) {
            if (mOnUnhandledInputEventListener.onUnhandledInputEvent(event)) {
                return true;
            }
        }
        return onUnhandledInputEvent(event);
    }

    /**
     * Called when an unhandled input event was also not handled by the user provided callback. This
     * is the last chance to handle the unhandled input event in the TvView.
     *
     * @param event The input event.
     * @return If you handled the event, return {@code true}. If you want to allow the event to be
     *         handled by the next receiver, return {@code false}.
     */
    public boolean onUnhandledInputEvent(InputEvent event) {
        return false;
    }

    /**
     * Registers a callback to be invoked when an input event was not handled by the bound TV input.
     *
     * @param listener The callback to invoke when the unhandled input event was received.
     */
    public void setOnUnhandledInputEventListener(OnUnhandledInputEventListener listener) {
        mOnUnhandledInputEventListener = listener;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (super.dispatchKeyEvent(event)) {
            return true;
        }
        if (DEBUG) Log.d(TAG, "dispatchKeyEvent(" + event + ")");
        if (mSession == null) {
            return false;
        }
        InputEvent copiedEvent = event.copy();
        int ret = mSession.dispatchInputEvent(copiedEvent, copiedEvent, mFinishedInputEventCallback,
                mHandler);
        return ret != Session.DISPATCH_NOT_HANDLED;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (super.dispatchTouchEvent(event)) {
            return true;
        }
        if (DEBUG) Log.d(TAG, "dispatchTouchEvent(" + event + ")");
        if (mSession == null) {
            return false;
        }
        InputEvent copiedEvent = event.copy();
        int ret = mSession.dispatchInputEvent(copiedEvent, copiedEvent, mFinishedInputEventCallback,
                mHandler);
        return ret != Session.DISPATCH_NOT_HANDLED;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        if (super.dispatchTrackballEvent(event)) {
            return true;
        }
        if (DEBUG) Log.d(TAG, "dispatchTrackballEvent(" + event + ")");
        if (mSession == null) {
            return false;
        }
        InputEvent copiedEvent = event.copy();
        int ret = mSession.dispatchInputEvent(copiedEvent, copiedEvent, mFinishedInputEventCallback,
                mHandler);
        return ret != Session.DISPATCH_NOT_HANDLED;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (super.dispatchGenericMotionEvent(event)) {
            return true;
        }
        if (DEBUG) Log.d(TAG, "dispatchGenericMotionEvent(" + event + ")");
        if (mSession == null) {
            return false;
        }
        InputEvent copiedEvent = event.copy();
        int ret = mSession.dispatchInputEvent(copiedEvent, copiedEvent, mFinishedInputEventCallback,
                mHandler);
        return ret != Session.DISPATCH_NOT_HANDLED;
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

    /**
     * Interface definition for a callback to be invoked when the unhandled input event is received.
     */
    public interface OnUnhandledInputEventListener {
        /**
         * Called when an input event was not handled by the bound TV input.
         * <p>
         * This is called asynchronously from where the event is dispatched. It gives the host
         * application a chance to handle the unhandled input events.
         *
         * @param event The input event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        boolean onUnhandledInputEvent(InputEvent event);
    }

    private class MySessionCallback extends SessionCallback {
        final SessionCallback mExternalCallback;

        MySessionCallback(SessionCallback externalCallback) {
            mExternalCallback = externalCallback;
        }

        @Override
        public void onSessionCreated(Session session) {
            if (this != mSessionCallback) {
                // This callback is obsolete.
                if (session != null) {
                    session.release();
                }
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

        @Override
        public void onSessionReleased(Session session) {
            mSession = null;
            if (mExternalCallback != null) {
                mExternalCallback.onSessionReleased(session);
            }
        }
    }
}
