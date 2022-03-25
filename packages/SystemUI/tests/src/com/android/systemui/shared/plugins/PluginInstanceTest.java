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

import static junit.framework.Assert.assertNotNull;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.annotations.ProvidesInterface;
import com.android.systemui.plugins.annotations.Requires;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PluginInstanceTest extends SysuiTestCase {

    private static final String PRIVILEGED_PACKAGE = "com.android.systemui.plugins";

    @Mock
    private TestPluginImpl mMockPlugin;
    @Mock
    private PluginListener<TestPlugin> mMockListener;
    @Mock
    private VersionInfo mVersionInfo;
    ComponentName mTestPluginComponentName =
            new ComponentName(PRIVILEGED_PACKAGE, TestPluginImpl.class.getName());
    private PluginInstance<TestPlugin> mPluginInstance;
    private PluginInstance.Factory mPluginInstanceFactory;

    private ApplicationInfo mAppInfo;
    private Context mPluginContext;
    @Mock
    private PluginInstance.VersionChecker mVersionChecker;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mAppInfo = mContext.getApplicationInfo();
        mAppInfo.packageName = mTestPluginComponentName.getPackageName();
        when(mVersionChecker.checkVersion(any(), any(), any())).thenReturn(mVersionInfo);

        mPluginInstanceFactory = new PluginInstance.Factory(
                this.getClass().getClassLoader(),
                new PluginInstance.InstanceFactory<TestPlugin>() {
                    @Override
                    TestPlugin create(Class cls) {
                        return mMockPlugin;
                    }
                },
                mVersionChecker,
                Collections.singletonList(PRIVILEGED_PACKAGE),
                false);

        mPluginInstance = mPluginInstanceFactory.create(
                mContext, mAppInfo, mTestPluginComponentName, TestPlugin.class);
        mPluginContext = mPluginInstance.getPluginContext();
    }

    @Test
    public void testCorrectVersion() {
        assertNotNull(mPluginInstance);
    }

    @Test(expected = VersionInfo.InvalidVersionException.class)
    public void testIncorrectVersion() throws Exception {

        ComponentName wrongVersionTestPluginComponentName =
                new ComponentName(PRIVILEGED_PACKAGE, TestPlugin.class.getName());

        when(mVersionChecker.checkVersion(any(), any(), any())).thenThrow(
                new VersionInfo.InvalidVersionException("test", true));

        mPluginInstanceFactory.create(
                mContext, mAppInfo, wrongVersionTestPluginComponentName, TestPlugin.class);
    }

    @Test
    public void testOnCreate() {
        mPluginInstance.onCreate(mContext, mMockListener);
        verify(mMockPlugin).onCreate(mContext, mPluginContext);
        verify(mMockListener).onPluginConnected(mMockPlugin, mPluginContext);
    }

    @Test
    public void testOnDestroy() {
        mPluginInstance.onDestroy(mMockListener);
        verify(mMockListener).onPluginDisconnected(mMockPlugin);
        verify(mMockPlugin).onDestroy();
    }

    // This target class doesn't matter, it just needs to have a Requires to hit the flow where
    // the mock version info is called.
    @ProvidesInterface(action = TestPlugin.ACTION, version = TestPlugin.VERSION)
    public interface TestPlugin extends Plugin {
        int VERSION = 1;
        String ACTION = "testAction";
    }

    @Requires(target = TestPlugin.class, version = TestPlugin.VERSION)
    public static class TestPluginImpl implements TestPlugin {
        @Override
        public void onCreate(Context sysuiContext, Context pluginContext) {
        }
    }
}
