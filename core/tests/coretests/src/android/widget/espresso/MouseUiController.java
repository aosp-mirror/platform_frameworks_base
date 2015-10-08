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

import android.support.test.espresso.InjectEventSecurityException;
import android.support.test.espresso.UiController;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Class to wrap an UiController to overwrite source of motion events to SOURCE_MOUSE.
 * Note that this doesn't change the tool type.
 */
public class MouseUiController implements UiController {
    private final UiController mUiController;

    public MouseUiController(UiController uiController) {
        mUiController = uiController;
    }

    @Override
    public boolean injectKeyEvent(KeyEvent event) throws InjectEventSecurityException {
        return mUiController.injectKeyEvent(event);
    }

    @Override
    public boolean injectMotionEvent(MotionEvent event) throws InjectEventSecurityException {
        // Modify the event to mimic mouse primary button event.
        event.setSource(InputDevice.SOURCE_MOUSE);
        event.setButtonState(MotionEvent.BUTTON_PRIMARY);
        return mUiController.injectMotionEvent(event);
    }

    @Override
    public boolean injectString(String str) throws InjectEventSecurityException {
        return mUiController.injectString(str);
    }

    @Override
    public void loopMainThreadForAtLeast(long millisDelay) {
        mUiController.loopMainThreadForAtLeast(millisDelay);
    }

    @Override
    public void loopMainThreadUntilIdle() {
        mUiController.loopMainThreadUntilIdle();
    }
}
