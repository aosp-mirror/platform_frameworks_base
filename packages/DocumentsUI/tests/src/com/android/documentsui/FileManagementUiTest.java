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

import android.net.Uri;
import android.os.RemoteException;
import android.support.test.filters.Suppress;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.KeyEvent;

@LargeTest
public class FileManagementUiTest extends ActivityTest<FilesActivity> {

    public FileManagementUiTest() {
        super(FilesActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        initTestFiles();
    }

    @Override
    public void initTestFiles() throws RemoteException {
        Uri uri = mDocsHelper.createFolder(rootDir0, dirName1);
        mDocsHelper.createFolder(uri, childDir1);

        mDocsHelper.createDocument(rootDir0, "text/plain", "file0.log");
        mDocsHelper.createDocument(rootDir0, "image/png", "file1.png");
        mDocsHelper.createDocument(rootDir0, "text/csv", "file2.csv");

        mDocsHelper.createDocument(rootDir1, "text/plain", "anotherFile0.log");
        mDocsHelper.createDocument(rootDir1, "text/plain", "poodles.text");
    }

    @Suppress
    public void testCreateDirectory() throws Exception {
        bots.main.openOverflowMenu();
        device.waitForIdle();

        bots.main.clickToolbarOverflowItem("New folder");
        device.waitForIdle();

        bots.main.setDialogText("Kung Fu Panda");
        device.waitForIdle();

        bots.keyboard.pressEnter();

        bots.directory.waitForDocument("Kung Fu Panda");
    }

    public void testDeleteDocument() throws Exception {
        bots.directory.clickDocument("file1.png");
        device.waitForIdle();
        bots.main.clickToolbarItem(R.id.menu_delete);

        bots.main.clickDialogOkButton();
        device.waitForIdle();

        bots.directory.assertDocumentsAbsent("file1.png");
    }

    public void testKeyboard_CutDocument() throws Exception {
        bots.directory.clickDocument("file1.png");
        device.waitForIdle();
        bots.keyboard.pressKey(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON);

        device.waitForIdle();

        bots.roots.openRoot(ROOT_1_ID);
        bots.keyboard.pressKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON);

        bots.directory.waitForDocument("file1.png");
        bots.directory.assertDocumentsPresent("file1.png");

        bots.roots.openRoot(ROOT_0_ID);
        bots.directory.assertDocumentsAbsent("file1.png");
    }

    public void testKeyboard_CopyDocument() throws Exception {
        bots.directory.clickDocument("file1.png");
        device.waitForIdle();
        bots.keyboard.pressKey(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON);

        device.waitForIdle();

        bots.roots.openRoot(ROOT_1_ID);
        bots.keyboard.pressKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON);

        bots.directory.waitForDocument("file1.png");

        bots.roots.openRoot(ROOT_0_ID);
        bots.directory.waitForDocument("file1.png");
    }

    public void testDeleteDocument_Cancel() throws Exception {
        bots.directory.clickDocument("file1.png");
        device.waitForIdle();
        bots.main.clickToolbarItem(R.id.menu_delete);

        bots.main.clickDialogCancelButton();

        bots.directory.waitForDocument("file1.png");
    }
}
