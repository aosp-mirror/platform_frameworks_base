/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.IClipboard;
import android.content.IOnPrimaryClipChangedListener;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Pair;
import android.util.Slog;

import java.util.HashSet;

/**
 * Implementation of the clipboard for copy and paste.
 */
public class ClipboardService extends IClipboard.Stub {
    private final Context mContext;
    private final IActivityManager mAm;
    private final PackageManager mPm;
    private final IBinder mPermissionOwner;

    private final RemoteCallbackList<IOnPrimaryClipChangedListener> mPrimaryClipListeners
            = new RemoteCallbackList<IOnPrimaryClipChangedListener>();

    private ClipData mPrimaryClip;

    private final HashSet<String> mActivePermissionOwners
            = new HashSet<String>();

    /**
     * Instantiates the clipboard.
     */
    public ClipboardService(Context context) {
        mContext = context;
        mAm = ActivityManagerNative.getDefault();
        mPm = context.getPackageManager();
        IBinder permOwner = null;
        try {
            permOwner = mAm.newUriPermissionOwner("clipboard");
        } catch (RemoteException e) {
            Slog.w("clipboard", "AM dead", e);
        }
        mPermissionOwner = permOwner;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            Slog.w("clipboard", "Exception: ", e);
            throw e;
        }
        
    }

    public void setPrimaryClip(ClipData clip) {
        synchronized (this) {
            if (clip != null && clip.getItemCount() <= 0) {
                throw new IllegalArgumentException("No items");
            }
            checkDataOwnerLocked(clip, Binder.getCallingUid());
            clearActiveOwnersLocked();
            mPrimaryClip = clip;
            final int n = mPrimaryClipListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mPrimaryClipListeners.getBroadcastItem(i).dispatchPrimaryClipChanged();
                } catch (RemoteException e) {

                    // The RemoteCallbackList will take care of removing
                    // the dead object for us.
                }
            }
            mPrimaryClipListeners.finishBroadcast();
        }
    }
    
    public ClipData getPrimaryClip(String pkg) {
        synchronized (this) {
            addActiveOwnerLocked(Binder.getCallingUid(), pkg);
            return mPrimaryClip;
        }
    }

    public ClipDescription getPrimaryClipDescription() {
        synchronized (this) {
            return mPrimaryClip != null ? mPrimaryClip.getDescription() : null;
        }
    }

    public boolean hasPrimaryClip() {
        synchronized (this) {
            return mPrimaryClip != null;
        }
    }

    public void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
        synchronized (this) {
            mPrimaryClipListeners.register(listener);
        }
    }

    public void removePrimaryClipChangedListener(IOnPrimaryClipChangedListener listener) {
        synchronized (this) {
            mPrimaryClipListeners.unregister(listener);
        }
    }

    public boolean hasClipboardText() {
        synchronized (this) {
            if (mPrimaryClip != null) {
                CharSequence text = mPrimaryClip.getItemAt(0).getText();
                return text != null && text.length() > 0;
            }
            return false;
        }
    }

    private final void checkUriOwnerLocked(Uri uri, int uid) {
        if (!"content".equals(uri.getScheme())) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        boolean allowed = false;
        try {
            // This will throw SecurityException for us.
            mAm.checkGrantUriPermission(uid, null, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void checkItemOwnerLocked(ClipData.Item item, int uid) {
        if (item.getUri() != null) {
            checkUriOwnerLocked(item.getUri(), uid);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            checkUriOwnerLocked(intent.getData(), uid);
        }
    }

    private final void checkDataOwnerLocked(ClipData data, int uid) {
        final int N = data.getItemCount();
        for (int i=0; i<N; i++) {
            checkItemOwnerLocked(data.getItemAt(i), uid);
        }
    }

    private final void grantUriLocked(Uri uri, String pkg) {
        long ident = Binder.clearCallingIdentity();
        try {
            mAm.grantUriPermissionFromOwner(mPermissionOwner, Process.myUid(), pkg, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void grantItemLocked(ClipData.Item item, String pkg) {
        if (item.getUri() != null) {
            grantUriLocked(item.getUri(), pkg);
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            grantUriLocked(intent.getData(), pkg);
        }
    }

    private final void addActiveOwnerLocked(int uid, String pkg) {
        PackageInfo pi;
        try {
            pi = mPm.getPackageInfo(pkg, 0);
            if (pi.applicationInfo.uid != uid) {
                throw new SecurityException("Calling uid " + uid
                        + " does not own package " + pkg);
            }
        } catch (NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown package " + pkg, e);
        }
        if (mPrimaryClip != null && !mActivePermissionOwners.contains(pkg)) {
            final int N = mPrimaryClip.getItemCount();
            for (int i=0; i<N; i++) {
                grantItemLocked(mPrimaryClip.getItemAt(i), pkg);
            }
            mActivePermissionOwners.add(pkg);
        }
    }

    private final void revokeUriLocked(Uri uri) {
        long ident = Binder.clearCallingIdentity();
        try {
            mAm.revokeUriPermissionFromOwner(mPermissionOwner, uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private final void revokeItemLocked(ClipData.Item item) {
        if (item.getUri() != null) {
            revokeUriLocked(item.getUri());
        }
        Intent intent = item.getIntent();
        if (intent != null && intent.getData() != null) {
            revokeUriLocked(intent.getData());
        }
    }

    private final void clearActiveOwnersLocked() {
        mActivePermissionOwners.clear();
        if (mPrimaryClip == null) {
            return;
        }
        final int N = mPrimaryClip.getItemCount();
        for (int i=0; i<N; i++) {
            revokeItemLocked(mPrimaryClip.getItemAt(i));
        }
    }
}
