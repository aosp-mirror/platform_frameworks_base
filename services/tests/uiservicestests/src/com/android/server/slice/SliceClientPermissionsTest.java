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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.net.Uri;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.Xml.Encoding;

import androidx.test.filters.SmallTest;

import com.android.server.UiServiceTestCase;
import com.android.server.slice.SliceClientPermissions.SliceAuthority;
import com.android.server.slice.SlicePermissionManager.PkgUser;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class SliceClientPermissionsTest extends UiServiceTestCase {

    @Test
    public void testRemoveBasic() {
        PkgUser pkg = new PkgUser("com.android.pkg", 0);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceClientPermissions client = new SliceClientPermissions(pkg, tracker);
        Uri base = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority("com.android.pkg.slices").build();

        PkgUser testPkg = new PkgUser("other", 2);

        client.grantUri(base.buildUpon()
                .appendPath("first")
                .build(), testPkg);
        client.revokeUri(base.buildUpon()
                .appendPath("first")
                .build(), testPkg);

        assertFalse(client.hasPermission(base.buildUpon()
                .appendPath("first")
                .appendPath("third")
                .build(), testPkg.getUserId()));

        ArrayList<SliceAuthority> authorities = new ArrayList<>(client.getAuthorities());
        assertEquals(0, authorities.get(0).getPaths().size());
    }

    @Test
    public void testRemoveSubtrees() {
        PkgUser pkg = new PkgUser("com.android.pkg", 0);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceClientPermissions client = new SliceClientPermissions(pkg, tracker);
        Uri base = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority("com.android.pkg.slices").build();

        PkgUser testPkg = new PkgUser("other", 2);

        client.grantUri(base.buildUpon()
                .appendPath("first")
                .appendPath("second")
                .build(), testPkg);
        client.grantUri(base.buildUpon()
                .appendPath("first")
                .appendPath("third")
                .build(), testPkg);
        client.revokeUri(base.buildUpon()
                .appendPath("first")
                .build(), testPkg);

        assertFalse(client.hasPermission(base.buildUpon()
                .appendPath("first")
                .appendPath("fourth")
                .build(), testPkg.getUserId()));

        ArrayList<SliceAuthority> authorities = new ArrayList<>(client.getAuthorities());
        assertEquals(0, authorities.get(0).getPaths().size());
    }

    @Test
    public void testAddConsolidate_addFirst() {
        PkgUser pkg = new PkgUser("com.android.pkg", 0);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceClientPermissions client = new SliceClientPermissions(pkg, tracker);
        Uri base = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority("com.android.pkg.slices").build();

        PkgUser testPkg = new PkgUser("other", 2);

        client.grantUri(base.buildUpon()
                .appendPath("first")
                .build(), testPkg);
        client.grantUri(base.buildUpon()
                .appendPath("first")
                .appendPath("second")
                .build(), testPkg);

        assertTrue(client.hasPermission(base.buildUpon()
                .appendPath("first")
                .appendPath("third")
                .build(), testPkg.getUserId()));

        ArrayList<SliceAuthority> authorities = new ArrayList<>(client.getAuthorities());
        assertEquals(1, authorities.get(0).getPaths().size());
    }

    @Test
    public void testAddConsolidate_addSecond() {
        PkgUser pkg = new PkgUser("com.android.pkg", 0);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceClientPermissions client = new SliceClientPermissions(pkg, tracker);
        Uri base = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority("com.android.pkg.slices").build();

        PkgUser testPkg = new PkgUser("other", 2);

        client.grantUri(base.buildUpon()
                .appendPath("first")
                .appendPath("second")
                .build(), testPkg);
        client.grantUri(base.buildUpon()
                .appendPath("first")
                .build(), testPkg);

        assertTrue(client.hasPermission(base.buildUpon()
                .appendPath("first")
                .appendPath("third")
                .build(), testPkg.getUserId()));

        ArrayList<SliceAuthority> authorities = new ArrayList<>(client.getAuthorities());
        assertEquals(1, authorities.get(0).getPaths().size());
    }

    @Test
    public void testDirty_addAuthority() {
        PkgUser pkg = new PkgUser("com.android.pkg", 0);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceClientPermissions client = new SliceClientPermissions(pkg, tracker);

        client.getOrCreateAuthority(new PkgUser("some_auth", 2), new PkgUser("com.pkg", 2));

        verify(tracker).onPersistableDirty(eq(client));
    }

    @Test
    public void testDirty_addPkg() {
        PkgUser pkg = new PkgUser("com.android.pkg", 0);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceClientPermissions client = new SliceClientPermissions(pkg, tracker);

        SliceAuthority auth = client.getOrCreateAuthority(
                new PkgUser("some_auth", 2),
                new PkgUser("com.pkg", 2));
        clearInvocations(tracker);

        auth.addPath(Arrays.asList("/something/"));

        verify(tracker).onPersistableDirty(eq(client));
    }

    @Test
    public void testCreation() {
        SliceClientPermissions client = createClient();
        ArrayList<SliceAuthority> authorities = new ArrayList<>(client.getAuthorities());
        authorities.sort(Comparator.comparing(SliceAuthority::getAuthority));

        assertEquals(2, authorities.size());
        assertEquals("com.android.pkg", authorities.get(0).getAuthority());
        assertEquals("com.android.pkg.slices", authorities.get(1).getAuthority());

        assertEquals(1, authorities.get(0).getPaths().size());
        assertEquals(2, authorities.get(1).getPaths().size());
    }

    @Test
    public void testSerialization() throws XmlPullParserException, IOException {
        SliceClientPermissions client = createClient();
        client.setHasFullAccess(true);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XmlSerializer serializer = XmlPullParserFactory.newInstance().newSerializer();
        serializer.setOutput(output, Encoding.UTF_8.name());

        client.writeTo(serializer);
        serializer.flush();

        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(input, Encoding.UTF_8.name());

        SliceClientPermissions deser = SliceClientPermissions.createFrom(parser,
                mock(DirtyTracker.class));

        assertEquivalent(client, deser);
    }

    @Test(expected = XmlPullParserException.class)
    public void testReadEmptyFile_ThrowException() throws XmlPullParserException, IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XmlSerializer serializer = XmlPullParserFactory.newInstance().newSerializer();
        serializer.setOutput(output, Encoding.UTF_8.name());
        // create empty xml document
        serializer.startDocument(null, true);
        serializer.endDocument();
        serializer.flush();

        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(input, Encoding.UTF_8.name());
        SliceClientPermissions.createFrom(parser, mock(DirtyTracker.class));
        // Should throw exception since the xml is empty
    }

    private void assertEquivalent(SliceClientPermissions o1, SliceClientPermissions o2) {
        assertEquals(o1.getPkg(), o2.getPkg());
        ArrayList<SliceAuthority> a1 = new ArrayList<>(o1.getAuthorities());
        ArrayList<SliceAuthority> a2 = new ArrayList<>(o2.getAuthorities());
        a1.sort(Comparator.comparing(SliceAuthority::getAuthority));
        a2.sort(Comparator.comparing(SliceAuthority::getAuthority));
        assertEquals(a1, a2);
    }

    private static SliceClientPermissions createClient() {
        PkgUser pkg = new PkgUser("com.android.pkg", 2);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceClientPermissions client = new SliceClientPermissions(pkg, tracker);

        SliceAuthority auth = client.getOrCreateAuthority(
                new PkgUser("com.android.pkg.slices", 3),
                new PkgUser("com.android.pkg", 3));
        auth.addPath(Arrays.asList("/something/"));
        auth.addPath(Arrays.asList("/something/else"));

        auth = client.getOrCreateAuthority(
                new PkgUser("com.android.pkg", 3),
                new PkgUser("com.pkg", 1));
        auth.addPath(Arrays.asList("/somewhere"));
        return client;
    }

}