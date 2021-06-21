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

package com.android.server.pm;

import android.annotation.NonNull;
import android.content.IntentFilter;

import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;

public class PersistentPreferredIntentResolver
        extends WatchedIntentResolver<PersistentPreferredActivity, PersistentPreferredActivity>
        implements Snappable {
    @Override
    protected PersistentPreferredActivity[] newArray(int size) {
        return new PersistentPreferredActivity[size];
    }

    @Override
    protected IntentFilter getIntentFilter(@NonNull PersistentPreferredActivity input) {
        return input.getIntentFilter();
    }

    @Override
    protected boolean isPackageForFilter(String packageName, PersistentPreferredActivity filter) {
        return packageName.equals(filter.mComponent.getPackageName());
    }

    public PersistentPreferredIntentResolver() {
        super();
        mSnapshot = makeCache();
    }

    // Take the snapshot of F
    protected PersistentPreferredActivity snapshot(PersistentPreferredActivity f) {
        return (f == null) ? null : f.snapshot();
    }

    // Copy constructor used only to create a snapshot.
    private PersistentPreferredIntentResolver(PersistentPreferredIntentResolver f) {
        copyFrom(f);
        mSnapshot = new SnapshotCache.Sealed();
    }

    // The cache for snapshots, so they are not rebuilt if the base object has not
    // changed.
    final SnapshotCache<PersistentPreferredIntentResolver> mSnapshot;

    private SnapshotCache makeCache() {
        return new SnapshotCache<PersistentPreferredIntentResolver>(this, this) {
            @Override
            public PersistentPreferredIntentResolver createSnapshot() {
                return new PersistentPreferredIntentResolver(mSource);
            }};
    }

    /**
     * Return a snapshot of the current object.  The snapshot is a read-only copy suitable
     * for read-only methods.
     * @return A snapshot of the current object.
     */
    public PersistentPreferredIntentResolver snapshot() {
        return mSnapshot.snapshot();
    }
}
