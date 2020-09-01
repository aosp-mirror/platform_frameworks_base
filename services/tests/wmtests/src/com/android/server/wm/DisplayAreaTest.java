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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.server.wm.DisplayArea.Type.ABOVE_TASKS;
import static com.android.server.wm.DisplayArea.Type.ANY;
import static com.android.server.wm.DisplayArea.Type.BELOW_TASKS;
import static com.android.server.wm.DisplayArea.Type.checkChild;
import static com.android.server.wm.DisplayArea.Type.checkSiblings;
import static com.android.server.wm.DisplayArea.Type.typeOf;
import static com.android.server.wm.testing.Assert.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import org.junit.Rule;
import org.junit.Test;

@Presubmit
public class DisplayAreaTest {

    @Rule
    public SystemServicesTestRule mWmsRule = new SystemServicesTestRule();

    @Test
    public void testDisplayArea_positionChanged_throwsIfIncompatibleChild() {
        WindowManagerService wms = mWmsRule.getWindowManagerService();
        DisplayArea<WindowContainer> parent = new DisplayArea<>(wms, BELOW_TASKS, "Parent");
        DisplayArea<WindowContainer> child = new DisplayArea<>(wms, ANY, "Child");

        assertThrows(IllegalStateException.class, () -> parent.addChild(child, 0));
    }

    @Test
    public void testType_typeOf() {
        WindowManagerService wms = mWmsRule.getWindowManagerService();

        assertEquals(ABOVE_TASKS, typeOf(new DisplayArea<>(wms, ABOVE_TASKS, "test")));
        assertEquals(ANY, typeOf(new DisplayArea<>(wms, ANY, "test")));
        assertEquals(BELOW_TASKS, typeOf(new DisplayArea<>(wms, BELOW_TASKS, "test")));

        assertEquals(ABOVE_TASKS, typeOf(createWindowToken(TYPE_APPLICATION_OVERLAY)));
        assertEquals(ABOVE_TASKS, typeOf(createWindowToken(TYPE_PRESENTATION)));
        assertEquals(BELOW_TASKS, typeOf(createWindowToken(TYPE_WALLPAPER)));

        assertThrows(IllegalArgumentException.class, () -> typeOf(mock(ActivityRecord.class)));
        assertThrows(IllegalArgumentException.class, () -> typeOf(mock(WindowContainer.class)));
    }

    @Test
    public void testType_checkSiblings() {
        checkSiblings(BELOW_TASKS, BELOW_TASKS);
        checkSiblings(BELOW_TASKS, ANY);
        checkSiblings(BELOW_TASKS, ABOVE_TASKS);
        checkSiblings(ANY, ABOVE_TASKS);
        checkSiblings(ABOVE_TASKS, ABOVE_TASKS);
        checkSiblings(ANY, ANY);

        assertThrows(IllegalStateException.class, () -> checkSiblings(ABOVE_TASKS, BELOW_TASKS));
        assertThrows(IllegalStateException.class, () -> checkSiblings(ABOVE_TASKS, ANY));
        assertThrows(IllegalStateException.class, () -> checkSiblings(ANY, BELOW_TASKS));
    }

    @Test
    public void testType_checkChild() {
        checkChild(ANY, ANY);
        checkChild(ANY, ABOVE_TASKS);
        checkChild(ANY, BELOW_TASKS);
        checkChild(ABOVE_TASKS, ABOVE_TASKS);
        checkChild(BELOW_TASKS, BELOW_TASKS);

        assertThrows(IllegalStateException.class, () -> checkChild(ABOVE_TASKS, BELOW_TASKS));
        assertThrows(IllegalStateException.class, () -> checkChild(ABOVE_TASKS, ANY));
        assertThrows(IllegalStateException.class, () -> checkChild(BELOW_TASKS, ABOVE_TASKS));
        assertThrows(IllegalStateException.class, () -> checkChild(BELOW_TASKS, ANY));
    }

    private WindowToken createWindowToken(int type) {
        return new WindowToken(mWmsRule.getWindowManagerService(), new Binder(),
                type, false /* persist */, null /* displayContent */,
                false /* canManageTokens */);
    }

    private static class SurfacelessDisplayArea<T extends WindowContainer> extends DisplayArea<T> {

        SurfacelessDisplayArea(WindowManagerService wms, Type type, String name) {
            super(wms, type, name);
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceControlBuilder();
        }
    }
}
