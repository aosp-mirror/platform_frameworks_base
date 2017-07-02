/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.showwhenlocked;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Sample app to test the manifest attrs {@link android.R.attr#showWhenLocked}
 * and {@link android.R.attr#turnScreenOn}.
 *
 * <p>Run with adb shell am start -n com.android.showwhenlocked/.ShowWhenLockedActivity to test
 * when the phone has a keyguard enabled and/or the screen is off.
 *
 * Use the extra {@link #EXTRA_SHOW_WHEN_LOCKED} and {@link #EXTRA_TURN_SCREEN_ON} to test
 * multiple scenarios.
 *
 * Ex: adb shell am start -n com.android.showwhenlocked/.ShowWhenLockedActivity --ez \
 *      showWhenLocked false \
 *      setTurnScreenOnAtStop false
 *
 * Note: Behavior may change if values are set to true after the Activity is already created
 * and only brought to the front. For example, turnScreenOn only takes effect on the next launch
 * if set using the extra value.
 */
public class ShowWhenLockedActivity extends Activity {
    private static final String TAG = ShowWhenLockedActivity.class.getSimpleName();

    /**
     * The value set for this extra sets {@link #setShowWhenLocked(boolean)} as soon as the app
     * is launched. This may cause delays in when the value set takes affect.
     */
    private static final String EXTRA_SHOW_WHEN_LOCKED = "showWhenLocked";

    /**
     * The value set for this extra sets {@link #setTurnScreenOn(boolean)} as soon as the app
     * is launched. This may cause delays in when the value set takes affect.
     */
    private static final String EXTRA_TURN_SCREEN_ON = "turnScreenOn";

    /**
     * The value set for this extra will call {@link #setShowWhenLocked(boolean)} at onStop so
     * it take effect on the next launch.
     */
    private static final String EXTRA_SHOW_WHEN_LOCKED_STOP = "setShowWhenLockedAtStop";

    /**
     * The value set for this extra will call {@link #setTurnScreenOn(boolean)} at onStop so
     * it take effect on the next launch.
     */
    private static final String EXTRA_TURN_SCREEN_ON_STOP = "setTurnScreenOnAtStop";

    /**
     * The value set for this extra will call
     * {@link KeyguardManager#requestDismissKeyguard(Activity, KeyguardManager.KeyguardDismissCallback)}
     * as soon as the app is launched.
     */
    private static final String EXTRA_DISMISS_KEYGUARD = "dismissKeyguard";

    private boolean showWhenLockedAtStop = true;
    private boolean turnScreenOnAtStop = true;

    private KeyguardManager mKeyguardManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(TAG, "onCreate");
        mKeyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        handleExtras(getIntent().getExtras());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleExtras(intent.getExtras());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.v(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.v(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.v(TAG, "onStop");

        setShowWhenLocked(showWhenLockedAtStop);
        setTurnScreenOn(turnScreenOnAtStop);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "onDestroy");
    }

    private void handleExtras(Bundle extras) {
        if (extras == null) {
            return;
        }

        if (extras.containsKey(EXTRA_SHOW_WHEN_LOCKED)) {
            boolean showWhenLocked = extras.getBoolean(EXTRA_SHOW_WHEN_LOCKED, true);
            Log.v(TAG, "Setting showWhenLocked to " + showWhenLocked);
            setShowWhenLocked(showWhenLocked);
        }

        if (extras.containsKey(EXTRA_TURN_SCREEN_ON)) {
            boolean turnScreenOn = extras.getBoolean(EXTRA_TURN_SCREEN_ON, true);
            Log.v(TAG, "Setting turnScreenOn to " + turnScreenOn);
            setTurnScreenOn(turnScreenOn);
        }

        if (extras.containsKey(EXTRA_SHOW_WHEN_LOCKED_STOP)) {
            showWhenLockedAtStop = extras.getBoolean(EXTRA_SHOW_WHEN_LOCKED_STOP, true);
            Log.v(TAG, "Setting showWhenLockedAtStop to " + showWhenLockedAtStop);
        }

        if (extras.containsKey(EXTRA_TURN_SCREEN_ON_STOP)) {
            turnScreenOnAtStop = extras.getBoolean(EXTRA_TURN_SCREEN_ON_STOP, true);
            Log.v(TAG, "Setting turnScreenOnAtStop to " + turnScreenOnAtStop);
        }

        if (extras.containsKey(EXTRA_DISMISS_KEYGUARD)) {
            if (extras.getBoolean(EXTRA_DISMISS_KEYGUARD, false)) {
                Log.v(TAG, "Requesting dismiss keyguard");
                mKeyguardManager.requestDismissKeyguard(this, null);
            }
        }
    }
}

