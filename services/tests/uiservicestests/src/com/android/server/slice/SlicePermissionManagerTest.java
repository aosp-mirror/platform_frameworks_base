/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.slice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.net.Uri;
import android.net.Uri.Builder;
import android.os.FileUtils;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.Xml.Encoding;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class SlicePermissionManagerTest extends UiServiceTestCase {

    @Test
    public void testGrant() {
        File sliceDir = new File(mContext.getDataDir(), "system/slices");
        SlicePermissionManager permissions = new SlicePermissionManager(mContext,
                TestableLooper.get(this).getLooper(), sliceDir);
        Uri uri = new Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority("authority")
                .path("something").build();

        permissions.grantSliceAccess("my.pkg", 0, "provider.pkg", 0, uri);

        assertTrue(permissions.hasPermission("my.pkg", 0, uri));
    }

    @Test
    public void testBackup() throws XmlPullParserException, IOException {
        File sliceDir = new File(mContext.getDataDir(), "system/slices");
        Uri uri = new Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority("authority")
                .path("something").build();
        SlicePermissionManager permissions = new SlicePermissionManager(mContext,
                TestableLooper.get(this).getLooper(), sliceDir);

        permissions.grantFullAccess("com.android.mypkg", 10);
        permissions.grantSliceAccess("com.android.otherpkg", 0, "com.android.lastpkg", 1, uri);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XmlSerializer serializer = XmlPullParserFactory.newInstance().newSerializer();
        serializer.setOutput(output, Encoding.UTF_8.name());


        TestableLooper.get(this).processAllMessages();
        permissions.writeBackup(serializer);
        serializer.flush();

        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(input, Encoding.UTF_8.name());

        permissions = new SlicePermissionManager(mContext,
                TestableLooper.get(this).getLooper());
        permissions.readRestore(parser);

        assertTrue(permissions.hasFullAccess("com.android.mypkg", 10));
        assertTrue(permissions.hasPermission("com.android.otherpkg", 0,
                ContentProvider.maybeAddUserId(uri, 1)));
        permissions.removePkg("com.android.lastpkg", 1);
        assertFalse(permissions.hasPermission("com.android.otherpkg", 0,
                ContentProvider.maybeAddUserId(uri, 1)));

        // Cleanup.
        assertTrue(FileUtils.deleteContentsAndDir(sliceDir));
    }

    @Test
    public void testInvalid() throws Exception {
        File sliceDir = new File(mContext.getCacheDir(), "slices-test");
        if (!sliceDir.exists()) {
            sliceDir.mkdir();
        }
        SlicePermissionManager permissions = new SlicePermissionManager(mContext,
                TestableLooper.get(this).getLooper(), sliceDir);

        DirtyTracker.Persistable junk = new DirtyTracker.Persistable() {
            @Override
            public String getFileName() {
                return "invalidData";
            }

            @Override
            public void writeTo(XmlSerializer out) throws IOException {
                throw new RuntimeException("this doesn't work");
            }
        };

        // let's put something bad in here
        permissions.addDirtyImmediate(junk);
        // force a persist. if this throws, it would take down system_server
        permissions.handlePersist();

        // Cleanup.
        assertTrue(FileUtils.deleteContentsAndDir(sliceDir));
    }

}
