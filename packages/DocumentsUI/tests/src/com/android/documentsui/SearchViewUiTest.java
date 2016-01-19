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

package com.android.documentsui;

import static com.android.documentsui.StubProvider.DEFAULT_AUTHORITY;
import static com.android.documentsui.StubProvider.ROOT_0_ID;
import static com.android.documentsui.StubProvider.ROOT_1_ID;

import android.app.Instrumentation;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.Until;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.MotionEvent;

import com.android.documentsui.model.RootInfo;

@LargeTest
public class SearchViewUiTest extends InstrumentationTestCase {

    private static final int TIMEOUT = 5000;
    private static final String TAG = "SearchViewUiTest";
    private static final String TARGET_PKG = "com.android.documentsui";
    private static final String LAUNCHER_PKG = "com.android.launcher";

    private UiBot mBot;
    private UiDevice mDevice;
    private Context mContext;
    private ContentResolver mResolver;
    private DocumentsProviderHelper mDocsHelper;
    private ContentProviderClient mClient;
    private RootInfo mRoot_0;
    private RootInfo mRoot_1;

    private UiObject mSearchView;
    private UiObject mSearchTextField;
    private UiObject mDocsList;
    private UiObject mMessageTextView;
    private UiObject mSearchIcon;

    public void setUp() throws Exception {
        // Initialize UiDevice instance.
        Instrumentation instrumentation = getInstrumentation();

        mDevice = UiDevice.getInstance(instrumentation);

        Configurator.getInstance().setToolType(MotionEvent.TOOL_TYPE_MOUSE);

        // Start from the home screen.
        mDevice.pressHome();
        mDevice.wait(Until.hasObject(By.pkg(LAUNCHER_PKG).depth(0)), TIMEOUT);

        // NOTE: Must be the "target" context, else security checks in content provider will fail.
        mContext = instrumentation.getTargetContext();
        mResolver = mContext.getContentResolver();

        mClient = mResolver.acquireUnstableContentProviderClient(DEFAULT_AUTHORITY);
        mDocsHelper = new DocumentsProviderHelper(DEFAULT_AUTHORITY, mClient);

        // Launch app.
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(TARGET_PKG);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(intent);
        // Wait for the app to appear.
        mDevice.wait(Until.hasObject(By.pkg(TARGET_PKG).depth(0)), TIMEOUT);
        mDevice.waitForIdle();

        mBot = new UiBot(mDevice, TIMEOUT);

        resetStorage(); // Just incase a test failed and tearDown didn't happen.

        initUiObjects();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mDevice.pressBack();
        resetStorage();
        mClient.release();
    }

    private void resetStorage() throws RemoteException {
        mClient.call("clear", null, null);
        // TODO: Would be nice to have an event to wait on here.
        mDevice.waitForIdle();
    }

    private void initTestFiles() throws RemoteException {
        mRoot_0 = mDocsHelper.getRoot(ROOT_0_ID);
        mRoot_1 = mDocsHelper.getRoot(ROOT_1_ID);

        mDocsHelper.createDocument(mRoot_0, "text/plain", "file10.log");
        mDocsHelper.createDocument(mRoot_0, "image/png", "file1.png");
        mDocsHelper.createDocument(mRoot_0, "text/csv", "file2.csv");

        mDocsHelper.createDocument(mRoot_1, "text/plain", "anotherFile0.log");
        mDocsHelper.createDocument(mRoot_1, "text/plain", "poodles.text");
    }

    private void initUiObjects() {
        mSearchView = mBot.findSearchView();
        mSearchTextField = mBot.findSearchViewTextField();
        mDocsList = mBot.findDocumentsList();
        mMessageTextView = mBot.findMessageTextView();
        mSearchIcon = mBot.findSearchViewIcon();
    }

    public void testSearchViewExpandsOnClick() throws Exception {
        assertTrue(mSearchIcon.exists());
        assertFalse(mSearchTextField.exists());

        mSearchView.click();

        assertTrue(mSearchTextField.exists());
        assertTrue(mSearchTextField.isFocused());
        assertFalse(mSearchIcon.exists());
    }

    public void testSearchViewCollapsesOnBack() throws Exception {
        assertTrue(mSearchIcon.exists());
        assertFalse(mSearchTextField.exists());

        mSearchView.click();

        mDevice.pressBack();

        assertTrue(mSearchIcon.exists());
        assertFalse(mSearchTextField.exists());
    }

    public void testSearchViewClearsTextOnBack() throws Exception {
        assertTrue(mSearchIcon.exists());
        assertFalse(mSearchTextField.exists());

        String query = "file2";
        mSearchView.click();
        mSearchTextField.setText(query);

        assertSearchTextField(true, query);

        mDevice.pressBack();

        assertTrue(mSearchIcon.exists());
        assertFalse(mSearchTextField.exists());
    }

    public void testSearchFound() throws Exception {
        initTestFiles();

        mBot.openRoot(ROOT_0_ID);

        assertDefaultTestDir0();

        String query = "file1";
        mSearchView.click();
        mSearchTextField.setText(query);

        assertTrue(mDocsList.exists());
        assertSearchTextField(true, query);

        mDevice.pressEnter();

        assertTrue(mDocsList.exists());
        assertEquals(2, mDocsList.getChildCount());
        mBot.assertHasDocuments("file1.png", "file10.log");
        assertSearchTextField(false, query);
    }

    public void testSearchFoundClearsOnBack() throws Exception {
        initTestFiles();

        mBot.openRoot(ROOT_0_ID);

        assertDefaultTestDir0();

        String query = "file1";
        mSearchView.click();
        mSearchTextField.setText(query);

        mDevice.pressEnter();
        mDevice.pressBack();

        assertDefaultTestDir0();
    }

    public void testSearchNoResults() throws Exception {
        initTestFiles();

        mBot.openRoot(ROOT_0_ID);

        assertDefaultTestDir0();

        String query = "chocolate";
        mSearchView.click();
        mSearchTextField.setText(query);

        mDevice.pressEnter();

        assertFalse(mDocsList.exists());
        assertTrue(mMessageTextView.exists());
        assertEquals(mContext.getString(R.string.empty), mMessageTextView.getText());
        assertSearchTextField(false, query);
    }

    public void testSearchNoResultsClearsOnBack() throws Exception {
        initTestFiles();

        mBot.openRoot(ROOT_0_ID);

        assertDefaultTestDir0();

        String query = "chocolate";
        mSearchView.click();
        mSearchTextField.setText(query);

        mDevice.pressEnter();
        mDevice.pressBack();

        assertDefaultTestDir0();
    }

    public void testSearchFoundClearsDirectoryChange() throws Exception {
        initTestFiles();

        mBot.openRoot(ROOT_0_ID);

        assertDefaultTestDir0();

        String query = "file1";
        mSearchView.click();
        mSearchTextField.setText(query);

        mDevice.pressEnter();

        mBot.openRoot(ROOT_1_ID);

         assertDefaultTestDir1();

         mBot.openRoot(ROOT_0_ID);

         assertDefaultTestDir0();
    }

    private void assertDefaultTestDir0() throws UiObjectNotFoundException {
        assertTrue(mSearchIcon.exists());
        assertTrue(mDocsList.exists());
        assertFalse(mSearchTextField.exists());
        assertEquals(3, mDocsList.getChildCount());
        mBot.assertHasDocuments("file2.csv", "file1.png", "file10.log");
    }

    private void assertDefaultTestDir1() throws UiObjectNotFoundException {
        assertTrue(mSearchIcon.exists());
        assertFalse(mSearchTextField.exists());
        assertTrue(mDocsList.exists());
        assertEquals(2, mDocsList.getChildCount());
        mBot.assertHasDocuments("anotherFile0.log", "poodles.text");
    }

    private void assertSearchTextField(boolean isFocused, String query)
            throws UiObjectNotFoundException {
        assertFalse(mSearchIcon.exists());
        assertTrue(mSearchTextField.exists());
        assertEquals(isFocused, mSearchTextField.isFocused());
        assertEquals(query, mSearchTextField.getText());
    }
}
