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

import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;

@LargeTest
public class RenameDocumentUiTest extends ActivityTest<FilesActivity> {

    private final String newName = "kitties.log";

    public RenameDocumentUiTest() {
        super(FilesActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        initTestFiles();
    }

    public void testRenameEnabled_SingleSelection() throws Exception {
        bots.directory.selectDocument(fileName1);
        bots.main.openOverflowMenu();
        bots.main.assertMenuEnabled(R.string.menu_rename, true);

        // Dismiss more options window
        device.pressBack();
    }

    public void testNoRenameSupport_SingleSelection() throws Exception {
        bots.directory.selectDocument(fileNameNoRename);
        bots.main.openOverflowMenu();
        bots.main.assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        device.pressBack();
    }

    public void testOneHasRenameSupport_MultipleSelection() throws Exception {
        bots.directory.selectDocument(fileName1);
        bots.directory.selectDocument(fileNameNoRename);
        bots.main.openOverflowMenu();
        bots.main.assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        device.pressBack();
    }

    public void testRenameDisabled_MultipleSelection() throws Exception {
        bots.directory.selectDocument(fileName1);
        bots.directory.selectDocument(fileName2);
        bots.main.openOverflowMenu();
        bots.main.assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        device.pressBack();
    }

    @Suppress
    public void testRenameFile_OkButton() throws Exception {
        bots.directory.selectDocument(fileName1);
        bots.main.openOverflowMenu();
        bots.main.menuRename().click();
        bots.main.setDialogText(newName);

        device.waitForIdle(TIMEOUT);
        bots.main.findDialogOkButton().click();
        device.waitForIdle(TIMEOUT);

        bots.directory.assertDocumentsAbsent(fileName1);
        bots.directory.assertDocumentsPresent(newName);
        bots.directory.assertDocumentsCount(4);
    }

    public void testRenameFile_Enter() throws Exception {
        bots.directory.selectDocument(fileName1);
        bots.main.openOverflowMenu();
        bots.main.menuRename().click();
        bots.main.setDialogText(newName);

        bots.keyboard.pressEnter();

        bots.directory.assertDocumentsAbsent(fileName1);
        bots.directory.assertDocumentsPresent(newName);
        bots.directory.assertDocumentsCount(4);
    }

    @Suppress
    public void testRenameFile_Cancel() throws Exception {
        bots.directory.selectDocument(fileName1);
        bots.main.openOverflowMenu();
        bots.main.menuRename().click();
        bots.main.setDialogText(newName);

        device.waitForIdle(TIMEOUT);
        bots.main.findDialogCancelButton().click();
        device.waitForIdle(TIMEOUT);

        bots.directory.assertDocumentsPresent(fileName1);
        bots.directory.assertDocumentsAbsent(newName);
        bots.directory.assertDocumentsCount(4);
    }

    public void testRenameDir() throws Exception {
        String oldName = "Dir1";
        String newName = "Dir123";

        bots.directory.selectDocument(oldName);
        bots.main.openOverflowMenu();
        bots.main.menuRename().click();
        bots.main.setDialogText(newName);

        bots.keyboard.pressEnter();

        bots.directory.assertDocumentsAbsent(oldName);
        bots.directory.assertDocumentsPresent(newName);
        bots.directory.assertDocumentsCount(4);
    }

    public void testRename_NameExists() throws Exception {
        // Check that document with the new name exists
        bots.directory.assertDocumentsPresent(fileName2);
        bots.directory.selectDocument(fileName1);
        bots.main.openOverflowMenu();
        bots.main.menuRename().click();
        bots.main.setDialogText(fileName2);

        bots.keyboard.pressEnter();

        bots.directory.assertSnackbar(R.string.rename_error);
        bots.directory.assertDocumentsPresent(fileName1);
        bots.directory.assertDocumentsPresent(fileName2);
        bots.directory.assertDocumentsCount(4);
    }
}
