/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.res.Resources;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.Until;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;

final class FloatingToolbarUtils {

    private final UiDevice mDevice;
    private static final BySelector TOOLBAR_CONTAINER_SELECTOR =
            By.res("android", "floating_popup_container");

    FloatingToolbarUtils() {
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    void waitForFloatingToolbarPopup() {
        mDevice.wait(Until.findObject(TOOLBAR_CONTAINER_SELECTOR), 500);
    }

    void assertFloatingToolbarIsDisplayed() {
        waitForFloatingToolbarPopup();
        assertThat(mDevice.hasObject(TOOLBAR_CONTAINER_SELECTOR)).isTrue();
    }

    void assertFloatingToolbarContainsItem(String itemLabel) {
        waitForFloatingToolbarPopup();
        assertWithMessage("Expected to find item labelled [" + itemLabel + "]")
                .that(mDevice.hasObject(
                        TOOLBAR_CONTAINER_SELECTOR.hasDescendant(By.text(itemLabel))))
                .isTrue();
    }

    void assertFloatingToolbarDoesNotContainItem(String itemLabel) {
        waitForFloatingToolbarPopup();
        assertWithMessage("Expected to not find item labelled [" + itemLabel + "]")
                .that(mDevice.hasObject(
                        TOOLBAR_CONTAINER_SELECTOR.hasDescendant(By.text(itemLabel))))
                .isFalse();
    }

    void assertFloatingToolbarContainsItemAtIndex(String itemLabel, int index) {
        waitForFloatingToolbarPopup();
        assertWithMessage("Expected to find item labelled [" + itemLabel + "] at index " + index)
                .that(mDevice.findObject(TOOLBAR_CONTAINER_SELECTOR)
                        .findObjects(By.clickable(true))
                        .get(index)
                        .getChildren()
                        .get(1)
                        .getText())
                .isEqualTo(itemLabel);
    }

    void clickFloatingToolbarItem(String label) {
        waitForFloatingToolbarPopup();
        mDevice.findObject(TOOLBAR_CONTAINER_SELECTOR)
                .findObject(By.text(label))
                .click();
    }

    void clickFloatingToolbarOverflowItem(String label) {
        // TODO: There might be a benefit to combining this with "clickFloatingToolbarItem" method.
        waitForFloatingToolbarPopup();
        mDevice.findObject(TOOLBAR_CONTAINER_SELECTOR)
                .findObject(By.desc(str(R.string.floating_toolbar_open_overflow_description)))
                .click();
        mDevice.wait(
                Until.findObject(TOOLBAR_CONTAINER_SELECTOR.hasDescendant(By.text(label))),
                1000);
        mDevice.findObject(TOOLBAR_CONTAINER_SELECTOR)
                .findObject(By.text(label))
                .click();
    }

    private static String str(int id) {
        return Resources.getSystem().getString(id);
    }
}
