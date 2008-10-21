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

import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics._Original_Paint;
import android.text.TextPaint;

import junit.framework.TestCase;

/**
 * 
 */
public class AndroidGraphicsTests extends TestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testMatrix() {
        Matrix m1 = new Matrix();
        
        assertFalse(m1.isIdentity());
        
        m1.setValues(new float[] { 1,0,0, 0,1,0, 0,0,1 });
        assertTrue(m1.isIdentity());
        
        Matrix m2 = new Matrix(m1);
        
        float[] v1 = new float[9];
        float[] v2 = new float[9];
        m1.getValues(v1);
        m2.getValues(v2);
        
        for (int i = 0 ; i < 9; i++) {
            assertEquals(v1[i], v2[i]);
        }
    }
    
    public void testPaint() {
        _Original_Paint o = new _Original_Paint();
        assertNotNull(o);

        Paint p = new Paint();
        assertNotNull(p);
    }
    
    public void textTextPaint() {
        TextPaint p = new TextPaint();
        assertNotNull(p);
    }
}
