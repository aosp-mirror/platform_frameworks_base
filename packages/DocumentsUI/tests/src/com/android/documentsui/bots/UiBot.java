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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;

import com.android.documentsui.R;

import java.util.Iterator;
import java.util.List;

/**
 * A test helper class that provides support for controlling DocumentsUI activities
 * programmatically, and making assertions against the state of the UI.
 *
 * <p>Support for working directly with Roots and Directory view can be found
 * in the respective bots.
 */
public class UiBot extends BaseBot {
    public static final String TARGET_PKG = "com.android.documentsui";
    private static final String LAUNCHER_PKG = "com.android.launcher";

    public UiBot(UiDevice device, Context context, int timeout) {
        super(device, context, timeout);
    }

    public void assertWindowTitle(String expected) {
        // Turns out the title field on a window does not have
        // an id associated with it at runtime (which confuses the hell out of me)
        // In code we address this via "android.R.id.title".
        UiObject2 o = find(By.text(expected));
        // It's a bit of a conceit that we then *assert* that the title
        // is the value that we used to identify the UiObject2.
        // If the preceeding lookup fails, this'll choke with an NPE.
        // But given the issue described in the comment above, we're
        // going to do it anyway. Because we shouldn't be looking up
        // the uiobject by it's expected content :|
        assertEquals(expected, o.getText());
    }

    public void assertMenuEnabled(int id, boolean enabled) {
        UiObject2 menu= findMenuWithName(mContext.getString(id));
        assertNotNull(menu);
        assertEquals(enabled, menu.isEnabled());
    }

    public void assertSearchTextField(boolean isFocused, String query)
            throws UiObjectNotFoundException {
        UiObject textField = findSearchViewTextField();
        UiObject searchIcon = findSearchViewIcon();

        assertFalse(searchIcon.exists());
        assertTrue(textField.exists());
        assertEquals(isFocused, textField.isFocused());
        if(query != null) {
            assertEquals(query, textField.getText());
        }
    }

    public void assertSearchTextFiledAndIcon(boolean searchTextFieldExists, boolean searchIconExists) {
        assertEquals(searchTextFieldExists, findSearchViewTextField().exists());
        assertEquals(searchIconExists, findSearchViewIcon().exists());
    }

    public void assertInActionMode(boolean inActionMode) {
        UiObject actionModeBar = findActionModeBar();
        assertEquals(inActionMode, actionModeBar.exists());
    }

    public void openSearchView() throws UiObjectNotFoundException {
        UiObject searchView = findSearchView();
        searchView.click();
        assertTrue(searchView.exists());
    }

    public void setSearchQuery(String query) throws UiObjectNotFoundException {
        UiObject searchView = findSearchView();
        assertTrue(searchView.exists());
        UiObject searchTextField = findSearchViewTextField();
        searchTextField.setText(query);
        assertSearchTextField(true, query);
    }

    public UiObject openOverflowMenu() throws UiObjectNotFoundException {
        UiObject obj = findMenuMoreOptions();
        obj.click();
        mDevice.waitForIdle(mTimeout);
        return obj;
    }

    public void setDialogText(String text) throws UiObjectNotFoundException {
        findDialogEditText().setText(text);
    }

    void switchViewMode() {
        UiObject2 mode = menuGridMode();
        if (mode != null) {
            mode.click();
        } else {
            menuListMode().click();
        }
    }

    UiObject2 menuGridMode() {
        // Note that we're using By.desc rather than By.res, because of b/25285770
        return find(By.desc("Grid view"));
    }

        UiObject2 menuListMode() {
        // Note that we're using By.desc rather than By.res, because of b/25285770
        return find(By.desc("List view"));
    }

    public UiObject2 menuDelete() {
        return find(By.res("com.android.documentsui:id/menu_delete"));
    }

    public UiObject2 menuShare() {
        return find(By.res("com.android.documentsui:id/menu_share"));
    }

    public UiObject2 menuRename() {
        return findMenuWithName(mContext.getString(R.string.menu_rename));
    }

    public UiObject2 menuNewFolder() {
        return findMenuWithName(mContext.getString(R.string.menu_create_dir));
    }

    UiObject findSearchView() {
        return findObject("com.android.documentsui:id/menu_search");
    }

    UiObject findSearchViewTextField() {
        return findObject("com.android.documentsui:id/menu_search", "android:id/search_src_text");
    }

    UiObject findSearchViewIcon() {
        return mContext.getResources().getBoolean(R.bool.full_bar_search_view)
                ? findObject("com.android.documentsui:id/menu_search")
                : findObject("com.android.documentsui:id/menu_search", "android:id/search_button");
    }

    UiObject findActionModeBar() {
        return findObject("android:id/action_mode_bar");
    }

    public UiObject findDialogEditText() {
        return findObject("android:id/content", "android:id/text1");
    }

    public UiObject findDownloadRetryDialog() {
        UiSelector selector = new UiSelector().text("Couldn't download");
        UiObject title = mDevice.findObject(selector);
        title.waitForExists(mTimeout);
        return title;
    }

    public UiObject findDialogOkButton() {
        UiObject object = findObject("android:id/content", "android:id/button1");
        object.waitForExists(mTimeout);
        return object;
    }

    public UiObject findDialogCancelButton() {
        UiObject object = findObject("android:id/content", "android:id/button2");
        object.waitForExists(mTimeout);
        return object;
    }

    UiObject findMenuLabelWithName(String label) {
        UiSelector selector = new UiSelector().text(label);
        return mDevice.findObject(selector);
    }

    UiObject2 findMenuWithName(String label) {
        List<UiObject2> menuItems = mDevice.findObjects(By.clazz("android.widget.LinearLayout"));
        Iterator<UiObject2> it = menuItems.iterator();

        UiObject2 menuItem = null;
        while(it.hasNext()) {
            menuItem = it.next();
            UiObject2 text = menuItem.findObject(By.text(label));
            if(text != null) {
                break;
            }
        }
        return menuItem;
    }

    UiObject findMenuMoreOptions() {
        UiSelector selector = new UiSelector().className("android.widget.ImageButton")
                .descriptionContains("More options");
        //TODO: use the system string ? android.R.string.action_menu_overflow_description
        return mDevice.findObject(selector);
    }

    public void pressKey(int keyCode) {
        mDevice.pressKeyCode(keyCode);
    }
}
