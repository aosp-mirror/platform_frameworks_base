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
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;

import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * The TvIAppService class represents a TV interactive applications RTE.
 * @hide
 */
public abstract class TvIAppService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvIAppService";

    private final Handler mServiceHandler = new ServiceHandler();

    /**
     * This is the interface name that a service implementing an environment to run Tv IApp should
     * say that it support -- that is, this is the action it uses for its intent filter. To be
     * supported, the service must also require the BIND_TV_IAPP permission so that other
     * applications cannot abuse it.
     */
    public static final String SERVICE_INTERFACE = "android.media.tv.TvIAppService";

    @Override
    public final IBinder onBind(Intent intent) {
        ITvIAppService.Stub tvIAppServiceBinder = new ITvIAppService.Stub() {

            @Override
            public void createSession(ITvIAppSessionCallback cb, String iAppServiceId, int type) {
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = cb;
                args.arg2 = iAppServiceId;
                args.arg3 = type;
                mServiceHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, args)
                        .sendToTarget();
            }
        };
        return tvIAppServiceBinder;
    }


    /**
     * Returns a concrete implementation of {@link Session}.
     *
     * <p>May return {@code null} if this TV IApp service fails to create a session for some
     * reason.
     *
     * @param iAppServiceId The ID of the TV IApp associated with the session.
     * @param type The type of the TV IApp associated with the session.
     */
    @Nullable
    public abstract Session onCreateSession(@NonNull String iAppServiceId, int type);

    /**
     * Base class for derived classes to implement to provide a TV interactive app session.
     */
    public abstract static class Session implements KeyEvent.Callback {
        private final Object mLock = new Object();
        // @GuardedBy("mLock")
        private ITvIAppSessionCallback mSessionCallback;
        // @GuardedBy("mLock")
        private final List<Runnable> mPendingActions = new ArrayList<>();

        private final Context mContext;
        private final Handler mHandler;
        private Surface mSurface;

        /**
         * Creates a new Session.
         *
         * @param context The context of the application
         */
        public Session(Context context) {
            mContext = context;
            mHandler = new Handler(context.getMainLooper());
        }

        /**
         * Starts TvIAppService session.
         */
        public void onStartIApp() {
        }

        /**
         * Called when the application sets the surface.
         *
         * <p>The TV IApp service should render interactive app UI onto the given surface. When
         * called with {@code null}, the input service should immediately free any references to the
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
         * Releases TvIAppService session.
         */
        public void onRelease() {
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

        void startIApp() {
            onStartIApp();
        }

        void release() {
            onRelease();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
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
    }

    /**
     * Implements the internal ITvIAppSession interface.
     */
    public static class ITvIAppSessionWrapper extends ITvIAppSession.Stub {
        private final Session mSessionImpl;

        public ITvIAppSessionWrapper(Session mSessionImpl) {
            this.mSessionImpl = mSessionImpl;
        }

        @Override
        public void startIApp() {
            mSessionImpl.startIApp();
        }

        @Override
        public void release() {
            mSessionImpl.release();
        }

        @Override
        public void setSurface(Surface surface) {
            mSessionImpl.setSurface(surface);
        }

        @Override
        public void dispatchSurfaceChanged(int format, int width, int height) {
            mSessionImpl.dispatchSurfaceChanged(format, width, height);
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
                    ITvIAppSessionCallback cb = (ITvIAppSessionCallback) args.arg1;
                    String iAppServiceId = (String) args.arg2;
                    int type = (int) args.arg3;
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
                    ITvIAppSession stub = new ITvIAppSessionWrapper(sessionImpl);

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
                default: {
                    Log.w(TAG, "Unhandled message code: " + msg.what);
                    return;
                }
            }
        }

    }
}
