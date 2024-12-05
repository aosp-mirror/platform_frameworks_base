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

package android.app;

import static android.app.Flags.FLAG_PIC_CACHE_NULLS;
import static android.app.Flags.FLAG_PIC_ISOLATE_CACHE_BY_UID;
import static android.app.PropertyInvalidatedCache.NONCE_UNSET;
import static android.app.PropertyInvalidatedCache.MODULE_BLUETOOTH;
import static android.app.PropertyInvalidatedCache.MODULE_SYSTEM;
import static android.app.PropertyInvalidatedCache.MODULE_TEST;
import static android.app.PropertyInvalidatedCache.NonceStore.INVALID_NONCE_INDEX;
import static com.android.internal.os.Flags.FLAG_APPLICATION_SHARED_MEMORY_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.SuppressLint;
import android.app.PropertyInvalidatedCache.Args;
import android.app.PropertyInvalidatedCache.NonceWatcher;
import android.app.PropertyInvalidatedCache.NonceStore;
import android.os.Binder;
import com.android.internal.os.ApplicationSharedMemory;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;

import com.android.internal.os.ApplicationSharedMemory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Test for verifying the behavior of {@link PropertyInvalidatedCache}.  This test does
 * not use any actual binder calls - it is entirely self-contained.  This test also relies
 * on the test mode of {@link PropertyInvalidatedCache} because Android SELinux rules do
 * not grant test processes the permission to set system properties.
 * <p>
 * Build/Install/Run:
 *  atest FrameworksCoreTests:PropertyInvalidatedCacheTests
 */
@SmallTest
public class PropertyInvalidatedCacheTests {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    // Configuration for creating caches
    private static final String MODULE = MODULE_TEST;
    private static final String API = "testApi";

    // This class is a proxy for binder calls.  It contains a counter that increments
    // every time the class is queried.
    private static class ServerProxy {
        // The number of times this class was queried.
        private int mCount = 0;

        // A single query.  The key behavior is that the query count is incremented.
        boolean query(int x) {
            mCount++;
            return value(x);
        }

        // Return the expected value of an input, without incrementing the query count.
        boolean value(int x) {
            return x % 3 == 0;
        }

        // Verify the count.
        void verify(int x) {
            assertEquals(x, mCount);
        }
    }

    // The functions for querying the server.
    private static class ServerQuery
            extends PropertyInvalidatedCache.QueryHandler<Integer, Boolean> {
        private final ServerProxy mServer;

        ServerQuery(ServerProxy server) {
            mServer = server;
        }

        @Override
        public Boolean apply(Integer x) {
            return mServer.query(x);
        }

        @Override
        public boolean shouldBypassCache(Integer x) {
            return x % 13 == 0;
        }
    }

    // Prepare for testing.
    @Before
    public void setUp() throws Exception {
        PropertyInvalidatedCache.setTestMode(true);
    }

    // Ensure all test configurations are cleared.
    @After
    public void tearDown() throws Exception {
        PropertyInvalidatedCache.setTestMode(false);
    }

    // This test is disabled pending an sepolicy change that allows any app to set the
    // test property.
    @Test
    public void testBasicCache() {

        // A stand-in for the binder.  The test verifies that calls are passed through to
        // this class properly.
        ServerProxy tester = new ServerProxy();

        // Create a cache that uses simple arithmetic to computer its values.
        PropertyInvalidatedCache<Integer, Boolean> testCache =
                new PropertyInvalidatedCache<>(4, MODULE, API, "cache1",
                        new ServerQuery(tester));

        tester.verify(0);
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(1);
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(2);
        testCache.invalidateCache();
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(3);
        assertEquals(tester.value(5), testCache.query(5));
        tester.verify(4);
        assertEquals(tester.value(5), testCache.query(5));
        tester.verify(4);
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(4);

        // Invalidate the cache, and verify that the next read on 3 goes to the server.
        testCache.invalidateCache();
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(5);

        // Test bypass.  The query for 13 always bypasses the cache.
        assertEquals(tester.value(12), testCache.query(12));
        assertEquals(tester.value(13), testCache.query(13));
        assertEquals(tester.value(14), testCache.query(14));
        tester.verify(8);
        assertEquals(tester.value(12), testCache.query(12));
        assertEquals(tester.value(13), testCache.query(13));
        assertEquals(tester.value(14), testCache.query(14));
        tester.verify(9);
    }

    @Test
    public void testDisableCache() {

        // A stand-in for the binder.  The test verifies that calls are passed through to
        // this class properly.
        ServerProxy tester = new ServerProxy();

        // Three caches, all using the same system property but one uses a different name.
        PropertyInvalidatedCache<Integer, Boolean> cache1 =
            new PropertyInvalidatedCache<>(4, MODULE, API, "cache1",
                        new ServerQuery(tester));
        PropertyInvalidatedCache<Integer, Boolean> cache2 =
            new PropertyInvalidatedCache<>(4, MODULE, API, "cache1",
                        new ServerQuery(tester));
        PropertyInvalidatedCache<Integer, Boolean> cache3 =
            new PropertyInvalidatedCache<>(4, MODULE, API, "cache3",
                        new ServerQuery(tester));

        // Caches are enabled upon creation.
        assertEquals(false, cache1.getDisabledState());
        assertEquals(false, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Disable the cache1 instance.  Only cache1 is disabled
        cache1.disableInstance();
        assertEquals(true, cache1.getDisabledState());
        assertEquals(false, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Disable cache1.  This will disable cache1 and cache2 because they share the
        // same name.  cache3 has a different name and will not be disabled.
        cache1.disableLocal();
        assertEquals(true, cache1.getDisabledState());
        assertEquals(true, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Create a new cache1.  Verify that the new instance is disabled.
        cache1 = new PropertyInvalidatedCache<>(4, MODULE, API, "cache1",
                new ServerQuery(tester));
        assertEquals(true, cache1.getDisabledState());

        // Remove the record of caches being locally disabled.  This is a clean-up step.
        cache1.forgetDisableLocal();
        assertEquals(true, cache1.getDisabledState());
        assertEquals(true, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Create a new cache1.  Verify that the new instance is not disabled.
        cache1 = new PropertyInvalidatedCache<>(4, MODULE, API, "cache1",
                new ServerQuery(tester));
        assertEquals(false, cache1.getDisabledState());
    }

    private static class TestQuery
            extends PropertyInvalidatedCache.QueryHandler<Integer, String> {

        private int mRecomputeCount = 0;

        @Override
        public String apply(Integer qv) {
            mRecomputeCount += 1;
            // Special case for testing caches of nulls.  Integers in the range 30-40 return null.
            if (qv >= 30 && qv < 40) {
                return null;
            } else {
                return "foo" + qv.toString();
            }
        }

        int getRecomputeCount() {
            return mRecomputeCount;
        }
    }

    private static class TestCache extends PropertyInvalidatedCache<Integer, String> {
        private final TestQuery mQuery;

        TestCache() {
            this(MODULE, API);
        }

        TestCache(String module, String api) {
            this(module, api, new TestQuery());
        }

        TestCache(String module, String api, TestQuery query) {
            super(4, module, api, api, query);
            mQuery = query;
        }

        // Create a cache from the args.  The name of the cache is the api.
        TestCache(Args args, TestQuery query) {
            super(args, args.mApi(), query);
            mQuery = query;
        }

        public int getRecomputeCount() {
            return mQuery.getRecomputeCount();
        }
    }

    @Test
    public void testCacheRecompute() {
        TestCache cache = new TestCache();
        cache.invalidateCache();
        assertEquals(cache.isDisabled(), false);
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals("foo6", cache.query(6));
        assertEquals(2, cache.getRecomputeCount());
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
        // Invalidate the cache with a direct call to the property.
        PropertyInvalidatedCache.invalidateCache(MODULE, API);
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(4, cache.getRecomputeCount());
    }

    @Test
    public void testCacheInitialState() {
        TestCache cache = new TestCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(2, cache.getRecomputeCount());
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
    }

    @Test
    public void testCachePropertyUnset() {
        final String UNSET_API = "otherApi";
        TestCache cache = new TestCache(MODULE, UNSET_API);
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(2, cache.getRecomputeCount());
    }

    @Test
    public void testCacheDisableState() {
        TestCache cache = new TestCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(2, cache.getRecomputeCount());
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
        cache.disableSystemWide();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(5, cache.getRecomputeCount());
        cache.invalidateCache();  // Should not reenable
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(7, cache.getRecomputeCount());
    }

    @Test
    public void testRefreshSameObject() {
        int[] refreshCount = new int[1];
        TestCache cache = new TestCache() {
            @Override
            public String refresh(String oldResult, Integer query) {
                refreshCount[0] += 1;
                return oldResult;
            }
        };
        cache.invalidateCache();
        String result1 = cache.query(5);
        assertEquals("foo5", result1);
        String result2 = cache.query(5);
        assertSame(result1, result2);
        assertEquals(1, cache.getRecomputeCount());
        assertEquals(1, refreshCount[0]);
        assertEquals("foo5", cache.query(5));
        assertEquals(2, refreshCount[0]);
    }

    @Test
    public void testRefreshInvalidateRace() {
        int[] refreshCount = new int[1];
        TestCache cache = new TestCache() {
            @Override
            public String refresh(String oldResult, Integer query) {
                refreshCount[0] += 1;
                invalidateCache();
                return new String(oldResult);
            }
        };
        cache.invalidateCache();
        String result1 = cache.query(5);
        assertEquals("foo5", result1);
        String result2 = cache.query(5);
        assertEquals(result1, result2);
        assertNotSame(result1, result2);
        assertEquals(2, cache.getRecomputeCount());
    }

    @Test
    public void testLocalProcessDisable() {
        TestCache cache = new TestCache();
        assertEquals(cache.isDisabled(), false);
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals(cache.isDisabled(), false);
        cache.disableLocal();
        assertEquals(cache.isDisabled(), true);
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
    }

    @Test
    public void testPropertyNames() {
        String n1;
        n1 = PropertyInvalidatedCache.createPropertyName(MODULE_SYSTEM, "getPackageInfo");
        assertEquals(n1, "cache_key.system_server.get_package_info");
        n1 = PropertyInvalidatedCache.createPropertyName(MODULE_SYSTEM, "get_package_info");
        assertEquals(n1, "cache_key.system_server.get_package_info");
        n1 = PropertyInvalidatedCache.createPropertyName(MODULE_BLUETOOTH, "getState");
        assertEquals(n1, "cache_key.bluetooth.get_state");
    }

    // Verify that invalidating the cache from an app process would fail due to lack of permissions.
    @Test
    @android.platform.test.annotations.DisabledOnRavenwood(
            reason = "SystemProperties doesn't have permission check")
    public void testPermissionFailure() {
        // Create a cache that will write a system nonce.
        TestCache sysCache = new TestCache(MODULE_SYSTEM, "mode1");
        try {
            // Invalidate the cache, which writes the system property.  There must be a permission
            // failure.
            sysCache.invalidateCache();
            fail("expected permission failure");
        } catch (RuntimeException e) {
            // The expected exception is a bare RuntimeException.  The test does not attempt to
            // validate the text of the exception message.
        }
    }

    // Verify that test mode works properly.
    @Test
    public void testTestMode() {
        // Create a cache that will write a system nonce.
        TestCache sysCache = new TestCache(MODULE_SYSTEM, "mode1");

        sysCache.testPropertyName();
        // Invalidate the cache.  This must succeed because the property has been marked for
        // testing.
        sysCache.invalidateCache();

        // Create a cache that uses MODULE_TEST.  Invalidation succeeds whether or not the
        // property is tagged as being tested.
        TestCache testCache = new TestCache(MODULE_TEST, "mode2");
        testCache.invalidateCache();
        testCache.testPropertyName();
        testCache.invalidateCache();

        // Clear test mode.  This fails if test mode is not enabled.
        PropertyInvalidatedCache.setTestMode(false);
        try {
            PropertyInvalidatedCache.setTestMode(false);
            if (Flags.enforcePicTestmodeProtocol()) {
                fail("expected an IllegalStateException");
            }
        } catch (IllegalStateException e) {
            // The expected exception.
        }
        // Configuring a property for testing must fail if test mode is false.
        TestCache cache2 = new TestCache(MODULE_SYSTEM, "mode3");
        try {
            cache2.testPropertyName();
            fail("expected an IllegalStateException");
        } catch (IllegalStateException e) {
            // The expected exception.
        }

        // Re-enable test mode (so that the cleanup for the test does not throw).
        PropertyInvalidatedCache.setTestMode(true);
    }

    // Test the Args-style constructor.
    @Test
    public void testArgsConstructor() {
        // Create a cache with a maximum of four entries and non-isolated UIDs.
        TestCache cache = new TestCache(new Args(MODULE_TEST)
                .maxEntries(4).isolateUids(false).api("init1"),
                new TestQuery());

        cache.invalidateCache();
        for (int i = 1; i <= 4; i++) {
            assertEquals("foo" + i, cache.query(i));
            assertEquals(i, cache.getRecomputeCount());
        }
        // Everything is in the cache.  The recompute count must not increase.
        for (int i = 1; i <= 4; i++) {
            assertEquals("foo" + i, cache.query(i));
            assertEquals(4, cache.getRecomputeCount());
        }
        // Overflow the max entries.  The recompute count increases by one.
        assertEquals("foo5", cache.query(5));
        assertEquals(5, cache.getRecomputeCount());
        // The oldest entry (1) has been evicted.  Iterating through the first four entries will
        // sequentially evict them all because the loop is proceeding oldest to newest.
        for (int i = 1; i <= 4; i++) {
            assertEquals("foo" + i, cache.query(i));
            assertEquals(5+i, cache.getRecomputeCount());
        }
    }

    // Verify that NonceWatcher change reporting works properly
    @Test
    public void testNonceWatcherChanged() {
        // Create a cache that will write a system nonce.
        TestCache sysCache = new TestCache(MODULE_SYSTEM, "watcher1");
        sysCache.testPropertyName();

        try (NonceWatcher watcher1 = sysCache.getNonceWatcher()) {

            // The property has never been invalidated so it is still unset.
            assertFalse(watcher1.isChanged());

            // Invalidate the cache.  The first call to isChanged will return true but the second
            // call will return false;
            sysCache.invalidateCache();
            assertTrue(watcher1.isChanged());
            assertFalse(watcher1.isChanged());

            // Invalidate the cache.  The first call to isChanged will return true but the second
            // call will return false;
            sysCache.invalidateCache();
            sysCache.invalidateCache();
            assertTrue(watcher1.isChanged());
            assertFalse(watcher1.isChanged());

            NonceWatcher watcher2 = sysCache.getNonceWatcher();
            // This watcher return isChanged() immediately because the nonce is not UNSET.
            assertTrue(watcher2.isChanged());
        }
    }

    // Verify that NonceWatcher wait-for-change works properly
    @Test
    public void testNonceWatcherWait() throws Exception {
        // Create a cache that will write a system nonce.
        TestCache sysCache = new TestCache(MODULE_TEST, "watcher1");

        // Use the watcher outside a try-with-resources block.
        NonceWatcher watcher1 = sysCache.getNonceWatcher();

        // Invalidate the cache and then "wait".
        sysCache.invalidateCache();
        assertEquals(watcher1.waitForChange(), 1);

        // Invalidate the cache three times and then "wait".
        sysCache.invalidateCache();
        sysCache.invalidateCache();
        sysCache.invalidateCache();
        assertEquals(watcher1.waitForChange(), 3);

        // Wait for a change.  It won't happen, but the code will time out after 10ms.
        assertEquals(watcher1.waitForChange(10, TimeUnit.MILLISECONDS), 0);

        watcher1.close();
    }

    // Verify the behavior of shared memory nonce storage.  This does not directly test the cache
    // storing nonces in shared memory.
    @RequiresFlagsEnabled(FLAG_APPLICATION_SHARED_MEMORY_ENABLED)
    @Test
    @android.platform.test.annotations.DisabledOnRavenwood(
            reason = "PIC doesn't use SharedMemory on Ravenwood")
    public void testSharedMemoryStorage() {
        // Fetch a shared memory instance for testing.
        ApplicationSharedMemory shmem = ApplicationSharedMemory.create();

        // Create a server-side store and a client-side store.  The server's store is mutable and
        // the client's store is not mutable.
        NonceStore server = new NonceStore(shmem.getSystemNonceBlock(), true);
        NonceStore client = new NonceStore(shmem.getSystemNonceBlock(), false);

        final String name1 = "name1";
        assertEquals(server.getHandleForName(name1), INVALID_NONCE_INDEX);
        assertEquals(client.getHandleForName(name1), INVALID_NONCE_INDEX);
        final int index1 = server.storeName(name1);
        assertNotEquals(index1, INVALID_NONCE_INDEX);
        assertEquals(server.getHandleForName(name1), index1);
        assertEquals(client.getHandleForName(name1), index1);
        assertEquals(server.storeName(name1), index1);

        assertEquals(server.getNonce(index1), NONCE_UNSET);
        assertEquals(client.getNonce(index1), NONCE_UNSET);
        final int value1 = 4;
        server.setNonce(index1, value1);
        assertEquals(server.getNonce(index1), value1);
        assertEquals(client.getNonce(index1), value1);
        final int value2 = 8;
        server.setNonce(index1, value2);
        assertEquals(server.getNonce(index1), value2);
        assertEquals(client.getNonce(index1), value2);

        final String name2 = "name2";
        assertEquals(server.getHandleForName(name2), INVALID_NONCE_INDEX);
        assertEquals(client.getHandleForName(name2), INVALID_NONCE_INDEX);
        final int index2 = server.storeName(name2);
        assertNotEquals(index2, INVALID_NONCE_INDEX);
        assertEquals(server.getHandleForName(name2), index2);
        assertEquals(client.getHandleForName(name2), index2);
        assertEquals(server.storeName(name2), index2);

        // The names are different, so the indices must be different.
        assertNotEquals(index1, index2);

        shmem.close();
    }

    // Verify that an invalid module causes an exception.
    private void testInvalidModule(String module) {
        try {
            @SuppressLint("UnusedVariable")
            Args arg = new Args(module);
            fail("expected an invalid module exception: module=" + module);
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    // Test various instantiation errors.  The good path is tested in other methods.
    @Test
    public void testArgumentErrors() {
        // Verify that an illegal module throws an exception.
        testInvalidModule(MODULE_SYSTEM.substring(0, MODULE_SYSTEM.length() - 1));
        testInvalidModule(MODULE_SYSTEM + "x");
        testInvalidModule("mymodule");

        // Verify that a negative max entries throws.
        Args arg = new Args(MODULE_SYSTEM);
        try {
            arg.maxEntries(0);
            fail("expected an invalid maxEntries exception");
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }

        // Verify that creating a cache with an invalid property string throws.
        try {
            final String badKey = "cache_key.volume_list";
            @SuppressLint("UnusedVariable")
            var cache = new PropertyInvalidatedCache<Integer, Void>(4, badKey);
            fail("expected bad property exception: prop=" + badKey);
        } catch (IllegalArgumentException e) {
            // Expected exception.
        }
    }

    // Verify that a cache created with isolatedUids(true) separates out the results.
    @RequiresFlagsEnabled(FLAG_PIC_ISOLATE_CACHE_BY_UID)
    @Test
    public void testIsolatedUids() {
        TestCache cache = new TestCache(new Args(MODULE_TEST)
                .maxEntries(4).isolateUids(true).api("testIsolatedUids").testMode(true),
                new TestQuery());
        cache.invalidateCache();
        final int uid1 = 1;
        final int uid2 = 2;

        long token = Binder.setCallingWorkSourceUid(uid1);
        try {
            // Populate the cache for user 1
            assertEquals("foo5", cache.query(5));
            assertEquals(1, cache.getRecomputeCount());
            assertEquals("foo5", cache.query(5));
            assertEquals(1, cache.getRecomputeCount());
            assertEquals("foo6", cache.query(6));
            assertEquals(2, cache.getRecomputeCount());

            // Populate the cache for user 2.  User 1 values are not reused.
            Binder.setCallingWorkSourceUid(uid2);
            assertEquals("foo5", cache.query(5));
            assertEquals(3, cache.getRecomputeCount());
            assertEquals("foo5", cache.query(5));
            assertEquals(3, cache.getRecomputeCount());

            // Verify that the cache for user 1 is still populated.
            Binder.setCallingWorkSourceUid(uid1);
            assertEquals("foo5", cache.query(5));
            assertEquals(3, cache.getRecomputeCount());

        } finally {
            Binder.restoreCallingWorkSource(token);
        }

        // Repeat the test with a non-isolated cache.
        cache = new TestCache(new Args(MODULE_TEST)
                .maxEntries(4).isolateUids(false).api("testIsolatedUids2").testMode(true),
                new TestQuery());
        cache.invalidateCache();
        token = Binder.setCallingWorkSourceUid(uid1);
        try {
            // Populate the cache for user 1
            assertEquals("foo5", cache.query(5));
            assertEquals(1, cache.getRecomputeCount());
            assertEquals("foo5", cache.query(5));
            assertEquals(1, cache.getRecomputeCount());
            assertEquals("foo6", cache.query(6));
            assertEquals(2, cache.getRecomputeCount());

            // Populate the cache for user 2.  User 1 values are reused.
            Binder.setCallingWorkSourceUid(uid2);
            assertEquals("foo5", cache.query(5));
            assertEquals(2, cache.getRecomputeCount());
            assertEquals("foo5", cache.query(5));
            assertEquals(2, cache.getRecomputeCount());

            // Verify that the cache for user 1 is still populated.
            Binder.setCallingWorkSourceUid(uid1);
            assertEquals("foo5", cache.query(5));
            assertEquals(2, cache.getRecomputeCount());

        } finally {
            Binder.restoreCallingWorkSource(token);
        }
    }

    @RequiresFlagsEnabled(FLAG_PIC_CACHE_NULLS)
    @Test
    public void testCachingNulls() {
        TestCache cache = new TestCache(new Args(MODULE_TEST)
                .maxEntries(4).api("testCachingNulls").cacheNulls(true),
                new TestQuery());
        cache.invalidateCache();
        assertEquals("foo1", cache.query(1));
        assertEquals("foo2", cache.query(2));
        assertEquals(null, cache.query(30));
        assertEquals(3, cache.getRecomputeCount());
        assertEquals("foo1", cache.query(1));
        assertEquals("foo2", cache.query(2));
        assertEquals(null, cache.query(30));
        assertEquals(3, cache.getRecomputeCount());

        cache = new TestCache(new Args(MODULE_TEST)
                .maxEntries(4).api("testCachingNulls").cacheNulls(false),
                new TestQuery());
        cache.invalidateCache();
        assertEquals("foo1", cache.query(1));
        assertEquals("foo2", cache.query(2));
        assertEquals(null, cache.query(30));
        assertEquals(3, cache.getRecomputeCount());
        assertEquals("foo1", cache.query(1));
        assertEquals("foo2", cache.query(2));
        assertEquals(null, cache.query(30));
        // The recompute is 4 because nulls were not cached.
        assertEquals(4, cache.getRecomputeCount());

        // Verify that the default is not to cache nulls.
        cache = new TestCache(new Args(MODULE_TEST)
                .maxEntries(4).api("testCachingNulls"),
                new TestQuery());
        cache.invalidateCache();
        assertEquals("foo1", cache.query(1));
        assertEquals("foo2", cache.query(2));
        assertEquals(null, cache.query(30));
        assertEquals(3, cache.getRecomputeCount());
        assertEquals("foo1", cache.query(1));
        assertEquals("foo2", cache.query(2));
        assertEquals(null, cache.query(30));
        // The recompute is 4 because nulls were not cached.
        assertEquals(4, cache.getRecomputeCount());
    }
}
