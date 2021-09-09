/*
 * Copyright 2020 The Android Open Source Project
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
package android.media;

import static android.Manifest.permission.MEDIA_CONTENT_CONTROL;
import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.CallbackExecutor;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.media.MediaBrowserService;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.annotation.MinSdk;
import com.android.modules.utils.build.SdkLevel;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Provides support for interacting with {@link android.media.MediaSession2 MediaSession2s}
 * that applications have published to express their ongoing media playback state.
 */
@MinSdk(Build.VERSION_CODES.S)
@SystemService(Context.MEDIA_COMMUNICATION_SERVICE)
public class MediaCommunicationManager {
    private static final String TAG = "MediaCommunicationManager";

    /**
     * The manager version used from beginning.
     */
    private static final int VERSION_1 = 1;

    /**
     * Current manager version.
     */
    private static final int CURRENT_VERSION = VERSION_1;

    private final Context mContext;
    // Do not access directly use getService().
    private IMediaCommunicationService mService;

    private final Object mLock = new Object();
    private final CopyOnWriteArrayList<SessionCallbackRecord> mTokenCallbackRecords =
            new CopyOnWriteArrayList<>();

    @GuardedBy("mLock")
    private MediaCommunicationServiceCallbackStub mCallbackStub;

    // TODO: remove this when MCS implements dispatchMediaKeyEvent.
    private MediaSessionManager mMediaSessionManager;

    /**
     * @hide
     */
    public MediaCommunicationManager(@NonNull Context context) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException("Android version must be S or greater.");
        }
        mContext = context;
    }

    /**
     * Gets the version of this {@link MediaCommunicationManager}.
     */
    public @IntRange(from = 1) int getVersion() {
        return CURRENT_VERSION;
    }

    /**
     * Notifies that a new {@link MediaSession2} with type {@link Session2Token#TYPE_SESSION} is
     * created.
     * @param token newly created session2 token
     * @hide
     */
    public void notifySession2Created(@NonNull Session2Token token) {
        Objects.requireNonNull(token, "token shouldn't be null");
        if (token.getType() != Session2Token.TYPE_SESSION) {
            throw new IllegalArgumentException("token's type should be TYPE_SESSION");
        }
        try {
            getService().notifySession2Created(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the remote user is a trusted app.
     * <p>
     * An app is trusted if the app holds the
     * {@link android.Manifest.permission#MEDIA_CONTENT_CONTROL} permission or has an enabled
     * notification listener.
     *
     * @param userInfo The remote user info from either
     *            {@link MediaSession#getCurrentControllerInfo()} or
     *            {@link MediaBrowserService#getCurrentBrowserInfo()}.
     * @return {@code true} if the remote user is trusted or {@code false} otherwise.
     * @hide
     */
    public boolean isTrustedForMediaControl(@NonNull MediaSessionManager.RemoteUserInfo userInfo) {
        Objects.requireNonNull(userInfo, "userInfo shouldn't be null");
        if (userInfo.getPackageName() == null) {
            return false;
        }
        try {
            return getService().isTrusted(
                    userInfo.getPackageName(), userInfo.getPid(), userInfo.getUid());
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot communicate with the service.", e);
        }
        return false;
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Gets a list of {@link Session2Token} with type {@link Session2Token#TYPE_SESSION} for the
     * current user.
     * <p>
     * Although this API can be used without any restriction, each session owners can accept or
     * reject your uses of {@link MediaSession2}.
     *
     * @return A list of {@link Session2Token}.
     */
    @NonNull
    public List<Session2Token> getSession2Tokens() {
        return getSession2Tokens(UserHandle.myUserId());
    }

    /**
     * Adds a callback to be notified when the list of active sessions changes.
     * <p>
     * This requires the {@link android.Manifest.permission#MEDIA_CONTENT_CONTROL} permission be
     * held by the calling app.
     * </p>
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @RequiresPermission(MEDIA_CONTENT_CONTROL)
    public void registerSessionCallback(@CallbackExecutor @NonNull Executor executor,
            @NonNull SessionCallback callback) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mTokenCallbackRecords.addIfAbsent(
                new SessionCallbackRecord(executor, callback))) {
            Log.w(TAG, "registerSession2TokenCallback: Ignoring the same callback");
            return;
        }
        synchronized (mLock) {
            if (mCallbackStub == null) {
                MediaCommunicationServiceCallbackStub callbackStub =
                        new MediaCommunicationServiceCallbackStub();
                try {
                    getService().registerCallback(callbackStub, mContext.getPackageName());
                    mCallbackStub = callbackStub;
                } catch (RemoteException ex) {
                    Log.e(TAG, "Failed to register callback.", ex);
                }
            }
        }
    }

    /**
     * Stops receiving active sessions updates on the specified callback.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public void unregisterSessionCallback(@NonNull SessionCallback callback) {
        if (!mTokenCallbackRecords.remove(
                new SessionCallbackRecord(null, callback))) {
            Log.w(TAG, "unregisterSession2TokenCallback: Ignoring an unknown callback.");
            return;
        }
        synchronized (mLock) {
            if (mCallbackStub != null && mTokenCallbackRecords.isEmpty()) {
                try {
                    getService().unregisterCallback(mCallbackStub);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Failed to unregister callback.", ex);
                }
                mCallbackStub = null;
            }
        }
    }

    private IMediaCommunicationService getService() {
        if (mService == null) {
            mService = IMediaCommunicationService.Stub.asInterface(
                    MediaFrameworkInitializer.getMediaServiceManager()
                            .getMediaCommunicationServiceRegisterer()
                            .get());
        }
        return mService;
    }

    // TODO: remove this when MCS implements dispatchMediaKeyEvent.
    private MediaSessionManager getMediaSessionManager() {
        if (mMediaSessionManager == null) {
            mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        }
        return mMediaSessionManager;
    }

    private List<Session2Token> getSession2Tokens(int userId) {
        try {
            MediaParceledListSlice slice = getService().getSession2Tokens(userId);
            return slice == null ? Collections.emptyList() : slice.getList();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get session tokens", e);
        }
        return Collections.emptyList();
    }

    /**
     * Sends a media key event. The receiver will be selected automatically.
     *
     * @param keyEvent the key event to send
     * @param asSystemService if {@code true}, the event sent to the session as if it was come from
     *                        the system service instead of the app process.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void dispatchMediaKeyEvent(@NonNull KeyEvent keyEvent, boolean asSystemService) {
        Objects.requireNonNull(keyEvent, "keyEvent shouldn't be null");

        // When MCS handles this, caller is changed.
        // TODO: remove this when MCS implementation is done.
        if (!asSystemService) {
            getMediaSessionManager().dispatchMediaKeyEvent(keyEvent, false);
            return;
        }

        try {
            getService().dispatchMediaKeyEvent(mContext.getPackageName(),
                    keyEvent, asSystemService);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send key event.", e);
        }
    }

    /**
     * Callback for listening to changes to the sessions.
     * @see #registerSessionCallback(Executor, SessionCallback)
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public interface SessionCallback {
        /**
         * Called when a new {@link MediaSession2 media session2} is created.
         * @param token the newly created token
         */
        default void onSession2TokenCreated(@NonNull Session2Token token) {}

        /**
         * Called when {@link #getSession2Tokens() session tokens} are changed.
         */
        default void onSession2TokensChanged(@NonNull List<Session2Token> tokens) {}
    }

    private static final class SessionCallbackRecord {
        public final Executor executor;
        public final SessionCallback callback;

        SessionCallbackRecord(Executor executor, SessionCallback callback) {
            this.executor = executor;
            this.callback = callback;
        }

        @Override
        public int hashCode() {
            return Objects.hash(callback);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SessionCallbackRecord)) {
                return false;
            }
            return Objects.equals(this.callback, ((SessionCallbackRecord) obj).callback);
        }
    }

    class MediaCommunicationServiceCallbackStub extends IMediaCommunicationServiceCallback.Stub {
        @Override
        public void onSession2Created(Session2Token token) throws RemoteException {
            for (SessionCallbackRecord record : mTokenCallbackRecords) {
                record.executor.execute(() -> record.callback.onSession2TokenCreated(token));
            }
        }

        @Override
        public void onSession2Changed(MediaParceledListSlice tokens) throws RemoteException {
            List<Session2Token> tokenList = tokens.getList();
            for (SessionCallbackRecord record : mTokenCallbackRecords) {
                record.executor.execute(() -> record.callback.onSession2TokensChanged(tokenList));
            }
        }
    }
}
