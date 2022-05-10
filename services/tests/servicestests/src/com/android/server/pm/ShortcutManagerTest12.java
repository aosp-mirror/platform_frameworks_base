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
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;

import android.app.PendingIntent;
import android.content.pm.ShortcutInfo;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;

import com.android.internal.infra.AndroidFuture;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tests for {@link android.app.appsearch.AppSearchManager} and relevant APIs in ShortcutManager.
 *
 atest -c com.android.server.pm.ShortcutManagerTest12
 */
public class ShortcutManagerTest12 extends BaseShortcutManagerTest {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mService.updateConfigurationLocked(
                ShortcutService.ConfigConstants.KEY_MAX_SHORTCUTS + "=5,"
                        + ShortcutService.ConfigConstants.KEY_SAVE_DELAY_MILLIS + "=1");
    }

    @Override
    protected void tearDown() throws Exception {
        if (mService.isAppSearchEnabled()) {
            setCaller(CALLING_PACKAGE_1, USER_0);
            mService.getPackageShortcutForTest(CALLING_PACKAGE_1, USER_0)
                    .removeAllShortcutsAsync();
        }
        super.tearDown();
    }

    public void testGetShortcutIntents_ReturnsMutablePendingIntents() throws RemoteException {
        setDefaultLauncher(USER_0, LAUNCHER_1);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () ->
                assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))))
        );

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            final PendingIntent intent = mLauncherApps.getShortcutIntent(
                    CALLING_PACKAGE_1, "s1", null, UserHandle.SYSTEM);
            assertNotNull(intent);
        });
    }

    public void testSetDynamicShortcuts_PersistsShortcutsToDisk() throws RemoteException {
        if (!mService.isAppSearchEnabled()) {
            return;
        }
        setCaller(CALLING_PACKAGE_1, USER_0);
        // Verifies setDynamicShortcuts persists shortcuts into AppSearch
        mManager.setDynamicShortcuts(list(
                makeShortcut("s1"),
                makeShortcut("s2"),
                makeShortcut("s3")
        ));
        List<ShortcutInfo> shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(3, shortcuts.size());
        Set<String> shortcutIds =
                shortcuts.stream().map(ShortcutInfo::getId).collect(Collectors.toSet());
        assertTrue(shortcutIds.contains("s1"));
        assertTrue(shortcutIds.contains("s2"));
        assertTrue(shortcutIds.contains("s3"));

        // Verifies removeAllDynamicShortcuts removes shortcuts from persistence layer
        mManager.removeAllDynamicShortcuts();
        shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertTrue(shortcuts.isEmpty());
    }

    public void testAddDynamicShortcuts_PersistsShortcutsToDisk() {
        if (!mService.isAppSearchEnabled()) {
            return;
        }
        setCaller(CALLING_PACKAGE_1, USER_0);
        mManager.setDynamicShortcuts(list(
                makeShortcut("s1"),
                makeShortcut("s2"),
                makeShortcut("s3")
        ));
        // Verifies addDynamicShortcuts persists shortcuts into AppSearch
        mManager.addDynamicShortcuts(list(makeShortcut("s4"), makeShortcut("s5")));
        final List<ShortcutInfo> shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(5, shortcuts.size());
        final Set<String> shortcutIds =
                shortcuts.stream().map(ShortcutInfo::getId).collect(Collectors.toSet());
        assertTrue(shortcutIds.contains("s1"));
        assertTrue(shortcutIds.contains("s2"));
        assertTrue(shortcutIds.contains("s3"));
        assertTrue(shortcutIds.contains("s4"));
        assertTrue(shortcutIds.contains("s5"));
    }

    public void testPushDynamicShortcuts_PersistsShortcutsToDisk() {
        if (!mService.isAppSearchEnabled()) {
            return;
        }
        setCaller(CALLING_PACKAGE_1, USER_0);
        mManager.setDynamicShortcuts(list(
                makeShortcut("s1"),
                makeShortcut("s2"),
                makeShortcut("s3"),
                makeShortcut("s4"),
                makeShortcut("s5")
        ));
        List<ShortcutInfo> shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(5, shortcuts.size());
        Set<String> shortcutIds =
                shortcuts.stream().map(ShortcutInfo::getId).collect(Collectors.toSet());
        assertTrue(shortcutIds.contains("s1"));
        assertTrue(shortcutIds.contains("s2"));
        assertTrue(shortcutIds.contains("s3"));
        assertTrue(shortcutIds.contains("s4"));
        assertTrue(shortcutIds.contains("s5"));
        // Verifies pushDynamicShortcuts further persists shortcuts into AppSearch without
        // removing previous shortcuts when max number of shortcuts is reached.
        mManager.pushDynamicShortcut(makeShortcut("s6"));
        // Increasing the max number of shortcuts since number of results per page in AppSearch
        // is set to match the former.
        mService.updateConfigurationLocked(
                ShortcutService.ConfigConstants.KEY_MAX_SHORTCUTS + "=10,"
                        + ShortcutService.ConfigConstants.KEY_SAVE_DELAY_MILLIS + "=1");
        shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(6, shortcuts.size());
        shortcutIds = shortcuts.stream().map(ShortcutInfo::getId).collect(Collectors.toSet());
        assertTrue(shortcutIds.contains("s1"));
        assertTrue(shortcutIds.contains("s2"));
        assertTrue(shortcutIds.contains("s3"));
        assertTrue(shortcutIds.contains("s4"));
        assertTrue(shortcutIds.contains("s5"));
        assertTrue(shortcutIds.contains("s6"));
    }

    public void testRemoveDynamicShortcuts_RemovesShortcutsFromDisk() {
        if (!mService.isAppSearchEnabled()) {
            return;
        }
        setCaller(CALLING_PACKAGE_1, USER_0);
        mManager.setDynamicShortcuts(list(
                makeShortcut("s1"),
                makeShortcut("s2"),
                makeShortcut("s3"),
                makeShortcut("s4"),
                makeShortcut("s5")
        ));

        // Verifies removeDynamicShortcuts removes shortcuts from persistence layer
        mManager.removeDynamicShortcuts(list("s1"));
        final List<ShortcutInfo> shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(4, shortcuts.size());
        final Set<String> shortcutIds =
                shortcuts.stream().map(ShortcutInfo::getId).collect(Collectors.toSet());
        assertTrue(shortcutIds.contains("s2"));
        assertTrue(shortcutIds.contains("s3"));
        assertTrue(shortcutIds.contains("s4"));
        assertTrue(shortcutIds.contains("s5"));
    }

    public void testRemoveLongLivedShortcuts_RemovesShortcutsFromDisk() {
        if (!mService.isAppSearchEnabled()) {
            return;
        }
        setCaller(CALLING_PACKAGE_1, USER_0);
        mManager.setDynamicShortcuts(list(
                makeShortcut("s1"),
                makeShortcut("s2"),
                makeShortcut("s3"),
                makeShortcut("s4"),
                makeShortcut("s5")
        ));
        mManager.removeDynamicShortcuts(list("s2"));
        final List<ShortcutInfo> shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(4, shortcuts.size());
        final Set<String> shortcutIds =
                shortcuts.stream().map(ShortcutInfo::getId).collect(Collectors.toSet());
        assertTrue(shortcutIds.contains("s1"));
        assertTrue(shortcutIds.contains("s3"));
        assertTrue(shortcutIds.contains("s4"));
        assertTrue(shortcutIds.contains("s5"));
    }

    public void testDisableShortcuts_RemovesShortcutsFromDisk() {
        if (!mService.isAppSearchEnabled()) {
            return;
        }
        setCaller(CALLING_PACKAGE_1, USER_0);
        mManager.setDynamicShortcuts(list(
                makeShortcut("s1"),
                makeShortcut("s2"),
                makeShortcut("s3"),
                makeShortcut("s4"),
                makeShortcut("s5")
        ));
        // Verifies disableShortcuts removes shortcuts from persistence layer
        mManager.disableShortcuts(list("s3"));
        final List<ShortcutInfo> shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(4, shortcuts.size());
        final Set<String> shortcutIds =
                shortcuts.stream().map(ShortcutInfo::getId).collect(Collectors.toSet());
        assertTrue(shortcutIds.contains("s1"));
        assertTrue(shortcutIds.contains("s2"));
        assertTrue(shortcutIds.contains("s4"));
        assertTrue(shortcutIds.contains("s5"));
    }

    public void testUpdateShortcuts_UpdateShortcutsOnDisk() {
        if (!mService.isAppSearchEnabled()) {
            return;
        }
        setCaller(CALLING_PACKAGE_1, USER_0);
        mManager.setDynamicShortcuts(list(
                makeShortcut("s1"),
                makeShortcut("s2"),
                makeShortcut("s3"),
                makeShortcut("s4"),
                makeShortcut("s5")
        ));
        // Verifies disableShortcuts removes shortcuts from persistence layer
        mManager.updateShortcuts(list(makeShortcutWithShortLabel("s3", "custom")));
        final List<ShortcutInfo> shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(5, shortcuts.size());
        final Map<String, ShortcutInfo> map = shortcuts.stream()
                .collect(Collectors.toMap(ShortcutInfo::getId, Function.identity()));
        assertTrue(map.containsKey("s3"));
        assertEquals("custom", map.get("s3").getShortLabel());
    }

    public void testShortcutsExcludedFromLauncher_PersistedToDisk() {
        if (!mService.isAppSearchEnabled()) {
            return;
        }
        setCaller(CALLING_PACKAGE_1, USER_0);
        mManager.setDynamicShortcuts(list(
                makeShortcutExcludedFromLauncher("s1"),
                makeShortcutExcludedFromLauncher("s2"),
                makeShortcutExcludedFromLauncher("s3"),
                makeShortcutExcludedFromLauncher("s4"),
                makeShortcutExcludedFromLauncher("s5")
        ));
        final List<ShortcutInfo> shortcuts = getAllPersistedShortcuts();
        assertNotNull(shortcuts);
        assertEquals(5, shortcuts.size());
        final Map<String, ShortcutInfo> map = shortcuts.stream()
                .collect(Collectors.toMap(ShortcutInfo::getId, Function.identity()));
        assertTrue(map.containsKey("s3"));
        assertEquals("Title-s3", map.get("s3").getShortLabel());
    }


    private List<ShortcutInfo> getAllPersistedShortcuts() {
        try {
            SystemClock.sleep(5000);
            final AndroidFuture<List<ShortcutInfo>> future = new AndroidFuture<>();
            getPersistedShortcut(future);
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
