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
import android.media.tv.TvInputManager.Session;
import android.media.tv.TvInputManager.Session.FinishedInputEventCallback;
import android.media.tv.TvInputManager.SessionCallback;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;

import java.util.List;

/**
 * View playing TV
 */
public class TvView extends ViewGroup {
    private static final String TAG = "TvView";
    // STOPSHIP: Turn debugging off.
    private static final boolean DEBUG = true;

    /**
     * Passed with {@link TvInputListener#onError(String, int)}. Indicates that the requested TV
     * input is busy and unable to handle the request.
     */
    public static final int ERROR_BUSY = 0;

    /**
     * Passed with {@link TvInputListener#onError(String, int)}. Indicates that the underlying TV
     * input has been disconnected.
     */
    public static final int ERROR_TV_INPUT_DISCONNECTED = 1;

    private static final int VIDEO_SIZE_VALUE_UNKNOWN = 0;

    private final Handler mHandler = new Handler();
    private TvInputManager.Session mSession;
    private final SurfaceView mSurfaceView;
    private Surface mSurface;
    private boolean mOverlayViewCreated;
    private Rect mOverlayViewFrame;
    private final TvInputManager mTvInputManager;
    private MySessionCallback mSessionCallback;
    private TvInputListener mListener;
    private OnUnhandledInputEventListener mOnUnhandledInputEventListener;
    private boolean mHasStreamVolume;
    private float mStreamVolume;
    private int mVideoWidth = VIDEO_SIZE_VALUE_UNKNOWN;
    private int mVideoHeight = VIDEO_SIZE_VALUE_UNKNOWN;

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
            ViewRootImpl viewRootImpl = getViewRootImpl();
            if (viewRootImpl != null) {
                viewRootImpl.dispatchUnhandledInputEvent(event);
            }
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
        mSurfaceView = new SurfaceView(context, attrs, defStyleAttr) {
                @Override
                protected void updateWindow(boolean force, boolean redrawNeeded) {
                    super.updateWindow(force, redrawNeeded);
                    relayoutSessionOverlayView();
                }};
        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
        addView(mSurfaceView);
        mTvInputManager = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
    }

    /**
     * Sets a listener for events in this TvView.
     *
     * @param listener The listener to be called with events. A value of {@code null} removes any
     *         existing listener.
     */
    public void setTvInputListener(TvInputListener listener) {
        mListener = listener;
    }

    /**
     * Sets the relative stream volume of this session to handle a change of audio focus.
     *
     * @param volume A volume value between 0.0f to 1.0f.
     */
    public void setStreamVolume(float volume) {
        if (DEBUG) Log.d(TAG, "setStreamVolume(" + volume + ")");
        mHasStreamVolume = true;
        mStreamVolume = volume;
        if (mSession == null) {
            // Volume will be set once the connection has been made.
            return;
        }
        mSession.setStreamVolume(volume);
    }

    /**
     * Tunes to a given channel.
     *
     * @param inputId the id of TV input which will play the given channel.
     * @param channelUri The URI of a channel.
     */
    public void tune(String inputId, Uri channelUri) {
        if (DEBUG) Log.d(TAG, "tune(" + channelUri + ")");
        if (TextUtils.isEmpty(inputId)) {
            throw new IllegalArgumentException("inputId cannot be null or an empty string");
        }
        if (mSessionCallback != null && mSessionCallback.mInputId.equals(inputId)) {
            if (mSession != null) {
                mSession.tune(channelUri);
            } else {
                // Session is not created yet. Replace the channel which will be set once the
                // session is made.
                mSessionCallback.mChannelUri = channelUri;
            }
        } else {
            if (mSession != null) {
                release();
            }
            // When createSession() is called multiple times before the callback is called,
            // only the callback of the last createSession() call will be actually called back.
            // The previous callbacks will be ignored. For the logic, mSessionCallback
            // is newly assigned for every createSession request and compared with
            // MySessionCreateCallback.this.
            mSessionCallback = new MySessionCallback(inputId, channelUri);
            mTvInputManager.createSession(inputId, mSessionCallback, mHandler);
        }
    }

    /**
     * Resets this TvView.
     * <p>
     * This method is primarily used to un-tune the current TvView.
     */
    public void reset() {
        if (mSession != null) {
            release();
        }
    }

    /**
     * Enables or disables the caption in this TvView.
     * <p>
     * Note that this method does not take any effect unless the current TvView is tuned.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     */
    public void setCaptionEnabled(boolean enabled) {
        if (mSession != null) {
            mSession.setCaptionEnabled(enabled);
        }
    }

    /**
     * Select a track.
     * <p>
     * If it is called multiple times on the same type of track (ie. Video, Audio, Text), the track
     * selected in previous will be unselected. Note that this method does not take any effect
     * unless the current TvView is tuned.
     * </p>
     *
     * @param track the track to be selected.
     * @see #getTracks()
     */
    public void selectTrack(TvTrackInfo track) {
        if (mSession != null) {
            mSession.selectTrack(track);
        }
    }

    /**
     * Unselect a track.
     * <p>
     * Note that this method does not take any effect unless the current TvView is tuned.
     *
     * @param track the track to be unselected.
     * @see #getTracks()
     */
    public void unselectTrack(TvTrackInfo track) {
        if (mSession != null) {
            mSession.unselectTrack(track);
        }
    }

    /**
     * Returns a list which includes of track information. May return {@code null} if the
     * information is not available.
     */
    public List<TvTrackInfo> getTracks() {
        if (mSession == null) {
            return null;
        }
        return mSession.getTracks();
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

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mSurfaceView.layout(0, 0, right - left, bottom - top);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mSurfaceView.measure(widthMeasureSpec, heightMeasureSpec);
        int width = mSurfaceView.getMeasuredWidth();
        int height = mSurfaceView.getMeasuredHeight();
        int childState = mSurfaceView.getMeasuredState();
        setMeasuredDimension(resolveSizeAndState(width, widthMeasureSpec, childState),
                resolveSizeAndState(height, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        mSurfaceView.setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            createSessionOverlayView();
        } else {
            removeSessionOverlayView();
        }
    }

    private void release() {
        setSessionSurface(null);
        removeSessionOverlayView();
        mSession.release();
        mSession = null;
        mSessionCallback = null;
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

    private void updateVideoSize(List<TvTrackInfo> tracks) {
        for (TvTrackInfo track : tracks) {
            if (track.getBoolean(TvTrackInfo.KEY_IS_SELECTED)
                    && track.getInt(TvTrackInfo.KEY_TYPE) == TvTrackInfo.VALUE_TYPE_VIDEO) {
                int width = track.getInt(TvTrackInfo.KEY_WIDTH);
                int height = track.getInt(TvTrackInfo.KEY_HEIGHT);
                if (width != mVideoWidth || height != mVideoHeight) {
                    mVideoWidth = width;
                    mVideoHeight = height;
                    if (mListener != null) {
                        mListener.onVideoSizeChanged(mSessionCallback.mInputId, width, height);
                    }
                }
            }
        }
    }

    /**
     * Interface used to receive various status updates on the {@link TvView}.
     */
    public abstract static class TvInputListener {

        /**
         * This is invoked when an error occurred while handling requested operation.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param errorCode The error code. For the details of error code, please see
         *         {@link TvView}.
         */
        public void onError(String inputId, int errorCode) {
        }

        /**
         * This is invoked when the view is tuned to a specific channel and starts decoding video
         * stream from there. It is also called later when the video size is changed.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param width The width of the video.
         * @param height The height of the video.
         */
        public void onVideoSizeChanged(String inputId, int width, int height) {
        }

        /**
         * This is invoked when the channel of this TvView is changed by the underlying TV input
         * with out any {@link TvView#tune(String, Uri)} request.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param channelUri The URI of a channel.
         */
        public void onChannelRetuned(String inputId, Uri channelUri) {
        }

        /**
         * This is called when the track information has been changed.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param tracks A list which includes track information.
         */
        public void onTrackInfoChanged(String inputId, List<TvTrackInfo> tracks) {
        }

        /**
         * This is called when the video is available, so the TV input starts the playback.
         *
         * @param inputId The ID of the TV input bound to this view.
         */
        public void onVideoAvailable(String inputId) {
        }

        /**
         * This is called when the video is not available, so the TV input stops the playback.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param reason The reason why the TV input stopped the playback:
         * <ul>
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_UNKNOWN}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_TUNE}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_BUFFERING}
         * </ul>
         */
        public void onVideoUnavailable(String inputId, int reason) {
        }

        /**
         * This is invoked when a custom event from the bound TV input is sent to this view.
         *
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         * @hide
         */
        public void onEvent(String inputId, String eventType, Bundle eventArgs) {
        }
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
        final String mInputId;
        Uri mChannelUri;

        MySessionCallback(String inputId, Uri channelUri) {
            mInputId = inputId;
            mChannelUri = channelUri;
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
                mSession.tune(mChannelUri);
                if (mHasStreamVolume) {
                    mSession.setStreamVolume(mStreamVolume);
                }
            } else {
                if (mListener != null) {
                    mListener.onError(mInputId, ERROR_BUSY);
                }
            }
        }

        @Override
        public void onSessionReleased(Session session) {
            if (this == mSessionCallback) {
                mSessionCallback = null;
            }
            mSession = null;
            if (mListener != null) {
                mListener.onError(mInputId, ERROR_TV_INPUT_DISCONNECTED);
            }
        }

        @Override
        public void onChannelRetuned(Session session, Uri channelUri) {
            if (DEBUG) {
                Log.d(TAG, "onChannelChangedByTvInput(" + channelUri + ")");
            }
            if (mListener != null) {
                mListener.onChannelRetuned(mInputId, channelUri);
            }
        }

        @Override
        public void onTrackInfoChanged(Session session, List<TvTrackInfo> tracks) {
            if (this != mSessionCallback) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onTrackInfoChanged()");
            }
            updateVideoSize(tracks);
            if (mListener != null) {
                mListener.onTrackInfoChanged(mInputId, tracks);
            }
        }

        public void onVideoAvailable(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onVideoAvailable()");
            }
            if (mListener != null) {
                mListener.onVideoAvailable(mInputId);
            }
        }

        public void onVideoUnavailable(Session session, int reason) {
            if (DEBUG) {
                Log.d(TAG, "onVideoUnavailable(" + reason + ")");
            }
            if (mListener != null) {
                mListener.onVideoUnavailable(mInputId, reason);
            }
        }

        @Override
        public void onSessionEvent(TvInputManager.Session session, String eventType,
                Bundle eventArgs) {
            if (this != mSessionCallback) {
                return;
            }
            if (mListener != null) {
                mListener.onEvent(mInputId, eventType, eventArgs);
            }
        }
    }
}
