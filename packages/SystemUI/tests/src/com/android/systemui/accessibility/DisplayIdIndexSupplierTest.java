/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import android.hardware.display.DisplayManager;
import android.testing.AndroidTestingRunner;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DisplayIdIndexSupplierTest extends SysuiTestCase {

    private DisplayIdIndexSupplier<Object> mDisplayIdIndexSupplier;

    @Before
    public void setUp() throws Exception {
        mDisplayIdIndexSupplier = new DisplayIdIndexSupplier(
                mContext.getSystemService(DisplayManager.class)) {

            @NonNull
            @Override
            protected Object createInstance(Display display) {
                return new Object();
            }
        };
    }

    @Test
    public void get_instanceIsNotNull() {
        Object object = mDisplayIdIndexSupplier.get(Display.DEFAULT_DISPLAY);
        assertNotNull(object);
    }

    @Test
    public void get_removeExistedObject_newObject() {
        Object object = mDisplayIdIndexSupplier.get(Display.DEFAULT_DISPLAY);
        mDisplayIdIndexSupplier.remove(Display.DEFAULT_DISPLAY);

        Object newObject = mDisplayIdIndexSupplier.get(Display.DEFAULT_DISPLAY);

        assertNotEquals(object, newObject);
    }

    @Test
    public void get_clearAllObjects_newObject() {
        Object object = mDisplayIdIndexSupplier.get(Display.DEFAULT_DISPLAY);
        mDisplayIdIndexSupplier.clear();

        Object newObject = mDisplayIdIndexSupplier.get(Display.DEFAULT_DISPLAY);

        assertNotEquals(object, newObject);
    }
}
