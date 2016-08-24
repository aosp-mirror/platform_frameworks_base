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
 * limitations under the License.
 */

package com.android.documentsui.bots;

import android.content.Context;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiScrollable;
import android.support.test.uiautomator.UiSelector;
import android.util.Log;

import junit.framework.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A test helper class that provides support for controlling and asserting against
 * the roots list drawer.
 */
public class RootsListBot extends BaseBot {
    private static final String ROOTS_LIST_ID = "com.android.documentsui:id/roots_list";
    private static final String TAG = "RootsListBot";

    public RootsListBot(UiDevice device, Context context, int timeout) {
        super(device, context, timeout);
    }

    private UiObject findRoot(String label) throws UiObjectNotFoundException {
        final UiSelector rootsList = new UiSelector().resourceId(
                "com.android.documentsui:id/container_roots").childSelector(
                new UiSelector().resourceId(ROOTS_LIST_ID));

        // We might need to expand drawer if not visible
        if (!new UiObject(rootsList).waitForExists(mTimeout)) {
            Log.d(TAG, "Failed to find roots list; trying to expand");
            final UiSelector hamburger = new UiSelector().resourceId(
                    "com.android.documentsui:id/toolbar").childSelector(
                    new UiSelector().className("android.widget.ImageButton").clickable(true));
            new UiObject(hamburger).click();
        }

        // Wait for the first list item to appear
        new UiObject(rootsList.childSelector(new UiSelector())).waitForExists(mTimeout);

        // Now scroll around to find our item
        new UiScrollable(rootsList).scrollIntoView(new UiSelector().text(label));
        return new UiObject(rootsList.childSelector(new UiSelector().text(label)));
    }

    public void openRoot(String label) throws UiObjectNotFoundException {
        findRoot(label).click();
        mDevice.waitForIdle();
    }

    public void assertRootsPresent(String... labels) throws UiObjectNotFoundException {
        List<String> missing = new ArrayList<>();
        for (String label : labels) {
            if (!findRoot(label).exists()) {
                missing.add(label);
            }
        }
        if (!missing.isEmpty()) {
            Assert.fail(
                    "Expected roots " + Arrays.asList(labels) + ", but missing " + missing);
        }
    }

    public void assertRootsAbsent(String... labels) throws UiObjectNotFoundException {
        List<String> unexpected = new ArrayList<>();
        for (String label : labels) {
            if (findRoot(label).exists()) {
                unexpected.add(label);
            }
        }
        if (!unexpected.isEmpty()) {
            Assert.fail("Unexpected roots " + unexpected);
        }
    }

    public void assertHasFocus() {
        assertHasFocus(ROOTS_LIST_ID);
    }
}
