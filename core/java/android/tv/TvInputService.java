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

package android.tv;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A base class for implementing television input service.
 */
public abstract class TvInputService extends Service {
    // STOPSHIP: Turn debugging off.
    private static final boolean DEBUG = true;
    private static final String TAG = "TvInputService";

    /**
     * This is the interface name that a service implementing a TV input should say that it support
     * -- that is, this is the action it uses for its intent filter. To be supported, the service
     * must also require the {@link android.Manifest.permission#BIND_TV_INPUT} permission so that
     * other applications cannot abuse it.
     */
    public static final String SERVICE_INTERFACE = "android.tv.TvInputService";

    private ComponentName mComponentName;
    private final Handler mHandler = new ServiceHandler();
    private final RemoteCallbackList<ITvInputServiceCallback> mCallbacks =
            new RemoteCallbackList<ITvInputServiceCallback>();
    private boolean mAvailable;

    @Override
    public void onCreate() {
        super.onCreate();
        mComponentName = new ComponentName(getPackageName(), getClass().getName());
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return new ITvInputService.Stub() {
            @Override
            public void registerCallback(ITvInputServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.register(cb);
                    // The first time a callback is registered, the service needs to report its
                    // availability status so that the system can know its initial value.
                    try {
                        cb.onAvailabilityChanged(mComponentName, mAvailable);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onAvailabilityChanged", e);
                    }
                }
            }

            @Override
            public void unregisterCallback(ITvInputServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.unregister(cb);
                }
            }

            @Override
            public void createSession(ITvInputSessionCallback cb) {
                if (cb != null) {
                    mHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, cb).sendToTarget();
                }
            }
        };
    }

    /**
     * Convenience method to notify an availability change of this TV input service.
     *
     * @param available {@code true} if the input service is available to show TV programs.
     */
    public final void setAvailable(boolean available) {
        if (available != mAvailable) {
            mAvailable = available;
            mHandler.obtainMessage(ServiceHandler.DO_BROADCAST_AVAILABILITY_CHANGE, available)
                    .sendToTarget();
        }
    }

    /**
     * Get the number of callbacks that are registered.
     *
     * @hide
     */
    @VisibleForTesting
    public final int getRegisteredCallbackCount() {
        return mCallbacks.getRegisteredCallbackCount();
    }

    /**
     * Returns a concrete implementation of {@link TvInputSessionImpl}.
     * <p>
     * May return {@code null} if this TV input service fails to create a session for some reason.
     * </p>
     */
    public abstract TvInputSessionImpl onCreateSession();

    /**
     * Base class for derived classes to implement to provide {@link TvInputSession}.
     */
    public abstract static class TvInputSessionImpl {
        /**
         * Called when the session is released.
         */
        public abstract void onRelease();

        /**
         * Sets the {@link Surface} for the current input session on which the TV input renders
         * video.
         *
         * @param surface {@link Surface} an application passes to this TV input session.
         * @return {@code true} if the surface was set, {@code false} otherwise.
         */
        public abstract boolean onSetSurface(Surface surface);

        /**
         * Sets the relative volume of the current TV input session to handle the change of audio
         * focus by setting.
         *
         * @param volume Volume scale from 0.0 to 1.0.
         */
        public abstract void onSetVolume(float volume);

        /**
         * Tunes to a given channel.
         *
         * @param channelUri The URI of the channel.
         * @return {@code true} the tuning was successful, {@code false} otherwise.
         */
        public abstract boolean onTune(Uri channelUri);
    }

    /**
     * Internal implementation of {@link TvInputSession}. This takes care of basic maintenance of
     * the TV input session but most behavior must be implemented in {@link TvInputSessionImpl}
     * returned by {@link TvInputService#onCreateSession}.
     */
    private static class TvInputSessionImplInternal extends TvInputSession {
        private final TvInputSessionImpl mSessionImpl;

        public TvInputSessionImplInternal(TvInputSessionImpl sessionImpl) {
            mSessionImpl = sessionImpl;
        }

        /**
         * This method is called when the application would like to stop using the current input
         * session.
         */
        @Override
        public final void release() {
            mSessionImpl.onRelease();
        }

        /**
         * Calls {@link TvInputSessionImpl#onSetSurface}.
         */
        @Override
        public final void setSurface(Surface surface) {
            mSessionImpl.onSetSurface(surface);
            // TODO: Handle failure.
        }

        /**
         * Calls {@link TvInputSessionImpl#onSetVolume}.
         */
        @Override
        public final void setVolume(float volume) {
            mSessionImpl.onSetVolume(volume);
        }

        /**
         * Calls {@link TvInputSessionImpl#onTune}.
         */
        @Override
        public final void tune(Uri channelUri) {
            mSessionImpl.onTune(channelUri);
            // TODO: Handle failure.
        }
    }

    private final class ServiceHandler extends Handler {
        private static final int DO_CREATE_SESSION = 1;
        private static final int DO_BROADCAST_AVAILABILITY_CHANGE = 2;

        @Override
        public final void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_CREATE_SESSION: {
                    ITvInputSessionCallback cb = (ITvInputSessionCallback) msg.obj;
                    try {
                        TvInputSessionImpl sessionImpl = onCreateSession();
                        if (sessionImpl == null) {
                            // Failed to create a session.
                            cb.onSessionCreated(null);
                            return;
                        }
                        ITvInputSession stub = new ITvInputSessionWrapper(TvInputService.this,
                                new TvInputSessionImplInternal(sessionImpl));
                        cb.onSessionCreated(stub);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated");
                    }
                    return;
                }
                case DO_BROADCAST_AVAILABILITY_CHANGE: {
                    boolean isAvailable = (Boolean) msg.obj;
                    int n = mCallbacks.beginBroadcast();
                    try {
                        for (int i = 0; i < n; i++) {
                            mCallbacks.getBroadcastItem(i).onAvailabilityChanged(mComponentName,
                                    isAvailable);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unexpected exception", e);
                    } finally {
                        mCallbacks.finishBroadcast();
                    }
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
