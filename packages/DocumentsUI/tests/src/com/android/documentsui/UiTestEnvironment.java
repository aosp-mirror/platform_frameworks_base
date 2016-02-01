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
import android.provider.DocumentsContract.Document;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.Until;
import android.view.MotionEvent;

import com.android.documentsui.model.RootInfo;

/**
 * Provides basic test environment for UI tests:
 * - Launches activity
 * - Creates and gives access to test root directories and test files
 * - Cleans up the test environment
 */
class UiTestEnvironment {

    public static final int TIMEOUT = 5000;
    public static final String NO_RENAME = "NO_RENAME";

    public static final String dirName1 = "Dir1";
    public static final String fileName1 = "file1.log";
    public static final String fileName2 = "file12.png";
    public static final String fileName3 = "anotherFile0.log";
    public static final String fileName4 = "poodles.text";
    public static final String fileNameNoRename = NO_RENAME + "file.txt";

    private static final String TARGET_PKG = "com.android.documentsui";
    private static final String LAUNCHER_PKG = "com.android.launcher";

    private final UiBot mBot;
    private final UiDevice mDevice;
    private final Context mContext;

    private  RootInfo mRootDir0;
    private  RootInfo mRootDir1;
    private int mDocsCountDir0;
    private int mDocsCountDir1;

    private ContentResolver mResolver;
    private DocumentsProviderHelper mDocsHelper;
    private ContentProviderClient mClient;

    private final Instrumentation mInstrumentation;

    public UiTestEnvironment(Instrumentation instrumentation) {
        mInstrumentation = instrumentation;
        mDevice = UiDevice.getInstance(mInstrumentation);
        // NOTE: Must be the "target" context, else security checks in content provider will fail.
        mContext = mInstrumentation.getTargetContext();
        mBot = new UiBot(mDevice, mContext, TIMEOUT);
    }

/**
 * Launches default activity and waits for the application to appear.
 * @throws Exception
 */
    public void launch() throws Exception {
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(TARGET_PKG);
        launch(intent);
    }

    /**
     * Launches activity specified by intent and waits for the application to appear.
     * @param intent Intent describing activity to launch.
     * @throws Exception
     */
    public void launch(Intent intent) throws Exception {
        Configurator.getInstance().setToolType(MotionEvent.TOOL_TYPE_MOUSE);
        // Start from the home screen.
        mDevice.pressHome();
        mDevice.wait(Until.hasObject(By.pkg(LAUNCHER_PKG).depth(0)), TIMEOUT);

        mResolver = mContext.getContentResolver();
        mClient = mResolver.acquireUnstableContentProviderClient(DEFAULT_AUTHORITY);
        mDocsHelper = new DocumentsProviderHelper(DEFAULT_AUTHORITY, mClient);

        // Launch app.
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        // Wait for the app to appear.
        mDevice.wait(Until.hasObject(By.pkg(TARGET_PKG).depth(0)), TIMEOUT);
        mDevice.waitForIdle();

        resetStorage(); // Just incase a test failed and tearDown didn't happen.
    }

    public void cleanUp() throws Exception {
        resetStorage();
        mClient.release();
    }

    public void resetStorage() throws RemoteException {
        mClient.call("clear", null, null);
        mDevice.waitForIdle();
    }

    public void initTestFiles() throws RemoteException {
        mRootDir0 = mDocsHelper.getRoot(ROOT_0_ID);
        mRootDir1 = mDocsHelper.getRoot(ROOT_1_ID);

        mDocsHelper.createFolder(mRootDir0, dirName1);
        mDocsHelper.createDocument(mRootDir0, "text/plain", fileName1);
        mDocsHelper.createDocument(mRootDir0, "image/png", fileName2);
        mDocsHelper.createDocumentWithFlags(mRootDir0.documentId, "text/plain", fileNameNoRename,
                Document.FLAG_SUPPORTS_WRITE);
        mDocsCountDir0 = 4;

        mDocsHelper.createDocument(mRootDir1, "text/plain", fileName3);
        mDocsHelper.createDocument(mRootDir1, "text/plain", fileName4);
        mDocsCountDir1 = 2;
    }

    public void assertDefaultContentOfTestDir0() throws UiObjectNotFoundException {
        bot().assertDocumentsCount(ROOT_0_ID, getDocumentsCountDir0());
        bot().assertHasDocuments(UiTestEnvironment.fileName1, UiTestEnvironment.fileName2,
                UiTestEnvironment.dirName1, UiTestEnvironment.fileNameNoRename);
    }

    public void assertDefaultContentOfTestDir1() throws UiObjectNotFoundException {
        bot().assertDocumentsCount(ROOT_1_ID, getDocumentsCountDir1());
        bot().assertHasDocuments(UiTestEnvironment.fileName3, UiTestEnvironment.fileName4);
    }

    public UiBot bot() {
        return mBot;
    }

    public UiDevice device() {
        return mDevice;
    }

    public Context context() {
        return mContext;
    }

    public RootInfo getRootDir0() {
        return mRootDir0;
    }

    public int getDocumentsCountDir0() {
        return mDocsCountDir0;
    }

    public int getDocumentsCountDir1() {
        return mDocsCountDir1;
    }
}
