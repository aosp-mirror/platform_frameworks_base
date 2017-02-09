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

package com.android.systemui;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.os.Looper;

import com.android.systemui.ConfigurationChangedReceiver;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.FlashlightController;

import org.junit.Assert;
import org.junit.Test;

import java.io.PrintWriter;

public class DependencyTest extends SysuiTestCase {

    @Test
    public void testClassDependency() {
        FlashlightController f = mock(FlashlightController.class);
        injectTestDependency(FlashlightController.class, f);
        Assert.assertEquals(f, Dependency.get(FlashlightController.class));
    }

    @Test
    public void testStringDependency() {
        Looper l = Looper.getMainLooper();
        injectTestDependency(Dependency.BG_LOOPER, l);
        assertEquals(l, Dependency.get(Dependency.BG_LOOPER));
    }

    @Test
    public void testDump() {
        Dumpable d = mock(Dumpable.class);
        injectTestDependency("test", d);
        Dependency.get("test");
        mDependency.dump(null, mock(PrintWriter.class), null);
        verify(d).dump(eq(null), any(), eq(null));
    }

    @Test
    public void testConfigurationChanged() {
        ConfigurationChangedReceiver d = mock(ConfigurationChangedReceiver.class);
        injectTestDependency("test", d);
        Dependency.get("test");
        mDependency.onConfigurationChanged(null);
        verify(d).onConfigurationChanged(eq(null));
    }
}
