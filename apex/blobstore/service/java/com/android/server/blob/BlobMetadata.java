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
package com.android.server.blob;

import static android.system.OsConstants.O_RDONLY;

import android.annotation.NonNull;
import android.app.blob.BlobHandle;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RevocableFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;

class BlobMetadata {
    private final Object mMetadataLock = new Object();

    private final Context mContext;
    private final long mBlobId;
    private final BlobHandle mBlobHandle;

    @GuardedBy("mMetadataLock")
    private final ArraySet<Committer> mCommitters = new ArraySet<>();

    @GuardedBy("mMetadataLock")
    private final ArraySet<Leasee> mLeasees = new ArraySet<>();

    /**
     * Contains packageName -> {RevocableFileDescriptors}.
     *
     * Keep track of RevocableFileDescriptors given to clients which are not yet revoked/closed so
     * that when clients access is revoked or the blob gets deleted, we can be sure that clients
     * do not have any reference to the blob and the space occupied by the blob can be freed.
     */
    @GuardedBy("mRevocableFds")
    private final ArrayMap<String, ArraySet<RevocableFileDescriptor>> mRevocableFds =
            new ArrayMap<>();

    BlobMetadata(Context context, long blobId, BlobHandle blobHandle) {
        mContext = context;
        mBlobId = blobId;
        mBlobHandle = blobHandle;
    }

    void addCommitter(String packageName, int uid, BlobAccessMode blobAccessMode) {
        synchronized (mMetadataLock) {
            mCommitters.add(new Committer(packageName, uid, blobAccessMode));
        }
    }

    void addLeasee(String callingPackage, int callingUid,
            int descriptionResId, long leaseExpiryTimeMillis) {
        synchronized (mMetadataLock) {
            mLeasees.add(new Leasee(callingPackage, callingUid,
                    descriptionResId, leaseExpiryTimeMillis));
        }
    }

    void removeLeasee(String packageName, int uid) {
        synchronized (mMetadataLock) {
            mLeasees.remove(new Accessor(packageName, uid));
        }
    }

    boolean isAccessAllowedForCaller(String callingPackage, int callingUid) {
        // TODO: verify blob is still valid (expiryTime is not elapsed)
        synchronized (mMetadataLock) {
            // Check if packageName already holds a lease on the blob.
            for (int i = 0, size = mLeasees.size(); i < size; ++i) {
                final Leasee leasee = mLeasees.valueAt(i);
                if (leasee.equals(callingPackage, callingUid)
                        && leasee.isStillValid()) {
                    return true;
                }
            }

            for (int i = 0, size = mCommitters.size(); i < size; ++i) {
                final Committer committer = mCommitters.valueAt(i);

                // Check if the caller is the same package that committed the blob.
                if (committer.equals(callingPackage, callingUid)) {
                    return true;
                }

                // Check if the caller is allowed access as per the access mode specified
                // by the committer.
                if (committer.blobAccessMode.isAccessAllowedForCaller(mContext,
                        callingPackage, committer.packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    ParcelFileDescriptor openForRead(String callingPackage) throws IOException {
        // TODO: Add limit on opened fds
        FileDescriptor fd;
        try {
            fd = Os.open(BlobStoreConfig.getBlobFile(mBlobId).getPath(), O_RDONLY, 0);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
        synchronized (mMetadataLock) {
            return createRevocableFdLocked(fd, callingPackage);
        }
    }

    @GuardedBy("mMetadataLock")
    @NonNull
    private ParcelFileDescriptor createRevocableFdLocked(FileDescriptor fd,
            String callingPackage) throws IOException {
        final RevocableFileDescriptor revocableFd =
                new RevocableFileDescriptor(mContext, fd);
        synchronized (mRevocableFds) {
            ArraySet<RevocableFileDescriptor> revocableFdsForPkg =
                    mRevocableFds.get(callingPackage);
            if (revocableFdsForPkg == null) {
                revocableFdsForPkg = new ArraySet<>();
                mRevocableFds.put(callingPackage, revocableFdsForPkg);
            }
            revocableFdsForPkg.add(revocableFd);
        }
        revocableFd.addOnCloseListener((e) -> {
            synchronized (mRevocableFds) {
                final ArraySet<RevocableFileDescriptor> revocableFdsForPkg =
                        mRevocableFds.get(callingPackage);
                if (revocableFdsForPkg != null) {
                    revocableFdsForPkg.remove(revocableFd);
                }
            }
        });
        return revocableFd.getRevocableFileDescriptor();
    }

    static final class Committer extends Accessor {
        public final BlobAccessMode blobAccessMode;

        Committer(String packageName, int uid, BlobAccessMode blobAccessMode) {
            super(packageName, uid);
            this.blobAccessMode = blobAccessMode;
        }
    }

    static final class Leasee extends Accessor {
        public final int descriptionResId;
        public final long expiryTimeMillis;

        Leasee(String packageName, int uid, int descriptionResId, long expiryTimeMillis) {
            super(packageName, uid);
            this.descriptionResId = descriptionResId;
            this.expiryTimeMillis = expiryTimeMillis;
        }

        boolean isStillValid() {
            return expiryTimeMillis == 0 || expiryTimeMillis <= System.currentTimeMillis();
        }
    }

    static class Accessor {
        public final String packageName;
        public final int uid;

        Accessor(String packageName, int uid) {
            this.packageName = packageName;
            this.uid = uid;
        }

        public boolean equals(String packageName, int uid) {
            return this.uid == uid && this.packageName.equals(packageName);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || !(obj instanceof Accessor)) {
                return false;
            }
            final Accessor other = (Accessor) obj;
            return this.uid == other.uid && this.packageName.equals(other.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, uid);
        }
    }
}
