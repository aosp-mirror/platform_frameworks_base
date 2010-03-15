/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.bridge;

import com.android.ninepatch.NinePatch;

import java.net.URL;

import junit.framework.TestCase;

public class NinePatchTest extends TestCase {

    private NinePatch mPatch;

    @Override
    protected void setUp() throws Exception {
        URL url = this.getClass().getClassLoader().getResource(
                "com/android/layoutlib/testdata/button.9.png");

        mPatch = NinePatch.load(url, false /* convert */);
    }

    public void test9PatchLoad() throws Exception {
        assertNotNull(mPatch);
    }

    public void test9PatchMinSize() {
        int[] padding = new int[4];
        mPatch.getPadding(padding);
        assertEquals(13, padding[0]);
        assertEquals(3, padding[1]);
        assertEquals(13, padding[2]);
        assertEquals(4, padding[3]);
        assertEquals(36, mPatch.getWidth());
        assertEquals(25, mPatch.getHeight());
    }

}
