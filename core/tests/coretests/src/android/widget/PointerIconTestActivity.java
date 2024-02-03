/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.Activity;
import android.os.Bundle;

import com.android.frameworks.coretests.R;

public class PointerIconTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pointer_icon_test);

        RadialTimePickerView timePicker = findViewById(R.id.timepicker);
        // Set the time of TimePicker to 0:00 so that the test is stable.
        timePicker.setCurrentHour(0);
        timePicker.setCurrentMinute(0);
    }
}
