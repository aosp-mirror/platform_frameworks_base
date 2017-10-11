/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import org.junit.Test;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import static org.junit.Assert.*;

public class SystemServiceRegistry_AccessorTest {
    @Test
    public void testRegistry() {
        // Just check a few services to make sure we don't break the accessor
        assertEquals(Context.ACCESSIBILITY_SERVICE,
                SystemServiceRegistry_Accessor.getSystemServiceName(AccessibilityManager.class));
        assertEquals(Context.INPUT_SERVICE,
                SystemServiceRegistry_Accessor.getSystemServiceName(InputManager.class));
        assertEquals(Context.WINDOW_SERVICE,
                SystemServiceRegistry_Accessor.getSystemServiceName(WindowManager.class));
    }
}