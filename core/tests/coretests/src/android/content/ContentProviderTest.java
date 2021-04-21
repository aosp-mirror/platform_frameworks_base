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
 * limitations under the License
 */

package android.content;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.UserHandle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;

@RunWith(AndroidJUnit4.class)
public class ContentProviderTest {

    private Context mContext;
    private ContentProvider mCp;

    private ApplicationInfo mProviderApp;
    private ProviderInfo mProvider;

    @Before
    public void setUp() {
        mProviderApp = new ApplicationInfo();
        mProviderApp.uid = 10001;

        mProvider = new ProviderInfo();
        mProvider.authority = "com.example";
        mProvider.applicationInfo = mProviderApp;

        mContext = mock(Context.class);

        mCp = mock(ContentProvider.class, withSettings().defaultAnswer(Answers.CALLS_REAL_METHODS));
        mCp.attachInfo(mContext, mProvider);
    }

    @Test
    public void testValidateIncomingUri_Normal() throws Exception {
        assertEquals(Uri.parse("content://com.example/"),
                mCp.validateIncomingUri(Uri.parse("content://com.example/")));
        assertEquals(Uri.parse("content://com.example/foo/bar"),
                mCp.validateIncomingUri(Uri.parse("content://com.example/foo/bar")));
        assertEquals(Uri.parse("content://com.example/foo%2Fbar"),
                mCp.validateIncomingUri(Uri.parse("content://com.example/foo%2Fbar")));
        assertEquals(Uri.parse("content://com.example/foo%2F%2Fbar"),
                mCp.validateIncomingUri(Uri.parse("content://com.example/foo%2F%2Fbar")));
    }

    @Test
    public void testValidateIncomingUri_Shady() throws Exception {
        assertEquals(Uri.parse("content://com.example/"),
                mCp.validateIncomingUri(Uri.parse("content://com.example//")));
        assertEquals(Uri.parse("content://com.example/foo/bar/"),
                mCp.validateIncomingUri(Uri.parse("content://com.example//foo//bar//")));
        assertEquals(Uri.parse("content://com.example/foo/bar/"),
                mCp.validateIncomingUri(Uri.parse("content://com.example/foo///bar/")));
        assertEquals(Uri.parse("content://com.example/foo%2F%2Fbar/baz"),
                mCp.validateIncomingUri(Uri.parse("content://com.example/foo%2F%2Fbar//baz")));
    }

    @Test
    public void testValidateIncomingUri_NonPath() throws Exception {
        // We only touch paths; queries and fragments are left intact
        assertEquals(Uri.parse("content://com.example/foo/bar?foo=b//ar#foo=b//ar"),
                mCp.validateIncomingUri(
                        Uri.parse("content://com.example/foo/bar?foo=b//ar#foo=b//ar")));
    }

    @Test
    public void testCreateContentUriForUser() {
        Uri uri = Uri.parse("content://com.example/foo/bar");
        Uri expectedUri = Uri.parse("content://7@com.example/foo/bar");
        assertEquals(expectedUri, ContentProvider.createContentUriForUser(uri, UserHandle.of(7)));
    }
}
