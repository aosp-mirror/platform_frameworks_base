/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.content.pm;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * This class provides information for a shared library. There are
 * four types of shared libraries: builtin - non-updatable part of
 * the OS; dynamic - updatable backwards-compatible dynamically linked;
 * static - non backwards-compatible emulating static linking;
 * SDK - updatable backwards-incompatible dynamically loaded.
 */
public final class SharedLibraryInfo implements Parcelable {

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_BUILTIN,
            TYPE_DYNAMIC,
            TYPE_STATIC,
            TYPE_SDK_PACKAGE,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Type{}

    /**
     * Shared library type: this library is a part of the OS
     * and cannot be updated or uninstalled.
     */
    public static final int TYPE_BUILTIN = 0;

    /**
     * Shared library type: this library is backwards-compatible, can
     * be updated, and updates can be uninstalled. Clients link against
     * the latest version of the library.
     */
    public static final int TYPE_DYNAMIC = 1;

    /**
     * Shared library type: this library is <strong>not</strong> backwards
     * -compatible, can be updated and updates can be uninstalled. Clients
     * link against a specific version of the library.
     *
     * Static shared libraries simulate static linking while allowing for
     * multiple clients to reuse the same instance of the library.
     */
    public static final int TYPE_STATIC = 2;

    /**
     * SDK package shared library type: this library is <strong>not</strong>
     * compatible between versions, can be updated and updates can be
     * uninstalled. Clients depend on a specific version of the library.
     *
     * SDK packages are not loaded automatically by the OS and rely
     * e.g. on 3P libraries to make them available for the clients.
     */
    public static final int TYPE_SDK_PACKAGE = 3;

    /**
     * Constant for referring to an undefined version.
     */
    public static final int VERSION_UNDEFINED = -1;

    private final String mPath;
    private final String mPackageName;
    private final String mName;
    private final List<String> mCodePaths;

    private final long mVersion;
    private final @Type int mType;
    private final boolean mIsNative;
    private final VersionedPackage mDeclaringPackage;
    private final List<VersionedPackage> mDependentPackages;
    private List<SharedLibraryInfo> mDependencies;

    /**
     * Creates a new instance.
     *
     * @param codePaths For a non {@link #TYPE_BUILTIN builtin} library, the locations of jars of
     *                  this shared library. Null for builtin library.
     * @param name The lib name.
     * @param version The lib version if not builtin.
     * @param type The lib type.
     * @param declaringPackage The package that declares the library.
     * @param dependentPackages The packages that depend on the library.
     * @param isNative indicate if this shared lib is a native lib or not (i.e. java)
     *
     * @hide
     */
    public SharedLibraryInfo(String path, String packageName, List<String> codePaths,
            String name, long version, int type,
            VersionedPackage declaringPackage, List<VersionedPackage> dependentPackages,
            List<SharedLibraryInfo> dependencies, boolean isNative) {
        mPath = path;
        mPackageName = packageName;
        mCodePaths = codePaths;
        mName = name;
        mVersion = version;
        mType = type;
        mDeclaringPackage = declaringPackage;
        mDependentPackages = dependentPackages;
        mDependencies = dependencies;
        mIsNative = isNative;
    }

    private SharedLibraryInfo(Parcel parcel) {
        mPath = parcel.readString8();
        mPackageName = parcel.readString8();
        if (parcel.readInt() != 0) {
            mCodePaths = Arrays.asList(parcel.createString8Array());
        } else {
            mCodePaths = null;
        }
        mName = parcel.readString8();
        mVersion = parcel.readLong();
        mType = parcel.readInt();
        mDeclaringPackage = parcel.readParcelable(null, android.content.pm.VersionedPackage.class);
        mDependentPackages = parcel.readArrayList(null, android.content.pm.VersionedPackage.class);
        mDependencies = parcel.createTypedArrayList(SharedLibraryInfo.CREATOR);
        mIsNative = parcel.readBoolean();
    }

    /**
     * Gets the type of this library.
     *
     * @return The library type.
     */
    public @Type int getType() {
        return mType;
    }

    /**
     * Tells whether this library is a native shared library or not.
     *
     * @hide
     */
    @TestApi
    public boolean isNative() {
        return mIsNative;
    }

    /**
     * Gets the library name an app defines in its manifest
     * to depend on the library.
     *
     * @return The name.
     */
    public String getName() {
        return mName;
    }

    /**
     * If the shared library is a jar file, returns the path of that jar. Null otherwise.
     * Only libraries with TYPE_BUILTIN are in jar files.
     *
     * @return The path.
     *
     * @hide
     */
    public @Nullable String getPath() {
        return mPath;
    }

    /**
     * If the shared library is an apk, returns the package name. Null otherwise.
     * Only libraries with TYPE_DYNAMIC or TYPE_STATIC are in apks.
     *
     * @return The package name.
     *
     * @hide
     */
    public @Nullable String getPackageName() {
        return mPackageName;
    }

    /**
     * Get all code paths for that library.
     *
     * @return All code paths.
     *
     * @hide
     */
    @TestApi
    public @NonNull List<String> getAllCodePaths() {
        if (getPath() != null) {
            // Builtin library.
            ArrayList<String> list = new ArrayList<>();
            list.add(getPath());
            return list;
        } else {
            // Static or dynamic library.
            return Objects.requireNonNull(mCodePaths);
        }
    }

    /**
     * Add a library dependency to that library. Note that this
     * should be called under the package manager lock.
     *
     * @hide
     */
    public void addDependency(@Nullable SharedLibraryInfo info) {
        if (info == null) {
            // For convenience of the caller, allow null to be passed.
            // This can happen when we create the dependencies of builtin
            // libraries.
            return;
        }
        if (mDependencies == null) {
            mDependencies = new ArrayList<>();
        }
        mDependencies.add(info);
    }

    /**
     * Clear all dependencies.
     *
     * @hide
     */
    public void clearDependencies() {
        mDependencies = null;
    }

    /**
     * Gets the libraries this library directly depends on. Note that
     * the package manager prevents recursive dependencies when installing
     * a package.
     *
     * @return The dependencies.
     *
     * @hide
     */
    public @Nullable List<SharedLibraryInfo> getDependencies() {
        return mDependencies;
    }

    /**
     * @deprecated Use {@link #getLongVersion()} instead.
     */
    @Deprecated
    public @IntRange(from = -1) int getVersion() {
        return mVersion < 0 ? (int) mVersion : (int) (mVersion & 0x7fffffff);
    }

    /**
     * Gets the version of the library. For {@link #TYPE_STATIC static} libraries
     * this is the declared version and for {@link #TYPE_DYNAMIC dynamic} and
     * {@link #TYPE_BUILTIN builtin} it is {@link #VERSION_UNDEFINED} as these
     * are not versioned.
     *
     * @return The version.
     */
    public @IntRange(from = -1) long getLongVersion() {
        return mVersion;
    }

    /**
     * @removed
     */
    public boolean isBuiltin() {
        return mType == TYPE_BUILTIN;
    }

    /**
     * @removed
     */
    public boolean isDynamic() {
        return mType == TYPE_DYNAMIC;
    }

    /**
     * @removed
     */
    public boolean isStatic() {
        return mType == TYPE_STATIC;
    }

    /**
     * @hide
     */
    public boolean isSdk() {
        return mType == TYPE_SDK_PACKAGE;
    }

    /**
     * Gets the package that declares the library.
     *
     * @return The package declaring the library.
     */
    public @NonNull VersionedPackage getDeclaringPackage() {
        return mDeclaringPackage;
    }

    /**
     * Gets the packages that depend on the library.
     *
     * @return The dependent packages.
     */
    public @NonNull List<VersionedPackage> getDependentPackages() {
        if (mDependentPackages == null) {
            return Collections.emptyList();
        }
        return mDependentPackages;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "SharedLibraryInfo{name:" + mName + ", type:" + typeToString(mType)
                + ", version:" + mVersion + (!getDependentPackages().isEmpty()
                ? " has dependents" : "") + "}";
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString8(mPath);
        parcel.writeString8(mPackageName);
        if (mCodePaths != null) {
            parcel.writeInt(1);
            parcel.writeString8Array(mCodePaths.toArray(new String[mCodePaths.size()]));
        } else {
            parcel.writeInt(0);
        }
        parcel.writeString8(mName);
        parcel.writeLong(mVersion);
        parcel.writeInt(mType);
        parcel.writeParcelable(mDeclaringPackage, flags);
        parcel.writeList(mDependentPackages);
        parcel.writeTypedList(mDependencies);
        parcel.writeBoolean(mIsNative);
    }

    private static String typeToString(int type) {
        switch (type) {
            case TYPE_BUILTIN: {
                return "builtin";
            }
            case TYPE_DYNAMIC: {
                return "dynamic";
            }
            case TYPE_STATIC: {
                return "static";
            }
            case TYPE_SDK_PACKAGE: {
                return "sdk";
            }
            default: {
                return "unknown";
            }
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SharedLibraryInfo> CREATOR =
            new Parcelable.Creator<SharedLibraryInfo>() {
        public SharedLibraryInfo createFromParcel(Parcel source) {
            return new SharedLibraryInfo(source);
        }

        public SharedLibraryInfo[] newArray(int size) {
            return new SharedLibraryInfo[size];
        }
    };
}
