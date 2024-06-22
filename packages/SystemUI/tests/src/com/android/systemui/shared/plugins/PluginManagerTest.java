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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
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
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.annotations.ProvidesInterface;
import com.android.systemui.shared.system.UncaughtExceptionPreHandlerManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper
public class PluginManagerTest extends SysuiTestCase {
    private static final String PRIVILEGED_PACKAGE = "com.android.systemui";

    @Mock PluginActionManager.Factory mMockFactory;
    @Mock PluginActionManager<TestPlugin> mMockPluginInstance;
    @Mock PluginListener<TestPlugin> mMockListener;
    @Mock PackageManager mMockPackageManager;
    @Mock PluginEnabler mMockPluginEnabler;
    @Mock PluginPrefs mMockPluginPrefs;
    @Mock UncaughtExceptionPreHandlerManager mMockExPreHandlerManager;

    @Captor ArgumentCaptor<UncaughtExceptionHandler> mExPreHandlerCaptor;

    private PluginManagerImpl mPluginManager;
    private UncaughtExceptionHandler mPluginExceptionHandler;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mMockFactory.create(any(), any(), eq(TestPlugin.class), anyBoolean(), anyBoolean()))
                .thenReturn(mMockPluginInstance);

        mPluginManager = new PluginManagerImpl(
                getContext(), mMockFactory, true,
                mMockExPreHandlerManager, mMockPluginEnabler,
                mMockPluginPrefs, new ArrayList<>());
        captureExceptionHandler();
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
                mMockExPreHandlerManager, mMockPluginEnabler,
                mMockPluginPrefs, new ArrayList<>());
        captureExceptionHandler();

        String sourceDir = "myPlugin";
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.sourceDir = sourceDir;
        applicationInfo.packageName = PRIVILEGED_PACKAGE;
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        verify(mMockFactory).create(eq("myAction"), eq(mMockListener), eq(TestPlugin.class),
                eq(false), eq(false));
        verify(mMockPluginInstance).loadAll();
    }

    @Test
    @RunWithLooper(setAsMainLooper = true)
    public void testNonDebuggable_privilegedPackage() {
        mPluginManager = new PluginManagerImpl(
                getContext(), mMockFactory, false,
                mMockExPreHandlerManager, mMockPluginEnabler,
                mMockPluginPrefs, Collections.singletonList(PRIVILEGED_PACKAGE));
        captureExceptionHandler();

        String sourceDir = "myPlugin";
        ApplicationInfo privilegedApplicationInfo = new ApplicationInfo();
        privilegedApplicationInfo.sourceDir = sourceDir;
        privilegedApplicationInfo.packageName = PRIVILEGED_PACKAGE;
        ApplicationInfo invalidApplicationInfo = new ApplicationInfo();
        invalidApplicationInfo.sourceDir = sourceDir;
        invalidApplicationInfo.packageName = "com.android.invalidpackage";
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        verify(mMockFactory).create(eq("myAction"), eq(mMockListener), eq(TestPlugin.class),
                eq(false), eq(false));
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
    }

    @Test
    public void testExceptionHandler_noFoundPlugin() {
        mPluginManager.addPluginListener("myAction", mMockListener, TestPlugin.class);
        when(mMockPluginInstance.checkAndDisable(any())).thenReturn(false);

        mPluginExceptionHandler.uncaughtException(Thread.currentThread(), new Throwable());

        verify(mMockPluginInstance, Mockito.atLeastOnce()).checkAndDisable(
                ArgumentCaptor.forClass(String.class).capture());
        verify(mMockPluginInstance).disableAll();
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
        verify(mMockPluginEnabler).setDisabled(testComponent,
                PluginEnabler.DISABLED_INVALID_VERSION);
    }

    private void captureExceptionHandler() {
        verify(mMockExPreHandlerManager, atLeastOnce()).registerHandler(
                mExPreHandlerCaptor.capture());
        List<UncaughtExceptionHandler> allValues = mExPreHandlerCaptor.getAllValues();
        mPluginExceptionHandler = allValues.get(allValues.size() - 1);
    }

    @ProvidesInterface(action = TestPlugin.ACTION, version = TestPlugin.VERSION)
    public interface TestPlugin extends Plugin {
        String ACTION = "testAction";
        int VERSION = 1;
    }
}
