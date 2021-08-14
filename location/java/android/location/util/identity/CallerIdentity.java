/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.location.util.identity;

import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.WorkSource;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.HexDump;

import java.util.Objects;

/**
 * Identifying information on a caller.
 *
 * @hide
 */
public final class CallerIdentity {

    /**
     * Construct a CallerIdentity for test purposes.
     */
    @VisibleForTesting
    public static CallerIdentity forTest(int uid, int pid, String packageName,
            @Nullable String attributionTag) {
        return forTest(uid, pid, packageName, attributionTag, null);
    }

    /**
     * Construct a CallerIdentity for test purposes.
     */
    @VisibleForTesting
    public static CallerIdentity forTest(int uid, int pid, String packageName,
            @Nullable String attributionTag, @Nullable String listenerId) {
        return new CallerIdentity(uid, pid, packageName, attributionTag, listenerId);
    }

    /**
     * Returns a CallerIdentity with PID and listener ID information stripped. This is mostly
     * useful for aggregating information for debug purposes, and should not be used in any API with
     * security requirements.
     */
    public static CallerIdentity forAggregation(CallerIdentity callerIdentity) {
        if (callerIdentity.getPid() == 0 && callerIdentity.getListenerId() == null) {
            return callerIdentity;
        }

        return new CallerIdentity(callerIdentity.getUid(), 0, callerIdentity.getPackageName(),
                callerIdentity.getAttributionTag(), null);
    }

    /**
     * Creates a CallerIdentity for the current process and context.
     */
    public static CallerIdentity fromContext(Context context) {
        return new CallerIdentity(Process.myUid(), Process.myPid(), context.getPackageName(),
                context.getAttributionTag(), null);
    }

    /**
     * Creates a CallerIdentity from the current binder identity, using the given package and
     * feature id. The package will be checked to enforce it belongs to the calling uid, and a
     * security exception will be thrown if it is invalid.
     */
    public static CallerIdentity fromBinder(Context context, String packageName,
            @Nullable String attributionTag) {
        return fromBinder(context, packageName, attributionTag, null);
    }

    /**
     * Creates a CallerIdentity from the current binder identity, using the given package, feature
     * id, and listener id. The package will be checked to enforce it belongs to the calling uid,
     * and a security exception will be thrown if it is invalid.
     */
    public static CallerIdentity fromBinder(Context context, String packageName,
            @Nullable String attributionTag, @Nullable String listenerId) {
        int uid = Binder.getCallingUid();
        if (!ArrayUtils.contains(context.getPackageManager().getPackagesForUid(uid), packageName)) {
            throw new SecurityException("invalid package \"" + packageName + "\" for uid " + uid);
        }

        return fromBinderUnsafe(packageName, attributionTag, listenerId);
    }

    /**
     * Creates a CallerIdentity from the current binder identity, using the given package and
     * feature id. The package will not be checked to enforce that it belongs to the calling uid -
     * this method should only be used if the package will be validated by some other means, such as
     * an appops call.
     */
    public static CallerIdentity fromBinderUnsafe(String packageName,
            @Nullable String attributionTag) {
        return fromBinderUnsafe(packageName, attributionTag, null);
    }

    /**
     * Creates a CallerIdentity from the current binder identity, using the given package, feature
     * id, and listener id. The package will not be checked to enforce that it belongs to the
     * calling uid - this method should only be used if the package will be validated by some other
     * means, such as an appops call.
     */
    public static CallerIdentity fromBinderUnsafe(String packageName,
            @Nullable String attributionTag, @Nullable String listenerId) {
        return new CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid(),
                packageName, attributionTag, listenerId);
    }

    private final int mUid;

    private final int mPid;

    private final String mPackageName;

    private final @Nullable String mAttributionTag;

    private final @Nullable String mListenerId;

    private CallerIdentity(int uid, int pid, String packageName,
            @Nullable String attributionTag, @Nullable String listenerId) {
        this.mUid = uid;
        this.mPid = pid;
        this.mPackageName = Objects.requireNonNull(packageName);
        this.mAttributionTag = attributionTag;
        this.mListenerId = listenerId;
    }

    /** The calling UID. */
    public int getUid() {
        return mUid;
    }

    /** The calling PID. */
    public int getPid() {
        return mPid;
    }

    /** The calling user. */
    public int getUserId() {
        return UserHandle.getUserId(mUid);
    }

    /** The calling package name. */
    public String getPackageName() {
        return mPackageName;
    }

    /** The calling attribution tag. */
    public String getAttributionTag() {
        return mAttributionTag;
    }

    /**
     * The calling listener id. A null listener id will match any other listener id for the purposes
     * of {@link #equals(Object)}.
     */
    public String getListenerId() {
        return mListenerId;
    }

    /** Returns true if this represents a system server identity. */
    public boolean isSystemServer() {
        return mUid == Process.SYSTEM_UID;
    }

    /**
     * Adds this identity to the worksource supplied, or if not worksource is supplied, creates a
     * new worksource representing this identity.
     */
    public WorkSource addToWorkSource(@Nullable WorkSource workSource) {
        if (workSource == null) {
            return new WorkSource(mUid, mPackageName);
        } else {
            workSource.add(mUid, mPackageName);
            return workSource;
        }
    }

    @Override
    public String toString() {
        int length = 10 + mPackageName.length();
        if (mAttributionTag != null) {
            length += mAttributionTag.length();
        }

        StringBuilder builder = new StringBuilder(length);
        builder.append(mUid).append("/").append(mPackageName);
        if (mAttributionTag != null) {
            builder.append("[");
            if (mAttributionTag.startsWith(mPackageName)) {
                builder.append(mAttributionTag.substring(mPackageName.length()));
            } else {
                builder.append(mAttributionTag);
            }
            builder.append("]");
        }
        if (mListenerId != null) {
            builder.append("/").append(HexDump.toHexString(mListenerId.hashCode()));
        }
        return builder.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CallerIdentity)) {
            return false;
        }
        CallerIdentity that = (CallerIdentity) o;
        return mUid == that.mUid
                && mPid == that.mPid
                && mPackageName.equals(that.mPackageName)
                && Objects.equals(mAttributionTag, that.mAttributionTag)
                && (mListenerId == null || that.mListenerId == null || mListenerId.equals(
                that.mListenerId));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUid, mPid, mPackageName, mAttributionTag);
    }
}
