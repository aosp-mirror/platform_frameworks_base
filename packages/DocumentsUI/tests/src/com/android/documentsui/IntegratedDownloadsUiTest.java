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

import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.net.Uri;
import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiObject;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.view.MotionEvent;

// TODO: As of this writing all tests in this class are disabled. Please fix.
@LargeTest
public class IntegratedDownloadsUiTest extends ActivityTest<FilesActivity> {

    public IntegratedDownloadsUiTest() {
        super(FilesActivity.class);
    }

    // We don't really need to test the entirety of download support
    // since downloads is (almost) just another provider.
    @Suppress
    public void testDownload_Queued() throws Exception {
        DownloadManager dm = (DownloadManager) context.getSystemService(
                Context.DOWNLOAD_SERVICE);
        // This downloads ends up being queued (because DNS can't be resolved).
        // We'll still see an entry in the downloads UI with a "Queued" label.
        dm.enqueue(new Request(Uri.parse("http://hammychamp.toodles")));

        bots.roots.openRoot("Downloads");
        bots.directory.assertDocumentsPresent("Queued");
    }

    @Suppress
    public void testDownload_RetryUnsuccessful() throws Exception {
        DownloadManager dm = (DownloadManager) context.getSystemService(
                Context.DOWNLOAD_SERVICE);
        // This downloads fails! But it'll still show up.
        dm.enqueue(new Request(Uri.parse("http://www.google.com/hamfancy")));

        bots.roots.openRoot("Downloads");
        UiObject doc = bots.directory.findDocument("Unsuccessful");
        doc.waitForExists(TIMEOUT);

        int toolType = Configurator.getInstance().getToolType();
        Configurator.getInstance().setToolType(MotionEvent.TOOL_TYPE_FINGER);
        doc.click();
        Configurator.getInstance().setToolType(toolType);

        assertTrue(bots.main.findDownloadRetryDialog().exists());

        device.pressBack(); // to clear the dialog.
    }
}
