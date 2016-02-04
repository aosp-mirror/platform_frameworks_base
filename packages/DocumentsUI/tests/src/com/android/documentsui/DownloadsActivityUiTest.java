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

import android.app.Instrumentation;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.Until;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.MotionEvent;

import com.android.documentsui.model.RootInfo;

@LargeTest
public class DownloadsActivityUiTest extends ActivityInstrumentationTestCase2<DownloadsActivity> {

    private static final int TIMEOUT = 5000;
    private static final String TAG = "DownloadsActivityUiTest";
    private static final String TARGET_PKG = "com.android.documentsui";
    private static final String LAUNCHER_PKG = "com.android.launcher";

    private UiBot mBot;
    private UiDevice mDevice;
    private Context mContext;
    private ContentResolver mResolver;
    private DocumentsProviderHelper mDocsHelper;
    private ContentProviderClient mClient;
    private RootInfo mRoot;

    public DownloadsActivityUiTest() {
        super(DownloadsActivity.class);
    }

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

        mRoot = mDocsHelper.getRoot(ROOT_0_ID);

        // Open the Downloads activity on our stub provider root.
        Intent intent = new Intent(DocumentsContract.ACTION_MANAGE_ROOT);
        intent.setDataAndType(mRoot.getUri(), DocumentsContract.Root.MIME_TYPE_ITEM);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        setActivityIntent(intent);
        getActivity();  // Start the activity.

        // Wait for the app to appear.
        mDevice.wait(Until.hasObject(By.pkg(TARGET_PKG).depth(0)), TIMEOUT);
        mDevice.waitForIdle();

        mBot = new UiBot(mDevice, mContext, TIMEOUT);

        resetStorage();  // Just in case a test failed and tearDown didn't happen.
    }

    @Override
    protected void tearDown() throws Exception {
        Log.d(TAG, "Resetting storage from setUp");
        resetStorage();
        mClient.release();

        super.tearDown();
    }

    private void resetStorage() throws RemoteException {
        mClient.call("clear", null, null);
        // TODO: Would be nice to have an event to wait on here.
        mDevice.waitForIdle();
    }

    private void initTestFiles() throws RemoteException {
        mDocsHelper.createDocument(mRoot, "text/plain", "file0.log");
        mDocsHelper.createDocument(mRoot, "image/png", "file1.png");
        mDocsHelper.createDocument(mRoot, "text/csv", "file2.csv");
    }

    public void testWindowTitle() throws Exception {
        initTestFiles();

        mBot.assertWindowTitle(ROOT_0_ID);
    }

    public void testFilesListed() throws Exception {
        initTestFiles();

        mBot.assertHasDocuments("file0.log", "file1.png", "file2.csv");
    }

    public void testFilesList_LiveUpdate() throws Exception {
        initTestFiles();

        mDocsHelper.createDocument(mRoot, "yummers/sandwich", "Ham & Cheese.sandwich");
        mBot.assertHasDocuments("file0.log", "file1.png", "file2.csv", "Ham & Cheese.sandwich");
    }

    public void testDeleteDocument() throws Exception {
        initTestFiles();

        mBot.clickDocument("file1.png");
        mDevice.waitForIdle();
        mBot.menuDelete().click();

        mBot.waitForDeleteSnackbar();
        assertFalse(mBot.hasDocuments("file1.png"));

        mBot.waitForDeleteSnackbarGone();
        assertFalse(mBot.hasDocuments("file1.png"));
    }

    public void testSupportsShare() throws Exception {
        initTestFiles();

        mBot.clickDocument("file1.png");
        mDevice.waitForIdle();
        assertNotNull(mBot.menuShare());
    }
}
