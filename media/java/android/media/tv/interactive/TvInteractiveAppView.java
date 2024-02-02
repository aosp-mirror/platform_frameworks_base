/*
 * Copyright 2021 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.PlaybackParams;
import android.media.tv.TvInputManager;
import android.media.tv.TvRecordingInfo;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.media.tv.flags.Flags;
import android.media.tv.interactive.TvInteractiveAppManager.Session;
import android.media.tv.interactive.TvInteractiveAppManager.Session.FinishedInputEventCallback;
import android.media.tv.interactive.TvInteractiveAppManager.SessionCallback;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;

import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Displays contents of interactive TV applications.
 */
public class TvInteractiveAppView extends ViewGroup {
    private static final String TAG = "TvInteractiveAppView";
    private static final boolean DEBUG = false;

    private static final int SET_TVVIEW_SUCCESS = 1;
    private static final int SET_TVVIEW_FAIL = 2;
    private static final int UNSET_TVVIEW_SUCCESS = 3;
    private static final int UNSET_TVVIEW_FAIL = 4;

    /**
     * Used to share client {@link java.security.cert.Certificate} with
     * {@link TvInteractiveAppService}.
     * @see #createBiInteractiveApp(Uri, Bundle)
     * @see java.security.cert.Certificate
     */
    public static final String BI_INTERACTIVE_APP_KEY_CERTIFICATE = "certificate";
    /**
     * Used to share the {@link KeyStore} alias with {@link TvInteractiveAppService}.
     * @see #createBiInteractiveApp(Uri, Bundle)
     * @see KeyStore#aliases()
     */
    public static final String BI_INTERACTIVE_APP_KEY_ALIAS = "alias";
    /**
     * Used to share the {@link java.security.PrivateKey} with {@link TvInteractiveAppService}.
     * <p>The private key is optional. It is used to encrypt data when necessary.
     *
     * @see #createBiInteractiveApp(Uri, Bundle)
     * @see java.security.PrivateKey
     */
    public static final String BI_INTERACTIVE_APP_KEY_PRIVATE_KEY = "private_key";
    /**
     * Additional HTTP headers to be used by {@link TvInteractiveAppService} to load the
     * broadcast-independent interactive application.
     * @see #createBiInteractiveApp(Uri, Bundle)
     */
    public static final String BI_INTERACTIVE_APP_KEY_HTTP_ADDITIONAL_HEADERS =
            "http_additional_headers";
    /**
     * HTTP user agent to be used by {@link TvInteractiveAppService} for broadcast-independent
     * interactive application.
     * @see #createBiInteractiveApp(Uri, Bundle)
     */
    public static final String BI_INTERACTIVE_APP_KEY_HTTP_USER_AGENT = "http_user_agent";

    /**
     * The name of the method where the error happened, if applicable. For example, if there is an
     * error during signing, the request name is "onRequestSigning".
     * @see #notifyError(String, Bundle)
     */
    public static final String ERROR_KEY_METHOD_NAME = "method_name";

    private final TvInteractiveAppManager mTvInteractiveAppManager;
    private final Handler mHandler = new Handler();
    private final Object mCallbackLock = new Object();
    private Session mSession;
    private MySessionCallback mSessionCallback;
    private TvInteractiveAppCallback mCallback;
    private Executor mCallbackExecutor;
    private SurfaceView mSurfaceView;
    private Surface mSurface;

    private boolean mSurfaceChanged;
    private int mSurfaceFormat;
    private int mSurfaceWidth;
    private int mSurfaceHeight;

    private boolean mUseRequestedSurfaceLayout;
    private int mSurfaceViewLeft;
    private int mSurfaceViewRight;
    private int mSurfaceViewTop;
    private int mSurfaceViewBottom;

    private boolean mMediaViewCreated;
    private Rect mMediaViewFrame;

    private final AttributeSet mAttrs;
    private final int mDefStyleAttr;
    private final XmlResourceParser mParser;
    private OnUnhandledInputEventListener mOnUnhandledInputEventListener;

    private final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "surfaceChanged(holder=" + holder + ", format=" + format
                        + ", width=" + width + ", height=" + height + ")");
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

    public TvInteractiveAppView(@NonNull Context context) {
        this(context, null, 0);
    }

    public TvInteractiveAppView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TvInteractiveAppView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
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
        mTvInteractiveAppManager = (TvInteractiveAppManager) getContext().getSystemService(
                Context.TV_INTERACTIVE_APP_SERVICE);
    }

    /**
     * Sets the callback to be invoked when an event is dispatched to this TvInteractiveAppView.
     *
     * @param callback the callback to receive events. MUST NOT be {@code null}.
     *
     * @see #clearCallback()
     */
    public void setCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull TvInteractiveAppCallback callback) {
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, callback);
        synchronized (mCallbackLock) {
            mCallbackExecutor = executor;
            mCallback = callback;
        }
    }

    /**
     * Clears the callback.
     *
     * @see #setCallback(Executor, TvInteractiveAppCallback)
     */
    public void clearCallback() {
        synchronized (mCallbackLock) {
            mCallback = null;
            mCallbackExecutor = null;
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        createSessionMediaView();
    }

    @Override
    public void onDetachedFromWindow() {
        removeSessionMediaView();
        super.onDetachedFromWindow();
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
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
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mSurfaceView.measure(widthMeasureSpec, heightMeasureSpec);
        int width = mSurfaceView.getMeasuredWidth();
        int height = mSurfaceView.getMeasuredHeight();
        int childState = mSurfaceView.getMeasuredState();
        setMeasuredDimension(resolveSizeAndState(width, widthMeasureSpec, childState),
                resolveSizeAndState(height, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override
    public void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        mSurfaceView.setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            createSessionMediaView();
        } else {
            removeSessionMediaView();
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
                relayoutSessionMediaView();
            }};
        // The surface view's content should be treated as secure all the time.
        mSurfaceView.setSecure(true);
        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
        mSurfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);

        mSurfaceView.setZOrderOnTop(false);
        mSurfaceView.setZOrderMediaOverlay(true);

        addView(mSurfaceView);
    }

    /**
     * Resets this TvInteractiveAppView to release its resources.
     *
     * <p>It can be reused by call {@link #prepareInteractiveApp(String, int)}.
     */
    public void reset() {
        if (DEBUG) Log.d(TAG, "reset()");
        resetInternal();
    }

    private void createSessionMediaView() {
        // TODO: handle z-order
        if (mSession == null || !isAttachedToWindow() || mMediaViewCreated) {
            return;
        }
        mMediaViewFrame = getViewFrameOnScreen();
        mSession.createMediaView(this, mMediaViewFrame);
        mMediaViewCreated = true;
    }

    private void removeSessionMediaView() {
        if (mSession == null || !mMediaViewCreated) {
            return;
        }
        mSession.removeMediaView();
        mMediaViewCreated = false;
        mMediaViewFrame = null;
    }

    private void relayoutSessionMediaView() {
        if (mSession == null || !isAttachedToWindow() || !mMediaViewCreated) {
            return;
        }
        Rect viewFrame = getViewFrameOnScreen();
        if (viewFrame.equals(mMediaViewFrame)) {
            return;
        }
        mSession.relayoutMediaView(viewFrame);
        mMediaViewFrame = viewFrame;
    }

    private Rect getViewFrameOnScreen() {
        Rect frame = new Rect();
        getGlobalVisibleRect(frame);
        RectF frameF = new RectF(frame);
        getMatrix().mapRect(frameF);
        frameF.round(frame);
        return frame;
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

    private final FinishedInputEventCallback mFinishedInputEventCallback =
            new FinishedInputEventCallback() {
                @Override
                public void onFinishedInputEvent(Object token, boolean handled) {
                    if (DEBUG) {
                        Log.d(TAG, "onFinishedInputEvent(token=" + token + ", handled="
                                + handled + ")");
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

    /**
     * Dispatches an unhandled input event to the next receiver.
     *
     * It gives the host application a chance to dispatch the unhandled input events.
     *
     * @param event The input event.
     * @return {@code true} if the event was handled by the view, {@code false} otherwise.
     */
    public boolean dispatchUnhandledInputEvent(@NonNull InputEvent event) {
        if (mOnUnhandledInputEventListener != null) {
            if (mOnUnhandledInputEventListener.onUnhandledInputEvent(event)) {
                return true;
            }
        }
        return onUnhandledInputEvent(event);
    }

    /**
     * Called when an unhandled input event also has not been handled by the user provided
     * callback. This is the last chance to handle the unhandled input event in the
     * TvInteractiveAppView.
     *
     * @param event The input event.
     * @return If you handled the event, return {@code true}. If you want to allow the event to be
     *         handled by the next receiver, return {@code false}.
     */
    public boolean onUnhandledInputEvent(@NonNull InputEvent event) {
        return false;
    }

    /**
     * Sets a listener to be invoked when an input event is not handled
     * by the TV Interactive App.
     *
     * @param listener The callback to be invoked when the unhandled input event is received.
     */
    public void setOnUnhandledInputEventListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnUnhandledInputEventListener listener) {
        mOnUnhandledInputEventListener = listener;
        // TODO: handle CallbackExecutor
    }

    /**
     * Gets the {@link OnUnhandledInputEventListener}.
     * <p>Returns {@code null} if the listener is not set or is cleared.
     *
     * @see #setOnUnhandledInputEventListener(Executor, OnUnhandledInputEventListener)
     * @see #clearOnUnhandledInputEventListener()
     */
    @Nullable
    public OnUnhandledInputEventListener getOnUnhandledInputEventListener() {
        return mOnUnhandledInputEventListener;
    }

    /**
     * Clears the {@link OnUnhandledInputEventListener}.
     */
    public void clearOnUnhandledInputEventListener() {
        mOnUnhandledInputEventListener = null;
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        if (super.dispatchKeyEvent(event)) {
            return true;
        }
        if (mSession == null) {
            return false;
        }
        InputEvent copiedEvent = event.copy();
        int ret = mSession.dispatchInputEvent(copiedEvent, copiedEvent, mFinishedInputEventCallback,
                mHandler);
        return ret != Session.DISPATCH_NOT_HANDLED;
    }

    /**
     * Prepares the interactive application runtime environment of corresponding
     * {@link TvInteractiveAppService}.
     *
     * @param iAppServiceId the interactive app service ID, which can be found in
     *                      {@link TvInteractiveAppServiceInfo#getId()}.
     *
     * @see android.media.tv.interactive.TvInteractiveAppManager#getTvInteractiveAppServiceList()
     */
    public void prepareInteractiveApp(
            @NonNull String iAppServiceId,
            @TvInteractiveAppServiceInfo.InteractiveAppType int type) {
        // TODO: document and handle the cases that this method is called multiple times.
        if (DEBUG) {
            Log.d(TAG, "prepareInteractiveApp");
        }
        mSessionCallback = new MySessionCallback(iAppServiceId, type);
        if (mTvInteractiveAppManager != null) {
            mTvInteractiveAppManager.createSession(iAppServiceId, type, mSessionCallback, mHandler);
        }
    }

    /**
     * Starts the interactive application.
     */
    public void startInteractiveApp() {
        if (DEBUG) {
            Log.d(TAG, "startInteractiveApp");
        }
        if (mSession != null) {
            mSession.startInteractiveApp();
        }
    }

    /**
     * Stops the interactive application.
     */
    public void stopInteractiveApp() {
        if (DEBUG) {
            Log.d(TAG, "stopInteractiveApp");
        }
        if (mSession != null) {
            mSession.stopInteractiveApp();
        }
    }

    /**
     * Resets the interactive application.
     *
     * <p>This releases the resources of the corresponding {@link TvInteractiveAppService.Session}.
     */
    public void resetInteractiveApp() {
        if (DEBUG) {
            Log.d(TAG, "resetInteractiveApp");
        }
        if (mSession != null) {
            mSession.resetInteractiveApp();
        }
    }

    /**
     * Sends current video bounds to related TV interactive app.
     *
     * @param bounds the rectangle area for rendering the current video.
     */
    public void sendCurrentVideoBounds(@NonNull Rect bounds) {
        if (DEBUG) {
            Log.d(TAG, "sendCurrentVideoBounds");
        }
        if (mSession != null) {
            mSession.sendCurrentVideoBounds(bounds);
        }
    }

    /**
     * Sends current channel URI to related TV interactive app.
     *
     * @param channelUri The current channel URI; {@code null} if there is no currently tuned
     *                   channel.
     */
    public void sendCurrentChannelUri(@Nullable Uri channelUri) {
        if (DEBUG) {
            Log.d(TAG, "sendCurrentChannelUri");
        }
        if (mSession != null) {
            mSession.sendCurrentChannelUri(channelUri);
        }
    }

    /**
     * Sends current channel logical channel number (LCN) to related TV interactive app.
     */
    public void sendCurrentChannelLcn(int lcn) {
        if (DEBUG) {
            Log.d(TAG, "sendCurrentChannelLcn");
        }
        if (mSession != null) {
            mSession.sendCurrentChannelLcn(lcn);
        }
    }

    /**
     * Sends stream volume to related TV interactive app.
     *
     * @param volume a volume value between {@code 0.0f} and {@code 1.0f}, inclusive.
     */
    public void sendStreamVolume(float volume) {
        if (DEBUG) {
            Log.d(TAG, "sendStreamVolume");
        }
        if (mSession != null) {
            mSession.sendStreamVolume(volume);
        }
    }

    /**
     * Sends track info list to related TV interactive app.
     */
    public void sendTrackInfoList(@Nullable List<TvTrackInfo> tracks) {
        if (DEBUG) {
            Log.d(TAG, "sendTrackInfoList");
        }
        if (mSession != null) {
            mSession.sendTrackInfoList(tracks);
        }
    }

    /**
     * Sends the currently selected track info to the TV Interactive App.
     *
     * @param tracks list of {@link TvTrackInfo} of the currently selected track(s)
     */
    @FlaggedApi(Flags.FLAG_TIAF_V_APIS)
    public void sendSelectedTrackInfo(@Nullable List<TvTrackInfo> tracks) {
        if (DEBUG) {
            Log.d(TAG, "sendSelectedTrackInfo");
        }
        if (mSession != null) {
            mSession.sendSelectedTrackInfo(tracks);
        }
    }

    /**
     * Sends current TV input ID to related TV interactive app.
     *
     * @param inputId The current TV input ID whose channel is tuned. {@code null} if no channel is
     *                tuned.
     * @see android.media.tv.TvInputInfo
     */
    public void sendCurrentTvInputId(@Nullable String inputId) {
        if (DEBUG) {
            Log.d(TAG, "sendCurrentTvInputId");
        }
        if (mSession != null) {
            mSession.sendCurrentTvInputId(inputId);
        }
    }

    /**
     * Sends the current time shift mode to the TV interactive app bound to this view
     *
     * @param mode The current time shift mode. The value is one of the following:
     * {@link TvInputManager#TIME_SHIFT_MODE_OFF}, {@link TvInputManager#TIME_SHIFT_MODE_LOCAL},
     * {@link TvInputManager#TIME_SHIFT_MODE_NETWORK},
     * {@link TvInputManager#TIME_SHIFT_MODE_AUTO}.
     */
    public void sendTimeShiftMode(@android.media.tv.TvInputManager.TimeShiftMode int mode) {
        if (DEBUG) {
            Log.d(TAG, "sendTimeShiftMode");
        }
        if (mSession != null) {
            mSession.sendTimeShiftMode(mode);
        }
    }

    /**
     * Sends the available supported playback speeds to the TV interactive app bound to this view.
     *
     * @param speeds An ordered array of playback speeds, expressed as values relative to the
     *               normal playback speed (1.0), at which the current content can be played as
     *               a time-shifted broadcast. This is an empty array if the supported playback
     *               speeds are unknown or the video/broadcast is not in time shift mode. If
     *               currently in time shift mode, this array will normally include at least
     *               the values 1.0 (normal speed) and 0.0 (paused).
     * @see PlaybackParams#getSpeed()
     */
    public void sendAvailableSpeeds(@NonNull float[] speeds) {
        if (DEBUG) {
            Log.d(TAG, "sendAvailableSpeeds");
        }
        if (mSession != null) {
            Arrays.sort(speeds);
            mSession.sendAvailableSpeeds(speeds);
        }
    }

    /**
     * Sends the requested {@link android.media.tv.TvRecordingInfo}.
     *
     * @see TvInteractiveAppService.Session#requestTvRecordingInfo(String)
     * @param recordingInfo The recording info requested. {@code null} if no recording found.
     */
    public void sendTvRecordingInfo(@Nullable TvRecordingInfo recordingInfo) {
        if (DEBUG) {
            Log.d(TAG, "sendTvRecordingInfo");
        }
        if (mSession != null) {
            mSession.sendTvRecordingInfo(recordingInfo);
        }
    }

    /**
     * Sends the requested {@link android.media.tv.TvRecordingInfo}.
     *
     * @see TvInteractiveAppService.Session#requestTvRecordingInfoList(int)
     * @param recordingInfoList The list of recording info requested. Returns an empty list if no
     *                          matching recording info found.
     */
    public void sendTvRecordingInfoList(@NonNull List<TvRecordingInfo> recordingInfoList) {
        if (DEBUG) {
            Log.d(TAG, "sendTvRecordingInfoList");
        }
        if (mSession != null) {
            mSession.sendTvRecordingInfoList(recordingInfoList);
        }
    }

    /**
     * Alerts the related TV interactive app service that a recording has been started.
     *
     * @param recordingId The ID of the recording started. This ID is created and maintained by the
     *                    TV app and is used to identify the recording in the future.
     *
     * @param requestId The ID of the request when
     *                  {@link TvInteractiveAppService.Session#requestStartRecording(String, Uri)}
     *                  is called. {@code null} if the recording is not triggered by a request.
     *                  This ID should be created by the {@link TvInteractiveAppService} and
     *                  can be any string.
     * @see TvInteractiveAppView#notifyRecordingStopped(String)
     */
    public void notifyRecordingStarted(@NonNull String recordingId, @Nullable String requestId) {
        if (DEBUG) {
            Log.d(TAG, "notifyRecordingStarted");
        }
        if (mSession != null) {
            mSession.notifyRecordingStarted(recordingId, requestId);
        }
    }

    /**
     * Alerts the TV interactive app that a recording has been stopped.
     *
     * @param recordingId The ID of the recording stopped. This ID is created and maintained
     *                    by the TV app when a recording is started.
     * @see TvInteractiveAppView#notifyRecordingStarted(String, String)
     */
    public void notifyRecordingStopped(@NonNull String recordingId) {
        if (DEBUG) {
            Log.d(TAG, "notifyRecordingStopped");
        }
        if (mSession != null) {
            mSession.notifyRecordingStopped(recordingId);
        }
    }

    /**
     * Alerts the TV Interactive app that the video freeze state has been updated. If {@code true},
     * the video is frozen on the last frame while audio playback continues.
     *
     * @param isFrozen Whether the video is frozen.
     */
    @FlaggedApi(Flags.FLAG_TIAF_V_APIS)
    public void notifyVideoFreezeUpdated(boolean isFrozen) {
        if (DEBUG) {
            Log.d(TAG, "notifyVideoFreezeUpdated");
        }
        if (mSession != null) {
            mSession.notifyVideoFreezeUpdated(isFrozen);
        }
    }

    /**
     * Sends signing result to related TV interactive app.
     *
     * <p>This is used when the corresponding server of the broadcast-independent interactive
     * app requires signing during handshaking, and the interactive app service doesn't have
     * the built-in private key. The private key is provided by the content providers and
     * pre-built in the related app, such as TV app.
     *
     * @param signingId the ID to identify the request. It's the same as the corresponding ID in
     *        {@link TvInteractiveAppService.Session#requestSigning(String, String, String, byte[])}
     * @param result the signed result.
     */
    public void sendSigningResult(@NonNull String signingId, @NonNull byte[] result) {
        if (DEBUG) {
            Log.d(TAG, "sendSigningResult");
        }
        if (mSession != null) {
            mSession.sendSigningResult(signingId, result);
        }
    }

    /**
     * Send the requested SSL certificate to the TV Interactive App
     * @param host the host name of the SSL authentication server.
     * @param port the port of the SSL authentication server. E.g., 443
     * @param cert the SSL certificate requested
     * @hide
     */
    public void sendCertificate(@NonNull String host, int port, @NonNull SslCertificate cert) {
        if (DEBUG) {
            Log.d(TAG, "sendCertificate");
        }
        if (mSession != null) {
            mSession.sendCertificate(host, port, cert);
        }
    }

    /**
     * Notifies the corresponding {@link TvInteractiveAppService} when there is an error.
     *
     * @param errMsg the message of the error.
     * @param params additional parameters of the error. For example, the signingId of {@link
     *     TvInteractiveAppCallback#onRequestSigning(String, String, String, String, byte[])} can be
     *     included to identify the related signing request, and the method name "onRequestSigning"
     *     can also be added to the params.
     *
     * @see #ERROR_KEY_METHOD_NAME
     */
    public void notifyError(@NonNull String errMsg, @NonNull Bundle params) {
        if (DEBUG) {
            Log.d(TAG, "notifyError msg=" + errMsg + "; params=" + params);
        }
        if (mSession != null) {
            mSession.notifyError(errMsg, params);
        }
    }

    /**
     * Notifies the corresponding {@link TvInteractiveAppService} when a time shift
     * {@link android.media.PlaybackParams} is set or changed.
     *
     * @see TvView#timeShiftSetPlaybackParams(PlaybackParams)
     * @param params The new {@link PlaybackParams} that was set or changed.
     */
    public void notifyTimeShiftPlaybackParams(@NonNull PlaybackParams params) {
        if (DEBUG) {
            Log.d(TAG, "notifyTimeShiftPlaybackParams params=" + params);
        }
        if (mSession != null) {
            mSession.notifyTimeShiftPlaybackParams(params);
        }
    }

    /**
     * Notifies the corresponding {@link TvInteractiveAppService} when time shift
     * status is changed.
     *
     * @see TvView.TvInputCallback#onTimeShiftStatusChanged(String, int)
     * @see android.media.tv.TvInputService.Session#notifyTimeShiftStatusChanged(int)
     * @param inputId The ID of the input for which the time shift status has changed.
     * @param status The status of which the input has changed to. Should be one of the
     *               following.
     *               <ul>
     *                  <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNKNOWN}
     *                  <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNSUPPORTED}
     *                  <li>{@link TvInputManager#TIME_SHIFT_STATUS_UNAVAILABLE}
     *                  <li>{@link TvInputManager#TIME_SHIFT_STATUS_AVAILABLE}
     *               </ul>
     */
    public void notifyTimeShiftStatusChanged(
            @NonNull String inputId, @TvInputManager.TimeShiftStatus int status) {
        if (DEBUG) {
            Log.d(TAG,
                    "notifyTimeShiftStatusChanged inputId=" + inputId + "; status=" + status);
        }
        if (mSession != null) {
            mSession.notifyTimeShiftStatusChanged(inputId, status);
        }
    }

    /**
     * Notifies the corresponding {@link TvInteractiveAppService} when time shift
     * start position is changed.
     *
     * @see TvView.TimeShiftPositionCallback#onTimeShiftStartPositionChanged(String, long)
     * @param inputId The ID of the input for which the time shift start position has changed.
     * @param timeMs The start position for time shifting, in milliseconds since the epoch.
     */
    public void notifyTimeShiftStartPositionChanged(@NonNull String inputId, long timeMs) {
        if (DEBUG) {
            Log.d(TAG, "notifyTimeShiftStartPositionChanged inputId=" + inputId
                    + "; timeMs=" + timeMs);
        }
        if (mSession != null) {
            mSession.notifyTimeShiftStartPositionChanged(inputId, timeMs);
        }
    }

    /**
     * Notifies the corresponding {@link TvInteractiveAppService} when time shift
     * current position is changed.
     *
     * @see TvView.TimeShiftPositionCallback#onTimeShiftCurrentPositionChanged(String, long)
     * @param inputId The ID of the input for which the time shift current position has changed.
     * @param timeMs The current position for time shifting, in milliseconds since the epoch.
     */
    public void notifyTimeShiftCurrentPositionChanged(@NonNull String inputId, long timeMs) {
        if (DEBUG) {
            Log.d(TAG, "notifyTimeShiftCurrentPositionChanged inputId=" + inputId
                    + "; timeMs=" + timeMs);
        }
        if (mSession != null) {
            mSession.notifyTimeShiftCurrentPositionChanged(inputId, timeMs);
        }
    }

    /**
     * This is called to notify the corresponding interactive app service when an error occurred
     * while establishing a connection to the recording session for the corresponding TV input.
     *
     * @param recordingId The ID of the related recording which is sent via
     *                    {@link #notifyRecordingStarted(String, String)}
     * @param inputId The ID of the TV input bound to the current TvRecordingClient.
     * @see android.media.tv.TvRecordingClient.RecordingCallback#onConnectionFailed(String)
     * @hide
     */
    public void notifyRecordingConnectionFailed(
            @NonNull String recordingId, @NonNull String inputId) {
        if (DEBUG) {
            Log.d(TAG, "notifyRecordingConnectionFailed recordingId=" + recordingId
                    + "; inputId=" + inputId);
        }
        if (mSession != null) {
            mSession.notifyRecordingConnectionFailed(recordingId, inputId);
        }
    }

    /**
     * This is called to notify the corresponding interactive app service when the connection to
     * the current recording session is lost.
     *
     * @param recordingId The ID of the related recording which is sent via
     *                    {@link #notifyRecordingStarted(String, String)}
     * @param inputId The ID of the TV input bound to the current TvRecordingClient.
     * @see android.media.tv.TvRecordingClient.RecordingCallback#onDisconnected(String)
     * @hide
     */
    public void notifyRecordingDisconnected(
            @NonNull String recordingId, @NonNull String inputId) {
        if (DEBUG) {
            Log.d(TAG, "notifyRecordingDisconnected recordingId=" + recordingId
                    + "; inputId=" + inputId);
        }
        if (mSession != null) {
            mSession.notifyRecordingDisconnected(recordingId, inputId);
        }
    }

    /**
     * This is called to notify the corresponding interactive app service when the recording session
     * has been tuned to the given channel and is ready to start recording.
     *
     * @param recordingId The ID of the related recording which is sent via
     *                    {@link #notifyRecordingStarted(String, String)}
     * @param channelUri The URI of the tuned channel.
     * @see android.media.tv.TvRecordingClient.RecordingCallback#onTuned(Uri)
     * @hide
     */
    public void notifyRecordingTuned(
            @NonNull String recordingId, @NonNull Uri channelUri) {
        if (DEBUG) {
            Log.d(TAG, "notifyRecordingTuned recordingId=" + recordingId
                    + "; channelUri=" + channelUri);
        }
        if (mSession != null) {
            mSession.notifyRecordingTuned(recordingId, channelUri);
        }
    }

    /**
     * This is called to notify the corresponding interactive app service when an issue has
     * occurred. It may be called at any time after the current recording session is created until
     * it is released.
     *
     * @param recordingId The ID of the related recording which is sent via
     *                    {@link #notifyRecordingStarted(String, String)}
     * @param err The error code. Should be one of the following.
     * <ul>
     * <li>{@link TvInputManager#RECORDING_ERROR_UNKNOWN}
     * <li>{@link TvInputManager#RECORDING_ERROR_INSUFFICIENT_SPACE}
     * <li>{@link TvInputManager#RECORDING_ERROR_RESOURCE_BUSY}
     * </ul>
     * @see android.media.tv.TvRecordingClient.RecordingCallback#onError(int)
     * @hide
     */
    public void notifyRecordingError(
            @NonNull String recordingId, @TvInputManager.RecordingError int err) {
        if (DEBUG) {
            Log.d(TAG, "notifyRecordingError recordingId=" + recordingId
                    + "; err=" + err);
        }
        if (mSession != null) {
            mSession.notifyRecordingError(recordingId, err);
        }
    }

    /**
     * This is called to notify the corresponding interactive app service when the recording has
     * been scheduled.
     *
     * @param recordingId The ID assigned to this recording by the app. It can be used to send
     *                    recording related requests such as
     *                    {@link TvInteractiveAppService.Session#requestStopRecording(String)}.
     * @param requestId The ID of the request when
     *                  {@link TvInteractiveAppService.Session#requestScheduleRecording} is called.
     *                  {@code null} if the recording is not triggered by a request.
     *                  This ID should be created by the {@link TvInteractiveAppService} and
     *                  can be any string.
     */
    public void notifyRecordingScheduled(
            @NonNull String recordingId, @Nullable String requestId) {
        if (DEBUG) {
            Log.d(TAG, "notifyRecordingScheduled recordingId=" + recordingId
                    + "; requestId=" + requestId);
        }
        if (mSession != null) {
            mSession.notifyRecordingScheduled(recordingId, requestId);
        }
    }

    /**
     * This is called to notify the corresponding interactive app service when a new TV message
     * is received.
     *
     * @param type The type of message received, such as
     * {@link TvInputManager#TV_MESSAGE_TYPE_WATERMARK}
     * @param data The raw data of the message. The bundle keys are:
     *             {@link TvInputManager#TV_MESSAGE_KEY_STREAM_ID},
     *             {@link TvInputManager#TV_MESSAGE_KEY_GROUP_ID},
     *             {@link TvInputManager#TV_MESSAGE_KEY_SUBTYPE},
     *             {@link TvInputManager#TV_MESSAGE_KEY_RAW_DATA}.
     *             See {@link TvInputManager#TV_MESSAGE_KEY_SUBTYPE} for more information on
     *             how to parse this data.
     */
    public void notifyTvMessage(@NonNull @TvInputManager.TvMessageType int type,
            @NonNull Bundle data) {
        if (DEBUG) {
            Log.d(TAG, "notifyTvMessage type=" + type
                    + "; data=" + data);
        }
        if (mSession != null) {
            mSession.notifyTvMessage(type, data);
        }
    }

    private void resetInternal() {
        mSessionCallback = null;
        if (mSession != null) {
            setSessionSurface(null);
            removeSessionMediaView();
            mUseRequestedSurfaceLayout = false;
            mSession.release();
            mSession = null;
            resetSurfaceView();
        }
    }

    /**
     * Creates broadcast-independent(BI) interactive application.
     *
     * <p>{@link TvInteractiveAppCallback#onBiInteractiveAppCreated(String, Uri, String)} will be
     * called for the result.
     *
     * @param biIAppUri URI associated this BI interactive app.
     * @param params optional parameters for broadcast-independent interactive application, such as
     *               {@link #BI_INTERACTIVE_APP_KEY_CERTIFICATE}.
     *
     * @see TvInteractiveAppCallback#onBiInteractiveAppCreated(String, Uri, String)
     * @see #BI_INTERACTIVE_APP_KEY_CERTIFICATE
     * @see #BI_INTERACTIVE_APP_KEY_HTTP_ADDITIONAL_HEADERS
     * @see #BI_INTERACTIVE_APP_KEY_HTTP_USER_AGENT
     */
    public void createBiInteractiveApp(@NonNull Uri biIAppUri, @Nullable Bundle params) {
        if (DEBUG) {
            Log.d(TAG, "createBiInteractiveApp Uri=" + biIAppUri + ", params=" + params);
        }
        if (mSession != null) {
            mSession.createBiInteractiveApp(biIAppUri, params);
        }
    }

    /**
     * Destroys broadcast-independent(BI) interactive application.
     *
     * @param biIAppId the BI interactive app ID from {@link #createBiInteractiveApp(Uri, Bundle)}
     *
     * @see #createBiInteractiveApp(Uri, Bundle)
     */
    public void destroyBiInteractiveApp(@NonNull String biIAppId) {
        if (DEBUG) {
            Log.d(TAG, "destroyBiInteractiveApp biIAppId=" + biIAppId);
        }
        if (mSession != null) {
            mSession.destroyBiInteractiveApp(biIAppId);
        }
    }

    /** @hide */
    public Session getInteractiveAppSession() {
        return mSession;
    }

    /**
     * Sets the TvInteractiveAppView to receive events from TIS. This method links the session of
     * TvInteractiveAppManager to TvInputManager session, so the TIAS can get the TIS events.
     *
     * @param tvView the TvView to be linked to this TvInteractiveAppView via linking of Sessions.
     * @return The result of the operation.
     */
    public int setTvView(@Nullable TvView tvView) {
        if (tvView == null) {
            return unsetTvView();
        }
        TvInputManager.Session inputSession = tvView.getInputSession();
        if (inputSession == null || mSession == null) {
            return SET_TVVIEW_FAIL;
        }
        mSession.setInputSession(inputSession);
        inputSession.setInteractiveAppSession(mSession);
        return SET_TVVIEW_SUCCESS;
    }

    private int unsetTvView() {
        if (mSession == null || mSession.getInputSession() == null) {
            return UNSET_TVVIEW_FAIL;
        }
        mSession.getInputSession().setInteractiveAppSession(null);
        mSession.setInputSession(null);
        return UNSET_TVVIEW_SUCCESS;
    }

    /**
     * To toggle Digital Teletext Application if there is one in AIT app list.
     *
     * <p>A Teletext Application is a broadcast-related application to display text and basic
     * graphics.
     *
     * @param enable {@code true} to enable Teletext app; {@code false} to disable it.
     */
    public void setTeletextAppEnabled(boolean enable) {
        if (DEBUG) {
            Log.d(TAG, "setTeletextAppEnabled enable=" + enable);
        }
        if (mSession != null) {
            mSession.setTeletextAppEnabled(enable);
        }
    }

    /**
     * Callback used to receive various status updates on the {@link TvInteractiveAppView}.
     */
    public abstract static class TvInteractiveAppCallback {
        // TODO: unhide the following public APIs

        /**
         * This is called when a playback command is requested to be processed by the related TV
         * input.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param cmdType type of the command
         * @param parameters parameters of the command
         */
        public void onPlaybackCommandRequest(
                @NonNull String iAppServiceId,
                @NonNull @TvInteractiveAppService.PlaybackCommandType String cmdType,
                @NonNull Bundle parameters) {
        }

        /**
         * This is called when a time shift command is requested to be processed by the related TV
         * input.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param cmdType type of the command
         * @param parameters parameters of the command
         */
        public void onTimeShiftCommandRequest(
                @NonNull String iAppServiceId,
                @NonNull @TvInteractiveAppService.TimeShiftCommandType String cmdType,
                @NonNull Bundle parameters) {
        }

        /**
         * This is called when the state of corresponding interactive app is changed.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param state the current state.
         * @param err the error code for error state. {@link TvInteractiveAppManager#ERROR_NONE}
         *              is used when the state is not
         *              {@link TvInteractiveAppManager#INTERACTIVE_APP_STATE_ERROR}.
         */
        public void onStateChanged(
                @NonNull String iAppServiceId,
                @TvInteractiveAppManager.InteractiveAppState int state,
                @TvInteractiveAppManager.ErrorCode int err) {
        }

        /**
         * This is called when broadcast-independent (BI) interactive app is created.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param biIAppUri URI associated this BI interactive app. This is the same URI in
         *                  {@link #createBiInteractiveApp(Uri, Bundle)}
         * @param biIAppId BI interactive app ID, which can be used to destroy the BI interactive
         *                 app. {@code null} if it's not created successfully.
         *
         * @see #createBiInteractiveApp(Uri, Bundle)
         * @see #destroyBiInteractiveApp(String)
         */
        public void onBiInteractiveAppCreated(@NonNull String iAppServiceId, @NonNull Uri biIAppUri,
                @Nullable String biIAppId) {
        }

        /**
         * This is called when the digital teletext app state is changed.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param state digital teletext app current state.
         */
        public void onTeletextAppStateChanged(
                @NonNull String iAppServiceId,
                @TvInteractiveAppManager.TeletextAppState int state) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#setVideoBounds(Rect)} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onSetVideoBounds(@NonNull String iAppServiceId, @NonNull Rect rect) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCurrentVideoBounds()}
         * is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestCurrentVideoBounds(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCurrentChannelUri()} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestCurrentChannelUri(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCurrentChannelLcn()} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestCurrentChannelLcn(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestStreamVolume()} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestStreamVolume(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestTrackInfoList()} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestTrackInfoList(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestSelectedTrackInfo()} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        @FlaggedApi(Flags.FLAG_TIAF_V_APIS)
        public void onRequestSelectedTrackInfo(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestCurrentTvInputId()} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestCurrentTvInputId(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestTimeShiftMode()} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestTimeShiftMode(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestAvailableSpeeds()} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestAvailableSpeeds(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestStartRecording(String, Uri)} is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param requestId The ID of this request which is used to match the corresponding
         *                  response. The request ID in
         *                  {@link #notifyRecordingStarted(String, String)}  for this request is the
         *                  same as the ID sent here. This should be defined by the
         *                  TIAS and can be any string. Should this API be called with the
         *                  same requestId twice, both requests should be handled regardless
         *                  by the TV application.
         * @param programUri The URI of the program to record
         *
         */
        public void onRequestStartRecording(@NonNull String iAppServiceId,
                @NonNull String requestId, @Nullable Uri programUri) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#requestStopRecording(String)}
         * is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param recordingId The ID of the recording to stop. This is provided by the TV app in
         *                    {@link #notifyRecordingStarted(String, String)}
         * @see #notifyRecordingStarted(String, String)
         * @see #notifyRecordingStopped(String)
         */
        public void onRequestStopRecording(
                @NonNull String iAppServiceId,
                @NonNull String recordingId) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestScheduleRecording(String, String, Uri, Uri, Bundle)}
         * is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param requestId The ID of this request which is used to match the corresponding
         *                  response. The request ID in
         *                  {@link #notifyRecordingScheduled(String, String)} for this request is
         *                  the same as the ID sent here. This should be defined by the
         *                  TIAS and can be any string. Should this API be called with the
         *                  same requestId twice, both requests should be handled regardless
         *                  by the TV application.
         * @param inputId The ID of the TV input for the given channel.
         * @param channelUri The URI of a channel to be recorded.
         * @param programUri The URI of the TV program to be recorded.
         * @param params Domain-specific data for this tune request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         * @see android.media.tv.TvRecordingClient#tune(String, Uri, Bundle)
         * @see android.media.tv.TvRecordingClient#startRecording(Uri)
         */
        public void onRequestScheduleRecording(@NonNull String iAppServiceId,
                @NonNull String requestId, @NonNull String inputId, @NonNull Uri channelUri,
                @NonNull Uri programUri, @NonNull Bundle params) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestScheduleRecording(String, String, Uri, long, long, int, Bundle)}
         * is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param requestId The ID of this request which is used to match the corresponding
         *                  response. The request ID in
         *                  {@link #notifyRecordingScheduled(String, String)} for this request is
         *                  the same as the ID sent here. This should be defined by the
         *                  TIAS and can be any string. Should this API be called with the
         *                  same requestId twice, both requests should be handled regardless
         *                  by the TV application.
         * @param inputId The ID of the TV input for the given channel.
         * @param channelUri The URI of a channel to be recorded.
         * @param startTime The start time of the recording in milliseconds since epoch.
         * @param duration The duration of the recording in milliseconds.
         * @param repeatDays The repeated days. 0 if not repeated.
         * @param params Domain-specific data for this tune request. Keys <em>must</em> be a scoped
         *            name, i.e. prefixed with a package name you own, so that different developers
         *            will not create conflicting keys.
         * @see android.media.tv.TvRecordingClient#tune(String, Uri, Bundle)
         * @see android.media.tv.TvRecordingClient#startRecording(Uri)
         */
        public void onRequestScheduleRecording(@NonNull String iAppServiceId,
                @NonNull String requestId, @NonNull String inputId, @NonNull Uri channelUri,
                long startTime, long duration, int repeatDays, @NonNull Bundle params) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestSigning(String, String, String, byte[])} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param signingId the ID to identify the request.
         * @param algorithm the standard name of the signature algorithm requested, such as
         *                  MD5withRSA, SHA256withDSA, etc.
         * @param alias the alias of the corresponding {@link java.security.KeyStore}.
         * @param data the original bytes to be signed.
         */
        public void onRequestSigning(@NonNull String iAppServiceId, @NonNull String signingId,
                @NonNull String algorithm, @NonNull String alias, @NonNull byte[] data) {
        }

        /**
         * This is called when {@link TvInteractiveAppService.Session#setTvRecordingInfo(String,
         * TvRecordingInfo)} is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param recordingId The ID of the recording to set the info for. This is provided by the
         *     TV app in {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         * @param recordingInfo The {@link TvRecordingInfo} to set to the recording.
         */
        public void onSetTvRecordingInfo(
                @NonNull String iAppServiceId,
                @NonNull String recordingId,
                @NonNull TvRecordingInfo recordingInfo) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestTvRecordingInfo(String)} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param recordingId The ID of the recording to get the info for. This is provided by the
         *                    TV app in
         *                    {@link TvInteractiveAppView#notifyRecordingStarted(String, String)}
         */
        public void onRequestTvRecordingInfo(
                @NonNull String iAppServiceId,
                @NonNull String recordingId) {
        }

        /**
         * This is called when
         * {@link TvInteractiveAppService.Session#requestTvRecordingInfoList(int)} is
         * called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param type The type of recording requested to retrieve.
         */
        public void onRequestTvRecordingInfoList(
                @NonNull String iAppServiceId,
                @TvRecordingInfo.TvRecordingListType int type) {
        }
    }

    /**
     * Interface definition for a callback to be invoked when the unhandled input event is received.
     */
    public interface OnUnhandledInputEventListener {
        /**
         * Called when an input event was not handled by the TV Interactive App.
         *
         * <p>This is called asynchronously from where the event is dispatched. It gives the host
         * application a chance to handle the unhandled input events.
         *
         * @param event The input event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        boolean onUnhandledInputEvent(@NonNull InputEvent event);
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
                // mSurface may not be ready yet as soon as starting an application.
                // In the case, we don't send Session.setSurface(null) unnecessarily.
                // setSessionSurface will be called in surfaceCreated.
                if (mSurface != null) {
                    setSessionSurface(mSurface);
                    if (mSurfaceChanged) {
                        dispatchSurfaceChanged(mSurfaceFormat, mSurfaceWidth, mSurfaceHeight);
                    }
                }
                createSessionMediaView();
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
            mMediaViewCreated = false;
            mMediaViewFrame = null;
            mSessionCallback = null;
            mSession = null;
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
        public void onCommandRequest(
                Session session,
                @TvInteractiveAppService.PlaybackCommandType String cmdType,
                Bundle parameters) {
            if (DEBUG) {
                Log.d(TAG, "onCommandRequest (cmdType=" + cmdType + ", parameters="
                        + parameters.toString() + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onCommandRequest - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onPlaybackCommandRequest(
                                        mIAppServiceId, cmdType, parameters);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onTimeShiftCommandRequest(
                Session session,
                @TvInteractiveAppService.TimeShiftCommandType String cmdType,
                Bundle parameters) {
            if (DEBUG) {
                Log.d(TAG, "onTimeShiftCommandRequest (cmdType=" + cmdType + ", parameters="
                        + parameters.toString() + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onTimeShiftCommandRequest - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onTimeShiftCommandRequest(
                                        mIAppServiceId, cmdType, parameters);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onSessionStateChanged(
                Session session,
                @TvInteractiveAppManager.InteractiveAppState int state,
                @TvInteractiveAppManager.ErrorCode int err) {
            if (DEBUG) {
                Log.d(TAG, "onSessionStateChanged (state=" + state + "; err=" + err + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSessionStateChanged - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onStateChanged(mIAppServiceId, state, err);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onBiInteractiveAppCreated(Session session, Uri biIAppUri, String biIAppId) {
            if (DEBUG) {
                Log.d(TAG, "onBiInteractiveAppCreated (biIAppUri=" + biIAppUri + ", biIAppId="
                        + biIAppId + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onBiInteractiveAppCreated - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onBiInteractiveAppCreated(
                                        mIAppServiceId, biIAppUri, biIAppId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onTeletextAppStateChanged(Session session, int state) {
            if (DEBUG) {
                Log.d(TAG, "onTeletextAppStateChanged (state=" + state +  ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onTeletextAppStateChanged - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onTeletextAppStateChanged(mIAppServiceId, state);
            }
        }

        @Override
        public void onSetVideoBounds(Session session, Rect rect) {
            if (DEBUG) {
                Log.d(TAG, "onSetVideoBounds (rect=" + rect + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSetVideoBounds - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onSetVideoBounds(mIAppServiceId, rect);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestCurrentVideoBounds(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestCurrentVideoBounds");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestCurrentVideoBounds - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onRequestCurrentVideoBounds(mIAppServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestCurrentChannelUri(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestCurrentChannelUri");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestCurrentChannelUri - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onRequestCurrentChannelUri(mIAppServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestCurrentChannelLcn(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestCurrentChannelLcn");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestCurrentChannelLcn - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onRequestCurrentChannelLcn(mIAppServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestStreamVolume(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestStreamVolume");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestStreamVolume - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onRequestStreamVolume(mIAppServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestTrackInfoList(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestTrackInfoList");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestTrackInfoList - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onRequestTrackInfoList(mIAppServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestSelectedTrackInfo(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestSelectedTrackInfo");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestSelectedTrackInfo - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onRequestSelectedTrackInfo(mIAppServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestCurrentTvInputId(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestCurrentTvInputId");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestCurrentTvInputId - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestCurrentTvInputId(mIAppServiceId);
            }
        }

        @Override
        public void onRequestTimeShiftMode(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestTimeShiftMode");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestTimeShiftMode - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestTimeShiftMode(mIAppServiceId);
            }
        }

        @Override
        public void onRequestAvailableSpeeds(Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestAvailableSpeeds");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestAvailableSpeeds - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestAvailableSpeeds(mIAppServiceId);
            }
        }

        @Override
        public void onRequestStartRecording(Session session, String requestId, Uri programUri) {
            if (DEBUG) {
                Log.d(TAG, "onRequestStartRecording");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestStartRecording - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestStartRecording(mIAppServiceId, requestId, programUri);
            }
        }

        @Override
        public void onRequestStopRecording(Session session, String recordingId) {
            if (DEBUG) {
                Log.d(TAG, "onRequestStopRecording");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestStopRecording - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestStopRecording(mIAppServiceId, recordingId);
            }
        }

        @Override
        public void onSetTvRecordingInfo(
                Session session, String recordingId, TvRecordingInfo recordingInfo) {
            if (DEBUG) {
                Log.d(TAG, "onSetRecordingInfo");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSetRecordingInfo - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onSetTvRecordingInfo(mIAppServiceId, recordingId, recordingInfo);
            }
        }

        @Override
        public void onRequestScheduleRecording(Session session, @NonNull String requestId,
                @NonNull String inputId, @NonNull Uri channelUri, Uri programUri,
                @NonNull Bundle params) {
            if (DEBUG) {
                Log.d(TAG, "onRequestScheduleRecording");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestScheduleRecording - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestScheduleRecording(mIAppServiceId, requestId, inputId, channelUri,
                        programUri, params);
            }
        }

        public void onRequestScheduleRecording(Session session, @NonNull String requestId,
                @NonNull String inputId, @NonNull Uri channelUri, long startTime, long duration,
                int repeatDays, @NonNull Bundle params) {
            if (DEBUG) {
                Log.d(TAG, "onRequestScheduleRecording");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestScheduleRecording - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestScheduleRecording(mIAppServiceId, requestId, inputId, channelUri,
                        startTime, duration, repeatDays, params);
            }
        }

        @Override
        public void onRequestTvRecordingInfo(Session session,
                String recordingId) {
            if (DEBUG) {
                Log.d(TAG, "onRequestRecordingInfo");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestRecordingInfo - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestTvRecordingInfo(mIAppServiceId, recordingId);
            }
        }

        @Override
        public void onRequestTvRecordingInfoList(Session session,
                int type) {
            if (DEBUG) {
                Log.d(TAG, "onRequestRecordingInfoList");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestRecordingInfoList - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestTvRecordingInfoList(mIAppServiceId, type);
            }
        }

        @Override
        public void onRequestSigning(
                Session session, String id, String algorithm, String alias, byte[] data) {
            if (DEBUG) {
                Log.d(TAG, "onRequestSigning");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestSigning - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onRequestSigning(mIAppServiceId, id, algorithm, alias, data);
            }
        }
    }
}
