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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
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

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.annotations.Requires;
import com.android.systemui.shared.plugins.VersionInfo.InvalidVersionException;
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
public class PluginInstanceManagerTest extends SysuiTestCase {

    private static final String PRIVILEGED_PACKAGE = "com.android.systemui.shared.plugins";
    private TestPlugin mMockPlugin;

    private PackageManager mMockPm;
    private PluginListener<Plugin> mMockListener;
    private PluginInstanceManager<Plugin> mPluginInstanceManager;
    private VersionInfo mMockVersionInfo;
    private PluginEnabler mMockEnabler;
    ComponentName mTestPluginComponentName =
            new ComponentName(PRIVILEGED_PACKAGE, TestPlugin.class.getName());
    private PluginInitializer mInitializer;
    private final FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    NotificationManager mNotificationManager;
    private PluginInstanceManager.Factory mInstanceManagerFactory;
    private final PluginInstanceManager.InstanceFactory<Plugin> mPluginInstanceFactory =
            new PluginInstanceManager.InstanceFactory<Plugin>() {
        @Override
        Plugin create(Class cls) {
            return mMockPlugin;
        }
    };

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
        mInstanceManagerFactory = new PluginInstanceManager.Factory(getContext(), mMockPm,
                mFakeExecutor, mFakeExecutor, mInitializer, mNotificationManager, mMockEnabler,
                new ArrayList<>())
                .setInstanceFactory(mPluginInstanceFactory);

        mPluginInstanceManager = mInstanceManagerFactory.create("myAction", mMockListener,
                true, mMockVersionInfo, true);
        when(mMockPlugin.getVersion()).thenReturn(1);
    }

    @Test
    public void testNoPlugins() {
        when(mMockPm.queryIntentServices(any(), anyInt())).thenReturn(
                Collections.emptyList());
        mPluginInstanceManager.loadAll();

        mFakeExecutor.runAllReady();

        verify(mMockListener, never()).onPluginConnected(any(), any());
    }

    @Test
    public void testPluginCreate() throws Exception {
        createPlugin();

        // Verify startup lifecycle
        verify(mMockPlugin).onCreate(ArgumentCaptor.forClass(Context.class).capture(),
                ArgumentCaptor.forClass(Context.class).capture());
        verify(mMockListener).onPluginConnected(any(), any());
    }

    @Test
    public void testPluginDestroy() throws Exception {
        createPlugin(); // Get into valid created state.

        mPluginInstanceManager.destroy();

        mFakeExecutor.runAllReady();


        // Verify shutdown lifecycle
        verify(mMockListener).onPluginDisconnected(ArgumentCaptor.forClass(Plugin.class).capture());
        verify(mMockPlugin).onDestroy();
    }

    @Test
    public void testIncorrectVersion() throws Exception {
        setupFakePmQuery();
        doThrow(new InvalidVersionException("", false)).when(mMockVersionInfo).checkVersion(any());

        mPluginInstanceManager.loadAll();

        mFakeExecutor.runAllReady();

        // Plugin shouldn't be connected because it is the wrong version.
        verify(mMockListener, never()).onPluginConnected(any(), any());
        verify(mNotificationManager).notify(eq(SystemMessage.NOTE_PLUGIN), any());
    }

    @Test
    public void testReloadOnChange() throws Exception {
        createPlugin(); // Get into valid created state.

        mPluginInstanceManager.onPackageChange(PRIVILEGED_PACKAGE);

        mFakeExecutor.runAllReady();

        // Verify the old one was destroyed.
        verify(mMockListener).onPluginDisconnected(ArgumentCaptor.forClass(Plugin.class).capture());
        verify(mMockPlugin).onDestroy();
        // Also verify we got a second onCreate.
        verify(mMockPlugin, Mockito.times(2)).onCreate(
                ArgumentCaptor.forClass(Context.class).capture(),
                ArgumentCaptor.forClass(Context.class).capture());
        verify(mMockListener, Mockito.times(2)).onPluginConnected(any(), any());
    }

    @Test
    public void testNonDebuggable() throws Exception {
        // Create a version that thinks the build is not debuggable.
        mPluginInstanceManager = mInstanceManagerFactory.create("myAction", mMockListener,
                true, mMockVersionInfo, false);
        setupFakePmQuery();

        mPluginInstanceManager.loadAll();

        mFakeExecutor.runAllReady();

        // Non-debuggable build should receive no plugins.
        verify(mMockListener, never()).onPluginConnected(any(), any());
    }

    @Test
    public void testNonDebuggable_privileged() throws Exception {
        // Create a version that thinks the build is not debuggable.
        PluginInstanceManager.Factory factory = new PluginInstanceManager.Factory(getContext(),
                mMockPm, mFakeExecutor, mFakeExecutor, mInitializer, mNotificationManager,
                mMockEnabler, Collections.singletonList(PRIVILEGED_PACKAGE));
        factory.setInstanceFactory(mPluginInstanceFactory);
        mPluginInstanceManager = factory.create("myAction", mMockListener,
                true, mMockVersionInfo, false);
        setupFakePmQuery();

        mPluginInstanceManager.loadAll();

        mFakeExecutor.runAllReady();

        // Verify startup lifecycle
        verify(mMockPlugin).onCreate(ArgumentCaptor.forClass(Context.class).capture(),
                ArgumentCaptor.forClass(Context.class).capture());
        verify(mMockListener).onPluginConnected(any(), any());
    }

    @Test
    public void testCheckAndDisable() throws Exception {
        createPlugin(); // Get into valid created state.

        // Start with an unrelated class.
        boolean result = mPluginInstanceManager.checkAndDisable(Activity.class.getName());
        assertFalse(result);
        verify(mMockEnabler, never()).setDisabled(any(ComponentName.class), anyInt());

        // Now hand it a real class and make sure it disables the plugin.
        result = mPluginInstanceManager.checkAndDisable(TestPlugin.class.getName());
        assertTrue(result);
        verify(mMockEnabler).setDisabled(
                mTestPluginComponentName, PluginEnabler.DISABLED_FROM_EXPLICIT_CRASH);
    }

    @Test
    public void testDisableAll() throws Exception {
        createPlugin(); // Get into valid created state.

        mPluginInstanceManager.disableAll();

        verify(mMockEnabler).setDisabled(
                mTestPluginComponentName, PluginEnabler.DISABLED_FROM_SYSTEM_CRASH);
    }

    @Test
    public void testDisableWhitelisted() throws Exception {
        PluginInstanceManager.Factory factory = new PluginInstanceManager.Factory(getContext(),
                mMockPm, mFakeExecutor, mFakeExecutor, mInitializer, mNotificationManager,
                mMockEnabler, Collections.singletonList(PRIVILEGED_PACKAGE));
        factory.setInstanceFactory(mPluginInstanceFactory);
        mPluginInstanceManager = factory.create("myAction", mMockListener,
                true, mMockVersionInfo, false);

        createPlugin(); // Get into valid created state.

        mPluginInstanceManager.disableAll();

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

        mPluginInstanceManager.loadAll();

        mFakeExecutor.runAllReady();
    }

    // Real context with no registering/unregistering of receivers.
    private static class MyContextWrapper extends SysuiTestableContext {
        public MyContextWrapper(Context base) {
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
