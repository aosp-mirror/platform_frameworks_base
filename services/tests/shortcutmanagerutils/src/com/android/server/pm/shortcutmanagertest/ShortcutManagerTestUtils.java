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

import static org.junit.Assert.assertNotEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.Callback;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.test.MoreAsserts;
import android.util.Log;

import junit.framework.Assert;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.json.JSONException;
import org.json.JSONObject;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.hamcrest.MockitoHamcrest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Common utility methods for ShortcutManager tests.  This is used by both CTS and the unit tests.
 * Because it's used by CTS too, it can only access the public APIs.
 */
public class ShortcutManagerTestUtils {
    private static final String TAG = "ShortcutManagerUtils";

    private static final boolean ENABLE_DUMPSYS = true; // DO NOT SUBMIT WITH true

    private static final int STANDARD_TIMEOUT_SEC = 5;

    private static final String[] EMPTY_STRINGS = new String[0];

    private ShortcutManagerTestUtils() {
    }

    public static List<String> readAll(File file) throws FileNotFoundException {
        return readAll(ParcelFileDescriptor.open(
                file.getAbsoluteFile(), ParcelFileDescriptor.MODE_READ_ONLY));
    }

    public static List<String> readAll(ParcelFileDescriptor pfd) {
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

    public static String concatResult(List<String> result) {
        final StringBuilder sb = new StringBuilder();
        for (String s : result) {
            sb.append(s);
            sb.append("\n");
        }
        return sb.toString();
    }

    public static boolean resultContains(List<String> result, String expected) {
        for (String line : result) {
            if (line.contains(expected)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> assertSuccess(List<String> result) {
        if (!resultContains(result, "Success")) {
            fail("Command failed.  Result was:\n" + concatResult(result));
        }
        return result;
    }

    public static List<String> assertContains(List<String> result, String expected) {
        if (!resultContains(result, expected)) {
            fail("Didn't contain expected string=" + expected
                    + "\nActual:\n" + concatResult(result));
        }
        return result;
    }

    public static List<String> runCommand(Instrumentation instrumentation, String command) {
        return runCommand(instrumentation, command, null);
    }
    public static List<String> runCommand(Instrumentation instrumentation, String command,
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

    public static void runCommandForNoOutput(Instrumentation instrumentation, String command) {
        runCommand(instrumentation, command, result -> result.size() == 0);
    }

    public static List<String> runShortcutCommand(Instrumentation instrumentation, String command,
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
        runCommand(instrumentation, "cmd package set-home-activity --user "
                + instrumentation.getContext().getUserId() + " " + component,
                result -> result.contains("Success"));
        runCommand(instrumentation, "cmd shortcut clear-default-launcher --user "
                        + instrumentation.getContext().getUserId(),
                result -> result.contains("Success"));
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

    public static void anyContains(List<String> result, String expected) {
        for (String l : result) {
            if (l.contains(expected)) {
                return;
            }
        }
        fail("Result didn't contain '" + expected + "': was\n" + result);
    }

    public static void enableComponent(Instrumentation instrumentation, ComponentName cn,
            boolean enable) {

        final String word = (enable ? "enable" : "disable");
        runCommand(instrumentation,
                "pm " + word + " " + cn.flattenToString()
                , result ->concatResult(result).contains(word));
    }

    public static void appOps(Instrumentation instrumentation, String packageName,
            String op, String mode) {
        runCommand(instrumentation, "appops set " + packageName + " " + op + " " + mode);
    }

    public static void dumpsysShortcut(Instrumentation instrumentation) {
        if (!ENABLE_DUMPSYS) {
            return;
        }
        Log.e(TAG, "Dumpsys shortcut");
        for (String s : runCommand(instrumentation, "dumpsys shortcut")) {
            Log.e(TAG, s);
        }
    }

    public static JSONObject getCheckinDump(Instrumentation instrumentation) throws JSONException {
        return new JSONObject(concatResult(runCommand(instrumentation, "dumpsys shortcut -c")));
    }

    public static boolean isLowRamDevice(Instrumentation instrumentation) throws JSONException {
        return getCheckinDump(instrumentation).getBoolean("lowRam");
    }

    public static int getIconSize(Instrumentation instrumentation) throws JSONException {
        return getCheckinDump(instrumentation).getInt("iconSize");
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

    public static PersistableBundle makePersistableBundle(Object... keysAndValues) {
        assertTrue((keysAndValues.length % 2) == 0);

        if (keysAndValues.length == 0) {
            return null;
        }
        final PersistableBundle ret = new PersistableBundle();

        for (int i = keysAndValues.length - 2; i >= 0; i -= 2) {
            final String key = keysAndValues[i].toString();
            final Object value = keysAndValues[i + 1];

            if (value == null) {
                ret.putString(key, null);
            } else if (value instanceof Integer) {
                ret.putInt(key, (Integer) value);
            } else if (value instanceof String) {
                ret.putString(key, (String) value);
            } else if (value instanceof PersistableBundle) {
                ret.putPersistableBundle(key, (PersistableBundle) value);
            } else {
                fail("Type not supported yet: " + value.getClass().getName());
            }
        }
        return ret;
    }

    public static <T> T[] array(T... array) {
        return array;
    }

    public static <T> List<T> list(T... array) {
        return Arrays.asList(array);
    }

    public static <T> Set<T> hashSet(Set<T> in) {
        return new LinkedHashSet<>(in);
    }

    public static <T> Set<T> set(T... values) {
        return set(v -> v, values);
    }

    public static <T, V> Set<T> set(Function<V, T> converter, V... values) {
        return set(converter, Arrays.asList(values));
    }

    public static <T, V> Set<T> set(Function<V, T> converter, List<V> values) {
        final LinkedHashSet<T> ret = new LinkedHashSet<>();
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

    public static <T extends Collection<?>> T assertEmpty(T collection) {
        if (collection == null) {
            return collection; // okay.
        }
        assertEquals(0, collection.size());
        return collection;
    }

    public static List<ShortcutInfo> filter(List<ShortcutInfo> list, Predicate<ShortcutInfo> p) {
        final ArrayList<ShortcutInfo> ret = new ArrayList<>(list);
        ret.removeIf(si -> !p.test(si));
        return ret;
    }

    public static List<ShortcutInfo> filterByActivity(List<ShortcutInfo> list,
            ComponentName activity) {
        return filter(list, si ->
                (si.getActivity().equals(activity)
                        && (si.isDeclaredInManifest() || si.isDynamic())));
    }

    public static List<ShortcutInfo> changedSince(List<ShortcutInfo> list, long time) {
        return filter(list, si -> si.getLastChangedTimestamp() >= time);
    }

    @FunctionalInterface
    public interface ExceptionRunnable {
        void run() throws Exception;
    }

    public static void assertExpectException(Class<? extends Throwable> expectedExceptionType,
            String expectedExceptionMessageRegex, ExceptionRunnable r) {
        assertExpectException("", expectedExceptionType, expectedExceptionMessageRegex, r);
    }

    public static void assertCannotUpdateImmutable(Runnable r) {
        assertExpectException(
                IllegalArgumentException.class, "may not be manipulated via APIs", r::run);
    }

    public static void assertDynamicShortcutCountExceeded(Runnable r) {
        assertExpectException(IllegalArgumentException.class,
                "Max number of dynamic shortcuts exceeded", r::run);
    }

    public static void assertExpectException(String message,
            Class<? extends Throwable> expectedExceptionType,
            String expectedExceptionMessageRegex, ExceptionRunnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            Assert.assertTrue(
                    "Expected exception type was " + expectedExceptionType.getName()
                            + " but caught " + e + " (message=" + message + ")",
                    expectedExceptionType.isAssignableFrom(e.getClass()));
            if (expectedExceptionMessageRegex != null) {
                MoreAsserts.assertContainsRegex(expectedExceptionMessageRegex, e.getMessage());
            }
            return; // Pass
        }
        Assert.fail("Expected exception type " + expectedExceptionType.getName()
                + " was not thrown");
    }

    public static List<ShortcutInfo> assertShortcutIds(List<ShortcutInfo> actualShortcuts,
            String... expectedIds) {
        final SortedSet<String> expected = new TreeSet<>(list(expectedIds));
        final SortedSet<String> actual = new TreeSet<>();
        for (ShortcutInfo s : actualShortcuts) {
            actual.add(s.getId());
        }

        // Compare the sets.
        assertEquals(expected, actual);
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertShortcutIdsOrdered(List<ShortcutInfo> actualShortcuts,
            String... expectedIds) {
        final ArrayList<String> expected = new ArrayList<>(list(expectedIds));
        final ArrayList<String> actual = new ArrayList<>();
        for (ShortcutInfo s : actualShortcuts) {
            actual.add(s.getId());
        }
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
            assertNotNull("ID " + s.getId(), s.getShortLabel());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllNotHaveTitle(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getShortLabel());
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
            assertTrue("ID " + s.getId(), s.isDeclaredInManifest());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllNotManifest(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertFalse("ID " + s.getId(), s.isDeclaredInManifest());
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

    public static void assertDynamicOnly(ShortcutInfo si) {
        assertTrue(si.isDynamic());
        assertFalse(si.isPinned());
    }

    public static void assertPinnedOnly(ShortcutInfo si) {
        assertFalse(si.isDynamic());
        assertFalse(si.isDeclaredInManifest());
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
        final Set<Object> set = new LinkedHashSet<>();
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
        return MockitoHamcrest.argThat(m);
    }

    public static List<ShortcutInfo> checkShortcutIds(String... ids) {
        return checkArgument((List<ShortcutInfo> list) -> {
            final Set<String> actualSet = set(si -> si.getId(), list);
            return actualSet.equals(set(ids));

        }, "Shortcut IDs=[" + Arrays.toString(ids) + "]", null);
    }

    public static ShortcutInfo parceled(ShortcutInfo si) {
        Parcel p = Parcel.obtain();
        p.writeParcelable(si, 0);
        p.setDataPosition(0);
        ShortcutInfo si2 = p.readParcelable(ShortcutManagerTestUtils.class.getClassLoader());
        p.recycle();
        return si2;
    }

    public static List<ShortcutInfo> cloneShortcutList(List<ShortcutInfo> list) {
        if (list == null) {
            return null;
        }
        final List<ShortcutInfo> ret = new ArrayList<>(list.size());
        for (ShortcutInfo si : list) {
            ret.add(parceled(si));
        }

        return ret;
    }

    private static final Comparator<ShortcutInfo> sRankComparator =
            (ShortcutInfo a, ShortcutInfo b) -> Integer.compare(a.getRank(), b.getRank());

    public static List<ShortcutInfo> sortedByRank(List<ShortcutInfo> shortcuts) {
        final ArrayList<ShortcutInfo> ret = new ArrayList<>(shortcuts);
        Collections.sort(ret, sRankComparator);
        return ret;
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

    public static final <T> T anyOrNull(Class<T> clazz) {
        return ArgumentMatchers.argThat(value -> true);
    }

    public static final String anyStringOrNull() {
        return ArgumentMatchers.argThat(value -> true);
    }

    public static ShortcutListAsserter assertWith(List<ShortcutInfo> list) {
        return new ShortcutListAsserter(list);
    }

    public static ShortcutListAsserter assertWith(ShortcutInfo... list) {
        return assertWith(list(list));
    }

    /**
     * New style assertion that allows chained calls.
     */
    public static class ShortcutListAsserter {
        private final ShortcutListAsserter mOriginal;
        private final List<ShortcutInfo> mList;

        ShortcutListAsserter(List<ShortcutInfo> list) {
            this(null, list);
        }

        private ShortcutListAsserter(ShortcutListAsserter original, List<ShortcutInfo> list) {
            mOriginal = (original == null) ? this : original;
            mList = (list == null) ? new ArrayList<>(0) : new ArrayList<>(list);
        }

        public ShortcutListAsserter revertToOriginalList() {
            return mOriginal;
        }

        public ShortcutListAsserter selectDynamic() {
            return new ShortcutListAsserter(this,
                    filter(mList, ShortcutInfo::isDynamic));
        }

        public ShortcutListAsserter selectManifest() {
            return new ShortcutListAsserter(this,
                    filter(mList, ShortcutInfo::isDeclaredInManifest));
        }

        public ShortcutListAsserter selectPinned() {
            return new ShortcutListAsserter(this,
                    filter(mList, ShortcutInfo::isPinned));
        }

        public ShortcutListAsserter selectFloating() {
            return new ShortcutListAsserter(this,
                    filter(mList, (si -> si.isPinned()
                            && !(si.isDynamic() || si.isDeclaredInManifest()))));
        }

        public ShortcutListAsserter selectByActivity(ComponentName activity) {
            return new ShortcutListAsserter(this,
                    ShortcutManagerTestUtils.filterByActivity(mList, activity));
        }

        public ShortcutListAsserter selectByChangedSince(long time) {
            return new ShortcutListAsserter(this,
                    ShortcutManagerTestUtils.changedSince(mList, time));
        }

        public ShortcutListAsserter selectByIds(String... ids) {
            final Set<String> idSet = set(ids);
            final ArrayList<ShortcutInfo> selected = new ArrayList<>();
            for (ShortcutInfo si : mList) {
                if (idSet.contains(si.getId())) {
                    selected.add(si);
                    idSet.remove(si.getId());
                }
            }
            if (idSet.size() > 0) {
                fail("Shortcuts not found for IDs=" + idSet);
            }

            return new ShortcutListAsserter(this, selected);
        }

        public ShortcutListAsserter toSortByRank() {
            return new ShortcutListAsserter(this,
                    ShortcutManagerTestUtils.sortedByRank(mList));
        }

        public ShortcutListAsserter call(Consumer<List<ShortcutInfo>> c) {
            c.accept(mList);
            return this;
        }

        public ShortcutListAsserter haveIds(String... expectedIds) {
            assertShortcutIds(mList, expectedIds);
            return this;
        }

        public ShortcutListAsserter haveIdsOrdered(String... expectedIds) {
            assertShortcutIdsOrdered(mList, expectedIds);
            return this;
        }

        private ShortcutListAsserter haveSequentialRanks() {
            for (int i = 0; i < mList.size(); i++) {
                final ShortcutInfo si = mList.get(i);
                assertEquals("Rank not sequential: id=" + si.getId(), i, si.getRank());
            }
            return this;
        }

        public ShortcutListAsserter haveRanksInOrder(String... expectedIds) {
            toSortByRank()
                    .haveSequentialRanks()
                    .haveIdsOrdered(expectedIds);
            return this;
        }

        public ShortcutListAsserter isEmpty() {
            assertEquals(0, mList.size());
            return this;
        }

        public ShortcutListAsserter areAllDynamic() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(), s.isDynamic()));
            return this;
        }

        public ShortcutListAsserter areAllNotDynamic() {
            forAllShortcuts(s -> assertFalse("id=" + s.getId(), s.isDynamic()));
            return this;
        }

        public ShortcutListAsserter areAllPinned() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(), s.isPinned()));
            return this;
        }

        public ShortcutListAsserter areAllNotPinned() {
            forAllShortcuts(s -> assertFalse("id=" + s.getId(), s.isPinned()));
            return this;
        }

        public ShortcutListAsserter areAllManifest() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(), s.isDeclaredInManifest()));
            return this;
        }

        public ShortcutListAsserter areAllNotManifest() {
            forAllShortcuts(s -> assertFalse("id=" + s.getId(), s.isDeclaredInManifest()));
            return this;
        }

        public ShortcutListAsserter areAllImmutable() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(), s.isImmutable()));
            return this;
        }

        public ShortcutListAsserter areAllMutable() {
            forAllShortcuts(s -> assertFalse("id=" + s.getId(), s.isImmutable()));
            return this;
        }

        public ShortcutListAsserter areAllEnabled() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(), s.isEnabled()));
            areAllWithDisabledReason(ShortcutInfo.DISABLED_REASON_NOT_DISABLED);
            return this;
        }

        public ShortcutListAsserter areAllDisabled() {
            forAllShortcuts(s -> assertFalse("id=" + s.getId(), s.isEnabled()));
            forAllShortcuts(s -> assertNotEquals("id=" + s.getId(),
                    ShortcutInfo.DISABLED_REASON_NOT_DISABLED, s.getDisabledReason()));
            return this;
        }

        public ShortcutListAsserter areAllFloating() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(),
                    s.isPinned() && !s.isDeclaredInManifest() && !s.isDynamic()));
            return this;
        }

        public ShortcutListAsserter areAllNotFloating() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(),
                    !(s.isPinned() && !s.isDeclaredInManifest() && !s.isDynamic())));
            return this;
        }

        public ShortcutListAsserter areAllOrphan() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(),
                    !s.isPinned() && !s.isDeclaredInManifest() && !s.isDynamic()));
            return this;
        }

        public ShortcutListAsserter areAllNotOrphan() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(),
                    s.isPinned() || s.isDeclaredInManifest() || s.isDynamic()));
            return this;
        }

        public ShortcutListAsserter areAllVisibleToPublisher() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(), s.isVisibleToPublisher()));
            return this;
        }

        public ShortcutListAsserter areAllNotVisibleToPublisher() {
            forAllShortcuts(s -> assertFalse("id=" + s.getId(), s.isVisibleToPublisher()));
            return this;
        }

        public ShortcutListAsserter areAllWithKeyFieldsOnly() {
            forAllShortcuts(s -> assertTrue("id=" + s.getId(), s.hasKeyFieldsOnly()));
            return this;
        }

        public ShortcutListAsserter areAllNotWithKeyFieldsOnly() {
            forAllShortcuts(s -> assertFalse("id=" + s.getId(), s.hasKeyFieldsOnly()));
            return this;
        }

        public ShortcutListAsserter areAllWithActivity(ComponentName activity) {
            forAllShortcuts(s -> assertEquals("id=" + s.getId(), activity, s.getActivity()));
            return this;
        }

        public ShortcutListAsserter areAllWithNoActivity() {
            forAllShortcuts(s -> assertNull("id=" + s.getId(), s.getActivity()));
            return this;
        }

        public ShortcutListAsserter areAllWithIntent() {
            forAllShortcuts(s -> assertNotNull("id=" + s.getId(), s.getIntent()));
            return this;
        }

        public ShortcutListAsserter areAllWithNoIntent() {
            forAllShortcuts(s -> assertNull("id=" + s.getId(), s.getIntent()));
            return this;
        }

        public ShortcutListAsserter areAllWithDisabledReason(int disabledReason) {
            forAllShortcuts(s -> assertEquals("id=" + s.getId(),
                    disabledReason, s.getDisabledReason()));
            if (disabledReason >= ShortcutInfo.DISABLED_REASON_VERSION_LOWER) {
                areAllNotVisibleToPublisher();
            } else {
                areAllVisibleToPublisher();
            }
            return this;
        }

        public ShortcutListAsserter forAllShortcuts(Consumer<ShortcutInfo> sa) {
            boolean found = false;
            for (int i = 0; i < mList.size(); i++) {
                final ShortcutInfo si = mList.get(i);
                found = true;
                sa.accept(si);
            }
            assertTrue("No shortcuts found.", found);
            return this;
        }

        public ShortcutListAsserter forShortcut(Predicate<ShortcutInfo> p,
                Consumer<ShortcutInfo> sa) {
            boolean found = false;
            for (int i = 0; i < mList.size(); i++) {
                final ShortcutInfo si = mList.get(i);
                if (p.test(si)) {
                    found = true;
                    try {
                        sa.accept(si);
                    } catch (Throwable e) {
                        throw new AssertionError("Assertion failed for shortcut " + si.getId(), e);
                    }
                }
            }
            assertTrue("Shortcut with the given condition not found.", found);
            return this;
        }

        public ShortcutListAsserter forShortcutWithId(String id, Consumer<ShortcutInfo> sa) {
            forShortcut(si -> si.getId().equals(id), sa);

            return this;
        }
    }

    public static void assertBundlesEqual(BaseBundle b1, BaseBundle b2) {
        if (b1 == null && b2 == null) {
            return; // pass
        }
        assertNotNull("b1 is null but b2 is not", b1);
        assertNotNull("b2 is null but b1 is not", b2);

        // HashSet makes the error message readable.
        assertEquals(set(b1.keySet()), set(b2.keySet()));

        for (String key : b1.keySet()) {
            final Object v1 = b1.get(key);
            final Object v2 = b2.get(key);
            if (v1 == null) {
                if (v2 == null) {
                    return;
                }
            }
            if (v1.equals(v2)) {
                return;
            }

            assertTrue("Only either value is null: key=" + key
                    + " b1=" + b1 + " b2=" + b2, v1 != null && v2 != null);
            assertEquals("Class mismatch: key=" + key, v1.getClass(), v2.getClass());

            if (v1 instanceof BaseBundle) {
                assertBundlesEqual((BaseBundle) v1, (BaseBundle) v2);

            } else if (v1 instanceof boolean[]) {
                assertTrue(Arrays.equals((boolean[]) v1, (boolean[]) v2));

            } else if (v1 instanceof int[]) {
                MoreAsserts.assertEquals((int[]) v1, (int[]) v2);

            } else if (v1 instanceof double[]) {
                MoreAsserts.assertEquals((double[]) v1, (double[]) v2);

            } else if (v1 instanceof String[]) {
                MoreAsserts.assertEquals((String[]) v1, (String[]) v2);

            } else if (v1 instanceof Double) {
                if (((Double) v1).isNaN()) {
                    assertTrue(((Double) v2).isNaN());
                } else {
                    assertEquals(v1, v2);
                }

            } else {
                assertEquals(v1, v2);
            }
        }
    }

    public static void waitOnMainThread() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        new Handler(Looper.getMainLooper()).post(() -> latch.countDown());

        latch.await();
    }

    public static class LauncherCallbackAsserter {
        private final LauncherApps.Callback mCallback = mock(LauncherApps.Callback.class);

        private Callback getMockCallback() {
            return mCallback;
        }

        public LauncherCallbackAsserter assertNoCallbackCalled() {
            verify(mCallback, times(0)).onShortcutsChanged(
                    anyString(),
                    any(List.class),
                    any(UserHandle.class));
            return this;
        }

        public LauncherCallbackAsserter assertNoCallbackCalledForPackage(
                String publisherPackageName) {
            verify(mCallback, times(0)).onShortcutsChanged(
                    eq(publisherPackageName),
                    any(List.class),
                    any(UserHandle.class));
            return this;
        }

        public LauncherCallbackAsserter assertNoCallbackCalledForPackageAndUser(
                String publisherPackageName, UserHandle publisherUserHandle) {
            verify(mCallback, times(0)).onShortcutsChanged(
                    eq(publisherPackageName),
                    any(List.class),
                    eq(publisherUserHandle));
            return this;
        }

        public ShortcutListAsserter assertCallbackCalledForPackageAndUser(
                String publisherPackageName, UserHandle publisherUserHandle) {
            final ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
            verify(mCallback, atLeastOnce()).onShortcutsChanged(
                    eq(publisherPackageName),
                    shortcuts.capture(),
                    eq(publisherUserHandle));
            return new ShortcutListAsserter(shortcuts.getValue());
        }
    }

    public static LauncherCallbackAsserter assertForLauncherCallback(
            LauncherApps launcherApps, Runnable body) throws InterruptedException {
        final LauncherCallbackAsserter asserter = new LauncherCallbackAsserter();
        launcherApps.registerCallback(asserter.getMockCallback(),
                new Handler(Looper.getMainLooper()));

        body.run();

        waitOnMainThread();

        // TODO unregister doesn't work well during unit tests.  Figure out and fix it.
        // launcherApps.unregisterCallback(asserter.getMockCallback());

        return asserter;
    }

    public static LauncherCallbackAsserter assertForLauncherCallbackNoThrow(
            LauncherApps launcherApps, Runnable body) {
        try {
            return assertForLauncherCallback(launcherApps, body);
        } catch (InterruptedException e) {
            fail("Caught InterruptedException");
            return null; // Never happens.
        }
    }

    public static void retryUntil(BooleanSupplier checker, String message) {
        retryUntil(checker, message, 30);
    }

    public static void retryUntil(BooleanSupplier checker, String message, long timeoutSeconds) {
        final long timeOut = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (!checker.getAsBoolean()) {
            if (System.currentTimeMillis() > timeOut) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {
            }
        }
        assertTrue(message, checker.getAsBoolean());
    }
}
