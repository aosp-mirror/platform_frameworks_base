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

import android.os.UserHandle;
import android.util.Xml.Encoding;

import androidx.test.filters.SmallTest;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@SmallTest
public class SliceFullAccessListTest extends UiServiceTestCase {

    private static final String TEST_XML = "<slice-access-list version=\"1\"><user "
            + "user=\"0\"><pkg>pkg</pkg><pkg>pkg1</pkg></user><user "
            + "user=\"1\"><pkg>pkg</pkg></user><user "
            + "user=\"3\"><pkg>pkg2</pkg></user></slice-access-list>";

    private SliceFullAccessList mAccessList;

    @Before
    public void setup() {
        mAccessList = new SliceFullAccessList(mContext);
    }

    @Test
    public void testNoDefaultAccess() {
        assertFalse(mAccessList.hasFullAccess("pkg", 0));
    }

    @Test
    public void testGrantAccess() {
        mAccessList.grantFullAccess("pkg", 0);
        assertTrue(mAccessList.hasFullAccess("pkg", 0));
    }

    @Test
    public void testUserSeparation() {
        mAccessList.grantFullAccess("pkg", 1);
        assertFalse(mAccessList.hasFullAccess("pkg", 0));
    }

    @Test
    public void testRemoveAccess() {
        mAccessList.grantFullAccess("pkg", 0);
        assertTrue(mAccessList.hasFullAccess("pkg", 0));

        mAccessList.removeGrant("pkg", 0);
        assertFalse(mAccessList.hasFullAccess("pkg", 0));
    }

    @Test
    public void testSerialization() throws XmlPullParserException, IOException {
        mAccessList.grantFullAccess("pkg", 0);
        mAccessList.grantFullAccess("pkg1", 0);
        mAccessList.grantFullAccess("pkg", 1);
        mAccessList.grantFullAccess("pkg2", 3);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        XmlSerializer out = XmlPullParserFactory.newInstance().newSerializer();
        out.setOutput(output, Encoding.UTF_8.name());
        mAccessList.writeXml(out, UserHandle.USER_ALL);
        out.flush();

        assertEquals(TEST_XML, output.toString(Encoding.UTF_8.name()));
    }

    @Test
    public void testDeSerialization() throws XmlPullParserException, IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(TEST_XML.getBytes());
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(input, Encoding.UTF_8.name());

        mAccessList.readXml(parser);

        assertTrue(mAccessList.hasFullAccess("pkg", 0));
        assertTrue(mAccessList.hasFullAccess("pkg1", 0));
        assertTrue(mAccessList.hasFullAccess("pkg", 1));
        assertTrue(mAccessList.hasFullAccess("pkg2", 3));

        assertFalse(mAccessList.hasFullAccess("pkg3", 0));
        assertFalse(mAccessList.hasFullAccess("pkg1", 1));
        assertFalse(mAccessList.hasFullAccess("pkg", 3));
        assertFalse(mAccessList.hasFullAccess("pkg", 2));
    }
}