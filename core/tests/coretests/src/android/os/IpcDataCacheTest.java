/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.os;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test for verifying the behavior of {@link IpcDataCache}.  This test does
 * not use any actual binder calls - it is entirely self-contained.  This test also relies
 * on the test mode of {@link IpcDataCache} because Android SELinux rules do
 * not grant test processes the permission to set system properties.
 * <p>
 * Build/Install/Run:
 *  atest FrameworksCoreTests:IpcDataCacheTest
 */
@SmallTest
@IgnoreUnderRavenwood(blockedBy = IpcDataCache.class)
public class IpcDataCacheTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    // Configuration for creating caches
    private static final String MODULE = IpcDataCache.MODULE_TEST;
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

        // A single query but this can throw an exception.
        boolean query(int x, boolean y) throws RemoteException {
            if (y) {
                throw new RemoteException();
            }
            return query(x);
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
            extends IpcDataCache.QueryHandler<Integer, Boolean> {
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

    // Clear the test mode after every test, in case this process is used for other
    // tests. This also resets the test property map.
    @After
    public void tearDown() throws Exception {
        IpcDataCache.setTestMode(false);
    }

    // This test is disabled pending an sepolicy change that allows any app to set the
    // test property.
    @Test
    public void testBasicCache() {

        // A stand-in for the binder.  The test verifies that calls are passed through to
        // this class properly.
        ServerProxy tester = new ServerProxy();

        // Create a cache that uses simple arithmetic to computer its values.
        IpcDataCache<Integer, Boolean> testCache =
                new IpcDataCache<>(4, MODULE, API, "testCache1",
                        new ServerQuery(tester));

        IpcDataCache.setTestMode(true);
        testCache.testPropertyName();

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

    // This test is disabled pending an sepolicy change that allows any app to set the
    // test property.
    @Test
    public void testRemoteCall() {

        // A stand-in for the binder.  The test verifies that calls are passed through to
        // this class properly.
        ServerProxy tester = new ServerProxy();

        // Create a cache that uses simple arithmetic to computer its values.
        IpcDataCache.Config config = new IpcDataCache.Config(4, MODULE, API, "testCache2");
        IpcDataCache<Integer, Boolean> testCache =
                new IpcDataCache<>(config, (x) -> tester.query(x, x % 10 == 9));

        IpcDataCache.setTestMode(true);
        testCache.testPropertyName();

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

        try {
            testCache.query(9);
            assertEquals(false, true);          // The code should not reach this point.
        } catch (RuntimeException e) {
            assertEquals(e.getCause() instanceof RemoteException, true);
        }
        tester.verify(4);
    }

    @Test
    public void testDisableCache() {

        // A stand-in for the binder.  The test verifies that calls are passed through to
        // this class properly.
        ServerProxy tester = new ServerProxy();

        // Three caches, all using the same system property but one uses a different name.
        IpcDataCache<Integer, Boolean> cache1 =
                new IpcDataCache<>(4, MODULE, API, "cacheA",
                        new ServerQuery(tester));
        IpcDataCache<Integer, Boolean> cache2 =
                new IpcDataCache<>(4, MODULE, API, "cacheA",
                        new ServerQuery(tester));
        IpcDataCache<Integer, Boolean> cache3 =
                new IpcDataCache<>(4, MODULE, API, "cacheB",
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
        cache1.disableForCurrentProcess();
        assertEquals(true, cache1.getDisabledState());
        assertEquals(true, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Create a new cache1.  Verify that the new instance is disabled.
        cache1 = new IpcDataCache<>(4, MODULE, API, "cacheA",
                new ServerQuery(tester));
        assertEquals(true, cache1.getDisabledState());

        // Remove the record of caches being locally disabled.  This is a clean-up step.
        cache1.forgetDisableLocal();
        assertEquals(true, cache1.getDisabledState());
        assertEquals(true, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Create a new cache1.  Verify that the new instance is not disabled.
        cache1 = new IpcDataCache<>(4, MODULE, API, "cacheA",
                new ServerQuery(tester));
        assertEquals(false, cache1.getDisabledState());
    }

    private static class TestQuery
            extends IpcDataCache.QueryHandler<Integer, String> {

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

    private static class TestCache extends IpcDataCache<Integer, String> {
        private final TestQuery mQuery;

        TestCache() {
            this(MODULE, API);
        }

        TestCache(String module, String api) {
            this(module, api, new TestQuery());
        }

        TestCache(String module, String api, TestQuery query) {
            super(4, module, api, "testCache7", query);
            mQuery = query;
            setTestMode(true);
            testPropertyName();
        }

        TestCache(IpcDataCache.Config c) {
            this(c, new TestQuery());
        }

        TestCache(IpcDataCache.Config c, TestQuery query) {
            super(c, query);
            mQuery = query;
            setTestMode(true);
            testPropertyName();
        }

        int getRecomputeCount() {
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
        IpcDataCache.invalidateCache(MODULE, API);
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
    public void testLocalProcessDisable() {
        TestCache cache = new TestCache();
        assertEquals(cache.isDisabled(), false);
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals(cache.isDisabled(), false);
        cache.disableForCurrentProcess();
        assertEquals(cache.isDisabled(), true);
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
    }

    @Test
    public void testConfig() {
        IpcDataCache.Config a = new IpcDataCache.Config(8, MODULE, "apiA");
        TestCache ac = new TestCache(a);
        assertEquals(8, a.maxEntries());
        assertEquals(MODULE, a.module());
        assertEquals("apiA", a.api());
        assertEquals("apiA", a.name());
        IpcDataCache.Config b = new IpcDataCache.Config(a, "apiB");
        TestCache bc = new TestCache(b);
        assertEquals(8, b.maxEntries());
        assertEquals(MODULE, b.module());
        assertEquals("apiB", b.api());
        assertEquals("apiB", b.name());
        IpcDataCache.Config c = new IpcDataCache.Config(a, "apiC", "nameC");
        TestCache cc = new TestCache(c);
        assertEquals(8, c.maxEntries());
        assertEquals(MODULE, c.module());
        assertEquals("apiC", c.api());
        assertEquals("nameC", c.name());
        IpcDataCache.Config d = a.child("nameD");
        TestCache dc = new TestCache(d);
        assertEquals(8, d.maxEntries());
        assertEquals(MODULE, d.module());
        assertEquals("apiA", d.api());
        assertEquals("nameD", d.name());

        a.disableForCurrentProcess();
        assertEquals(ac.isDisabled(), true);
        assertEquals(bc.isDisabled(), false);
        assertEquals(cc.isDisabled(), false);
        assertEquals(dc.isDisabled(), false);

        a.disableAllForCurrentProcess();
        assertEquals(ac.isDisabled(), true);
        assertEquals(bc.isDisabled(), false);
        assertEquals(cc.isDisabled(), false);
        assertEquals(dc.isDisabled(), true);

        IpcDataCache.Config e = a.child("nameE");
        TestCache ec = new TestCache(e);
        assertEquals(ec.isDisabled(), true);
    }
}
