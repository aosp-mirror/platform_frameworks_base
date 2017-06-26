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

import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.action.CoordinatesProvider;
import android.support.test.espresso.action.MotionEvents;
import android.support.test.espresso.action.MotionEvents.DownResultHolder;
import android.support.test.espresso.action.Press;
import android.support.test.espresso.action.Tapper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.hamcrest.Matcher;

/**
 * ViewAction for performing an click on View by a mouse.
 */
public final class MouseClickAction implements ViewAction {
    private final ViewClickAction mViewClickAction;
    @MouseUiController.MouseButton
    private final int mButton;

    public enum CLICK implements Tapper {
        TRIPLE {
            @Override
            public Tapper.Status sendTap(UiController uiController, float[] coordinates,
                    float[] precision, int inputDevice, int buttonState) {
                Tapper.Status stat = sendSingleTap(uiController, coordinates, precision,
                        inputDevice, buttonState);
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
                    stat = sendSingleTap(uiController, coordinates, precision, inputDevice,
                            buttonState);
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

            @Override
            public Tapper.Status sendTap(UiController uiController, float[] coordinates,
                    float[] precision) {
                return sendTap(uiController, coordinates, precision, InputDevice.SOURCE_UNKNOWN,
                        MotionEvent.BUTTON_PRIMARY);
            }
        };

        private static Tapper.Status sendSingleTap(UiController uiController,
                float[] coordinates, float[] precision, int inputDevice, int buttonState) {
            DownResultHolder res = MotionEvents.sendDown(uiController, coordinates, precision,
                    inputDevice, buttonState);
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
        this(tapper, coordinatesProvider, MotionEvent.BUTTON_PRIMARY);
    }

    /**
     * Constructs MouseClickAction
     *
     * @param tapper the tapper
     * @param coordinatesProvider the provider of the event coordinates
     * @param button the mouse button used to send motion events
     */
    public MouseClickAction(Tapper tapper, CoordinatesProvider coordinatesProvider,
            @MouseUiController.MouseButton int button) {
        mViewClickAction = new ViewClickAction(tapper, coordinatesProvider, Press.PINPOINT);
        mButton = button;
    }

    @Override
    public Matcher<View> getConstraints() {
        return mViewClickAction.getConstraints();
    }

    @Override
    public String getDescription() {
        return mViewClickAction.getDescription();
    }

    @Override
    public void perform(UiController uiController, View view) {
        mViewClickAction.perform(new MouseUiController(uiController, mButton), view);
    }
}
