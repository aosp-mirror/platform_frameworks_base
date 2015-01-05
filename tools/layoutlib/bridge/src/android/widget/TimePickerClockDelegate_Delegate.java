/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.widget;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.view.KeyEvent;

/**
 * Delegate used to provide new implementation of few methods in {@link TimePickerClockDelegate}.
 */
public class TimePickerClockDelegate_Delegate {

    // Copied from TimePickerClockDelegate.
    private static final int AM = 0;
    private static final int PM = 1;

    @LayoutlibDelegate
    static int getAmOrPmKeyCode(TimePickerClockDelegate tpcd, int amOrPm) {
        // We don't care about locales here.
        if (amOrPm == AM) {
            return KeyEvent.KEYCODE_A;
        } else if (amOrPm == PM) {
            return KeyEvent.KEYCODE_P;
        } else {
            assert false : "amOrPm value in TimePickerSpinnerDelegate can only be 0 or 1";
            return -1;
        }
    }
}
