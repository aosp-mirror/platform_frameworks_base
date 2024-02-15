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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.UserPackage;
import android.content.pm.overlay.OverlayPaths;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedArraySet;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** @hide */
@DataClass(genConstructor = false, genBuilder = false, genEqualsHashCode = true)
@DataClass.Suppress({"mOverlayPathsLock", "mOverlayPaths", "mSharedLibraryOverlayPathsLock",
        "mSharedLibraryOverlayPaths", "setOverlayPaths", "setCachedOverlayPaths", "getWatchable",
        "getBooleans"
})
public class PackageUserStateImpl extends WatchableImpl implements PackageUserStateInternal,
        Snappable {
    // Use a bitset to store boolean data to save memory
    private static class Booleans {
        @IntDef({
                INSTALLED,
                STOPPED,
                NOT_LAUNCHED,
                HIDDEN,
                INSTANT_APP,
                VIRTUAL_PRELOADED,
        })
        public @interface Flags {
        }
        private static final int INSTALLED = 1;
        private static final int STOPPED = 1 << 1;
        private static final int NOT_LAUNCHED = 1 << 2;
        // Is the app restricted by owner / admin
        private static final int HIDDEN = 1 << 3;
        private static final int INSTANT_APP = 1 << 4;
        private static final int VIRTUAL_PRELOADED = 1 << 5;
    }
    private int mBooleans;

    private void setBoolean(@Booleans.Flags int flag, boolean value) {
        if (value) {
            mBooleans |= flag;
        } else {
            mBooleans &= ~flag;
        }
    }

    private boolean getBoolean(@Booleans.Flags int flag) {
        return (mBooleans & flag) != 0;
    }

    @Nullable
    protected WatchedArraySet<String> mDisabledComponentsWatched;
    @Nullable
    protected WatchedArraySet<String> mEnabledComponentsWatched;

    private long mCeDataInode;
    private long mDeDataInode;
    private int mDistractionFlags;
    @PackageManager.EnabledState
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
    private OverlayPaths mOverlayPaths;

    // Lib name to overlay paths
    @Nullable
    protected WatchedArrayMap<String, OverlayPaths> mSharedLibraryOverlayPaths;

    @Nullable
    private String mSplashScreenTheme;

    @PackageManager.UserMinAspectRatio
    private int mMinAspectRatio = PackageManager.USER_MIN_ASPECT_RATIO_UNSET;

    /**
     * Suspending package to suspend params
     */
    @Nullable
    private WatchedArrayMap<UserPackage, SuspendParams> mSuspendParams;

    @Nullable
    private WatchedArrayMap<ComponentName, Pair<String, Integer>> mComponentLabelIconOverrideMap;

    private @CurrentTimeMillisLong long mFirstInstallTimeMillis;

    // TODO(b/239050028): Remove, enforce notifying parent through PMS commit method
    @Nullable
    private Watchable mWatchable;

    @Nullable
    private ArchiveState mArchiveState;

    @NonNull
    final SnapshotCache<PackageUserStateImpl> mSnapshot;

    private SnapshotCache<PackageUserStateImpl> makeCache() {
        return new SnapshotCache<PackageUserStateImpl>(this, this) {
            @Override
            public PackageUserStateImpl createSnapshot() {
                return new PackageUserStateImpl(mWatchable, mSource);
            }};
    }

    /**
     * Only used for tests
     */
    public PackageUserStateImpl() {
        super();
        mWatchable = null;
        mSnapshot = makeCache();
        setBoolean(Booleans.INSTALLED, true);
    }

    public PackageUserStateImpl(@NonNull Watchable watchable) {
        mWatchable = watchable;
        mSnapshot = makeCache();
        setBoolean(Booleans.INSTALLED, true);
    }

    public PackageUserStateImpl(@NonNull Watchable watchable, PackageUserStateImpl other) {
        mWatchable = watchable;
        mBooleans = other.mBooleans;
        mDisabledComponentsWatched = other.mDisabledComponentsWatched == null
                ? null : other.mDisabledComponentsWatched.snapshot();
        mEnabledComponentsWatched =  other.mEnabledComponentsWatched == null
                ? null : other.mEnabledComponentsWatched.snapshot();
        mOverlayPaths = other.mOverlayPaths;
        mSharedLibraryOverlayPaths = other.mSharedLibraryOverlayPaths == null
                ? null : other.mSharedLibraryOverlayPaths.snapshot();
        mCeDataInode = other.mCeDataInode;
        mDeDataInode = other.mDeDataInode;
        mDistractionFlags = other.mDistractionFlags;
        mEnabledState = other.mEnabledState;
        mInstallReason = other.mInstallReason;
        mUninstallReason = other.mUninstallReason;
        mHarmfulAppWarning = other.mHarmfulAppWarning;
        mLastDisableAppCaller = other.mLastDisableAppCaller;
        mSplashScreenTheme = other.mSplashScreenTheme;
        mMinAspectRatio = other.mMinAspectRatio;
        mSuspendParams = other.mSuspendParams == null ? null : other.mSuspendParams.snapshot();
        mComponentLabelIconOverrideMap = other.mComponentLabelIconOverrideMap == null
                ? null : other.mComponentLabelIconOverrideMap.snapshot();
        mFirstInstallTimeMillis = other.mFirstInstallTimeMillis;
        mArchiveState = other.mArchiveState;
        mSnapshot = new SnapshotCache.Sealed<>();
    }

    private void onChanged() {
        if (mWatchable != null) {
            mWatchable.dispatchChange(mWatchable);
        }
        dispatchChange(this);
    }

    @NonNull
    @Override
    public PackageUserStateImpl snapshot() {
        return mSnapshot.snapshot();
    }

    /**
     * Sets the path of overlays currently enabled for this package and user combination.
     *
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
        onChanged();
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
            mSharedLibraryOverlayPaths = new WatchedArrayMap<>();
            mSharedLibraryOverlayPaths.registerObserver(mSnapshot);
        }
        final OverlayPaths currentPaths = mSharedLibraryOverlayPaths.get(library);
        if (Objects.equals(paths, currentPaths)) {
            return false;
        }
        if (paths == null || paths.isEmpty()) {
            boolean returnValue = mSharedLibraryOverlayPaths.remove(library) != null;
            onChanged();
            return returnValue;
        } else {
            mSharedLibraryOverlayPaths.put(library, paths);
            onChanged();
            return true;
        }
    }

    @Nullable
    @Override
    public WatchedArraySet<String> getDisabledComponentsNoCopy() {
        return mDisabledComponentsWatched;
    }

    @Nullable
    @Override
    public WatchedArraySet<String> getEnabledComponentsNoCopy() {
        return mEnabledComponentsWatched;
    }

    @NonNull
    @Override
    public ArraySet<String> getDisabledComponents() {
        return mDisabledComponentsWatched == null
                ? new ArraySet<>() : mDisabledComponentsWatched.untrackedStorage();
    }

    @NonNull
    @Override
    public ArraySet<String> getEnabledComponents() {
        return mEnabledComponentsWatched == null
                ? new ArraySet<>() : mEnabledComponentsWatched.untrackedStorage();
    }


    @Override
    public boolean isComponentEnabled(String componentName) {
        return mEnabledComponentsWatched != null
                && mEnabledComponentsWatched.contains(componentName);
    }

    @Override
    public boolean isComponentDisabled(String componentName) {
        return mDisabledComponentsWatched != null
                && mDisabledComponentsWatched.contains(componentName);
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
                    mComponentLabelIconOverrideMap = new WatchedArrayMap<>(1);
                    mComponentLabelIconOverrideMap.registerObserver(mSnapshot);
                }

                mComponentLabelIconOverrideMap.put(component, Pair.create(nonLocalizedLabel, icon));
            }
            onChanged();
        }

        return changed;
    }

    /**
     * Clears all values previously set by {@link #overrideLabelAndIcon(ComponentName, String,
     * Integer)}.
     * <p>
     * This is done when the package is updated as the components and resource IDs may have
     * changed.
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
    public boolean isSuspended() {
        return !CollectionUtils.isEmpty(mSuspendParams);
    }

    /**
     * Adds or updates suspension params by the given package.
     */
    public PackageUserStateImpl putSuspendParams(@NonNull UserPackage suspendingPackage,
            @Nullable SuspendParams suspendParams) {
        if (mSuspendParams == null) {
            mSuspendParams = new WatchedArrayMap<>();
            mSuspendParams.registerObserver(mSnapshot);
        }
        if (!mSuspendParams.containsKey(suspendingPackage)
                || !Objects.equals(mSuspendParams.get(suspendingPackage), suspendParams)) {
            mSuspendParams.put(suspendingPackage, suspendParams);
            onChanged();
        }

        return this;
    }

    /**
     * Removes suspension by the given package.
     */
    public PackageUserStateImpl removeSuspension(@NonNull UserPackage suspendingPackage) {
        if (mSuspendParams != null) {
            mSuspendParams.remove(suspendingPackage);
            onChanged();
        }
        return this;
    }

    public @NonNull PackageUserStateImpl setDisabledComponents(@Nullable ArraySet<String> value) {
        if (mDisabledComponentsWatched == null) {
            mDisabledComponentsWatched = new WatchedArraySet<>();
            mDisabledComponentsWatched.registerObserver(mSnapshot);
        }
        mDisabledComponentsWatched.clear();
        if (value != null) {
            mDisabledComponentsWatched.addAll(value);
        }
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setEnabledComponents(@Nullable ArraySet<String> value) {
        if (mEnabledComponentsWatched == null) {
            mEnabledComponentsWatched = new WatchedArraySet<>();
            mEnabledComponentsWatched.registerObserver(mSnapshot);
        }
        mEnabledComponentsWatched.clear();
        if (value != null) {
            mEnabledComponentsWatched.addAll(value);
        }
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setEnabledComponents(
            @Nullable WatchedArraySet<String> value) {
        mEnabledComponentsWatched = value;
        if (mEnabledComponentsWatched != null) {
            mEnabledComponentsWatched.registerObserver(mSnapshot);
        }
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setDisabledComponents(
            @Nullable WatchedArraySet<String> value) {
        mDisabledComponentsWatched = value;
        if (mDisabledComponentsWatched != null) {
            mDisabledComponentsWatched.registerObserver(mSnapshot);
        }
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setCeDataInode(long value) {
        mCeDataInode = value;
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setDeDataInode(long value) {
        mDeDataInode = value;
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setInstalled(boolean value) {
        setBoolean(Booleans.INSTALLED, value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setStopped(boolean value) {
        setBoolean(Booleans.STOPPED, value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setNotLaunched(boolean value) {
        setBoolean(Booleans.NOT_LAUNCHED, value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setHidden(boolean value) {
        setBoolean(Booleans.HIDDEN, value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setDistractionFlags(int value) {
        mDistractionFlags = value;
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setInstantApp(boolean value) {
        setBoolean(Booleans.INSTANT_APP, value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setVirtualPreload(boolean value) {
        setBoolean(Booleans.VIRTUAL_PRELOADED, value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setEnabledState(int value) {
        mEnabledState = value;
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setInstallReason(@PackageManager.InstallReason int value) {
        mInstallReason = value;
        com.android.internal.util.AnnotationValidations.validate(
                PackageManager.InstallReason.class, null, mInstallReason);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setUninstallReason(
            @PackageManager.UninstallReason int value) {
        mUninstallReason = value;
        com.android.internal.util.AnnotationValidations.validate(
                PackageManager.UninstallReason.class, null, mUninstallReason);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setHarmfulAppWarning(@NonNull String value) {
        mHarmfulAppWarning = value;
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setLastDisableAppCaller(@NonNull String value) {
        mLastDisableAppCaller = value;
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setSharedLibraryOverlayPaths(
            @NonNull ArrayMap<String, OverlayPaths> value) {
        if (value == null) {
            return this;
        }
        if (mSharedLibraryOverlayPaths == null) {
            mSharedLibraryOverlayPaths = new WatchedArrayMap<>();
            registerObserver(mSnapshot);
        }
        mSharedLibraryOverlayPaths.clear();
        mSharedLibraryOverlayPaths.putAll(value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setSplashScreenTheme(@NonNull String value) {
        mSplashScreenTheme = value;
        onChanged();
        return this;
    }

    /**
     * Sets user min aspect ratio override value
     * @see PackageManager.UserMinAspectRatio
     */
    public @NonNull PackageUserStateImpl setMinAspectRatio(
            @PackageManager.UserMinAspectRatio int value) {
        mMinAspectRatio = value;
        com.android.internal.util.AnnotationValidations.validate(
                PackageManager.UserMinAspectRatio.class, null, mMinAspectRatio);
        onChanged();
        return this;
    }

    /**
     * Suspending package to suspend params
     */
    public @NonNull PackageUserStateImpl setSuspendParams(
            @NonNull ArrayMap<UserPackage, SuspendParams> value) {
        if (value == null) {
            return this;
        }
        if (mSuspendParams == null) {
            mSuspendParams = new WatchedArrayMap<>();
            registerObserver(mSnapshot);
        }
        mSuspendParams.clear();
        mSuspendParams.putAll(value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setComponentLabelIconOverrideMap(
            @NonNull ArrayMap<ComponentName, Pair<String, Integer>> value) {
        if (value == null) {
            return this;
        }
        if (mComponentLabelIconOverrideMap == null) {
            mComponentLabelIconOverrideMap = new WatchedArrayMap<>();
            registerObserver(mSnapshot);
        }
        mComponentLabelIconOverrideMap.clear();
        mComponentLabelIconOverrideMap.putAll(value);
        onChanged();
        return this;
    }

    public @NonNull PackageUserStateImpl setFirstInstallTimeMillis(long value) {
        mFirstInstallTimeMillis = value;
        onChanged();
        return this;
    }

    /**
     * Sets the value for {@link #getArchiveState()}.
     */
    @NonNull
    public PackageUserStateImpl setArchiveState(@NonNull ArchiveState archiveState) {
        mArchiveState = archiveState;
        onChanged();
        return this;
    }

    @NonNull
    @Override
    public Map<String, OverlayPaths> getSharedLibraryOverlayPaths() {
        return mSharedLibraryOverlayPaths == null
                ? Collections.emptyMap() : mSharedLibraryOverlayPaths;
    }

    @NonNull
    public PackageUserStateImpl setWatchable(@NonNull Watchable watchable) {
        mWatchable = watchable;
        return this;
    }

    private boolean watchableEquals(Watchable other) {
        // Ignore the Watchable for equality
        return true;
    }

    private int watchableHashCode() {
        // Ignore the Watchable for equality
        return 0;
    }

    private boolean snapshotEquals(SnapshotCache<PackageUserStateImpl> other) {
        // Ignore the SnapshotCache for equality
        return true;
    }

    private int snapshotHashCode() {
        // Ignore the SnapshotCache for equality
        return 0;
    }


    @Override
    public boolean isInstalled() {
        return getBoolean(Booleans.INSTALLED);
    }

    @Override
    public boolean isStopped() {
        return getBoolean(Booleans.STOPPED);
    }

    @Override
    public boolean isNotLaunched() {
        return getBoolean(Booleans.NOT_LAUNCHED);
    }

    @Override
    public boolean isHidden() {
        return getBoolean(Booleans.HIDDEN);
    }

    @Override
    public boolean isInstantApp() {
        return getBoolean(Booleans.INSTANT_APP);
    }

    @Override
    public boolean isVirtualPreload() {
        return getBoolean(Booleans.VIRTUAL_PRELOADED);
    }

    @Override
    public boolean isQuarantined() {
        if (!isSuspended()) {
            return false;
        }
        final var suspendParams = mSuspendParams;
        for (int i = 0, size = suspendParams.size(); i < size; i++) {
            final SuspendParams params = suspendParams.valueAt(i);
            if (params.isQuarantined()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean dataExists() {
        return getCeDataInode() > 0 || getDeDataInode() > 0;
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
    public @Nullable WatchedArraySet<String> getDisabledComponentsWatched() {
        return mDisabledComponentsWatched;
    }

    @DataClass.Generated.Member
    public @Nullable WatchedArraySet<String> getEnabledComponentsWatched() {
        return mEnabledComponentsWatched;
    }

    @DataClass.Generated.Member
    public long getCeDataInode() {
        return mCeDataInode;
    }

    @DataClass.Generated.Member
    public long getDeDataInode() {
        return mDeDataInode;
    }

    @DataClass.Generated.Member
    public int getDistractionFlags() {
        return mDistractionFlags;
    }

    @DataClass.Generated.Member
    public @PackageManager.EnabledState int getEnabledState() {
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
    public @Nullable String getSplashScreenTheme() {
        return mSplashScreenTheme;
    }

    @DataClass.Generated.Member
    public @PackageManager.UserMinAspectRatio int getMinAspectRatio() {
        return mMinAspectRatio;
    }

    /**
     * Suspending package to suspend params
     */
    @DataClass.Generated.Member
    public @Nullable WatchedArrayMap<UserPackage,SuspendParams> getSuspendParams() {
        return mSuspendParams;
    }

    @DataClass.Generated.Member
    public @Nullable WatchedArrayMap<ComponentName,Pair<String,Integer>> getComponentLabelIconOverrideMap() {
        return mComponentLabelIconOverrideMap;
    }

    @DataClass.Generated.Member
    public @CurrentTimeMillisLong long getFirstInstallTimeMillis() {
        return mFirstInstallTimeMillis;
    }

    @DataClass.Generated.Member
    public @Nullable ArchiveState getArchiveState() {
        return mArchiveState;
    }

    @DataClass.Generated.Member
    public @NonNull SnapshotCache<PackageUserStateImpl> getSnapshot() {
        return mSnapshot;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setBooleans( int value) {
        mBooleans = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setDisabledComponentsWatched(@NonNull WatchedArraySet<String> value) {
        mDisabledComponentsWatched = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setEnabledComponentsWatched(@NonNull WatchedArraySet<String> value) {
        mEnabledComponentsWatched = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setSharedLibraryOverlayPaths(@NonNull WatchedArrayMap<String,OverlayPaths> value) {
        mSharedLibraryOverlayPaths = value;
        return this;
    }

    /**
     * Suspending package to suspend params
     */
    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setSuspendParams(@NonNull WatchedArrayMap<UserPackage,SuspendParams> value) {
        mSuspendParams = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull PackageUserStateImpl setComponentLabelIconOverrideMap(@NonNull WatchedArrayMap<ComponentName,Pair<String,Integer>> value) {
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
                && mBooleans == that.mBooleans
                && Objects.equals(mDisabledComponentsWatched, that.mDisabledComponentsWatched)
                && Objects.equals(mEnabledComponentsWatched, that.mEnabledComponentsWatched)
                && mCeDataInode == that.mCeDataInode
                && mDeDataInode == that.mDeDataInode
                && mDistractionFlags == that.mDistractionFlags
                && mEnabledState == that.mEnabledState
                && mInstallReason == that.mInstallReason
                && mUninstallReason == that.mUninstallReason
                && Objects.equals(mHarmfulAppWarning, that.mHarmfulAppWarning)
                && Objects.equals(mLastDisableAppCaller, that.mLastDisableAppCaller)
                && Objects.equals(mOverlayPaths, that.mOverlayPaths)
                && Objects.equals(mSharedLibraryOverlayPaths, that.mSharedLibraryOverlayPaths)
                && Objects.equals(mSplashScreenTheme, that.mSplashScreenTheme)
                && mMinAspectRatio == that.mMinAspectRatio
                && Objects.equals(mSuspendParams, that.mSuspendParams)
                && Objects.equals(mComponentLabelIconOverrideMap, that.mComponentLabelIconOverrideMap)
                && mFirstInstallTimeMillis == that.mFirstInstallTimeMillis
                && watchableEquals(that.mWatchable)
                && Objects.equals(mArchiveState, that.mArchiveState)
                && snapshotEquals(that.mSnapshot);
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mBooleans;
        _hash = 31 * _hash + Objects.hashCode(mDisabledComponentsWatched);
        _hash = 31 * _hash + Objects.hashCode(mEnabledComponentsWatched);
        _hash = 31 * _hash + Long.hashCode(mCeDataInode);
        _hash = 31 * _hash + Long.hashCode(mDeDataInode);
        _hash = 31 * _hash + mDistractionFlags;
        _hash = 31 * _hash + mEnabledState;
        _hash = 31 * _hash + mInstallReason;
        _hash = 31 * _hash + mUninstallReason;
        _hash = 31 * _hash + Objects.hashCode(mHarmfulAppWarning);
        _hash = 31 * _hash + Objects.hashCode(mLastDisableAppCaller);
        _hash = 31 * _hash + Objects.hashCode(mOverlayPaths);
        _hash = 31 * _hash + Objects.hashCode(mSharedLibraryOverlayPaths);
        _hash = 31 * _hash + Objects.hashCode(mSplashScreenTheme);
        _hash = 31 * _hash + mMinAspectRatio;
        _hash = 31 * _hash + Objects.hashCode(mSuspendParams);
        _hash = 31 * _hash + Objects.hashCode(mComponentLabelIconOverrideMap);
        _hash = 31 * _hash + Long.hashCode(mFirstInstallTimeMillis);
        _hash = 31 * _hash + watchableHashCode();
        _hash = 31 * _hash + Objects.hashCode(mArchiveState);
        _hash = 31 * _hash + snapshotHashCode();
        return _hash;
    }

    @DataClass.Generated(
            time = 1701864813354L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/pkg/PackageUserStateImpl.java",
            inputSignatures = "private  int mBooleans\nprotected @android.annotation.Nullable com.android.server.utils.WatchedArraySet<java.lang.String> mDisabledComponentsWatched\nprotected @android.annotation.Nullable com.android.server.utils.WatchedArraySet<java.lang.String> mEnabledComponentsWatched\nprivate  long mCeDataInode\nprivate  long mDeDataInode\nprivate  int mDistractionFlags\nprivate @android.content.pm.PackageManager.EnabledState int mEnabledState\nprivate @android.content.pm.PackageManager.InstallReason int mInstallReason\nprivate @android.content.pm.PackageManager.UninstallReason int mUninstallReason\nprivate @android.annotation.Nullable java.lang.String mHarmfulAppWarning\nprivate @android.annotation.Nullable java.lang.String mLastDisableAppCaller\nprivate @android.annotation.Nullable android.content.pm.overlay.OverlayPaths mOverlayPaths\nprotected @android.annotation.Nullable com.android.server.utils.WatchedArrayMap<java.lang.String,android.content.pm.overlay.OverlayPaths> mSharedLibraryOverlayPaths\nprivate @android.annotation.Nullable java.lang.String mSplashScreenTheme\nprivate @android.content.pm.PackageManager.UserMinAspectRatio int mMinAspectRatio\nprivate @android.annotation.Nullable com.android.server.utils.WatchedArrayMap<android.content.pm.UserPackage,com.android.server.pm.pkg.SuspendParams> mSuspendParams\nprivate @android.annotation.Nullable com.android.server.utils.WatchedArrayMap<android.content.ComponentName,android.util.Pair<java.lang.String,java.lang.Integer>> mComponentLabelIconOverrideMap\nprivate @android.annotation.CurrentTimeMillisLong long mFirstInstallTimeMillis\nprivate @android.annotation.Nullable com.android.server.utils.Watchable mWatchable\nprivate @android.annotation.Nullable com.android.server.pm.pkg.ArchiveState mArchiveState\nfinal @android.annotation.NonNull com.android.server.utils.SnapshotCache<com.android.server.pm.pkg.PackageUserStateImpl> mSnapshot\nprivate  void setBoolean(int,boolean)\nprivate  boolean getBoolean(int)\nprivate  com.android.server.utils.SnapshotCache<com.android.server.pm.pkg.PackageUserStateImpl> makeCache()\nprivate  void onChanged()\npublic @android.annotation.NonNull @java.lang.Override com.android.server.pm.pkg.PackageUserStateImpl snapshot()\npublic @android.annotation.Nullable boolean setOverlayPaths(android.content.pm.overlay.OverlayPaths)\npublic  boolean setSharedLibraryOverlayPaths(java.lang.String,android.content.pm.overlay.OverlayPaths)\npublic @android.annotation.Nullable @java.lang.Override com.android.server.utils.WatchedArraySet<java.lang.String> getDisabledComponentsNoCopy()\npublic @android.annotation.Nullable @java.lang.Override com.android.server.utils.WatchedArraySet<java.lang.String> getEnabledComponentsNoCopy()\npublic @android.annotation.NonNull @java.lang.Override android.util.ArraySet<java.lang.String> getDisabledComponents()\npublic @android.annotation.NonNull @java.lang.Override android.util.ArraySet<java.lang.String> getEnabledComponents()\npublic @java.lang.Override boolean isComponentEnabled(java.lang.String)\npublic @java.lang.Override boolean isComponentDisabled(java.lang.String)\npublic @java.lang.Override android.content.pm.overlay.OverlayPaths getAllOverlayPaths()\npublic @com.android.internal.annotations.VisibleForTesting boolean overrideLabelAndIcon(android.content.ComponentName,java.lang.String,java.lang.Integer)\npublic  void resetOverrideComponentLabelIcon()\npublic @android.annotation.Nullable android.util.Pair<java.lang.String,java.lang.Integer> getOverrideLabelIconForComponent(android.content.ComponentName)\npublic @java.lang.Override boolean isSuspended()\npublic  com.android.server.pm.pkg.PackageUserStateImpl putSuspendParams(android.content.pm.UserPackage,com.android.server.pm.pkg.SuspendParams)\npublic  com.android.server.pm.pkg.PackageUserStateImpl removeSuspension(android.content.pm.UserPackage)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setDisabledComponents(android.util.ArraySet<java.lang.String>)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setEnabledComponents(android.util.ArraySet<java.lang.String>)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setEnabledComponents(com.android.server.utils.WatchedArraySet<java.lang.String>)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setDisabledComponents(com.android.server.utils.WatchedArraySet<java.lang.String>)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setCeDataInode(long)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setDeDataInode(long)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setInstalled(boolean)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setStopped(boolean)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setNotLaunched(boolean)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setHidden(boolean)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setDistractionFlags(int)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setInstantApp(boolean)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setVirtualPreload(boolean)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setEnabledState(int)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setInstallReason(int)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setUninstallReason(int)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setHarmfulAppWarning(java.lang.String)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setLastDisableAppCaller(java.lang.String)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setSharedLibraryOverlayPaths(android.util.ArrayMap<java.lang.String,android.content.pm.overlay.OverlayPaths>)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setSplashScreenTheme(java.lang.String)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setMinAspectRatio(int)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setSuspendParams(android.util.ArrayMap<android.content.pm.UserPackage,com.android.server.pm.pkg.SuspendParams>)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setComponentLabelIconOverrideMap(android.util.ArrayMap<android.content.ComponentName,android.util.Pair<java.lang.String,java.lang.Integer>>)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setFirstInstallTimeMillis(long)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setArchiveState(com.android.server.pm.pkg.ArchiveState)\npublic @android.annotation.NonNull @java.lang.Override java.util.Map<java.lang.String,android.content.pm.overlay.OverlayPaths> getSharedLibraryOverlayPaths()\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageUserStateImpl setWatchable(com.android.server.utils.Watchable)\nprivate  boolean watchableEquals(com.android.server.utils.Watchable)\nprivate  int watchableHashCode()\nprivate  boolean snapshotEquals(com.android.server.utils.SnapshotCache<com.android.server.pm.pkg.PackageUserStateImpl>)\nprivate  int snapshotHashCode()\npublic @java.lang.Override boolean isInstalled()\npublic @java.lang.Override boolean isStopped()\npublic @java.lang.Override boolean isNotLaunched()\npublic @java.lang.Override boolean isHidden()\npublic @java.lang.Override boolean isInstantApp()\npublic @java.lang.Override boolean isVirtualPreload()\npublic @java.lang.Override boolean isQuarantined()\npublic @java.lang.Override boolean dataExists()\nclass PackageUserStateImpl extends com.android.server.utils.WatchableImpl implements [com.android.server.pm.pkg.PackageUserStateInternal, com.android.server.utils.Snappable]\nprivate static final  int INSTALLED\nprivate static final  int STOPPED\nprivate static final  int NOT_LAUNCHED\nprivate static final  int HIDDEN\nprivate static final  int INSTANT_APP\nprivate static final  int VIRTUAL_PRELOADED\nclass Booleans extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false, genBuilder=false, genEqualsHashCode=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
