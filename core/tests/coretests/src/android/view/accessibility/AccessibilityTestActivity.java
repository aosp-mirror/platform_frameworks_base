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

package android.view.accessibility;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;

import com.android.frameworks.coretests.R;

/**
 * An activity for accessibility test.
 */
public class AccessibilityTestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        turnOnScreen();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.accessibility_test);
    }

    private void turnOnScreen() {
        setTurnScreenOn(true);
        setShowWhenLocked(true);
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(
                Context.KEYGUARD_SERVICE);
        keyguardManager.requestDismissKeyguard(this, null);
    }
}
