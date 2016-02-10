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

import static com.android.documentsui.StubProvider.ROOT_0_ID;
import static com.android.documentsui.StubProvider.ROOT_1_ID;

import android.os.RemoteException;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.KeyEvent;

@LargeTest
public class FilesActivityUiTest extends ActivityTest<FilesActivity> {

    public FilesActivityUiTest() {
        super(FilesActivity.class);
    }

    @Override
    public void initTestFiles() throws RemoteException {
        mDocsHelper.createDocument(rootDir0, "text/plain", "file0.log");
        mDocsHelper.createDocument(rootDir0, "image/png", "file1.png");
        mDocsHelper.createDocument(rootDir0, "text/csv", "file2.csv");

        mDocsHelper.createDocument(rootDir1, "text/plain", "anotherFile0.log");
        mDocsHelper.createDocument(rootDir1, "text/plain", "poodles.text");
    }

    public void testRootsListed() throws Exception {
        initTestFiles();

        bot.openRoot(ROOT_0_ID);

        // Should also have Drive, but that requires pre-configuration of devices
        // We omit for now.
        bot.assertHasRoots(
                "Images",
                "Videos",
                "Audio",
                "Downloads",
                "Home",
                ROOT_0_ID,
                ROOT_1_ID);
    }

    public void testFilesListed() throws Exception {
        initTestFiles();

        bot.openRoot(ROOT_0_ID);
        bot.assertHasDocuments("file0.log", "file1.png", "file2.csv");
    }

    public void testLoadsHomeByDefault() throws Exception {
        initTestFiles();

        device.waitForIdle();
        bot.assertWindowTitle("Home");
    }

    public void testRootClickSetsWindowTitle() throws Exception {
        initTestFiles();

        bot.openRoot("Downloads");
        bot.assertWindowTitle("Downloads");
    }

    public void testFilesList_LiveUpdate() throws Exception {
        initTestFiles();

        bot.openRoot(ROOT_0_ID);
        mDocsHelper.createDocument(rootDir0, "yummers/sandwich", "Ham & Cheese.sandwich");

        bot.waitForDocument("Ham & Cheese.sandwich");
        bot.assertHasDocuments("file0.log", "file1.png", "file2.csv", "Ham & Cheese.sandwich");
    }

    public void testDeleteDocument() throws Exception {
        initTestFiles();

        bot.openRoot(ROOT_0_ID);

        bot.clickDocument("file1.png");
        device.waitForIdle();
        bot.menuDelete().click();

        bot.waitForDeleteSnackbar();
        assertFalse(bot.hasDocuments("file1.png"));

        bot.waitForDeleteSnackbarGone();
        assertFalse(bot.hasDocuments("file1.png"));

        // Now delete from another root.
        bot.openRoot(ROOT_1_ID);

        bot.clickDocument("poodles.text");
        device.waitForIdle();
        bot.menuDelete().click();

        bot.waitForDeleteSnackbar();
        assertFalse(bot.hasDocuments("poodles.text"));

        bot.waitForDeleteSnackbarGone();
        assertFalse(bot.hasDocuments("poodles.text"));
    }

    // Tests that pressing tab switches focus between the roots and directory listings.
    public void testKeyboard_tab() throws Exception {
        bot.pressKey(KeyEvent.KEYCODE_TAB);
        bot.assertHasFocus("com.android.documentsui:id/roots_list");
        bot.pressKey(KeyEvent.KEYCODE_TAB);
        bot.assertHasFocus("com.android.documentsui:id/dir_list");
    }

    // Tests that arrow keys do not switch focus away from the dir list.
    public void testKeyboard_arrowsDirList() throws Exception {
        for (int i = 0; i < 10; i++) {
            bot.pressKey(KeyEvent.KEYCODE_DPAD_LEFT);
            bot.assertHasFocus("com.android.documentsui:id/dir_list");
        }
        for (int i = 0; i < 10; i++) {
            bot.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT);
            bot.assertHasFocus("com.android.documentsui:id/dir_list");
        }
    }

    // Tests that arrow keys do not switch focus away from the roots list.
    public void testKeyboard_arrowsRootsList() throws Exception {
        bot.pressKey(KeyEvent.KEYCODE_TAB);
        for (int i = 0; i < 10; i++) {
            bot.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT);
            bot.assertHasFocus("com.android.documentsui:id/roots_list");
        }
        for (int i = 0; i < 10; i++) {
            bot.pressKey(KeyEvent.KEYCODE_DPAD_LEFT);
            bot.assertHasFocus("com.android.documentsui:id/roots_list");
        }
    }
}
