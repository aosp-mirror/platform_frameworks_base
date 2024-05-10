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

import static android.Manifest.permission.ACCESS_BLOBS_ACROSS_USERS;
import static android.app.blob.XmlTags.ATTR_COMMIT_TIME_MS;
import static android.app.blob.XmlTags.ATTR_DESCRIPTION;
import static android.app.blob.XmlTags.ATTR_DESCRIPTION_RES_NAME;
import static android.app.blob.XmlTags.ATTR_EXPIRY_TIME;
import static android.app.blob.XmlTags.ATTR_ID;
import static android.app.blob.XmlTags.ATTR_PACKAGE;
import static android.app.blob.XmlTags.ATTR_UID;
import static android.app.blob.XmlTags.ATTR_USER_ID;
import static android.app.blob.XmlTags.TAG_ACCESS_MODE;
import static android.app.blob.XmlTags.TAG_BLOB_HANDLE;
import static android.app.blob.XmlTags.TAG_COMMITTER;
import static android.app.blob.XmlTags.TAG_LEASEE;
import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.O_RDONLY;
import static android.text.format.Formatter.FLAG_IEC_UNITS;
import static android.text.format.Formatter.formatFileSize;

import static com.android.server.blob.BlobStoreConfig.TAG;
import static com.android.server.blob.BlobStoreConfig.XML_VERSION_ADD_COMMIT_TIME;
import static com.android.server.blob.BlobStoreConfig.XML_VERSION_ADD_DESC_RES_NAME;
import static com.android.server.blob.BlobStoreConfig.XML_VERSION_ADD_STRING_DESC;
import static com.android.server.blob.BlobStoreConfig.XML_VERSION_ALLOW_ACCESS_ACROSS_USERS;
import static com.android.server.blob.BlobStoreConfig.hasLeaseWaitTimeElapsed;
import static com.android.server.blob.BlobStoreUtils.getDescriptionResourceId;
import static com.android.server.blob.BlobStoreUtils.getPackageResources;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.blob.BlobHandle;
import android.app.blob.LeaseInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ResourceId;
import android.content.res.Resources;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RevocableFileDescriptor;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StatsEvent;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;
import com.android.server.blob.BlobStoreManagerService.DumpArgs;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

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
     * Contains Accessor -> {RevocableFileDescriptors}.
     *
     * Keep track of RevocableFileDescriptors given to clients which are not yet revoked/closed so
     * that when clients access is revoked or the blob gets deleted, we can be sure that clients
     * do not have any reference to the blob and the space occupied by the blob can be freed.
     */
    @GuardedBy("mRevocableFds")
    private final ArrayMap<Accessor, ArraySet<RevocableFileDescriptor>> mRevocableFds =
            new ArrayMap<>();

    // Do not access this directly, instead use #getBlobFile().
    private File mBlobFile;

    BlobMetadata(Context context, long blobId, BlobHandle blobHandle) {
        mContext = context;
        this.mBlobId = blobId;
        this.mBlobHandle = blobHandle;
    }

    long getBlobId() {
        return mBlobId;
    }

    BlobHandle getBlobHandle() {
        return mBlobHandle;
    }

    void addOrReplaceCommitter(@NonNull Committer committer) {
        synchronized (mMetadataLock) {
            // We need to override the committer data, so first remove any existing
            // committer before adding the new one.
            mCommitters.remove(committer);
            mCommitters.add(committer);
        }
    }

    void setCommitters(ArraySet<Committer> committers) {
        synchronized (mMetadataLock) {
            mCommitters.clear();
            mCommitters.addAll(committers);
        }
    }

    void removeCommitter(@NonNull String packageName, int uid) {
        synchronized (mMetadataLock) {
            mCommitters.removeIf((committer) ->
                    committer.uid == uid && committer.packageName.equals(packageName));
        }
    }

    void removeCommitter(@NonNull Committer committer) {
        synchronized (mMetadataLock) {
            mCommitters.remove(committer);
        }
    }

    void removeCommittersFromUnknownPkgs(SparseArray<SparseArray<String>> knownPackages) {
        synchronized (mMetadataLock) {
            mCommitters.removeIf(committer -> {
                final int userId = UserHandle.getUserId(committer.uid);
                final SparseArray<String> userPackages = knownPackages.get(userId);
                if (userPackages == null) {
                    return true;
                }
                return !committer.packageName.equals(userPackages.get(committer.uid));
            });
        }
    }

    void addCommittersAndLeasees(BlobMetadata blobMetadata) {
        mCommitters.addAll(blobMetadata.mCommitters);
        mLeasees.addAll(blobMetadata.mLeasees);
    }

    @Nullable
    Committer getExistingCommitter(@NonNull String packageName, int uid) {
        synchronized (mCommitters) {
            for (int i = 0, size = mCommitters.size(); i < size; ++i) {
                final Committer committer = mCommitters.valueAt(i);
                if (committer.uid == uid && committer.packageName.equals(packageName)) {
                    return committer;
                }
            }
        }
        return null;
    }

    void addOrReplaceLeasee(String callingPackage, int callingUid, int descriptionResId,
            CharSequence description, long leaseExpiryTimeMillis) {
        synchronized (mMetadataLock) {
            // We need to override the leasee data, so first remove any existing
            // leasee before adding the new one.
            final Leasee leasee = new Leasee(mContext, callingPackage, callingUid,
                    descriptionResId, description, leaseExpiryTimeMillis);
            mLeasees.remove(leasee);
            mLeasees.add(leasee);
        }
    }

    void setLeasees(ArraySet<Leasee> leasees) {
        synchronized (mMetadataLock) {
            mLeasees.clear();
            mLeasees.addAll(leasees);
        }
    }

    void removeLeasee(String packageName, int uid) {
        synchronized (mMetadataLock) {
            mLeasees.removeIf((leasee) ->
                    leasee.uid == uid && leasee.packageName.equals(packageName));
        }
    }

    void removeLeaseesFromUnknownPkgs(SparseArray<SparseArray<String>> knownPackages) {
        synchronized (mMetadataLock) {
            mLeasees.removeIf(leasee -> {
                final int userId = UserHandle.getUserId(leasee.uid);
                final SparseArray<String> userPackages = knownPackages.get(userId);
                if (userPackages == null) {
                    return true;
                }
                return !leasee.packageName.equals(userPackages.get(leasee.uid));
            });
        }
    }

    void removeExpiredLeases() {
        synchronized (mMetadataLock) {
            mLeasees.removeIf(leasee -> !leasee.isStillValid());
        }
    }

    void removeDataForUser(int userId) {
        synchronized (mMetadataLock) {
            mCommitters.removeIf(committer -> (userId == UserHandle.getUserId(committer.uid)));
            mLeasees.removeIf(leasee -> (userId == UserHandle.getUserId(leasee.uid)));
            mRevocableFds.entrySet().removeIf(entry -> {
                final Accessor accessor = entry.getKey();
                final ArraySet<RevocableFileDescriptor> rFds = entry.getValue();
                if (userId != UserHandle.getUserId(accessor.uid)) {
                    return false;
                }
                for (int i = 0, fdCount = rFds.size(); i < fdCount; ++i) {
                    rFds.valueAt(i).revoke();
                }
                rFds.clear();
                return true;
            });
        }
    }

    boolean hasValidLeases() {
        synchronized (mMetadataLock) {
            for (int i = 0, size = mLeasees.size(); i < size; ++i) {
                if (mLeasees.valueAt(i).isStillValid()) {
                    return true;
                }
            }
            return false;
        }
    }

    long getSize() {
        return getBlobFile().length();
    }

    boolean isAccessAllowedForCaller(@NonNull String callingPackage, int callingUid) {
        // Don't allow the blob to be accessed after it's expiry time has passed.
        if (getBlobHandle().isExpired()) {
            return false;
        }
        synchronized (mMetadataLock) {
            // Check if packageName already holds a lease on the blob.
            for (int i = 0, size = mLeasees.size(); i < size; ++i) {
                final Leasee leasee = mLeasees.valueAt(i);
                if (leasee.isStillValid() && leasee.equals(callingPackage, callingUid)) {
                    return true;
                }
            }

            final int callingUserId = UserHandle.getUserId(callingUid);
            for (int i = 0, size = mCommitters.size(); i < size; ++i) {
                final Committer committer = mCommitters.valueAt(i);
                if (callingUserId != UserHandle.getUserId(committer.uid)) {
                    continue;
                }

                // Check if the caller is the same package that committed the blob.
                if (committer.equals(callingPackage, callingUid)) {
                    return true;
                }

                // Check if the caller is allowed access as per the access mode specified
                // by the committer.
                if (committer.blobAccessMode.isAccessAllowedForCaller(mContext,
                        callingPackage, callingUid, committer.uid)) {
                    return true;
                }
            }

            final boolean canCallerAccessBlobsAcrossUsers =
                    checkCallerCanAccessBlobsAcrossUsers(callingUid);
            if (!canCallerAccessBlobsAcrossUsers) {
                return false;
            }
            for (int i = 0, size = mCommitters.size(); i < size; ++i) {
                final Committer committer = mCommitters.valueAt(i);
                final int committerUserId = UserHandle.getUserId(committer.uid);
                if (callingUserId == committerUserId) {
                    continue;
                }
                if (!isPackageInstalledOnUser(callingPackage, committerUserId)) {
                    continue;
                }

                // Check if the caller is allowed access as per the access mode specified
                // by the committer.
                if (committer.blobAccessMode.isAccessAllowedForCaller(mContext,
                        callingPackage, callingUid, committer.uid)) {
                    return true;
                }
            }

        }
        return false;
    }

    private boolean checkCallerCanAccessBlobsAcrossUsers(int callingUid) {
        final long token = Binder.clearCallingIdentity();
        try {
            return mContext.checkPermission(ACCESS_BLOBS_ACROSS_USERS,
                    Process.INVALID_PID, callingUid) == PackageManager.PERMISSION_GRANTED;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isPackageInstalledOnUser(String packageName, int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.getPackageManager().getPackageInfoAsUser(packageName, 0, userId);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    boolean hasACommitterOrLeaseeInUser(int userId) {
        return hasACommitterInUser(userId) || hasALeaseeInUser(userId);
    }

    boolean hasACommitterInUser(int userId) {
        synchronized (mMetadataLock) {
            for (int i = 0, size = mCommitters.size(); i < size; ++i) {
                final Committer committer = mCommitters.valueAt(i);
                if (userId == UserHandle.getUserId(committer.uid)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasALeaseeInUser(int userId) {
        synchronized (mMetadataLock) {
            for (int i = 0, size = mLeasees.size(); i < size; ++i) {
                final Leasee leasee = mLeasees.valueAt(i);
                if (userId == UserHandle.getUserId(leasee.uid)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isACommitter(@NonNull String packageName, int uid) {
        synchronized (mMetadataLock) {
            return isAnAccessor(mCommitters, packageName, uid, UserHandle.getUserId(uid));
        }
    }

    boolean isALeasee(@Nullable String packageName, int uid) {
        synchronized (mMetadataLock) {
            final Leasee leasee = getAccessor(mLeasees, packageName, uid,
                    UserHandle.getUserId(uid));
            return leasee != null && leasee.isStillValid();
        }
    }

    private boolean isALeaseeInUser(@Nullable String packageName, int uid, int userId) {
        synchronized (mMetadataLock) {
            final Leasee leasee = getAccessor(mLeasees, packageName, uid, userId);
            return leasee != null && leasee.isStillValid();
        }
    }

    private static <T extends Accessor> boolean isAnAccessor(@NonNull ArraySet<T> accessors,
            @Nullable String packageName, int uid, int userId) {
        // Check if the package is an accessor of the data blob.
        return getAccessor(accessors, packageName, uid, userId) != null;
    }

    private static <T extends Accessor> T getAccessor(@NonNull ArraySet<T> accessors,
            @Nullable String packageName, int uid, int userId) {
        // Check if the package is an accessor of the data blob.
        for (int i = 0, size = accessors.size(); i < size; ++i) {
            final Accessor accessor = accessors.valueAt(i);
            if (packageName != null && uid != INVALID_UID
                    && accessor.equals(packageName, uid)) {
                return (T) accessor;
            } else if (packageName != null && accessor.packageName.equals(packageName)
                    && userId == UserHandle.getUserId(accessor.uid)) {
                return (T) accessor;
            } else if (uid != INVALID_UID && accessor.uid == uid) {
                return (T) accessor;
            }
        }
        return null;
    }

    boolean shouldAttributeToUser(int userId) {
        synchronized (mMetadataLock) {
            for (int i = 0, size = mLeasees.size(); i < size; ++i) {
                final Leasee leasee = mLeasees.valueAt(i);
                // Don't attribute the blob to userId if there is a lease on it from another user.
                if (userId != UserHandle.getUserId(leasee.uid)) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean shouldAttributeToLeasee(@NonNull String packageName, int userId,
            boolean callerHasStatsPermission) {
        if (!isALeaseeInUser(packageName, INVALID_UID, userId)) {
            return false;
        }
        if (!callerHasStatsPermission || !hasOtherLeasees(packageName, INVALID_UID, userId)) {
            return true;
        }
        return false;
    }

    boolean shouldAttributeToLeasee(int uid, boolean callerHasStatsPermission) {
        final int userId = UserHandle.getUserId(uid);
        if (!isALeaseeInUser(null, uid, userId)) {
            return false;
        }
        if (!callerHasStatsPermission || !hasOtherLeasees(null, uid, userId)) {
            return true;
        }
        return false;
    }

    private boolean hasOtherLeasees(@Nullable String packageName, int uid, int userId) {
        synchronized (mMetadataLock) {
            for (int i = 0, size = mLeasees.size(); i < size; ++i) {
                final Leasee leasee = mLeasees.valueAt(i);
                if (!leasee.isStillValid()) {
                    continue;
                }
                // TODO: Also exclude packages which are signed with same cert?
                if (packageName != null && uid != INVALID_UID
                        && !leasee.equals(packageName, uid)) {
                    return true;
                } else if (packageName != null && (!leasee.packageName.equals(packageName)
                        || userId != UserHandle.getUserId(leasee.uid))) {
                    return true;
                } else if (uid != INVALID_UID && leasee.uid != uid) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    LeaseInfo getLeaseInfo(@NonNull String packageName, int uid) {
        synchronized (mMetadataLock) {
            for (int i = 0, size = mLeasees.size(); i < size; ++i) {
                final Leasee leasee = mLeasees.valueAt(i);
                if (!leasee.isStillValid()) {
                    continue;
                }
                if (leasee.uid == uid && leasee.packageName.equals(packageName)) {
                    final int descriptionResId = leasee.descriptionResEntryName == null
                            ? Resources.ID_NULL
                            : BlobStoreUtils.getDescriptionResourceId(
                                    mContext, leasee.descriptionResEntryName, leasee.packageName,
                                    UserHandle.getUserId(leasee.uid));
                    return new LeaseInfo(packageName, leasee.expiryTimeMillis,
                            descriptionResId, leasee.description);
                }
            }
        }
        return null;
    }

    void forEachLeasee(Consumer<Leasee> consumer) {
        synchronized (mMetadataLock) {
            mLeasees.forEach(consumer);
        }
    }

    File getBlobFile() {
        if (mBlobFile == null) {
            mBlobFile = BlobStoreConfig.getBlobFile(mBlobId);
        }
        return mBlobFile;
    }

    ParcelFileDescriptor openForRead(String callingPackage, int callingUid) throws IOException {
        // TODO: Add limit on opened fds
        FileDescriptor fd;
        try {
            fd = Os.open(getBlobFile().getPath(), O_RDONLY, 0);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
        try {
            if (BlobStoreConfig.shouldUseRevocableFdForReads()) {
                return createRevocableFd(fd, callingPackage, callingUid);
            } else {
                return new ParcelFileDescriptor(fd);
            }
        } catch (IOException e) {
            IoUtils.closeQuietly(fd);
            throw e;
        }
    }

    @NonNull
    private ParcelFileDescriptor createRevocableFd(FileDescriptor fd,
            String callingPackage, int callingUid) throws IOException {
        final RevocableFileDescriptor revocableFd =
                new RevocableFileDescriptor(mContext, fd, BlobStoreUtils.getRevocableFdHandler());
        final Accessor accessor;
        synchronized (mRevocableFds) {
            accessor = new Accessor(callingPackage, callingUid);
            ArraySet<RevocableFileDescriptor> revocableFdsForAccessor =
                    mRevocableFds.get(accessor);
            if (revocableFdsForAccessor == null) {
                revocableFdsForAccessor = new ArraySet<>();
                mRevocableFds.put(accessor, revocableFdsForAccessor);
            }
            revocableFdsForAccessor.add(revocableFd);
        }
        revocableFd.addOnCloseListener((e) -> {
            synchronized (mRevocableFds) {
                final ArraySet<RevocableFileDescriptor> revocableFdsForAccessor =
                        mRevocableFds.get(accessor);
                if (revocableFdsForAccessor != null) {
                    revocableFdsForAccessor.remove(revocableFd);
                    if (revocableFdsForAccessor.isEmpty()) {
                        mRevocableFds.remove(accessor);
                    }
                }
            }
        });
        return revocableFd.getRevocableFileDescriptor();
    }

    void destroy() {
        revokeAndClearAllFds();
        getBlobFile().delete();
    }

    private void revokeAndClearAllFds() {
        synchronized (mRevocableFds) {
            for (int i = 0, accessorCount = mRevocableFds.size(); i < accessorCount; ++i) {
                final ArraySet<RevocableFileDescriptor> rFds =
                        mRevocableFds.valueAt(i);
                if (rFds == null) {
                    continue;
                }
                for (int j = 0, fdCount = rFds.size(); j < fdCount; ++j) {
                    rFds.valueAt(j).revoke();
                }
            }
            mRevocableFds.clear();
        }
    }

    boolean shouldBeDeleted(boolean respectLeaseWaitTime) {
        // Expired data blobs
        if (getBlobHandle().isExpired()) {
            return true;
        }

        // Blobs with no active leases
        if ((!respectLeaseWaitTime || hasLeaseWaitTimeElapsedForAll())
                && !hasValidLeases()) {
            return true;
        }

        return false;
    }

    @VisibleForTesting
    boolean hasLeaseWaitTimeElapsedForAll() {
        for (int i = 0, size = mCommitters.size(); i < size; ++i) {
            final Committer committer = mCommitters.valueAt(i);
            if (!hasLeaseWaitTimeElapsed(committer.getCommitTimeMs())) {
                return false;
            }
        }
        return true;
    }

    StatsEvent dumpAsStatsEvent(int atomTag) {
        synchronized (mMetadataLock) {
            ProtoOutputStream proto = new ProtoOutputStream();
            // Write Committer data to proto format
            for (int i = 0, size = mCommitters.size(); i < size; ++i) {
                final Committer committer = mCommitters.valueAt(i);
                final long token = proto.start(
                        BlobStatsEventProto.BlobCommitterListProto.COMMITTER);
                proto.write(BlobStatsEventProto.BlobCommitterProto.UID, committer.uid);
                proto.write(BlobStatsEventProto.BlobCommitterProto.COMMIT_TIMESTAMP_MILLIS,
                        committer.commitTimeMs);
                proto.write(BlobStatsEventProto.BlobCommitterProto.ACCESS_MODE,
                        committer.blobAccessMode.getAccessType());
                proto.write(BlobStatsEventProto.BlobCommitterProto.NUM_WHITELISTED_PACKAGE,
                        committer.blobAccessMode.getAllowedPackagesCount());
                proto.end(token);
            }
            final byte[] committersBytes = proto.getBytes();

            proto = new ProtoOutputStream();
            // Write Leasee data to proto format
            for (int i = 0, size = mLeasees.size(); i < size; ++i) {
                final Leasee leasee = mLeasees.valueAt(i);
                final long token = proto.start(BlobStatsEventProto.BlobLeaseeListProto.LEASEE);
                proto.write(BlobStatsEventProto.BlobLeaseeProto.UID, leasee.uid);
                proto.write(BlobStatsEventProto.BlobLeaseeProto.LEASE_EXPIRY_TIMESTAMP_MILLIS,
                        leasee.expiryTimeMillis);
                proto.end(token);
            }
            final byte[] leaseesBytes = proto.getBytes();

            // Construct the StatsEvent to represent this Blob
            return FrameworkStatsLog.buildStatsEvent(atomTag, mBlobId, getSize(),
                    mBlobHandle.getExpiryTimeMillis(), committersBytes, leaseesBytes);
        }
    }

    void dump(IndentingPrintWriter fout, DumpArgs dumpArgs) {
        synchronized (mMetadataLock) {
            fout.println("blobHandle:");
            fout.increaseIndent();
            mBlobHandle.dump(fout, dumpArgs.shouldDumpFull());
            fout.decreaseIndent();
            fout.println("size: " + formatFileSize(mContext, getSize(), FLAG_IEC_UNITS));

            fout.println("Committers:");
            fout.increaseIndent();
            if (mCommitters.isEmpty()) {
                fout.println("<empty>");
            } else {
                for (int i = 0, count = mCommitters.size(); i < count; ++i) {
                    final Committer committer = mCommitters.valueAt(i);
                    fout.println("committer " + committer.toString());
                    fout.increaseIndent();
                    committer.dump(fout);
                    fout.decreaseIndent();
                }
            }
            fout.decreaseIndent();

            fout.println("Leasees:");
            fout.increaseIndent();
            if (mLeasees.isEmpty()) {
                fout.println("<empty>");
            } else {
                for (int i = 0, count = mLeasees.size(); i < count; ++i) {
                    final Leasee leasee = mLeasees.valueAt(i);
                    fout.println("leasee " + leasee.toString());
                    fout.increaseIndent();
                    leasee.dump(mContext, fout);
                    fout.decreaseIndent();
                }
            }
            fout.decreaseIndent();

            fout.println("Open fds:");
            fout.increaseIndent();
            if (mRevocableFds.isEmpty()) {
                fout.println("<empty>");
            } else {
                for (int i = 0, count = mRevocableFds.size(); i < count; ++i) {
                    final Accessor accessor = mRevocableFds.keyAt(i);
                    final ArraySet<RevocableFileDescriptor> rFds =
                            mRevocableFds.valueAt(i);
                    fout.println(accessor + ": #" + rFds.size());
                }
            }
            fout.decreaseIndent();
        }
    }

    void writeToXml(XmlSerializer out) throws IOException {
        synchronized (mMetadataLock) {
            XmlUtils.writeLongAttribute(out, ATTR_ID, mBlobId);

            out.startTag(null, TAG_BLOB_HANDLE);
            mBlobHandle.writeToXml(out);
            out.endTag(null, TAG_BLOB_HANDLE);

            for (int i = 0, count = mCommitters.size(); i < count; ++i) {
                out.startTag(null, TAG_COMMITTER);
                mCommitters.valueAt(i).writeToXml(out);
                out.endTag(null, TAG_COMMITTER);
            }

            for (int i = 0, count = mLeasees.size(); i < count; ++i) {
                out.startTag(null, TAG_LEASEE);
                mLeasees.valueAt(i).writeToXml(out);
                out.endTag(null, TAG_LEASEE);
            }
        }
    }

    @Nullable
    static BlobMetadata createFromXml(XmlPullParser in, int version, Context context)
            throws XmlPullParserException, IOException {
        final long blobId = XmlUtils.readLongAttribute(in, ATTR_ID);
        if (version < XML_VERSION_ALLOW_ACCESS_ACROSS_USERS) {
            XmlUtils.readIntAttribute(in, ATTR_USER_ID);
        }

        BlobHandle blobHandle = null;
        final ArraySet<Committer> committers = new ArraySet<>();
        final ArraySet<Leasee> leasees = new ArraySet<>();
        final int depth = in.getDepth();
        while (XmlUtils.nextElementWithin(in, depth)) {
            if (TAG_BLOB_HANDLE.equals(in.getName())) {
                blobHandle = BlobHandle.createFromXml(in);
            } else if (TAG_COMMITTER.equals(in.getName())) {
                final Committer committer = Committer.createFromXml(in, version);
                if (committer != null) {
                    committers.add(committer);
                }
            } else if (TAG_LEASEE.equals(in.getName())) {
                leasees.add(Leasee.createFromXml(in, version));
            }
        }

        if (blobHandle == null) {
            Slog.wtf(TAG, "blobHandle should be available");
            return null;
        }

        final BlobMetadata blobMetadata = new BlobMetadata(context, blobId, blobHandle);
        blobMetadata.setCommitters(committers);
        blobMetadata.setLeasees(leasees);
        return blobMetadata;
    }

    static final class Committer extends Accessor {
        public final BlobAccessMode blobAccessMode;
        public final long commitTimeMs;

        Committer(String packageName, int uid, BlobAccessMode blobAccessMode, long commitTimeMs) {
            super(packageName, uid);
            this.blobAccessMode = blobAccessMode;
            this.commitTimeMs = commitTimeMs;
        }

        long getCommitTimeMs() {
            return commitTimeMs;
        }

        void dump(IndentingPrintWriter fout) {
            fout.println("commit time: "
                    + (commitTimeMs == 0 ? "<null>" : BlobStoreUtils.formatTime(commitTimeMs)));
            fout.println("accessMode:");
            fout.increaseIndent();
            blobAccessMode.dump(fout);
            fout.decreaseIndent();
        }

        void writeToXml(@NonNull XmlSerializer out) throws IOException {
            XmlUtils.writeStringAttribute(out, ATTR_PACKAGE, packageName);
            XmlUtils.writeIntAttribute(out, ATTR_UID, uid);
            XmlUtils.writeLongAttribute(out, ATTR_COMMIT_TIME_MS, commitTimeMs);

            out.startTag(null, TAG_ACCESS_MODE);
            blobAccessMode.writeToXml(out);
            out.endTag(null, TAG_ACCESS_MODE);
        }

        @Nullable
        static Committer createFromXml(@NonNull XmlPullParser in, int version)
                throws XmlPullParserException, IOException {
            final String packageName = XmlUtils.readStringAttribute(in, ATTR_PACKAGE);
            final int uid = XmlUtils.readIntAttribute(in, ATTR_UID);
            final long commitTimeMs = version >= XML_VERSION_ADD_COMMIT_TIME
                    ? XmlUtils.readLongAttribute(in, ATTR_COMMIT_TIME_MS)
                    : 0;

            final int depth = in.getDepth();
            BlobAccessMode blobAccessMode = null;
            while (XmlUtils.nextElementWithin(in, depth)) {
                if (TAG_ACCESS_MODE.equals(in.getName())) {
                    blobAccessMode = BlobAccessMode.createFromXml(in);
                }
            }
            if (blobAccessMode == null) {
                Slog.wtf(TAG, "blobAccessMode should be available");
                return null;
            }
            return new Committer(packageName, uid, blobAccessMode, commitTimeMs);
        }
    }

    static final class Leasee extends Accessor {
        public final String descriptionResEntryName;
        public final CharSequence description;
        public final long expiryTimeMillis;

        Leasee(@NonNull Context context, @NonNull String packageName,
                int uid, int descriptionResId,
                @Nullable CharSequence description, long expiryTimeMillis) {
            super(packageName, uid);
            final Resources packageResources = getPackageResources(context, packageName,
                    UserHandle.getUserId(uid));
            this.descriptionResEntryName = getResourceEntryName(packageResources, descriptionResId);
            this.expiryTimeMillis = expiryTimeMillis;
            this.description = description == null
                    ? getDescription(packageResources, descriptionResId)
                    : description;
        }

        Leasee(String packageName, int uid, @Nullable String descriptionResEntryName,
                @Nullable CharSequence description, long expiryTimeMillis) {
            super(packageName, uid);
            this.descriptionResEntryName = descriptionResEntryName;
            this.expiryTimeMillis = expiryTimeMillis;
            this.description = description;
        }

        @Nullable
        private static String getResourceEntryName(@Nullable Resources packageResources,
                int resId) {
            if (!ResourceId.isValid(resId) || packageResources == null) {
                return null;
            }
            return packageResources.getResourceEntryName(resId);
        }

        @Nullable
        private static String getDescription(@NonNull Context context,
                @NonNull String descriptionResEntryName, @NonNull String packageName, int userId) {
            if (descriptionResEntryName == null || descriptionResEntryName.isEmpty()) {
                return null;
            }
            final Resources resources = getPackageResources(context, packageName, userId);
            if (resources == null) {
                return null;
            }
            final int resId = getDescriptionResourceId(resources, descriptionResEntryName,
                    packageName);
            return resId == Resources.ID_NULL ? null : resources.getString(resId);
        }

        @Nullable
        private static String getDescription(@Nullable Resources packageResources,
                int descriptionResId) {
            if (!ResourceId.isValid(descriptionResId) || packageResources == null) {
                return null;
            }
            return packageResources.getString(descriptionResId);
        }

        boolean isStillValid() {
            return expiryTimeMillis == 0 || expiryTimeMillis >= System.currentTimeMillis();
        }

        void dump(@NonNull Context context, @NonNull IndentingPrintWriter fout) {
            fout.println("desc: " + getDescriptionToDump(context));
            fout.println("expiryMs: " + expiryTimeMillis);
        }

        @NonNull
        private String getDescriptionToDump(@NonNull Context context) {
            String desc = getDescription(context, descriptionResEntryName, packageName,
                    UserHandle.getUserId(uid));
            if (desc == null) {
                desc = description.toString();
            }
            return desc == null ? "<none>" : desc;
        }

        void writeToXml(@NonNull XmlSerializer out) throws IOException {
            XmlUtils.writeStringAttribute(out, ATTR_PACKAGE, packageName);
            XmlUtils.writeIntAttribute(out, ATTR_UID, uid);
            XmlUtils.writeStringAttribute(out, ATTR_DESCRIPTION_RES_NAME, descriptionResEntryName);
            XmlUtils.writeLongAttribute(out, ATTR_EXPIRY_TIME, expiryTimeMillis);
            XmlUtils.writeStringAttribute(out, ATTR_DESCRIPTION, description);
        }

        @NonNull
        static Leasee createFromXml(@NonNull XmlPullParser in, int version)
                throws IOException {
            final String packageName = XmlUtils.readStringAttribute(in, ATTR_PACKAGE);
            final int uid = XmlUtils.readIntAttribute(in, ATTR_UID);
            final String descriptionResEntryName;
            if (version >= XML_VERSION_ADD_DESC_RES_NAME) {
                descriptionResEntryName = XmlUtils.readStringAttribute(
                        in, ATTR_DESCRIPTION_RES_NAME);
            } else {
                descriptionResEntryName = null;
            }
            final long expiryTimeMillis = XmlUtils.readLongAttribute(in, ATTR_EXPIRY_TIME);
            final CharSequence description;
            if (version >= XML_VERSION_ADD_STRING_DESC) {
                description = XmlUtils.readStringAttribute(in, ATTR_DESCRIPTION);
            } else {
                description = null;
            }

            return new Leasee(packageName, uid, descriptionResEntryName,
                    description, expiryTimeMillis);
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

        @Override
        public String toString() {
            return "[" + packageName + ", " + uid + "]";
        }
    }
}
