/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.android.frameworks.coretests.R;

/**
 * Tries to set the brightness to 0. Should be silently thwarted by the framework.
 */
public class BrightnessLimit extends Activity implements OnClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.brightness_limit);

        Button b = findViewById(R.id.go);
        b.setOnClickListener(this);
    }

    public void onClick(View v) {
        DisplayManager dm = getSystemService(DisplayManager.class);
        final int displayId = getBaseContext().getDisplay().getDisplayId();
        dm.setTemporaryBrightness(displayId, 0.0f);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 0);
    }
}

