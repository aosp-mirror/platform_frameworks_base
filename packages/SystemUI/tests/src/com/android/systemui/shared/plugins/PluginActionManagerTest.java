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

package com.android.systemui.shared.plugins;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.annotations.Requires;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PluginActionManagerTest extends SysuiTestCase {

    private static final String PRIVILEGED_PACKAGE = "com.android.systemui.shared.plugins";
    private TestPlugin mMockPlugin;

    private PackageManager mMockPm;
    private PluginListener<TestPlugin> mMockListener;
    private PluginActionManager<TestPlugin> mPluginActionManager;
    private VersionInfo mMockVersionInfo;
    private PluginEnabler mMockEnabler;
    ComponentName mTestPluginComponentName =
            new ComponentName(PRIVILEGED_PACKAGE, TestPlugin.class.getName());
    private PluginInitializer mInitializer;
    private final FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    NotificationManager mNotificationManager;
    private PluginInstance<TestPlugin> mPluginInstance;
    private PluginInstance.Factory mPluginInstanceFactory = new PluginInstance.Factory(
            this.getClass().getClassLoader(),
            new PluginInstance.InstanceFactory<>(), new PluginInstance.VersionChecker(),
            Collections.emptyList(), false) {
        @Override
        public <T extends Plugin> PluginInstance<T> create(Context context, ApplicationInfo appInfo,
                ComponentName componentName, Class<T> pluginClass) {
            return (PluginInstance<T>) mPluginInstance;
        }
    };

    private PluginActionManager.Factory mActionManagerFactory;

    @Before
    public void setup() throws Exception {
        mContext = new MyContextWrapper(mContext);
        mMockPm = mock(PackageManager.class);
        mMockListener = mock(PluginListener.class);
        mMockEnabler = mock(PluginEnabler.class);
        mMockVersionInfo = mock(VersionInfo.class);
        mInitializer = mock(PluginInitializer.class);
        mNotificationManager = mock(NotificationManager.class);
        mMockPlugin = mock(TestPlugin.class);
        mPluginInstance = mock(PluginInstance.class);
        when(mPluginInstance.getComponentName()).thenReturn(mTestPluginComponentName);
        when(mPluginInstance.getPackage()).thenReturn(mTestPluginComponentName.getPackageName());
        mActionManagerFactory = new PluginActionManager.Factory(getContext(), mMockPm,
                mFakeExecutor, mFakeExecutor, mInitializer, mNotificationManager, mMockEnabler,
                new ArrayList<>(), mPluginInstanceFactory);

        mPluginActionManager = mActionManagerFactory.create("myAction", mMockListener,
                TestPlugin.class, true, true);
        when(mMockPlugin.getVersion()).thenReturn(1);
    }

    @Test
    public void testNoPlugins() {
        when(mMockPm.queryIntentServices(any(), anyInt())).thenReturn(
                Collections.emptyList());
        mPluginActionManager.loadAll();

        mFakeExecutor.runAllReady();

        verify(mMockListener, never()).onPluginConnected(any(), any());
    }

    @Test
    public void testPluginCreate() throws Exception {
        //Debug.waitForDebugger();
        createPlugin();

        // Verify startup lifecycle
        verify(mPluginInstance).onCreate(mContext, mMockListener);
    }

    @Test
    public void testPluginDestroy() throws Exception {
        createPlugin(); // Get into valid created state.

        mPluginActionManager.destroy();

        mFakeExecutor.runAllReady();

        // Verify shutdown lifecycle
        verify(mPluginInstance).onDestroy(mMockListener);
    }

    @Test
    public void testReloadOnChange() throws Exception {
        createPlugin(); // Get into valid created state.

        mPluginActionManager.reloadPackage(PRIVILEGED_PACKAGE);

        mFakeExecutor.runAllReady();

        // Verify the old one was destroyed.
        verify(mPluginInstance).onDestroy(mMockListener);
        verify(mPluginInstance, Mockito.times(2))
                .onCreate(mContext, mMockListener);
    }

    @Test
    public void testNonDebuggable() throws Exception {
        // Create a version that thinks the build is not debuggable.
        mPluginActionManager = mActionManagerFactory.create("myAction", mMockListener,
                TestPlugin.class, true, false);
        setupFakePmQuery();

        mPluginActionManager.loadAll();

        mFakeExecutor.runAllReady();

        // Non-debuggable build should receive no plugins.
        verify(mMockListener, never()).onPluginConnected(any(), any());
    }

    @Test
    public void testNonDebuggable_privileged() throws Exception {
        // Create a version that thinks the build is not debuggable.
        PluginActionManager.Factory factory = new PluginActionManager.Factory(getContext(),
                mMockPm, mFakeExecutor, mFakeExecutor, mInitializer, mNotificationManager,
                mMockEnabler, Collections.singletonList(PRIVILEGED_PACKAGE),
                mPluginInstanceFactory);
        mPluginActionManager = factory.create("myAction", mMockListener,
                TestPlugin.class, true, false);
        setupFakePmQuery();

        mPluginActionManager.loadAll();

        mFakeExecutor.runAllReady();

        // Verify startup lifecycle
        verify(mPluginInstance).onCreate(mContext, mMockListener);
    }

    @Test
    public void testCheckAndDisable() throws Exception {
        createPlugin(); // Get into valid created state.

        // Start with an unrelated class.
        boolean result = mPluginActionManager.checkAndDisable(Activity.class.getName());
        assertFalse(result);
        verify(mMockEnabler, never()).setDisabled(any(ComponentName.class), anyInt());

        // Now hand it a real class and make sure it disables the plugin.
        result = mPluginActionManager.checkAndDisable(TestPlugin.class.getName());
        assertTrue(result);
        verify(mMockEnabler).setDisabled(
                mTestPluginComponentName, PluginEnabler.DISABLED_FROM_EXPLICIT_CRASH);
    }

    @Test
    public void testDisableAll() throws Exception {
        createPlugin(); // Get into valid created state.

        mPluginActionManager.disableAll();

        verify(mMockEnabler).setDisabled(
                mTestPluginComponentName, PluginEnabler.DISABLED_FROM_SYSTEM_CRASH);
    }

    @Test
    public void testDisablePrivileged() throws Exception {
        PluginActionManager.Factory factory = new PluginActionManager.Factory(getContext(),
                mMockPm, mFakeExecutor, mFakeExecutor, mInitializer, mNotificationManager,
                mMockEnabler, Collections.singletonList(PRIVILEGED_PACKAGE),
                mPluginInstanceFactory);
        mPluginActionManager = factory.create("myAction", mMockListener,
                TestPlugin.class, true, false);

        createPlugin(); // Get into valid created state.

        mPluginActionManager.disableAll();

        verify(mMockPm, never()).setComponentEnabledSetting(
                ArgumentCaptor.forClass(ComponentName.class).capture(),
                ArgumentCaptor.forClass(int.class).capture(),
                ArgumentCaptor.forClass(int.class).capture());
    }

    private void setupFakePmQuery() throws Exception {
        List<ResolveInfo> list = new ArrayList<>();
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = mock(ServiceInfo.class);
        info.serviceInfo.packageName = mTestPluginComponentName.getPackageName();
        info.serviceInfo.name = mTestPluginComponentName.getClassName();
        when(info.serviceInfo.loadLabel(any())).thenReturn("Test Plugin");
        list.add(info);
        when(mMockPm.queryIntentServices(any(), Mockito.anyInt())).thenReturn(list);
        when(mMockPm.getServiceInfo(any(), anyInt())).thenReturn(info.serviceInfo);

        when(mMockPm.checkPermission(Mockito.anyString(), Mockito.anyString())).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        when(mMockPm.getApplicationInfo(Mockito.anyString(), anyInt())).thenAnswer(
                (Answer<ApplicationInfo>) invocation -> {
                    ApplicationInfo appInfo = getContext().getApplicationInfo();
                    appInfo.packageName = invocation.getArgument(0);
                    return appInfo;
                });
        when(mMockEnabler.isEnabled(mTestPluginComponentName)).thenReturn(true);
    }

    private void createPlugin() throws Exception {
        setupFakePmQuery();

        mPluginActionManager.loadAll();

        mFakeExecutor.runAllReady();
    }

    // Real context with no registering/unregistering of receivers.
    private static class MyContextWrapper extends SysuiTestableContext {
        MyContextWrapper(Context base) {
            super(base);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            return null;
        }

        @Override
        public void unregisterReceiver(BroadcastReceiver receiver) {
        }

        @Override
        public void sendBroadcast(Intent intent) {
            // Do nothing.
        }
    }

    // This target class doesn't matter, it just needs to have a Requires to hit the flow where
    // the mock version info is called.
    @Requires(target = PluginManagerTest.class, version = 1)
    public static class TestPlugin implements Plugin {
        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public void onCreate(Context sysuiContext, Context pluginContext) {
        }

        @Override
        public void onDestroy() {
        }
    }
}
