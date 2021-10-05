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
import android.content.pm.overlay.OverlayPaths;
import android.content.pm.pkg.PackageUserStateImpl;
import android.content.pm.pkg.PackageUserStateInternal;
import android.content.pm.pkg.SuspendParams;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DataClass;

import java.util.Objects;

@DataClass(genConstructor = false, genBuilder = false, genEqualsHashCode = true)
@DataClass.Suppress({"mCachedOverlayPathsLock", "mCachedOverlayPaths", "setCachedOverlayPaths"})
public class PackageUserStateInternalImpl extends PackageUserStateImpl implements
        PackageUserStateInternal {

    /** Suspending package to suspend params */
    @Nullable
    private ArrayMap<String, SuspendParams> mSuspendParams;

    @Nullable
    private OverlayPaths mCachedOverlayPaths;

    @Nullable
    private ArrayMap<ComponentName, Pair<String, Integer>> mComponentLabelIconOverrideMap;

    public PackageUserStateInternalImpl() {
        super();
    }

    public PackageUserStateInternalImpl(PackageUserStateInternalImpl other) {
        super(other);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PackageUserStateInternalImpl)) return false;
        if (!super.equals(o)) return false;
        PackageUserStateInternalImpl that = (PackageUserStateInternalImpl) o;
        return Objects.equals(mSuspendParams, that.mSuspendParams)
                && Objects.equals(mCachedOverlayPaths, that.mCachedOverlayPaths)
                && Objects.equals(mComponentLabelIconOverrideMap,
                that.mComponentLabelIconOverrideMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mSuspendParams, mCachedOverlayPaths,
                mComponentLabelIconOverrideMap);
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/services/core/java/com/android/server/pm/pkg/PackageUserStateInternalImpl.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Suspending package to suspend params
     */
    @DataClass.Generated.Member
    public @Nullable ArrayMap<String, SuspendParams> getSuspendParams() {
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

    /**
     * Suspending package to suspend params
     */
    @DataClass.Generated.Member
    public @NonNull PackageUserStateInternalImpl setSuspendParams(@NonNull ArrayMap<String, SuspendParams> value) {
        mSuspendParams = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateInternalImpl setComponentLabelIconOverrideMap(@NonNull ArrayMap<ComponentName,Pair<String,Integer>> value) {
        mComponentLabelIconOverrideMap = value;
        return this;
    }

    @DataClass.Generated(
            time = 1626458385872L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/pkg/PackageUserStateInternalImpl.java",
            inputSignatures = "private @android.annotation.Nullable android.util.ArrayMap<java.lang.String,android.content.pm.PackageUserState.SuspendParams> mSuspendParams\nprivate @android.annotation.Nullable android.content.pm.overlay.OverlayPaths mCachedOverlayPaths\nprivate @android.annotation.Nullable android.util.ArrayMap<android.content.ComponentName,android.util.Pair<java.lang.String,java.lang.Integer>> mComponentLabelIconOverrideMap\npublic @java.lang.Override boolean isComponentEnabled(java.lang.String)\npublic @java.lang.Override boolean isComponentDisabled(java.lang.String)\npublic  android.content.pm.overlay.OverlayPaths getAllOverlayPaths()\npublic  boolean setSharedLibraryOverlayPaths(java.lang.String,android.content.pm.overlay.OverlayPaths)\npublic @android.annotation.Nullable @java.lang.Override android.util.ArraySet<java.lang.String> getDisabledComponentsNoCopy()\npublic @android.annotation.Nullable @java.lang.Override android.util.ArraySet<java.lang.String> getEnabledComponentsNoCopy()\npublic @com.android.internal.annotations.VisibleForTesting boolean overrideLabelAndIcon(android.content.ComponentName,java.lang.String,java.lang.Integer)\npublic  void resetOverrideComponentLabelIcon()\npublic @android.annotation.Nullable android.util.Pair<java.lang.String,java.lang.Integer> getOverrideLabelIconForComponent(android.content.ComponentName)\nclass PackageUserStateInternalImpl extends android.content.pm.pkg.PackageUserStateImpl implements [android.content.pm.pkg.PackageUserStateInternal, android.content.pm.pkg.PackageUserStateHidden]\n@com.android.internal.util.DataClass(genConstructor=false, genBuilder=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
