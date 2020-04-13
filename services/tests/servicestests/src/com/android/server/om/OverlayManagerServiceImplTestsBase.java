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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.om.OverlayInfo.State;
import android.content.om.OverlayableInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.ArraySet;

import androidx.annotation.Nullable;

import com.android.internal.content.om.OverlayConfig;

import org.junit.Before;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Base class for creating {@link OverlayManagerServiceImplTests} tests. */
class OverlayManagerServiceImplTestsBase {
    private OverlayManagerServiceImpl mImpl;
    private DummyDeviceState mState;
    private DummyListener mListener;

    @Before
    public void setUp() {
        mState = new DummyDeviceState();
        mListener = new DummyListener();
        final DummyPackageManagerHelper pmh = new DummyPackageManagerHelper(mState);

        mImpl = new OverlayManagerServiceImpl(pmh,
                new DummyIdmapManager(mState, pmh),
                new OverlayManagerSettings(),
                mState.mOverlayConfig,
                new String[0],
                mListener);
    }

    public OverlayManagerServiceImpl getImpl() {
        return mImpl;
    }

    public DummyListener getListener() {
        return mListener;
    }

    void assertState(@State int expected, final String overlayPackageName, int userId) {
        final OverlayInfo info = mImpl.getOverlayInfo(overlayPackageName, userId);
        if (info == null) {
            throw new IllegalStateException("package not installed");
        }

        final String msg = String.format("expected %s but was %s:",
                OverlayInfo.stateToString(expected), OverlayInfo.stateToString(info.state));
        assertEquals(msg, expected, info.state);
    }

    void assertOverlayInfoList(final String targetPackageName, int userId,
            OverlayInfo... overlayInfos) {
        final List<OverlayInfo> expected =
                mImpl.getOverlayInfosForTarget(targetPackageName, userId);
        final List<OverlayInfo> actual = Arrays.asList(overlayInfos);
        assertEquals(expected, actual);
    }

    /**
     * Creates an overlay configured through {@link OverlayConfig}.
     *
     * @throws IllegalStateException if the package is already installed
     */
    void addOverlayPackage(String packageName, String targetPackageName, int userId,
            boolean mutable, boolean enabled, int priority) {
        mState.addOverlay(packageName, targetPackageName, userId, mutable, enabled, priority);
    }

    /**
     * Adds the target package to the device.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_ADDED} broadcast.
     *
     * @throws IllegalStateException if the package is not currently installed
     */
    void installTargetPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) != null) {
            throw new IllegalStateException("package already installed");
        }
        mState.addTarget(packageName, userId);
        mImpl.onTargetPackageAdded(packageName, userId);
    }

    /**
     * Begins upgrading the target package.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_REMOVED} broadcast with the
     * {@link android.content.Intent#EXTRA_REPLACING} extra.
     *
     * @throws IllegalStateException if the package is not currently installed
     */
    void beginUpgradeTargetPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) == null) {
            throw new IllegalStateException("package not installed");
        }
        mImpl.onTargetPackageReplacing(packageName, userId);
    }

    /**
     * Ends upgrading the target package.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_ADDED} broadcast with the
     * {@link android.content.Intent#EXTRA_REPLACING} extra.
     *
     * @throws IllegalStateException if the package is not currently installed
     */
    void endUpgradeTargetPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) == null) {
            throw new IllegalStateException("package not installed");
        }
        mState.addTarget(packageName, userId);
        mImpl.onTargetPackageReplaced(packageName, userId);
    }

    /**
     * Removes the target package from the device.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_REMOVED} broadcast.
     *
     * @throws IllegalStateException if the package is not currently installed
     */
    void uninstallTargetPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) == null) {
            throw new IllegalStateException("package not installed");
        }
        mState.remove(packageName, userId);
        mImpl.onTargetPackageRemoved(packageName, userId);
    }

    /**
     * Adds the overlay package to the device.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_ADDED} broadcast.
     *
     * @throws IllegalStateException if the package is already installed
     */
    void installOverlayPackage(String packageName, String targetPackageName, int userId) {
        if (mState.select(packageName, userId) != null) {
            throw new IllegalStateException("package already installed");
        }
        mState.addOverlay(packageName, targetPackageName, userId);
        mImpl.onOverlayPackageAdded(packageName, userId);
    }

    /**
     * Begins upgrading the overlay package.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_REMOVED} broadcast with the
     * {@link android.content.Intent#EXTRA_REPLACING} extra.
     *
     * @throws IllegalStateException if the package is not currently installed
     */
    void beginUpgradeOverlayPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) == null) {
            throw new IllegalStateException("package not installed, cannot upgrade");
        }

        mImpl.onOverlayPackageReplacing(packageName, userId);
    }

    /**
     * Ends upgrading the overlay package, potentially changing its target package.
     *
     * This corresponds to when the OMS receives the
     * {@link android.content.Intent#ACTION_PACKAGE_ADDED} broadcast with the
     * {@link android.content.Intent#EXTRA_REPLACING} extra.
     *
     * @throws IllegalStateException if the package is not currently installed
     */
    void endUpgradeOverlayPackage(String packageName, String targetPackageName, int userId) {
        if (mState.select(packageName, userId) == null) {
            throw new IllegalStateException("package not installed, cannot upgrade");
        }

        mState.addOverlay(packageName, targetPackageName, userId);
        mImpl.onOverlayPackageReplaced(packageName, userId);
    }

    private static final class DummyDeviceState {
        private List<Package> mPackages = new ArrayList<>();
        private OverlayConfig mOverlayConfig = mock(OverlayConfig.class);

        /** Adds a non-overlay to the device. */
        public void addTarget(String packageName, int userId) {
            remove(packageName, userId);
            mPackages.add(new Package(packageName, userId, null, false, false, 0));
        }

        /** Adds an overlay to the device. */
        public void addOverlay(String packageName, String targetPackageName, int userId) {
            addOverlay(packageName, targetPackageName, userId, true, false, OverlayConfig.DEFAULT_PRIORITY);
        }

        /** Adds a configured overlay to the device. */
        public void addOverlay(String packageName, String targetPackageName, int userId,
                boolean mutable, boolean enabled, int priority) {
            remove(packageName, userId);
            mPackages.add(new Package(packageName, userId, targetPackageName, mutable, enabled,
                    priority));
            when(mOverlayConfig.getPriority(packageName)).thenReturn(priority);
            when(mOverlayConfig.isEnabled(packageName)).thenReturn(enabled);
            when(mOverlayConfig.isMutable(packageName)).thenReturn(mutable);
        }

        /** Remove a package from the device. */
        public void remove(String packageName, int userId) {
            final Iterator<Package> iter = mPackages.iterator();
            while (iter.hasNext()) {
                final Package pkg = iter.next();
                if (pkg.packageName.equals(packageName) && pkg.userId == userId) {
                    iter.remove();
                    return;
                }
            }
        }

        /** Retrieves all packages on device for a particular user. */
        public List<Package> select(int userId) {
            return mPackages.stream().filter(p -> p.userId == userId).collect(Collectors.toList());
        }

        /** Retrieves the package with the specified package name for a particular user. */
        public Package select(String packageName, int userId) {
            return mPackages.stream().filter(
                    p -> p.packageName.equals(packageName) && p.userId == userId)
                    .findFirst().orElse(null);
        }

        private static final class Package {
            public final String packageName;
            public final int userId;
            public final String targetPackageName;
            public final boolean mutable;
            public final boolean enabled;
            public final int priority;

            private Package(String packageName, int userId, String targetPackageName,
                    boolean mutable, boolean enabled, int priority) {
                this.packageName = packageName;
                this.userId = userId;
                this.targetPackageName = targetPackageName;
                this.mutable = mutable;
                this.enabled = enabled;
                this.priority = priority;
            }
        }
    }

    static final class DummyPackageManagerHelper implements PackageManagerHelper,
            OverlayableInfoCallback {
        private final DummyDeviceState mState;

        private DummyPackageManagerHelper(DummyDeviceState state) {
            mState = state;
        }

        @Override
        public PackageInfo getPackageInfo(@NonNull String packageName, int userId) {
            final DummyDeviceState.Package pkg = mState.select(packageName, userId);
            if (pkg == null) {
                return null;
            }
            final ApplicationInfo ai = new ApplicationInfo();
            ai.sourceDir = String.format("%s/%s/base.apk",
                    pkg.targetPackageName == null ? "/system/app/" : "/vendor/overlay/",
                    pkg.packageName);
            PackageInfo pi = new PackageInfo();
            pi.applicationInfo = ai;
            pi.packageName = pkg.packageName;
            pi.overlayTarget = pkg.targetPackageName;
            pi.overlayCategory = "dummy-category-" + pkg.targetPackageName;
            return pi;
        }

        @Override
        public boolean signaturesMatching(@NonNull String packageName1,
                @NonNull String packageName2, int userId) {
            return false;
        }

        @Override
        public List<PackageInfo> getOverlayPackages(int userId) {
            return mState.select(userId).stream()
                    .filter(p -> p.targetPackageName != null)
                    .map(p -> getPackageInfo(p.packageName, p.userId))
                    .collect(Collectors.toList());
        }

        @Nullable
        @Override
        public OverlayableInfo getOverlayableForTarget(@NonNull String packageName,
                @NonNull String targetOverlayableName, int userId) {
            throw new UnsupportedOperationException();
        }

        @Nullable
        @Override
        public String[] getPackagesForUid(int uid) {
            throw new UnsupportedOperationException();
        }

        @NonNull
        @Override
        public Map<String, Map<String, String>> getNamedActors() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean doesTargetDefineOverlayable(String targetPackageName, int userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void enforcePermission(String permission, String message) throws SecurityException {
            throw new UnsupportedOperationException();
        }
    }

    static class DummyIdmapManager extends IdmapManager {
        private final DummyDeviceState mState;
        private Set<String> mIdmapFiles = new ArraySet<>();

        private DummyIdmapManager(DummyDeviceState state,
                DummyPackageManagerHelper packageManagerHelper) {
            super(packageManagerHelper);
            mState = state;
        }

        @Override
        boolean createIdmap(@NonNull final PackageInfo targetPackage,
                @NonNull final PackageInfo overlayPackage, int userId) {
            final DummyDeviceState.Package t = mState.select(targetPackage.packageName, userId);
            if (t == null) {
                return false;
            }
            final DummyDeviceState.Package o = mState.select(overlayPackage.packageName, userId);
            if (o == null) {
                return false;
            }
            final String key = createKey(overlayPackage.packageName, userId);
            mIdmapFiles.add(key);
            return true;
        }

        @Override
        boolean removeIdmap(@NonNull final OverlayInfo oi, final int userId) {
            final String key = createKey(oi.packageName, oi.userId);
            if (!mIdmapFiles.contains(key)) {
                return false;
            }
            mIdmapFiles.remove(key);
            return true;
        }

        @Override
        boolean idmapExists(@NonNull final OverlayInfo oi) {
            final String key = createKey(oi.packageName, oi.userId);
            return mIdmapFiles.contains(key);
        }

        @Override
        boolean idmapExists(@NonNull final PackageInfo overlayPackage, final int userId) {
            final String key = createKey(overlayPackage.packageName, userId);
            return mIdmapFiles.contains(key);
        }

        private String createKey(@NonNull final String packageName, final int userId) {
            return String.format("%s:%d", packageName, userId);
        }
    }

    static class DummyListener implements OverlayManagerServiceImpl.OverlayChangeListener {
        public int count;

        public void onOverlaysChanged(@NonNull String targetPackage, int userId) {
            count++;
        }
    }
}
