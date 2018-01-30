/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.slice;

import static android.app.slice.SliceManager.PERMISSION_GRANTED;

import android.app.slice.ISliceListener;
import android.app.slice.Slice;
import android.app.slice.SliceProvider;
import android.app.slice.SliceSpec;
import android.content.ContentProviderClient;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Manages the state of a pinned slice.
 */
public class PinnedSliceState {

    private static final long SLICE_TIMEOUT = 5000;
    private static final String TAG = "PinnedSliceState";

    private final Object mLock;

    private final SliceManagerService mService;
    private final Uri mUri;
    @GuardedBy("mLock")
    private final ArraySet<String> mPinnedPkgs = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ListenerInfo> mListeners = new ArrayMap<>();
    @GuardedBy("mLock")
    private SliceSpec[] mSupportedSpecs = null;

    private final DeathRecipient mDeathRecipient = this::handleRecheckListeners;
    private boolean mSlicePinned;

    public PinnedSliceState(SliceManagerService service, Uri uri) {
        mService = service;
        mUri = uri;
        mLock = mService.getLock();
    }

    public SliceSpec[] getSpecs() {
        return mSupportedSpecs;
    }

    public void mergeSpecs(SliceSpec[] supportedSpecs) {
        synchronized (mLock) {
            if (mSupportedSpecs == null) {
                mSupportedSpecs = supportedSpecs;
            } else {
                List<SliceSpec> specs = Arrays.asList(mSupportedSpecs);
                mSupportedSpecs = specs.stream().map(s -> {
                    SliceSpec other = findSpec(supportedSpecs, s.getType());
                    if (other == null) return null;
                    if (other.getRevision() < s.getRevision()) {
                        return other;
                    }
                    return s;
                }).filter(s -> s != null).toArray(SliceSpec[]::new);
            }
        }
    }

    private SliceSpec findSpec(SliceSpec[] specs, String type) {
        for (SliceSpec spec : specs) {
            if (Objects.equals(spec.getType(), type)) {
                return spec;
            }
        }
        return null;
    }

    public Uri getUri() {
        return mUri;
    }

    public void destroy() {
        setSlicePinned(false);
    }

    public void onChange() {
        mService.getHandler().post(this::handleBind);
    }

    private void setSlicePinned(boolean pinned) {
        synchronized (mLock) {
            if (mSlicePinned == pinned) return;
            mSlicePinned = pinned;
            if (pinned) {
                mService.getHandler().post(this::handleSendPinned);
            } else {
                mService.getHandler().post(this::handleSendUnpinned);
            }
        }
    }

    public void addSliceListener(ISliceListener listener, String pkg, SliceSpec[] specs,
            boolean hasPermission) {
        synchronized (mLock) {
            if (mListeners.size() == 0) {
                mService.listen(mUri);
            }
            try {
                listener.asBinder().linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
            }
            mListeners.put(listener.asBinder(), new ListenerInfo(listener, pkg, hasPermission,
                    Binder.getCallingUid(), Binder.getCallingPid()));
            mergeSpecs(specs);
            setSlicePinned(hasPermission);
        }
    }

    public boolean removeSliceListener(ISliceListener listener) {
        synchronized (mLock) {
            listener.asBinder().unlinkToDeath(mDeathRecipient, 0);
            if (mListeners.containsKey(listener.asBinder()) && mListeners.size() == 1) {
                mService.unlisten(mUri);
            }
            mListeners.remove(listener.asBinder());
        }
        return !hasPinOrListener();
    }

    public void pin(String pkg, SliceSpec[] specs) {
        synchronized (mLock) {
            setSlicePinned(true);
            mPinnedPkgs.add(pkg);
            mergeSpecs(specs);
        }
    }

    public boolean unpin(String pkg) {
        synchronized (mLock) {
            mPinnedPkgs.remove(pkg);
        }
        return !hasPinOrListener();
    }

    public boolean isListening() {
        synchronized (mLock) {
            return !mListeners.isEmpty();
        }
    }

    public void recheckPackage(String pkg) {
        synchronized (mLock) {
            for (int i = 0; i < mListeners.size(); i++) {
                ListenerInfo info = mListeners.valueAt(i);
                if (!info.hasPermission && Objects.equals(info.pkg, pkg)) {
                    mService.getHandler().post(() -> {
                        // This bind lets the app itself participate in the permission grant.
                        Slice s = doBind(info);
                        if (mService.checkAccess(info.pkg, mUri, info.callingUid, info.callingPid)
                                == PERMISSION_GRANTED) {
                            info.hasPermission = true;
                            setSlicePinned(true);
                            try {
                                info.listener.onSliceUpdated(s);
                            } catch (RemoteException e) {
                                checkSelfRemove();
                            }
                        }
                    });
                }
            }
        }
    }

    @VisibleForTesting
    public boolean hasPinOrListener() {
        synchronized (mLock) {
            return !mPinnedPkgs.isEmpty() || !mListeners.isEmpty();
        }
    }

    ContentProviderClient getClient() {
        ContentProviderClient client =
                mService.getContext().getContentResolver().acquireContentProviderClient(mUri);
        if (client == null) return null;
        client.setDetectNotResponding(SLICE_TIMEOUT);
        return client;
    }

    private void checkSelfRemove() {
        if (!hasPinOrListener()) {
            // All the listeners died, remove from pinned state.
            mService.unlisten(mUri);
            mService.removePinnedSlice(mUri);
        }
    }

    private void handleRecheckListeners() {
        if (!hasPinOrListener()) return;
        synchronized (mLock) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                ListenerInfo l = mListeners.valueAt(i);
                if (!l.listener.asBinder().isBinderAlive()) {
                    mListeners.removeAt(i);
                }
            }
            checkSelfRemove();
        }
    }

    private void handleBind() {
        Slice cachedSlice = doBind(null);
        synchronized (mLock) {
            if (!hasPinOrListener()) return;
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                ListenerInfo info = mListeners.valueAt(i);
                Slice s = cachedSlice;
                if (s == null || s.hasHint(Slice.HINT_CALLER_NEEDED)
                        || !info.hasPermission) {
                    s = doBind(info);
                }
                if (s == null) {
                    mListeners.removeAt(i);
                    continue;
                }
                try {
                    info.listener.onSliceUpdated(s);
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to notify slice " + mUri, e);
                    mListeners.removeAt(i);
                    continue;
                }
            }
            checkSelfRemove();
        }
    }

    private Slice doBind(ListenerInfo info) {
        try (ContentProviderClient client = getClient()) {
            if (client == null) return null;
            Bundle extras = new Bundle();
            extras.putParcelable(SliceProvider.EXTRA_BIND_URI, mUri);
            extras.putParcelableArrayList(SliceProvider.EXTRA_SUPPORTED_SPECS,
                    new ArrayList<>(Arrays.asList(mSupportedSpecs)));
            if (info != null) {
                extras.putString(SliceProvider.EXTRA_OVERRIDE_PKG, info.pkg);
                extras.putInt(SliceProvider.EXTRA_OVERRIDE_UID, info.callingUid);
                extras.putInt(SliceProvider.EXTRA_OVERRIDE_PID, info.callingPid);
            }
            final Bundle res;
            try {
                res = client.call(SliceProvider.METHOD_SLICE, null, extras);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to bind slice " + mUri, e);
                return null;
            }
            if (res == null) return null;
            Bundle.setDefusable(res, true);
            return res.getParcelable(SliceProvider.EXTRA_SLICE);
        } catch (Throwable t) {
            // Calling out of the system process, make sure they don't throw anything at us.
            Log.e(TAG, "Caught throwable while binding " + mUri, t);
            return null;
        }
    }

    private void handleSendPinned() {
        try (ContentProviderClient client = getClient()) {
            if (client == null) return;
            Bundle b = new Bundle();
            b.putParcelable(SliceProvider.EXTRA_BIND_URI, mUri);
            try {
                client.call(SliceProvider.METHOD_PIN, null, b);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to contact " + mUri, e);
            }
        }
    }

    private void handleSendUnpinned() {
        try (ContentProviderClient client = getClient()) {
            if (client == null) return;
            Bundle b = new Bundle();
            b.putParcelable(SliceProvider.EXTRA_BIND_URI, mUri);
            try {
                client.call(SliceProvider.METHOD_UNPIN, null, b);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to contact " + mUri, e);
            }
        }
    }

    private class ListenerInfo {

        private ISliceListener listener;
        private String pkg;
        private boolean hasPermission;
        private int callingUid;
        private int callingPid;

        public ListenerInfo(ISliceListener listener, String pkg, boolean hasPermission,
                int callingUid, int callingPid) {
            this.listener = listener;
            this.pkg = pkg;
            this.hasPermission = hasPermission;
            this.callingUid = callingUid;
            this.callingPid = callingPid;
        }
    }
}
