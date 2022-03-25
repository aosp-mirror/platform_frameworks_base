/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.set;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.res.XmlResourceParser;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;

import java.util.List;
import java.util.Set;

/**
 * Unit tests for all the IPackageManager related methods in {@link ShortcutService}.
 *
 * All the tests here actually talks to the real IPackageManager, so we can't test complicated
 * cases.  Instead we just make sure they all work reasonably without at least crashing.
 */
@Presubmit
@SmallTest
public class ShortcutManagerTest5 extends BaseShortcutManagerTest {
    private ShortcutService mShortcutService;

    private String mMyPackage;
    private int mMyUserId;

    public static class ShortcutEnabled extends Activity {
    }

    public static class ShortcutDisabled extends Activity {
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);
        mShortcutService = new ShortcutService(getTestContext(), Looper.getMainLooper(),
                /* onyForPackageManagerApis */ true);

        mMyPackage = getTestContext().getPackageName();
        mMyUserId = android.os.Process.myUserHandle().getIdentifier();
    }

    public void testGetPackageUid() {
        assertTrue(mShortcutService.injectGetPackageUid(
                mMyPackage, mMyUserId) != 0);

        assertEquals(-1, mShortcutService.injectGetPackageUid(
                "no.such.package", mMyUserId));
    }

    public void testGetPackageInfo() {
        PackageInfo pi = mShortcutService.getPackageInfo(
                mMyPackage, mMyUserId, /*signature*/ false);
        assertEquals(mMyPackage, pi.packageName);
        assertNull(pi.signatures);
        assertNull(pi.signingInfo);

        pi = mShortcutService.getPackageInfo(
                mMyPackage, mMyUserId, /*signature*/ true);
        assertEquals(mMyPackage, pi.packageName);
        assertNull(pi.signatures);
        assertNotNull(pi.signingInfo);

        pi = mShortcutService.getPackageInfo(
                "no.such.package", mMyUserId, /*signature*/ true);
        assertNull(pi);
    }

    public void testGetApplicationInfo() {
        ApplicationInfo ai = mShortcutService.getApplicationInfo(
                mMyPackage, mMyUserId);
        assertEquals(mMyPackage, ai.packageName);

        ai = mShortcutService.getApplicationInfo(
                "no.such.package", mMyUserId);
        assertNull(ai);
    }

    public void testGetActivityInfoWithMetadata() {
        // Disabled activity
        ActivityInfo ai = mShortcutService.getActivityInfoWithMetadata(
                new ComponentName(mMyPackage, "ShortcutDisabled"), mMyUserId);
        assertNull(ai);

        // Nonexistent
        ai = mShortcutService.getActivityInfoWithMetadata(
                new ComponentName("no.such.package", "ShortcutDisabled"), mMyUserId);
        assertNull(ai);

        // Existent, with no metadata.
        ai = mShortcutService.getActivityInfoWithMetadata(
                new ComponentName(mMyPackage, "a.ShortcutEnabled"), mMyUserId);
        assertEquals(mMyPackage, ai.packageName);
        assertEquals("a.ShortcutEnabled", ai.name);
        assertNull(ai.loadXmlMetaData(getTestContext().getPackageManager(),
                "android.app.shortcuts"));

        // Existent, with a shortcut metadata.
        ai = mShortcutService.getActivityInfoWithMetadata(
                new ComponentName(mMyPackage, "a.Shortcut1"), mMyUserId);
        assertEquals(mMyPackage, ai.packageName);
        assertEquals("a.Shortcut1", ai.name);
        XmlResourceParser meta = ai.loadXmlMetaData(getTestContext().getPackageManager(),
                "android.app.shortcuts");
        assertNotNull(meta);
        meta.close();
    }

    public void testGetInstalledPackages() {
        List<PackageInfo> apks = mShortcutService.getInstalledPackages(mMyUserId);

        Set<String> expectedPackages = set("com.android.settings", mMyPackage);
        for (PackageInfo pi : apks) {
            expectedPackages.remove(pi.packageName);
        }
        assertEquals(set(), expectedPackages);
    }

    public void testGetDefaultMainActivity() {
        ComponentName cn = mShortcutService.injectGetDefaultMainActivity(
                "com.android.settings", mMyUserId);

        assertEquals(
                ComponentName.unflattenFromString("com.android.settings/.Settings"),
                cn);

        // This package has no main activity.
        assertNull(mShortcutService.injectGetDefaultMainActivity(
                mMyPackage, mMyUserId));

        // Nonexistent.
        assertNull(mShortcutService.injectGetDefaultMainActivity(
                "no.such.package", mMyUserId));
    }

    public void testIsMainActivity() {
        assertTrue(mShortcutService.injectIsMainActivity(
                ComponentName.unflattenFromString("com.android.settings/.Settings"), mMyUserId));
        assertFalse(mShortcutService.injectIsMainActivity(
                ComponentName.unflattenFromString("com.android.settings/.xxx"), mMyUserId));
        assertFalse(mShortcutService.injectIsMainActivity(
                ComponentName.unflattenFromString("no.such.package/.xxx"), mMyUserId));

        assertFalse(mShortcutService.injectIsMainActivity(
                new ComponentName(mMyPackage, "a.DisabledMain"), mMyUserId));
        assertFalse(mShortcutService.injectIsMainActivity(
                new ComponentName(mMyPackage, "a.UnexportedMain"), mMyUserId));

    }

    public void testGetMainActivities() {
        assertEquals(1, mShortcutService.injectGetMainActivities(
                "com.android.settings", mMyUserId).size());

        // This APK has no main activities.
        assertEquals(0, mShortcutService.injectGetMainActivities(
                mMyPackage, mMyUserId).size());
    }

    public void testIsActivityEnabledAndExported() {
        assertTrue(mShortcutService.injectIsActivityEnabledAndExported(
                ComponentName.unflattenFromString("com.android.settings/.Settings"), mMyUserId));
        assertFalse(mShortcutService.injectIsActivityEnabledAndExported(
                ComponentName.unflattenFromString("com.android.settings/.xxx"), mMyUserId));
        assertFalse(mShortcutService.injectIsActivityEnabledAndExported(
                ComponentName.unflattenFromString("no.such.package/.xxx"), mMyUserId));

        assertTrue(mShortcutService.injectIsActivityEnabledAndExported(
                new ComponentName(mMyPackage, "com.android.server.pm.ShortcutTestActivity"),
                mMyUserId));

        assertTrue(mShortcutService.injectIsActivityEnabledAndExported(
                new ComponentName(mMyPackage, "a.ShortcutEnabled"), mMyUserId));

        assertFalse(mShortcutService.injectIsActivityEnabledAndExported(
                new ComponentName(mMyPackage, "a.ShortcutDisabled"), mMyUserId));
        assertFalse(mShortcutService.injectIsActivityEnabledAndExported(
                new ComponentName(mMyPackage, "a.ShortcutUnexported"), mMyUserId));

    }
}
