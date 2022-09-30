/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.local;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Binder;
import android.os.UserHandle;

import com.android.server.pm.Computer;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.snapshot.PackageDataSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** @hide */
public class PackageManagerLocalImpl implements PackageManagerLocal {

    private final PackageManagerService mService;

    public PackageManagerLocalImpl(PackageManagerService service) {
        mService = service;
    }

    @Override
    public void reconcileSdkData(@Nullable String volumeUuid, @NonNull String packageName,
            @NonNull List<String> subDirNames, int userId, int appId, int previousAppId,
            @NonNull String seInfo, int flags) throws IOException {
        mService.reconcileSdkData(volumeUuid, packageName, subDirNames, userId, appId,
                previousAppId, seInfo, flags);
    }

    @NonNull
    @Override
    public UnfilteredSnapshotImpl withUnfilteredSnapshot() {
        return new UnfilteredSnapshotImpl(mService.snapshotComputer(false /*allowLiveComputer*/));
    }

    @NonNull
    @Override
    public FilteredSnapshotImpl withFilteredSnapshot() {
        return withFilteredSnapshot(Binder.getCallingUid(), Binder.getCallingUserHandle());
    }

    @NonNull
    @Override
    public FilteredSnapshotImpl withFilteredSnapshot(int callingUid, @NonNull UserHandle user) {
        return new FilteredSnapshotImpl(callingUid, user,
                mService.snapshotComputer(false /*allowLiveComputer*/), null);
    }

    private abstract static class BaseSnapshotImpl implements AutoCloseable {

        private boolean mClosed;

        @NonNull
        protected Computer mSnapshot;

        private BaseSnapshotImpl(@NonNull PackageDataSnapshot snapshot) {
            mSnapshot = (Computer) snapshot;
        }

        @Override
        public void close() {
            mClosed = true;
            mSnapshot = null;
            // TODO: Recycle snapshots?
        }

        @CallSuper
        protected void checkClosed() {
            if (mClosed) {
                throw new IllegalStateException("Snapshot already closed");
            }
        }
    }

    private static class UnfilteredSnapshotImpl extends BaseSnapshotImpl implements
            UnfilteredSnapshot {

        private UnfilteredSnapshotImpl(@NonNull PackageDataSnapshot snapshot) {
            super(snapshot);
        }

        @Override
        public FilteredSnapshot filtered(int callingUid, @NonNull UserHandle user) {
            return new FilteredSnapshotImpl(callingUid, user, mSnapshot, this);
        }

        @SuppressWarnings("RedundantSuppression")
        @NonNull
        @Override
        public Map<String, PackageState> getPackageStates() {
            checkClosed();

            //noinspection unchecked, RedundantCast
            return (Map<String, PackageState>) (Map<?, ?>) mSnapshot.getPackageStates();
        }
    }

    private static class FilteredSnapshotImpl extends BaseSnapshotImpl implements
            FilteredSnapshot {

        private final int mCallingUid;

        @UserIdInt
        private final int mUserId;

        @Nullable
        private ArrayList<PackageState> mFilteredPackageStates;

        @Nullable
        private final UnfilteredSnapshotImpl mParentSnapshot;

        private FilteredSnapshotImpl(int callingUid, @NonNull UserHandle user,
                @NonNull PackageDataSnapshot snapshot,
                @Nullable UnfilteredSnapshotImpl parentSnapshot) {
            super(snapshot);
            mCallingUid = callingUid;
            mUserId = user.getIdentifier();
            mParentSnapshot = parentSnapshot;
        }

        @Override
        protected void checkClosed() {
            if (mParentSnapshot != null) {
                mParentSnapshot.checkClosed();
            }

            super.checkClosed();
        }

        @Nullable
        @Override
        public PackageState getPackageState(@NonNull String packageName) {
            checkClosed();
            return mSnapshot.getPackageStateFiltered(packageName, mCallingUid, mUserId);
        }

        @Override
        public void forAllPackageStates(@NonNull Consumer<PackageState> consumer) {
            checkClosed();

            if (mFilteredPackageStates == null) {
                var packageStates = mSnapshot.getPackageStates();
                var filteredPackageStates = new ArrayList<PackageState>();
                for (int index = 0, size = packageStates.size(); index < size; index++) {
                    var packageState = packageStates.valueAt(index);
                    if (!mSnapshot.shouldFilterApplication(packageState, mCallingUid, mUserId)) {
                        filteredPackageStates.add(packageState);
                    }
                }
                mFilteredPackageStates = filteredPackageStates;
            }

            for (int index = 0, size = mFilteredPackageStates.size(); index < size; index++) {
                var packageState = mFilteredPackageStates.get(index);
                consumer.accept(packageState);
            }
        }
    }
}
