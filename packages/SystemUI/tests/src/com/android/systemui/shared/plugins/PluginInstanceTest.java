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
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginLifecycleManager;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.annotations.ProvidesInterface;
import com.android.systemui.plugins.annotations.Requires;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PluginInstanceTest extends SysuiTestCase {

    private static final String PRIVILEGED_PACKAGE = "com.android.systemui.plugins";
    private static final ComponentName TEST_PLUGIN_COMPONENT_NAME =
            new ComponentName(PRIVILEGED_PACKAGE, TestPluginImpl.class.getName());

    private FakeListener mPluginListener;
    private VersionInfo mVersionInfo;
    private VersionInfo.InvalidVersionException mVersionException;
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
            public <T extends Plugin> VersionInfo checkVersion(
                    Class<T> instanceClass,
                    Class<T> pluginClass,
                    Plugin plugin
            ) {
                if (mVersionException != null) {
                    throw mVersionException;
                }
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
        mPluginContext = new WeakReference<>(mPluginInstance.getPluginContext());
    }

    @Test
    public void testCorrectVersion() {
        assertNotNull(mPluginInstance);
    }

    @Test(expected = VersionInfo.InvalidVersionException.class)
    public void testIncorrectVersion() throws Exception {
        ComponentName wrongVersionTestPluginComponentName =
                new ComponentName(PRIVILEGED_PACKAGE, TestPlugin.class.getName());

        mVersionException = new VersionInfo.InvalidVersionException("test", true);

        mPluginInstanceFactory.create(
                mContext, mAppInfo, wrongVersionTestPluginComponentName,
                TestPlugin.class, mPluginListener);
        mPluginInstance.onCreate();
    }

    @Test
    public void testOnCreate() {
        mPluginInstance.onCreate();
        assertEquals(1, mPluginListener.mAttachedCount);
        assertEquals(1, mPluginListener.mLoadCount);
        assertEquals(mPlugin.get(), mPluginInstance.getPlugin());
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
        mPluginListener.mAttachReturn = false;
        mPluginInstance.onCreate();
        assertEquals(1, mPluginListener.mAttachedCount);
        assertEquals(0, mPluginListener.mLoadCount);
        assertNull(mPluginInstance.getPlugin());
        assertInstances(0, 0);
    }

    // This target class doesn't matter, it just needs to have a Requires to hit the flow where
    // the mock version info is called.
    @ProvidesInterface(action = TestPlugin.ACTION, version = TestPlugin.VERSION)
    public interface TestPlugin extends Plugin {
        int VERSION = 1;
        String ACTION = "testAction";
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
    }

    public class FakeListener implements PluginListener<TestPlugin> {
        public boolean mAttachReturn = true;
        public int mAttachedCount = 0;
        public int mDetachedCount = 0;
        public int mLoadCount = 0;
        public int mUnloadCount = 0;

        @Override
        public boolean onPluginAttached(PluginLifecycleManager<TestPlugin> manager) {
            mAttachedCount++;
            assertEquals(PluginInstanceTest.this.mPluginInstance, manager);
            return mAttachReturn;
        }

        @Override
        public void onPluginDetached(PluginLifecycleManager<TestPlugin> manager) {
            mDetachedCount++;
            assertEquals(PluginInstanceTest.this.mPluginInstance, manager);
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
                assertEquals(expectedPlugin, plugin);
            }
            Context expectedContext = PluginInstanceTest.this.mPluginContext.get();
            if (expectedContext != null) {
                assertEquals(expectedContext, pluginContext);
            }
            assertEquals(PluginInstanceTest.this.mPluginInstance, manager);
        }

        @Override
        public void onPluginUnloaded(
                TestPlugin plugin,
                PluginLifecycleManager<TestPlugin> manager
        ) {
            mUnloadCount++;
            TestPlugin expectedPlugin = PluginInstanceTest.this.mPlugin.get();
            if (expectedPlugin != null) {
                assertEquals(expectedPlugin, plugin);
            }
            assertEquals(PluginInstanceTest.this.mPluginInstance, manager);
        }
    }
}
