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
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;

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

        /**
         * Starts TvIAppService session.
         */
        public void onStartIApp() {
        }

        /**
         * Releases TvIAppService session.
         */
        public void onRelease() {
        }

        void startIApp() {
            onStartIApp();
        }
        void release() {
            onRelease();
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
