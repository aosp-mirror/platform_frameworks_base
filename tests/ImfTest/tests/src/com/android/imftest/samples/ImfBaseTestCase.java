/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.imftest.samples;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import com.android.imftest.R;

public abstract class ImfBaseTestCase<T extends Activity> extends InstrumentationTestCase {

    /*
     * The amount of time we are willing to wait for the IME to appear after a user action
     * before we give up and fail the test.
     */
    public final long WAIT_FOR_IME = 5000;

    /*
     * Unfortunately there is now way for us to know how tall the IME is,
     * so we have to hard code a minimum and maximum value.
     */
    public final int IME_MIN_HEIGHT = 150;
    public final int IME_MAX_HEIGHT = 300;

    protected InputMethodManager mImm;
    protected T mTargetActivity;
    protected boolean mExpectAutoPop;
    private Class<T> mTargetActivityClass;

    public ImfBaseTestCase(Class<T> activityClass) {
        mTargetActivityClass = activityClass;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        final String packageName = getInstrumentation().getTargetContext().getPackageName();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        mTargetActivity = launchActivityWithIntent(packageName, mTargetActivityClass, intent);
        // expect ime to auto pop up if device has no hard keyboard
        int keyboardType = mTargetActivity.getResources().getConfiguration().keyboard;
        mExpectAutoPop = (keyboardType  == Configuration.KEYBOARD_NOKEYS ||
                keyboardType == Configuration.KEYBOARD_UNDEFINED);

        mImm = InputMethodManager.getInstance(mTargetActivity);

        KeyguardManager keyguardManager =
            (KeyguardManager) getInstrumentation().getContext().getSystemService(
                    Context.KEYGUARD_SERVICE);
        keyguardManager.newKeyguardLock("imftest").disableKeyguard();
    }
    
    // Utility test methods
    public void verifyEditTextAdjustment(final View editText, int rootViewHeight) {

        int[] origLocation = new int[2];
        int[] newLocation = new int[2];

        // Tell the keyboard to go away.
        mImm.hideSoftInputFromWindow(editText.getWindowToken(), 0);

        // Bring the target EditText into focus.
        mTargetActivity.runOnUiThread(new Runnable() {
            public void run() {
                editText.requestFocus();
            }
        });

        // Get the original location of the EditText.
        editText.getLocationOnScreen(origLocation);

        // Tap the EditText to bring up the IME.
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);

        // Wait until the EditText pops above the IME or until we hit the timeout.
        editText.getLocationOnScreen(newLocation);
        long timeoutTime = SystemClock.uptimeMillis() + WAIT_FOR_IME;
        while (newLocation[1] > rootViewHeight - IME_MIN_HEIGHT && SystemClock.uptimeMillis() < timeoutTime) {
            editText.getLocationOnScreen(newLocation);
            pause(100);
        }

        assertTrue(newLocation[1] <= rootViewHeight - IME_MIN_HEIGHT);

        // Tell the keyboard to go away.
        mImm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
    }

    public void destructiveCheckImeInitialState(View rootView, View servedView) {
        int windowSoftInputMode = mTargetActivity.getWindow().getAttributes().softInputMode;
        int adjustMode = windowSoftInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
        if (mExpectAutoPop && adjustMode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
            assertTrue(destructiveCheckImeUp(rootView, servedView));
        } else {
            assertFalse(destructiveCheckImeUp(rootView, servedView));
        }
    }

    public boolean destructiveCheckImeUp(View rootView, View servedView) {
        int origHeight;
        int newHeight;

        origHeight = rootView.getHeight();

        // Tell the keyboard to go away.
        mImm.hideSoftInputFromWindow(servedView.getWindowToken(), 0);

        // Give it five seconds to adjust
        newHeight = rootView.getHeight();
        long timeoutTime = SystemClock.uptimeMillis() + WAIT_FOR_IME;
        while (Math.abs(newHeight - origHeight) < IME_MIN_HEIGHT && SystemClock.uptimeMillis() < timeoutTime) {
            newHeight = rootView.getHeight();
        }

        return (Math.abs(origHeight - newHeight) >= IME_MIN_HEIGHT);
    }

    void pause(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
        }
    }

}
