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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.tv.TvInputManager;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.media.tv.interactive.TvIAppManager.Session;
import android.media.tv.interactive.TvIAppManager.SessionCallback;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

/**
 * Displays contents of interactive TV applications.
 * @hide
 */
public class TvIAppView extends ViewGroup {
    private static final String TAG = "TvIAppView";
    private static final boolean DEBUG = false;

    private static final int SET_TVVIEW_SUCCESS = 1;
    private static final int SET_TVVIEW_FAIL = 2;
    private static final int UNSET_TVVIEW_SUCCESS = 3;
    private static final int UNSET_TVVIEW_FAIL = 4;

    private final TvIAppManager mTvIAppManager;
    private final Handler mHandler = new Handler();
    private Session mSession;
    private MySessionCallback mSessionCallback;
    private TvIAppCallback mCallback;
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

    public TvIAppView(@NonNull Context context) {
        this(context, null, 0);
    }

    public TvIAppView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TvIAppView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
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
        mTvIAppManager = (TvIAppManager) getContext().getSystemService(Context.TV_IAPP_SERVICE);
    }

    /**
     * Sets the callback to be invoked when an event is dispatched to this TvIAppView.
     *
     * @param callback The callback to receive events. A value of {@code null} removes the existing
     *            callback.
     */
    public void setCallback(@Nullable TvIAppCallback callback) {
        mCallback = callback;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        createSessionMediaView();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeSessionMediaView();
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
        addView(mSurfaceView);
    }

    /**
     * Resets this TvIAppView.
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

    /**
     * Prepares the interactive application.
     */
    public void prepareIApp(@NonNull String iAppServiceId, int type) {
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

    /**
     * Stops the interactive application.
     */
    public void stopIApp() {
        if (DEBUG) {
            Log.d(TAG, "stopIApp");
        }
        if (mSession != null) {
            mSession.stopIApp();
        }
    }

    /**
     * Sends current channel URI to related TV interactive app.
     */
    public void sendCurrentChannelUri(Uri channelUri) {
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
    public void sendTrackInfoList(List<TvTrackInfo> tracks) {
        if (DEBUG) {
            Log.d(TAG, "sendTrackInfoList");
        }
        if (mSession != null) {
            mSession.sendTrackInfoList(tracks);
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
     * @see #destroyBiInteractiveApp(String)
     * @hide
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
     * @hide
     */
    public void destroyBiInteractiveApp(@NonNull String biIAppId) {
        if (DEBUG) {
            Log.d(TAG, "destroyBiInteractiveApp biIAppId=" + biIAppId);
        }
        if (mSession != null) {
            mSession.destroyBiInteractiveApp(biIAppId);
        }
    }

    public Session getIAppSession() {
        return mSession;
    }

    /**
     * Sets the TvIAppView to receive events from TIS. This method links the session of
     * TvIAppManager to TvInputManager session, so the TIAS can get the TIS events.
     *
     * @param tvView the TvView to be linked to this TvIAppView via linking of Sessions.
     * @return to be added
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
        inputSession.setIAppSession(mSession);
        return SET_TVVIEW_SUCCESS;
    }

    private int unsetTvView() {
        if (mSession == null || mSession.getInputSession() == null) {
            return UNSET_TVVIEW_FAIL;
        }
        mSession.getInputSession().setIAppSession(null);
        mSession.setInputSession(null);
        return UNSET_TVVIEW_SUCCESS;
    }

    /**
     * Callback used to receive various status updates on the {@link TvIAppView}.
     */
    public abstract static class TvIAppCallback {

        /**
         * This is called when a command is requested to be processed by the related TV input.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param cmdType type of the command
         * @param parameters parameters of the command
         */
        public void onCommandRequest(
                @NonNull String iAppServiceId,
                @NonNull @TvIAppService.IAppServiceCommandType String cmdType,
                @Nullable Bundle parameters) {
        }

        /**
         * This is called when the session state is changed.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param state current session state.
         */
        public void onSessionStateChanged(@NonNull String iAppServiceId, int state) {
        }

        /**
         * This is called when broadcast-independent (BI) interactive app is created.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         * @param biIAppUri URI associated this BI interactive app. This is the same URI in
         *                  {@link Session#createBiInteractiveApp(Uri, Bundle)}
         * @param biIAppId BI interactive app ID, which can be used to destroy the BI interactive
         *                 app.
         */
        public void onBiInteractiveAppCreated(@NonNull String iAppServiceId, @NonNull Uri biIAppUri,
                @Nullable String biIAppId) {
        }

        /**
         * This is called when {@link TvIAppService.Session#SetVideoBounds} is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onSetVideoBounds(@NonNull String iAppServiceId, @NonNull Rect rect) {
        }

        /**
         * This is called when {@link TvIAppService.Session#RequestCurrentChannelUri} is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestCurrentChannelUri(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvIAppService.Session#RequestCurrentChannelLcn} is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestCurrentChannelLcn(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvIAppService.Session#RequestStreamVolume} is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestStreamVolume(@NonNull String iAppServiceId) {
        }

        /**
         * This is called when {@link TvIAppService.Session#RequestTrackInfoList} is called.
         *
         * @param iAppServiceId The ID of the TV interactive app service bound to this view.
         */
        public void onRequestTrackInfoList(@NonNull String iAppServiceId) {
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
        public void onCommandRequest(Session session,
                @TvIAppService.IAppServiceCommandType String cmdType, Bundle parameters) {
            if (DEBUG) {
                Log.d(TAG, "onCommandRequest (cmdType=" + cmdType + ", parameters="
                        + parameters.toString() + ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onCommandRequest - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onCommandRequest(mIAppServiceId, cmdType, parameters);
            }
        }

        @Override
        public void onSessionStateChanged(Session session, int state) {
            if (DEBUG) {
                Log.d(TAG, "onSessionStateChanged (state=" + state +  ")");
            }
            if (this != mSessionCallback) {
                Log.w(TAG, "onSessionStateChanged - session not created");
                return;
            }
            if (mCallback != null) {
                mCallback.onSessionStateChanged(mIAppServiceId, state);
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
            if (mCallback != null) {
                mCallback.onBiInteractiveAppCreated(mIAppServiceId, biIAppUri, biIAppId);
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
            if (mCallback != null) {
                mCallback.onSetVideoBounds(mIAppServiceId, rect);
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
            if (mCallback != null) {
                mCallback.onRequestCurrentChannelUri(mIAppServiceId);
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
            if (mCallback != null) {
                mCallback.onRequestCurrentChannelLcn(mIAppServiceId);
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
            if (mCallback != null) {
                mCallback.onRequestStreamVolume(mIAppServiceId);
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
            if (mCallback != null) {
                mCallback.onRequestTrackInfoList(mIAppServiceId);
            }
        }
    }
}
