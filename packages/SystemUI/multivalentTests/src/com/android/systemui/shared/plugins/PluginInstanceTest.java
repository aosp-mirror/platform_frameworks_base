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

package com.android.systemui.shared.plugins;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginLifecycleManager;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginWrapper;
import com.android.systemui.plugins.TestPlugin;
import com.android.systemui.plugins.annotations.Requires;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PluginInstanceTest extends SysuiTestCase {

    private static final String PRIVILEGED_PACKAGE = "com.android.systemui.plugins";
    private static final ComponentName TEST_PLUGIN_COMPONENT_NAME =
            new ComponentName(PRIVILEGED_PACKAGE, TestPluginImpl.class.getName());

    private FakeListener mPluginListener;
    private VersionInfo mVersionInfo;
    private boolean mVersionCheckResult = true;
    private PluginInstance.VersionChecker mVersionChecker;

    private RefCounter mCounter;
    private PluginInstance<TestPlugin> mPluginInstance;
    private PluginInstance.Factory mPluginInstanceFactory;
    private ApplicationInfo mAppInfo;

    // Because we're testing memory in this file, we must be careful not to assert the target
    // objects, or capture them via mockito if we expect the garbage collector to later free them.
    // Both JUnit and Mockito will save references and prevent these objects from being cleaned up.
    private WeakReference<TestPluginImpl> mPlugin;
    private WeakReference<Context> mPluginContext;

    @Before
    public void setup() throws Exception {
        mCounter = new RefCounter();
        mAppInfo = mContext.getApplicationInfo();
        mAppInfo.packageName = TEST_PLUGIN_COMPONENT_NAME.getPackageName();
        mPluginListener = new FakeListener();
        mVersionInfo = new VersionInfo();
        mVersionChecker = new PluginInstance.VersionChecker() {
            @Override
            public <T extends Plugin> boolean checkVersion(
                    Class<T> instanceClass,
                    Class<T> pluginClass,
                    Plugin plugin
            ) {
                return mVersionCheckResult;
            }

            @Override
            public <T extends Plugin> VersionInfo getVersionInfo(Class<T> instanceClass) {
                return mVersionInfo;
            }
        };

        mPluginInstanceFactory = new PluginInstance.Factory(
                this.getClass().getClassLoader(),
                new PluginInstance.InstanceFactory<TestPlugin>() {
                    @Override
                    TestPlugin create(Class cls) {
                        TestPluginImpl plugin = new TestPluginImpl(mCounter);
                        mPlugin = new WeakReference<>(plugin);
                        return plugin;
                    }
                },
                mVersionChecker,
                Collections.singletonList(PRIVILEGED_PACKAGE),
                false);

        mPluginInstance = mPluginInstanceFactory.create(
                mContext, mAppInfo, TEST_PLUGIN_COMPONENT_NAME,
                TestPlugin.class, mPluginListener);
        mPluginInstance.setLogFunc((tag, msg) -> Log.d((String) tag, (String) msg));
        mPluginContext = new WeakReference<>(mPluginInstance.getPluginContext());
    }

    @Test
    public void testCorrectVersion_onCreateBuildsPlugin() {
        mVersionCheckResult = true;
        assertFalse(mPluginInstance.hasError());

        mPluginInstance.onCreate();
        assertFalse(mPluginInstance.hasError());
        assertNotNull(mPluginInstance.getPlugin());
    }

    @Test
    public void testIncorrectVersion_destroysPluginInstance() throws Exception {
        ComponentName wrongVersionTestPluginComponentName =
                new ComponentName(PRIVILEGED_PACKAGE, TestPlugin.class.getName());

        mVersionCheckResult = false;
        assertFalse(mPluginInstance.hasError());

        mPluginInstanceFactory.create(
                mContext, mAppInfo, wrongVersionTestPluginComponentName,
                TestPlugin.class, mPluginListener);
        mPluginInstance.onCreate();
        assertTrue(mPluginInstance.hasError());
        assertNull(mPluginInstance.getPlugin());
    }

    @Test
    public void testOnCreate() {
        mPluginInstance.onCreate();
        assertEquals(1, mPluginListener.mAttachedCount);
        assertEquals(1, mPluginListener.mLoadCount);
        assertEquals(mPlugin.get(), unwrap(mPluginInstance.getPlugin()));
        assertInstances(1, 1);
    }

    @Test
    public void testOnDestroy() {
        mPluginInstance.onCreate();
        mPluginInstance.onDestroy();
        assertEquals(1, mPluginListener.mDetachedCount);
        assertEquals(1, mPluginListener.mUnloadCount);
        assertNull(mPluginInstance.getPlugin());
        assertInstances(0, 0); // Destroyed but never created
    }

    @Test
    public void testOnUnloadAfterLoad() {
        mPluginInstance.onCreate();
        mPluginInstance.loadPlugin();
        assertNotNull(mPluginInstance.getPlugin());
        assertInstances(1, 1);

        mPluginInstance.unloadPlugin();
        assertNull(mPluginInstance.getPlugin());
        assertInstances(0, 0);
    }

    @Test
    public void testOnAttach_SkipLoad() {
        mPluginListener.mOnAttach = () -> false;
        mPluginInstance.onCreate();
        assertEquals(1, mPluginListener.mAttachedCount);
        assertEquals(0, mPluginListener.mLoadCount);
        assertNull(mPluginInstance.getPlugin());
        assertInstances(0, 0);
    }

    @Test
    public void testLinkageError_caughtAndPluginDestroyed() {
        mPluginInstance.onCreate();
        assertFalse(mPluginInstance.hasError());

        Object result = mPluginInstance.getPlugin().methodThrowsError();
        assertNotNull(result);  // Wrapper function should return non-null;
        assertTrue(mPluginInstance.hasError());
        assertNull(mPluginInstance.getPlugin());
    }

    @Test
    public void testLoadUnloadSimultaneous_HoldsUnload() throws Throwable {
        final Semaphore loadLock = new Semaphore(1);
        final Semaphore unloadLock = new Semaphore(1);

        mPluginListener.mOnAttach = () -> false;
        mPluginListener.mOnLoad = () -> {
            assertNotNull(mPluginInstance.getPlugin());

            // Allow the bg thread the opportunity to delete the plugin
            loadLock.release();
            Thread.yield();
            boolean isLocked = getLock(unloadLock, 1000);

            // Ensure the bg thread failed to delete the plugin
            assertNotNull(mPluginInstance.getPlugin());
            // We expect that bgThread deadlocked holding the semaphore
            assertFalse(isLocked);
        };

        AtomicReference<Throwable> bgFailure = new AtomicReference<Throwable>(null);
        Thread bgThread = new Thread(() -> {
            assertTrue(getLock(unloadLock, 10));
            assertTrue(getLock(loadLock, 10000)); // Wait for the foreground thread
            assertNotNull(mPluginInstance.getPlugin());
            // Attempt to delete the plugin, this should block until the load completes
            mPluginInstance.unloadPlugin();
            assertNull(mPluginInstance.getPlugin());
            unloadLock.release();
            loadLock.release();
        });

        // This protects the test suite from crashing due to the uncaught exception.
        bgThread.setUncaughtExceptionHandler((Thread t, Throwable ex) -> {
            Log.e("PluginInstanceTest#testLoadUnloadSimultaneous_HoldsUnload",
                    "Exception from BG Thread", ex);
            bgFailure.set(ex);
        });

        loadLock.acquire();
        mPluginInstance.onCreate();

        assertNull(mPluginInstance.getPlugin());
        bgThread.start();
        mPluginInstance.loadPlugin();

        bgThread.join(5000);

        // Rethrow final background exception on test thread
        Throwable bgEx = bgFailure.get();
        if (bgEx != null) {
            throw bgEx;
        }

        assertNull(mPluginInstance.getPlugin());
    }

    private static <T> T unwrap(T plugin) {
        if (plugin instanceof PluginWrapper) {
            return ((PluginWrapper<T>) plugin).getPlugin();
        }
        return plugin;
    }

    private boolean getLock(Semaphore lock, long millis) {
        try {
            return lock.tryAcquire(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Log.e("PluginInstanceTest#getLock",
                    "Interrupted Exception getting lock", ex);
            fail();
            return false;
        }
    }

    private void assertInstances(int allocated, int created) {
        // If there are more than the expected number of allocated instances, then we run the
        // garbage collector to finalize and deallocate any outstanding non-referenced instances.
        // Since the GC doesn't always appear to want to run completely when we ask, we do this up
        // to 10 times before failing the test.
        for (int i = 0; mCounter.getAllocatedInstances() > allocated && i < 10; i++) {
            System.runFinalization();
            System.gc();
        }

        assertEquals(allocated, mCounter.getAllocatedInstances());
        assertEquals(created, mCounter.getCreatedInstances());
    }

    public static class RefCounter {
        public final AtomicInteger mAllocatedInstances = new AtomicInteger();
        public final AtomicInteger mCreatedInstances = new AtomicInteger();

        public int getAllocatedInstances() {
            return mAllocatedInstances.get();
        }

        public int getCreatedInstances() {
            return mCreatedInstances.get();
        }
    }

    @Requires(target = TestPlugin.class, version = TestPlugin.VERSION)
    public static class TestPluginImpl implements TestPlugin {
        public final RefCounter mCounter;
        public TestPluginImpl(RefCounter counter) {
            mCounter = counter;
            mCounter.mAllocatedInstances.getAndIncrement();
        }

        @Override
        public void finalize() {
            mCounter.mAllocatedInstances.getAndDecrement();
        }

        @Override
        public void onCreate(Context sysuiContext, Context pluginContext) {
            mCounter.mCreatedInstances.getAndIncrement();
        }

        @Override
        public void onDestroy() {
            mCounter.mCreatedInstances.getAndDecrement();
        }

        @Override
        public Object methodThrowsError() {
            throw new LinkageError();
        }
    }

    public class FakeListener implements PluginListener<TestPlugin> {
        public Supplier<Boolean> mOnAttach = null;
        public Runnable mOnDetach = null;
        public Runnable mOnLoad = null;
        public Runnable mOnUnload = null;
        public int mAttachedCount = 0;
        public int mDetachedCount = 0;
        public int mLoadCount = 0;
        public int mUnloadCount = 0;

        @Override
        public boolean onPluginAttached(PluginLifecycleManager<TestPlugin> manager) {
            mAttachedCount++;
            assertEquals(PluginInstanceTest.this.mPluginInstance, manager);
            return mOnAttach != null ? mOnAttach.get() : true;
        }

        @Override
        public void onPluginDetached(PluginLifecycleManager<TestPlugin> manager) {
            mDetachedCount++;
            assertEquals(PluginInstanceTest.this.mPluginInstance, manager);
            if (mOnDetach != null) {
                mOnDetach.run();
            }
        }

        @Override
        public void onPluginLoaded(
                TestPlugin plugin,
                Context pluginContext,
                PluginLifecycleManager<TestPlugin> manager
        ) {
            mLoadCount++;
            TestPlugin expectedPlugin = PluginInstanceTest.this.mPlugin.get();
            if (expectedPlugin != null) {
                assertEquals(expectedPlugin, unwrap(plugin));
            }
            Context expectedContext = PluginInstanceTest.this.mPluginContext.get();
            if (expectedContext != null) {
                assertEquals(expectedContext, pluginContext);
            }
            assertEquals(PluginInstanceTest.this.mPluginInstance, manager);
            if (mOnLoad != null) {
                mOnLoad.run();
            }
        }

        @Override
        public void onPluginUnloaded(
                TestPlugin plugin,
                PluginLifecycleManager<TestPlugin> manager
        ) {
            mUnloadCount++;
            TestPlugin expectedPlugin = PluginInstanceTest.this.mPlugin.get();
            if (expectedPlugin != null) {
                assertEquals(expectedPlugin, unwrap(plugin));
            }
            assertEquals(PluginInstanceTest.this.mPluginInstance, manager);
            if (mOnUnload != null) {
                mOnUnload.run();
            }
        }
    }
}
