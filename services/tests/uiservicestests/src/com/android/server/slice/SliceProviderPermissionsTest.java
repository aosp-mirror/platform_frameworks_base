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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.Xml.Encoding;

import com.android.server.UiServiceTestCase;
import com.android.server.slice.SlicePermissionManager.PkgUser;
import com.android.server.slice.SliceProviderPermissions.SliceAuthority;

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
import java.util.Comparator;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class SliceProviderPermissionsTest extends UiServiceTestCase {

    @Test
    public void testDirty_addAuthority() {
        PkgUser pkg = new PkgUser("com.android.pkg", 0);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceProviderPermissions provider = new SliceProviderPermissions(pkg, tracker);

        provider.getOrCreateAuthority("some_auth");

        verify(tracker).onPersistableDirty(eq(provider));
    }

    @Test
    public void testDirty_addPkg() {
        PkgUser pkg = new PkgUser("com.android.pkg", 0);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceProviderPermissions provider = new SliceProviderPermissions(pkg, tracker);

        SliceAuthority auth = provider.getOrCreateAuthority("some_auth");
        clearInvocations(tracker);

        auth.addPkg(new PkgUser("pkg", 0));

        verify(tracker).onPersistableDirty(eq(provider));
    }

    @Test
    public void testCreation() {
        SliceProviderPermissions provider = createProvider();
        ArrayList<SliceAuthority> authorities = new ArrayList<>(provider.getAuthorities());
        authorities.sort(Comparator.comparing(SliceAuthority::getAuthority));

        assertEquals(2, authorities.size());
        assertEquals("com.android.pkg", authorities.get(0).getAuthority());
        assertEquals("com.android.pkg.slices", authorities.get(1).getAuthority());

        assertEquals(1, authorities.get(0).getPkgs().size());
        assertEquals(2, authorities.get(1).getPkgs().size());
    }

    @Test
    public void testSerialization() throws XmlPullParserException, IOException {
        SliceProviderPermissions provider = createProvider();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XmlSerializer serializer = XmlPullParserFactory.newInstance().newSerializer();
        serializer.setOutput(output, Encoding.UTF_8.name());

        provider.writeTo(serializer);
        serializer.flush();

        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(input, Encoding.UTF_8.name());

        SliceProviderPermissions deser = SliceProviderPermissions.createFrom(parser,
                mock(DirtyTracker.class));

        assertEquivalent(provider, deser);
    }

    private void assertEquivalent(SliceProviderPermissions o1, SliceProviderPermissions o2) {
        assertEquals(o1.getPkg(), o2.getPkg());
        assertEquals(o1.getAuthorities(), o2.getAuthorities());
    }

    private static SliceProviderPermissions createProvider() {
        PkgUser pkg = new PkgUser("com.android.pkg", 2);
        DirtyTracker tracker = mock(DirtyTracker.class);
        SliceProviderPermissions provider = new SliceProviderPermissions(pkg, tracker);

        SliceAuthority auth = provider.getOrCreateAuthority("com.android.pkg.slices");
        auth.addPkg(new PkgUser("com.example.pkg", 0));
        auth.addPkg(new PkgUser("example.pkg.com", 10));

        auth = provider.getOrCreateAuthority("com.android.pkg");
        auth.addPkg(new PkgUser("com.example.pkg", 2));
        return provider;
    }

}