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

import static android.app.PropertyInvalidatedCache.NONCE_UNSET;
import static android.app.PropertyInvalidatedCache.NonceStore.INVALID_NONCE_INDEX;
import static com.android.internal.os.Flags.FLAG_APPLICATION_SHARED_MEMORY_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.internal.os.ApplicationSharedMemory;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

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
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    // Configuration for creating caches
    private static final String MODULE = PropertyInvalidatedCache.MODULE_TEST;
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
            return "foo" + qv.toString();
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
        n1 = PropertyInvalidatedCache.createPropertyName(
            PropertyInvalidatedCache.MODULE_SYSTEM, "getPackageInfo");
        assertEquals(n1, "cache_key.system_server.get_package_info");
        n1 = PropertyInvalidatedCache.createPropertyName(
            PropertyInvalidatedCache.MODULE_SYSTEM, "get_package_info");
        assertEquals(n1, "cache_key.system_server.get_package_info");
        n1 = PropertyInvalidatedCache.createPropertyName(
            PropertyInvalidatedCache.MODULE_BLUETOOTH, "getState");
        assertEquals(n1, "cache_key.bluetooth.get_state");
    }

    // Verify that invalidating the cache from an app process would fail due to lack of permissions.
    @Test
    @android.platform.test.annotations.DisabledOnRavenwood(
            reason = "SystemProperties doesn't have permission check")
    public void testPermissionFailure() {
        // Create a cache that will write a system nonce.
        TestCache sysCache = new TestCache(PropertyInvalidatedCache.MODULE_SYSTEM, "mode1");
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
        TestCache sysCache = new TestCache(PropertyInvalidatedCache.MODULE_SYSTEM, "mode1");

        sysCache.testPropertyName();
        // Invalidate the cache.  This must succeed because the property has been marked for
        // testing.
        sysCache.invalidateCache();

        // Create a cache that uses MODULE_TEST.  Invalidation succeeds whether or not the
        // property is tagged as being tested.
        TestCache testCache = new TestCache(PropertyInvalidatedCache.MODULE_TEST, "mode2");
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
        TestCache cache2 = new TestCache(PropertyInvalidatedCache.MODULE_SYSTEM, "mode3");
        try {
            cache2.testPropertyName();
            fail("expected an IllegalStateException");
        } catch (IllegalStateException e) {
            // The expected exception.
        }

        // Re-enable test mode (so that the cleanup for the test does not throw).
        PropertyInvalidatedCache.setTestMode(true);
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
        PropertyInvalidatedCache.NonceStore server =
                new PropertyInvalidatedCache.NonceStore(shmem.getSystemNonceBlock(), true);
        PropertyInvalidatedCache.NonceStore client =
                new PropertyInvalidatedCache.NonceStore(shmem.getSystemNonceBlock(), false);

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
}
