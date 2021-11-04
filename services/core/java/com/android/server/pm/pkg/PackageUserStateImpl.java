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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.overlay.OverlayPaths;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DataClass;

import java.util.Objects;

@DataClass(genConstructor = false, genBuilder = false, genEqualsHashCode = true)
@DataClass.Suppress({"mOverlayPathsLock", "mOverlayPaths", "mSharedLibraryOverlayPathsLock",
        "mSharedLibraryOverlayPaths", "setOverlayPaths", "mCachedOverlayPathsLock",
        "mCachedOverlayPaths", "setCachedOverlayPaths"})
public class PackageUserStateImpl implements PackageUserStateInternal {

    @Nullable
    protected ArraySet<String> mDisabledComponents;
    @Nullable
    protected ArraySet<String> mEnabledComponents;

    private long mCeDataInode;
    private boolean mInstalled = true;
    private boolean mStopped;
    private boolean mNotLaunched;
    private boolean mHidden; // Is the app restricted by owner / admin
    private int mDistractionFlags;
    private boolean mSuspended;
    private boolean mInstantApp;
    private boolean mVirtualPreload;
    private int mEnabledState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    @PackageManager.InstallReason
    private int mInstallReason = PackageManager.INSTALL_REASON_UNKNOWN;
    @PackageManager.UninstallReason
    private int mUninstallReason = PackageManager.UNINSTALL_REASON_UNKNOWN;
    @Nullable
    private String mHarmfulAppWarning;
    @Nullable
    private String mLastDisableAppCaller;

    @Nullable
    protected OverlayPaths mOverlayPaths;

    // Lib name to overlay paths
    @Nullable
    protected ArrayMap<String, OverlayPaths> mSharedLibraryOverlayPaths;

    @Nullable
    private String mSplashScreenTheme;

    /** Suspending package to suspend params */
    @Nullable
    private ArrayMap<String, SuspendParams> mSuspendParams;

    @Nullable
    private OverlayPaths mCachedOverlayPaths;

    @Nullable
    private ArrayMap<ComponentName, Pair<String, Integer>> mComponentLabelIconOverrideMap;

    public PackageUserStateImpl() {
        super();
    }

    public PackageUserStateImpl(PackageUserStateImpl other) {
        mDisabledComponents = ArrayUtils.cloneOrNull(other.mDisabledComponents);
        mEnabledComponents = ArrayUtils.cloneOrNull(other.mEnabledComponents);
        mOverlayPaths = other.mOverlayPaths;
        if (other.mSharedLibraryOverlayPaths != null) {
            mSharedLibraryOverlayPaths = new ArrayMap<>(other.mSharedLibraryOverlayPaths);
        }
        mDisabledComponents = other.mDisabledComponents;
        mEnabledComponents = other.mEnabledComponents;
        mCeDataInode = other.mCeDataInode;
        mInstalled = other.mInstalled;
        mStopped = other.mStopped;
        mNotLaunched = other.mNotLaunched;
        mHidden = other.mHidden;
        mDistractionFlags = other.mDistractionFlags;
        mSuspended = other.mSuspended;
        mInstantApp = other.mInstantApp;
        mVirtualPreload = other.mVirtualPreload;
        mEnabledState = other.mEnabledState;
        mInstallReason = other.mInstallReason;
        mUninstallReason = other.mUninstallReason;
        mHarmfulAppWarning = other.mHarmfulAppWarning;
        mLastDisableAppCaller = other.mLastDisableAppCaller;
        mOverlayPaths = other.mOverlayPaths;
        mSharedLibraryOverlayPaths = other.mSharedLibraryOverlayPaths;
        mSplashScreenTheme = other.mSplashScreenTheme;
        mSuspendParams = other.mSuspendParams == null ? null : new ArrayMap<>(other.mSuspendParams);
        mComponentLabelIconOverrideMap = other.mComponentLabelIconOverrideMap == null ? null
                : new ArrayMap<>(other.mComponentLabelIconOverrideMap);
    }

    /**
     * Sets the path of overlays currently enabled for this package and user combination.
     * @return true if the path contents differ than what they were previously
     */
    @Nullable
    public boolean setOverlayPaths(@Nullable OverlayPaths paths) {
        if (Objects.equals(paths, mOverlayPaths)) {
            return false;
        }
        if ((mOverlayPaths == null && paths.isEmpty())
                || (paths == null && mOverlayPaths.isEmpty())) {
            return false;
        }
        mOverlayPaths = paths;
        mCachedOverlayPaths = null;
        return true;
    }

    /**
     * Sets the path of overlays currently enabled for a library that this package uses.
     *
     * @return true if the path contents for the library differ than what they were previously
     */
    public boolean setSharedLibraryOverlayPaths(@NonNull String library,
            @Nullable OverlayPaths paths) {
        if (mSharedLibraryOverlayPaths == null) {
            mSharedLibraryOverlayPaths = new ArrayMap<>();
        }
        final OverlayPaths currentPaths = mSharedLibraryOverlayPaths.get(library);
        if (Objects.equals(paths, currentPaths)) {
            return false;
        }
        mCachedOverlayPaths = null;
        if (paths == null || paths.isEmpty()) {
            return mSharedLibraryOverlayPaths.remove(library) != null;
        } else {
            mSharedLibraryOverlayPaths.put(library, paths);
            return true;
        }
    }

    @Nullable
    @Override
    public ArraySet<String> getDisabledComponentsNoCopy() {
        return mDisabledComponents;
    }

    @Nullable
    @Override
    public ArraySet<String> getEnabledComponentsNoCopy() {
        return mEnabledComponents;
    }

    @Override
    public boolean isComponentEnabled(String componentName) {
        // TODO: Not locked
        return ArrayUtils.contains(mEnabledComponents, componentName);
    }

    @Override
    public boolean isComponentDisabled(String componentName) {
        // TODO: Not locked
        return ArrayUtils.contains(mDisabledComponents, componentName);
    }

    @Override
    public OverlayPaths getAllOverlayPaths() {
        if (mOverlayPaths == null && mSharedLibraryOverlayPaths == null) {
            return null;
        }
        final OverlayPaths.Builder newPaths = new OverlayPaths.Builder();
        newPaths.addAll(mOverlayPaths);
        if (mSharedLibraryOverlayPaths != null) {
            for (final OverlayPaths libOverlayPaths : mSharedLibraryOverlayPaths.values()) {
                newPaths.addAll(libOverlayPaths);
            }
        }
        return newPaths.build();
    }

    /**
     * Overrides the non-localized label and icon of a component.
     *
     * @return true if the label or icon was changed.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean overrideLabelAndIcon(@NonNull ComponentName component,
            @Nullable String nonLocalizedLabel, @Nullable Integer icon) {
        String existingLabel = null;
        Integer existingIcon = null;

        if (mComponentLabelIconOverrideMap != null) {
            Pair<String, Integer> pair = mComponentLabelIconOverrideMap.get(component);
            if (pair != null) {
                existingLabel = pair.first;
                existingIcon = pair.second;
            }
        }

        boolean changed = !TextUtils.equals(existingLabel, nonLocalizedLabel)
                || !Objects.equals(existingIcon, icon);

        if (changed) {
            if (nonLocalizedLabel == null && icon == null) {
                mComponentLabelIconOverrideMap.remove(component);
                if (mComponentLabelIconOverrideMap.isEmpty()) {
                    mComponentLabelIconOverrideMap = null;
                }
            } else {
                if (mComponentLabelIconOverrideMap == null) {
                    mComponentLabelIconOverrideMap = new ArrayMap<>(1);
                }

                mComponentLabelIconOverrideMap.put(component, Pair.create(nonLocalizedLabel, icon));
            }
        }

        return changed;
    }

    /**
     * Clears all values previously set by {@link #overrideLabelAndIcon(ComponentName,
     * String, Integer)}.
     *
     * This is done when the package is updated as the components and resource IDs may have changed.
     */
    public void resetOverrideComponentLabelIcon() {
        mComponentLabelIconOverrideMap = null;
    }

    @Nullable
    public Pair<String, Integer> getOverrideLabelIconForComponent(ComponentName componentName) {
        if (ArrayUtils.isEmpty(mComponentLabelIconOverrideMap)) {
            return null;
        }

        return mComponentLabelIconOverrideMap.get(componentName);
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/services/core/java/com/android/server/pm/pkg/PackageUserStateImpl.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public @Nullable ArraySet<String> getDisabledComponents() {
        return mDisabledComponents;
    }

    @DataClass.Generated.Member
    public @Nullable ArraySet<String> getEnabledComponents() {
        return mEnabledComponents;
    }

    @DataClass.Generated.Member
    public long getCeDataInode() {
        return mCeDataInode;
    }

    @DataClass.Generated.Member
    public boolean isInstalled() {
        return mInstalled;
    }

    @DataClass.Generated.Member
    public boolean isStopped() {
        return mStopped;
    }

    @DataClass.Generated.Member
    public boolean isNotLaunched() {
        return mNotLaunched;
    }

    @DataClass.Generated.Member
    public boolean isHidden() {
        return mHidden;
    }

    @DataClass.Generated.Member
    public int getDistractionFlags() {
        return mDistractionFlags;
    }

    @DataClass.Generated.Member
    public boolean isSuspended() {
        return mSuspended;
    }

    @DataClass.Generated.Member
    public boolean isInstantApp() {
        return mInstantApp;
    }

    @DataClass.Generated.Member
    public boolean isVirtualPreload() {
        return mVirtualPreload;
    }

    @DataClass.Generated.Member
    public int getEnabledState() {
        return mEnabledState;
    }

    @DataClass.Generated.Member
    public @PackageManager.InstallReason int getInstallReason() {
        return mInstallReason;
    }

    @DataClass.Generated.Member
    public @PackageManager.UninstallReason int getUninstallReason() {
        return mUninstallReason;
    }

    @DataClass.Generated.Member
    public @Nullable String getHarmfulAppWarning() {
        return mHarmfulAppWarning;
    }

    @DataClass.Generated.Member
    public @Nullable String getLastDisableAppCaller() {
        return mLastDisableAppCaller;
    }

    @DataClass.Generated.Member
    public @Nullable OverlayPaths getOverlayPaths() {
        return mOverlayPaths;
    }

    @DataClass.Generated.Member
    public @Nullable ArrayMap<String,OverlayPaths> getSharedLibraryOverlayPaths() {
        return mSharedLibraryOverlayPaths;
    }

    @DataClass.Generated.Member
    public @Nullable String getSplashScreenTheme() {
        return mSplashScreenTheme;
    }

    /**
     * Suspending package to suspend params
     */
    @DataClass.Generated.Member
    public @Nullable ArrayMap<String,SuspendParams> getSuspendParams() {
        return mSuspendParams;
    }

    @DataClass.Generated.Member
    public @Nullable OverlayPaths getCachedOverlayPaths() {
        return mCachedOverlayPaths;
    }

    @DataClass.Generated.Member
    public @Nullable ArrayMap<ComponentName,Pair<String,Integer>> getComponentLabelIconOverrideMap() {
        return mComponentLabelIconOverrideMap;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setDisabledComponents(@NonNull ArraySet<String> value) {
        mDisabledComponents = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setEnabledComponents(@NonNull ArraySet<String> value) {
        mEnabledComponents = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setCeDataInode( long value) {
        mCeDataInode = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setInstalled( boolean value) {
        mInstalled = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setStopped( boolean value) {
        mStopped = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setNotLaunched( boolean value) {
        mNotLaunched = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setHidden( boolean value) {
        mHidden = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setDistractionFlags( int value) {
        mDistractionFlags = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setSuspended( boolean value) {
        mSuspended = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setInstantApp( boolean value) {
        mInstantApp = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setVirtualPreload( boolean value) {
        mVirtualPreload = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setEnabledState( int value) {
        mEnabledState = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setInstallReason(@PackageManager.InstallReason int value) {
        mInstallReason = value;
        com.android.internal.util.AnnotationValidations.validate(
                PackageManager.InstallReason.class, null, mInstallReason);
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setUninstallReason(@PackageManager.UninstallReason int value) {
        mUninstallReason = value;
        com.android.internal.util.AnnotationValidations.validate(
                PackageManager.UninstallReason.class, null, mUninstallReason);
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setHarmfulAppWarning(@NonNull String value) {
        mHarmfulAppWarning = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setLastDisableAppCaller(@NonNull String value) {
        mLastDisableAppCaller = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setSharedLibraryOverlayPaths(@NonNull ArrayMap<String,OverlayPaths> value) {
        mSharedLibraryOverlayPaths = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setSplashScreenTheme(@NonNull String value) {
        mSplashScreenTheme = value;
        return this;
    }

    /**
     * Suspending package to suspend params
     */
    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setSuspendParams(@NonNull ArrayMap<String,SuspendParams> value) {
        mSuspendParams = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setComponentLabelIconOverrideMap(@NonNull ArrayMap<ComponentName,Pair<String,Integer>> value) {
        mComponentLabelIconOverrideMap = value;
        return this;
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(PackageUserStateImpl other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        PackageUserStateImpl that = (PackageUserStateImpl) o;
        //noinspection PointlessBooleanExpression
        return true
                && Objects.equals(mDisabledComponents, that.mDisabledComponents)
                && Objects.equals(mEnabledComponents, that.mEnabledComponents)
                && mCeDataInode == that.mCeDataInode
                && mInstalled == that.mInstalled
                && mStopped == that.mStopped
                && mNotLaunched == that.mNotLaunched
                && mHidden == that.mHidden
                && mDistractionFlags == that.mDistractionFlags
                && mSuspended == that.mSuspended
                && mInstantApp == that.mInstantApp
                && mVirtualPreload == that.mVirtualPreload
                && mEnabledState == that.mEnabledState
                && mInstallReason == that.mInstallReason
                && mUninstallReason == that.mUninstallReason
                && Objects.equals(mHarmfulAppWarning, that.mHarmfulAppWarning)
                && Objects.equals(mLastDisableAppCaller, that.mLastDisableAppCaller)
                && Objects.equals(mOverlayPaths, that.mOverlayPaths)
                && Objects.equals(mSharedLibraryOverlayPaths, that.mSharedLibraryOverlayPaths)
                && Objects.equals(mSplashScreenTheme, that.mSplashScreenTheme)
                && Objects.equals(mSuspendParams, that.mSuspendParams)
                && Objects.equals(mCachedOverlayPaths, that.mCachedOverlayPaths)
                && Objects.equals(mComponentLabelIconOverrideMap, that.mComponentLabelIconOverrideMap);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + Objects.hashCode(mDisabledComponents);
        _hash = 31 * _hash + Objects.hashCode(mEnabledComponents);
        _hash = 31 * _hash + Long.hashCode(mCeDataInode);
        _hash = 31 * _hash + Boolean.hashCode(mInstalled);
        _hash = 31 * _hash + Boolean.hashCode(mStopped);
        _hash = 31 * _hash + Boolean.hashCode(mNotLaunched);
        _hash = 31 * _hash + Boolean.hashCode(mHidden);
        _hash = 31 * _hash + mDistractionFlags;
        _hash = 31 * _hash + Boolean.hashCode(mSuspended);
        _hash = 31 * _hash + Boolean.hashCode(mInstantApp);
        _hash = 31 * _hash + Boolean.hashCode(mVirtualPreload);
        _hash = 31 * _hash + mEnabledState;
        _hash = 31 * _hash + mInstallReason;
        _hash = 31 * _hash + mUninstallReason;
        _hash = 31 * _hash + Objects.hashCode(mHarmfulAppWarning);
        _hash = 31 * _hash + Objects.hashCode(mLastDisableAppCaller);
        _hash = 31 * _hash + Objects.hashCode(mOverlayPaths);
        _hash = 31 * _hash + Objects.hashCode(mSharedLibraryOverlayPaths);
        _hash = 31 * _hash + Objects.hashCode(mSplashScreenTheme);
        _hash = 31 * _hash + Objects.hashCode(mSuspendParams);
        _hash = 31 * _hash + Objects.hashCode(mCachedOverlayPaths);
        _hash = 31 * _hash + Objects.hashCode(mComponentLabelIconOverrideMap);
        return _hash;
    }

    @DataClass.Generated(
            time = 1633983318771L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/pkg/PackageUserStateImpl.java",
            inputSignatures = "protected @android.annotation.Nullable android.util.ArraySet<java.lang.String> mDisabledComponents\nprotected @android.annotation.Nullable android.util.ArraySet<java.lang.String> mEnabledComponents\nprivate  long mCeDataInode\nprivate  boolean mInstalled\nprivate  boolean mStopped\nprivate  boolean mNotLaunched\nprivate  boolean mHidden\nprivate  int mDistractionFlags\nprivate  boolean mSuspended\nprivate  boolean mInstantApp\nprivate  boolean mVirtualPreload\nprivate  int mEnabledState\nprivate @android.content.pm.PackageManager.InstallReason int mInstallReason\nprivate @android.content.pm.PackageManager.UninstallReason int mUninstallReason\nprivate @android.annotation.Nullable java.lang.String mHarmfulAppWarning\nprivate @android.annotation.Nullable java.lang.String mLastDisableAppCaller\nprotected @android.annotation.Nullable android.content.pm.overlay.OverlayPaths mOverlayPaths\nprotected @android.annotation.Nullable android.util.ArrayMap<java.lang.String,android.content.pm.overlay.OverlayPaths> mSharedLibraryOverlayPaths\nprivate @android.annotation.Nullable java.lang.String mSplashScreenTheme\nprivate @android.annotation.Nullable android.util.ArrayMap<java.lang.String,com.android.server.pm.pkg.SuspendParams> mSuspendParams\nprivate @android.annotation.Nullable android.content.pm.overlay.OverlayPaths mCachedOverlayPaths\nprivate @android.annotation.Nullable android.util.ArrayMap<android.content.ComponentName,android.util.Pair<java.lang.String,java.lang.Integer>> mComponentLabelIconOverrideMap\npublic @android.annotation.Nullable boolean setOverlayPaths(android.content.pm.overlay.OverlayPaths)\npublic  boolean setSharedLibraryOverlayPaths(java.lang.String,android.content.pm.overlay.OverlayPaths)\npublic @android.annotation.Nullable @java.lang.Override android.util.ArraySet<java.lang.String> getDisabledComponentsNoCopy()\npublic @android.annotation.Nullable @java.lang.Override android.util.ArraySet<java.lang.String> getEnabledComponentsNoCopy()\npublic @java.lang.Override boolean isComponentEnabled(java.lang.String)\npublic @java.lang.Override boolean isComponentDisabled(java.lang.String)\npublic @java.lang.Override android.content.pm.overlay.OverlayPaths getAllOverlayPaths()\npublic @com.android.internal.annotations.VisibleForTesting boolean overrideLabelAndIcon(android.content.ComponentName,java.lang.String,java.lang.Integer)\npublic  void resetOverrideComponentLabelIcon()\npublic @android.annotation.Nullable android.util.Pair<java.lang.String,java.lang.Integer> getOverrideLabelIconForComponent(android.content.ComponentName)\nclass PackageUserStateImpl extends java.lang.Object implements [com.android.server.pm.pkg.PackageUserStateInternal]\n@com.android.internal.util.DataClass(genConstructor=false, genBuilder=false, genEqualsHashCode=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
