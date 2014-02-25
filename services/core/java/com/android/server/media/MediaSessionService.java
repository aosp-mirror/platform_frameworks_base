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

package com.android.server.media;

import android.content.Context;
import android.media.session.IMediaSession;
import android.media.session.IMediaSessionCallback;
import android.media.session.IMediaSessionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.SystemService;

import java.util.ArrayList;

/**
 * System implementation of MediaSessionManager
 */
public class MediaSessionService extends SystemService {
    private static final String TAG = "MediaSessionService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final SessionManagerImpl mSessionManagerImpl;

    private final ArrayList<MediaSessionRecord> mSessions
            = new ArrayList<MediaSessionRecord>();
    private final Object mLock = new Object();
    // TODO do we want a separate thread for handling mediasession messages?
    private final Handler mHandler = new Handler();

    public MediaSessionService(Context context) {
        super(context);
        mSessionManagerImpl = new SessionManagerImpl();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_SESSION_SERVICE, mSessionManagerImpl);
    }

    void sessionDied(MediaSessionRecord session) {
        synchronized (mSessions) {
            destroySessionLocked(session);
        }
    }

    void destroySession(MediaSessionRecord session) {
        synchronized (mSessions) {
            destroySessionLocked(session);
        }
    }

    private void destroySessionLocked(MediaSessionRecord session) {
        mSessions.remove(session);
    }

    private void enforcePackageName(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName may not be empty");
        }
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        final int packageCount = packages.length;
        for (int i = 0; i < packageCount; i++) {
            if (packageName.equals(packages[i])) {
                return;
            }
        }
        throw new IllegalArgumentException("packageName is not owned by the calling process");
    }

    private MediaSessionRecord createSessionInternal(int pid, String packageName,
            IMediaSessionCallback cb, String tag) {
        synchronized (mLock) {
            return createSessionLocked(pid, packageName, cb, tag);
        }
    }

    private MediaSessionRecord createSessionLocked(int pid, String packageName,
            IMediaSessionCallback cb, String tag) {
        final MediaSessionRecord session = new MediaSessionRecord(pid, packageName, cb, tag, this,
                mHandler);
        try {
            cb.asBinder().linkToDeath(session, 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Media Session owner died prematurely.", e);
        }
        synchronized (mSessions) {
            mSessions.add(session);
        }
        if (DEBUG) {
            Log.d(TAG, "Created session for package " + packageName + " with tag " + tag);
        }
        return session;
    }

    class SessionManagerImpl extends IMediaSessionManager.Stub {
        @Override
        public IMediaSession createSession(String packageName, IMediaSessionCallback cb, String tag)
                throws RemoteException {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                enforcePackageName(packageName, uid);
                if (cb == null) {
                    throw new IllegalArgumentException("Controller callback cannot be null");
                }
                return createSessionInternal(pid, packageName, cb, tag).getSessionBinder();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

}
