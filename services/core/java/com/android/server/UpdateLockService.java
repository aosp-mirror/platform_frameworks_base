/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IUpdateLock;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.TokenWatcher;
import android.os.UpdateLock;
import android.os.UserHandle;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public class UpdateLockService extends IUpdateLock.Stub {
    static final boolean DEBUG = false;
    static final String TAG = "UpdateLockService";

    // signatureOrSystem required to use update locks
    static final String PERMISSION = "android.permission.UPDATE_LOCK";

    Context mContext;
    LockWatcher mLocks;

    class LockWatcher extends TokenWatcher {
        LockWatcher(Handler h, String tag) {
            super(h, tag);
        }

        public void acquired() {
            if (DEBUG) {
                Slog.d(TAG, "first acquire; broadcasting convenient=false");
            }
            sendLockChangedBroadcast(false);
        }
        public void released() {
            if (DEBUG) {
                Slog.d(TAG, "last release; broadcasting convenient=true");
            }
            sendLockChangedBroadcast(true);
        }
    }

    UpdateLockService(Context context) {
        mContext = context;
        mLocks = new LockWatcher(new Handler(), "UpdateLocks");

        // Consider just-booting to be a reasonable time to allow
        // interruptions for update installation etc.
        sendLockChangedBroadcast(true);
    }

    void sendLockChangedBroadcast(boolean state) {
        // Safe early during boot because this broadcast only goes to registered receivers.
        long oldIdent = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(UpdateLock.UPDATE_LOCK_CHANGED)
                    .putExtra(UpdateLock.NOW_IS_CONVENIENT, state)
                    .putExtra(UpdateLock.TIMESTAMP, System.currentTimeMillis())
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(oldIdent);
        }
    }

    @Override
    public void acquireUpdateLock(IBinder token, String tag) throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "acquire(" + token + ") by " + makeTag(tag));
        }

        mContext.enforceCallingOrSelfPermission(PERMISSION, "acquireUpdateLock");
        mLocks.acquire(token, makeTag(tag));
    }

    @Override
    public void releaseUpdateLock(IBinder token) throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "release(" + token + ')');
        }

        mContext.enforceCallingOrSelfPermission(PERMISSION, "releaseUpdateLock");
        mLocks.release(token);
    };

    private String makeTag(String tag) {
        return "{tag=" + tag
                + " uid=" + Binder.getCallingUid()
                + " pid=" + Binder.getCallingPid() + '}';
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump update lock service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        mLocks.dump(pw);
    }
}
