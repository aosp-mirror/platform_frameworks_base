/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package android.windowanimationjank;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;

/**
 * Set of helpers to manipulate test activities.
 */
public class Utils {
    protected final static String PACKAGE = "android.windowanimationjank";
    protected final static String ELEMENT_LAYOUT_ACTIVITY = "ElementLayoutActivity";
    protected final static String ELEMENT_LAYOUT_CLASS = PACKAGE + "." + ELEMENT_LAYOUT_ACTIVITY;
    protected final static long WAIT_FOR_ACTIVITY_TIMEOUT = 10000;
    private static final BySelector ROOT_ELEMENT_LAYOUT = By.res(PACKAGE, "root_flow_layout");

    private final static long ROTATION_ANIMATION_TIME_FULL_SCREEN_MS = 1000;

    protected final static int ROTATION_MODE_NATURAL = 0;
    protected final static int ROTATION_MODE_LEFT = 1;
    protected final static int ROTATION_MODE_RIGHT = 2;

    private static UiObject2 waitForActivity(Instrumentation instrumentation, BySelector selector) {
        UiDevice device = UiDevice.getInstance(instrumentation);
        UiObject2 window = device.wait(Until.findObject(selector), WAIT_FOR_ACTIVITY_TIMEOUT);
        if (window == null) {
            throw new RuntimeException(selector.toString() + " has not been started.");
        }

        // Get root object.
        while (window.getParent() != null) {
            window = window.getParent();
        }
        return window;
    }

    public static UiObject2 waitForElementLayout(Instrumentation instrumentation) {
        return waitForActivity(instrumentation, ROOT_ELEMENT_LAYOUT);
    }

    /**
     * Start and return activity with requested number of random elements.
     */
    public static UiObject2 startElementLayout(Instrumentation instrumentation, int numElements) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(PACKAGE, ELEMENT_LAYOUT_CLASS));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(ElementLayoutActivity.NUM_ELEMENTS_KEY, numElements);
        instrumentation.getTargetContext().startActivity(intent);
        return waitForElementLayout(instrumentation);
    }

    public static int getDeviceRotation(Instrumentation instrumentation) {
        try {
            UiDevice device = UiDevice.getInstance(instrumentation);
            switch (device.getDisplayRotation()) {
            case UiAutomation.ROTATION_FREEZE_90:
                return ROTATION_MODE_LEFT;
            case UiAutomation.ROTATION_FREEZE_270:
                return ROTATION_MODE_RIGHT;
            case UiAutomation.ROTATION_FREEZE_0:
            case UiAutomation.ROTATION_FREEZE_180:
                return ROTATION_MODE_NATURAL;
            }
        } catch(Exception e) {
            throw new RuntimeException();
        }
        throw new RuntimeException("Unsupported device rotation.");
    }

    public static void rotateDevice(Instrumentation instrumentation, int rotationMode) {
        try {
            UiDevice device = UiDevice.getInstance(instrumentation);
            long startTime = System.currentTimeMillis();
            switch (rotationMode) {
            case ROTATION_MODE_NATURAL:
                device.setOrientationNatural();
                break;
            case ROTATION_MODE_LEFT:
                device.setOrientationLeft();
                break;
            case ROTATION_MODE_RIGHT:
                device.setOrientationRight();
                break;
            default:
                throw new RuntimeException("Unsupported rotation mode: " + rotationMode);
            }

            long toSleep = ROTATION_ANIMATION_TIME_FULL_SCREEN_MS -
                    (System.currentTimeMillis() - startTime);
            if (toSleep > 0) {
                SystemClock.sleep(toSleep);
            }
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
