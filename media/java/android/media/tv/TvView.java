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

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.media.PlaybackParams;
import android.media.tv.TvInputManager.Session;
import android.media.tv.TvInputManager.Session.FinishedInputEventCallback;
import android.media.tv.TvInputManager.SessionCallback;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.Xml;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Displays TV contents. The TvView class provides a high level interface for applications to show
 * TV programs from various TV sources that implement {@link TvInputService}. (Note that the list of
 * TV inputs available on the system can be obtained by calling
 * {@link TvInputManager#getTvInputList() TvInputManager.getTvInputList()}.)
 *
 * <p>Once the application supplies the URI for a specific TV channel to {@link #tune}
 * method, it takes care of underlying service binding (and unbinding if the current TvView is
 * already bound to a service) and automatically allocates/deallocates resources needed. In addition
 * to a few essential methods to control how the contents are presented, it also provides a way to
 * dispatch input events to the connected TvInputService in order to enable custom key actions for
 * the TV input.
 */
public class TvView extends ViewGroup {
    private static final String TAG = "TvView";
    private static final boolean DEBUG = false;

    private static final int ZORDER_MEDIA = 0;
    private static final int ZORDER_MEDIA_OVERLAY = 1;
    private static final int ZORDER_ON_TOP = 2;

    private static final WeakReference<TvView> NULL_TV_VIEW = new WeakReference<>(null);

    private static final Object sMainTvViewLock = new Object();
    private static WeakReference<TvView> sMainTvView = NULL_TV_VIEW;

    private final Handler mHandler = new Handler();
    private Session mSession;
    private SurfaceView mSurfaceView;
    private Surface mSurface;
    private boolean mOverlayViewCreated;
    private Rect mOverlayViewFrame;
    private final TvInputManager mTvInputManager;
    private MySessionCallback mSessionCallback;
    private TvInputCallback mCallback;
    private OnUnhandledInputEventListener mOnUnhandledInputEventListener;
    private Float mStreamVolume;
    private Boolean mCaptionEnabled;
    private final Queue<Pair<String, Bundle>> mPendingAppPrivateCommands = new ArrayDeque<>();

    private boolean mSurfaceChanged;
    private int mSurfaceFormat;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private final AttributeSet mAttrs;
    private final XmlResourceParser mParser;
    private final int mDefStyleAttr;
    private int mWindowZOrder;
    private boolean mUseRequestedSurfaceLayout;
    private int mSurfaceViewLeft;
    private int mSurfaceViewRight;
    private int mSurfaceViewTop;
    private int mSurfaceViewBottom;
    private TimeShiftPositionCallback mTimeShiftPositionCallback;

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
        int sourceResId = Resources.getAttributeSetSourceResId(attrs);
        if (sourceResId != Resources.ID_NULL) {
            Log.d(TAG, "Build local AttributeSet");
            mParser  = context.getResources().getXml(sourceResId);
            mAttrs = Xml.asAttributeSet(mParser);
        } else {
            Log.d(TAG, "Use passed in AttributeSet");
            mParser = null;
            mAttrs = attrs;
        }
        mDefStyleAttr = defStyleAttr;
        resetSurfaceView();
        mTvInputManager = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
    }

    /**
     * Sets the callback to be invoked when an event is dispatched to this TvView.
     *
     * @param callback The callback to receive events. A value of {@code null} removes the existing
     *            callback.
     */
    public void setCallback(@Nullable TvInputCallback callback) {
        mCallback = callback;
    }

    /** @hide */
    public Session getInputSession() {
        return mSession;
    }

    /**
     * Sets this as the main {@link TvView}.
     *
     * <p>The main {@link TvView} is a {@link TvView} whose corresponding TV input determines the
     * HDMI-CEC active source device. For an HDMI port input, one of source devices that is
     * connected to that HDMI port becomes the active source. For an HDMI-CEC logical device input,
     * the corresponding HDMI-CEC logical device becomes the active source. For any non-HDMI input
     * (including the tuner, composite, S-Video, etc.), the internal device (= TV itself) becomes
     * the active source.
     *
     * <p>First tuned {@link TvView} becomes main automatically, and keeps to be main until either
     * {@link #reset} is called for the main {@link TvView} or {@code setMain()} is called for other
     * {@link TvView}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CHANGE_HDMI_CEC_ACTIVE_SOURCE)
    public void setMain() {
        synchronized (sMainTvViewLock) {
            sMainTvView = new WeakReference<>(this);
            if (hasWindowFocus() && mSession != null) {
                mSession.setMain();
            }
        }
    }

    /**
     * Controls whether the TvView's surface is placed on top of another regular surface view in the
     * window (but still behind the window itself).
     * This is typically used to place overlays on top of an underlying TvView.
     *
     * <p>Note that this must be set before the TvView's containing window is attached to the
     * window manager.
     *
     * <p>Calling this overrides any previous call to {@link #setZOrderOnTop}.
     *
     * @param isMediaOverlay {@code true} to be on top of another regular surface, {@code false}
     *            otherwise.
     */
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
     * Controls whether the TvView's surface is placed on top of its window. Normally it is placed
     * behind the window, to allow it to (for the most part) appear to composite with the views in
     * the hierarchy.  By setting this, you cause it to be placed above the window. This means that
     * none of the contents of the window this TvView is in will be visible on top of its surface.
     *
     * <p>Note that this must be set before the TvView's containing window is attached to the window
     * manager.
     *
     * <p>Calling this overrides any previous call to {@link #setZOrderMediaOverlay}.
     *
     * @param onTop {@code true} to be on top of its window, {@code false} otherwise.
     */
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
     * Sets the relative stream volume of this TvView.
     *
     * <p>This method is primarily used to handle audio focus changes or mute a specific TvView when
     * multiple views are displayed. If the method has not yet been called, the TvView assumes the
     * default value of {@code 1.0f}.
     *
     * @param volume A volume value between {@code 0.0f} to {@code 1.0f}.
     */
    public void setStreamVolume(@FloatRange(from = 0.0, to = 1.0) float volume) {
        if (DEBUG) Log.d(TAG, "setStreamVolume(" + volume + ")");
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
     * @param inputId The ID of the TV input for the given channel.
     * @param channelUri The URI of a channel.
     */
    public void tune(@NonNull String inputId, Uri channelUri) {
        tune(inputId, channelUri, null);
    }

    /**
     * Tunes to a given channel. This can be used to provide domain-specific features that are only
     * known between certain clients and their TV inputs.
     *
     * @param inputId The ID of TV input for the given channel.
     * @param channelUri The URI of a channel.
     * @param params Domain-specific data for this tune request. Keys <em>must</em> be a scoped
     *            name, i.e. prefixed with a package name you own, so that different developers will
     *            not create conflicting keys.
     */
    public void tune(String inputId, Uri channelUri, Bundle params) {
        if (DEBUG) Log.d(TAG, "tune(" + channelUri + ")");
        if (TextUtils.isEmpty(inputId)) {
            throw new IllegalArgumentException("inputId cannot be null or an empty string");
        }
        synchronized (sMainTvViewLock) {
            if (sMainTvView.get() == null) {
                sMainTvView = new WeakReference<>(this);
            }
        }
        if (mSessionCallback != null && TextUtils.equals(mSessionCallback.mInputId, inputId)) {
            if (mSession != null) {
                mSession.tune(channelUri, params);
            } else {
                // createSession() was called but the actual session for the given inputId has not
                // yet been created. Just replace the existing tuning params in the callback with
                // the new ones and tune later in onSessionCreated(). It is not necessary to create
                // a new callback because this tuning request was made on the same inputId.
                mSessionCallback.mChannelUri = channelUri;
                mSessionCallback.mTuneParams = params;
            }
        } else {
            resetInternal();
            // In case createSession() is called multiple times across different inputId's before
            // any session is created (e.g. when quickly tuning to a channel from input A and then
            // to another channel from input B), only the callback for the last createSession()
            // should be invoked. (The previous callbacks are simply ignored.) To do that, we create
            // a new callback each time and keep mSessionCallback pointing to the last one. If
            // MySessionCallback.this is different from mSessionCallback, we know that this callback
            // is obsolete and should ignore it.
            mSessionCallback = new MySessionCallback(inputId, channelUri, params);
            if (mTvInputManager != null) {
                mTvInputManager.createSession(inputId, mSessionCallback, mHandler);
            }
        }
    }

    /**
     * Resets this TvView.
     *
     * <p>This method is primarily used to un-tune the current TvView.
     */
    public void reset() {
        if (DEBUG) Log.d(TAG, "reset()");
        synchronized (sMainTvViewLock) {
            if (this == sMainTvView.get()) {
                sMainTvView = NULL_TV_VIEW;
            }
        }
        resetInternal();
    }

    private void resetInternal() {
        mSessionCallback = null;
        mPendingAppPrivateCommands.clear();
        if (mSession != null) {
            setSessionSurface(null);
            removeSessionOverlayView();
            mUseRequestedSurfaceLayout = false;
            mSession.release();
            mSession = null;
            resetSurfaceView();
        }
    }

    /**
     * Requests to unblock TV content according to the given rating.
     *
     * <p>This notifies TV input that blocked content is now OK to play.
     *
     * @param unblockedRating A TvContentRating to unblock.
     * @see TvInputService.Session#notifyContentBlocked(TvContentRating)
     * @removed
     */
    public void requestUnblockContent(TvContentRating unblockedRating) {
        unblockContent(unblockedRating);
    }

    /**
     * Requests to unblock TV content according to the given rating.
     *
     * <p>This notifies TV input that blocked content is now OK to play.
     *
     * @param unblockedRating A TvContentRating to unblock.
     * @see TvInputService.Session#notifyContentBlocked(TvContentRating)
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PARENTAL_CONTROLS)
    public void unblockContent(TvContentRating unblockedRating) {
        if (mSession != null) {
            mSession.unblockContent(unblockedRating);
        }
    }

    /**
     * Enables or disables the caption in this TvView.
     *
     * <p>Note that this method does not take any effect unless the current TvView is tuned.
     *
     * @param enabled {@code true} to enable, {@code false} to disable.
     */
    public void setCaptionEnabled(boolean enabled) {
        if (DEBUG) Log.d(TAG, "setCaptionEnabled(" + enabled + ")");
        mCaptionEnabled = enabled;
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
     * Enables or disables interactive app notification.
     *
     * <p>This method enables or disables the event detection from the corresponding TV input. When
     * it's enabled, the TV input service detects events related to interactive app, such as
     * AIT (Application Information Table) and sends to TvView or the linked TV interactive app
     * service.
     *
     * @param enabled {@code true} if you want to enable interactive app notifications.
     *                {@code false} otherwise.
     *
     * @see TvInputService.Session#notifyAitInfoUpdated(android.media.tv.AitInfo)
     * @see android.media.tv.interactive.TvInteractiveAppView#setTvView(TvView)
     */
    public void setInteractiveAppNotificationEnabled(boolean enabled) {
        if (mSession != null) {
            mSession.setInteractiveAppNotificationEnabled(enabled);
        }
    }

    /**
     * Plays a given recorded TV program.
     *
     * @param inputId The ID of the TV input that created the given recorded program.
     * @param recordedProgramUri The URI of a recorded program.
     */
    public void timeShiftPlay(String inputId, Uri recordedProgramUri) {
        if (DEBUG) Log.d(TAG, "timeShiftPlay(" + recordedProgramUri + ")");
        if (TextUtils.isEmpty(inputId)) {
            throw new IllegalArgumentException("inputId cannot be null or an empty string");
        }
        synchronized (sMainTvViewLock) {
            if (sMainTvView.get() == null) {
                sMainTvView = new WeakReference<>(this);
            }
        }
        if (mSessionCallback != null && TextUtils.equals(mSessionCallback.mInputId, inputId)) {
            if (mSession != null) {
                mSession.timeShiftPlay(recordedProgramUri);
            } else {
                mSessionCallback.mRecordedProgramUri = recordedProgramUri;
            }
        } else {
            resetInternal();
            mSessionCallback = new MySessionCallback(inputId, recordedProgramUri);
            if (mTvInputManager != null) {
                mTvInputManager.createSession(inputId, mSessionCallback, mHandler);
            }
        }
    }

    /**
     * Pauses playback. No-op if it is already paused. Call {@link #timeShiftResume} to resume.
     */
    public void timeShiftPause() {
        if (mSession != null) {
            mSession.timeShiftPause();
        }
    }

    /**
     * Resumes playback. No-op if it is already resumed. Call {@link #timeShiftPause} to pause.
     */
    public void timeShiftResume() {
        if (mSession != null) {
            mSession.timeShiftResume();
        }
    }

    /**
     * Seeks to a specified time position. {@code timeMs} must be equal to or greater than the start
     * position returned by {@link TimeShiftPositionCallback#onTimeShiftStartPositionChanged} and
     * equal to or less than the current time.
     *
     * @param timeMs The time position to seek to, in milliseconds since the epoch.
     */
    public void timeShiftSeekTo(long timeMs) {
        if (mSession != null) {
            mSession.timeShiftSeekTo(timeMs);
        }
    }

    /**
     * Sets playback rate using {@link android.media.PlaybackParams}.
     *
     * @param params The playback params.
     */
    public void timeShiftSetPlaybackParams(@NonNull PlaybackParams params) {
        if (mSession != null) {
            mSession.timeShiftSetPlaybackParams(params);
        }
    }

    /**
     * Sets the callback to be invoked when the time shift position is changed.
     *
     * @param callback The callback to receive time shift position changes. A value of {@code null}
     *            removes the existing callback.
     */
    public void setTimeShiftPositionCallback(@Nullable TimeShiftPositionCallback callback) {
        mTimeShiftPositionCallback = callback;
        ensurePositionTracking();
    }

    private void ensurePositionTracking() {
        if (mSession == null) {
            return;
        }
        mSession.timeShiftEnablePositionTracking(mTimeShiftPositionCallback != null);
    }

    /**
     * Sends a private command to the underlying TV input. This can be used to provide
     * domain-specific features that are only known between certain clients and their TV inputs.
     *
     * @param action The name of the private command to send. This <em>must</em> be a scoped name,
     *            i.e. prefixed with a package name you own, so that different developers will not
     *            create conflicting commands.
     * @param data An optional bundle to send with the command.
     */
    public void sendAppPrivateCommand(@NonNull String action, Bundle data) {
        if (TextUtils.isEmpty(action)) {
            throw new IllegalArgumentException("action cannot be null or an empty string");
        }
        if (mSession != null) {
            mSession.sendAppPrivateCommand(action, data);
        } else {
            Log.w(TAG, "sendAppPrivateCommand - session not yet created (action \"" + action
                    + "\" pending)");
            mPendingAppPrivateCommands.add(Pair.create(action, data));
        }
    }

    /**
     * Dispatches an unhandled input event to the next receiver.
     *
     * <p>Except system keys, TvView always consumes input events in the normal flow. This is called
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
            if (hasFocus && this == sMainTvView.get() && mSession != null
                    && checkChangeHdmiCecActiveSourcePermission()) {
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
    public boolean gatherTransparentRegion(Region region) {
        if (mWindowZOrder != ZORDER_ON_TOP) {
            if (region != null) {
                int width = getWidth();
                int height = getHeight();
                if (width > 0 && height > 0) {
                    int location[] = new int[2];
                    getLocationInWindow(location);
                    int left = location[0];
                    int top = location[1];
                    region.op(left, top, left + width, top + height, Region.Op.UNION);
                }
            }
        }
        return super.gatherTransparentRegion(region);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mWindowZOrder != ZORDER_ON_TOP) {
            // Punch a hole so that the underlying overlay view and surface can be shown.
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        }
        super.draw(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mWindowZOrder != ZORDER_ON_TOP) {
            // Punch a hole so that the underlying overlay view and surface can be shown.
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        }
        super.dispatchDraw(canvas);
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
            protected void updateSurface() {
                super.updateSurface();
                relayoutSessionOverlayView();
            }};
        // The surface view's content should be treated as secure all the time.
        mSurfaceView.setSecure(true);
        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
        if (mWindowZOrder == ZORDER_MEDIA_OVERLAY) {
            mSurfaceView.setZOrderMediaOverlay(true);
        } else if (mWindowZOrder == ZORDER_ON_TOP) {
            mSurfaceView.setZOrderOnTop(true);
        }
        addView(mSurfaceView);
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
        Rect frame = new Rect();
        getGlobalVisibleRect(frame);
        RectF frameF = new RectF(frame);
        getMatrix().mapRect(frameF);
        frameF.round(frame);
        return frame;
    }

    private boolean checkChangeHdmiCecActiveSourcePermission() {
        return getContext().checkSelfPermission(
                android.Manifest.permission.CHANGE_HDMI_CEC_ACTIVE_SOURCE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Callback used to receive time shift position changes.
     */
    public abstract static class TimeShiftPositionCallback {

        /**
         * This is called when the start position for time shifting has changed.
         *
         * <p>The start position for time shifting indicates the earliest possible time the user can
         * seek to. Initially this is equivalent to the time when the underlying TV input starts
         * recording. Later it may be adjusted because there is insufficient space or the duration
         * of recording is limited. The application must not allow the user to seek to a position
         * earlier than the start position.
         *
         * <p>For playback of a recorded program initiated by {@link #timeShiftPlay(String, Uri)},
         * the start position is the time when playback starts. It does not change.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param timeMs The start position for time shifting, in milliseconds since the epoch.
         */
        public void onTimeShiftStartPositionChanged(String inputId, long timeMs) {
        }

        /**
         * This is called when the current position for time shifting has changed.
         *
         * <p>The current position for time shifting is the same as the current position of
         * playback. During playback, the current position changes continuously. When paused, it
         * does not change.
         *
         * <p>Note that {@code timeMs} is wall-clock time.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param timeMs The current position for time shifting, in milliseconds since the epoch.
         */
        public void onTimeShiftCurrentPositionChanged(String inputId, long timeMs) {
        }
    }

    /**
     * Callback used to receive various status updates on the {@link TvView}.
     */
    public abstract static class TvInputCallback {

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
         * This is invoked when the channel of this TvView is changed by the underlying TV input
         * without any {@link TvView#tune} request.
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
         * This is invoked when the video size has been changed. It is also called when the first
         * time video size information becomes available after this view is tuned to a specific
         * channel.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param width The width of the video.
         * @param height The height of the video.
         */
        public void onVideoSizeChanged(String inputId, int width, int height) {
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
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY}
         * </ul>
         */
        public void onVideoUnavailable(
                String inputId, @TvInputManager.VideoUnavailableReason int reason) {
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
         * @param inputId The ID of the TV input bound to this view.
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         * @hide
         */
        @SystemApi
        public void onEvent(String inputId, String eventType, Bundle eventArgs) {
        }

        /**
         * This is called when the time shift status is changed.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param status The current time shift status. Should be one of the followings.
         * <ul>
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNSUPPORTED}
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNAVAILABLE}
         * <li>{@link TvInputManager#TIME_SHIFT_STATUS_AVAILABLE}
         * </ul>
         */
        public void onTimeShiftStatusChanged(
                String inputId, @TvInputManager.TimeShiftStatus int status) {
        }

        /**
         * This is called when the AIT (Application Information Table) info has been updated.
         *
         * @param aitInfo The current AIT info.
         */
        public void onAitInfoUpdated(@NonNull String inputId, @NonNull AitInfo aitInfo) {
        }

        /**
         * This is called when signal strength is updated.
         *
         * @param inputId The ID of the TV input bound to this view.
         * @param strength The current signal strength.
         */
        public void onSignalStrengthUpdated(
                @NonNull String inputId, @TvInputManager.SignalStrength int strength) {
        }

        /**
         * This is called when the session has been tuned to the given channel.
         *
         * @param channelUri The URI of a channel.
         */
        public void onTuned(@NonNull String inputId, @NonNull Uri channelUri) {
        }
    }

    /**
     * Interface definition for a callback to be invoked when the unhandled input event is received.
     */
    public interface OnUnhandledInputEventListener {
        /**
         * Called when an input event was not handled by the bound TV input.
         *
         * <p>This is called asynchronously from where the event is dispatched. It gives the host
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
        Uri mRecordedProgramUri;

        MySessionCallback(String inputId, Uri channelUri, Bundle tuneParams) {
            mInputId = inputId;
            mChannelUri = channelUri;
            mTuneParams = tuneParams;
        }

        MySessionCallback(String inputId, Uri recordedProgramUri) {
            mInputId = inputId;
            mRecordedProgramUri = recordedProgramUri;
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
                // Sends the pending app private commands first.
                for (Pair<String, Bundle> command : mPendingAppPrivateCommands) {
                    mSession.sendAppPrivateCommand(command.first, command.second);
                }
                mPendingAppPrivateCommands.clear();

                synchronized (sMainTvViewLock) {
                    if (hasWindowFocus() && TvView.this == sMainTvView.get()
                            && checkChangeHdmiCecActiveSourcePermission()) {
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
                if (mStreamVolume != null) {
                    mSession.setStreamVolume(mStreamVolume);
                }
                if (mCaptionEnabled != null) {
                    mSession.setCaptionEnabled(mCaptionEnabled);
                }
                if (mChannelUri != null) {
                    mSession.tune(mChannelUri, mTuneParams);
                } else {
                    mSession.timeShiftPlay(mRecordedProgramUri);
                }
                ensurePositionTracking();
            } else {
                mSessionCallback = null;
                if (mCallback != null) {
                    mCallback.onConnectionFailed(mInputId);
                }
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
            mOverlayViewCreated = false;
            mOverlayViewFrame = null;
            mSessionCallback = null;
            mSession = null;
            if (mCallback != null) {
                mCallback.onDisconnected(mInputId);
            }
        }

        @Override
        public void onChannelRetuned(Session session, Uri channelUri) {
            if (DEBUG) {
                Log.d(TAG, "onChannelChangedByTvInput(" + channelUri + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onChannelRetuned - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onChannelRetuned(mInputId, channelUri);
            }
        }

        @Override
        public void onTracksChanged(Session session, List<TvTrackInfo> tracks) {
            if (DEBUG) {
                Log.d(TAG, "onTracksChanged(" + tracks + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onTracksChanged - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onTracksChanged(mInputId, tracks);
            }
        }

        @Override
        public void onTrackSelected(Session session, int type, String trackId) {
            if (DEBUG) {
                Log.d(TAG, "onTrackSelected(type=" + type + ", trackId=" + trackId + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onTrackSelected - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onTrackSelected(mInputId, type, trackId);
            }
        }

        @Override
        public void onVideoSizeChanged(Session session, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "onVideoSizeChanged()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onVideoSizeChanged - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onVideoSizeChanged(mInputId, width, height);
            }
        }

        @Override
        public void onVideoAvailable(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onVideoAvailable()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onVideoAvailable - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onVideoAvailable(mInputId);
            }
        }

        @Override
        public void onVideoUnavailable(Session session, int reason) {
            if (DEBUG) {
                Log.d(TAG, "onVideoUnavailable(reason=" + reason + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onVideoUnavailable - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onVideoUnavailable(mInputId, reason);
            }
        }

        @Override
        public void onContentAllowed(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onContentAllowed()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onContentAllowed - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onContentAllowed(mInputId);
            }
        }

        @Override
        public void onContentBlocked(Session session, TvContentRating rating) {
            if (DEBUG) {
                Log.d(TAG, "onContentBlocked(rating=" + rating + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onContentBlocked - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onContentBlocked(mInputId, rating);
            }
        }

        @Override
        public void onLayoutSurface(Session session, int left, int top, int right, int bottom) {
            if (DEBUG) {
                Log.d(TAG, "onLayoutSurface (left=" + left + ", top=" + top + ", right="
                        + right + ", bottom=" + bottom + ",)");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onLayoutSurface - session not created");
                return;
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
            if (DEBUG) {
                Log.d(TAG, "onSessionEvent(" + eventType + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSessionEvent - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onEvent(mInputId, eventType, eventArgs);
            }
        }

        @Override
        public void onTimeShiftStatusChanged(Session session, int status) {
            if (DEBUG) {
                Log.d(TAG, "onTimeShiftStatusChanged()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onTimeShiftStatusChanged - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onTimeShiftStatusChanged(mInputId, status);
            }
        }

        @Override
        public void onTimeShiftStartPositionChanged(Session session, long timeMs) {
            if (DEBUG) {
                Log.d(TAG, "onTimeShiftStartPositionChanged()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onTimeShiftStartPositionChanged - session not created");
                return;
            }
            if (mTimeShiftPositionCallback != null) {
                mTimeShiftPositionCallback.onTimeShiftStartPositionChanged(mInputId, timeMs);
            }
        }

        @Override
        public void onTimeShiftCurrentPositionChanged(Session session, long timeMs) {
            if (DEBUG) {
                Log.d(TAG, "onTimeShiftCurrentPositionChanged()");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onTimeShiftCurrentPositionChanged - session not created");
                return;
            }
            if (mTimeShiftPositionCallback != null) {
                mTimeShiftPositionCallback.onTimeShiftCurrentPositionChanged(mInputId, timeMs);
            }
        }

        @Override
        public void onAitInfoUpdated(Session session, AitInfo aitInfo) {
            if (DEBUG) {
                Log.d(TAG, "onAitInfoUpdated(aitInfo=" + aitInfo + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onAitInfoUpdated - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onAitInfoUpdated(mInputId, aitInfo);
            }
        }

        @Override
        public void onSignalStrengthUpdated(Session session, int strength) {
            if (DEBUG) {
                Log.d(TAG, "onSignalStrengthUpdated(strength=" + strength + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSignalStrengthUpdated - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onSignalStrengthUpdated(mInputId, strength);
            }
        }

        @Override
        public void onTuned(Session session, Uri channelUri) {
            if (DEBUG) {
                Log.d(TAG, "onTuned(channelUri=" + channelUri + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onTuned - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onTuned(mInputId, channelUri);
            }
        }
    }
}
