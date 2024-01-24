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

import android.annotation.CallSuper;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.tv.TvInputManager;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * The TvAdService class represents a TV client-side advertisement service.
 * @hide
 */
public abstract class TvAdService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvAdService";

    private static final int DETACH_MEDIA_VIEW_TIMEOUT_MS = 5000;

    /**
     * Name under which a TvAdService component publishes information about itself. This meta-data
     * must reference an XML resource containing an
     * <code>&lt;{@link android.R.styleable#TvAdService tv-ad-service}&gt;</code> tag.
     * @hide
     */
    public static final String SERVICE_META_DATA = "android.media.tv.ad.service";

    /**
     * This is the interface name that a service implementing a TV AD service should
     * say that it supports -- that is, this is the action it uses for its intent filter. To be
     * supported, the service must also require the
     * android.Manifest.permission#BIND_TV_AD_SERVICE permission so that other
     * applications cannot abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.media.tv.ad.TvAdService";

    private final Handler mServiceHandler = new ServiceHandler();
    private final RemoteCallbackList<ITvAdServiceCallback> mCallbacks = new RemoteCallbackList<>();

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        ITvAdService.Stub tvAdServiceBinder = new ITvAdService.Stub() {
            @Override
            public void registerCallback(ITvAdServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.register(cb);
                }
            }

            @Override
            public void unregisterCallback(ITvAdServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.unregister(cb);
                }
            }

            @Override
            public void createSession(InputChannel channel, ITvAdSessionCallback cb,
                    String serviceId, String type) {
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = channel;
                args.arg2 = cb;
                args.arg3 = serviceId;
                args.arg4 = type;
                mServiceHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, args)
                        .sendToTarget();
            }

            @Override
            public void sendAppLinkCommand(Bundle command) {
                onAppLinkCommand(command);
            }
        };
        return tvAdServiceBinder;
    }

    /**
     * Called when app link command is received.
     */
    public void onAppLinkCommand(@NonNull Bundle command) {
    }


    /**
     * Returns a concrete implementation of {@link Session}.
     *
     * <p>May return {@code null} if this TV AD service fails to create a session for some
     * reason.
     *
     * @param serviceId The ID of the TV AD associated with the session.
     * @param type The type of the TV AD associated with the session.
     */
    @Nullable
    public abstract Session onCreateSession(@NonNull String serviceId, @NonNull String type);

    /**
     * Base class for derived classes to implement to provide a TV AD session.
     */
    public abstract static class Session implements KeyEvent.Callback {
        private final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();

        private final Object mLock = new Object();
        // @GuardedBy("mLock")
        private ITvAdSessionCallback mSessionCallback;
        // @GuardedBy("mLock")
        private final List<Runnable> mPendingActions = new ArrayList<>();
        private final Context mContext;
        final Handler mHandler;
        private final WindowManager mWindowManager;
        private WindowManager.LayoutParams mWindowParams;
        private Surface mSurface;
        private FrameLayout mMediaViewContainer;
        private View mMediaView;
        private MediaViewCleanUpTask mMediaViewCleanUpTask;
        private boolean mMediaViewEnabled;
        private IBinder mWindowToken;
        private Rect mMediaFrame;


        /**
         * Creates a new Session.
         *
         * @param context The context of the application
         */
        public Session(@NonNull Context context) {
            mContext = context;
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mHandler = new Handler(context.getMainLooper());
        }

        /**
         * Enables or disables the media view.
         *
         * <p>By default, the media view is disabled. Must be called explicitly after the
         * session is created to enable the media view.
         *
         * <p>The TV AD service can disable its media view when needed.
         *
         * @param enable {@code true} if you want to enable the media view. {@code false}
         *            otherwise.
         * @hide
         */
        @CallSuper
        public void setMediaViewEnabled(final boolean enable) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (enable == mMediaViewEnabled) {
                        return;
                    }
                    mMediaViewEnabled = enable;
                    if (enable) {
                        if (mWindowToken != null) {
                            createMediaView(mWindowToken, mMediaFrame);
                        }
                    } else {
                        removeMediaView(false);
                    }
                }
            });
        }

        /**
         * Returns {@code true} if media view is enabled, {@code false} otherwise.
         *
         * @see #setMediaViewEnabled(boolean)
         * @hide
         */
        public boolean isMediaViewEnabled() {
            return mMediaViewEnabled;
        }

        /**
         * Releases TvAdService session.
         */
        public abstract void onRelease();

        void release() {
            onRelease();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            synchronized (mLock) {
                mSessionCallback = null;
                mPendingActions.clear();
            }
            // Removes the media view lastly so that any hanging on the main thread can be handled
            // in {@link #scheduleMediaViewCleanup}.
            removeMediaView(true);
        }

        /**
         * Starts TvAdService session.
         * @hide
         */
        public void onStartAdService() {
        }

        /**
         * Stops TvAdService session.
         * @hide
         */
        public void onStopAdService() {
        }

        /**
         * Resets TvAdService session.
         * @hide
         */
        public void onResetAdService() {
        }

        void startAdService() {
            onStartAdService();
        }

        void stopAdService() {
            onStopAdService();
        }

        void resetAdService() {
            onResetAdService();
        }

        @Override
        public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        @Override
        public boolean onKeyLongPress(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        @Override
        public boolean onKeyMultiple(int keyCode, int count, @NonNull KeyEvent event) {
            return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
            return false;
        }

        /**
         * Implement this method to handle touch screen motion events on the current session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onTouchEvent
         */
        public boolean onTouchEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * Implement this method to handle trackball events on the current session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onTrackballEvent
         */
        public boolean onTrackballEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * Implement this method to handle generic motion events on the current session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onGenericMotionEvent
         */
        public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
            return false;
        }

        /**
         * Assigns a size and position to the surface passed in {@link #onSetSurface}. The position
         * is relative to the overlay view that sits on top of this surface.
         *
         * @param left Left position in pixels, relative to the overlay view.
         * @param top Top position in pixels, relative to the overlay view.
         * @param right Right position in pixels, relative to the overlay view.
         * @param bottom Bottom position in pixels, relative to the overlay view.
         */
        @CallSuper
        public void layoutSurface(final int left, final int top, final int right,
                final int bottom) {
            if (left > right || top > bottom) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "layoutSurface (l=" + left + ", t=" + top
                                    + ", r=" + right + ", b=" + bottom + ",)");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onLayoutSurface(left, top, right, bottom);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in layoutSurface", e);
                    }
                }
            });
        }

        /**
         * Called when the application sets the surface.
         *
         * <p>The TV AD service should render AD UI onto the given surface. When called with
         * {@code null}, the AD service should immediately free any references to the currently set
         * surface and stop using it.
         *
         * @param surface The surface to be used for AD UI rendering. Can be {@code null}.
         * @return {@code true} if the surface was set successfully, {@code false} otherwise.
         */
        public abstract boolean onSetSurface(@Nullable Surface surface);

        /**
         * Called after any structural changes (format or size) have been made to the surface passed
         * in {@link #onSetSurface}. This method is always called at least once, after
         * {@link #onSetSurface} is called with non-null surface.
         *
         * @param format The new {@link PixelFormat} of the surface.
         * @param width The new width of the surface.
         * @param height The new height of the surface.
         */
        public void onSurfaceChanged(@PixelFormat.Format int format, int width, int height) {
        }

        /**
         * Receives current video bounds.
         *
         * @param bounds the rectangle area for rendering the current video.
         * @hide
         */
        public void onCurrentVideoBounds(@NonNull Rect bounds) {
        }

        /**
         * Receives current channel URI.
         * @hide
         */
        public void onCurrentChannelUri(@Nullable Uri channelUri) {
        }

        /**
         * Receives track list.
         * @hide
         */
        public void onTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
        }

        /**
         * Receives current TV input ID.
         * @hide
         */
        public void onCurrentTvInputId(@Nullable String inputId) {
        }

        /**
         * Receives signing result.
         *
         * @param signingId the ID to identify the request. It's the same as the corresponding ID in
         *        {@link Session#requestSigning(String, String, String, byte[])}
         * @param result the signed result.
         *
         * @see #requestSigning(String, String, String, byte[])
         * @hide
         */
        public void onSigningResult(@NonNull String signingId, @NonNull byte[] result) {
        }

        /**
         * Called when the application sends information of an error.
         *
         * @param errMsg the message of the error.
         * @param params additional parameters of the error. For example, the signingId of {@link
         *     TvAdView.TvAdCallback#onRequestSigning(String, String, String, String, byte[])}
         *     can be included to identify the related signing request, and the method name
         *     "onRequestSigning" can also be added to the params.
         *
         * @see TvAdView#ERROR_KEY_METHOD_NAME
         * @hide
         */
        public void onError(@NonNull String errMsg, @NonNull Bundle params) {
        }

        /**
         * Called when a TV message is received
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
         * @hide
         */
        public void onTvMessage(@TvInputManager.TvMessageType int type,
                @NonNull Bundle data) {
        }

        /**
         * Called when the size of the media view is changed by the application.
         *
         * <p>This is always called at least once when the session is created regardless of whether
         * the media view is enabled or not. The media view container size is the same as the
         * containing {@link TvAdView}. Note that the size of the underlying surface can
         * be different if the surface was changed by calling {@link #layoutSurface}.
         *
         * @param width The width of the media view, in pixels.
         * @param height The height of the media view, in pixels.
         * @hide
         */
        public void onMediaViewSizeChanged(@Px int width, @Px int height) {
        }

        /**
         * Called when the application requests to create a media view. Each session
         * implementation can override this method and return its own view.
         *
         * @return a view attached to the media window. {@code null} if no media view is created.
         * @hide
         */
        @Nullable
        public View onCreateMediaView() {
            return null;
        }

        /**
         * Takes care of dispatching incoming input events and tells whether the event was handled.
         */
        int dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
            if (DEBUG) Log.d(TAG, "dispatchInputEvent(" + event + ")");
            if (event instanceof KeyEvent) {
                KeyEvent keyEvent = (KeyEvent) event;
                if (keyEvent.dispatch(this, mDispatcherState, this)) {
                    return TvAdManager.Session.DISPATCH_HANDLED;
                }

                // TODO: special handlings of navigation keys and media keys
            } else if (event instanceof MotionEvent) {
                MotionEvent motionEvent = (MotionEvent) event;
                final int source = motionEvent.getSource();
                if (motionEvent.isTouchEvent()) {
                    if (onTouchEvent(motionEvent)) {
                        return TvAdManager.Session.DISPATCH_HANDLED;
                    }
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    if (onTrackballEvent(motionEvent)) {
                        return TvAdManager.Session.DISPATCH_HANDLED;
                    }
                } else {
                    if (onGenericMotionEvent(motionEvent)) {
                        return TvAdManager.Session.DISPATCH_HANDLED;
                    }
                }
            }
            // TODO: handle overlay view
            return TvAdManager.Session.DISPATCH_NOT_HANDLED;
        }


        private void initialize(ITvAdSessionCallback callback) {
            synchronized (mLock) {
                mSessionCallback = callback;
                for (Runnable runnable : mPendingActions) {
                    runnable.run();
                }
                mPendingActions.clear();
            }
        }

        /**
         * Calls {@link #onSetSurface}.
         */
        void setSurface(Surface surface) {
            onSetSurface(surface);
            if (mSurface != null) {
                mSurface.release();
            }
            mSurface = surface;
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onSurfaceChanged}.
         */
        void dispatchSurfaceChanged(int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "dispatchSurfaceChanged(format=" + format + ", width=" + width
                        + ", height=" + height + ")");
            }
            onSurfaceChanged(format, width, height);
        }

        void sendCurrentVideoBounds(@NonNull Rect bounds) {
            onCurrentVideoBounds(bounds);
        }

        void sendCurrentChannelUri(@Nullable Uri channelUri) {
            onCurrentChannelUri(channelUri);
        }

        void sendTrackInfoList(@NonNull List<TvTrackInfo> tracks) {
            onTrackInfoList(tracks);
        }

        void sendCurrentTvInputId(@Nullable String inputId) {
            onCurrentTvInputId(inputId);
        }

        void sendSigningResult(String signingId, byte[] result) {
            onSigningResult(signingId, result);
        }

        void notifyError(String errMsg, Bundle params) {
            onError(errMsg, params);
        }

        void notifyTvMessage(int type, Bundle data) {
            if (DEBUG) {
                Log.d(TAG, "notifyTvMessage (type=" + type + ", data= " + data + ")");
            }
            onTvMessage(type, data);
        }

        private void executeOrPostRunnableOnMainThread(Runnable action) {
            synchronized (mLock) {
                if (mSessionCallback == null) {
                    // The session is not initialized yet.
                    mPendingActions.add(action);
                } else {
                    if (mHandler.getLooper().isCurrentThread()) {
                        action.run();
                    } else {
                        // Posts the runnable if this is not called from the main thread
                        mHandler.post(action);
                    }
                }
            }
        }

        /**
         * Creates a media view. This calls {@link #onCreateMediaView} to get a view to attach
         * to the media window.
         *
         * @param windowToken A window token of the application.
         * @param frame A position of the media view.
         */
        void createMediaView(IBinder windowToken, Rect frame) {
            if (mMediaViewContainer != null) {
                removeMediaView(false);
            }
            if (DEBUG) Log.d(TAG, "create media view(" + frame + ")");
            mWindowToken = windowToken;
            mMediaFrame = frame;
            onMediaViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            if (!mMediaViewEnabled) {
                return;
            }
            mMediaView = onCreateMediaView();
            if (mMediaView == null) {
                return;
            }
            if (mMediaViewCleanUpTask != null) {
                mMediaViewCleanUpTask.cancel(true);
                mMediaViewCleanUpTask = null;
            }
            // Creates a container view to check hanging on the media view detaching.
            // Adding/removing the media view to/from the container make the view attach/detach
            // logic run on the main thread.
            mMediaViewContainer = new FrameLayout(mContext.getApplicationContext());
            mMediaViewContainer.addView(mMediaView);

            int type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
            // We make the overlay view non-focusable and non-touchable so that
            // the application that owns the window token can decide whether to consume or
            // dispatch the input events.
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (ActivityManager.isHighEndGfx()) {
                flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            }
            mWindowParams = new WindowManager.LayoutParams(
                    frame.right - frame.left, frame.bottom - frame.top,
                    frame.left, frame.top, type, flags, PixelFormat.TRANSPARENT);
            mWindowParams.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
            mWindowParams.gravity = Gravity.START | Gravity.TOP;
            mWindowParams.token = windowToken;
            mWindowManager.addView(mMediaViewContainer, mWindowParams);
        }

        /**
         * Relayouts the current media view.
         *
         * @param frame A new position of the media view.
         */
        void relayoutMediaView(Rect frame) {
            if (DEBUG) Log.d(TAG, "relayoutMediaView(" + frame + ")");
            if (mMediaFrame == null || mMediaFrame.width() != frame.width()
                    || mMediaFrame.height() != frame.height()) {
                // Note: relayoutMediaView is called whenever TvAdView's layout is
                // changed regardless of setMediaViewEnabled.
                onMediaViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            }
            mMediaFrame = frame;
            if (!mMediaViewEnabled || mMediaViewContainer == null) {
                return;
            }
            mWindowParams.x = frame.left;
            mWindowParams.y = frame.top;
            mWindowParams.width = frame.right - frame.left;
            mWindowParams.height = frame.bottom - frame.top;
            mWindowManager.updateViewLayout(mMediaViewContainer, mWindowParams);
        }

        /**
         * Removes the current media view.
         */
        void removeMediaView(boolean clearWindowToken) {
            if (DEBUG) Log.d(TAG, "removeMediaView(" + mMediaViewContainer + ")");
            if (clearWindowToken) {
                mWindowToken = null;
                mMediaFrame = null;
            }
            if (mMediaViewContainer != null) {
                // Removes the media view from the view hierarchy in advance so that it can be
                // cleaned up in the {@link MediaViewCleanUpTask} if the remove process is
                // hanging.
                mMediaViewContainer.removeView(mMediaView);
                mMediaView = null;
                mWindowManager.removeView(mMediaViewContainer);
                mMediaViewContainer = null;
                mWindowParams = null;
            }
        }

        /**
         * Schedules a task which checks whether the media view is detached and kills the process
         * if it is not. Note that this method is expected to be called in a non-main thread.
         */
        void scheduleMediaViewCleanup() {
            View mediaViewParent = mMediaViewContainer;
            if (mediaViewParent != null) {
                mMediaViewCleanUpTask = new MediaViewCleanUpTask();
                mMediaViewCleanUpTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                        mediaViewParent);
            }
        }
    }

    private static final class MediaViewCleanUpTask extends AsyncTask<View, Void, Void> {
        @Override
        protected Void doInBackground(View... views) {
            View mediaViewParent = views[0];
            try {
                Thread.sleep(DETACH_MEDIA_VIEW_TIMEOUT_MS);
            } catch (InterruptedException e) {
                return null;
            }
            if (isCancelled()) {
                return null;
            }
            if (mediaViewParent.isAttachedToWindow()) {
                Log.e(TAG, "Time out on releasing media view. Killing "
                        + mediaViewParent.getContext().getPackageName());
                android.os.Process.killProcess(Process.myPid());
            }
            return null;
        }
    }


    @SuppressLint("HandlerLeak")
    private final class ServiceHandler extends Handler {
        private static final int DO_CREATE_SESSION = 1;
        private static final int DO_NOTIFY_SESSION_CREATED = 2;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_CREATE_SESSION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputChannel channel = (InputChannel) args.arg1;
                    ITvAdSessionCallback cb = (ITvAdSessionCallback) args.arg2;
                    String serviceId = (String) args.arg3;
                    String type = (String) args.arg4;
                    args.recycle();
                    TvAdService.Session sessionImpl = onCreateSession(serviceId, type);
                    if (sessionImpl == null) {
                        try {
                            // Failed to create a session.
                            cb.onSessionCreated(null);
                        } catch (RemoteException e) {
                            Log.e(TAG, "error in onSessionCreated", e);
                        }
                        return;
                    }
                    ITvAdSession stub =
                            new ITvAdSessionWrapper(TvAdService.this, sessionImpl, channel);

                    SomeArgs someArgs = SomeArgs.obtain();
                    someArgs.arg1 = sessionImpl;
                    someArgs.arg2 = stub;
                    someArgs.arg3 = cb;
                    mServiceHandler.obtainMessage(
                            DO_NOTIFY_SESSION_CREATED, someArgs).sendToTarget();
                    return;
                }
                case DO_NOTIFY_SESSION_CREATED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Session sessionImpl = (Session) args.arg1;
                    ITvAdSession stub = (ITvAdSession) args.arg2;
                    ITvAdSessionCallback cb = (ITvAdSessionCallback) args.arg3;
                    try {
                        cb.onSessionCreated(stub);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated", e);
                    }
                    if (sessionImpl != null) {
                        sessionImpl.initialize(cb);
                    }
                    args.recycle();
                    return;
                }
                default: {
                    Log.w(TAG, "Unhandled message code: " + msg.what);
                    return;
                }
            }
        }

    }
}
