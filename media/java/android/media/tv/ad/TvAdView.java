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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.tv.TvInputManager;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.media.tv.ad.TvAdManager.Session.FinishedInputEventCallback;
import android.net.Uri;
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

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Displays contents of TV AD services.
 * @hide
 */
public class TvAdView extends ViewGroup {
    private static final String TAG = "TvAdView";
    private static final boolean DEBUG = false;

    /**
     * The name of the method where the error happened, if applicable. For example, if there is an
     * error during signing, the request name is "onRequestSigning".
     * @see #notifyError(String, Bundle)
     * @hide
     */
    public static final String ERROR_KEY_METHOD_NAME = "method_name";

    private final TvAdManager mTvAdManager;

    private final Handler mHandler = new Handler();
    private final Object mCallbackLock = new Object();
    private TvAdManager.Session mSession;
    private MySessionCallback mSessionCallback;
    private TvAdCallback mCallback;
    private Executor mCallbackExecutor;

    private final AttributeSet mAttrs;
    private final int mDefStyleAttr;
    private final XmlResourceParser mParser;

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


    public TvAdView(@NonNull Context context) {
        this(context, null, 0);
    }

    public TvAdView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TvAdView(@NonNull Context context, @Nullable AttributeSet attrs,
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
        mTvAdManager = (TvAdManager) getContext().getSystemService(Context.TV_AD_SERVICE);
    }

    /**
     * Sets the TvAdView to receive events from TvInputService. This method links the session of
     * TvAdManager to TvInputManager session, so the TvAdService can get the TvInputService events.
     *
     * @param tvView the TvView to be linked to this TvAdView via linking of Sessions. {@code null}
     *               to unlink the TvView.
     * @return {@code true} if it's linked successfully; {@code false} otherwise.
     * @hide
     */
    public boolean setTvView(@Nullable TvView tvView) {
        if (tvView == null) {
            return unsetTvView();
        }
        TvInputManager.Session inputSession = tvView.getInputSession();
        if (inputSession == null || mSession == null) {
            return false;
        }
        mSession.setInputSession(inputSession);
        inputSession.setAdSession(mSession);
        return true;
    }

    private boolean unsetTvView() {
        if (mSession == null || mSession.getInputSession() == null) {
            return false;
        }
        mSession.getInputSession().setAdSession(null);
        mSession.setInputSession(null);
        return true;
    }

    /** @hide */
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        createSessionMediaView();
    }

    /** @hide */
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
     * Resets this TvAdView to release its resources.
     *
     * <p>It can be reused by call {@link #prepareAdService(String, String)}.
     * @hide
     */
    public void reset() {
        if (DEBUG) Log.d(TAG, "reset()");
        resetInternal();
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
     * @hide
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
     * TvAdView.
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
     * by the TV AD service.
     *
     * @param listener The callback to be invoked when the unhandled input event is received.
     * @hide
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
     * @hide
     */
    @Nullable
    public OnUnhandledInputEventListener getOnUnhandledInputEventListener() {
        return mOnUnhandledInputEventListener;
    }

    /**
     * Clears the {@link OnUnhandledInputEventListener}.
     * @hide
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
        return ret != TvAdManager.Session.DISPATCH_NOT_HANDLED;
    }

    /**
     * Prepares the AD service of corresponding {@link TvAdService}.
     *
     * @param serviceId the AD service ID, which can be found in TvAdServiceInfo#getId().
     */
    public void prepareAdService(@NonNull String serviceId, @NonNull String type) {
        if (DEBUG) {
            Log.d(TAG, "prepareAdService");
        }
        mSessionCallback = new TvAdView.MySessionCallback(serviceId);
        if (mTvAdManager != null) {
            mTvAdManager.createSession(serviceId, type, mSessionCallback, mHandler);
        }
    }

    /**
     * Starts the AD service.
     * @hide
     */
    public void startAdService() {
        if (DEBUG) {
            Log.d(TAG, "startAdService");
        }
        if (mSession != null) {
            mSession.startAdService();
        }
    }

    /**
     * Stops the AD service.
     */
    public void stopAdService() {
        if (DEBUG) {
            Log.d(TAG, "stopAdService");
        }
        if (mSession != null) {
            mSession.stopAdService();
        }
    }

    /**
     * Resets the AD service.
     *
     * <p>This releases the resources of the corresponding {@link TvAdService.Session}.
     */
    public void resetAdService() {
        if (DEBUG) {
            Log.d(TAG, "resetAdService");
        }
        if (mSession != null) {
            mSession.resetAdService();
        }
    }

    /**
     * Sends current video bounds to related TV AD service.
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
     * Sends current channel URI to related TV AD service.
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
     * Sends track info list to related TV AD service.
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
     * Sends current TV input ID to related TV AD service.
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
     * Sends signing result to related TV AD service.
     *
     * <p>This is used when the corresponding server of the ADs requires signing during handshaking,
     * and the AD service doesn't have the built-in private key. The private key is provided by the
     * content providers and pre-built in the related app, such as TV app.
     *
     * @param signingId the ID to identify the request. It's the same as the corresponding ID in
     *        {@link TvAdService.Session#requestSigning(String, String, String, byte[])}
     * @param result the signed result.
     * @hide
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
     * Notifies the corresponding {@link TvAdService} when there is an error.
     *
     * @param errMsg the message of the error.
     * @param params additional parameters of the error. For example, the signingId of {@link
     *     TvAdView.TvAdCallback#onRequestSigning(String, String, String, String, byte[])} can be
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
     * This is called to notify the corresponding TV AD service when a new TV message is received.
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

    /**
     * Interface definition for a callback to be invoked when the unhandled input event is received.
     * @hide
     */
    public interface OnUnhandledInputEventListener {
        /**
         * Called when an input event was not handled by the TV AD service.
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

    /**
     * Sets the callback to be invoked when an event is dispatched to this TvAdView.
     *
     * @param callback the callback to receive events. MUST NOT be {@code null}.
     *
     * @see #clearCallback()
     * @hide
     */
    public void setCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull TvAdCallback callback) {
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, callback);
        synchronized (mCallbackLock) {
            mCallbackExecutor = executor;
            mCallback = callback;
        }
    }

    /**
     * Clears the callback.
     *
     * @see #setCallback(Executor, TvAdCallback)
     * @hide
     */
    public void clearCallback() {
        synchronized (mCallbackLock) {
            mCallback = null;
            mCallbackExecutor = null;
        }
    }

    private class MySessionCallback extends TvAdManager.SessionCallback {
        final String mServiceId;

        MySessionCallback(String serviceId) {
            mServiceId = serviceId;
        }

        @Override
        public void onSessionCreated(TvAdManager.Session session) {
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
        public void onSessionReleased(TvAdManager.Session session) {
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
        public void onLayoutSurface(
                TvAdManager.Session session, int left, int top, int right, int bottom) {
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
        public void onRequestCurrentVideoBounds(TvAdManager.Session session) {
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
                                mCallback.onRequestCurrentVideoBounds(mServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestCurrentChannelUri(TvAdManager.Session session) {
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
                                mCallback.onRequestCurrentChannelUri(mServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestTrackInfoList(TvAdManager.Session session) {
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
                                mCallback.onRequestTrackInfoList(mServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestCurrentTvInputId(TvAdManager.Session session) {
            if (DEBUG) {
                Log.d(TAG, "onRequestCurrentTvInputId");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestCurrentTvInputId - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onRequestCurrentTvInputId(mServiceId);
                            }
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestSigning(TvAdManager.Session session, String id, String algorithm,
                String alias, byte[] data) {
            if (DEBUG) {
                Log.d(TAG, "onRequestSigning");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onRequestSigning - session not created");
                return;
            }
            synchronized (mCallbackLock) {
                if (mCallbackExecutor != null) {
                    mCallbackExecutor.execute(() -> {
                        synchronized (mCallbackLock) {
                            if (mCallback != null) {
                                mCallback.onRequestSigning(mServiceId, id, algorithm, alias, data);
                            }
                        }
                    });
                }
            }
        }
    }

    /**
     * Callback used to receive various status updates on the {@link TvAdView}.
     * @hide
     */
    public abstract static class TvAdCallback {

        /**
         * This is called when {@link TvAdService.Session#requestCurrentVideoBounds()}
         * is called.
         *
         * @param serviceId The ID of the TV AD service bound to this view.
         */
        public void onRequestCurrentVideoBounds(@NonNull String serviceId) {
        }

        /**
         * This is called when {@link TvAdService.Session#requestCurrentChannelUri()} is
         * called.
         *
         * @param serviceId The ID of the AD service bound to this view.
         */
        public void onRequestCurrentChannelUri(@NonNull String serviceId) {
        }

        /**
         * This is called when {@link TvAdService.Session#requestTrackInfoList()} is called.
         *
         * @param serviceId The ID of the AD service bound to this view.
         */
        public void onRequestTrackInfoList(@NonNull String serviceId) {
        }

        /**
         * This is called when {@link TvAdService.Session#requestCurrentTvInputId()} is called.
         *
         * @param serviceId The ID of the AD service bound to this view.
         */
        public void onRequestCurrentTvInputId(@NonNull String serviceId) {
        }

        /**
         * This is called when
         * {@link TvAdService.Session#requestSigning(String, String, String, byte[])} is called.
         *
         * @param serviceId The ID of the AD service bound to this view.
         * @param signingId the ID to identify the request.
         * @param algorithm the standard name of the signature algorithm requested, such as
         *                  MD5withRSA, SHA256withDSA, etc.
         * @param alias the alias of the corresponding {@link java.security.KeyStore}.
         * @param data the original bytes to be signed.
         *
         */
        public void onRequestSigning(@NonNull String serviceId, @NonNull String signingId,
                @NonNull String algorithm, @NonNull String alias, @NonNull byte[] data) {
        }
    }
}
