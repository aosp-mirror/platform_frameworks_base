/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.pm;

import android.content.Context;
import android.content.pm.ModuleInfo;
import android.test.InstrumentationTestCase;

import com.android.frameworks.servicestests.R;

import java.util.Collections;
import java.util.List;

public class ModuleInfoProviderTest extends InstrumentationTestCase {
    public void testSuccessfulParse() {
        ModuleInfoProvider provider = getProvider(R.xml.well_formed_metadata);

        List<ModuleInfo> mi = provider.getInstalledModules(0);
        assertEquals(2, mi.size());

        Collections.sort(mi, (ModuleInfo m1, ModuleInfo m2) ->
                m1.getPackageName().compareTo(m1.getPackageName()));
        assertEquals("com.android.module1", mi.get(0).getPackageName());
        assertEquals("com.android.module2", mi.get(1).getPackageName());

        ModuleInfo mi1 = provider.getModuleInfo("com.android.module1", 0);
        assertEquals("com.android.module1", mi1.getPackageName());
        assertEquals("module_1_name", mi1.getName());
        assertEquals(false, mi1.isHidden());

        ModuleInfo mi2 = provider.getModuleInfo("com.android.module2", 0);
        assertEquals("com.android.module2", mi2.getPackageName());
        assertEquals("module_2_name", mi2.getName());
        assertEquals(true, mi2.isHidden());
    }

    public void testParseFailure_incorrectTopLevelElement() {
        ModuleInfoProvider provider = getProvider(R.xml.unparseable_metadata1);
        assertEquals(0, provider.getInstalledModules(0).size());
    }

    public void testParseFailure_incorrectModuleElement() {
        ModuleInfoProvider provider = getProvider(R.xml.unparseable_metadata2);
        assertEquals(0, provider.getInstalledModules(0).size());
    }

    public void testParse_unknownAttributesIgnored() {
        ModuleInfoProvider provider = getProvider(R.xml.well_formed_metadata);

        List<ModuleInfo> mi = provider.getInstalledModules(0);
        assertEquals(2, mi.size());

        ModuleInfo mi1 = provider.getModuleInfo("com.android.module1", 0);
        assertEquals("com.android.module1", mi1.getPackageName());
        assertEquals("module_1_name", mi1.getName());
        assertEquals(false, mi1.isHidden());
    }

    /**
     * Constructs an {@code ModuleInfoProvider} using the test package resources.
     */
    private ModuleInfoProvider getProvider(int resourceId) {
        final Context ctx = getInstrumentation().getContext();
        return new ModuleInfoProvider(ctx.getResources().getXml(resourceId), ctx.getResources());
    }
}
