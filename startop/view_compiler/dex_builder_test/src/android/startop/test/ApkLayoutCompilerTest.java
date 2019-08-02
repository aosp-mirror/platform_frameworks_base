/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.startop.test;

import android.content.Context;
import androidx.test.InstrumentationRegistry;
import android.view.View;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class ApkLayoutCompilerTest {
    static ClassLoader loadDexFile() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        return new PathClassLoader(context.getCodeCacheDir() + "/compiled_view.dex",
                ClassLoader.getSystemClassLoader());
    }

    @BeforeClass
    public static void setup() throws Exception {
        // ensure PackageManager has compiled the layouts.
        Process pm = Runtime.getRuntime().exec("pm compile --compile-layouts android.startop.test");
        pm.waitFor();
    }

    @Test
    public void loadAndInflateLayout1() throws Exception {
        ClassLoader dex_file = loadDexFile();
        Class compiled_view = dex_file.loadClass("android.startop.test.CompiledView");
        Method layout1 = compiled_view.getMethod("layout1", Context.class, int.class);
        Context context = InstrumentationRegistry.getTargetContext();
        layout1.invoke(null, context, R.layout.layout1);
    }

    @Test
    public void loadAndInflateLayout2() throws Exception {
        ClassLoader dex_file = loadDexFile();
        Class compiled_view = dex_file.loadClass("android.startop.test.CompiledView");
        Method layout2 = compiled_view.getMethod("layout2", Context.class, int.class);
        Context context = InstrumentationRegistry.getTargetContext();
        layout2.invoke(null, context, R.layout.layout2);
    }
}
