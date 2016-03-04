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

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.pm.ShortcutServiceInternal;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.test.mock.MockContext;
import android.util.Log;

import com.android.frameworks.servicestests.R;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.junit.Assert;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Tests for ShortcutService and ShortcutManager.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
public class ShortcutManagerTest extends AndroidTestCase {
    private static final String TAG = "ShortcutManagerTest";

    /**
     * Whether to enable dump or not.  Should be only true when debugging to avoid bugs where
     * dump affecting the behavior.
     */
    private static final boolean ENABLE_DUMP = true; // DO NOT SUBMIT WITH true

    /** Context used in the client side */
    private final class ClientContext extends MockContext {
        @Override
        public String getPackageName() {
            return mInjectedClientPackage;
        }
    }

    /** Context used in the service side */
    private final class ServiceContext extends MockContext {
    }

    /** ShortcutService with injection override methods. */
    private final class ShortcutServiceTestable extends ShortcutService {
        public ShortcutServiceTestable(Context context) {
            super(context);

        }

        @Override
        void injectLoadConfigurationLocked() {
            setResetIntervalForTest(INTERVAL);
            setMaxDynamicShortcutsForTest(MAX_SHORTCUTS);
            setMaxDailyUpdatesForTest(MAX_DAILY_UPDATES);
        }

        @Override
        long injectCurrentTimeMillis() {
            return mInjectedCurrentTimeLillis;
        }

        @Override
        int injectBinderCallingUid() {
            return mInjectedCallingUid;
        }

        @Override
        int injectGetPackageUid(String packageName) {
            Integer uid = mInjectedPackageUidMap.get(packageName);
            return uid != null ? uid : -1;
        }

        @Override
        File injectSystemDataPath() {
            return new File(mInjectedFilePathRoot, "system");
        }

        @Override
        File injectUserDataPath(@UserIdInt int userId) {
            return new File(mInjectedFilePathRoot, "user-" + userId);
        }
    }

    /** ShortcutManager with injection override methods. */
    private final class ShortcutManagerTestable extends ShortcutManager {
        public ShortcutManagerTestable(Context context, ShortcutServiceTestable service) {
            super(context, service);
        }

        @Override
        protected int injectMyUserId() {
            return UserHandle.getUserId(mInjectedCallingUid);
        }
    }


    public static class ShortcutActivity extends Activity {
    }

    public static class ShortcutActivity2 extends Activity {
    }

    public static class ShortcutActivity3 extends Activity {
    }

    private ServiceContext mServiceContext;
    private ClientContext mClientContext;

    private ShortcutServiceTestable mService;
    private ShortcutManagerTestable mManager;
    private ShortcutServiceInternal mInternal;

    private File mInjectedFilePathRoot;

    private long mInjectedCurrentTimeLillis;

    private int mInjectedCallingUid;
    private String mInjectedClientPackage;

    private Map<String, Integer> mInjectedPackageUidMap;

    private static final String CALLING_PACKAGE_1 = "com.android.test.1";
    private static final int CALLING_UID_1 = 10001;

    private static final String CALLING_PACKAGE_2 = "com.android.test.2";
    private static final int CALLING_UID_2 = 10002;

    private static final String CALLING_PACKAGE_3 = "com.android.test.3";
    private static final int CALLING_UID_3 = 10003;

    private static final String LAUNCHER_1 = "com.android.launcher.1";
    private static final int LAUNCHER_UID_1 = 10011;

    private static final String LAUNCHER_2 = "com.android.launcher.2";
    private static final int LAUNCHER_UID_2 = 10012;

    private static final long START_TIME = 1234560000000L;

    private static final long INTERVAL = 10000;

    private static final int MAX_SHORTCUTS = 5;

    private static final int MAX_DAILY_UPDATES = 3;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mServiceContext = new ServiceContext();
        mClientContext = new ClientContext();

        // Prepare injection values.

        mInjectedCurrentTimeLillis = START_TIME;

        mInjectedPackageUidMap = new HashMap<>();
        mInjectedPackageUidMap.put(CALLING_PACKAGE_1, CALLING_UID_1);
        mInjectedPackageUidMap.put(CALLING_PACKAGE_2, CALLING_UID_2);
        mInjectedPackageUidMap.put(CALLING_PACKAGE_3, CALLING_UID_3);
        mInjectedPackageUidMap.put(LAUNCHER_1, LAUNCHER_UID_1);
        mInjectedPackageUidMap.put(LAUNCHER_2, LAUNCHER_UID_2);

        mInjectedFilePathRoot = new File(getContext().getCacheDir(), "test-files");

        // Empty the data directory.
        if (mInjectedFilePathRoot.exists()) {
            Assert.assertTrue("failed to delete dir",
                    FileUtils.deleteContents(mInjectedFilePathRoot));
        }
        mInjectedFilePathRoot.mkdirs();

        initService();
        setCaller(CALLING_PACKAGE_1);
    }

    /** (Re-) init the manager and the service. */
    private void initService() {
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);

        // Instantiate targets.
        mService = new ShortcutServiceTestable(mServiceContext);
        mManager = new ShortcutManagerTestable(mClientContext, mService);

        mInternal = LocalServices.getService(ShortcutServiceInternal.class);

        // Load the setting file.
        mService.onBootPhase(SystemService.PHASE_LOCK_SETTINGS_READY);
    }

    /** Replace the current calling package */
    private void setCaller(String packageName) {
        mInjectedClientPackage = packageName;
        mInjectedCallingUid = Preconditions.checkNotNull(mInjectedPackageUidMap.get(packageName));
    }

    private String getCallingPackage() {
        return mInjectedClientPackage;
    }

    private int getCallingUserId() {
        return UserHandle.getUserId(mInjectedCallingUid);
    }

    /** For debugging */
    private void dumpsysOnLogcat() {
        if (!ENABLE_DUMP) return;

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintWriter pw = new PrintWriter(out);
        mService.dumpInner(pw);
        pw.close();

        Log.e(TAG, "Dumping ShortcutService:");
        for (String line : out.toString().split("\n")) {
            Log.e(TAG, line);
        }
    }

    /**
     * For debugging, dump arbitrary file on logcat.
     */
    private void dumpFileOnLogcat(String path) {
        if (!ENABLE_DUMP) return;

        Log.i(TAG, "Dumping file: " + path);
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                Log.i(TAG, line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't read file", e);
            fail("Exception " + e);
        }
    }

    /**
     * For debugging, dump the main state file on logcat.
     */
    private void dumpBaseStateFile() {
        dumpFileOnLogcat(mInjectedFilePathRoot.getAbsolutePath()
                + "/system/" + ShortcutService.FILENAME_BASE_STATE);
    }

    /**
     * For debugging, dump per-user state file on logcat.
     */
    private void dumpUserFile(int userId) {
        dumpFileOnLogcat(mInjectedFilePathRoot.getAbsolutePath()
                + "/user-" + userId
                + "/" + ShortcutService.FILENAME_USER_PACKAGES);
    }

    private static Bundle makeBundle(Object... keysAndValues) {
        Preconditions.checkState((keysAndValues.length % 2) == 0);

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

    /**
     * Make a shortcut with an ID.
     */
    private ShortcutInfo makeShortcut(String id) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
    }

    /**
     * Make a shortcut with an ID and timestamp.
     */
    private ShortcutInfo makeShortcutWithTimestamp(String id, long timestamp) {
        final ShortcutInfo s = makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
        s.setTimestamp(timestamp);
        return s;
    }

    /**
     * Make multiple shortcuts with IDs.
     */
    private List<ShortcutInfo> makeShortcuts(String... ids) {
        final ArrayList<ShortcutInfo> ret = new ArrayList();
        for (String id : ids) {
            ret.add(makeShortcut(id));
        }
        return ret;
    }

    /**
     * Make a shortcut with details.
     */
    private ShortcutInfo makeShortcut(String id, String title, ComponentName activity,
            Icon icon, Intent intent, int weight) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext)
                .setId(id)
                .setTitle(title)
                .setWeight(weight)
                .setIntent(intent);
        if (icon != null) {
            b.setIcon(icon);
        }
        if (activity != null) {
            b.setActivityComponent(activity);
        }
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeLillis); // HACK

        return s;
    }

    /**
     * Make an intent.
     */
    private Intent makeIntent(String action, Class<?> clazz, Object... bundleKeysAndValues) {
        final Intent intent = new Intent(action);
        intent.setComponent(makeComponent(clazz));
        intent.replaceExtras(makeBundle(bundleKeysAndValues));
        return intent;
    }

    /**
     * Make an component name, with the client context.
     */
    @NonNull
    private ComponentName makeComponent(Class<?> clazz) {
        return new ComponentName(mClientContext, clazz);
    }

    @NonNull
    private ShortcutInfo findById(List<ShortcutInfo> list, String id) {
        for (ShortcutInfo s : list) {
            if (s.getId().equals(id)) {
                return s;
            }
        }
        fail("Shortcut with id " + id + " not found");
        return null;
    }

    private void assertResetTimes(long expectedLastResetTime, long expectedNextResetTime) {
        assertEquals(expectedLastResetTime, mService.getLastResetTimeLocked());
        assertEquals(expectedNextResetTime, mService.getNextResetTimeLocked());
    }

    @NonNull
    private List<ShortcutInfo> assertShortcutIds(@NonNull List<ShortcutInfo> actualShortcuts,
            String... expectedIds) {
        final HashSet<String> expected = new HashSet<>(Arrays.asList(expectedIds));
        final HashSet<String> actual = new HashSet<>();
        for (ShortcutInfo s : actualShortcuts) {
            actual.add(s.getId());
        }

        // Compare the sets.
        assertEquals(expected, actual);
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveIntents(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNotNull("ID " + s.getId(), s.getIntent());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllNotHaveIntents(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getIntent());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveTitle(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNotNull("ID " + s.getId(), s.getTitle());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllNotHaveTitle(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getTitle());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveFlags(@NonNull List<ShortcutInfo> actualShortcuts,
            int shortcutFlags) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.hasFlags(shortcutFlags));
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllDynamic(@NonNull List<ShortcutInfo> actualShortcuts) {
        return assertAllHaveFlags(actualShortcuts, ShortcutInfo.FLAG_DYNAMIC);
    }

    @NonNull
    private List<ShortcutInfo> assertAllPinned(@NonNull List<ShortcutInfo> actualShortcuts) {
        return assertAllHaveFlags(actualShortcuts, ShortcutInfo.FLAG_PINNED);
    }

    @NonNull
    private List<ShortcutInfo> assertAllDynamicOrPinned(
            @NonNull List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.isDynamic() || s.isPinned());
        }
        return actualShortcuts;
    }

    /**
     * Test for the first launch path, no settings file available.
     */
    public void testFirstInitialize() {
        assertResetTimes(START_TIME, START_TIME + INTERVAL);
    }

    /**
     * Test for {@link ShortcutService#updateTimes()}
     */
    public void testUpdateAndGetNextResetTimeLocked() {
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock.
        mInjectedCurrentTimeLillis += 100;

        // Shouldn't have changed.
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock, almost the reset time.
        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;

        // Shouldn't have changed.
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock.
        mInjectedCurrentTimeLillis += 1;

        assertResetTimes(START_TIME + INTERVAL, START_TIME + 2 * INTERVAL);

        // Advance further; 4 days since start.
        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;

        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);
    }

    /**
     * Test for the restoration from saved file.
     */
    public void testInitializeFromSavedFile() {

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;
        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);

        mService.saveBaseStateLocked();

        dumpBaseStateFile();

        // Restore.
        initService();

        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);
    }

    /**
     * Test for the restoration from restored file.
     */
    public void testLoadFromBrokenFile() {
        // TODO Add various broken cases.
    }

    // === Test for app side APIs ===

    /** Test for {@link android.content.pm.ShortcutManager#getMaxDynamicShortcutCount()} */
    public void testGetMaxDynamicShortcutCount() {
        assertEquals(MAX_SHORTCUTS, mManager.getMaxDynamicShortcutCount());
    }

    /** Test for {@link android.content.pm.ShortcutManager#getRemainingCallCount()} */
    public void testGetRemainingCallCount() {
        assertEquals(MAX_DAILY_UPDATES, mManager.getRemainingCallCount());
    }

    /** Test for {@link android.content.pm.ShortcutManager#getRateLimitResetTime()} */
    public void testGetRateLimitResetTime() {
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;

        assertEquals(START_TIME + 5 * INTERVAL, mManager.getRateLimitResetTime());
    }

    public void testSetDynamicShortcuts() {
        final Icon icon1 = Icon.createWithResource(mContext, R.drawable.icon1);
        final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                mContext.getResources(), R.drawable.icon2));

        final ShortcutInfo si1 = makeShortcut(
                "shortcut1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                icon1,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo si2 = makeShortcut(
                "shortcut2",
                "Title 2",
                /* activity */ null,
                icon2,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1, si2)));
        assertEquals(2, mManager.getDynamicShortcuts().size());
        assertEquals(2, mManager.getRemainingCallCount());

        // TODO: Check fields

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(1, mManager.getDynamicShortcuts().size());
        assertEquals(1, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList()));
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getRemainingCallCount());

        dumpsysOnLogcat();

        mInjectedCurrentTimeLillis++; // Need to advance the clock for reset to work.
        mService.resetThrottlingInner();

        dumpsysOnLogcat();

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si2, si3)));
        assertEquals(2, mManager.getDynamicShortcuts().size());

        // TODO Check max number
    }

    public void testAddDynamicShortcuts() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(1, mManager.getDynamicShortcuts().size());

        assertTrue(mManager.addDynamicShortcut(si2));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(2, mManager.getDynamicShortcuts().size());

        // Add with the same ID
        assertTrue(mManager.addDynamicShortcut(makeShortcut("shortcut1")));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(2, mManager.getDynamicShortcuts().size()); // Still 2

        // TODO Check max number

        // TODO Check fields.
    }

    public void testDeleteDynamicShortcut() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1, si2, si3)));
        assertEquals(3, mManager.getDynamicShortcuts().size());

        assertEquals(2, mManager.getRemainingCallCount());

        mManager.deleteDynamicShortcut("shortcut1");
        assertEquals(2, mManager.getDynamicShortcuts().size());

        mManager.deleteDynamicShortcut("shortcut1");
        assertEquals(2, mManager.getDynamicShortcuts().size());

        mManager.deleteDynamicShortcut("shortcutXXX");
        assertEquals(2, mManager.getDynamicShortcuts().size());

        mManager.deleteDynamicShortcut("shortcut2");
        assertEquals(1, mManager.getDynamicShortcuts().size());

        mManager.deleteDynamicShortcut("shortcut3");
        assertEquals(0, mManager.getDynamicShortcuts().size());

        // Still 2 calls left.
        assertEquals(2, mManager.getRemainingCallCount());

        // TODO Make sure pinned shortcuts won't be deleted.
    }

    public void testDeleteAllDynamicShortcuts() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1, si2, si3)));
        assertEquals(3, mManager.getDynamicShortcuts().size());

        assertEquals(2, mManager.getRemainingCallCount());

        mManager.deleteAllDynamicShortcuts();
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(2, mManager.getRemainingCallCount());

        // Note delete shouldn't affect throttling, so...
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getDynamicShortcuts().size());

        // This should still work.
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1, si2, si3)));
        assertEquals(3, mManager.getDynamicShortcuts().size());

        // Still 1 call left
        assertEquals(1, mManager.getRemainingCallCount());

        // TODO Make sure pinned shortcuts won't be deleted.
    }

    public void testThrottling() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(1, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Reached the max

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Still throttled
        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;
        assertFalse(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Now it should work.
        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(1, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // 4 days later...
        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        // Make sure getRemainingCallCount() itself gets reset withou calling setDynamicShortcuts().
        mInjectedCurrentTimeLillis = START_TIME + 8 * INTERVAL;
        assertEquals(3, mManager.getRemainingCallCount());
    }

    public void testThrottling_perPackage() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(1, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Reached the max

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(Arrays.asList(si1)));

        // Try from a different caller.
        mInjectedClientPackage = CALLING_PACKAGE_2;
        mInjectedCallingUid = CALLING_UID_2;

        // Need to create a new one wit the updated package name.
        final ShortcutInfo si2 = makeShortcut("shortcut1");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si2)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si2)));
        assertEquals(1, mManager.getRemainingCallCount());

        // Back to the original caller, still throttled.
        mInjectedClientPackage = CALLING_PACKAGE_1;
        mInjectedCallingUid = CALLING_UID_1;

        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;
        assertEquals(0, mManager.getRemainingCallCount());
        assertFalse(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Now it should work.
        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(Arrays.asList(si1)));

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL;
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1)));
        assertFalse(mManager.setDynamicShortcuts(Arrays.asList(si1)));

        mInjectedClientPackage = CALLING_PACKAGE_2;
        mInjectedCallingUid = CALLING_UID_2;

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si2)));
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si2)));
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si2)));
        assertFalse(mManager.setDynamicShortcuts(Arrays.asList(si2)));
    }

    // TODO: updateShortcuts()
    // TODO: getPinnedShortcuts()

    // === Test for launcher side APIs ===

    public void testGetShortcuts() {

        // Set up shortcuts.

        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 5000);
        final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 1000);

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
        final ShortcutInfo s2_3 = makeShortcutWithTimestamp("s3", 3000);
        final ShortcutInfo s2_4 = makeShortcutWithTimestamp("s4", 500);
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s2_2, s2_3, s2_4)));

        setCaller(CALLING_PACKAGE_3);
        final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s3", 5000);
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s3_2)));

        setCaller(LAUNCHER_1);

        // Get dynamic
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                mInternal.getShortcuts(getCallingPackage(), /* time =*/ 0, CALLING_PACKAGE_1,
                        /* activity =*/ null,
                    ShortcutQuery.FLAG_GET_DYNAMIC, getCallingUserId()),
                "s1", "s2"))));

        // Get pinned
        assertShortcutIds(
                mInternal.getShortcuts(getCallingPackage(),  /* time =*/ 0, CALLING_PACKAGE_1,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED, getCallingUserId())
                /* none */);

        // Get both, with timestamp
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                mInternal.getShortcuts(getCallingPackage(),  /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED | ShortcutQuery.FLAG_GET_DYNAMIC,
                        getCallingUserId()),
                "s2", "s3"))));

        // FLAG_GET_KEY_FIELDS_ONLY
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                mInternal.getShortcuts(getCallingPackage(), /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY,
                        getCallingUserId()),
                "s2", "s3"))));

        // Pin some shortcuts.
        mInternal.pinShortcuts(getCallingPackage(), CALLING_PACKAGE_2,
                Arrays.asList("s3", "s4"), getCallingUserId());

        // Pinned ones only
        assertAllPinned(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                mInternal.getShortcuts(getCallingPackage(), /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED,
                        getCallingUserId()),
                "s3"))));

        // All packages.
        assertShortcutIds(
                mInternal.getShortcuts(getCallingPackage(),
                        /* time =*/ 5000, /* package= */ null,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED,
                        getCallingUserId()),
                "s1", "s3");

        // TODO More tests: pinned but dynamic, filter by activity
    }

    public void testGetShortcutInfo() {
        // Create shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcut(
                "s1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo s1_2 = makeShortcut(
                "s2",
                "Title 2",
                /* activity */ null,
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s1_1, s1_2)));
        dumpsysOnLogcat();

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_1 = makeShortcut(
                "s1",
                "ABC",
                makeComponent(ShortcutActivity2.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ANSWER, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s2_1)));
        dumpsysOnLogcat();

        // Pin some.
        setCaller(LAUNCHER_1);

        mInternal.pinShortcuts(getCallingPackage(), CALLING_PACKAGE_1,
                Arrays.asList("s2"), getCallingUserId());

        dumpsysOnLogcat();

        // Delete some.
        setCaller(CALLING_PACKAGE_1);
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
        mManager.deleteDynamicShortcut("s2");
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");

        dumpsysOnLogcat();

        setCaller(LAUNCHER_1);
        List<ShortcutInfo> list;

        // Note we don't guarantee the orders.
        list = assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mInternal.getShortcutInfo(getCallingPackage(), CALLING_PACKAGE_1,
                Arrays.asList("s2", "s1", "s3", null), getCallingUserId()))),
                "s1", "s2");
        assertEquals("Title 1", findById(list, "s1").getTitle());
        assertEquals("Title 2", findById(list, "s2").getTitle());

        assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mInternal.getShortcutInfo(getCallingPackage(), CALLING_PACKAGE_1,
                        Arrays.asList("s3"), getCallingUserId())))
                /* none */);

        list = assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mInternal.getShortcutInfo(getCallingPackage(), CALLING_PACKAGE_2,
                        Arrays.asList("s1", "s2", "s3"), getCallingUserId()))),
                "s1");
        assertEquals("ABC", findById(list, "s1").getTitle());
    }

    public void testPinShortcutAndGetPinnedShortcuts() {
        // Create some shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 1000);
        final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 2000);

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
        final ShortcutInfo s2_3 = makeShortcutWithTimestamp("s3", 3000);
        final ShortcutInfo s2_4 = makeShortcutWithTimestamp("s4", 500);
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s2_2, s2_3, s2_4)));

        setCaller(CALLING_PACKAGE_3);
        final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s2", 1000);
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s2_2)));

        // Pin some.
        setCaller(LAUNCHER_1);

        mInternal.pinShortcuts(getCallingPackage(), CALLING_PACKAGE_1,
                Arrays.asList("s2", "s3"), getCallingUserId());

        mInternal.pinShortcuts(getCallingPackage(), CALLING_PACKAGE_2,
                Arrays.asList("s3", "s4", "s5"), getCallingUserId());

        mInternal.pinShortcuts(getCallingPackage(), CALLING_PACKAGE_3,
                Arrays.asList("s3"), getCallingUserId());  // Note ID doesn't exist

        // Delete some.
        setCaller(CALLING_PACKAGE_1);
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
        mManager.deleteDynamicShortcut("s2");
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");

        setCaller(CALLING_PACKAGE_2);
        assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");
        mManager.deleteDynamicShortcut("s3");
        assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");

        setCaller(CALLING_PACKAGE_3);
        assertShortcutIds(mManager.getPinnedShortcuts() /* none */);
        mManager.deleteDynamicShortcut("s2");
        assertShortcutIds(mManager.getPinnedShortcuts() /* none */);

        // Get pinned shortcuts from launcher
        setCaller(LAUNCHER_1);

        // CALLING_PACKAGE_1 deleted s2, but it's pinned, so it still exists.
        assertShortcutIds(assertAllPinned(
                mInternal.getShortcuts(getCallingPackage(), /* time =*/ 0, CALLING_PACKAGE_1,
                /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED, getCallingUserId())),
                "s2");

        assertShortcutIds(assertAllPinned(
                mInternal.getShortcuts(getCallingPackage(), /* time =*/ 0, CALLING_PACKAGE_2,
                /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED, getCallingUserId())),
                "s3", "s4");

        assertShortcutIds(assertAllPinned(
                mInternal.getShortcuts(getCallingPackage(), /* time =*/ 0, CALLING_PACKAGE_3,
                /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED, getCallingUserId()))
                /* none */);
    }

    public void testCreateShortcutIntent() {
        // Create some shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcut(
                "s1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo s1_2 = makeShortcut(
                "s2",
                "Title 2",
                /* activity */ null,
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_1 = makeShortcut(
                "s1",
                "ABC",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ANSWER, ShortcutActivity.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(s2_1)));

        // Pin all.
        setCaller(LAUNCHER_1);
        mInternal.pinShortcuts(getCallingPackage(), CALLING_PACKAGE_1,
                Arrays.asList("s1", "s2"), getCallingUserId());

        mInternal.pinShortcuts(getCallingPackage(), CALLING_PACKAGE_2,
                Arrays.asList("s1"), getCallingUserId());

        // Just to make it complicated, delete some.
        setCaller(CALLING_PACKAGE_1);
        mManager.deleteDynamicShortcut("s2");

        // intent and check.
        setCaller(LAUNCHER_1);
        Intent intent;
        intent = mInternal.createShortcutIntent(getCallingPackage(),
                s1_1.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO), getCallingUserId());
        assertEquals(ShortcutActivity2.class.getName(), intent.getComponent().getClassName());

        intent = mInternal.createShortcutIntent(getCallingPackage(),
                s1_2.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO), getCallingUserId());
        assertEquals(ShortcutActivity3.class.getName(), intent.getComponent().getClassName());

        intent = mInternal.createShortcutIntent(getCallingPackage(),
                s2_1.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO), getCallingUserId());
        assertEquals(ShortcutActivity.class.getName(), intent.getComponent().getClassName());

        // TODO Check extra, etc
    }

    // === Test for persisting ===

    public void testSaveAndLoadUser_empty() {
        assertTrue(mManager.setDynamicShortcuts(Arrays.asList()));

        Log.i(TAG, "Saved state");
        dumpsysOnLogcat();
        dumpUserFile(0);

        // Restore.
        initService();

        assertEquals(0, mManager.getDynamicShortcuts().size());
    }

    /**
     * Try save and load, also stop/start the user.
     */
    public void testSaveAndLoadUser() {
        // First, create some shortcuts and save.
        final Icon icon1 = Icon.createWithResource(mContext, R.drawable.icon1);
        final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                mContext.getResources(), R.drawable.icon2));

        final ShortcutInfo si1 = makeShortcut(
                "shortcut1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                icon1,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo si2 = makeShortcut(
                "shortcut2",
                "Title 2",
                /* activity */ null,
                icon2,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(Arrays.asList(si1, si2)));

        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
        assertEquals(2, mManager.getRemainingCallCount());

        Log.i(TAG, "Saved state");
        dumpsysOnLogcat();
        dumpUserFile(0);

        // Restore.
        initService();

        // Before the load, the map should be empty.
        assertEquals(0, mService.getShortcutsForTest().size());

        // this will pre-load the per-user info.
        mService.onStartUserLocked(UserHandle.USER_SYSTEM);

        // Now it's loaded.
        assertEquals(1, mService.getShortcutsForTest().size());

        // Start another user
        mService.onStartUserLocked(10);

        // Now the size is 2.
        assertEquals(2, mService.getShortcutsForTest().size());

        Log.i(TAG, "Dumping the new instance");

        List<ShortcutInfo> loaded = mManager.getDynamicShortcuts();

        Log.i(TAG, "Loaded state");
        dumpsysOnLogcat();

        assertEquals(2, loaded.size());

        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
        assertEquals(2, mManager.getRemainingCallCount());

        // Try stopping the user
        mService.onCleanupUserInner(UserHandle.USER_SYSTEM);

        // Now it's unloaded.
        assertEquals(1, mService.getShortcutsForTest().size());

        // TODO Check all other fields
    }
}
