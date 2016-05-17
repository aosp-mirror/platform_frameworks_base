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
package com.android.server.pm.shortcutmanagertest;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.test.MoreAsserts;
import android.util.Log;

import junit.framework.Assert;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

public class ShortcutManagerTestUtils {
    private static final String TAG = "ShortcutManagerUtils";

    private static final boolean ENABLE_DUMPSYS = true; // DO NOT SUBMIT WITH true

    private static final int STANDARD_TIMEOUT_SEC = 5;

    private ShortcutManagerTestUtils() {
    }

    private static List<String> readAll(ParcelFileDescriptor pfd) {
        try {
            try {
                final ArrayList<String> ret = new ArrayList<>();
                try (BufferedReader r = new BufferedReader(
                        new FileReader(pfd.getFileDescriptor()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        ret.add(line);
                    }
                    r.readLine();
                }
                return ret;
            } finally {
                pfd.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String concatResult(List<String> result) {
        final StringBuilder sb = new StringBuilder();
        for (String s : result) {
            sb.append(s);
            sb.append("\n");
        }
        return sb.toString();
    }

    private static List<String> runCommand(Instrumentation instrumentation, String command) {
        return runCommand(instrumentation, command, null);
    }
    private static List<String> runCommand(Instrumentation instrumentation, String command,
            Predicate<List<String>> resultAsserter) {
        Log.d(TAG, "Running command: " + command);
        final List<String> result;
        try {
            result = readAll(
                    instrumentation.getUiAutomation().executeShellCommand(command));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (resultAsserter != null && !resultAsserter.test(result)) {
            fail("Command '" + command + "' failed, output was:\n" + concatResult(result));
        }
        return result;
    }

    private static void runCommandForNoOutput(Instrumentation instrumentation, String command) {
        runCommand(instrumentation, command, result -> result.size() == 0);
    }

    private static List<String> runShortcutCommand(Instrumentation instrumentation, String command,
            Predicate<List<String>> resultAsserter) {
        return runCommand(instrumentation, "cmd shortcut " + command, resultAsserter);
    }

    public static List<String> runShortcutCommandForSuccess(Instrumentation instrumentation,
            String command) {
        return runShortcutCommand(instrumentation, command, result -> result.contains("Success"));
    }

    public static String getDefaultLauncher(Instrumentation instrumentation) {
        final String PREFIX = "Launcher: ComponentInfo{";
        final String POSTFIX = "}";
        final List<String> result = runShortcutCommandForSuccess(
                instrumentation, "get-default-launcher");
        for (String s : result) {
            if (s.startsWith(PREFIX) && s.endsWith(POSTFIX)) {
                return s.substring(PREFIX.length(), s.length() - POSTFIX.length());
            }
        }
        fail("Default launcher not found");
        return null;
    }

    public static void setDefaultLauncher(Instrumentation instrumentation, String component) {
        runCommandForNoOutput(instrumentation, "cmd package set-home-activity " + component);
    }

    public static void setDefaultLauncher(Instrumentation instrumentation, Context packageContext) {
        setDefaultLauncher(instrumentation, packageContext.getPackageName()
                + "/android.content.pm.cts.shortcutmanager.packages.Launcher");
    }

    public static void overrideConfig(Instrumentation instrumentation, String config) {
        runShortcutCommandForSuccess(instrumentation, "override-config " + config);
    }

    public static void resetConfig(Instrumentation instrumentation) {
        runShortcutCommandForSuccess(instrumentation, "reset-config");
    }

    public static void resetThrottling(Instrumentation instrumentation) {
        runShortcutCommandForSuccess(instrumentation, "reset-throttling");
    }

    public static void resetAllThrottling(Instrumentation instrumentation) {
        runShortcutCommandForSuccess(instrumentation, "reset-all-throttling");
    }

    public static void clearShortcuts(Instrumentation instrumentation, int userId,
            String packageName) {
        runShortcutCommandForSuccess(instrumentation, "clear-shortcuts "
                + " --user " + userId + " " + packageName);
    }

    public static void dumpsysShortcut(Instrumentation instrumentation) {
        if (!ENABLE_DUMPSYS) {
            return;
        }
        for (String s : runCommand(instrumentation, "dumpsys shortcut")) {
            Log.e(TAG, s);
        }
    }

    public static Bundle makeBundle(Object... keysAndValues) {
        assertTrue((keysAndValues.length % 2) == 0);

        if (keysAndValues.length == 0) {
            return null;
        }
        final Bundle ret = new Bundle();

        for (int i = keysAndValues.length - 2; i >= 0; i -= 2) {
            final String key = keysAndValues[i].toString();
            final Object value = keysAndValues[i + 1];

            if (value == null) {
                ret.putString(key, null);
            } else if (value instanceof Integer) {
                ret.putInt(key, (Integer) value);
            } else if (value instanceof String) {
                ret.putString(key, (String) value);
            } else if (value instanceof Bundle) {
                ret.putBundle(key, (Bundle) value);
            } else {
                fail("Type not supported yet: " + value.getClass().getName());
            }
        }
        return ret;
    }

    public static <T> List<T> list(T... array) {
        return Arrays.asList(array);
    }

    public static <T> Set<T> hashSet(Set<T> in) {
        return new HashSet<T>(in);
    }

    public static <T> Set<T> set(T... values) {
        return set(v -> v, values);
    }

    public static <T, V> Set<T> set(Function<V, T> converter, V... values) {
        return set(converter, Arrays.asList(values));
    }

    public static <T, V> Set<T> set(Function<V, T> converter, List<V> values) {
        final HashSet<T> ret = new HashSet<>();
        for (V v : values) {
            ret.add(converter.apply(v));
        }
        return ret;
    }

    public static void resetAll(Collection<?> mocks) {
        for (Object o : mocks) {
            reset(o);
        }
    }

    public static <T> List<T> assertEmpty(List<T> list) {
        assertEquals(0, list.size());
        return list;
    }

    public static void assertExpectException(Class<? extends Throwable> expectedExceptionType,
            String expectedExceptionMessageRegex, Runnable r) {
        assertExpectException("", expectedExceptionType, expectedExceptionMessageRegex, r);
    }

    public static void assertCannotUpdateImmutable(Runnable r) {
        assertExpectException(
                IllegalArgumentException.class, "may not be manipulated via APIs", r);
    }

    public static void assertDynamicShortcutCountExceeded(Runnable r) {
        assertExpectException(IllegalArgumentException.class,
                "Max number of dynamic shortcuts exceeded", r);
    }

    public static void assertExpectException(String message,
            Class<? extends Throwable> expectedExceptionType,
            String expectedExceptionMessageRegex, Runnable r) {
        try {
            r.run();
            Assert.fail("Expected exception type " + expectedExceptionType.getName()
                    + " was not thrown (message=" + message + ")");
        } catch (Throwable e) {
            Assert.assertTrue(
                    "Expected exception type was " + expectedExceptionType.getName()
                            + " but caught " + e + " (message=" + message + ")",
                    expectedExceptionType.isAssignableFrom(e.getClass()));
            if (expectedExceptionMessageRegex != null) {
                MoreAsserts.assertContainsRegex(expectedExceptionMessageRegex, e.getMessage());
            }
        }
    }

    public static List<ShortcutInfo> assertShortcutIds(List<ShortcutInfo> actualShortcuts,
            String... expectedIds) {
        final HashSet<String> expected = new HashSet<>(list(expectedIds));
        final HashSet<String> actual = new HashSet<>();
        for (ShortcutInfo s : actualShortcuts) {
            actual.add(s.getId());
        }

        // Compare the sets.
        assertEquals(expected, actual);
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllHaveIntents(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNotNull("ID " + s.getId(), s.getIntent());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllNotHaveIntents(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getIntent());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllHaveTitle(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNotNull("ID " + s.getId(), s.getTitle());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllNotHaveTitle(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getTitle());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllHaveIconResId(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId() + " not have icon res ID", s.hasIconResource());
            assertFalse("ID " + s.getId() + " shouldn't have icon FD", s.hasIconFile());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllHaveIconFile(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertFalse("ID " + s.getId() + " shouldn't have icon res ID", s.hasIconResource());
            assertTrue("ID " + s.getId() + " not have icon FD", s.hasIconFile());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllHaveIcon(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId() + " has no icon ", s.hasIconFile() || s.hasIconResource());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllKeyFieldsOnly(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.hasKeyFieldsOnly());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllNotKeyFieldsOnly(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertFalse("ID " + s.getId(), s.hasKeyFieldsOnly());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllDynamic(List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.isDynamic());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllPinned(List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.isPinned());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllDynamicOrPinned(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.isDynamic() || s.isPinned());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllManifest(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.isManifestShortcut());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllNotManifest(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertFalse("ID " + s.getId(), s.isManifestShortcut());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllDisabled(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), !s.isEnabled());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllEnabled(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.isEnabled());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllImmutable(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.isImmutable());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllStringsResolved(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.hasStringResourcesResolved());
        }
        return actualShortcuts;
    }

    public static void assertDynamicOnly(ShortcutInfo si) {
        assertTrue(si.isDynamic());
        assertFalse(si.isPinned());
    }

    public static void assertPinnedOnly(ShortcutInfo si) {
        assertFalse(si.isDynamic());
        assertFalse(si.isManifestShortcut());
        assertTrue(si.isPinned());
    }

    public static void assertDynamicAndPinned(ShortcutInfo si) {
        assertTrue(si.isDynamic());
        assertTrue(si.isPinned());
    }

    public static void assertBitmapSize(int expectedWidth, int expectedHeight, Bitmap bitmap) {
        assertEquals("width", expectedWidth, bitmap.getWidth());
        assertEquals("height", expectedHeight, bitmap.getHeight());
    }

    public static <T> void assertAllUnique(Collection<T> list) {
        final Set<Object> set = new HashSet<>();
        for (T item : list) {
            if (set.contains(item)) {
                fail("Duplicate item found: " + item + " (in the list: " + list + ")");
            }
            set.add(item);
        }
    }

    public static ShortcutInfo findShortcut(List<ShortcutInfo> list, String id) {
        for (ShortcutInfo si : list) {
            if (si.getId().equals(id)) {
                return si;
            }
        }
        fail("Shortcut " + id + " not found in the list");
        return null;
    }

    public static Bitmap pfdToBitmap(ParcelFileDescriptor pfd) {
        assertNotNull(pfd);
        try {
            try {
                return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            } finally {
                pfd.close();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void assertBundleEmpty(BaseBundle b) {
        assertTrue(b == null || b.size() == 0);
    }

    public static void assertCallbackNotReceived(LauncherApps.Callback mock) {
        verify(mock, times(0)).onShortcutsChanged(anyString(), anyList(),
                any(UserHandle.class));
    }

    public static void assertCallbackReceived(LauncherApps.Callback mock,
            UserHandle user, String packageName, String... ids) {
        verify(mock).onShortcutsChanged(eq(packageName), checkShortcutIds(ids),
                eq(user));
    }

    public static boolean checkAssertSuccess(Runnable r) {
        try {
            r.run();
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    public static <T> T checkArgument(Predicate<T> checker, String description,
            List<T> matchedCaptor) {
        final Matcher<T> m = new BaseMatcher<T>() {
            @Override
            public boolean matches(Object item) {
                if (item == null) {
                    return false;
                }
                final T value = (T) item;
                if (!checker.test(value)) {
                    return false;
                }

                if (matchedCaptor != null) {
                    matchedCaptor.add(value);
                }
                return true;
            }

            @Override
            public void describeTo(Description d) {
                d.appendText(description);
            }
        };
        return Mockito.argThat(m);
    }

    public static List<ShortcutInfo> checkShortcutIds(String... ids) {
        return checkArgument((List<ShortcutInfo> list) -> {
            final Set<String> actualSet = set(si -> si.getId(), list);
            return actualSet.equals(set(ids));

        }, "Shortcut IDs=[" + Arrays.toString(ids) + "]", null);
    }

    public static void waitUntil(String message, BooleanSupplier condition) {
        waitUntil(message, condition, STANDARD_TIMEOUT_SEC);
    }

    public static void waitUntil(String message, BooleanSupplier condition, int timeoutSeconds) {
        final long timeout = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < timeout) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        fail("Timed out for: " + message);
    }
}
