/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.permission.flags.Flags;
import android.util.ArraySet;

import com.android.internal.annotations.Immutable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * This class represents a source to which access to permission protected data should be
 * attributed. Attribution sources can be chained to represent cases where the protected
 * data would flow through several applications. For example, app A may ask app B for
 * contacts and in turn app B may ask app C for contacts. In this case, the attribution
 * chain would be A -> B -> C and the data flow would be C -> B -> A. There are two
 * main benefits of using the attribution source mechanism: avoid doing explicit permission
 * checks on behalf of the calling app if you are accessing private data on their behalf
 * to send back; avoid double data access blaming which happens as you check the calling
 * app's permissions and when you access the data behind these permissions (for runtime
 * permissions). Also if not explicitly blaming the caller the data access would be
 * counted towards your app vs to the previous app where yours was just a proxy.
 * <p>
 * Every {@link Context} has an attribution source and you can get it via {@link
 * Context#getAttributionSource()} representing itself, which is a chain of one. You
 * can attribute work to another app, or more precisely to a chain of apps, through
 * which the data you would be accessing would flow, via {@link Context#createContext(
 * ContextParams)} plus specifying an attribution source for the next app to receive
 * the protected data you are accessing via {@link AttributionSource.Builder#setNext(
 * AttributionSource)}. Creating this attribution chain ensures that the datasource would
 * check whether every app in the attribution chain has permission to access the data
 * before releasing it. The datasource will also record appropriately that this data was
 * accessed by the apps in the sequence if the data is behind a sensitive permission
 * (e.g. dangerous). Again, this is useful if you are accessing the data on behalf of another
 * app, for example a speech recognizer using the mic so it can provide recognition to
 * a calling app.
 * <p>
 * You can create an attribution chain of you and any other app without any verification
 * as this is something already available via the {@link android.app.AppOpsManager} APIs.
 * This is supported to handle cases where you don't have access to the caller's attribution
 * source and you can directly use the {@link AttributionSource.Builder} APIs. However,
 * if the data flows through more than two apps (more than you access the data for the
 * caller) you need to have a handle to the {@link AttributionSource} for the calling app's
 * context in order to create an attribution context. This means you either need to have an
 * API for the other app to send you its attribution source or use a platform API that pipes
 * the callers attribution source.
 * <p>
 * You cannot forge an attribution chain without the participation of every app in the
 * attribution chain (aside of the special case mentioned above). To create an attribution
 * source that is trusted you need to create an attribution context that points to an
 * attribution source that was explicitly created by the app that it refers to, recursively.
 * <p>
 * Since creating an attribution context leads to all permissions for apps in the attribution
 * chain being checked, you need to expect getting a security exception when accessing
 * permission protected APIs since some app in the chain may not have the permission.
 */
@Immutable
public final class AttributionSource implements Parcelable {
    private static final String DESCRIPTOR = "android.content.AttributionSource";

    private static final Binder sDefaultToken = new Binder(DESCRIPTOR);

    private final @NonNull AttributionSourceState mAttributionSourceState;

    private @Nullable AttributionSource mNextCached;
    private @Nullable Set<String> mRenouncedPermissionsCached;

    /** @hide */
    @TestApi
    public AttributionSource(int uid, @Nullable String packageName,
            @Nullable String attributionTag) {
        this(uid, Process.INVALID_PID, packageName, attributionTag, sDefaultToken);
    }

    /** @hide */
    public AttributionSource(int uid, int pid, @Nullable String packageName,
            @Nullable String attributionTag) {
        this(uid, pid, packageName, attributionTag, sDefaultToken);
    }

    /** @hide */
    @TestApi
    public AttributionSource(int uid, @Nullable String packageName,
            @Nullable String attributionTag, @NonNull IBinder token) {
        this(uid, Process.INVALID_PID, packageName, attributionTag, token,
                /*renouncedPermissions*/ null, Context.DEVICE_ID_DEFAULT, /*next*/ null);
    }

    /** @hide */
    public AttributionSource(int uid, int pid, @Nullable String packageName,
            @Nullable String attributionTag, @NonNull IBinder token) {
        this(uid, pid, packageName, attributionTag, token, /*renouncedPermissions*/ null,
                Context.DEVICE_ID_DEFAULT, /*next*/ null);
    }

    /** @hide */
    @TestApi
    public AttributionSource(int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable Set<String> renouncedPermissions,
            @Nullable AttributionSource next) {
        this(uid, Process.INVALID_PID, packageName, attributionTag, sDefaultToken,
                (renouncedPermissions != null)
                ? renouncedPermissions.toArray(new String[0]) : null, Context.DEVICE_ID_DEFAULT,
                /*next*/ next);
    }

    /** @hide */
    public AttributionSource(@NonNull AttributionSource current, @Nullable AttributionSource next) {
        this(current.getUid(), current.getPid(), current.getPackageName(),
                current.getAttributionTag(), current.getToken(),
                current.mAttributionSourceState.renouncedPermissions, current.getDeviceId(), next);
    }

    /** @hide */
    public AttributionSource(int uid, int pid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String[] renouncedPermissions, int deviceId,
            @Nullable AttributionSource next) {
        this(uid, pid, packageName, attributionTag, sDefaultToken, renouncedPermissions, deviceId,
                next);
    }

    /** @hide */
    @TestApi
    @FlaggedApi(Flags.FLAG_ATTRIBUTION_SOURCE_CONSTRUCTOR)
    public AttributionSource(int uid, int pid, @Nullable String packageName,
            @Nullable String attributionTag, @NonNull IBinder token,
            @Nullable String[] renouncedPermissions,
            @Nullable AttributionSource next) {
        this(uid, pid, packageName, attributionTag, token, renouncedPermissions,
                Context.DEVICE_ID_DEFAULT, next);
    }

    /** @hide */
    @TestApi
    @FlaggedApi(Flags.FLAG_DEVICE_AWARE_PERMISSION_APIS)
    public AttributionSource(int uid, int pid, @Nullable String packageName,
            @Nullable String attributionTag, @NonNull IBinder token,
            @Nullable String[] renouncedPermissions,
            int deviceId, @Nullable AttributionSource next) {
        mAttributionSourceState = new AttributionSourceState();
        mAttributionSourceState.uid = uid;
        mAttributionSourceState.pid = pid;
        mAttributionSourceState.token = token;
        mAttributionSourceState.packageName = packageName;
        mAttributionSourceState.attributionTag = attributionTag;
        mAttributionSourceState.renouncedPermissions = renouncedPermissions;
        mAttributionSourceState.deviceId = deviceId;
        mAttributionSourceState.next = (next != null) ? new AttributionSourceState[]
                {next.mAttributionSourceState} : new AttributionSourceState[0];
    }

    AttributionSource(@NonNull Parcel in) {
        this(AttributionSourceState.CREATOR.createFromParcel(in));

        if (!Binder.isDirectlyHandlingTransaction()) {
            throw new SecurityException("AttributionSource should be unparceled during a binder "
                    + "transaction for proper verification.");
        }

        // Since we just unpacked this object as part of it transiting a Binder
        // call, this is the perfect time to enforce that its UID and PID can be trusted
        enforceCallingUid();

        // If this object is being constructed as part of a oneway Binder call, getCallingPid will
        // return 0 instead of the true PID. In that case, invalidate the PID by setting it to
        // INVALID_PID (-1).
        final int callingPid = Binder.getCallingPid();
        if (callingPid == 0) {
            mAttributionSourceState.pid = Process.INVALID_PID;
        }

        enforceCallingPid();
    }

    /** @hide */
    public AttributionSource(@NonNull AttributionSourceState attributionSourceState) {
        mAttributionSourceState = attributionSourceState;
    }

    /** @hide */
    public AttributionSource withNextAttributionSource(@Nullable AttributionSource next) {
        return new AttributionSource(getUid(), getPid(), getPackageName(), getAttributionTag(),
                getToken(), mAttributionSourceState.renouncedPermissions, getDeviceId(), next);
    }

    /** @hide */
    public AttributionSource withPackageName(@Nullable String packageName) {
        return new AttributionSource(getUid(), getPid(), packageName, getAttributionTag(),
               getToken(), mAttributionSourceState.renouncedPermissions, getDeviceId(), getNext());
    }

    /** @hide */
    public AttributionSource withToken(@NonNull Binder token) {
        return new AttributionSource(getUid(), getPid(), getPackageName(), getAttributionTag(),
                token, mAttributionSourceState.renouncedPermissions, getDeviceId(), getNext());
    }

    /** @hide */
    public AttributionSource withDefaultToken() {
        return withToken(sDefaultToken);
    }

    /** @hide */
    public AttributionSource withPid(int pid) {
        return new AttributionSource(getUid(), pid, getPackageName(), getAttributionTag(),
                getToken(), mAttributionSourceState.renouncedPermissions, getDeviceId(), getNext());
    }

    /** @hide */
    public AttributionSource withDeviceId(int deviceId) {
        return new AttributionSource(getUid(), getPid(), getPackageName(), getAttributionTag(),
                getToken(), mAttributionSourceState.renouncedPermissions, deviceId, getNext());
    }

    /** @hide */
    public @NonNull AttributionSourceState asState() {
        return mAttributionSourceState;
    }

    /** @hide */
    public @NonNull ScopedParcelState asScopedParcelState() {
        return new ScopedParcelState(this);
    }

    /**
     * Returns a generic {@link AttributionSource} that represents the entire
     * calling process.
     *
     * <p>Callers are <em>strongly</em> encouraged to use a more specific
     * attribution source whenever possible, such as from
     * {@link Context#getAttributionSource()}, since that enables developers to
     * have more detailed and scoped control over attribution within
     * sub-components of their app.
     *
     * @see Context#createAttributionContext(String)
     * @see Context#getAttributionTag()
     * @return a generic {@link AttributionSource} representing the entire
     *         calling process
     * @throws IllegalStateException when no accurate {@link AttributionSource}
     *         can be determined
     */
    public static @NonNull AttributionSource myAttributionSource() {

        final AttributionSource globalSource = ActivityThread.currentAttributionSource();
        if (globalSource != null) {
            return globalSource;
        }

        int uid = Process.myUid();
        if (uid == Process.ROOT_UID) {
            uid = Process.SYSTEM_UID;
        }
        try {
            return new AttributionSource.Builder(uid)
                .setPid(Process.myPid())
                .setDeviceId(Context.DEVICE_ID_DEFAULT)
                .setPackageName(AppGlobals.getPackageManager().getPackagesForUid(uid)[0])
                .build();
        } catch (Exception ignored) {
        }

        throw new IllegalStateException("Failed to resolve AttributionSource");
    }

    /**
     * This is a scoped object that exposes the content of an attribution source
     * as a parcel. This is useful when passing one to native and avoid custom
     * conversion logic from Java to native state that needs to be kept in sync
     * as attribution source evolves. This way we use the same logic for passing
     * to native as the ones for passing in an IPC - in both cases this is the
     * same auto generated code.
     *
     * @hide
     */
    public static class ScopedParcelState implements AutoCloseable {
        private final Parcel mParcel;

        public @NonNull Parcel getParcel() {
            return mParcel;
        }

        public ScopedParcelState(AttributionSource attributionSource) {
            mParcel = Parcel.obtain();
            attributionSource.writeToParcel(mParcel, 0);
            mParcel.setDataPosition(0);
        }

        public void close() {
            mParcel.recycle();
        }
    }

    /**
     * If you are handling an IPC and you don't trust the caller you need to validate
     * whether the attribution source is one for the calling app to prevent the caller
     * to pass you a source from another app without including themselves in the
     * attribution chain.
     *
     * @throws SecurityException if the attribution source cannot be trusted to be from the caller.
     */
    public void enforceCallingUid() {
        if (!checkCallingUid()) {
            throw new SecurityException("Calling uid: " + Binder.getCallingUid()
                    + " doesn't match source uid: " + mAttributionSourceState.uid);
        }
        // No need to check package as app ops manager does it already.
    }

    /**
     * If you are handling an IPC and you don't trust the caller you need to validate
     * whether the attribution source is one for the calling app to prevent the caller
     * to pass you a source from another app without including themselves in the
     * attribution chain.
     *
     * @return if the attribution source cannot be trusted to be from the caller.
     */
    public boolean checkCallingUid() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID
                && UserHandle.getAppId(callingUid) != Process.SYSTEM_UID
                && callingUid != mAttributionSourceState.uid) {
            return false;
        }
        // No need to check package as app ops manager does it already.
        return true;
    }

    /**
     * Validate that the pid being claimed for the calling app is not spoofed.
     *
     * Note that the PID may be unavailable, for example if we're in a oneway Binder call. In this
     * case, calling enforceCallingPid is guaranteed to fail. The caller should anticipate this.
     *
     * @throws SecurityException if the attribution source cannot be trusted to be from the caller.
     * @hide
     */
    @TestApi
    public void enforceCallingPid() {
        if (!checkCallingPid()) {
            if (Binder.getCallingPid() == 0) {
                throw new SecurityException("Calling pid unavailable due to oneway Binder call.");
            } else {
                throw new SecurityException("Calling pid: " + Binder.getCallingPid()
                        + " doesn't match source pid: " + mAttributionSourceState.pid);
            }
        }
    }

    /**
     * Validate that the pid being claimed for the calling app is not spoofed
     *
     * @return if the attribution source cannot be trusted to be from the caller.
     */
    private boolean checkCallingPid() {
        final int callingPid = Binder.getCallingPid();
        if (mAttributionSourceState.pid != Process.INVALID_PID
                && callingPid != mAttributionSourceState.pid) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        if (Build.IS_DEBUGGABLE) {
            return "AttributionSource { " +
                    "uid = " + mAttributionSourceState.uid + ", " +
                    "packageName = " + mAttributionSourceState.packageName + ", " +
                    "attributionTag = " + mAttributionSourceState.attributionTag + ", " +
                    "token = " + mAttributionSourceState.token + ", " +
                    "next = " + (mAttributionSourceState.next != null
                                    && mAttributionSourceState.next.length > 0
                            ? mAttributionSourceState.next[0] : null) +
                    " }";
        }
        return super.toString();
    }

    /**
     * @return The next UID that would receive the permission protected data.
     *
     * @hide
     */
    public int getNextUid() {
        if (mAttributionSourceState.next != null
                && mAttributionSourceState.next.length > 0) {
            return mAttributionSourceState.next[0].uid;
        }
        return Process.INVALID_UID;
    }

    /**
     * @return The next package that would receive the permission protected data.
     *
     * @hide
     */
    public @Nullable String getNextPackageName() {
        if (mAttributionSourceState.next != null
                && mAttributionSourceState.next.length > 0) {
            return mAttributionSourceState.next[0].packageName;
        }
        return null;
    }

    /**
     * @return The next package's attribution tag that would receive
     * the permission protected data.
     *
     * @hide
     */
    public @Nullable String getNextAttributionTag() {
        if (mAttributionSourceState.next != null
                && mAttributionSourceState.next.length > 0) {
            return mAttributionSourceState.next[0].attributionTag;
        }
        return null;
    }

    /**
     * @return The next package's token that would receive
     * the permission protected data.
     *
     * @hide
     */
    public @Nullable IBinder getNextToken() {
        if (mAttributionSourceState.next != null
                && mAttributionSourceState.next.length > 0) {
            return mAttributionSourceState.next[0].token;
        }
        return null;
    }

    /**
     * Checks whether this attribution source can be trusted. That is whether
     * the app it refers to created it and provided to the attribution chain.
     *
     * @param context Context handle.
     * @return Whether this is a trusted source.
     */
    public boolean isTrusted(@NonNull Context context) {
        return mAttributionSourceState.token != null
                && context.getSystemService(PermissionManager.class)
                        .isRegisteredAttributionSource(this);
    }

    /**
     * Permissions that should be considered revoked regardless if granted.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS)
    @NonNull
    public Set<String> getRenouncedPermissions() {
        if (mRenouncedPermissionsCached == null) {
            if (mAttributionSourceState.renouncedPermissions != null) {
                mRenouncedPermissionsCached = new ArraySet<>(
                        mAttributionSourceState.renouncedPermissions);
            } else {
                mRenouncedPermissionsCached = Collections.emptySet();
            }
        }
        return mRenouncedPermissionsCached;
    }

    /**
     * The UID that is accessing the permission protected data.
     */
    public int getUid() {
        return mAttributionSourceState.uid;
    }

    /**
     * The PID that is accessing the permission protected data.
     */
    public int getPid() {
        return mAttributionSourceState.pid;
    }

    /**
     * The package that is accessing the permission protected data.
     */
    public @Nullable String getPackageName() {
        return mAttributionSourceState.packageName;
    }

    /**
     * The attribution tag of the app accessing the permission protected data.
     */
    public @Nullable String getAttributionTag() {
        return mAttributionSourceState.attributionTag;
    }

    /**
     * Gets the device ID for this attribution source. Attribution source can set the device ID
     * using {@link Builder#setDeviceId(int)}, the default device ID is
     * {@link Context#DEVICE_ID_DEFAULT}.
     * <p>
     * This device ID is used for permissions checking during attribution source validation.
     */
    @FlaggedApi(Flags.FLAG_DEVICE_AWARE_PERMISSION_APIS)
    public int getDeviceId() {
        return mAttributionSourceState.deviceId;
    }

    /**
     * Unique token for that source.
     *
     * @hide
     */
    public @NonNull IBinder getToken() {
        return mAttributionSourceState.token;
    }

    /**
     * The next app to receive the permission protected data.
     */
    public @Nullable AttributionSource getNext() {
        if (mNextCached == null && mAttributionSourceState.next != null
                && mAttributionSourceState.next.length > 0) {
            mNextCached = new AttributionSource(mAttributionSourceState.next[0]);
        }
        return mNextCached;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AttributionSource that = (AttributionSource) o;
        return equalsExceptToken(that) && Objects.equals(
                mAttributionSourceState.token, that.mAttributionSourceState.token);
    }

    /**
     * We store trusted attribution sources without their token (the token is the key to the map)
     * to avoid having a strong reference to the token. This means, when checking the equality of a
     * supplied AttributionSource in PermissionManagerService.isTrustedAttributionSource, we want to
     * compare everything except the token.
     *
     * @hide
     */
    public boolean equalsExceptToken(@Nullable AttributionSource o) {
        if (o == null) return false;
        return mAttributionSourceState.uid == o.mAttributionSourceState.uid
                && Objects.equals(mAttributionSourceState.packageName,
                o.mAttributionSourceState.packageName)
                && Objects.equals(mAttributionSourceState.attributionTag,
                o.mAttributionSourceState.attributionTag)
                && Arrays.equals(mAttributionSourceState.renouncedPermissions,
                o.mAttributionSourceState.renouncedPermissions)
                && Objects.equals(getNext(), o.getNext());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAttributionSourceState.uid, mAttributionSourceState.packageName,
                mAttributionSourceState.attributionTag, mAttributionSourceState.token,
                Arrays.hashCode(mAttributionSourceState.renouncedPermissions), getNext());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mAttributionSourceState.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() { return 0; }

    public static final @NonNull Parcelable.Creator<AttributionSource> CREATOR
            = new Parcelable.Creator<AttributionSource>() {
        @Override
        public AttributionSource[] newArray(int size) {
            return new AttributionSource[size];
        }

        @Override
        public AttributionSource createFromParcel(@NonNull Parcel in) {
            return new AttributionSource(in);
        }
    };

    /**
     * A builder for {@link AttributionSource}
     */
    public static final class Builder {
        private @NonNull final AttributionSourceState mAttributionSourceState =
                new AttributionSourceState();

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param uid
         *   The UID that is accessing the permission protected data.
         */
        public Builder(int uid) {
            mAttributionSourceState.uid = uid;
        }

        public Builder(@NonNull AttributionSource current) {
            if (current == null) {
                throw new IllegalArgumentException("current AttributionSource can not be null");
            }
            mAttributionSourceState.uid = current.getUid();
            mAttributionSourceState.pid = current.getPid();
            mAttributionSourceState.packageName = current.getPackageName();
            mAttributionSourceState.attributionTag = current.getAttributionTag();
            mAttributionSourceState.token = current.getToken();
            mAttributionSourceState.renouncedPermissions =
                current.mAttributionSourceState.renouncedPermissions;
        }

        /**
         * The PID of the process that is accessing the permission protected data.
         *
         * If not called, pid will default to {@link Process@INVALID_PID} (-1). This indicates that
         * the PID data is missing. Supplying a PID is not required, but recommended when
         * accessible.
         */
        public @NonNull Builder setPid(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mAttributionSourceState.pid = value;
            return this;
        }

        /**
         * The package that is accessing the permission protected data.
         */
        public @NonNull Builder setPackageName(@Nullable String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mAttributionSourceState.packageName = value;
            return this;
        }

        /**
         * The attribution tag of the app accessing the permission protected data.
         */
        public @NonNull Builder setAttributionTag(@Nullable String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mAttributionSourceState.attributionTag = value;
            return this;
        }

        /**
         * Sets permissions which have been voluntarily "renounced" by the
         * calling app.
         * <p>
         * Interactions performed through services obtained from the created
         * Context will ideally be treated as if these "renounced" permissions
         * have not actually been granted to the app, regardless of their actual
         * grant status.
         * <p>
         * This is designed for use by separate logical components within an app
         * which have no intention of interacting with data or services that are
         * protected by the renounced permissions.
         * <p>
         * Note that only {@link PermissionInfo#PROTECTION_DANGEROUS}
         * permissions are supported by this mechanism. Additionally, this
         * mechanism only applies to calls made through services obtained via
         * {@link Context#getSystemService}; it has no effect on static or raw
         * Binder calls.
         *
         * @param renouncedPermissions The set of permissions to treat as
         *            renounced, which is as if not granted.
         * @return This builder.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS)
        public @NonNull Builder setRenouncedPermissions(@Nullable Set<String> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mAttributionSourceState.renouncedPermissions = (value != null)
                    ? value.toArray(new String[0]) : null;
            return this;
        }

        /**
         * Set the device ID for this attribution source, permission check would happen
         * against this device ID.
         *
         * @return the builder
         */
        @FlaggedApi(Flags.FLAG_DEVICE_AWARE_PERMISSION_APIS)
        public @NonNull Builder setDeviceId(int deviceId) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x12;
            mAttributionSourceState.deviceId = deviceId;
            return this;
        }

        /**
         * The next app to receive the permission protected data.
         *
         * @deprecated Use {@link #setNextAttributionSource} instead.
         */
        @Deprecated
        public @NonNull Builder setNext(@Nullable AttributionSource value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mAttributionSourceState.next = (value != null) ? new AttributionSourceState[]
                    {value.mAttributionSourceState} : mAttributionSourceState.next;
            return this;
        }

        /**
         * The next app to receive the permission protected data.
         */
        @FlaggedApi(Flags.FLAG_SET_NEXT_ATTRIBUTION_SOURCE)
        public @NonNull Builder setNextAttributionSource(@NonNull AttributionSource value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mAttributionSourceState.next =
                    new AttributionSourceState[]{value.mAttributionSourceState};
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull AttributionSource build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40; // Mark builder used

            if ((mBuilderFieldsSet & 0x2) == 0) {
                mAttributionSourceState.pid = Process.INVALID_PID;
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mAttributionSourceState.packageName = null;
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mAttributionSourceState.attributionTag = null;
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mAttributionSourceState.renouncedPermissions = null;
            }
            if ((mBuilderFieldsSet & 0x12) == 0) {
                mAttributionSourceState.deviceId = Context.DEVICE_ID_DEFAULT;
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mAttributionSourceState.next = null;
            }

            mAttributionSourceState.token = sDefaultToken;

            if (mAttributionSourceState.next == null) {
                // The NDK aidl backend doesn't support null parcelable arrays.
                mAttributionSourceState.next = new AttributionSourceState[0];
            }
            return new AttributionSource(mAttributionSourceState);
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x40) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
