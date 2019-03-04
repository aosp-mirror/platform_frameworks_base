/*
 * Copyright (C) 2018 The Android Open Source Project
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

import dalvik.system.PathClassLoader;

import org.junit.Test;

import java.lang.reflect.Method;

// Adding tests here requires changes in several other places. See README.md in
// the view_compiler directory for more information.
public class LayoutCompilerTest {
    static ClassLoader loadDexFile(String filename) throws Exception {
        return new PathClassLoader("/data/local/tmp/dex-builder-test/" + filename,
                ClassLoader.getSystemClassLoader());
    }

    @Test
    public void loadAndInflateLayout1() throws Exception {
        ClassLoader dex_file = loadDexFile("layout1.dex");
        Class compiled_view = dex_file.loadClass("android.startop.test.CompiledView");
        Method layout1 = compiled_view.getMethod("layout1", Context.class, int.class);
        Context context = InstrumentationRegistry.getTargetContext();
        layout1.invoke(null, context, R.layout.layout1);
    }

    @Test
    public void loadAndInflateLayout2() throws Exception {
        ClassLoader dex_file = loadDexFile("layout2.dex");
        Class compiled_view = dex_file.loadClass("android.startop.test.CompiledView");
        Method layout1 = compiled_view.getMethod("layout2", Context.class, int.class);
        Context context = InstrumentationRegistry.getTargetContext();
        layout1.invoke(null, context, R.layout.layout1);
    }
}
