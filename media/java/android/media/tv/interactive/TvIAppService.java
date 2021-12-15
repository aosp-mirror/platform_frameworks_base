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

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.BroadcastInfoResponse;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
 * The TvIAppService class represents a TV interactive applications RTE.
 */
public abstract class TvIAppService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvIAppService";

    private static final int DETACH_MEDIA_VIEW_TIMEOUT_MS = 5000;

    // TODO: cleanup and unhide APIs.

    /**
     * This is the interface name that a service implementing a TV IApp service should say that it
     * supports -- that is, this is the action it uses for its intent filter. To be supported, the
     * service must also require the android.Manifest.permission#BIND_TV_IAPP permission so
     * that other applications cannot abuse it.
     */
    public static final String SERVICE_INTERFACE = "android.media.tv.interactive.TvIAppService";

    /**
     * Name under which a TvIAppService component publishes information about itself. This meta-data
     * must reference an XML resource containing an
     * <code>&lt;{@link android.R.styleable#TvIAppService tv-iapp}&gt;</code>
     * tag.
     */
    public static final String SERVICE_META_DATA = "android.media.tv.interactive.app";

    private final Handler mServiceHandler = new ServiceHandler();
    private final RemoteCallbackList<ITvIAppServiceCallback> mCallbacks =
            new RemoteCallbackList<>();

    /** @hide */
    @Override
    public final IBinder onBind(Intent intent) {
        ITvIAppService.Stub tvIAppServiceBinder = new ITvIAppService.Stub() {
            @Override
            public void registerCallback(ITvIAppServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.register(cb);
                }
            }

            @Override
            public void unregisterCallback(ITvIAppServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.unregister(cb);
                }
            }

            @Override
            public void createSession(InputChannel channel, ITvIAppSessionCallback cb,
                    String iAppServiceId, int type) {
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = channel;
                args.arg2 = cb;
                args.arg3 = iAppServiceId;
                args.arg4 = type;
                mServiceHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, args)
                        .sendToTarget();
            }

            @Override
            public void prepare(int type) {
                onPrepare(type);
            }

            @Override
            public void notifyAppLinkInfo(Bundle appLinkInfo) {
                onAppLinkInfo(appLinkInfo);
            }

            @Override
            public void sendAppLinkCommand(Bundle command) {
                onAppLinkCommand(command);
            }
        };
        return tvIAppServiceBinder;
    }

    /**
     * Prepares TV IApp service for the given type.
     * @hide
     */
    public void onPrepare(int type) {
        // TODO: make it abstract when unhide
    }

    /**
     * Registers App link info.
     * @hide
     */
    public void onAppLinkInfo(Bundle appLinkInfo) {
        // TODO: make it abstract when unhide
    }

    /**
     * Sends App link info.
     * @hide
     */
    public void onAppLinkCommand(Bundle command) {
        // TODO: make it abstract when unhide
    }


    /**
     * Returns a concrete implementation of {@link Session}.
     *
     * <p>May return {@code null} if this TV IApp service fails to create a session for some
     * reason.
     *
     * @param iAppServiceId The ID of the TV IApp associated with the session.
     * @param type The type of the TV IApp associated with the session.
     * @hide
     */
    @Nullable
    public Session onCreateSession(@NonNull String iAppServiceId, int type) {
        // TODO: make it abstract when unhide
        return null;
    }

    /**
     * Notifies the system when the state of the interactive app has been changed.
     * @param state the current state
     * @hide
     */
    public final void notifyStateChanged(int type, @TvIAppManager.TvIAppRteState int state) {
        mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_RTE_STATE_CHANGED,
                type, state).sendToTarget();
    }

    /**
     * Base class for derived classes to implement to provide a TV interactive app session.
     * @hide
     */
    public abstract static class Session implements KeyEvent.Callback {
        private final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();

        private final Object mLock = new Object();
        // @GuardedBy("mLock")
        private ITvIAppSessionCallback mSessionCallback;
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
        public Session(Context context) {
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
         * <p>The TV IApp service can disable its media view when needed.
         *
         * @param enable {@code true} if you want to enable the media view. {@code false}
         *            otherwise.
         */
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
         * Starts TvIAppService session.
         * @hide
         */
        public void onStartIApp() {
        }

        /**
         * Called when the application sets the surface.
         *
         * <p>The TV IApp service should render interactive app UI onto the given surface. When
         * called with {@code null}, the IApp service should immediately free any references to the
         * currently set surface and stop using it.
         *
         * @param surface The surface to be used for interactive app UI rendering. Can be
         *                {@code null}.
         * @return {@code true} if the surface was set successfully, {@code false} otherwise.
         */
        public abstract boolean onSetSurface(@Nullable Surface surface);

        /**
         * Called after any structural changes (format or size) have been made to the surface passed
         * in {@link #onSetSurface}. This method is always called at least once, after
         * {@link #onSetSurface} is called with non-null surface.
         *
         * @param format The new PixelFormat of the surface.
         * @param width The new width of the surface.
         * @param height The new height of the surface.
         */
        public void onSurfaceChanged(int format, int width, int height) {
        }

        /**
         * Called when the size of the media view is changed by the application.
         *
         * <p>This is always called at least once when the session is created regardless of whether
         * the media view is enabled or not. The media view container size is the same as the
         * containing {@link TvIAppView}. Note that the size of the underlying surface can be
         * different if the surface was changed by calling {@link #layoutSurface}.
         *
         * @param width The width of the media view.
         * @param height The height of the media view.
         */
        public void onMediaViewSizeChanged(int width, int height) {
        }

        /**
         * Called when the application requests to create an media view. Each session
         * implementation can override this method and return its own view.
         *
         * @return a view attached to the media window
         */
        public View onCreateMediaView() {
            return null;
        }

        /**
         * Releases TvIAppService session.
         * @hide
         */
        public void onRelease() {
        }

        /**
         * Called when the corresponding TV input tuned to a channel.
         * @hide
         */
        public void onTuned(Uri channelUri) {
        }

        /**
         * Called when a broadcast info response is received.
         * @hide
         */
        public void onBroadcastInfoResponse(BroadcastInfoResponse response) {
        }

        /**
         * TODO: JavaDoc of APIs related to input events.
         * @hide
         */
        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * @hide
         */
        @Override
        public boolean onKeyLongPress(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * @hide
         */
        @Override
        public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
            return false;
        }

        /**
         * @hide
         */
        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * @hide
         */
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        /**
         * @hide
         */
        public boolean onTrackballEvent(MotionEvent event) {
            return false;
        }

        /**
         * @hide
         */
        public boolean onGenericMotionEvent(MotionEvent event) {
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
         * Requests broadcast related information from the related TV input.
         * @param request the request for broadcast info
         */
        public void requestBroadcastInfo(@NonNull final BroadcastInfoRequest request) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "requestBroadcastInfo (requestId="
                                    + request.getRequestId() + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onBroadcastInfoRequest(request);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in requestBroadcastInfo", e);
                    }
                }
            });
        }

        void startIApp() {
            onStartIApp();
        }

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

        void notifyTuned(Uri channelUri) {
            if (DEBUG) {
                Log.d(TAG, "notifyTuned (channelUri=" + channelUri + ")");
            }
            onTuned(channelUri);
        }


        /**
         * Calls {@link #onBroadcastInfoResponse}.
         */
        void notifyBroadcastInfoResponse(BroadcastInfoResponse response) {
            if (DEBUG) {
                Log.d(TAG, "notifyBroadcastInfoResponse (requestId="
                        + response.getRequestId() + ")");
            }
            onBroadcastInfoResponse(response);
        }

        /**
         * Notifies when the session state is changed.
         * @param state the current state.
         */
        public void notifySessionStateChanged(@TvIAppManager.TvIAppRteState int state) {
            executeOrPostRunnableOnMainThread(new Runnable() {
                @MainThread
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "notifySessionStateChanged (state="
                                    + state + ")");
                        }
                        if (mSessionCallback != null) {
                            mSessionCallback.onSessionStateChanged(state);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifySessionStateChanged", e);
                    }
                }
            });
        }

        /**
         * Takes care of dispatching incoming input events and tells whether the event was handled.
         */
        int dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
            if (DEBUG) Log.d(TAG, "dispatchInputEvent(" + event + ")");
            if (event instanceof KeyEvent) {
                KeyEvent keyEvent = (KeyEvent) event;
                if (keyEvent.dispatch(this, mDispatcherState, this)) {
                    return TvIAppManager.Session.DISPATCH_HANDLED;
                }

                // TODO: special handlings of navigation keys and media keys
            } else if (event instanceof MotionEvent) {
                MotionEvent motionEvent = (MotionEvent) event;
                final int source = motionEvent.getSource();
                if (motionEvent.isTouchEvent()) {
                    if (onTouchEvent(motionEvent)) {
                        return TvIAppManager.Session.DISPATCH_HANDLED;
                    }
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    if (onTrackballEvent(motionEvent)) {
                        return TvIAppManager.Session.DISPATCH_HANDLED;
                    }
                } else {
                    if (onGenericMotionEvent(motionEvent)) {
                        return TvIAppManager.Session.DISPATCH_HANDLED;
                    }
                }
            }
            // TODO: handle overlay view
            return TvIAppManager.Session.DISPATCH_NOT_HANDLED;
        }

        private void initialize(ITvIAppSessionCallback callback) {
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
         * Creates an media view. This calls {@link #onCreateMediaView} to get a view to attach
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
                // Note: relayoutMediaView is called whenever TvIAppView's layout is changed
                // regardless of setMediaViewEnabled.
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

    /**
     * Implements the internal ITvIAppSession interface.
     * @hide
     */
    public static class ITvIAppSessionWrapper extends ITvIAppSession.Stub {
        // TODO: put ITvIAppSessionWrapper in a separate Java file
        private final Session mSessionImpl;
        private InputChannel mChannel;
        private TvIAppEventReceiver mReceiver;

        public ITvIAppSessionWrapper(Context context, Session mSessionImpl, InputChannel channel) {
            this.mSessionImpl = mSessionImpl;
            mChannel = channel;
            if (channel != null) {
                mReceiver = new TvIAppEventReceiver(channel, context.getMainLooper());
            }
        }

        @Override
        public void startIApp() {
            mSessionImpl.startIApp();
        }

        @Override
        public void release() {
            mSessionImpl.scheduleMediaViewCleanup();
            mSessionImpl.release();
        }

        @Override
        public void notifyTuned(Uri channelUri) {
            mSessionImpl.notifyTuned(channelUri);
        }

        @Override
        public void setSurface(Surface surface) {
            mSessionImpl.setSurface(surface);
        }

        @Override
        public void dispatchSurfaceChanged(int format, int width, int height) {
            mSessionImpl.dispatchSurfaceChanged(format, width, height);
        }

        @Override
        public void notifyBroadcastInfoResponse(BroadcastInfoResponse response) {
            mSessionImpl.notifyBroadcastInfoResponse(response);
        }

        @Override
        public void createMediaView(IBinder windowToken, Rect frame) {
            mSessionImpl.createMediaView(windowToken, frame);
        }

        @Override
        public void relayoutMediaView(Rect frame) {
            mSessionImpl.relayoutMediaView(frame);
        }

        @Override
        public void removeMediaView() {
            mSessionImpl.removeMediaView(true);
        }

        private final class TvIAppEventReceiver extends InputEventReceiver {
            TvIAppEventReceiver(InputChannel inputChannel, Looper looper) {
                super(inputChannel, looper);
            }

            @Override
            public void onInputEvent(InputEvent event) {
                if (mSessionImpl == null) {
                    // The session has been finished.
                    finishInputEvent(event, false);
                    return;
                }

                int handled = mSessionImpl.dispatchInputEvent(event, this);
                if (handled != TvIAppManager.Session.DISPATCH_IN_PROGRESS) {
                    finishInputEvent(event, handled == TvIAppManager.Session.DISPATCH_HANDLED);
                }
            }
        }
    }

    @SuppressLint("HandlerLeak")
    private final class ServiceHandler extends Handler {
        private static final int DO_CREATE_SESSION = 1;
        private static final int DO_NOTIFY_SESSION_CREATED = 2;
        private static final int DO_NOTIFY_RTE_STATE_CHANGED = 3;

        private void broadcastRteStateChanged(int type, int state) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).onStateChanged(type, state);
                } catch (RemoteException e) {
                    Log.e(TAG, "error in broadcastRteStateChanged", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_CREATE_SESSION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputChannel channel = (InputChannel) args.arg1;
                    ITvIAppSessionCallback cb = (ITvIAppSessionCallback) args.arg2;
                    String iAppServiceId = (String) args.arg3;
                    int type = (int) args.arg4;
                    args.recycle();
                    Session sessionImpl = onCreateSession(iAppServiceId, type);
                    if (sessionImpl == null) {
                        try {
                            // Failed to create a session.
                            cb.onSessionCreated(null);
                        } catch (RemoteException e) {
                            Log.e(TAG, "error in onSessionCreated", e);
                        }
                        return;
                    }
                    ITvIAppSession stub = new ITvIAppSessionWrapper(
                            TvIAppService.this, sessionImpl, channel);

                    SomeArgs someArgs = SomeArgs.obtain();
                    someArgs.arg1 = sessionImpl;
                    someArgs.arg2 = stub;
                    someArgs.arg3 = cb;
                    mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED,
                            someArgs).sendToTarget();
                    return;
                }
                case DO_NOTIFY_SESSION_CREATED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Session sessionImpl = (Session) args.arg1;
                    ITvIAppSession stub = (ITvIAppSession) args.arg2;
                    ITvIAppSessionCallback cb = (ITvIAppSessionCallback) args.arg3;
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
                case DO_NOTIFY_RTE_STATE_CHANGED: {
                    int type = msg.arg1;
                    int state = msg.arg2;
                    broadcastRteStateChanged(type, state);
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
