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

package com.android.server.pm.pkg.mutate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.overlay.OverlayPaths;
import android.util.ArraySet;

import com.android.server.pm.PackageSetting;
import com.android.server.pm.pkg.PackageUserStateImpl;
import com.android.server.pm.pkg.SuspendParams;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public class PackageStateMutator {

    private static final AtomicLong sStateChangeSequence = new AtomicLong();

    private final StateWriteWrapper mStateWrite = new StateWriteWrapper();

    private final Function<String, PackageSetting> mActiveStateFunction;
    private final Function<String, PackageSetting> mDisabledStateFunction;

    public PackageStateMutator(@NonNull Function<String, PackageSetting> activeStateFunction,
            @NonNull Function<String, PackageSetting> disabledStateFunction) {
        mActiveStateFunction = activeStateFunction;
        mDisabledStateFunction = disabledStateFunction;
    }

    public static void onPackageStateChanged() {
        sStateChangeSequence.incrementAndGet();
    }

    @NonNull
    public PackageStateWrite forPackage(@NonNull String packageName) {
        return mStateWrite.setState(mActiveStateFunction.apply(packageName));
    }

    @Nullable
    public PackageStateWrite forPackageNullable(@NonNull String packageName) {
        final PackageSetting packageState = mActiveStateFunction.apply(packageName);
        mStateWrite.setState(packageState);
        if (packageState == null) {
            return null;
        }

        return mStateWrite.setState(packageState);
    }

    @NonNull
    public PackageStateWrite forDisabledSystemPackage(@NonNull String packageName) {
        return mStateWrite.setState(mDisabledStateFunction.apply(packageName));
    }

    @Nullable
    public PackageStateWrite forDisabledSystemPackageNullable(@NonNull String packageName) {
        final PackageSetting packageState = mDisabledStateFunction.apply(packageName);
        if (packageState == null) {
            return null;
        }

        return mStateWrite.setState(packageState);
    }

    @NonNull
    public InitialState initialState(int changedPackagesSequenceNumber) {
        return new InitialState(changedPackagesSequenceNumber, sStateChangeSequence.get());
    }

    /**
     * @return null if initial state is null or if nothing has changed, otherwise return result
     * with what changed
     */
    @Nullable
    public Result generateResult(@Nullable InitialState state, int changedPackagesSequenceNumber) {
        if (state == null) {
            return Result.SUCCESS;
        }

        boolean packagesChanged = changedPackagesSequenceNumber != state.mPackageSequence;
        boolean stateChanged = sStateChangeSequence.get() != state.mStateSequence;
        if (packagesChanged && stateChanged) {
            return Result.PACKAGES_AND_STATE_CHANGED;
        } else if (packagesChanged) {
            return Result.PACKAGES_CHANGED;
        } else if (stateChanged) {
            return Result.STATE_CHANGED;
        } else {
            return Result.SUCCESS;
        }
    }

    public static class InitialState {

        private final int mPackageSequence;
        private final long mStateSequence;

        public InitialState(int packageSequence, long stateSequence) {
            mPackageSequence = packageSequence;
            mStateSequence = stateSequence;
        }
    }

    public static class Result {

        public static final Result SUCCESS = new Result(true, false, false, false);
        public static final Result PACKAGES_CHANGED = new Result(false, true, false, false);
        public static final Result STATE_CHANGED = new Result(false, false, true, false);
        public static final Result PACKAGES_AND_STATE_CHANGED = new Result(false, true, true, false);
        public static final Result SPECIFIC_PACKAGE_NULL = new Result(false, false, true, true);

        private final boolean mCommitted;
        private final boolean mPackagesChanged;
        private final boolean mStateChanged;
        private final boolean mSpecificPackageNull;

        public Result(boolean committed, boolean packagesChanged, boolean stateChanged,
                boolean specificPackageNull) {
            mCommitted = committed;
            mPackagesChanged = packagesChanged;
            mStateChanged = stateChanged;
            mSpecificPackageNull = specificPackageNull;
        }

        public boolean isCommitted() {
            return mCommitted;
        }

        public boolean isPackagesChanged() {
            return mPackagesChanged;
        }

        public boolean isStateChanged() {
            return mStateChanged;
        }

        public boolean isSpecificPackageNull() {
            return mSpecificPackageNull;
        }
    }

    private static class StateWriteWrapper implements PackageStateWrite {

        private final UserStateWriteWrapper mUserStateWrite = new UserStateWriteWrapper();

        @NonNull
        private PackageSetting mState;

        public StateWriteWrapper setState(PackageSetting state) {
            this.mState = state;
            return this;
        }

        @NonNull
        @Override
        public PackageUserStateWrite userState(int userId) {
            return mUserStateWrite.setStates(
                    mState == null ? null : mState.getOrCreateUserState(userId));
        }

        @Override
        public void onChanged() {
            if (mState != null) {
                mState.onChanged();
            }
        }

        @Override
        public PackageStateWrite setLastPackageUsageTime(int reason, long timeInMillis) {
            if (mState != null) {
                mState.getTransientState().setLastPackageUsageTimeInMills(reason, timeInMillis);
            }
            return this;
        }

        @Override
        public PackageStateWrite setHiddenUntilInstalled(boolean value) {
            if (mState != null) {
                mState.getTransientState().setHiddenUntilInstalled(value);
            }
            return this;
        }

        @NonNull
        @Override
        public PackageStateWrite setRequiredForSystemUser(boolean requiredForSystemUser) {
            if (mState != null) {
                if (requiredForSystemUser) {
                    mState.setPrivateFlags(mState.getPrivateFlags()
                            | ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER);
                } else {
                    mState.setPrivateFlags(mState.getPrivateFlags()
                            & ~ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER);
                }
            }
            return this;
        }

        @NonNull
        @Override
        public PackageStateWrite setMimeGroup(@NonNull String mimeGroup,
                @NonNull ArraySet<String> mimeTypes) {
            if (mState != null) {
                mState.setMimeGroup(mimeGroup, mimeTypes);
            }
            return this;
        }

        @NonNull
        @Override
        public PackageStateWrite setCategoryOverride(@ApplicationInfo.Category int category) {
            if (mState != null) {
                mState.setCategoryOverride(category);
            }
            return this;
        }

        @NonNull
        @Override
        public PackageStateWrite setUpdateAvailable(boolean updateAvailable) {
            if (mState != null) {
                mState.setUpdateAvailable(updateAvailable);
            }
            return this;
        }

        @NonNull
        @Override
        public PackageStateWrite setLoadingProgress(float progress) {
            if (mState != null) {
                mState.setLoadingProgress(progress);
            }
            return this;
        }

        @NonNull
        @Override
        public PackageStateWrite setOverrideSeInfo(@Nullable String newSeInfo) {
            if (mState != null) {
                mState.getTransientState().setOverrideSeInfo(newSeInfo);
            }
            return this;
        }

        @NonNull
        @Override
        public PackageStateWrite setInstaller(@NonNull String installerPackageName) {
            if (mState != null) {
                mState.setInstallerPackageName(installerPackageName);
            }
            return this;
        }

        private static class UserStateWriteWrapper implements PackageUserStateWrite {

            @Nullable
            private PackageUserStateImpl mUserState;

            public UserStateWriteWrapper setStates(@Nullable PackageUserStateImpl userState) {
                mUserState = userState;
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setInstalled(boolean installed) {
                if (mUserState != null) {
                    mUserState.setInstalled(installed);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setUninstallReason(int reason) {
                if (mUserState != null) {
                    mUserState.setUninstallReason(reason);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setDistractionFlags(
                    @PackageManager.DistractionRestriction int restrictionFlags) {
                if (mUserState != null) {
                    mUserState.setDistractionFlags(restrictionFlags);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite putSuspendParams(@NonNull String suspendingPackage,
                    @Nullable SuspendParams suspendParams) {
                if (mUserState != null) {
                    mUserState.putSuspendParams(suspendingPackage, suspendParams);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite removeSuspension(@NonNull String suspendingPackage) {
                if (mUserState != null) {
                    mUserState.removeSuspension(suspendingPackage);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setHidden(boolean hidden) {
                if (mUserState != null) {
                    mUserState.setHidden(hidden);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setStopped(boolean stopped) {
                if (mUserState != null) {
                    mUserState.setStopped(stopped);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setNotLaunched(boolean notLaunched) {
                if (mUserState != null) {
                    mUserState.setNotLaunched(notLaunched);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setOverlayPaths(@NonNull OverlayPaths overlayPaths) {
                if (mUserState != null) {
                    mUserState.setOverlayPaths(overlayPaths);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setOverlayPathsForLibrary(@NonNull String libraryName,
                    @Nullable OverlayPaths overlayPaths) {
                if (mUserState != null) {
                    mUserState.setSharedLibraryOverlayPaths(libraryName, overlayPaths);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setHarmfulAppWarning(@Nullable String warning) {
                if (mUserState != null) {
                    mUserState.setHarmfulAppWarning(warning);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setSplashScreenTheme(@Nullable String theme) {
                if (mUserState != null) {
                    mUserState.setSplashScreenTheme(theme);
                }
                return this;
            }

            @NonNull
            @Override
            public PackageUserStateWrite setComponentLabelIcon(@NonNull ComponentName componentName,
                    @Nullable String nonLocalizedLabel, @Nullable Integer icon) {
                if (mUserState != null) {
                    mUserState.overrideLabelAndIcon(componentName, nonLocalizedLabel, icon);
                }
                return null;
            }
        }
    }
}
