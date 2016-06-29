/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.typeText;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.v7.recyclerview.R;

/**
 * A test helper class that provides support for controlling the search UI
 * programmatically, and making assertions against the state of the UI.
 * <p>
 * Support for working directly with Roots and Directory view can be found in the respective bots.
 */
public class SearchBot extends Bots.BaseBot {

    public static final String TARGET_PKG = "com.android.documentsui";

    public SearchBot(UiDevice device, Context context, int timeout) {
        super(device, context, timeout);
    }

    public void clickIcon() throws UiObjectNotFoundException {
        UiObject searchView = findSearchView();
        searchView.click();
        assertTrue(searchView.exists());
    }

    public void setInputText(String query) throws UiObjectNotFoundException {
        onView(Matchers.SEARCH_MENU).perform(typeText(query));
    }

    public void assertIconVisible(boolean visible) {
        if (visible) {
            assertTrue(
                    "Search icon should be visible.",
                    Matchers.present(Matchers.SEARCH_BUTTON));
        } else {
            assertFalse(
                    "Search icon should not be visible.",
                    Matchers.present(Matchers.SEARCH_BUTTON));
        }
    }

    public void assertInputEquals(String query)
            throws UiObjectNotFoundException {
        UiObject textField = findSearchViewTextField();

        assertTrue(textField.exists());
        assertEquals(query, textField.getText());
    }

    public void assertInputFocused(boolean focused)
            throws UiObjectNotFoundException {
        UiObject textField = findSearchViewTextField();

        assertTrue(textField.exists());
        assertEquals(focused, textField.isFocused());
    }

    public void assertInputExists(boolean exists)
            throws UiObjectNotFoundException {
        assertEquals(exists, findSearchViewTextField().exists());
    }

    private UiObject findSearchView() {
        return findObject("com.android.documentsui:id/menu_search");
    }

    private UiObject findSearchViewTextField() {
        return findObject("com.android.documentsui:id/menu_search", "android:id/search_src_text");
    }

    private UiObject findSearchViewIcon() {
        return mContext.getResources().getBoolean(R.bool.full_bar_search_view)
                ? findObject("com.android.documentsui:id/menu_search")
                : findObject("com.android.documentsui:id/menu_search", "android:id/search_button");
    }
}
