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

package com.android.documentsui;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertFalse;

import android.content.Context;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiScrollable;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.util.Log;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

import junit.framework.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A test helper class that provides support for controlling DocumentsUI activities
 * programmatically, and making assertions against the state of the UI.
 */
class UiBot {

    private static final String TAG = "UiBot";

    private static final BySelector SNACK_DELETE =
            By.desc(Pattern.compile("^Deleting [0-9]+ file.+"));

    private UiDevice mDevice;
    private Context mContext;
    private int mTimeout;

    public UiBot(UiDevice device, Context context, int timeout) {
        mDevice = device;
        mContext = context;
        mTimeout = timeout;
    }

    UiObject findRoot(String label) throws UiObjectNotFoundException {
        final UiSelector rootsList = new UiSelector().resourceId(
                "com.android.documentsui:id/container_roots").childSelector(
                new UiSelector().resourceId("android:id/list"));

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

    void openRoot(String label) throws UiObjectNotFoundException {
        findRoot(label).click();
        mDevice.waitForIdle();
    }

    void assertWindowTitle(String expected) {
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

    void assertHasRoots(String... labels) throws UiObjectNotFoundException {
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

    void assertMenuEnabled(int id, boolean enabled) {
        UiObject2 menu= findMenuWithName(mContext.getString(id));
        assertNotNull(menu);
        assertEquals(enabled, menu.isEnabled());
    }

    void assertDocumentsCount(int count) throws UiObjectNotFoundException {
        UiObject docsList = findDocumentsList();
        assertEquals(count, docsList.getChildCount());
    }

    void assertDocumentsCount(String dir, int count) throws UiObjectNotFoundException {
        openRoot(dir);
        UiObject docsList = findDocumentsList();
        assertEquals(count, docsList.getChildCount());
    }

    void assertSearchTextField(boolean isFocused, String query)
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

    void assertSearchTextFiledAndIcon(boolean searchTextFieldExists, boolean searchIconExists) {
        assertEquals(searchTextFieldExists, findSearchViewTextField().exists());
        assertEquals(searchIconExists, findSearchViewIcon().exists());
    }

    void assertHasDocuments(String... labels) throws UiObjectNotFoundException {
        List<String> missing = new ArrayList<>();
        for (String label : labels) {
            if (!findDocument(label).exists()) {
                missing.add(label);
            }
        }
        if (!missing.isEmpty()) {
            Assert.fail(
                    "Expected documents " + Arrays.asList(labels) + ", but missing " + missing);
        }
    }

    void assertDocument(String name, boolean exists) throws UiObjectNotFoundException {
        UiObject doc = findDocument(name);
        assertEquals(exists, doc.exists());
    }

    void assertDocumentsCountOnList(boolean exists, int count) throws UiObjectNotFoundException {
        UiObject docsList = findDocumentsList();
        assertEquals(exists, docsList.exists());
        if(docsList.exists()) {
            assertEquals(count, docsList.getChildCount());
        }
    }

    void assertMessageTextView(String message) throws UiObjectNotFoundException {
        UiObject messageTextView = findMessageTextView();
        assertTrue(messageTextView.exists());

        String msg = String.valueOf(message);
        assertEquals(String.format(msg, "TEST_ROOT_0"), messageTextView.getText());

    }
    void assertSnackbar(int id) {
        assertNotNull(getSnackbar(mContext.getString(id)));
    }

    void openDocument(String label) throws UiObjectNotFoundException {
        int toolType = Configurator.getInstance().getToolType();
        Configurator.getInstance().setToolType(MotionEvent.TOOL_TYPE_FINGER);
        UiObject doc = findDocument(label);
        doc.click();
        Configurator.getInstance().setToolType(toolType);
    }

    void clickDocument(String label) throws UiObjectNotFoundException {
        findDocument(label).click();
    }

    void openSearchView() throws UiObjectNotFoundException {
        UiObject searchView = findSearchView();
        searchView.click();
        assertTrue(searchView.exists());
    }

    void setSearchQuery(String query) throws UiObjectNotFoundException {
        UiObject searchView = findSearchView();
        assertTrue(searchView.exists());
        UiObject searchTextField = findSearchViewTextField();
        searchTextField.setText(query);
        assertSearchTextField(true, query);
    }

    UiObject openOverflowMenu() throws UiObjectNotFoundException {
        UiObject obj = findMenuMoreOptions();
        obj.click();
        mDevice.waitForIdle(mTimeout);
        return obj;
    }

    void openDialog(int id) {
        UiObject2 menu= findMenuWithName(mContext.getString(id));
        assertNotNull(menu);
        assertEquals(true, menu.isEnabled());
        menu.click();
    }

    void setDialogText(String text) throws UiObjectNotFoundException {
        findDialogEditText().setText(text);
    }

    UiObject selectDocument(String label) throws UiObjectNotFoundException {
        UiObject doc = findDocument(label);
        doc.longClick();
        return doc;
    }

    UiObject2 getSnackbar(String message) {
        return mDevice.wait(Until.findObject(By.text(message)), mTimeout);
    }

    void waitForDeleteSnackbar() {
        mDevice.wait(Until.findObject(SNACK_DELETE), mTimeout);
    }

    void waitForDeleteSnackbarGone() {
        // wait a little longer for snackbar to go away, as it disappears after a timeout.
        mDevice.wait(Until.gone(SNACK_DELETE), mTimeout * 2);
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

    UiObject2 menuDelete() {
        return find(By.res("com.android.documentsui:id/menu_delete"));
    }

    UiObject2 menuShare() {
        return find(By.res("com.android.documentsui:id/menu_share"));
    }

    private UiObject2 find(BySelector selector) {
        mDevice.wait(Until.findObject(selector), mTimeout);
        return mDevice.findObject(selector);
    }

    private UiObject findObject(String resourceId) {
        final UiSelector object = new UiSelector().resourceId(resourceId);
        return mDevice.findObject(object);
    }

    private UiObject findObject(String parentResourceId, String childResourceId) {
        final UiSelector selector = new UiSelector()
                .resourceId(parentResourceId)
                .childSelector(new UiSelector().resourceId(childResourceId));
        return mDevice.findObject(selector);
    }

    UiObject findDocument(String label) throws UiObjectNotFoundException {
        final UiSelector docList = new UiSelector().resourceId(
                "com.android.documentsui:id/container_directory").childSelector(
                        new UiSelector().resourceId("com.android.documentsui:id/list"));

        // Wait for the first list item to appear
        new UiObject(docList.childSelector(new UiSelector())).waitForExists(mTimeout);

        // new UiScrollable(docList).scrollIntoView(new UiSelector().text(label));
        return mDevice.findObject(docList.childSelector(new UiSelector().text(label)));
    }

    boolean hasDocuments(String... labels) throws UiObjectNotFoundException {
        for (String label : labels) {
            if (!findDocument(label).exists()) {
                return false;
            }
        }
        return true;
    }

    UiObject findDocumentsList() {
        return findObject(
                "com.android.documentsui:id/container_directory",
                "com.android.documentsui:id/list");
    }

    UiObject findSearchView() {
        return findObject("com.android.documentsui:id/menu_search");
    }

    UiObject findSearchViewTextField() {
        return findObject("com.android.documentsui:id/menu_search", "android:id/search_src_text");
    }

    UiObject findSearchViewIcon() {
        return findObject("com.android.documentsui:id/menu_search", "android:id/search_button");
    }

    UiObject findMessageTextView() {
        return findObject(
                "com.android.documentsui:id/container_directory",
                "com.android.documentsui:id/message");
    }

    UiObject findActionModeBar() {
        return findObject("android:id/action_mode_bar");
    }

    UiObject findDialogEditText() {
        return findObject("android:id/content", "android:id/text1");
    }

    UiObject findRenameDialogOkButton() {
        return findObject("android:id/content", "android:id/button1");
    }

    UiObject findRenameDialogCancelButton() {
        return findObject("android:id/content", "android:id/button2");
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

    // Indirect way to detect the keyboard.
    boolean isKeyboardPresent() {
        InputMethodManager inputManager = (InputMethodManager) mContext
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        return inputManager.isAcceptingText();
    }

    void dismissKeyboardIfPresent() {
        if(isKeyboardPresent()) {
            mDevice.pressBack();
        }
    }

}
