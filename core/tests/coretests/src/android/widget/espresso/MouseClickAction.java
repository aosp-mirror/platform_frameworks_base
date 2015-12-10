/*
 * Copyright (C) 2015 The Android Open Source Project
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

import org.hamcrest.Matcher;

import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.action.CoordinatesProvider;
import android.support.test.espresso.action.GeneralClickAction;
import android.support.test.espresso.action.MotionEvents;
import android.support.test.espresso.action.MotionEvents.DownResultHolder;
import android.support.test.espresso.action.PrecisionDescriber;
import android.support.test.espresso.action.Press;
import android.support.test.espresso.action.Tapper;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * ViewAction for performing an click on View by a mouse.
 */
public final class MouseClickAction implements ViewAction {
    private final GeneralClickAction mGeneralClickAction;

    public enum CLICK implements Tapper {
        TRIPLE {
            @Override
            public Tapper.Status sendTap(UiController uiController, float[] coordinates,
                    float[] precision) {
                Tapper.Status stat = sendSingleTap(uiController, coordinates, precision);
                boolean warning = false;
                if (stat == Tapper.Status.FAILURE) {
                    return Tapper.Status.FAILURE;
                } else if (stat == Tapper.Status.WARNING) {
                    warning = true;
                }

                long doubleTapMinimumTimeout = ViewConfiguration.getDoubleTapMinTime();
                for (int i = 0; i < 2; i++) {
                    if (0 < doubleTapMinimumTimeout) {
                        uiController.loopMainThreadForAtLeast(doubleTapMinimumTimeout);
                    }
                    stat = sendSingleTap(uiController, coordinates, precision);
                    if (stat == Tapper.Status.FAILURE) {
                        return Tapper.Status.FAILURE;
                    } else if (stat == Tapper.Status.WARNING) {
                        warning = true;
                    }
                }

                if (warning) {
                    return Tapper.Status.WARNING;
                } else {
                    return Tapper.Status.SUCCESS;
                }
            }
        };

        private static Tapper.Status sendSingleTap(UiController uiController,
                float[] coordinates, float[] precision) {
            DownResultHolder res = MotionEvents.sendDown(uiController, coordinates, precision);
            try {
                if (!MotionEvents.sendUp(uiController, res.down)) {
                    MotionEvents.sendCancel(uiController, res.down);
                    return Tapper.Status.FAILURE;
                }
            } finally {
                res.down.recycle();
            }
            return res.longPress ? Tapper.Status.WARNING : Tapper.Status.SUCCESS;
        }
    };

    public MouseClickAction(Tapper tapper, CoordinatesProvider coordinatesProvider) {
        mGeneralClickAction = new GeneralClickAction(tapper, coordinatesProvider,
                Press.PINPOINT);
    }

    @Override
    public Matcher<View> getConstraints() {
        return mGeneralClickAction.getConstraints();
    }

    @Override
    public String getDescription() {
        return mGeneralClickAction.getDescription();
    }

    @Override
    public void perform(UiController uiController, View view) {
        mGeneralClickAction.perform(new MouseUiController(uiController), view);
        long doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
        if (0 < doubleTapTimeout) {
            // Wait to avoid false gesture detection. Without this wait, consecutive clicks can be
            // detected as a triple click. e.g. 2 double clicks are detected as a triple click and
            // a single click because espresso isn't aware of triple click detection logic, which
            // is TextView specific gesture.
            uiController.loopMainThreadForAtLeast(doubleTapTimeout);
        }
    }
}
