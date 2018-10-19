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
    private final String mPkg;
    @GuardedBy("mLock")
    private SliceSpec[] mSupportedSpecs = null;

    private final DeathRecipient mDeathRecipient = this::handleRecheckListeners;
    private boolean mSlicePinned;

    public PinnedSliceState(SliceManagerService service, Uri uri, String pkg) {
        mService = service;
        mUri = uri;
        mPkg = pkg;
        mLock = mService.getLock();
    }

    public String getPkg() {
        return mPkg;
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

    public void pin(String pkg, SliceSpec[] specs, IBinder token) {
        synchronized (mLock) {
            mListeners.put(token, new ListenerInfo(token, pkg, true,
                    Binder.getCallingUid(), Binder.getCallingPid()));
            try {
                token.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
            }
            mergeSpecs(specs);
            setSlicePinned(true);
        }
    }

    public boolean unpin(String pkg, IBinder token) {
        synchronized (mLock) {
            token.unlinkToDeath(mDeathRecipient, 0);
            mListeners.remove(token);
        }
        return !hasPinOrListener();
    }

    public boolean isListening() {
        synchronized (mLock) {
            return !mListeners.isEmpty();
        }
    }

    @VisibleForTesting
    public boolean hasPinOrListener() {
        synchronized (mLock) {
            return !mPinnedPkgs.isEmpty() || !mListeners.isEmpty();
        }
    }

    ContentProviderClient getClient() {
        ContentProviderClient client = mService.getContext().getContentResolver()
                .acquireUnstableContentProviderClient(mUri);
        if (client == null) return null;
        client.setDetectNotResponding(SLICE_TIMEOUT);
        return client;
    }

    private void checkSelfRemove() {
        if (!hasPinOrListener()) {
            // All the listeners died, remove from pinned state.
            mService.removePinnedSlice(mUri);
        }
    }

    private void handleRecheckListeners() {
        if (!hasPinOrListener()) return;
        synchronized (mLock) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                ListenerInfo l = mListeners.valueAt(i);
                if (!l.token.isBinderAlive()) {
                    mListeners.removeAt(i);
                }
            }
            checkSelfRemove();
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

        private IBinder token;
        private String pkg;
        private boolean hasPermission;
        private int callingUid;
        private int callingPid;

        public ListenerInfo(IBinder token, String pkg, boolean hasPermission,
                int callingUid, int callingPid) {
            this.token = token;
            this.pkg = pkg;
            this.hasPermission = hasPermission;
            this.callingUid = callingUid;
            this.callingPid = callingPid;
        }
    }
}
