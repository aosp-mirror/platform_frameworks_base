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
package com.android.systemui.plugins;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.PluginInstanceManager.PluginInfo;
import com.android.systemui.plugins.PluginManagerImpl.PluginInstanceManagerFactory;
import com.android.systemui.plugins.annotations.ProvidesInterface;

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

    private PluginInstanceManagerFactory mMockFactory;
    private PluginInstanceManager mMockPluginInstance;
    private PluginManagerImpl mPluginManager;
    private PluginListener mMockListener;

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
        mPluginManager = new PluginManagerImpl(getContext(), mMockFactory, true,
                mMockExceptionHandler);
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
        assertTrue(result == mockPlugin);
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
    public void testNonDebuggable() {
        mPluginManager = new PluginManagerImpl(getContext(), mMockFactory, false,
                mMockExceptionHandler);
        resetExceptionHandler();

        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        verify(mMockPluginInstance, Mockito.never()).loadAll();

        assertNull(mPluginManager.getOneShotPlugin("myPlugin", TestPlugin.class));
        verify(mMockPluginInstance, Mockito.never()).getPlugin();
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
        PackageManager pm = mock(PackageManager.class);
        mContext.addMockSystemService(Context.NOTIFICATION_SERVICE, nm);
        mContext.setMockPackageManager(pm);

        ComponentName testComponent = new ComponentName(getContext().getPackageName(),
                PluginManagerTest.class.getName());
        Intent intent = new Intent(PluginManagerImpl.DISABLE_PLUGIN);
        intent.setData(Uri.parse("package://" + testComponent.flattenToString()));
        mPluginManager.onReceive(mContext, intent);
        verify(nm).cancel(eq(testComponent.getClassName()), eq(SystemMessage.NOTE_PLUGIN));
        verify(pm).setComponentEnabledSetting(eq(testComponent),
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
