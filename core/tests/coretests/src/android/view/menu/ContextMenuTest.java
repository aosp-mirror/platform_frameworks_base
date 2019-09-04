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

package android.view.menu;

import android.content.Context;
import android.graphics.Point;
import android.test.ActivityInstrumentationTestCase;
import android.util.PollingCheck;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.espresso.ContextMenuUtils;

import androidx.test.filters.MediumTest;

@MediumTest
public class ContextMenuTest extends ActivityInstrumentationTestCase<ContextMenuActivity> {

    public ContextMenuTest() {
        super("com.android.frameworks.coretests", ContextMenuActivity.class);
    }

    public void testContextMenuPositionLtr() throws InterruptedException {
        testMenuPosition(getActivity().getTargetLtr());
    }

    public void testContextMenuPositionRtl() throws InterruptedException {
        testMenuPosition(getActivity().getTargetRtl());
    }

    public void testContextMenuPositionRepetitive() throws InterruptedException {
        // Regression test for b/72507876
        testMenuPosition(getActivity().getTargetLtr());
        testMenuPosition(getActivity().getTargetRtl());
        testMenuPosition(getActivity().getTargetLtr());
    }

    private void testMenuPosition(View target) throws InterruptedException {
        final int minScreenDimension = getMinScreenDimension();
        if (minScreenDimension < 320) {
            // Assume there is insufficient room for the context menu to be aligned properly.
            return;
        }

        int offsetX = target.getWidth() / 2;
        int offsetY = target.getHeight() / 2;

        getInstrumentation().runOnMainSync(() -> target.performLongClick(offsetX, offsetY));

        PollingCheck.waitFor(
                () -> ContextMenuUtils.isMenuItemClickable(ContextMenuActivity.LABEL_SUBMENU));

        ContextMenuUtils.assertContextMenuAlignment(target, offsetX, offsetY);

        ContextMenuUtils.clickMenuItem(ContextMenuActivity.LABEL_SUBMENU);

        PollingCheck.waitFor(
                () -> ContextMenuUtils.isMenuItemClickable(ContextMenuActivity.LABEL_SUBITEM));

        if (minScreenDimension < getCascadingMenuTreshold()) {
            // A non-cascading submenu should be displayed at the same location as its parent.
            // Not testing cascading submenu position, as it is positioned differently.
            ContextMenuUtils.assertContextMenuAlignment(target, offsetX, offsetY);
        }
    }

    /**
     * Returns the minimum of the default display's width and height.
     */
    private int getMinScreenDimension() {
        final WindowManager windowManager = (WindowManager) getActivity().getSystemService(
                Context.WINDOW_SERVICE);
        final Display display = windowManager.getDefaultDisplay();
        final Point displaySize = new Point();
        display.getRealSize(displaySize);
        return Math.min(displaySize.x, displaySize.y);
    }

    /**
     * Returns the minimum display size where cascading submenus are supported.
     */
    private int getCascadingMenuTreshold() {
        // Use the same dimension resource as in MenuPopupHelper.createPopup().
        return getActivity().getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.cascading_menus_min_smallest_width);
    }
}
