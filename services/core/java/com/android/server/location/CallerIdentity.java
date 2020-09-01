/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.location;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Binder;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents the calling process's uid, pid, and package name.
 */
public final class CallerIdentity {

    public static final int PERMISSION_NONE = 0;
    public static final int PERMISSION_COARSE = 1;
    public static final int PERMISSION_FINE = 2;

    @IntDef({PERMISSION_NONE, PERMISSION_COARSE, PERMISSION_FINE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionLevel {}

    /**
     * Converts the given permission level to the corresponding permission.
     */
    public static String asPermission(@PermissionLevel int permissionLevel) {
        switch (permissionLevel) {
            case PERMISSION_COARSE:
                return ACCESS_COARSE_LOCATION;
            case PERMISSION_FINE:
                return ACCESS_FINE_LOCATION;
            default:
                throw new IllegalArgumentException();
        }
    }

    /**
     * Converts the given permission level to the corresponding appop.
     */
    public static int asAppOp(@PermissionLevel int permissionLevel) {
        switch (permissionLevel) {
            case PERMISSION_COARSE:
                return AppOpsManager.OP_COARSE_LOCATION;
            case PERMISSION_FINE:
                return AppOpsManager.OP_FINE_LOCATION;
            default:
                throw new IllegalArgumentException();
        }
    }

    /**
     * Creates a CallerIdentity from the current binder identity, using the given package and
     * feature id. The package will be checked to enforce it belongs to the calling uid, and a
     * security exception will be thrown if it is invalid.
     */
    public static CallerIdentity fromBinder(Context context, String packageName,
            @Nullable String featureId) {
        return fromBinder(context, packageName, featureId, null);
    }

    /**
     * Creates a CallerIdentity from the current binder identity, using the given package, feature
     * id, and listener id. The package will be checked to enforce it belongs to the calling uid,
     * and a security exception will be thrown if it is invalid.
     */
    public static CallerIdentity fromBinder(Context context, String packageName,
            @Nullable String featureId, @Nullable String listenerId) {
        int uid = Binder.getCallingUid();
        if (!ArrayUtils.contains(context.getPackageManager().getPackagesForUid(uid), packageName)) {
            throw new SecurityException("invalid package \"" + packageName + "\" for uid " + uid);
        }

        return fromBinderUnsafe(context, packageName, featureId, listenerId);
    }

    /**
     * Creates a CallerIdentity from the current binder identity, using the given package and
     * feature id. The package will not be checked to enforce that it belongs to the calling uid -
     * this method should only be used if the package will be validated by some other means, such as
     * an appops call.
     */
    public static CallerIdentity fromBinderUnsafe(Context context, String packageName,
            @Nullable String featureId) {
        return fromBinderUnsafe(context, packageName, featureId, null);
    }

    /**
     * Creates a CallerIdentity from the current binder identity, using the given package, feature
     * id, and listener id. The package will not be checked to enforce that it belongs to the
     * calling uid - this method should only be used if the package will be validated by some other
     * means, such as an appops call.
     */
    public static CallerIdentity fromBinderUnsafe(Context context, String packageName,
            @Nullable String featureId, @Nullable String listenerId) {
        return new CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid(),
                UserHandle.getCallingUserId(), packageName, featureId, listenerId,
                getBinderPermissionLevel(context));
    }

    /**
     * Throws a security exception if the caller does not hold a location permission.
     */
    public static void enforceCallingOrSelfLocationPermission(Context context) {
        enforceLocationPermission(Binder.getCallingUid(), getBinderPermissionLevel(context));
    }

    /**
     * Returns false if the caller does not hold a location permission, true otherwise.
     */
    public static boolean checkCallingOrSelfLocationPermission(Context context) {
        return checkLocationPermission(getBinderPermissionLevel(context));
    }

    private static void enforceLocationPermission(int uid, @PermissionLevel int permissionLevel) {
        if (checkLocationPermission(permissionLevel)) {
            return;
        }

        throw new SecurityException("uid " + uid + " does not have " + ACCESS_COARSE_LOCATION
                + " or " + ACCESS_FINE_LOCATION + ".");
    }

    private static boolean checkLocationPermission(@PermissionLevel int permissionLevel) {
        return permissionLevel >= PERMISSION_COARSE;
    }

    private static @PermissionLevel int getBinderPermissionLevel(Context context) {
        if (context.checkCallingOrSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED) {
            return PERMISSION_FINE;
        }
        if (context.checkCallingOrSelfPermission(ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED) {
            return PERMISSION_COARSE;
        }

        return PERMISSION_NONE;
    }

    /** The calling UID. */
    public final int uid;

    /** The calling PID. */
    public final int pid;

    /** The calling user. */
    public final int userId;

    /** The calling package name. */
    public final String packageName;

    /** The calling feature id. */
    public final @Nullable String featureId;

    /** The calling listener id. */
    public final @Nullable String listenerId;

    /**
     * The calling location permission level. This field should only be used for validating
     * permissions for API access. It should not be used for validating permissions for location
     * access - that must be done through appops.
     */
    public final @PermissionLevel int permissionLevel;

    @VisibleForTesting
    public CallerIdentity(int uid, int pid, int userId, String packageName,
            @Nullable String featureId, @PermissionLevel int permissionLevel) {
        this(uid, pid, userId, packageName, featureId, null, permissionLevel);
    }

    private CallerIdentity(int uid, int pid, int userId, String packageName,
            @Nullable String featureId, @Nullable String listenerId,
            @PermissionLevel int permissionLevel) {
        this.uid = uid;
        this.pid = pid;
        this.userId = userId;
        this.packageName = Objects.requireNonNull(packageName);
        this.featureId = featureId;
        this.listenerId = listenerId;
        this.permissionLevel = Preconditions.checkArgumentInRange(permissionLevel, PERMISSION_NONE,
                PERMISSION_FINE, "permissionLevel");
    }

    /**
     * Throws a security exception if the CallerIdentity does not hold a location permission.
     */
    public void enforceLocationPermission() {
        enforceLocationPermission(uid, permissionLevel);
    }

    @Override
    public String toString() {
        int length = 10 + packageName.length();
        if (featureId != null) {
            length += featureId.length();
        }

        StringBuilder builder = new StringBuilder(length);
        builder.append(pid).append("/").append(packageName);
        if (featureId != null) {
            builder.append("[");
            if (featureId.startsWith(packageName)) {
                builder.append(featureId.substring(packageName.length()));
            } else {
                builder.append(featureId);
            }
            builder.append("]");
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
        return uid == that.uid
                && pid == that.pid
                && packageName.equals(that.packageName)
                && Objects.equals(featureId, that.featureId)
                && Objects.equals(listenerId, that.listenerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, pid, packageName, featureId);
    }
}
