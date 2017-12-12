/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.OverlayPlugin;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;
import com.android.systemui.statusbar.policy.ExtensionController.TunerFactory;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ExtensionControllerImplTest extends SysuiTestCase {

    private PluginManager mPluginManager;
    private TunerService mTunerService;
    private ExtensionController mExtensionController;
    private ConfigurationController mConfigurationController;

    @Before
    public void setup() {
        mPluginManager = mDependency.injectMockDependency(PluginManager.class);
        mTunerService = mDependency.injectMockDependency(TunerService.class);
        mConfigurationController = mDependency.injectMockDependency(ConfigurationController.class);
        mExtensionController = Dependency.get(ExtensionController.class);
    }

    @Test
    public void testPlugin() {
        OverlayPlugin plugin = mock(OverlayPlugin.class);

        Extension ext = mExtensionController.newExtension(OverlayPlugin.class)
                .withPlugin(OverlayPlugin.class)
                .build();
        ArgumentCaptor<PluginListener> listener = ArgumentCaptor.forClass(PluginListener.class);
        verify(mPluginManager).addPluginListener(eq(OverlayPlugin.ACTION), listener.capture(),
                eq(OverlayPlugin.class));

        listener.getValue().onPluginConnected(plugin, null);
        assertEquals(plugin, ext.get());

        ext.destroy();
        verify(mPluginManager).removePluginListener(any());
    }

    @Test
    public void testTuner() {
        String[] keys = new String[] { "key1", "key2" };
        TunerFactory<Object> factory = new ExtensionController.TunerFactory() {
            @Override
            public String[] keys() {
                return keys;
            }

            @Override
            public Object create(Map settings) {
                return null;
            }
        };
        Extension ext = mExtensionController.newExtension(Object.class)
                .withTunerFactory(factory)
                .build();
        verify(mTunerService).addTunable(any(), eq(keys[0]), eq(keys[1]));

        ext.destroy();
        verify(mTunerService).removeTunable(any());
    }

    @Test
    @RunWithLooper
    public void testUiMode() {
        Object o = new Object();
        Extension ext = mExtensionController.newExtension(Object.class)
                .withUiMode(Configuration.UI_MODE_TYPE_CAR, () -> o)
                .build();
        ArgumentCaptor<ConfigurationListener> captor = ArgumentCaptor.forClass(
                ConfigurationListener.class);
        verify(mConfigurationController).addCallback(captor.capture());

        Configuration c = new Configuration(mContext.getResources().getConfiguration());
        c.uiMode = 0;
        captor.getValue().onConfigChanged(c);
        TestableLooper.get(this).processAllMessages();
        assertNull(ext.get());

        c.uiMode = Configuration.UI_MODE_TYPE_CAR;
        captor.getValue().onConfigChanged(c);
        TestableLooper.get(this).processAllMessages();
        assertEquals(o, ext.get());

        ext.destroy();
        verify(mConfigurationController).removeCallback(eq(captor.getValue()));
    }

    @Test
    public void testDefault() {
        Object o = new Object();
        Extension ext = mExtensionController.newExtension(Object.class)
                .withDefault(() -> o)
                .build();
        assertEquals(o, ext.get());
    }

    @Test
    @RunWithLooper
    public void testSortOrder() {
        Object def = new Object();
        Object uiMode = new Object();
        Object tuner = new Object();
        Plugin plugin = mock(Plugin.class);
        TunerFactory<Object> factory = mock(TunerFactory.class);
        Extension ext = mExtensionController.newExtension(Object.class)
                .withDefault(() -> def)
                .withUiMode(Configuration.UI_MODE_TYPE_CAR, () -> uiMode)
                .withTunerFactory(factory)
                .withPlugin(Object.class, "some_action")
                .build();

        // Test default first.
        assertEquals(def, ext.get());

        // Enable a UI mode and check that.
        ArgumentCaptor<ConfigurationListener> captor = ArgumentCaptor.forClass(
                ConfigurationListener.class);
        verify(mConfigurationController).addCallback(captor.capture());
        Configuration c = new Configuration(mContext.getResources().getConfiguration());
        c.uiMode |= Configuration.UI_MODE_TYPE_CAR;
        captor.getValue().onConfigChanged(c);
        TestableLooper.get(this).processAllMessages();
        assertEquals(uiMode, ext.get());

        // Turn on tuner item and check that.
        when(factory.create(any())).thenReturn(tuner);
        ArgumentCaptor<Tunable> tunable = ArgumentCaptor.forClass(Tunable.class);
        verify(mTunerService).addTunable(tunable.capture(), any());
        tunable.getValue().onTuningChanged(null, null);
        assertEquals(tuner, ext.get());

        // Lastly, check a plugin.
        ArgumentCaptor<PluginListener> listener = ArgumentCaptor.forClass(PluginListener.class);
        verify(mPluginManager).addPluginListener(any(), listener.capture(), any());
        listener.getValue().onPluginConnected(plugin, null);
        assertEquals(plugin, ext.get());
    }

    @Test
    public void testCallback() {
        Consumer<Object> callback = mock(Consumer.class);
        final Object o = new Object();
        mExtensionController.newExtension(Object.class)
                .withDefault(() -> o)
                .withCallback(callback)
                .build();
        verify(callback).accept(eq(o));
    }

}
