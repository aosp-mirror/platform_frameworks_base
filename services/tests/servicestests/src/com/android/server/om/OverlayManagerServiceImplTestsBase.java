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

package com.android.server.om;

import static com.android.server.om.OverlayManagerServiceImpl.OperationFailedException;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayInfo.State;
import android.content.om.OverlayableInfo;
import android.os.FabricatedOverlayInfo;
import android.os.FabricatedOverlayInternal;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.android.internal.content.om.OverlayConfig;
import com.android.internal.util.CollectionUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import org.junit.Assert;
import org.junit.Before;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Base class for creating {@link OverlayManagerServiceImplTests} tests. */
class OverlayManagerServiceImplTestsBase {
    private OverlayManagerServiceImpl mImpl;
    private FakeDeviceState mState;
    private FakePackageManagerHelper mPackageManager;
    private FakeIdmapDaemon mIdmapDaemon;
    private OverlayConfig mOverlayConfig;
    private String mConfigSignaturePackageName;

    @Before
    public void setUp() {
        mState = new FakeDeviceState();
        mPackageManager = new FakePackageManagerHelper(mState);
        mIdmapDaemon = new FakeIdmapDaemon(mState);
        mOverlayConfig = mock(OverlayConfig.class);
        when(mOverlayConfig.getPriority(any())).thenReturn(OverlayConfig.DEFAULT_PRIORITY);
        when(mOverlayConfig.isEnabled(any())).thenReturn(false);
        when(mOverlayConfig.isMutable(any())).thenReturn(true);
        reinitializeImpl();
    }

    void reinitializeImpl() {
        mImpl = new OverlayManagerServiceImpl(mPackageManager,
                new IdmapManager(mIdmapDaemon, mPackageManager),
                new OverlayManagerSettings(),
                mOverlayConfig,
                new String[0]);
    }

    OverlayManagerServiceImpl getImpl() {
        return mImpl;
    }

    FakeIdmapDaemon getIdmapd() {
        return mIdmapDaemon;
    }

    FakeDeviceState getState() {
        return mState;
    }

    void setConfigSignaturePackageName(String packageName) {
        mConfigSignaturePackageName = packageName;
    }

    void assertState(@State int expected, final OverlayIdentifier overlay, int userId) {
        final OverlayInfo info = mImpl.getOverlayInfo(overlay, userId);
        if (info == null) {
            throw new IllegalStateException("overlay '" + overlay + "' not installed");
        }
        final String msg = String.format("expected %s but was %s:",
                OverlayInfo.stateToString(expected), OverlayInfo.stateToString(info.state));
        assertEquals(msg, expected, info.state);
    }

    void assertOverlayInfoForTarget(final String targetPackageName, int userId,
            OverlayInfo... overlayInfos) {
        final List<OverlayInfo> expected =
                mImpl.getOverlayInfosForTarget(targetPackageName, userId);
        final List<OverlayInfo> actual = Arrays.asList(overlayInfos);
        assertEquals(expected, actual);
    }

    FakeDeviceState.PackageBuilder app(String packageName) {
        return new FakeDeviceState.PackageBuilder(packageName, null /* targetPackageName */,
                null /* targetOverlayableName */, "data");
    }

    FakeDeviceState.PackageBuilder target(String packageName) {
        return new FakeDeviceState.PackageBuilder(packageName, null /* targetPackageName */,
                null /* targetOverlayableName */, "");
    }

    FakeDeviceState.PackageBuilder overlay(String packageName, String targetPackageName) {
        return overlay(packageName, targetPackageName, null /* targetOverlayableName */);
    }

    FakeDeviceState.PackageBuilder overlay(String packageName, String targetPackageName,
            String targetOverlayableName) {
        return new FakeDeviceState.PackageBuilder(packageName, targetPackageName,
                targetOverlayableName, "");
    }

    void addPackage(FakeDeviceState.PackageBuilder pkg, int userId) {
        mState.add(pkg, userId);
    }

    enum ConfigState {
        IMMUTABLE_DISABLED,
        IMMUTABLE_ENABLED,
        MUTABLE_DISABLED,
        MUTABLE_ENABLED
    }

    void configureSystemOverlay(@NonNull String packageName, @NonNull ConfigState state,
            int priority) {
        final boolean mutable = state == ConfigState.MUTABLE_DISABLED
                || state == ConfigState.MUTABLE_ENABLED;
        final boolean enabled = state == ConfigState.IMMUTABLE_ENABLED
                || state == ConfigState.MUTABLE_ENABLED;
        when(mOverlayConfig.getPriority(packageName)).thenReturn(priority);
        when(mOverlayConfig.isEnabled(packageName)).thenReturn(enabled);
        when(mOverlayConfig.isMutable(packageName)).thenReturn(mutable);
    }

    /**
     * Adds the package to the device.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_ADDED} broadcast.
     *
     * @throws IllegalStateException if the package is currently installed
     */
    void installAndAssert(@NonNull FakeDeviceState.PackageBuilder pkg, int userId,
            @NonNull Set<PackageAndUser> onAddedUpdatedPackages)
            throws OperationFailedException {
        if (mState.select(pkg.packageName, userId) != null) {
            throw new IllegalStateException("package " + pkg.packageName + " already installed");
        }
        mState.add(pkg, userId);
        assertEquals(onAddedUpdatedPackages, mImpl.onPackageAdded(pkg.packageName, userId));
    }

    /**
     * Begins upgrading the package.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_REMOVED} broadcast with the
     * {@link android.content.Intent#EXTRA_REPLACING} extra and then receives the
     * {@link android.content.Intent#ACTION_PACKAGE_ADDED} broadcast with the
     * {@link android.content.Intent#EXTRA_REPLACING} extra.
     *
     * @throws IllegalStateException if the package is not currently installed
     */
    void upgradeAndAssert(FakeDeviceState.PackageBuilder pkg, int userId,
            @NonNull Set<PackageAndUser> onReplacingUpdatedPackages,
            @NonNull Set<PackageAndUser> onReplacedUpdatedPackages)
            throws OperationFailedException {
        final FakeDeviceState.Package replacedPackage = mState.select(pkg.packageName, userId);
        if (replacedPackage == null) {
            throw new IllegalStateException("package " + pkg.packageName + " not installed");
        }

        assertEquals(onReplacingUpdatedPackages, mImpl.onPackageReplacing(pkg.packageName, userId));
        mState.add(pkg, userId);
        assertEquals(onReplacedUpdatedPackages, mImpl.onPackageReplaced(pkg.packageName, userId));
    }

    /**
     * Removes the package from the device.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_REMOVED} broadcast.
     *
     * @throws IllegalStateException if the package is not currently installed
     */
    void uninstallAndAssert(@NonNull String packageName, int userId,
            @NonNull Set<PackageAndUser> onRemovedUpdatedPackages) {
        final FakeDeviceState.Package pkg = mState.select(packageName, userId);
        if (pkg == null) {
            throw new IllegalStateException("package " + packageName + " not installed");
        }
        mState.remove(pkg.packageName);
        assertEquals(onRemovedUpdatedPackages, mImpl.onPackageRemoved(pkg.packageName, userId));
    }

    /** Represents the state of packages installed on a fake device. */
    static class FakeDeviceState {
        private ArrayMap<String, Package> mPackages = new ArrayMap<>();

        void add(PackageBuilder pkgBuilder, int userId) {
            final Package pkg = pkgBuilder.build();
            final Package previousPkg = select(pkg.packageName, userId);
            mPackages.put(pkg.packageName, pkg);

            pkg.installedUserIds.add(userId);
            if (previousPkg != null) {
                pkg.installedUserIds.addAll(previousPkg.installedUserIds);
            }
        }

        void remove(String packageName) {
            mPackages.remove(packageName);
        }

        void uninstall(String packageName, int userId) {
            final Package pkg = mPackages.get(packageName);
            if (pkg != null) {
                pkg.installedUserIds.remove(userId);
            }
        }

        Package select(String packageName, int userId) {
            final Package pkg = mPackages.get(packageName);
            return pkg != null && pkg.installedUserIds.contains(userId) ? pkg : null;
        }

        private Package selectFromPath(String path) {
            return mPackages.values().stream()
                    .filter(p -> p.apkPath.equals(path)).findFirst().orElse(null);
        }

        static final class PackageBuilder {
            private String packageName;
            private String targetPackage;
            private String certificate = "[default]";
            private String partition;
            private int version = 0;
            private ArrayList<String> overlayableNames = new ArrayList<>();
            private String targetOverlayableName;

            private PackageBuilder(String packageName, String targetPackage,
                    String targetOverlayableName, String partition) {
                this.packageName = packageName;
                this.targetPackage = targetPackage;
                this.targetOverlayableName = targetOverlayableName;
                this.partition = partition;
            }

            PackageBuilder setCertificate(String certificate) {
                this.certificate = certificate;
                return this;
            }

            PackageBuilder addOverlayable(String overlayableName) {
                overlayableNames.add(overlayableName);
                return this;
            }

            PackageBuilder setVersion(int version) {
                this.version = version;
                return this;
            }

            Package build() {
                String path = "";
                if (TextUtils.isEmpty(partition)) {
                    if (targetPackage == null) {
                        path = "/system/app";
                    } else {
                        path = "/vendor/overlay";
                    }
                } else {
                    String type = targetPackage == null ? "app" : "overlay";
                    path = String.format("%s/%s", partition, type);
                }

                final String apkPath = String.format("%s/%s/base.apk", path, packageName);
                final Package newPackage = new Package(packageName, targetPackage,
                        targetOverlayableName, version, apkPath, certificate);
                newPackage.overlayableNames.addAll(overlayableNames);
                return newPackage;
            }
        }

        static final class Package {
            final String packageName;
            final String targetPackageName;
            final String targetOverlayableName;
            final int versionCode;
            final String apkPath;
            final String certificate;
            final ArrayList<String> overlayableNames = new ArrayList<>();
            private final ArraySet<Integer> installedUserIds = new ArraySet<>();

            private Package(String packageName, String targetPackageName,
                    String targetOverlayableName, int versionCode, String apkPath,
                    String certificate) {
                this.packageName = packageName;
                this.targetPackageName = targetPackageName;
                this.targetOverlayableName = targetOverlayableName;
                this.versionCode = versionCode;
                this.apkPath = apkPath;
                this.certificate = certificate;
            }

            @Nullable
            private AndroidPackage getPackageForUser(int user) {
                if (!installedUserIds.contains(user)) {
                    return null;
                }
                final AndroidPackage pkg = Mockito.mock(AndroidPackage.class);
                when(pkg.getPackageName()).thenReturn(packageName);
                when(pkg.getBaseApkPath()).thenReturn(apkPath);
                when(pkg.getLongVersionCode()).thenReturn((long) versionCode);
                when(pkg.getOverlayTarget()).thenReturn(targetPackageName);
                when(pkg.getOverlayTargetName()).thenReturn(targetOverlayableName);
                when(pkg.getOverlayCategory()).thenReturn("Fake-category-" + targetPackageName);
                return pkg;
            }
        }
    }

    final class FakePackageManagerHelper implements PackageManagerHelper {
        private final FakeDeviceState mState;

        private FakePackageManagerHelper(FakeDeviceState state) {
            mState = state;
        }

        @NonNull
        @Override
        public ArrayMap<String, AndroidPackage> initializeForUser(int userId) {
            final ArrayMap<String, AndroidPackage> packages = new ArrayMap<>();
            mState.mPackages.forEach((key, value) -> {
                final AndroidPackage pkg = value.getPackageForUser(userId);
                if (pkg != null) {
                    packages.put(key, pkg);
                }
            });
            return packages;
        }

        @Nullable
        @Override
        public AndroidPackage getPackageForUser(@NonNull String packageName, int userId) {
            final FakeDeviceState.Package pkgState = mState.select(packageName, userId);
            return pkgState == null ? null : pkgState.getPackageForUser(userId);
        }

        @Override
        public boolean isInstantApp(@NonNull String packageName, int userId) {
            return false;
        }

        @Override
        public boolean signaturesMatching(@NonNull String packageName1,
                @NonNull String packageName2, int userId) {
            final FakeDeviceState.Package pkg1 = mState.select(packageName1, userId);
            final FakeDeviceState.Package pkg2 = mState.select(packageName2, userId);
            return pkg1 != null && pkg2 != null && pkg1.certificate.equals(pkg2.certificate);
        }

        @Override
        public @NonNull String getConfigSignaturePackage() {
            return mConfigSignaturePackageName;
        }

        @Nullable
        @Override
        public OverlayableInfo getOverlayableForTarget(@NonNull String packageName,
                @NonNull String targetOverlayableName, int userId) {
            final FakeDeviceState.Package pkg = mState.select(packageName, userId);
            if (pkg == null || !pkg.overlayableNames.contains(targetOverlayableName)) {
                return null;
            }
            return new OverlayableInfo(targetOverlayableName, null /* actor */);
        }

        @Nullable
        @Override
        public String[] getPackagesForUid(int uid) {
            throw new UnsupportedOperationException();
        }

        @NonNull
        @Override
        public Map<String, Map<String, String>> getNamedActors() {
            return Collections.emptyMap();
        }

        @Override
        public boolean doesTargetDefineOverlayable(String targetPackageName, int userId) {
            final FakeDeviceState.Package pkg = mState.select(targetPackageName, userId);
            return pkg != null && pkg.overlayableNames.contains(targetPackageName);
        }

        @Override
        public void enforcePermission(String permission, String message) throws SecurityException {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeIdmapDaemon extends IdmapDaemon {
        private final FakeDeviceState mState;
        private final ArrayMap<String, IdmapHeader> mIdmapFiles = new ArrayMap<>();
        private final ArrayMap<String, FabricatedOverlayInfo> mFabricatedOverlays =
                new ArrayMap<>();
        private int mFabricatedAssetSeq = 0;

        FakeIdmapDaemon(FakeDeviceState state) {
            this.mState = state;
        }

        private int getCrc(@NonNull final String path) {
            final FakeDeviceState.Package pkg = mState.selectFromPath(path);
            Assert.assertNotNull(pkg);
            return pkg.versionCode;
        }

        @Override
        String createIdmap(String targetPath, String overlayPath, String overlayName,
                int policies, boolean enforce, int userId) {
            mIdmapFiles.put(overlayPath, new IdmapHeader(getCrc(targetPath),
                    getCrc(overlayPath), targetPath, overlayName, policies, enforce));
            return overlayPath;
        }

        @Override
        boolean removeIdmap(String overlayPath, int userId) {
            return mIdmapFiles.remove(overlayPath) != null;
        }

        @Override
        boolean verifyIdmap(String targetPath, String overlayPath, String overlayName, int policies,
                boolean enforce, int userId) {
            final IdmapHeader idmap = mIdmapFiles.get(overlayPath);
            if (idmap == null) {
                return false;
            }
            return idmap.isUpToDate(getCrc(targetPath), getCrc(overlayPath), targetPath, policies,
                    enforce);
        }

        @Override
        boolean idmapExists(String overlayPath, int userId) {
            return mIdmapFiles.containsKey(overlayPath);
        }

        @Override
        FabricatedOverlayInfo createFabricatedOverlay(@NonNull FabricatedOverlayInternal overlay) {
            final String path = Integer.toString(mFabricatedAssetSeq++);
            final FabricatedOverlayInfo info = new FabricatedOverlayInfo();
            info.path = path;
            info.overlayName = overlay.overlayName;
            info.packageName = overlay.packageName;
            info.targetPackageName = overlay.targetPackageName;
            info.targetOverlayable = overlay.targetOverlayable;
            mFabricatedOverlays.put(path, info);
            return info;
        }

        @Override
        boolean deleteFabricatedOverlay(@NonNull String path) {
            return mFabricatedOverlays.remove(path) != null;
        }

        @Override
        List<FabricatedOverlayInfo> getFabricatedOverlayInfos() {
            return new ArrayList<>(mFabricatedOverlays.values());
        }

        IdmapHeader getIdmap(String overlayPath) {
            return mIdmapFiles.get(overlayPath);
        }

        static class IdmapHeader {
            private final int targetCrc;
            private final int overlayCrc;
            final String targetPath;
            final String overlayName;
            final int policies;
            final boolean enforceOverlayable;

            private IdmapHeader(int targetCrc, int overlayCrc, String targetPath,
                    String overlayName, int policies, boolean enforceOverlayable) {
                this.targetCrc = targetCrc;
                this.overlayCrc = overlayCrc;
                this.targetPath = targetPath;
                this.overlayName = overlayName;
                this.policies = policies;
                this.enforceOverlayable = enforceOverlayable;
            }

            private boolean isUpToDate(int expectedTargetCrc, int expectedOverlayCrc,
                    String expectedTargetPath, int expectedPolicies,
                    boolean expectedEnforceOverlayable) {
                return expectedTargetCrc == targetCrc && expectedOverlayCrc == overlayCrc
                        && expectedTargetPath.equals(targetPath) && expectedPolicies == policies
                        && expectedEnforceOverlayable == enforceOverlayable;
            }
        }
    }
}
