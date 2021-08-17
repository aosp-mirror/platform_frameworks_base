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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
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
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.annotations.ProvidesInterface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class PluginManagerTest extends SysuiTestCase {

    private static final String PRIVILEGED_PACKAGE = "com.android.systemui";

    private PluginInstanceManager.Factory mMockFactory;
    private PluginInstanceManager<Plugin> mMockPluginInstance;
    private PluginManagerImpl mPluginManager;
    private PluginListener<?> mMockListener;
    private PackageManager mMockPackageManager;
    private PluginEnabler mPluginEnabler;
    private PluginPrefs mPluginPrefs;

    private UncaughtExceptionHandler mRealExceptionHandler;
    private UncaughtExceptionHandler mMockExceptionHandler;
    private UncaughtExceptionHandler mPluginExceptionHandler;

    @Before
    public void setup() throws Exception {
        mRealExceptionHandler = Thread.getUncaughtExceptionPreHandler();
        mMockExceptionHandler = mock(UncaughtExceptionHandler.class);
        mMockFactory = mock(PluginInstanceManager.Factory.class);
        mMockPluginInstance = mock(PluginInstanceManager.class);
        mPluginEnabler = mock(PluginEnabler.class);
        mPluginPrefs = mock(PluginPrefs.class);
        when(mMockFactory.create(any(), any(), Mockito.anyBoolean(), any(), Mockito.anyBoolean()))
                .thenReturn(mMockPluginInstance);

        mMockPackageManager = mock(PackageManager.class);
        mPluginManager = new PluginManagerImpl(
                getContext(), mMockFactory, true,
                Optional.of(mMockExceptionHandler), mPluginEnabler,
                mPluginPrefs, new ArrayList<>());

        resetExceptionHandler();
        mMockListener = mock(PluginListener.class);
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
    public void testNonDebuggable_nonPrivileged() {
        mPluginManager = new PluginManagerImpl(
                getContext(), mMockFactory, false,
                Optional.of(mMockExceptionHandler), mPluginEnabler,
                mPluginPrefs, new ArrayList<>());
        resetExceptionHandler();

        String sourceDir = "myPlugin";
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.sourceDir = sourceDir;
        applicationInfo.packageName = PRIVILEGED_PACKAGE;
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        verify(mMockFactory).create(eq("myAction"), eq(mMockListener), eq(false),
                any(VersionInfo.class), eq(false));
        verify(mMockPluginInstance).loadAll();
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    public void testNonDebuggable_privilegedPackage() {
        mPluginManager = new PluginManagerImpl(
                getContext(), mMockFactory, false,
                Optional.of(mMockExceptionHandler), mPluginEnabler,
                mPluginPrefs, Collections.singletonList(PRIVILEGED_PACKAGE));
        resetExceptionHandler();

        String sourceDir = "myPlugin";
        ApplicationInfo privilegedApplicationInfo = new ApplicationInfo();
        privilegedApplicationInfo.sourceDir = sourceDir;
        privilegedApplicationInfo.packageName = PRIVILEGED_PACKAGE;
        ApplicationInfo invalidApplicationInfo = new ApplicationInfo();
        invalidApplicationInfo.sourceDir = sourceDir;
        invalidApplicationInfo.packageName = "com.android.invalidpackage";
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        verify(mMockFactory).create(eq("myAction"), eq(mMockListener), eq(false),
                any(VersionInfo.class), eq(false));
        verify(mMockPluginInstance).loadAll();
    }

    @Test
    public void testExceptionHandler_foundPlugin() {
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        when(mMockPluginInstance.checkAndDisable(any())).thenReturn(true);

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
        when(mMockPluginInstance.checkAndDisable(any())).thenReturn(false);

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
        verify(mPluginEnabler).setDisabled(testComponent, PluginEnabler.DISABLED_INVALID_VERSION);
    }

    private void resetExceptionHandler() {
        mPluginExceptionHandler = Thread.getUncaughtExceptionPreHandler();
        // Set back the real exception handler so the test can crash if it wants to.
        Thread.setUncaughtExceptionPreHandler(mRealExceptionHandler);
    }

    @ProvidesInterface(action = TestPlugin.ACTION, version = TestPlugin.VERSION)
    public interface TestPlugin extends Plugin {
        String ACTION = "testAction";
        int VERSION = 1;
    }
}
