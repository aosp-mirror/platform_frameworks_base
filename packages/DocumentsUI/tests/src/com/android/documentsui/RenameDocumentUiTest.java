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
import static com.android.documentsui.UiTestEnvironment.TIMEOUT;

import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;

@LargeTest
public class RenameDocumentUiTest extends InstrumentationTestCase {

    private static final String TAG = "RenamDocumentUiTest";

    private final String newName = "kitties.log";

    private UiTestEnvironment mHelper;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mHelper = new UiTestEnvironment(getInstrumentation());
        mHelper.launch();
        mHelper.initTestFiles();
        mHelper.bot().openRoot(ROOT_0_ID);
    }

    public void testRenameEnabled_SingleSelection() throws Exception {
        mHelper.bot().selectDocument(UiTestEnvironment.fileName1);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().assertMenuEnabled(R.string.menu_rename, true);

        // Dismiss more options window
        mHelper.device().pressBack();
    }

    public void testNoRenameSupport_SingleSelection() throws Exception {
        mHelper.bot().selectDocument(UiTestEnvironment.fileNameNoRename);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        mHelper.device().pressBack();
    }

    public void testOneHasRenameSupport_MultipleSelection() throws Exception {
        mHelper.bot().selectDocument(UiTestEnvironment.fileName1);
        mHelper.bot().selectDocument(UiTestEnvironment.fileNameNoRename);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        mHelper.device().pressBack();
    }

    public void testRenameDisabled_MultipleSelection() throws Exception {
        mHelper.bot().selectDocument(UiTestEnvironment.fileName1);
        mHelper.bot().selectDocument(UiTestEnvironment.fileName2);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().assertMenuEnabled(R.string.menu_rename, false);

        // Dismiss more options window
        mHelper.device().pressBack();
    }

    public void testRenameFile_OkButton() throws Exception {
        mHelper.bot().selectDocument(UiTestEnvironment.fileName1);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().openDialog(R.string.menu_rename);
        mHelper.bot().setDialogText(newName);
        mHelper.bot().dismissKeyboardIfPresent();

        mHelper.device().waitForIdle(TIMEOUT);
        mHelper.bot().findRenameDialogOkButton().click();
        mHelper.device().waitForIdle(TIMEOUT);

        mHelper.bot().assertDocument(UiTestEnvironment.fileName1, false);
        mHelper.bot().assertDocument(newName, true);
        mHelper.bot().assertDocumentsCount(mHelper.getDocumentsCountDir0());
    }

    public void testRenameFile_Enter() throws Exception {
        mHelper.bot().selectDocument(UiTestEnvironment.fileName1);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().openDialog(R.string.menu_rename);
        mHelper.bot().setDialogText(newName);

        pressEnter();

        mHelper.bot().assertDocument(UiTestEnvironment.fileName1, false);
        mHelper.bot().assertDocument(newName, true);
        mHelper.bot().assertDocumentsCount(mHelper.getDocumentsCountDir0());
    }

    public void testRenameFile_Cancel() throws Exception {
        mHelper.bot().selectDocument(UiTestEnvironment.fileName1);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().openDialog(R.string.menu_rename);
        mHelper.bot().setDialogText(newName);
        mHelper.bot().dismissKeyboardIfPresent();

        mHelper.device().waitForIdle(TIMEOUT);
        mHelper.bot().findRenameDialogCancelButton().click();
        mHelper.device().waitForIdle(TIMEOUT);

        mHelper.bot().assertDocument(UiTestEnvironment.fileName1, true);
        mHelper.bot().assertDocument(newName, false);
        mHelper.bot().assertDocumentsCount(mHelper.getDocumentsCountDir0());
    }

    public void testRenameDir() throws Exception {
        String oldName = "Dir1";
        String newName = "Dir123";

        mHelper.bot().selectDocument(oldName);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().openDialog(R.string.menu_rename);
        mHelper.bot().setDialogText(newName);

        pressEnter();

        mHelper.bot().assertDocument(oldName, false);
        mHelper.bot().assertDocument(newName, true);
        mHelper.bot().assertDocumentsCount(mHelper.getDocumentsCountDir0());
    }

    public void testRename_NameExists() throws Exception {
        // Check that document with the new name exists
        mHelper.bot().assertDocument(UiTestEnvironment.fileName2, true);
        mHelper.bot().selectDocument(UiTestEnvironment.fileName1);
        mHelper.bot().openOverflowMenu();
        mHelper.bot().openDialog(R.string.menu_rename);
        mHelper.bot().setDialogText(UiTestEnvironment.fileName2);

        pressEnter();

        mHelper.bot().assertSnackbar(R.string.rename_error);
        mHelper.bot().assertDocument(UiTestEnvironment.fileName1, true);
        mHelper.bot().assertDocument(UiTestEnvironment.fileName2, true);
        mHelper.bot().assertDocumentsCount(mHelper.getDocumentsCountDir0());
    }

    private void pressEnter() {
        mHelper.device().waitForIdle(TIMEOUT);
        mHelper.device().pressEnter();
        mHelper.device().waitForIdle(TIMEOUT);
    }

}
