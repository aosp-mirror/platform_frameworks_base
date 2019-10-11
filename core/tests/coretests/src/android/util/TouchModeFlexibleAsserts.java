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

package android.util;

import android.test.InstrumentationTestCase;
import android.test.TouchUtils;
import android.view.View;

import junit.framework.Assert;

/**
 * When entering touch mode via touch, the tests can be flaky.  These asserts
 * are more flexible (allowing up to MAX_ATTEMPTS touches to enter touch mode via touch or
 * tap) until we can find a way to solve the flakiness.
 */
public class TouchModeFlexibleAsserts {

    private static int MAX_ATTEMPTS = 2;

    private static int MAX_DELAY_MILLIS = 2000;

    public static void assertInTouchModeAfterClick(InstrumentationTestCase test, View viewToTouch) {
        int numAttemptsAtTouchMode = 0;
        while (numAttemptsAtTouchMode < MAX_ATTEMPTS &&
                !viewToTouch.isInTouchMode()) {
            TouchUtils.clickView(test, viewToTouch);
            numAttemptsAtTouchMode++;
        }
        Assert.assertTrue("even after " + MAX_ATTEMPTS + " clicks, did not enter "
                + "touch mode", viewToTouch.isInTouchMode());
        //Assert.assertEquals("number of touches to enter touch mode", 1, numAttemptsAtTouchMode);
    }

    public static void assertInTouchModeAfterTap(InstrumentationTestCase test, View viewToTouch) {
        int numAttemptsAtTouchMode = 0;
        while (numAttemptsAtTouchMode < MAX_ATTEMPTS &&
                !viewToTouch.isInTouchMode()) {
            TouchUtils.tapView(test, viewToTouch);
            numAttemptsAtTouchMode++;
        }
        Assert.assertTrue("even after " + MAX_ATTEMPTS + " taps, did not enter "
                + "touch mode", viewToTouch.isInTouchMode());
        //Assert.assertEquals("number of touches to enter touch mode", 1, numAttemptsAtTouchMode);
    }

    public static void assertNotInTouchModeAfterKey(InstrumentationTestCase test, int keyCode, View checkForTouchMode) {
        test.sendKeys(keyCode);
        int amountLeft = MAX_DELAY_MILLIS;

        while (checkForTouchMode.isInTouchMode() && amountLeft > 0) {
            amountLeft -= 200;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        Assert.assertFalse("even after waiting " + MAX_DELAY_MILLIS + " millis after " 
                + "pressing key event, still in touch mode", checkForTouchMode.isInTouchMode());
    }
}
