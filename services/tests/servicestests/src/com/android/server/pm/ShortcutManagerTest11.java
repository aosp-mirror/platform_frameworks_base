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
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutChangeCallback;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.ShortcutInfo;
import android.os.test.TestLooper;

import com.android.server.pm.ShortcutService.ConfigConstants;

import org.mockito.ArgumentCaptor;

import java.util.List;

/**
 * Tests for {@link android.content.pm.LauncherApps.ShortcutChangeCallback} and relevant APIs.
 *
 atest -c com.android.server.pm.ShortcutManagerTest11
 */
public class ShortcutManagerTest11 extends BaseShortcutManagerTest {

    private static final ShortcutQuery QUERY_MATCH_ALL = createShortcutQuery(
            ShortcutQuery.FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED);

    private static final int CACHE_OWNER_0 = LauncherApps.FLAG_CACHE_NOTIFICATION_SHORTCUTS;
    private static final int CACHE_OWNER_1 = LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS;

    private final TestLooper mTestLooper = new TestLooper();

    public void testShortcutChangeCallback_setDynamicShortcuts() {
        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s1", "s2")));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s2");
    }

    public void testShortcutChangeCallback_setDynamicShortcuts_replaceSameId() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s1", "s2")));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s2", "s3")));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));

        ArgumentCaptor<List> removedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), removedShortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2", "s3");

        assertWith(removedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1");
    }

    public void testShortcutChangeCallback_setDynamicShortcuts_pinnedAndCached() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(
                    list(makeShortcut("s1"), makeLongLivedShortcut("s2"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_0);
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0,
                    CACHE_OWNER_0);
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s3", "s4")));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s2", "s3", "s4");
    }

    public void testShortcutChangeCallback_pinShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s1", "s2")));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_0);
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1");
    }

    public void testShortcutChangeCallback_pinShortcuts_unpinOthers() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s1", "s2", "s3")));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1", "s2"), HANDLE_USER_0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeDynamicShortcuts(list("s1", "s2"));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2", "s3"), HANDLE_USER_0);
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));

        ArgumentCaptor<List> removedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), removedShortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2", "s3");

        assertWith(removedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1");
    }

    public void testShortcutChangeCallback_cacheShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeLongLivedShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeLongLivedShortcut("s3"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s3"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s3"), HANDLE_USER_0,
                    CACHE_OWNER_1);
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(2)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s3");
    }

    public void testShortcutChangeCallback_cacheShortcuts_alreadyCached() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeLongLivedShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeLongLivedShortcut("s3"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s3"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
            // Should not cause any callback events
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s3"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            // Should cause a change event
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s3"), HANDLE_USER_0,
                    CACHE_OWNER_1);
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s3");
    }

    public void testShortcutChangeCallback_uncacheShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeLongLivedShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeLongLivedShortcut("s3"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s2"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
            mLauncherApps.uncacheShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0,
                    CACHE_OWNER_0);
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2");
    }

    public void testShortcutChangeCallback_uncacheShortcuts_causeDeletion() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeLongLivedShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeLongLivedShortcut("s3"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0);
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_0,
                    CACHE_OWNER_1);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeDynamicShortcuts(list("s2", "s3"));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
            mLauncherApps.uncacheShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3"), HANDLE_USER_0,
                    CACHE_OWNER_0);
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));

        ArgumentCaptor<List> removedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), removedShortcuts.capture(), eq(HANDLE_USER_0));

        // s1 is still cached for owner1, s2 is pinned.
        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s2");

        assertWith(removedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s3");
    }

    public void testShortcutChangeCallback_updateShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"),
                    makeShortcutWithActivity("s2", new ComponentName(CALLING_PACKAGE_1, "test")))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        final ComponentName updatedCn = new ComponentName(CALLING_PACKAGE_1, "updated activity");
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.updateShortcuts(list(makeShortcutWithActivity("s2", updatedCn))));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2");
        assertEquals(updatedCn, ((ShortcutInfo) shortcuts.getValue().get(0)).getActivity());
    }

    public void testShortcutChangeCallback_addDynamicShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s1")));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.addDynamicShortcuts(makeShortcuts("s1", "s2")));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s2");
    }

    public void testShortcutChangeCallback_pushDynamicShortcut() {
        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.pushDynamicShortcut(makeShortcut("s1"));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1");
    }

    public void testShortcutChangeCallback_pushDynamicShortcut_existingId() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts((makeShortcuts("s1", "s2", "s3"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.pushDynamicShortcut(makeShortcut("s2"));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2");
    }

    public void testShortcutChangeCallback_pushDynamicShortcut_causeDeletion() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts((makeShortcuts("s1", "s2", "s3"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.pushDynamicShortcut(makeShortcut("s4"));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));

        ArgumentCaptor<List> removedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), removedShortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s4");

        assertWith(removedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s3");
    }

    public void testShortcutChangeCallback_pushDynamicShortcut_causeDeletionButCached() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts((makeShortcuts("s1", "s2"))));
            ShortcutInfo s3 = makeLongLivedShortcut("s3");
            s3.setRank(3);
            mManager.pushDynamicShortcut(s3);  // Add a long lived shortcut to the end of the list.
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s3"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.pushDynamicShortcut(makeShortcut("s4"));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s3", "s4");
    }

    public void testShortcutChangeCallback_disableShortcuts() {
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s1", "s2")));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.disableShortcuts(list("s2"));
        });

        mTestLooper.dispatchAll();

        verify(callback, times(0)).onShortcutsAddedOrUpdated(any(), any(), any());

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2");
    }

    public void testShortcutChangeCallback_disableShortcuts_pinnedAndCached() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(
                    list(makeShortcut("s1"), makeLongLivedShortcut("s2"), makeShortcut("s3"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3"), HANDLE_USER_0);
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.disableShortcuts(list("s1", "s2", "s3"));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));

        ArgumentCaptor<List> removedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), removedShortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2", "s3");

        assertWith(removedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1");
    }

    public void testShortcutChangeCallback_enableShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(
                    list(makeShortcut("s1"), makeLongLivedShortcut("s2"), makeShortcut("s3"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3"), HANDLE_USER_0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.disableShortcuts(list("s1", "s2", "s3"));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.enableShortcuts(list("s1", "s2", "s3"));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));
        verify(callback, times(0)).onShortcutsRemoved(any(), any(), any());

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2", "s3");
    }

    public void testShortcutChangeCallback_removeDynamicShortcuts() {
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s1", "s2")));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeDynamicShortcuts(list("s2"));
        });

        mTestLooper.dispatchAll();

        verify(callback, times(0)).onShortcutsAddedOrUpdated(any(), any(), any());

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2");
    }

    public void testShortcutChangeCallback_removeDynamicShortcuts_pinnedAndCached() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeShortcut("s3"), makeShortcut("s4"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3"), HANDLE_USER_0);
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeDynamicShortcuts(list("s1", "s2", "s3"));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));

        ArgumentCaptor<List> removedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), removedShortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2", "s3");

        assertWith(removedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1");
    }

    public void testShortcutChangeCallback_removeAllDynamicShortcuts() {
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(makeShortcuts("s1", "s2")));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();
        });

        mTestLooper.dispatchAll();

        verify(callback, times(0)).onShortcutsAddedOrUpdated(any(), any(), any());

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s2");
    }

    public void testShortcutChangeCallback_removeAllDynamicShortcuts_pinnedAndCached() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(
                    list(makeShortcut("s1"), makeLongLivedShortcut("s2"), makeShortcut("s3"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3"), HANDLE_USER_0);
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));

        ArgumentCaptor<List> removedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), removedShortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s2", "s3");

        assertWith(removedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1");
    }

    public void testShortcutChangeCallback_removeLongLivedShortcuts_notCached() {
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeShortcut("s3"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeLongLivedShortcuts(list("s1", "s2"));
        });

        mTestLooper.dispatchAll();

        verify(callback, times(0)).onShortcutsAddedOrUpdated(any(), any(), any());

        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), shortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(shortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s2");
    }

    public void testShortcutChangeCallback_removeLongLivedShortcuts_pinnedAndCached() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeShortcut("s3"), makeShortcut("s4"))));
        });

        ShortcutChangeCallback callback = mock(ShortcutChangeCallback.class);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3"), HANDLE_USER_0);
            mLauncherApps.registerShortcutChangeCallback(callback, QUERY_MATCH_ALL,
                    mTestLooper.getNewExecutor());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeLongLivedShortcuts(list("s1", "s2", "s3"));
        });

        mTestLooper.dispatchAll();

        ArgumentCaptor<List> changedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsAddedOrUpdated(
                eq(CALLING_PACKAGE_1), changedShortcuts.capture(), eq(HANDLE_USER_0));

        ArgumentCaptor<List> removedShortcuts = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onShortcutsRemoved(
                eq(CALLING_PACKAGE_1), removedShortcuts.capture(), eq(HANDLE_USER_0));

        assertWith(changedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s3");

        assertWith(removedShortcuts.getValue())
                .areAllWithKeyFieldsOnly()
                .haveIds("s1", "s2");
    }

    private static ShortcutQuery createShortcutQuery(int queryFlags) {
        ShortcutQuery q = new ShortcutQuery();
        return q.setQueryFlags(ShortcutQuery.FLAG_MATCH_ALL_KINDS_WITH_ALL_PINNED);
    }
}
