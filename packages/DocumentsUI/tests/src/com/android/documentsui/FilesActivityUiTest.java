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
import android.support.test.uiautomator.Until;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.MotionEvent;

import com.android.documentsui.model.RootInfo;

@LargeTest
public class FilesActivityUiTest extends ActivityTest<FilesActivity> {

    private static final int TIMEOUT = 5000;
    private static final String TAG = "FilesActivityUiTest";
    private static final String TARGET_PKG = "com.android.documentsui";
    private static final String LAUNCHER_PKG = "com.android.launcher";

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

        device.waitForIdle();
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
}
