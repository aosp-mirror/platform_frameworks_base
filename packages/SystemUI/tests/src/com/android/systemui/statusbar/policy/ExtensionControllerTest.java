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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.support.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.OverlayPlugin;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;
import com.android.systemui.statusbar.policy.ExtensionController.TunerFactory;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.function.Consumer;

@SmallTest
public class ExtensionControllerTest extends SysuiTestCase {

    private PluginManager mPluginManager;
    private TunerService mTunerService;
    private ExtensionController mExtensionController;

    @Before
    public void setup() {
        mPluginManager = mDependency.injectMockDependency(PluginManager.class);
        mTunerService = mDependency.injectMockDependency(TunerService.class);
        mExtensionController = Dependency.get(ExtensionController.class);
    }

    @Test
    public void testPlugin() {
        Extension ext = mExtensionController.newExtension(OverlayPlugin.class)
                .withPlugin(OverlayPlugin.class)
                .build();
        verify(mPluginManager).addPluginListener(eq(OverlayPlugin.ACTION), any(),
                eq(OverlayPlugin.class));

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
    public void testDefault() {
        Object o = new Object();
        Extension ext = mExtensionController.newExtension(Object.class)
                .withDefault(() -> o)
                .build();
        assertEquals(o, ext.get());
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
