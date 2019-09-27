/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm.flicker.helpers;

import static com.android.server.wm.flicker.helpers.AutomationUtils.getPipWindowSelector;

import android.app.Instrumentation;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;

public class PipAppHelper extends FlickerAppHelper {

    public PipAppHelper(Instrumentation instr) {
        super(instr, "PipApp");
    }

    public void clickEnterPipButton(UiDevice device) {
        UiObject2 enterPipButton = device.findObject(By.res(getPackage(), "enter_pip"));
        enterPipButton.click();
        UiObject2 pipWindow = device.wait(Until.findObject(getPipWindowSelector()), sFindTimeout);

        if (pipWindow == null) {
            throw new RuntimeException("Unable to find PIP window");
        }
    }

}
