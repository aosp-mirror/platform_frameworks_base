/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.widget.espresso;

import static androidx.test.espresso.action.ViewActions.actionWithAssertions;

import android.view.View;

import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.CoordinatesProvider;
import androidx.test.espresso.action.GeneralClickAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Tap;

import com.android.internal.util.Preconditions;

/**
 * A collection of view actions.
 */
public final class CustomViewActions {

    /**
     * Returns an action that long presses on a view at coordinates relative to the view's
     * location on screen.
     *
     * @param hRef LEFT, RIGHT, CENTER to specify an x reference
     * @param hDelta number of pixels relative to the hRef point
     * @param vRef TOP, BOTTOM, CENTER to specify a y reference
     * @param vDelta number of pixels relative to the vRef point
     */
    public static ViewAction longPressAtRelativeCoordinates(
            final RelativeCoordinatesProvider.HorizontalReference hRef, final int hDelta,
            final RelativeCoordinatesProvider.VerticalReference vRef, final int vDelta) {
        Preconditions.checkNotNull(hRef);
        Preconditions.checkNotNull(vRef);
        return actionWithAssertions(
                new GeneralClickAction(
                        Tap.LONG,
                        new RelativeCoordinatesProvider(hRef, hDelta, vRef, vDelta),
                        Press.FINGER));
    }

    /**
     * A provider of x, y coordinates relative to a view's boundaries.
     */
    public static final class RelativeCoordinatesProvider implements CoordinatesProvider {

        public enum VerticalReference {
            TOP, BOTTOM, CENTER
        }

        public enum HorizontalReference {
            LEFT, RIGHT, CENTER
        }

        private final HorizontalReference hRef;
        private final VerticalReference vRef;
        private final int hDelta;
        private final int vDelta;

        private RelativeCoordinatesProvider(
                final HorizontalReference hRef, final int hDelta,
                final VerticalReference vRef, final int vDelta) {
            this.hRef = Preconditions.checkNotNull(hRef);
            this.vRef = Preconditions.checkNotNull(vRef);
            this.hDelta = hDelta;
            this.vDelta = vDelta;
        }

        @Override
        public float[] calculateCoordinates(View view) {
            int[] xy = view.getLocationOnScreen();
            int w = view.getWidth();
            int h = view.getHeight();
            int x = 0;
            switch (hRef) {
                case LEFT:
                    x = xy[0] + hDelta;
                    break;
                case RIGHT:
                    x = xy[0] + w + hDelta;
                    break;
                case CENTER:
                    x = xy[0] + w / 2 + hDelta;
                    break;
            }
            int y = 0;
            switch (vRef) {
                case TOP:
                    y = xy[1] + vDelta;
                    break;
                case BOTTOM:
                    y = xy[1] + h + vDelta;
                    break;
                case CENTER:
                    y = xy[1] + h / 2 + vDelta;
                    break;
            }
            return new float[]{x, y};
        }
    }
}
