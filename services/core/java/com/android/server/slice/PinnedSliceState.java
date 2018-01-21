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

import android.app.slice.ISliceListener;
import android.app.slice.Slice;
import android.app.slice.SliceProvider;
import android.app.slice.SliceSpec;
import android.content.ContentProviderClient;
import android.net.Uri;
import android.os.Bundle;
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
    private final ArraySet<ISliceListener> mListeners = new ArraySet<>();
    @GuardedBy("mLock")
    private SliceSpec[] mSupportedSpecs = null;
    @GuardedBy("mLock")
    private final ArrayMap<ISliceListener, String> mPkgMap = new ArrayMap<>();

    public PinnedSliceState(SliceManagerService service, Uri uri) {
        mService = service;
        mUri = uri;
        mService.getHandler().post(this::handleSendPinned);
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
        mService.getHandler().post(this::handleSendUnpinned);
    }

    public void onChange() {
        mService.getHandler().post(this::handleBind);
    }

    public void addSliceListener(ISliceListener listener, String pkg, SliceSpec[] specs) {
        synchronized (mLock) {
            if (mListeners.add(listener) && mListeners.size() == 1) {
                mService.listen(mUri);
            }
            mPkgMap.put(listener, pkg);
            mergeSpecs(specs);
        }
    }

    public boolean removeSliceListener(ISliceListener listener) {
        synchronized (mLock) {
            mPkgMap.remove(listener);
            if (mListeners.remove(listener) && mListeners.size() == 0) {
                mService.unlisten(mUri);
            }
        }
        return !isPinned();
    }

    public void pin(String pkg, SliceSpec[] specs) {
        synchronized (mLock) {
            mPinnedPkgs.add(pkg);
            mergeSpecs(specs);
        }
    }

    public boolean unpin(String pkg) {
        synchronized (mLock) {
            mPinnedPkgs.remove(pkg);
        }
        return !isPinned();
    }

    public boolean isListening() {
        synchronized (mLock) {
            return !mListeners.isEmpty();
        }
    }

    @VisibleForTesting
    public boolean isPinned() {
        synchronized (mLock) {
            return !mPinnedPkgs.isEmpty() || !mListeners.isEmpty();
        }
    }

    ContentProviderClient getClient() {
        ContentProviderClient client =
                mService.getContext().getContentResolver().acquireContentProviderClient(mUri);
        client.setDetectNotResponding(SLICE_TIMEOUT);
        return client;
    }

    private void handleBind() {
        Slice cachedSlice = doBind(null);
        synchronized (mLock) {
            mListeners.removeIf(l -> {
                Slice s = cachedSlice;
                if (s == null || s.hasHint(Slice.HINT_CALLER_NEEDED)) {
                    s = doBind(mPkgMap.get(l));
                }
                if (s == null) {
                    return true;
                }
                try {
                    l.onSliceUpdated(s);
                    return false;
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to notify slice " + mUri, e);
                    return true;
                }
            });
            if (!isPinned()) {
                // All the listeners died, remove from pinned state.
                mService.removePinnedSlice(mUri);
            }
        }
    }

    private Slice doBind(String overridePkg) {
        try (ContentProviderClient client = getClient()) {
            Bundle extras = new Bundle();
            extras.putParcelable(SliceProvider.EXTRA_BIND_URI, mUri);
            extras.putParcelableArrayList(SliceProvider.EXTRA_SUPPORTED_SPECS,
                    new ArrayList<>(Arrays.asList(mSupportedSpecs)));
            extras.putString(SliceProvider.EXTRA_OVERRIDE_PKG, overridePkg);
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
        }
    }

    private void handleSendPinned() {
        try (ContentProviderClient client = getClient()) {
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
            Bundle b = new Bundle();
            b.putParcelable(SliceProvider.EXTRA_BIND_URI, mUri);
            try {
                client.call(SliceProvider.METHOD_UNPIN, null, b);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to contact " + mUri, e);
            }
        }
    }
}
