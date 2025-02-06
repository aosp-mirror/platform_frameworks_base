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

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.set;

import android.content.ComponentName;
import android.content.pm.ShortcutInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.frameworks.servicestests.R;
import com.android.server.pm.ShortcutService.ConfigConstants;

import java.util.Set;

/**
 * Tests related to shortcut rank auto-adjustment.
 */
@Presubmit
@SmallTest
public class ShortcutManagerTest3 extends BaseShortcutManagerTest {

    private static final String CALLING_PACKAGE = CALLING_PACKAGE_1;

    private static final ComponentName A1 = new ComponentName(CALLING_PACKAGE,
            ShortcutActivity.class.getName());

    private static final ComponentName A2 = new ComponentName(CALLING_PACKAGE,
            ShortcutActivity2.class.getName());

    private static final ComponentName A3 = new ComponentName(CALLING_PACKAGE,
            ShortcutActivity3.class.getName());

    private ShortcutInfo shortcut(String id, ComponentName activity, int rank) {
        return makeShortcutWithActivityAndRank(id, activity, rank);
    }

    private ShortcutInfo shortcut(String id, ComponentName activity) {
        return makeShortcutWithActivityAndRank(id, activity, ShortcutInfo.RANK_NOT_SET);
    }

    private ShortcutInfo shortcut(String id, Set<String> categories) {
        return makeShortcutWithCategory(id, categories);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // We don't need throttling during this test class, and also relax the max cap.
        mService.updateConfigurationLocked(
                ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL + "=99999999,"
                + ConfigConstants.KEY_MAX_SHORTCUTS + "=99999999"
        );

        setCaller(CALLING_PACKAGE, USER_10);
    }

    private void publishManifestShortcuts(ComponentName activity, int resId) {
        addManifestShortcutResource(activity, resId);
        updatePackageVersion(CALLING_PACKAGE, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE, USER_10));
    }

    public void disabled_testSetDynamicShortcuts_noManifestShortcuts() {
        mManager.setDynamicShortcuts(list(
                shortcut("s1", A1)
        ));

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s1");

        assertTrue(mManager.setDynamicShortcuts(list(
                shortcut("s5", A1),
                shortcut("s4", A1),
                shortcut("s3", A1)
        )));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s4", "s3");

        // RANK_NOT_SET is always the last.
        assertTrue(mManager.setDynamicShortcuts(list(
                shortcut("s5", A1),
                shortcut("s4", A1, 5),
                shortcut("s3", A1, 3),
                shortcut("s2", A1)
        )));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s3", "s4", "s5", "s2");

        // Same rank, preserve the argument order.
        assertTrue(mManager.setDynamicShortcuts(list(
                shortcut("s5", A1, 5),
                shortcut("s4", A1, 0),
                shortcut("s3", A1, 5)
        )));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s4", "s5", "s3");

        // Multiple activities.
        assertTrue(mManager.setDynamicShortcuts(list(
                shortcut("s5", A1),
                shortcut("s4", A2),
                shortcut("s3", A3)
        )));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5");
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("s4");
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A3)
                .haveRanksInOrder("s3");

        assertTrue(mManager.setDynamicShortcuts(list(
                shortcut("s5", A1, 5),
                shortcut("s4", A1),
                shortcut("s3", A1, 5),
                shortcut("x5", A2, 5),
                shortcut("x4", A2),
                shortcut("x3", A2, 1)
        )));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s4");
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x5", "x4");

        // Clear.  Make sure it wouldn't lead to invalid internals state.
        // (ShortcutService.verifyStates() will do so internally.)
        assertTrue(mManager.setDynamicShortcuts(list()));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1).isEmpty();
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2).isEmpty();
    }

    private void runTestWithManifestShortcuts(Runnable r) {
        publishManifestShortcuts(A1, R.xml.shortcut_5_alt);
        publishManifestShortcuts(A2, R.xml.shortcut_1);

        assertWith(getCallerShortcuts()).selectManifest().selectByActivity(A1)
                .haveRanksInOrder("ms1_alt", "ms2_alt", "ms3_alt", "ms4_alt", "ms5_alt");

        assertWith(getCallerShortcuts()).selectManifest().selectByActivity(A2)
                .haveRanksInOrder("ms1");

        // Existence of manifest shortcuts shouldn't affect dynamic shortcut ranks,
        // so running another test here should pass.
        r.run();

        // And dynamic shortcut tests shouldn't affect manifest shortcuts, so repeat the
        // same check.
        assertWith(getCallerShortcuts()).selectManifest().selectByActivity(A1)
                .haveRanksInOrder("ms1_alt", "ms2_alt", "ms3_alt", "ms4_alt", "ms5_alt");

        assertWith(getCallerShortcuts()).selectManifest().selectByActivity(A2)
                .haveRanksInOrder("ms1");
    }

    public void disabled_Shortcuts_withManifestShortcuts() {
        runTestWithManifestShortcuts(() ->
                disabled_testSetDynamicShortcuts_noManifestShortcuts());
    }

    public void disabled_testAddDynamicShortcuts_noManifestShortcuts() {
        mManager.addDynamicShortcuts(list(
                shortcut("s1", A1)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s1");

        //------------------------------------------------------
        long lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.addDynamicShortcuts(list(
                shortcut("s5", A1, 0),
                shortcut("s4", A1),
                shortcut("s2", A1, 3),
                shortcut("x1", A2),
                shortcut("x3", A2, 2),
                shortcut("x2", A2, 2),
                shortcut("s3", A1, 0)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s1", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s5", "s3", "s1", "s2", "s4", "x3", "x2", "x1");

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.addDynamicShortcuts(list(
                shortcut("s1", A1, 1)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s1", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s1", "s3");

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.addDynamicShortcuts(list(
                shortcut("s1", A1, 1),

                // This is add, not update, so the following means s5 will have NO_RANK,
                // which puts it at the end.
                shortcut("s5", A1),
                shortcut("s3", A1, 0),

                // s10 also has NO_RANK, so it'll be put at the end, even after "s5" as we preserve
                // the argument order.
                shortcut("s10", A1),

                // Note we're changing the activity for x2.
                shortcut("x2", A1, 0),
                shortcut("x10", A2)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s3", "x2", "s1", "s2", "s4", "s5", "s10");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x1", "x10");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s3", "x2", "s1", "s5", "s10", "x1", "x10");

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        // Change the activities again.
        mManager.addDynamicShortcuts(list(
                shortcut("s1", A2),
                shortcut("s2", A2, 999)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s3", "x2", "s4", "s5", "s10");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x1", "x10", "s2", "s1");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s1", "s2", "s4", "s5", "s10");
    }

    public void disabled_testAddDynamicShortcuts_withManifestShortcuts() {
        runTestWithManifestShortcuts(() ->
                disabled_testAddDynamicShortcuts_noManifestShortcuts());
    }

    public void disabled_testUpdateShortcuts_noManifestShortcuts() {
        mManager.addDynamicShortcuts(list(
                shortcut("s5", A1, 0),
                shortcut("s4", A1),
                shortcut("s2", A1, 3),
                shortcut("x1", A2),
                shortcut("x3", A2, 2),
                shortcut("x2", A2, 2),
                shortcut("s3", A1, 0)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        //------------------------------------------------------
        long lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.updateShortcuts(list());
        // Same order.
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .isEmpty();


        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE, list("s2", "s4", "x2"), HANDLE_USER_10);
        });
        // Still same order.
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.updateShortcuts(list(
                shortcut("s4", A1, 1),

                // Rank not changing, should keep the same positions.
                // c.f. in case of addDynamicShortcuts, this means "put them at the end".
                shortcut("s3", A1),
                shortcut("x2", A2)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s4", "s3", "s2");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s4", "s3", "s2", "x2");

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.updateShortcuts(list(
                shortcut("s4", A1, 0),

                // Change the activity without specifying a rank -> keep the same rank.
                shortcut("s5", A2),

                // Change the activity without specifying a rank -> assign a new rank.
                shortcut("x2", A1, 2),

                // "xx" doesn't exist, so it'll be ignored.
                shortcut("xx", A1, 0)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s4", "x2", "s3", "s2");

        // Interesting case: both x3 and s5 originally had rank=0, and in this case s5 has moved
        // to A2 without changing the rank.  So they're tie for the new rank, as well as
        // the "rank changed" bit.  Also in this case, "s5" won't have an implicit order, since
        // its rank isn't changing.  So we sort them by ID, thus s5 comes before x3.
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("s5", "x3", "x1");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s4", "x2", "s5", "x3");

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.updateShortcuts(list(
                shortcut("s3", A3)));

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s4", "x2", "s2");
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("s5", "x3", "x1");
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A3)
                .haveRanksInOrder("s3");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s3", "s2");
    }

    public void disabled_testUpdateShortcuts_withManifestShortcuts() {
        runTestWithManifestShortcuts(() ->
                disabled_testUpdateShortcuts_noManifestShortcuts());
    }

    public void disabled_testDeleteDynamicShortcuts_noManifestShortcuts() {
        mManager.addDynamicShortcuts(list(
                shortcut("s5", A1, 0),
                shortcut("s4", A1),
                shortcut("s2", A1, 3),
                shortcut("x1", A2),
                shortcut("x3", A2, 2),
                shortcut("x2", A2, 2),
                shortcut("s3", A1, 0)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        //------------------------------------------------------
        long lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.removeDynamicShortcuts(list());

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .isEmpty();

        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(
                    CALLING_PACKAGE, list("s2", "s4", "x1", "x2"), HANDLE_USER_10);
        });
        // Still same order.

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.removeDynamicShortcuts(list("s3", "x1", "xxxx"));

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s2", "s4");
    }

    public void disabled_testDeleteDynamicShortcuts_withManifestShortcuts() {
        runTestWithManifestShortcuts(() ->
                disabled_testDeleteDynamicShortcuts_noManifestShortcuts());
    }

    public void disabled_testDisableShortcuts_noManifestShortcuts() {
        mManager.addDynamicShortcuts(list(
                shortcut("s5", A1, 0),
                shortcut("s4", A1),
                shortcut("s2", A1, 3),
                shortcut("x1", A2),
                shortcut("x3", A2, 2),
                shortcut("x2", A2, 2),
                shortcut("s3", A1, 0)
        ));
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        //------------------------------------------------------
        long lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.disableShortcuts(list());

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s3", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2", "x1");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .isEmpty();

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.disableShortcuts(list("s3", "x1", "xxxx"));

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s2", "s4");

        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE, list("s2", "s4", "x2"), HANDLE_USER_10);
        });
        // Still same order.
        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s2", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x3", "x2");

        //------------------------------------------------------
        lastApiTime = ++mInjectedCurrentTimeMillis;

        mManager.disableShortcuts(list("s2", "x3"));

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A1)
                .haveRanksInOrder("s5", "s4");

        assertWith(getCallerShortcuts()).selectDynamic().selectByActivity(A2)
                .haveRanksInOrder("x2");

        assertWith(getCallerShortcuts()).selectDynamic().selectByChangedSince(lastApiTime)
                .haveIds("s4", "x2");
    }

    public void disabled_testDisableShortcuts_withManifestShortcuts() {
        runTestWithManifestShortcuts(() ->
                disabled_testDisableShortcuts_noManifestShortcuts());
    }

    public void testGetSharingShortcutCount() {
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_share_targets);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_10));

        // There are two valid <share-target> definitions in the test manifest with two different
        // categories: {"com.test.category.CATEGORY1", "com.test.category.CATEGORY2"} and
        // {"com.test.category.CATEGORY5", "com.test.category.CATEGORY6"}.
        //
        // Note that a shortcut is a match, only if it has ALL of the categories of at least one
        // of the share-target definitions from the manifest.

        mManager.addDynamicShortcuts(list(
                shortcut("s1", set("com.test.category.CATEGORY1", "com.test.category.CATEGORY2")),
                shortcut("s2", set("com.test.category.CATEGORY5")),
                shortcut("s3", set("com.test.category.CATEGORY5", "com.test.category.CATEGORY6")),
                shortcut("s4", set("com.test.category.CATEGORY1", "com.test.category.CATEGORY2",
                        "com.test.category.CATEGORY5", "com.test.category.CATEGORY6")),
                shortcut("s5", A1)
        ));

        assertEquals(3, getCallerSharingShortcutCount());
    }
}
