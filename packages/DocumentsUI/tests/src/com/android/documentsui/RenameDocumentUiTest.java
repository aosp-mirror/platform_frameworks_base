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

import static com.android.documentsui.StubProvider.ROOT_0_ID;

import android.test.suitebuilder.annotation.LargeTest;

@LargeTest
public class RenameDocumentUiTest extends ActivityTest<FilesActivity> {

    private static final String TAG = "RenamDocumentUiTest";

    private final String newName = "kitties.log";

    public RenameDocumentUiTest() {
        super(FilesActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        initTestFiles();
        bot.openRoot(ROOT_0_ID);
    }

    public void testRenameEnabled_SingleSelection() throws Exception {
        bot.selectDocument(fileName1);
        bot.openOverflowMenu();
        bot.assertMenuEnabled(R.string.menu_rename, true);

        // Dismiss more options window
        device.pressBack();
    }

    public void testNoRenameSupport_SingleSelection() throws Exception {
        bot.selectDocument(fileNameNoRename);
        bot.openOverflowMenu();
        bot.assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        device.pressBack();
    }

    public void testOneHasRenameSupport_MultipleSelection() throws Exception {
        bot.selectDocument(fileName1);
        bot.selectDocument(fileNameNoRename);
        bot.openOverflowMenu();
        bot.assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        device.pressBack();
    }

    public void testRenameDisabled_MultipleSelection() throws Exception {
        bot.selectDocument(fileName1);
        bot.selectDocument(fileName2);
        bot.openOverflowMenu();
        bot.assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        device.pressBack();
    }

    public void testRenameFile_OkButton() throws Exception {
        bot.selectDocument(fileName1);
        bot.openOverflowMenu();
        bot.openDialog(R.string.menu_rename);
        bot.setDialogText(newName);

        device.waitForIdle(TIMEOUT);
        bot.findRenameDialogOkButton().click();
        device.waitForIdle(TIMEOUT);

        bot.assertDocument(fileName1, false);
        bot.assertDocument(newName, true);
        bot.assertDocumentsCount(4);
    }

    public void testRenameFile_Enter() throws Exception {
        bot.selectDocument(fileName1);
        bot.openOverflowMenu();
        bot.openDialog(R.string.menu_rename);
        bot.setDialogText(newName);

        pressEnter();

        bot.assertDocument(fileName1, false);
        bot.assertDocument(newName, true);
        bot.assertDocumentsCount(4);
    }

    public void testRenameFile_Cancel() throws Exception {
        bot.selectDocument(fileName1);
        bot.openOverflowMenu();
        bot.openDialog(R.string.menu_rename);
        bot.setDialogText(newName);

        device.waitForIdle(TIMEOUT);
        bot.findRenameDialogCancelButton().click();
        device.waitForIdle(TIMEOUT);

        bot.assertDocument(fileName1, true);
        bot.assertDocument(newName, false);
        bot.assertDocumentsCount(4);
    }

    public void testRenameDir() throws Exception {
        String oldName = "Dir1";
        String newName = "Dir123";

        bot.selectDocument(oldName);
        bot.openOverflowMenu();
        bot.openDialog(R.string.menu_rename);
        bot.setDialogText(newName);

        pressEnter();

        bot.assertDocument(oldName, false);
        bot.assertDocument(newName, true);
        bot.assertDocumentsCount(4);
    }

    public void testRename_NameExists() throws Exception {
        // Check that document with the new name exists
        bot.assertDocument(fileName2, true);
        bot.selectDocument(fileName1);
        bot.openOverflowMenu();
        bot.openDialog(R.string.menu_rename);
        bot.setDialogText(fileName2);

        pressEnter();

        bot.assertSnackbar(R.string.rename_error);
        bot.assertDocument(fileName1, true);
        bot.assertDocument(fileName2, true);
        bot.assertDocumentsCount(4);
    }

    private void pressEnter() {
        device.waitForIdle(TIMEOUT);
        device.pressEnter();
        device.waitForIdle(TIMEOUT);
    }
}
