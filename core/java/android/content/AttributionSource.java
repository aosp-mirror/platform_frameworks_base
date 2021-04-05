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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.AppGlobals;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArraySet;

import com.android.internal.annotations.Immutable;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;

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
 * caller - which you cannot know ahead of time) you need to have a handle to the {@link
 * AttributionSource} for the calling app's context in order to create an attribution context.
 * This means you either need to have an API for the other app to send you its attribution
 * source or use a platform API that pipes the callers attribution source.
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
// TODO: Codegen doesn't properly verify the class if the parcelling is inner class
// TODO: Codegen doesn't allow overriding the constructor to change its visibility
// TODO: Codegen applies method level annotations to argument vs the generated member (@SystemApi)
// TODO: Codegen doesn't properly read/write IBinder members
// TODO: Codegen doesn't properly handle Set arguments
// @DataClass(genEqualsHashCode = true, genConstructor = false, genBuilder = true)
public final class AttributionSource implements Parcelable {
    /**
     * @hide
     */
    static class RenouncedPermissionsParcelling implements Parcelling<Set<String>> {

        @Override
        public void parcel(Set<String> item, Parcel dest, int parcelFlags) {
            if (item == null) {
                dest.writeInt(-1);
            } else {
                dest.writeInt(item.size());
                for (String permission : item) {
                    dest.writeString8(permission);
                }
            }
        }

        @Override
        public Set<String> unparcel(Parcel source) {
            final int size = source.readInt();
            if (size < 0) {
                return null;
            }
            final ArraySet<String> result = new ArraySet<>(size);
            for (int i = 0; i < size; i++) {
                result.add(source.readString8());
            }
            return result;
        }
    }

    /**
     * The UID that is accessing the permission protected data.
     */
    private final int mUid;

    /**
     * The package that is accessing the permission protected data.
     */
    private @Nullable String mPackageName = null;

    /**
     * The attribution tag of the app accessing the permission protected data.
     */
    private @Nullable String mAttributionTag = null;

    /**
     * Unique token for that source.
     *
     * @hide
     */
    private @Nullable IBinder mToken = null;

    /**
     * Permissions that should be considered revoked regardless if granted.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS)
    @DataClass.ParcelWith(RenouncedPermissionsParcelling.class)
    private @Nullable Set<String> mRenouncedPermissions = null;

    /**
     * The next app to receive the permission protected data.
     */
    private @Nullable AttributionSource mNext = null;

    /** @hide */
    @TestApi
    public AttributionSource(int uid, @Nullable String packageName,
            @Nullable String attributionTag) {
        this(uid, packageName, attributionTag, /*next*/ null);
    }

    /** @hide */
    @TestApi
    public AttributionSource(int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable AttributionSource next) {
        this(uid, packageName, attributionTag, /*token*/ null,
                /*renouncedPermissions*/ null, next);
    }

    /** @hide */
    @TestApi
    public AttributionSource(int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable Set<String> renouncedPermissions,
            @Nullable AttributionSource next) {
        this(uid, packageName, attributionTag, /*token*/ null,
                renouncedPermissions, next);
    }

    /** @hide */
    public AttributionSource(@NonNull AttributionSource current,
            @Nullable AttributionSource next) {
        this(current.getUid(), current.getPackageName(), current.getAttributionTag(),
                /*token*/ null, /*renouncedPermissions*/ null, next);
    }

    /** @hide */
    public AttributionSource withNextAttributionSource(@Nullable AttributionSource next) {
        return new AttributionSource(mUid, mPackageName, mAttributionTag,  mToken,
                mRenouncedPermissions, next);
    }

    /** @hide */
    public AttributionSource withToken(@Nullable IBinder token) {
        return new AttributionSource(mUid, mPackageName, mAttributionTag, token,
                mRenouncedPermissions, mNext);
    }

    /**
     * If you are handling an IPC and you don't trust the caller you need to validate
     * whether the attribution source is one for the calling app to prevent the caller
     * to pass you a source from another app without including themselves in the
     * attribution chain.
     *
     * @throws SecurityException if the attribution source cannot be trusted to be
     * from the caller.
     */
    public void enforceCallingUid() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != mUid) {
            throw new SecurityException("Calling uid: " + callingUid
                    + " doesn't match source uid: " + mUid);
        }
        // No need to check package as app ops manager does it already.
    }

    /**
     * If you are handling an IPC and you don't trust the caller you need to validate
     * whether the attribution source is one for the calling app to prevent the caller
     * to pass you a source from another app without including themselves in the
     * attribution chain.
     *f
     * @return if the attribution source cannot be trusted to be from the caller.
     */
    public boolean checkCallingUid() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != mUid) {
            return false;
        }
        // No need to check package as app ops manager does it already.
        return true;
    }

    @Override
    public String toString() {
        if (Build.IS_DEBUGGABLE) {
            return "AttributionSource { " +
                    "uid = " + mUid + ", " +
                    "packageName = " + mPackageName + ", " +
                    "attributionTag = " + mAttributionTag + ", " +
                    "token = " + mToken + ", " +
                    "next = " + mNext +
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
        if (mNext != null) {
            return mNext.getUid();
        }
        return Process.INVALID_UID;
    }

    /**
     * @return The next package that would receive the permission protected data.
     *
     * @hide
     */
    public @Nullable String getNextPackageName() {
        if (mNext != null) {
            return mNext.getPackageName();
        }
        return null;
    }

    /**
     * @return The nexxt package's attribution tag that would receive
     * the permission protected data.
     *
     * @hide
     */
    public @Nullable String getNextAttributionTag() {
        if (mNext != null) {
            return mNext.getAttributionTag();
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
        return mToken != null && context.getSystemService(PermissionManager.class)
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
        return CollectionUtils.emptyIfNull(mRenouncedPermissions);
    }

    @DataClass.Suppress({"setUid", "setToken"})
    static class BaseBuilder {}






    // Code below generated by codegen v1.0.22.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/AttributionSource.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /* package-private */ AttributionSource(
            int uid,
            @Nullable String packageName,
            @Nullable String attributionTag,
            @Nullable IBinder token,
            @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS) @Nullable Set<String> renouncedPermissions,
            @Nullable AttributionSource next) {
        this.mUid = uid;
        this.mPackageName = packageName;
        this.mAttributionTag = attributionTag;
        this.mToken = token;
        this.mRenouncedPermissions = renouncedPermissions;
        com.android.internal.util.AnnotationValidations.validate(
                SystemApi.class, null, mRenouncedPermissions);
        com.android.internal.util.AnnotationValidations.validate(
                RequiresPermission.class, null, mRenouncedPermissions,
                "value", android.Manifest.permission.RENOUNCE_PERMISSIONS);
        this.mNext = next;

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * The UID that is accessing the permission protected data.
     */
    public int getUid() {
        return mUid;
    }

    /**
     * The package that is accessing the permission protected data.
     */
    public @Nullable String getPackageName() {
        return mPackageName;
    }

    /**
     * The attribution tag of the app accessing the permission protected data.
     */
    public @Nullable String getAttributionTag() {
        return mAttributionTag;
    }

    /**
     * Unique token for that source.
     *
     * @hide
     */
    public @Nullable IBinder getToken() {
        return mToken;
    }

    /**
     * The next app to receive the permission protected data.
     */
    public @Nullable AttributionSource getNext() {
        return mNext;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(AttributionSource other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        AttributionSource that = (AttributionSource) o;
        //noinspection PointlessBooleanExpression
        return true
                && mUid == that.mUid
                && Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mAttributionTag, that.mAttributionTag)
                && Objects.equals(mToken, that.mToken)
                && Objects.equals(mRenouncedPermissions, that.mRenouncedPermissions)
                && Objects.equals(mNext, that.mNext);
    }

    @Override
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mUid;
        _hash = 31 * _hash + Objects.hashCode(mPackageName);
        _hash = 31 * _hash + Objects.hashCode(mAttributionTag);
        _hash = 31 * _hash + Objects.hashCode(mToken);
        _hash = 31 * _hash + Objects.hashCode(mRenouncedPermissions);
        _hash = 31 * _hash + Objects.hashCode(mNext);
        return _hash;
    }

    static Parcelling<Set<String>> sParcellingForRenouncedPermissions =
            Parcelling.Cache.get(
                    RenouncedPermissionsParcelling.class);
    static {
        if (sParcellingForRenouncedPermissions == null) {
            sParcellingForRenouncedPermissions = Parcelling.Cache.put(
                    new RenouncedPermissionsParcelling());
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mPackageName != null) flg |= 0x2;
        if (mAttributionTag != null) flg |= 0x4;
        if (mToken != null) flg |= 0x8;
        if (mRenouncedPermissions != null) flg |= 0x10;
        if (mNext != null) flg |= 0x20;
        dest.writeByte(flg);
        dest.writeInt(mUid);
        if (mPackageName != null) dest.writeString(mPackageName);
        if (mAttributionTag != null) dest.writeString(mAttributionTag);
        if (mToken != null) dest.writeStrongBinder(mToken);
        sParcellingForRenouncedPermissions.parcel(mRenouncedPermissions, dest, flags);
        if (mNext != null) dest.writeTypedObject(mNext, flags);
    }

    @Override
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */ AttributionSource(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        int uid = in.readInt();
        String packageName = (flg & 0x2) == 0 ? null : in.readString();
        String attributionTag = (flg & 0x4) == 0 ? null : in.readString();
        IBinder token = (flg & 0x8) == 0 ? null : in.readStrongBinder();
        Set<String> renouncedPermissions = sParcellingForRenouncedPermissions.unparcel(in);
        AttributionSource next = (flg & 0x20) == 0 ? null : (AttributionSource) in.readTypedObject(AttributionSource.CREATOR);

        this.mUid = uid;
        this.mPackageName = packageName;
        this.mAttributionTag = attributionTag;
        this.mToken = token;
        this.mRenouncedPermissions = renouncedPermissions;
        com.android.internal.util.AnnotationValidations.validate(
                SystemApi.class, null, mRenouncedPermissions);
        com.android.internal.util.AnnotationValidations.validate(
                RequiresPermission.class, null, mRenouncedPermissions,
                "value", android.Manifest.permission.RENOUNCE_PERMISSIONS);
        this.mNext = next;

        // onConstructed(); // You can define this method to get a callback
    }

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
    @SuppressWarnings("WeakerAccess")
    public static final class Builder extends BaseBuilder {

        private int mUid;
        private @Nullable String mPackageName;
        private @Nullable String mAttributionTag;
        private @Nullable IBinder mToken;
        private @SystemApi @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS) @Nullable Set<String> mRenouncedPermissions;
        private @Nullable AttributionSource mNext;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param uid
         *   The UID that is accessing the permission protected data.
         */
        public Builder(
                int uid) {
            mUid = uid;
        }

        /**
         * The package that is accessing the permission protected data.
         */
        public @NonNull Builder setPackageName(@Nullable String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mPackageName = value;
            return this;
        }

        /**
         * The attribution tag of the app accessing the permission protected data.
         */
        public @NonNull Builder setAttributionTag(@Nullable String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mAttributionTag = value;
            return this;
        }

        /**
         * Permissions that should be considered revoked regardless if granted.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.RENOUNCE_PERMISSIONS)
        public @NonNull Builder setRenouncedPermissions(@Nullable Set<String> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mRenouncedPermissions = value;
            return this;
        }

        /**
         * The next app to receive the permission protected data.
         */
        public @NonNull Builder setNext(@Nullable AttributionSource value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mNext = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull AttributionSource build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40; // Mark builder used

            if ((mBuilderFieldsSet & 0x2) == 0) {
                mPackageName = null;
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mAttributionTag = null;
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mToken = null;
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mRenouncedPermissions = null;
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mNext = null;
            }
            AttributionSource o = new AttributionSource(
                    mUid,
                    mPackageName,
                    mAttributionTag,
                    mToken,
                    mRenouncedPermissions,
                    mNext);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x40) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }


    //@formatter:on
    // End of generated code

}
