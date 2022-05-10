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

import static com.android.externalstorage.ExternalStorageProvider.AUTHORITY;
import static com.android.externalstorage.ExternalStorageProvider.getPathFromDocId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.pm.ProviderInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExternalStorageProviderTest {
    @Test
    public void onCreate_shouldUpdateVolumes() throws Exception {
        ExternalStorageProvider externalStorageProvider = new ExternalStorageProvider();
        ExternalStorageProvider spyProvider = spy(externalStorageProvider);
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = AUTHORITY;
        providerInfo.grantUriPermissions = true;
        providerInfo.exported = true;

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                spyProvider.attachInfoForTesting(
                        InstrumentationRegistry.getTargetContext(), providerInfo);
            }
        });

        verify(spyProvider, atLeast(1)).updateVolumes();
    }

    @Test
    public void testGetPathFromDocId() throws Exception {
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
}
