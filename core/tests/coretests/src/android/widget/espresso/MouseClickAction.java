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
import android.support.test.espresso.action.PrecisionDescriber;
import android.support.test.espresso.action.Tapper;
import android.view.View;

/**
 * ViewAction for performing an click on View by a mouse.
 */
public final class MouseClickAction implements ViewAction {
    private final GeneralClickAction mGeneralClickAction;

    public MouseClickAction(Tapper tapper, CoordinatesProvider coordinatesProvider,
            PrecisionDescriber precisionDescriber) {
        mGeneralClickAction = new GeneralClickAction(tapper, coordinatesProvider,
                precisionDescriber);
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
    }
}
