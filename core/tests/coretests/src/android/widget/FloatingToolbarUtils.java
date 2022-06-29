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

import static com.android.internal.widget.floatingtoolbar.FloatingToolbar.FLOATING_TOOLBAR_TAG;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.res.Resources;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.Until;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;

final class FloatingToolbarUtils {

    private final UiDevice mDevice;

    FloatingToolbarUtils() {
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    void waitForFloatingToolbarPopup() {
        mDevice.wait(Until.findObject(By.desc(FLOATING_TOOLBAR_TAG)), 500);
    }

    void assertFloatingToolbarIsDisplayed() {
        waitForFloatingToolbarPopup();
        assertThat(mDevice.hasObject(By.desc(FLOATING_TOOLBAR_TAG))).isTrue();
    }

    void assertFloatingToolbarContainsItem(String itemLabel) {
        waitForFloatingToolbarPopup();
        assertWithMessage("Expected to find item labelled [" + itemLabel + "]")
                .that(mDevice.hasObject(
                        By.desc(FLOATING_TOOLBAR_TAG).hasDescendant(By.text(itemLabel))))
                .isTrue();
    }

    void assertFloatingToolbarDoesNotContainItem(String itemLabel) {
        waitForFloatingToolbarPopup();
        assertWithMessage("Expected to not find item labelled [" + itemLabel + "]")
                .that(mDevice.hasObject(
                        By.desc(FLOATING_TOOLBAR_TAG).hasDescendant(By.text(itemLabel))))
                .isFalse();
    }

    void assertFloatingToolbarContainsItemAtIndex(String itemLabel, int index) {
        waitForFloatingToolbarPopup();
        assertWithMessage("Expected to find item labelled [" + itemLabel + "] at index " + index)
                .that(mDevice.findObject(By.desc(FLOATING_TOOLBAR_TAG))
                        .findObjects(By.clickable(true))
                        .get(index)
                        .getChildren()
                        .get(1)
                        .getText())
                .isEqualTo(itemLabel);
    }

    void clickFloatingToolbarItem(String label) {
        waitForFloatingToolbarPopup();
        mDevice.findObject(By.desc(FLOATING_TOOLBAR_TAG))
                .findObject(By.text(label))
                .click();
    }

    void clickFloatingToolbarOverflowItem(String label) {
        // TODO: There might be a benefit to combining this with "clickFloatingToolbarItem" method.
        waitForFloatingToolbarPopup();
        mDevice.findObject(By.desc(FLOATING_TOOLBAR_TAG))
                .findObject(By.desc(str(R.string.floating_toolbar_open_overflow_description)))
                .click();
        mDevice.wait(
                Until.findObject(By.desc(FLOATING_TOOLBAR_TAG).hasDescendant(By.text(label))),
                1000);
        mDevice.findObject(By.desc(FLOATING_TOOLBAR_TAG))
                .findObject(By.text(label))
                .click();
    }

    private static String str(int id) {
        return Resources.getSystem().getString(id);
    }
}
