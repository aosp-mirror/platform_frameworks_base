/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool;

import org.junit.Before;
import org.junit.Test;

import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.reflection.SdkUtil;
import android.databinding.tool.reflection.java.JavaAnalyzer;

import static org.junit.Assert.assertEquals;

public class SdkVersionTest {

    @Before
    public void setUp() throws Exception {
        JavaAnalyzer.initForTests();
    }

    @Test
    public void testNewApiMethod() {
        ModelClass view = ModelAnalyzer.getInstance().findClass("android.view.View", null);
        ModelMethod setElevation = view.getMethods("setElevation", 1)[0];
        assertEquals(21, SdkUtil.getMinApi(setElevation));
    }

    @Test
    public void testCustomCode() {
        ModelClass view = ModelAnalyzer.getInstance()
                .findClass("android.databinding.tool.SdkVersionTest", null);
        ModelMethod setElevation = view.getMethods("testCustomCode", 0)[0];
        assertEquals(1, SdkUtil.getMinApi(setElevation));
    }
}
