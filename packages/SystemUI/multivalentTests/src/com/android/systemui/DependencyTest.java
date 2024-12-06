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

import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DependencyTest extends SysuiTestCase {

    @Test
    public void testClassDependency() {
        FakeClass f = new FakeClass();
        mDependency.injectTestDependency(FakeClass.class, f);
        Assert.assertEquals(f, Dependency.get(FakeClass.class));
    }

    @Test
    public void testStringDependency() {
        Looper l = Looper.getMainLooper();
        mDependency.injectTestDependency(Dependency.BG_LOOPER, l);
        assertEquals(l, Dependency.get(Dependency.BG_LOOPER));
    }

    @Test
    public void testInitDependency() throws ExecutionException, InterruptedException {
        Dependency.clearDependencies();
        SystemUIInitializer initializer = new SystemUIInitializerImpl(mContext);
        initializer.init(true);
        Dependency dependency = initializer.getSysUIComponent().createDependency();
        dependency.start();
    }

    private static class FakeClass {

    }
}
