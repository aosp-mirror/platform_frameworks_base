/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.unit_tests.content;

import android.content.res.Resources;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.unit_tests.R;

public class ResourceNameTest extends AndroidTestCase {

    @SmallTest
    public void testGetResourceName() {
        Resources res = mContext.getResources();
        
        String fullName = res.getResourceName(R.configVarying.simple);
        assertEquals("com.android.unit_tests:configVarying/simple", fullName);
        
        String packageName = res.getResourcePackageName(R.configVarying.simple);
        assertEquals("com.android.unit_tests", packageName);
        
        String typeName = res.getResourceTypeName(R.configVarying.simple);
        assertEquals("configVarying", typeName);
        
        String entryName = res.getResourceEntryName(R.configVarying.simple);
        assertEquals("simple", entryName);
    }
    
    @SmallTest
    public void testGetResourceIdentifier() {
        Resources res = mContext.getResources();
        int resid = res.getIdentifier(
                "com.android.unit_tests:configVarying/simple",
                null, null);
        assertEquals(R.configVarying.simple, resid);
        
        resid = res.getIdentifier("configVarying/simple", null,
                "com.android.unit_tests");
        assertEquals(R.configVarying.simple, resid);
        
        resid = res.getIdentifier("simple", "configVarying",
                "com.android.unit_tests");
        assertEquals(R.configVarying.simple, resid);
    }    
}

