/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.externalstorage;

import static android.provider.DocumentsContract.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID;

import static com.android.externalstorage.ExternalStorageProvider.AUTHORITY;
import static com.android.externalstorage.ExternalStorageProvider.getPathFromDocId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ProviderInfo;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExternalStorageProviderTest {

    @NonNull
    private static final Instrumentation sInstrumentation =
            InstrumentationRegistry.getInstrumentation();
    @NonNull
    private static final Context sTargetContext = sInstrumentation.getTargetContext();

    private ExternalStorageProvider mExternalStorageProvider;

    @Before
    public void setUp() {
        mExternalStorageProvider = new ExternalStorageProvider();
    }


    @Test
    public void onCreate_shouldUpdateVolumes() {
        final ExternalStorageProvider spyProvider = spy(mExternalStorageProvider);

        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = AUTHORITY;
        providerInfo.grantUriPermissions = true;
        providerInfo.exported = true;

        sInstrumentation.runOnMainSync(() ->
                spyProvider.attachInfoForTesting(sTargetContext, providerInfo));

        verify(spyProvider, atLeast(1)).updateVolumes();
    }

    @Test
    public void test_getPathFromDocId() {
        final String root = "root";
        final String path = "abc/def/ghi";
        String docId = root + ":" + path;
        assertEquals(getPathFromDocId(docId), path);

        docId = root + ":" + path + "/";
        assertEquals(getPathFromDocId(docId), path);

        docId = root + ":";
        assertTrue(getPathFromDocId(docId).isEmpty());

        docId = root + ":./" + path;
        assertEquals(getPathFromDocId(docId), path);

        final String dotPath = "abc/./def/ghi";
        docId = root + ":" + dotPath;
        assertEquals(getPathFromDocId(docId), path);

        final String twoDotPath = "abc/../abc/def/ghi";
        docId = root + ":" + twoDotPath;
        assertEquals(getPathFromDocId(docId), path);
    }

    @Test
    public void test_shouldHideDocument() {
        // Should hide "Android/data", "Android/obb", "Android/sandbox" and all their
        // "subtrees".
        final String[] shouldHide = {
                // "Android/data" and all its subdirectories
                "Android/data",
                "Android/data/com.my.app",
                "Android/data/com.my.app/cache",
                "Android/data/com.my.app/cache/image.png",
                "Android/data/mydata",

                // "Android/obb" and all its subdirectories
                "Android/obb",
                "Android/obb/com.my.app",
                "Android/obb/com.my.app/file.blob",

                // "Android/sandbox" and all its subdirectories
                "Android/sandbox",
                "Android/sandbox/com.my.app",

                // Also make sure we are not allowing path traversals
                "Android/./data",
                "Android/Download/../data",
        };
        for (String path : shouldHide) {
            final String docId = buildDocId(path);
            assertTrue("ExternalStorageProvider should hide \"" + docId + "\", but it didn't",
                    mExternalStorageProvider.shouldHideDocument(docId));
        }

        // Should NOT hide anything else.
        final String[] shouldNotHide = {
                "Android",
                "Android/datadir",
                "Documents",
                "Download",
                "Music",
                "Pictures",
        };
        for (String path : shouldNotHide) {
            final String docId = buildDocId(path);
            assertFalse("ExternalStorageProvider should NOT hide \"" + docId + "\", but it did",
                    mExternalStorageProvider.shouldHideDocument(docId));
        }
    }

    @NonNull
    private static String buildDocId(@NonNull String path) {
        return buildDocId(EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID, path);
    }

    @NonNull
    private static String buildDocId(@NonNull String root, @NonNull String path) {
        // docId format: root:path/to/file
        return root + ':' + path;
    }
}
