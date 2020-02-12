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

import static android.app.blob.XmlTags.ATTR_DESCRIPTION;
import static android.app.blob.XmlTags.ATTR_DESCRIPTION_RES_ID;
import static android.app.blob.XmlTags.ATTR_EXPIRY_TIME;
import static android.app.blob.XmlTags.ATTR_ID;
import static android.app.blob.XmlTags.ATTR_PACKAGE;
import static android.app.blob.XmlTags.ATTR_UID;
import static android.app.blob.XmlTags.ATTR_USER_ID;
import static android.app.blob.XmlTags.TAG_ACCESS_MODE;
import static android.app.blob.XmlTags.TAG_BLOB_HANDLE;
import static android.app.blob.XmlTags.TAG_COMMITTER;
import static android.app.blob.XmlTags.TAG_LEASEE;
import static android.system.OsConstants.O_RDONLY;

import static com.android.server.blob.BlobStoreConfig.TAG;
import static com.android.server.blob.BlobStoreConfig.XML_VERSION_ADD_STRING_DESC;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.blob.BlobHandle;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ResourceId;
import android.content.res.Resources;
import android.os.ParcelFileDescriptor;
import android.os.RevocableFileDescriptor;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;
import com.android.server.blob.BlobStoreManagerService.DumpArgs;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;

class BlobMetadata {
    private final Object mMetadataLock = new Object();

    private final Context mContext;

    private final long mBlobId;
    private final BlobHandle mBlobHandle;
    private final int mUserId;

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

    // Do not access this directly, instead use getSessionFile().
    private File mBlobFile;

    BlobMetadata(Context context, long blobId, BlobHandle blobHandle, int userId) {
        mContext = context;
        this.mBlobId = blobId;
        this.mBlobHandle = blobHandle;
        this.mUserId = userId;
    }

    long getBlobId() {
        return mBlobId;
    }

    BlobHandle getBlobHandle() {
        return mBlobHandle;
    }

    int getUserId() {
        return mUserId;
    }

    void addCommitter(@NonNull Committer committer) {
        synchronized (mMetadataLock) {
            mCommitters.add(committer);
        }
    }

    void addCommitters(ArraySet<Committer> committers) {
        synchronized (mMetadataLock) {
            mCommitters.addAll(committers);
        }
    }

    void removeCommitter(@NonNull String packageName, int uid) {
        synchronized (mMetadataLock) {
            mCommitters.removeIf((committer) ->
                    committer.uid == uid && committer.packageName.equals(packageName));
        }
    }

    void removeInvalidCommitters(SparseArray<String> packages) {
        synchronized (mMetadataLock) {
            mCommitters.removeIf(committer ->
                    !committer.packageName.equals(packages.get(committer.uid)));
        }
    }

    @Nullable
    Committer getExistingCommitter(@NonNull Committer newCommitter) {
        synchronized (mCommitters) {
            final int index = mCommitters.indexOf(newCommitter);
            return index >= 0 ? mCommitters.valueAt(index) : null;
        }
    }

    void addLeasee(String callingPackage, int callingUid, int descriptionResId,
            CharSequence description, long leaseExpiryTimeMillis) {
        synchronized (mMetadataLock) {
            mLeasees.add(new Leasee(callingPackage, callingUid,
                    descriptionResId, description, leaseExpiryTimeMillis));
        }
    }

    void addLeasees(ArraySet<Leasee> leasees) {
        synchronized (mMetadataLock) {
            mLeasees.addAll(leasees);
        }
    }

    void removeLeasee(String packageName, int uid) {
        synchronized (mMetadataLock) {
            mLeasees.removeIf((leasee) ->
                    leasee.uid == uid && leasee.packageName.equals(packageName));
        }
    }

    void removeInvalidLeasees(SparseArray<String> packages) {
        synchronized (mMetadataLock) {
            mLeasees.removeIf(leasee ->
                    !leasee.packageName.equals(packages.get(leasee.uid)));
        }
    }

    boolean hasLeases() {
        synchronized (mMetadataLock) {
            return !mLeasees.isEmpty();
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

    File getBlobFile() {
        if (mBlobFile == null) {
            mBlobFile = BlobStoreConfig.getBlobFile(mBlobId);
        }
        return mBlobFile;
    }

    ParcelFileDescriptor openForRead(String callingPackage) throws IOException {
        // TODO: Add limit on opened fds
        FileDescriptor fd;
        try {
            fd = Os.open(getBlobFile().getPath(), O_RDONLY, 0);
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

    void dump(IndentingPrintWriter fout, DumpArgs dumpArgs) {
        fout.println("blobHandle:");
        fout.increaseIndent();
        mBlobHandle.dump(fout, dumpArgs.shouldDumpFull());
        fout.decreaseIndent();

        fout.println("Committers:");
        fout.increaseIndent();
        for (int i = 0, count = mCommitters.size(); i < count; ++i) {
            final Committer committer = mCommitters.valueAt(i);
            fout.println("committer " + committer.toString());
            fout.increaseIndent();
            committer.dump(fout);
            fout.decreaseIndent();
        }
        fout.decreaseIndent();

        fout.println("Leasees:");
        fout.increaseIndent();
        for (int i = 0, count = mLeasees.size(); i < count; ++i) {
            final Leasee leasee = mLeasees.valueAt(i);
            fout.println("leasee " + leasee.toString());
            fout.increaseIndent();
            leasee.dump(mContext, fout);
            fout.decreaseIndent();
        }
        fout.decreaseIndent();

        fout.println("Open fds: #" + mRevocableFds.size());
    }

    void writeToXml(XmlSerializer out) throws IOException {
        synchronized (mMetadataLock) {
            XmlUtils.writeLongAttribute(out, ATTR_ID, mBlobId);
            XmlUtils.writeIntAttribute(out, ATTR_USER_ID, mUserId);

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
        final int userId = XmlUtils.readIntAttribute(in, ATTR_USER_ID);

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

        final BlobMetadata blobMetadata = new BlobMetadata(context, blobId, blobHandle, userId);
        blobMetadata.addCommitters(committers);
        blobMetadata.addLeasees(leasees);
        return blobMetadata;
    }

    static final class Committer extends Accessor {
        public final BlobAccessMode blobAccessMode;

        Committer(String packageName, int uid, BlobAccessMode blobAccessMode) {
            super(packageName, uid);
            this.blobAccessMode = blobAccessMode;
        }

        void dump(IndentingPrintWriter fout) {
            fout.println("accessMode:");
            fout.increaseIndent();
            blobAccessMode.dump(fout);
            fout.decreaseIndent();
        }

        void writeToXml(@NonNull XmlSerializer out) throws IOException {
            XmlUtils.writeStringAttribute(out, ATTR_PACKAGE, packageName);
            XmlUtils.writeIntAttribute(out, ATTR_UID, uid);

            out.startTag(null, TAG_ACCESS_MODE);
            blobAccessMode.writeToXml(out);
            out.endTag(null, TAG_ACCESS_MODE);
        }

        @Nullable
        static Committer createFromXml(@NonNull XmlPullParser in, int version)
                throws XmlPullParserException, IOException {
            final String packageName = XmlUtils.readStringAttribute(in, ATTR_PACKAGE);
            final int uid = XmlUtils.readIntAttribute(in, ATTR_UID);

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
            return new Committer(packageName, uid, blobAccessMode);
        }
    }

    static final class Leasee extends Accessor {
        public final int descriptionResId;
        public final CharSequence description;
        public final long expiryTimeMillis;

        Leasee(String packageName, int uid, int descriptionResId, CharSequence description,
                long expiryTimeMillis) {
            super(packageName, uid);
            this.descriptionResId = descriptionResId;
            this.expiryTimeMillis = expiryTimeMillis;
            this.description = description;
        }

        boolean isStillValid() {
            return expiryTimeMillis == 0 || expiryTimeMillis <= System.currentTimeMillis();
        }

        void dump(Context context, IndentingPrintWriter fout) {
            fout.println("desc: " + getDescriptionToDump(context));
            fout.println("expiryMs: " + expiryTimeMillis);
        }

        private String getDescriptionToDump(Context context) {
            String desc = null;
            if (ResourceId.isValid(descriptionResId)) {
                try {
                    final Resources leaseeRes = context.getPackageManager()
                            .getResourcesForApplicationAsUser(
                                    packageName, UserHandle.getUserId(uid));
                    desc = leaseeRes.getString(descriptionResId);
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.d(TAG, "Unknown package in user " + UserHandle.getUserId(uid) + ": "
                            + packageName, e);
                    desc = "<none>";
                }
            } else {
                desc = description.toString();
            }
            return desc;
        }

        void writeToXml(@NonNull XmlSerializer out) throws IOException {
            XmlUtils.writeStringAttribute(out, ATTR_PACKAGE, packageName);
            XmlUtils.writeIntAttribute(out, ATTR_UID, uid);
            XmlUtils.writeIntAttribute(out, ATTR_DESCRIPTION_RES_ID, descriptionResId);
            XmlUtils.writeLongAttribute(out, ATTR_EXPIRY_TIME, expiryTimeMillis);
            XmlUtils.writeStringAttribute(out, ATTR_DESCRIPTION, description);
        }

        @NonNull
        static Leasee createFromXml(@NonNull XmlPullParser in, int version) throws IOException {
            final String packageName = XmlUtils.readStringAttribute(in, ATTR_PACKAGE);
            final int uid = XmlUtils.readIntAttribute(in, ATTR_UID);
            final int descriptionResId = XmlUtils.readIntAttribute(in, ATTR_DESCRIPTION_RES_ID);
            final long expiryTimeMillis = XmlUtils.readLongAttribute(in, ATTR_EXPIRY_TIME);
            final CharSequence description;
            if (version >= XML_VERSION_ADD_STRING_DESC) {
                description = XmlUtils.readStringAttribute(in, ATTR_DESCRIPTION);
            } else {
                description = null;
            }

            return new Leasee(packageName, uid, descriptionResId, description, expiryTimeMillis);
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
