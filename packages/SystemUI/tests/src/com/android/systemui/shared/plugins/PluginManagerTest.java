/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.shared.plugins;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginEnablerImpl;
import com.android.systemui.plugins.PluginInitializerImpl;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.annotations.ProvidesInterface;
import com.android.systemui.shared.plugins.PluginInstanceManager.PluginInfo;
import com.android.systemui.shared.plugins.PluginManagerImpl.PluginInstanceManagerFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.Thread.UncaughtExceptionHandler;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class PluginManagerTest extends SysuiTestCase {

    private static final String WHITELISTED_PACKAGE = "com.android.systemui";

    private PluginInstanceManagerFactory mMockFactory;
    private PluginInstanceManager mMockPluginInstance;
    private PluginManagerImpl mPluginManager;
    private PluginListener mMockListener;
    private PackageManager mMockPackageManager;

    private UncaughtExceptionHandler mRealExceptionHandler;
    private UncaughtExceptionHandler mMockExceptionHandler;
    private UncaughtExceptionHandler mPluginExceptionHandler;

    @Before
    public void setup() throws Exception {
        mDependency.injectTestDependency(Dependency.BG_LOOPER,
                TestableLooper.get(this).getLooper());
        mRealExceptionHandler = Thread.getUncaughtExceptionPreHandler();
        mMockExceptionHandler = mock(UncaughtExceptionHandler.class);
        mMockFactory = mock(PluginInstanceManagerFactory.class);
        mMockPluginInstance = mock(PluginInstanceManager.class);
        when(mMockFactory.createPluginInstanceManager(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyBoolean(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(mMockPluginInstance);

        mMockPackageManager = mock(PackageManager.class);
        mPluginManager = new PluginManagerImpl(
                getContext(), mMockFactory, true,
                mMockExceptionHandler, new PluginInitializerImpl() {
                    @Override
                    public String[] getWhitelistedPlugins(Context context) {
                        return new String[0];
                    }

                    @Override
                    public PluginEnabler getPluginEnabler(Context context) {
                        return new PluginEnablerImpl(context, mMockPackageManager);
                    }
        });
        resetExceptionHandler();
        mMockListener = mock(PluginListener.class);
    }

    @RunWithLooper(setAsMainLooper = true)
    @Test
    public void testOneShot() {
        Plugin mockPlugin = mock(Plugin.class);
        when(mMockPluginInstance.getPlugin()).thenReturn(new PluginInfo(null, null, mockPlugin,
                null, null));
        Plugin result = mPluginManager.getOneShotPlugin("myAction", TestPlugin.class);
        assertSame(mockPlugin, result);
    }

    @Test
    public void testAddListener() {
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);

        verify(mMockPluginInstance).loadAll();
    }

    @Test
    public void testRemoveListener() {
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);

        mPluginManager.removePluginListener(mMockListener);
        verify(mMockPluginInstance).destroy();
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    public void testNonDebuggable_noWhitelist() {
        mPluginManager = new PluginManagerImpl(getContext(), mMockFactory, false,
                mMockExceptionHandler, new PluginInitializerImpl() {
            @Override
            public String[] getWhitelistedPlugins(Context context) {
                return new String[0];
            }
        });
        resetExceptionHandler();

        String sourceDir = "myPlugin";
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.sourceDir = sourceDir;
        applicationInfo.packageName = WHITELISTED_PACKAGE;
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        assertNull(mPluginManager.getOneShotPlugin(sourceDir, TestPlugin.class));
        assertNull(mPluginManager.getClassLoader(applicationInfo));
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    public void testNonDebuggable_whitelistedPkg() {
        mPluginManager = new PluginManagerImpl(getContext(), mMockFactory, false,
                mMockExceptionHandler, new PluginInitializerImpl() {
            @Override
            public String[] getWhitelistedPlugins(Context context) {
                return new String[] {WHITELISTED_PACKAGE};
            }
        });
        resetExceptionHandler();

        String sourceDir = "myPlugin";
        ApplicationInfo whiteListedApplicationInfo = new ApplicationInfo();
        whiteListedApplicationInfo.sourceDir = sourceDir;
        whiteListedApplicationInfo.packageName = WHITELISTED_PACKAGE;
        ApplicationInfo invalidApplicationInfo = new ApplicationInfo();
        invalidApplicationInfo.sourceDir = sourceDir;
        invalidApplicationInfo.packageName = "com.android.invalidpackage";
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        assertNotNull(mPluginManager.getClassLoader(whiteListedApplicationInfo));
        assertNull(mPluginManager.getClassLoader(invalidApplicationInfo));
    }

    @Test
    public void testExceptionHandler_foundPlugin() {
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        when(mMockPluginInstance.checkAndDisable(Mockito.any())).thenReturn(true);

        mPluginExceptionHandler.uncaughtException(Thread.currentThread(), new Throwable());

        verify(mMockPluginInstance, Mockito.atLeastOnce()).checkAndDisable(
                ArgumentCaptor.forClass(String.class).capture());
        verify(mMockPluginInstance, Mockito.never()).disableAll();
        verify(mMockExceptionHandler).uncaughtException(
                ArgumentCaptor.forClass(Thread.class).capture(),
                ArgumentCaptor.forClass(Throwable.class).capture());
    }

    @Test
    public void testExceptionHandler_noFoundPlugin() {
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        when(mMockPluginInstance.checkAndDisable(Mockito.any())).thenReturn(false);

        mPluginExceptionHandler.uncaughtException(Thread.currentThread(), new Throwable());

        verify(mMockPluginInstance, Mockito.atLeastOnce()).checkAndDisable(
                ArgumentCaptor.forClass(String.class).capture());
        verify(mMockPluginInstance).disableAll();
        verify(mMockExceptionHandler).uncaughtException(
                ArgumentCaptor.forClass(Thread.class).capture(),
                ArgumentCaptor.forClass(Throwable.class).capture());
    }

    @Test
    public void testDisableIntent() {
        NotificationManager nm = mock(NotificationManager.class);
        mContext.addMockSystemService(Context.NOTIFICATION_SERVICE, nm);
        mContext.setMockPackageManager(mMockPackageManager);

        ComponentName testComponent = new ComponentName(getContext().getPackageName(),
                PluginManagerTest.class.getName());
        Intent intent = new Intent(PluginManagerImpl.DISABLE_PLUGIN);
        intent.setData(Uri.parse("package://" + testComponent.flattenToString()));
        mPluginManager.onReceive(mContext, intent);
        verify(nm).cancel(eq(testComponent.getClassName()), eq(SystemMessage.NOTE_PLUGIN));
        verify(mMockPackageManager).setComponentEnabledSetting(eq(testComponent),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                eq(PackageManager.DONT_KILL_APP));
    }

    private void resetExceptionHandler() {
        mPluginExceptionHandler = Thread.getUncaughtExceptionPreHandler();
        // Set back the real exception handler so the test can crash if it wants to.
        Thread.setUncaughtExceptionPreHandler(mRealExceptionHandler);
    }

    @ProvidesInterface(action = TestPlugin.ACTION, version = TestPlugin.VERSION)
    public static interface TestPlugin extends Plugin {
        public static final String ACTION = "testAction";
        public static final int VERSION = 1;
    }
}
