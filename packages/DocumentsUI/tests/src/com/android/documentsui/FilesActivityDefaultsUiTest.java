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
import static com.android.documentsui.StubProvider.ROOT_1_ID;

import android.content.Intent;
import android.provider.DocumentsContract;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.documentsui.model.RootInfo;

@LargeTest
public class FilesActivityDefaultsUiTest extends ActivityTest<FilesActivity> {

    public FilesActivityDefaultsUiTest() {
        super(FilesActivity.class);
    }

    @Override
    protected RootInfo getInitialRoot() {
        return null;  // test the default, unaffected state of the app.
    }

    public void testDefaultDirectory() throws Exception {
        device.waitForIdle();

        // Separate logic for "Documents" root, which presence depends on the config setting
        if (docsRootEnabled()) {
            bots.main.assertWindowTitle("Documents");
        } else {
            bots.main.assertWindowTitle("Downloads");
        }
    }

    public void testDefaultRoots() throws Exception {
        device.waitForIdle();

        // Should also have Drive, but that requires pre-configuration of devices
        // We omit for now.
        bots.roots.assertRootsPresent(
                "Images",
                "Videos",
                "Audio",
                "Downloads",
                ROOT_0_ID,
                ROOT_1_ID);

        // Separate logic for "Documents" root, which presence depends on the config setting
        if (docsRootEnabled()) {
            bots.roots.assertRootsPresent("Documents");
        } else {
            bots.roots.assertRootsAbsent("Documents");
        }
    }

    private boolean docsRootEnabled() {
        return Shared.shouldShowDocumentsRoot(context, new Intent(DocumentsContract.ACTION_BROWSE));
    }
}
