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

import android.annotation.SystemApi;
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
 * Displays TV contents. The TvView class provides a high level interface for applications to show
 * TV programs from various TV sources that implement {@link TvInputService}. (Note that the list of
 * TV inputs available on the system can be obtained by calling
 * {@link TvInputManager#getTvInputList() TvInputManager.getTvInputList()}.)
 * <p>
 * Once the application supplies the URI for a specific TV channel to {@link #tune(String, Uri)}
 * method, it takes care of underlying service binding (and unbinding if the current TvView is
 * already bound to a service) and automatically allocates/deallocates resources needed. In addition
 * to a few essential methods to control how the contents are presented, it also provides a way to
 * dispatch input events to the connected TvInputService in order to enable custom key actions for
 * the TV input.
 * </p>
 */
public class TvView extends ViewGroup {
    private static final String TAG = "TvView";
    // STOPSHIP: Turn debugging off.
    private static final boolean DEBUG = true;

    private static final int VIDEO_SIZE_VALUE_UNKNOWN = 0;

    private static final int ZORDER_MEDIA = 0;
    private static final int ZORDER_MEDIA_OVERLAY = 1;
    private static final int ZORDER_ON_TOP = 2;

    private static final int CAPTION_DEFAULT = 0;
    private static final int CAPTION_ENABLED = 1;
    private static final int CAPTION_DISABLED = 2;

    private static final Object sMainTvViewLock = new Object();
    private static TvView sMainTvView;

    private final Handler mHandler = new Handler();
    private Session mSession;
    private SurfaceView mSurfaceView;
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
    private boolean mSurfaceChanged;
    private int mSurfaceFormat;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private final AttributeSet mAttrs;
    private final int mDefStyleAttr;
    private int mWindowZOrder;
    private boolean mUseRequestedSurfaceLayout;
    private int mSurfaceViewLeft;
    private int mSurfaceViewRight;
    private int mSurfaceViewTop;
    private int mSurfaceViewBottom;
    private int mCaptionEnabled;

    private final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "surfaceChanged(holder=" + holder + ", format=" + format + ", width="
                    + width + ", height=" + height + ")");
            }
            mSurfaceFormat = format;
            mSurfaceWidth = width;
            mSurfaceHeight = height;
            mSurfaceChanged = true;
            dispatchSurfaceChanged(mSurfaceFormat, mSurfaceWidth, mSurfaceHeight);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            mSurface = holder.getSurface();
            setSessionSurface(mSurface);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            mSurface = null;
            mSurfaceChanged = false;
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
        mAttrs = attrs;
        mDefStyleAttr = defStyleAttr;
        resetSurfaceView();
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
     * Sets this as the main {@link TvView}.
     * <p>
     * The main {@link TvView} is a {@link TvView} whose corresponding TV input determines the
     * HDMI-CEC active source device. For an HDMI port input, one of source devices that is
     * connected to that HDMI port becomes the active source. For an HDMI-CEC logical device input,
     * the corresponding HDMI-CEC logical device becomes the active source. For any non-HDMI input
     * (including the tuner, composite, S-Video, etc.), the internal device (= TV itself) becomes
     * the active source.
     * </p><p>
     * First tuned {@link TvView} becomes main automatically, and keeps to be main until {@link
     * #setMain} is called for other {@link TvView}. Note that main {@link TvView} won't be reset
     * even when current main {@link TvView} is removed from view hierarchy.
     * </p>
     * @hide
     */
    @SystemApi
    public void setMain() {
        synchronized (sMainTvViewLock) {
            sMainTvView = this;
            if (hasWindowFocus() && mSession != null) {
                mSession.setMain();
            }
        }
    }

    /**
     * Sets the Z order of a window owning the surface of this TvView above the normal TvView
     * but below an application.
     *
     * @see SurfaceView#setZOrderMediaOverlay
     * @hide
     */
    @SystemApi
    public void setZOrderMediaOverlay(boolean isMediaOverlay) {
        if (isMediaOverlay) {
            mWindowZOrder = ZORDER_MEDIA_OVERLAY;
            removeSessionOverlayView();
        } else {
            mWindowZOrder = ZORDER_MEDIA;
            createSessionOverlayView();
        }
        if (mSurfaceView != null) {
            // ZOrderOnTop(false) removes WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            // from WindowLayoutParam as well as changes window type.
            mSurfaceView.setZOrderOnTop(false);
            mSurfaceView.setZOrderMediaOverlay(isMediaOverlay);
        }
    }

    /**
     * Sets the Z order of a window owning the surface of this TvView on top of an application.
     *
     * @see SurfaceView#setZOrderOnTop
     * @hide
     */
    @SystemApi
    public void setZOrderOnTop(boolean onTop) {
        if (onTop) {
            mWindowZOrder = ZORDER_ON_TOP;
            removeSessionOverlayView();
        } else {
            mWindowZOrder = ZORDER_MEDIA;
            createSessionOverlayView();
        }
        if (mSurfaceView != null) {
            mSurfaceView.setZOrderMediaOverlay(false);
            mSurfaceView.setZOrderOnTop(onTop);
        }
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
     * @param inputId The ID of TV input which will play the given channel.
     * @param channelUri The URI of a channel.
     */
    public void tune(String inputId, Uri channelUri) {
        tune(inputId, channelUri, null);
    }

    /**
     * Tunes to a given channel.
     *
     * @param inputId The ID of TV input which will play the given channel.
     * @param channelUri The URI of a channel.
     * @param params Extra parameters which might be handled with the tune event.
     * @hide
     */
    @SystemApi
    public void tune(String inputId, Uri channelUri, Bundle params) {
        if (DEBUG) Log.d(TAG, "tune(" + channelUri + ")");
        if (TextUtils.isEmpty(inputId)) {
            throw new IllegalArgumentException("inputId cannot be null or an empty string");
        }
        synchronized (sMainTvViewLock) {
            if (sMainTvView == null) {
                sMainTvView = this;
            }
        }
        if (mSessionCallback != null && mSessionCallback.mInputId.equals(inputId)) {
            if (mSession != null) {
                mSession.tune(channelUri, params);
            } else {
                // Session is not created yet. Replace the channel which will be set once the
                // session is made.
                mSessionCallback.mChannelUri = channelUri;
                mSessionCallback.mTuneParams = params;
            }
        } else {
            reset();
            // When createSession() is called multiple times before the callback is called,
            // only the callback of the last createSession() call will be actually called back.
            // The previous callbacks will be ignored. For the logic, mSessionCallback
            // is newly assigned for every createSession request and compared with
            // MySessionCreateCallback.this.
            mSessionCallback = new MySessionCallback(inputId, channelUri, params);
            mTvInputManager.createSession(inputId, mSessionCallback, mHandler);
        }
    }

    /**
     * Resets this TvView.
     * <p>
     * This method is primarily used to un-tune the current TvView.
     */
    public void reset() {
        if (DEBUG) Log.d(TAG, "reset()");
        if (mSession != null) {
            release();
            resetSurfaceView();
        }
    }

    /**
     * Requests to unblock TV content according to the given rating.
     * <p>
     * This notifies TV input that blocked content is now OK to play.
     * </p>
     *
     * @param unblockedRating A TvContentRating to unblock.
     * @see TvInputService.Session#notifyContentBlocked(TvContentRating)
     * @hide
     */
    @SystemApi
    public void requestUnblockContent(TvContentRating unblockedRating) {
        if (mSession != null) {
            mSession.requestUnblockContent(unblockedRating);
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
        mCaptionEnabled = enabled ? CAPTION_ENABLED : CAPTION_DISABLED;
        if (mSession != null) {
            mSession.setCaptionEnabled(enabled);
        }
    }

    /**
     * Selects a track.
     *
     * @param type The type of the track to select. The type can be {@link TvTrackInfo#TYPE_AUDIO},
     *            {@link TvTrackInfo#TYPE_VIDEO} or {@link TvTrackInfo#TYPE_SUBTITLE}.
     * @param trackId The ID of the track to select. {@code null} means to unselect the current
     *            track for a given type.
     * @see #getTracks
     * @see #getSelectedTrack
     */
    public void selectTrack(int type, String trackId) {
        if (mSession != null) {
            mSession.selectTrack(type, trackId);
        }
    }

    /**
     * Returns the list of tracks. Returns {@code null} if the information is not available.
     *
     * @param type The type of the tracks. The type can be {@link TvTrackInfo#TYPE_AUDIO},
     *            {@link TvTrackInfo#TYPE_VIDEO} or {@link TvTrackInfo#TYPE_SUBTITLE}.
     * @see #selectTrack
     * @see #getSelectedTrack
     */
    public List<TvTrackInfo> getTracks(int type) {
        if (mSession == null) {
            return null;
        }
        return mSession.getTracks(type);
    }

    /**
     * Returns the ID of the selected track for a given type. Returns {@code null} if the
     * information is not available or the track is not selected.
     *
     * @param type The type of the selected tracks. The type can be {@link TvTrackInfo#TYPE_AUDIO},
     *            {@link TvTrackInfo#TYPE_VIDEO} or {@link TvTrackInfo#TYPE_SUBTITLE}.
     * @see #selectTrack
     * @see #getTracks
     */
    public String getSelectedTrack(int type) {
        if (mSession == null) {
            return null;
        }
        return mSession.getSelectedTrack(type);
    }

    /**
     * Calls {@link TvInputService.Session#appPrivateCommand(String, Bundle)
     * TvInputService.Session.appPrivateCommand()} on the current TvView.
     *
     * @param action Name of the command to be performed. This <em>must</em> be a scoped name, i.e.
     *            prefixed with a package name you own, so that different developers will not create
     *            conflicting commands.
     * @param data Any data to include with the command.
     * @hide
     */
    @SystemApi
    public void sendAppPrivateCommand(String action, Bundle data) {
        if (TextUtils.isEmpty(action)) {
            throw new IllegalArgumentException("action cannot be null or an empty string");
        }
        if (mSession != null) {
            mSession.sendAppPrivateCommand(action, data);
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
     * Called when an unhandled input event also has not been handled by the user provided
     * callback. This is the last chance to handle the unhandled input event in the TvView.
     *
     * @param event The input event.
     * @return If you handled the event, return {@code true}. If you want to allow the event to be
     *         handled by the next receiver, return {@code false}.
     */
    public boolean onUnhandledInputEvent(InputEvent event) {
        return false;
    }

    /**
     * Registers a callback to be invoked when an input event is not handled by the bound TV input.
     *
     * @param listener The callback to be invoked when the unhandled input event is received.
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
    public void dispatchWindowFocusChanged(boolean hasFocus) {
        super.dispatchWindowFocusChanged(hasFocus);
        // Other app may have shown its own main TvView.
        // Set main again to regain main session.
        synchronized (sMainTvViewLock) {
            if (hasFocus && this == sMainTvView && mSession != null) {
                mSession.setMain();
            }
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

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) {
            Log.d(TAG, "onLayout (left=" + left + ", top=" + top + ", right=" + right
                    + ", bottom=" + bottom + ",)");
        }
        if (mUseRequestedSurfaceLayout) {
            mSurfaceView.layout(mSurfaceViewLeft, mSurfaceViewTop, mSurfaceViewRight,
                    mSurfaceViewBottom);
        } else {
            mSurfaceView.layout(0, 0, right - left, bottom - top);
        }
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
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        mSurfaceView.setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            createSessionOverlayView();
        } else {
            removeSessionOverlayView();
        }
    }

    private void resetSurfaceView() {
        if (mSurfaceView != null) {
            mSurfaceView.getHolder().removeCallback(mSurfaceHolderCallback);
            removeView(mSurfaceView);
        }
        mSurface = null;
        mSurfaceView = new SurfaceView(getContext(), mAttrs, mDefStyleAttr) {
            @Override
            protected void updateWindow(boolean force, boolean redrawNeeded) {
                super.updateWindow(force, redrawNeeded);
                relayoutSessionOverlayView();
            }};
        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
        if (mWindowZOrder == ZORDER_MEDIA_OVERLAY) {
            mSurfaceView.setZOrderMediaOverlay(true);
        } else if (mWindowZOrder == ZORDER_ON_TOP) {
            mSurfaceView.setZOrderOnTop(true);
        }
        addView(mSurfaceView);
    }

    private void release() {
        setSessionSurface(null);
        removeSessionOverlayView();
        mUseRequestedSurfaceLayout = false;
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

    private void dispatchSurfaceChanged(int format, int width, int height) {
        if (mSession == null) {
            return;
        }
        mSession.dispatchSurfaceChanged(format, width, height);
    }

    private void createSessionOverlayView() {
        if (mSession == null || !isAttachedToWindow()
                || mOverlayViewCreated || mWindowZOrder != ZORDER_MEDIA) {
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
        if (mSession == null || !isAttachedToWindow() || !mOverlayViewCreated
                || mWindowZOrder != ZORDER_MEDIA) {
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
     * Interface used to receive various status updates on the {@link TvView}.
     */
    public abstract static class TvInputListener {

        /**
         * This is invoked when an error occurred while establishing a connection to the underlying
         * TV input.
         *
         * @param inputId The ID of the TV input bound to this view.
         */
        public void onConnectionFailed(String inputId) {
        }

        /**
         * This is invoked when the existing connection to the underlying TV input is lost.
         *
         * @param inputId The ID of the TV input bound to this view.
         */
        public void onDisconnected(String inputId) {
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
        public void onTracksChanged(String inputId, List<TvTrackInfo> tracks) {
        }

        /**
         * This is called when there is a change on the selected tracks.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param type The type of the track selected. The type can be
         *            {@link TvTrackInfo#TYPE_AUDIO}, {@link TvTrackInfo#TYPE_VIDEO} or
         *            {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @param trackId The ID of the track selected.
         */
        public void onTrackSelected(String inputId, int type, String trackId) {
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
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_TUNING}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_BUFFERING}
         * </ul>
         */
        public void onVideoUnavailable(String inputId, int reason) {
        }

        /**
         * This is called when the current program content turns out to be allowed to watch since
         * its content rating is not blocked by parental controls.
         *
         * @param inputId The ID of the TV input bound to this view.
         */
        public void onContentAllowed(String inputId) {
        }

        /**
         * This is called when the current program content turns out to be not allowed to watch
         * since its content rating is blocked by parental controls.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param rating The content rating of the blocked program.
         */
        public void onContentBlocked(String inputId, TvContentRating rating) {
        }

        /**
         * This is invoked when a custom event from the bound TV input is sent to this view.
         *
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         * @hide
         */
        @SystemApi
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
        Bundle mTuneParams;

        MySessionCallback(String inputId, Uri channelUri, Bundle tuneParams) {
            mInputId = inputId;
            mChannelUri = channelUri;
            mTuneParams = tuneParams;
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
            if (DEBUG) {
                Log.d(TAG, "onSessionCreated()");
            }
            mSession = session;
            if (session != null) {
                synchronized (sMainTvViewLock) {
                    if (hasWindowFocus() && TvView.this == sMainTvView) {
                        mSession.setMain();
                    }
                }
                // mSurface may not be ready yet as soon as starting an application.
                // In the case, we don't send Session.setSurface(null) unnecessarily.
                // setSessionSurface will be called in surfaceCreated.
                if (mSurface != null) {
                    setSessionSurface(mSurface);
                    if (mSurfaceChanged) {
                        dispatchSurfaceChanged(mSurfaceFormat, mSurfaceWidth, mSurfaceHeight);
                    }
                }
                createSessionOverlayView();
                if (mCaptionEnabled != CAPTION_DEFAULT) {
                    mSession.setCaptionEnabled(mCaptionEnabled == CAPTION_ENABLED);
                }
                mSession.tune(mChannelUri, mTuneParams);
                if (mHasStreamVolume) {
                    mSession.setStreamVolume(mStreamVolume);
                }
            } else {
                if (mListener != null) {
                    mListener.onConnectionFailed(mInputId);
                }
            }
        }

        @Override
        public void onSessionReleased(Session session) {
            if (this != mSessionCallback) {
                return;
            }
            mSessionCallback = null;
            mSession = null;
            if (mListener != null) {
                mListener.onDisconnected(mInputId);
            }
        }

        @Override
        public void onChannelRetuned(Session session, Uri channelUri) {
            if (this != mSessionCallback) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onChannelChangedByTvInput(" + channelUri + ")");
            }
            if (mListener != null) {
                mListener.onChannelRetuned(mInputId, channelUri);
            }
        }

        @Override
        public void onTracksChanged(Session session, List<TvTrackInfo> tracks) {
            if (this != mSessionCallback) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onTracksChanged()");
            }
            if (mListener != null) {
                mListener.onTracksChanged(mInputId, tracks);
            }
        }

        @Override
        public void onTrackSelected(Session session, int type, String trackId) {
            if (this != mSessionCallback) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onTrackSelected()");
            }
            // TODO: Update the video size when the type is TYPE_VIDEO.
            if (mListener != null) {
                mListener.onTrackSelected(mInputId, type, trackId);
            }
        }

        @Override
        public void onVideoAvailable(Session session) {
            if (this != mSessionCallback) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onVideoAvailable()");
            }
            if (mListener != null) {
                mListener.onVideoAvailable(mInputId);
            }
        }

        @Override
        public void onVideoUnavailable(Session session, int reason) {
            if (this != mSessionCallback) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onVideoUnavailable(" + reason + ")");
            }
            if (mListener != null) {
                mListener.onVideoUnavailable(mInputId, reason);
            }
        }

        @Override
        public void onContentAllowed(Session session) {
            if (this != mSessionCallback) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onContentAllowed()");
            }
            if (mListener != null) {
                mListener.onContentAllowed(mInputId);
            }
        }

        @Override
        public void onContentBlocked(Session session, TvContentRating rating) {
            if (DEBUG) {
                Log.d(TAG, "onContentBlocked()");
            }
            if (mListener != null) {
                mListener.onContentBlocked(mInputId, rating);
            }
        }

        @Override
        public void onLayoutSurface(Session session, int left, int top, int right, int bottom) {
            if (DEBUG) {
                Log.d(TAG, "onLayoutSurface (left=" + left + ", top=" + top + ", right="
                        + right + ", bottom=" + bottom + ",)");
            }
            mSurfaceViewLeft = left;
            mSurfaceViewTop = top;
            mSurfaceViewRight = right;
            mSurfaceViewBottom = bottom;
            mUseRequestedSurfaceLayout = true;
            requestLayout();
        }

        @Override
        public void onSessionEvent(Session session, String eventType, Bundle eventArgs) {
            if (this != mSessionCallback) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onSessionEvent(" + eventType + ")");
            }
            if (mListener != null) {
                mListener.onEvent(mInputId, eventType, eventArgs);
            }
        }
    }
}
