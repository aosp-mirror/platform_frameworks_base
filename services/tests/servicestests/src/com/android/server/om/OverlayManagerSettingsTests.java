/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.om;

import static android.content.om.OverlayInfo.STATE_DISABLED;
import static android.content.om.OverlayInfo.STATE_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.om.OverlayInfo;
import android.text.TextUtils;
import android.util.Xml;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@RunWith(AndroidJUnit4.class)
public class OverlayManagerSettingsTests {
    private OverlayManagerSettings mSettings;

    private static final OverlayInfo OVERLAY_A0 = new OverlayInfo(
            "com.dummy.overlay_a",
            "com.dummy.target",
            null,
            "some-category",
            "/data/app/com.dummy.overlay_a-1/base.apk",
            STATE_DISABLED,
            0,
            0,
            true);

    private static final OverlayInfo OVERLAY_B0 = new OverlayInfo(
            "com.dummy.overlay_b",
            "com.dummy.target",
            null,
            "some-category",
            "/data/app/com.dummy.overlay_b-1/base.apk",
            STATE_DISABLED,
            0,
            0,
            true);

    private static final OverlayInfo OVERLAY_C0 = new OverlayInfo(
            "com.dummy.overlay_c",
            "com.dummy.target",
            null,
            "some-category",
            "/data/app/com.dummy.overlay_c-1/base.apk",
            STATE_DISABLED,
            0,
            0,
            true);

    private static final OverlayInfo OVERLAY_A1 = new OverlayInfo(
            "com.dummy.overlay_a",
            "com.dummy.target",
            null,
            "some-category",
            "/data/app/com.dummy.overlay_a-1/base.apk",
            STATE_DISABLED,
            1,
            0,
            true);

    private static final OverlayInfo OVERLAY_B1 = new OverlayInfo(
            "com.dummy.overlay_b",
            "com.dummy.target",
            null,
            "some-category",
            "/data/app/com.dummy.overlay_b-1/base.apk",
            STATE_DISABLED,
            1,
            0,
            true);

    @Before
    public void setUp() throws Exception {
        mSettings = new OverlayManagerSettings();
    }

    // tests: generic functionality

    @Test
    public void testSettingsInitiallyEmpty() throws Exception {
        final int userId = 0;
        Map<String, List<OverlayInfo>> map = mSettings.getOverlaysForUser(userId);
        assertEquals(0, map.size());
    }

    @Test
    public void testBasicSetAndGet() throws Exception {
        assertDoesNotContain(mSettings, OVERLAY_A0.packageName, OVERLAY_A0.userId);

        insert(OVERLAY_A0);
        assertContains(mSettings, OVERLAY_A0);
        OverlayInfo oi = mSettings.getOverlayInfo(OVERLAY_A0.packageName, OVERLAY_A0.userId);
        assertEquals(OVERLAY_A0, oi);

        assertTrue(mSettings.remove(OVERLAY_A0.packageName, OVERLAY_A0.userId));
        assertDoesNotContain(mSettings, OVERLAY_A0.packageName, OVERLAY_A0.userId);
    }

    @Test
    public void testGetUsers() throws Exception {
        int[] users = mSettings.getUsers();
        assertEquals(0, users.length);

        insert(OVERLAY_A0);
        users = mSettings.getUsers();
        assertEquals(1, users.length);
        assertContains(users, OVERLAY_A0.userId);

        insert(OVERLAY_A1);
        insert(OVERLAY_B1);
        users = mSettings.getUsers();
        assertEquals(2, users.length);
        assertContains(users, OVERLAY_A0.userId);
        assertContains(users, OVERLAY_A1.userId);
    }

    @Test
    public void testGetOverlaysForUser() throws Exception {
        insert(OVERLAY_A0);
        insert(OVERLAY_B0);
        insert(OVERLAY_A1);
        insert(OVERLAY_B1);

        Map<String, List<OverlayInfo>> map = mSettings.getOverlaysForUser(OVERLAY_A0.userId);
        assertEquals(1, map.keySet().size());
        assertTrue(map.keySet().contains(OVERLAY_A0.targetPackageName));

        List<OverlayInfo> list = map.get(OVERLAY_A0.targetPackageName);
        assertEquals(2, list.size());
        assertTrue(list.contains(OVERLAY_A0));
        assertTrue(list.contains(OVERLAY_B0));

        // getOverlaysForUser should never return null
        map = mSettings.getOverlaysForUser(-1);
        assertNotNull(map);
        assertEquals(0, map.size());
    }

    @Test
    public void testRemoveUser() throws Exception {
        insert(OVERLAY_A0);
        insert(OVERLAY_B0);
        insert(OVERLAY_A1);

        assertContains(mSettings, OVERLAY_A0);
        assertContains(mSettings, OVERLAY_B0);
        assertContains(mSettings, OVERLAY_A1);

        mSettings.removeUser(OVERLAY_A0.userId);

        assertDoesNotContain(mSettings, OVERLAY_A0);
        assertDoesNotContain(mSettings, OVERLAY_B0);
        assertContains(mSettings, OVERLAY_A1);
    }

    @Test
    public void testOrderOfNewlyAddedItems() throws Exception {
        // new items are appended to the list
        insert(OVERLAY_A0);
        insert(OVERLAY_B0);
        insert(OVERLAY_C0);

        List<OverlayInfo> list =
                mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_A0, OVERLAY_B0, OVERLAY_C0);

        // overlays keep their positions when updated
        mSettings.setState(OVERLAY_B0.packageName, OVERLAY_B0.userId, STATE_ENABLED);
        OverlayInfo oi = mSettings.getOverlayInfo(OVERLAY_B0.packageName, OVERLAY_B0.userId);

        list = mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_A0, oi, OVERLAY_C0);
    }

    @Test
    public void testSetPriority() throws Exception {
        insert(OVERLAY_A0);
        insert(OVERLAY_B0);
        insert(OVERLAY_C0);

        List<OverlayInfo> list =
                mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_A0, OVERLAY_B0, OVERLAY_C0);

        boolean changed = mSettings.setPriority(OVERLAY_B0.packageName, OVERLAY_C0.packageName,
                OVERLAY_B0.userId);
        assertTrue(changed);
        list = mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_A0, OVERLAY_C0, OVERLAY_B0);

        changed =
            mSettings.setPriority(OVERLAY_B0.packageName, "does.not.exist", OVERLAY_B0.userId);
        assertFalse(changed);
        list = mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_A0, OVERLAY_C0, OVERLAY_B0);

        OverlayInfo otherTarget = new OverlayInfo(
                "com.dummy.overlay_other",
                "com.dummy.some.other.target",
                null,
                "some-category",
                "/data/app/com.dummy.overlay_other-1/base.apk",
                STATE_DISABLED,
                0,
                0,
                true);
        insert(otherTarget);
        changed = mSettings.setPriority(OVERLAY_A0.packageName, otherTarget.packageName,
                OVERLAY_A0.userId);
        assertFalse(changed);
    }

    @Test
    public void testSetLowestPriority() throws Exception {
        insert(OVERLAY_A0);
        insert(OVERLAY_B0);
        insert(OVERLAY_C0);

        List<OverlayInfo> list =
                mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_A0, OVERLAY_B0, OVERLAY_C0);

        boolean changed = mSettings.setLowestPriority(OVERLAY_B0.packageName, OVERLAY_B0.userId);
        assertTrue(changed);

        list = mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_B0, OVERLAY_A0, OVERLAY_C0);
    }

    @Test
    public void testSetHighestPriority() throws Exception {
        insert(OVERLAY_A0);
        insert(OVERLAY_B0);
        insert(OVERLAY_C0);

        List<OverlayInfo> list =
                mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_A0, OVERLAY_B0, OVERLAY_C0);

        boolean changed = mSettings.setHighestPriority(OVERLAY_B0.packageName, OVERLAY_B0.userId);
        assertTrue(changed);

        list = mSettings.getOverlaysForTarget(OVERLAY_A0.targetPackageName, OVERLAY_A0.userId);
        assertListsAreEqual(list, OVERLAY_A0, OVERLAY_C0, OVERLAY_B0);
    }

    // tests: persist and restore

    @Test
    public void testPersistEmpty() throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        mSettings.persist(os);
        String xml = new String(os.toByteArray(), "utf-8");

        assertEquals(1, countXmlTags(xml, "overlays"));
        assertEquals(0, countXmlTags(xml, "item"));
    }

    @Test
    public void testPersistDifferentOverlaysSameUser() throws Exception {
        insert(OVERLAY_A0);
        insert(OVERLAY_B0);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        mSettings.persist(os);
        final String xml = new String(os.toByteArray(), "utf-8");

        assertEquals(1, countXmlTags(xml, "overlays"));
        assertEquals(2, countXmlTags(xml, "item"));
        assertEquals(1, countXmlAttributesWhere(xml, "item", "packageName",
                    OVERLAY_A0.packageName));
        assertEquals(1, countXmlAttributesWhere(xml, "item", "packageName",
                    OVERLAY_B0.packageName));
        assertEquals(2, countXmlAttributesWhere(xml, "item", "userId",
                    Integer.toString(OVERLAY_A0.userId)));
    }

    @Test
    public void testPersistSameOverlayDifferentUsers() throws Exception {
        insert(OVERLAY_A0);
        insert(OVERLAY_A1);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        mSettings.persist(os);
        String xml = new String(os.toByteArray(), "utf-8");

        assertEquals(1, countXmlTags(xml, "overlays"));
        assertEquals(2, countXmlTags(xml, "item"));
        assertEquals(2, countXmlAttributesWhere(xml, "item", "packageName",
                    OVERLAY_A0.packageName));
        assertEquals(1, countXmlAttributesWhere(xml, "item", "userId",
                    Integer.toString(OVERLAY_A0.userId)));
        assertEquals(1, countXmlAttributesWhere(xml, "item", "userId",
                    Integer.toString(OVERLAY_A1.userId)));
    }

    @Test
    public void testPersistEnabled() throws Exception {
        insert(OVERLAY_A0);
        mSettings.setEnabled(OVERLAY_A0.packageName, OVERLAY_A0.userId, true);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        mSettings.persist(os);
        String xml = new String(os.toByteArray(), "utf-8");

        assertEquals(1, countXmlAttributesWhere(xml, "item", "isEnabled", "true"));
    }

    @Test
    public void testRestoreEmpty() throws Exception {
        final int version = OverlayManagerSettings.Serializer.CURRENT_VERSION;
        final String xml =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<overlays version=\"" + version + "\" />\n";
        ByteArrayInputStream is = new ByteArrayInputStream(xml.getBytes("utf-8"));

        mSettings.restore(is);
        assertDoesNotContain(mSettings, "com.dummy.overlay", 0);
    }

    @Test
    public void testRestoreSingleUserSingleOverlay() throws Exception {
        final int version = OverlayManagerSettings.Serializer.CURRENT_VERSION;
        final String xml =
                "<?xml version='1.0' encoding='utf-8' standalone='yes'?>\n"
                + "<overlays version='" + version + "'>\n"
                + "<item packageName='com.dummy.overlay'\n"
                + "      userId='1234'\n"
                + "      targetPackageName='com.dummy.target'\n"
                + "      baseCodePath='/data/app/com.dummy.overlay-1/base.apk'\n"
                + "      state='" + STATE_DISABLED + "'\n"
                + "      isEnabled='false'\n"
                + "      category='dummy-category'\n"
                + "      isStatic='false'\n"
                + "      priority='0' />\n"
                + "</overlays>\n";
        ByteArrayInputStream is = new ByteArrayInputStream(xml.getBytes("utf-8"));

        mSettings.restore(is);
        OverlayInfo oi = mSettings.getOverlayInfo("com.dummy.overlay", 1234);
        assertNotNull(oi);
        assertEquals("com.dummy.overlay", oi.packageName);
        assertEquals("com.dummy.target", oi.targetPackageName);
        assertEquals("/data/app/com.dummy.overlay-1/base.apk", oi.baseCodePath);
        assertEquals(1234, oi.userId);
        assertEquals(STATE_DISABLED, oi.state);
        assertFalse(mSettings.getEnabled("com.dummy.overlay", 1234));
    }

    @Test
    public void testPersistAndRestore() throws Exception {
        insert(OVERLAY_A0);
        insert(OVERLAY_B1);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        mSettings.persist(os);
        String xml = new String(os.toByteArray(), "utf-8");
        ByteArrayInputStream is = new ByteArrayInputStream(xml.getBytes("utf-8"));
        OverlayManagerSettings newSettings = new OverlayManagerSettings();
        newSettings.restore(is);

        OverlayInfo a = newSettings.getOverlayInfo(OVERLAY_A0.packageName, OVERLAY_A0.userId);
        assertEquals(OVERLAY_A0, a);

        OverlayInfo b = newSettings.getOverlayInfo(OVERLAY_B1.packageName, OVERLAY_B1.userId);
        assertEquals(OVERLAY_B1, b);
    }

    private int countXmlTags(String xml, String tagToLookFor) throws Exception {
        int count = 0;
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && tagToLookFor.equals(parser.getName())) {
                count++;
            }
            event = parser.next();
        }
        return count;
    }

    private int countXmlAttributesWhere(String xml, String tag, String attr, String value)
            throws Exception {
        int count = 0;
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && tag.equals(parser.getName())) {
                String v = parser.getAttributeValue(null, attr);
                if (value.equals(v)) {
                    count++;
                }
            }
            event = parser.next();
        }
        return count;
    }

    private void insert(OverlayInfo oi) throws Exception {
        mSettings.init(oi.packageName, oi.userId, oi.targetPackageName, null, oi.baseCodePath,
                true, false,0, oi.category);
        mSettings.setState(oi.packageName, oi.userId, oi.state);
        mSettings.setEnabled(oi.packageName, oi.userId, false);
    }

    private static void assertContains(final OverlayManagerSettings settings,
            final OverlayInfo oi) {
        assertContains(settings, oi.packageName, oi.userId);
    }

    private static void assertContains(final OverlayManagerSettings settings,
            final String packageName, int userId) {
        try {
            settings.getOverlayInfo(packageName, userId);
        } catch (OverlayManagerSettings.BadKeyException e) {
            fail(String.format("settings does not contain packageName=%s userId=%d",
                        packageName, userId));
        }
    }

    private static void assertDoesNotContain(final OverlayManagerSettings settings,
            final OverlayInfo oi) {
        assertDoesNotContain(settings, oi.packageName, oi.userId);
    }

    private static void assertDoesNotContain(final OverlayManagerSettings settings,
            final String packageName, int userId) {
        try {
            settings.getOverlayInfo(packageName, userId);
            fail(String.format("settings contains packageName=%s userId=%d", packageName, userId));
        } catch (OverlayManagerSettings.BadKeyException e) {
            // do nothing: we expect to end up here
        }
    }

    private static void assertContains(int[] haystack, int needle) {
        List<Integer> list = IntStream.of(haystack)
                .boxed()
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (!list.contains(needle)) {
            fail(String.format("integer array [%s] does not contain value %s",
                        TextUtils.join(",", list), needle));
        }
    }

    private static void assertDoesNotContain(int[] haystack, int needle) {
        List<Integer> list = IntStream.of(haystack)
                .boxed()
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (list.contains(needle)) {
            fail(String.format("integer array [%s] contains value %s",
                        TextUtils.join(",", list), needle));
        }
    }

    private static void assertListsAreEqual(List<OverlayInfo> list, OverlayInfo... array) {
        List<OverlayInfo> other = Stream.of(array)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        assertListsAreEqual(list, other);
    }

    private static void assertListsAreEqual(List<OverlayInfo> list, List<OverlayInfo> other) {
        if (!list.equals(other)) {
            fail(String.format("lists [%s] and [%s] differ",
                        TextUtils.join(",", list), TextUtils.join(",", other)));
        }
    }
}
