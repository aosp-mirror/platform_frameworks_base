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

package com.android.server.om;

import static android.content.om.OverlayInfo.STATE_DISABLED;
import static android.content.om.OverlayInfo.STATE_ENABLED;
import static android.content.om.OverlayInfo.STATE_MISSING_TARGET;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class OverlayManagerServiceImplTests {
    private OverlayManagerServiceImpl mImpl;
    private DummyDeviceState mState;
    private DummyListener mListener;

    private static final String OVERLAY = "com.dummy.overlay";
    private static final String TARGET = "com.dummy.target";
    private static final int USER = 0;

    private static final String OVERLAY2 = OVERLAY + "2";
    private static final String TARGET2 = TARGET + "2";
    private static final int USER2 = USER + 1;

    private static final String OVERLAY3 = OVERLAY + "3";
    private static final int USER3 = USER2 + 1;


    @Before
    public void setUp() throws Exception {
        mState = new DummyDeviceState();
        mListener = new DummyListener();
        DummyPackageManagerHelper pmh = new DummyPackageManagerHelper(mState);
        mImpl = new OverlayManagerServiceImpl(pmh,
                new DummyIdmapManager(mState, pmh),
                new OverlayManagerSettings(),
                new String[0],
                mListener);
    }

    // tests: basics

    @Test
    public void testGetOverlayInfo() throws Exception {
        installOverlayPackage(OVERLAY, TARGET, USER, false);
        final OverlayInfo oi = mImpl.getOverlayInfo(OVERLAY, USER);
        assertNotNull(oi);
        assertEquals(oi.packageName, OVERLAY);
        assertEquals(oi.targetPackageName, TARGET);
        assertEquals(oi.userId, USER);
    }

    @Test
    public void testGetOverlayInfosForTarget() throws Exception {
        installOverlayPackage(OVERLAY, TARGET, USER, false);
        installOverlayPackage(OVERLAY2, TARGET, USER, false);

        installOverlayPackage(OVERLAY3, TARGET, USER2, false);

        final List<OverlayInfo> ois = mImpl.getOverlayInfosForTarget(TARGET, USER);
        assertEquals(ois.size(), 2);
        assertTrue(ois.contains(mImpl.getOverlayInfo(OVERLAY, USER)));
        assertTrue(ois.contains(mImpl.getOverlayInfo(OVERLAY2, USER)));

        final List<OverlayInfo> ois2 = mImpl.getOverlayInfosForTarget(TARGET, USER2);
        assertEquals(ois2.size(), 1);
        assertTrue(ois2.contains(mImpl.getOverlayInfo(OVERLAY3, USER2)));

        final List<OverlayInfo> ois3 = mImpl.getOverlayInfosForTarget(TARGET, USER3);
        assertNotNull(ois3);
        assertEquals(ois3.size(), 0);

        final List<OverlayInfo> ois4 = mImpl.getOverlayInfosForTarget("no.such.overlay", USER);
        assertNotNull(ois4);
        assertEquals(ois4.size(), 0);
    }

    @Test
    public void testGetOverlayInfosForUser() throws Exception {
        installOverlayPackage(OVERLAY, TARGET, USER, false);
        installOverlayPackage(OVERLAY2, TARGET, USER, false);
        installOverlayPackage(OVERLAY3, TARGET2, USER, false);

        final Map<String, List<OverlayInfo>> everything = mImpl.getOverlaysForUser(USER);
        assertEquals(everything.size(), 2);

        final List<OverlayInfo> ois = everything.get(TARGET);
        assertNotNull(ois);
        assertEquals(ois.size(), 2);
        assertTrue(ois.contains(mImpl.getOverlayInfo(OVERLAY, USER)));
        assertTrue(ois.contains(mImpl.getOverlayInfo(OVERLAY2, USER)));

        final List<OverlayInfo> ois2 = everything.get(TARGET2);
        assertNotNull(ois2);
        assertEquals(ois2.size(), 1);
        assertTrue(ois2.contains(mImpl.getOverlayInfo(OVERLAY3, USER)));

        final Map<String, List<OverlayInfo>> everything2 = mImpl.getOverlaysForUser(USER2);
        assertNotNull(everything2);
        assertEquals(everything2.size(), 0);
    }

    @Test
    public void testPriority() throws Exception {
        installOverlayPackage(OVERLAY, TARGET, USER, false);
        installOverlayPackage(OVERLAY2, TARGET, USER, false);
        installOverlayPackage(OVERLAY3, TARGET, USER, false);

        final OverlayInfo o1 = mImpl.getOverlayInfo(OVERLAY, USER);
        final OverlayInfo o2 = mImpl.getOverlayInfo(OVERLAY2, USER);
        final OverlayInfo o3 = mImpl.getOverlayInfo(OVERLAY3, USER);

        assertOverlayInfoList(TARGET, USER, o1, o2, o3);

        assertTrue(mImpl.setLowestPriority(OVERLAY3, USER));
        assertOverlayInfoList(TARGET, USER, o3, o1, o2);

        assertTrue(mImpl.setHighestPriority(OVERLAY3, USER));
        assertOverlayInfoList(TARGET, USER, o1, o2, o3);

        assertTrue(mImpl.setPriority(OVERLAY, OVERLAY2, USER));
        assertOverlayInfoList(TARGET, USER, o2, o1, o3);
    }

    @Test
    public void testOverlayInfoStateTransitions() throws Exception {
        assertNull(mImpl.getOverlayInfo(OVERLAY, USER));

        installOverlayPackage(OVERLAY, TARGET, USER, true);
        assertState(STATE_MISSING_TARGET, OVERLAY, USER);

        installTargetPackage(TARGET, USER);
        assertState(STATE_DISABLED, OVERLAY, USER);

        mImpl.setEnabled(OVERLAY, true, USER);
        assertState(STATE_ENABLED, OVERLAY, USER);

        // target upgrades do not change the state of the overlay
        beginUpgradeTargetPackage(TARGET, USER);
        assertState(STATE_ENABLED, OVERLAY, USER);

        endUpgradeTargetPackage(TARGET, USER);
        assertState(STATE_ENABLED, OVERLAY, USER);

        uninstallTargetPackage(TARGET, USER);
        assertState(STATE_MISSING_TARGET, OVERLAY, USER);

        installTargetPackage(TARGET, USER);
        assertState(STATE_ENABLED, OVERLAY, USER);
    }

    @Test
    public void testUpdateOverlaysForUser() throws Exception {
        installTargetPackage(TARGET, USER);
        installTargetPackage("some.other.target", USER);
        installOverlayPackage(OVERLAY, TARGET, USER, true);

        // do nothing, expect no change
        List<String> a = mImpl.updateOverlaysForUser(USER);
        assertEquals(1, a.size());
        assertTrue(a.contains(TARGET));

        // upgrade overlay, keep target
        upgradeOverlayPackage(OVERLAY, TARGET, USER, true);
        List<String> b = mImpl.updateOverlaysForUser(USER);
        assertEquals(1, b.size());
        assertTrue(b.contains(TARGET));

        // do nothing, expect no change
        List<String> c = mImpl.updateOverlaysForUser(USER);
        assertEquals(1, c.size());
        assertTrue(c.contains(TARGET));

        // upgrade overlay, switch to new target
        upgradeOverlayPackage(OVERLAY, "some.other.target", USER, true);
        List<String> d = mImpl.updateOverlaysForUser(USER);
        assertEquals(2, d.size());
        assertTrue(d.containsAll(Arrays.asList(TARGET, "some.other.target")));

        // do nothing, expect no change
        List<String> e = mImpl.updateOverlaysForUser(USER);
        assertEquals(1, e.size());
        assertTrue(e.contains("some.other.target"));
    }

    @Test
    public void testOnOverlayPackageUpgraded() throws Exception {
        installTargetPackage(TARGET, USER);
        installOverlayPackage(OVERLAY, TARGET, USER, true);
        mImpl.onOverlayPackageReplacing(OVERLAY, USER);
        mListener.count = 0;
        mImpl.onOverlayPackageReplaced(OVERLAY, USER);
        assertEquals(1, mListener.count);

        // upgrade to a version where the overlay has changed its target
        upgradeOverlayPackage(OVERLAY, "some.other.target", USER, true);
        mImpl.onOverlayPackageReplacing(OVERLAY, USER);
        mListener.count = 0;
        mImpl.onOverlayPackageReplaced(OVERLAY, USER);
        // expect once for the old target package, once for the new target package
        assertEquals(2, mListener.count);

        upgradeOverlayPackage(OVERLAY, "some.other.target", USER, true);
        mImpl.onOverlayPackageReplacing(OVERLAY, USER);
        mListener.count = 0;
        mImpl.onOverlayPackageReplaced(OVERLAY, USER);
        assertEquals(1, mListener.count);
    }

    // tests: listener interface

    @Test
    public void testListener() throws Exception {
        installOverlayPackage(OVERLAY, TARGET, USER, true);
        assertEquals(1, mListener.count);
        mListener.count = 0;

        installTargetPackage(TARGET, USER);
        assertEquals(1, mListener.count);
        mListener.count = 0;

        mImpl.setEnabled(OVERLAY, true, USER);
        assertEquals(1, mListener.count);
        mListener.count = 0;

        mImpl.setEnabled(OVERLAY, true, USER);
        assertEquals(0, mListener.count);
    }

    // helper methods

    private void assertState(int expected, final String overlayPackageName, int userId) {
        int actual = mImpl.getOverlayInfo(OVERLAY, USER).state;
        String msg = String.format("expected %s but was %s:",
                OverlayInfo.stateToString(expected), OverlayInfo.stateToString(actual));
        assertEquals(msg, expected, actual);
    }

    private void assertOverlayInfoList(final String targetPackageName, int userId,
            OverlayInfo... overlayInfos) {
        final List<OverlayInfo> expected =
                mImpl.getOverlayInfosForTarget(targetPackageName, userId);
        final List<OverlayInfo> actual = Arrays.asList(overlayInfos);
        assertEquals(expected, actual);
    }

    private void installTargetPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) != null) {
            throw new IllegalStateException("package already installed");
        }
        mState.add(packageName, null, userId, false);
        mImpl.onTargetPackageAdded(packageName, userId);
    }

    private void beginUpgradeTargetPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) == null) {
            throw new IllegalStateException("package not installed");
        }
        mState.add(packageName, null, userId, false);
        mImpl.onTargetPackageReplacing(packageName, userId);
    }

    private void endUpgradeTargetPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) == null) {
            throw new IllegalStateException("package not installed");
        }
        mState.add(packageName, null, userId, false);
        mImpl.onTargetPackageReplaced(packageName, userId);
    }

    private void uninstallTargetPackage(String packageName, int userId) {
        if (mState.select(packageName, userId) == null) {
            throw new IllegalStateException("package not installed");
        }
        mState.remove(packageName, userId);
        mImpl.onTargetPackageRemoved(packageName, userId);
    }

    private void installOverlayPackage(String packageName, String targetPackageName, int userId,
            boolean canCreateIdmap) {
        if (mState.select(packageName, userId) != null) {
            throw new IllegalStateException("package already installed");
        }
        mState.add(packageName, targetPackageName, userId, canCreateIdmap);
        mImpl.onOverlayPackageAdded(packageName, userId);
    }

    private void upgradeOverlayPackage(String packageName, String targetPackageName, int userId,
            boolean canCreateIdmap) {
        DummyDeviceState.Package pkg = mState.select(packageName, userId);
        if (pkg == null) {
            throw new IllegalStateException("package not installed, cannot upgrade");
        }
        pkg.targetPackageName = targetPackageName;
        pkg.canCreateIdmap = canCreateIdmap;
    }

    private void uninstallOverlayPackage(String packageName, int userId) {
        // implement this when adding support for downloadable overlays
        throw new IllegalArgumentException("not implemented");
    }

    private static final class DummyDeviceState {
        private List<Package> mPackages = new ArrayList<>();

        public void add(String packageName, String targetPackageName, int userId,
                boolean canCreateIdmap) {
            remove(packageName, userId);
            Package pkg = new Package();
            pkg.packageName = packageName;
            pkg.targetPackageName = targetPackageName;
            pkg.userId = userId;
            pkg.canCreateIdmap = canCreateIdmap;
            mPackages.add(pkg);
        }

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

        public List<Package> select(int userId) {
            List<Package> out = new ArrayList<>();
            final int packageCount = mPackages.size();
            for (int i = 0; i < packageCount; i++) {
                final Package pkg = mPackages.get(i);
                if (pkg.userId == userId) {
                    out.add(pkg);
                }
            }
            return out;
        }

        public Package select(String packageName, int userId) {
            final int packageCount = mPackages.size();
            for (int i = 0; i < packageCount; i++) {
                final Package pkg = mPackages.get(i);
                if (pkg.packageName.equals(packageName) && pkg.userId == userId) {
                    return pkg;
                }
            }
            return null;
        }

        private static final class Package {
            public String packageName;
            public int userId;
            public String targetPackageName;
            public boolean canCreateIdmap;
        }
    }

    private static final class DummyPackageManagerHelper implements
            OverlayManagerServiceImpl.PackageManagerHelper {
        private final DummyDeviceState mState;

        DummyPackageManagerHelper(DummyDeviceState state) {
            mState = state;
        }

        @Override
        public PackageInfo getPackageInfo(@NonNull String packageName, int userId) {
            final DummyDeviceState.Package pkg = mState.select(packageName, userId);
            if (pkg == null) {
                return null;
            }
            ApplicationInfo ai = new ApplicationInfo();
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
            List<PackageInfo> out = new ArrayList<>();
            final List<DummyDeviceState.Package> packages = mState.select(userId);
            final int packageCount = packages.size();
            for (int i = 0; i < packageCount; i++) {
                final DummyDeviceState.Package pkg = packages.get(i);
                if (pkg.targetPackageName != null) {
                    out.add(getPackageInfo(pkg.packageName, pkg.userId));
                }
            }
            return out;
        }
    }

    private static class DummyIdmapManager extends IdmapManager {
        private final DummyDeviceState mState;
        private Set<String> mIdmapFiles = new ArraySet<>();

        DummyIdmapManager(DummyDeviceState state, DummyPackageManagerHelper packageManagerHelper) {
            super(null, packageManagerHelper);
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
            if (!o.canCreateIdmap) {
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

    private static class DummyListener implements OverlayManagerServiceImpl.OverlayChangeListener {
        public int count;

        public void onOverlaysChanged(@NonNull String targetPackage, int userId) {
            count++;
        }
    }
}
