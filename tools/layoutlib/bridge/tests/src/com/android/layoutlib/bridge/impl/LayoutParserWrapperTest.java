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

package com.android.layoutlib.bridge.impl;

import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;

import static com.android.SdkConstants.NS_RESOURCES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;


public class LayoutParserWrapperTest {
    @Test
    @SuppressWarnings("StatementWithEmptyBody")  // some for loops need to be empty statements.
    public void testDataBindingLayout() throws Exception {
        LayoutParserWrapper parser = getParserFromString(sDataBindingLayout);
        parser.peekTillLayoutStart();
        assertEquals("Expected START_TAG", START_TAG, parser.next());
        assertEquals("RelativeLayout", parser.getName());
        for (int next = parser.next(); next != START_TAG && next != END_DOCUMENT;
             next = parser.next());
        assertEquals("Expected START_TAG", START_TAG, parser.getEventType());
        assertEquals("TextView", parser.getName());
        assertEquals("layout_width incorrect for first text view.", "wrap_content",
                parser.getAttributeValue(NS_RESOURCES, "layout_width"));
        // Ensure that data-binding part is stripped.
        assertEquals("Bound attribute android:text incorrect", "World",
                parser.getAttributeValue(NS_RESOURCES, "text"));
        assertEquals("resource attribute 'id' for first text view incorrect.", "@+id/first",
                parser.getAttributeValue(NS_RESOURCES, "id"));
        for (int next = parser.next();
             (next != END_TAG || !"RelativeLayout".equals(parser.getName())) && next != END_DOCUMENT;
             next = parser.next());
        assertNotSame("Unexpected end of document", END_DOCUMENT, parser.getEventType());
        assertEquals("Document didn't end when expected.", END_DOCUMENT, parser.next());
    }

    @Test
    @SuppressWarnings("StatementWithEmptyBody")
    public void testNonDataBindingLayout() throws Exception {
        LayoutParserWrapper parser = getParserFromString(sNonDataBindingLayout);
        parser.peekTillLayoutStart();
        assertEquals("Expected START_TAG", START_TAG, parser.next());
        assertEquals("RelativeLayout", parser.getName());
        for (int next = parser.next(); next != START_TAG && next != END_DOCUMENT;
             next = parser.next());
        assertEquals("Expected START_TAG", START_TAG, parser.getEventType());
        assertEquals("TextView", parser.getName());
        assertEquals("layout_width incorrect for first text view.", "wrap_content",
                parser.getAttributeValue(NS_RESOURCES, "layout_width"));
        // Ensure that value isn't modified.
        assertEquals("Bound attribute android:text incorrect", "@{user.firstName,default=World}",
                parser.getAttributeValue(NS_RESOURCES, "text"));
        assertEquals("resource attribute 'id' for first text view incorrect.", "@+id/first",
                parser.getAttributeValue(NS_RESOURCES, "id"));
        for (int next = parser.next();
             (next != END_TAG || !"RelativeLayout".equals(parser.getName())) && next != END_DOCUMENT;
             next = parser.next());
        assertNotSame("Unexpected end of document", END_DOCUMENT, parser.getEventType());
        assertEquals("Document didn't end when expected.", END_DOCUMENT, parser.next());
    }

    private static LayoutParserWrapper getParserFromString(String layoutContent) throws
            XmlPullParserException {
        XmlPullParser parser = new KXmlParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new StringReader(layoutContent));
        return new LayoutParserWrapper(parser);
    }

    private static final String sDataBindingLayout =
            //language=XML
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<layout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "        xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n" +
                    "        xmlns:tools=\"http://schemas.android.com/tools\"\n" +
                    "        tools:context=\".MainActivity\"\n" +
                    "        tools:showIn=\"@layout/activity_main\">\n" +
                    "\n" +
                    "    <data>\n" +
                    "\n" +
                    "        <variable\n" +
                    "            name=\"user\"\n" +
                    "            type=\"com.example.User\" />\n" +
                    "        <variable\n" +
                    "            name=\"activity\"\n" +
                    "            type=\"com.example.MainActivity\" />\n" +
                    "    </data>\n" +
                    "\n" +
                    "    <RelativeLayout\n" +
                    "        android:layout_width=\"match_parent\"\n" +
                    "        android:layout_height=\"match_parent\"\n" +
                    "        android:paddingBottom=\"@dimen/activity_vertical_margin\"\n" +
                    "        android:paddingLeft=\"@dimen/activity_horizontal_margin\"\n" +
                    "        android:paddingRight=\"@dimen/activity_horizontal_margin\"\n" +
                    "        android:paddingTop=\"@dimen/activity_vertical_margin\"\n" +
                    "        app:layout_behavior=\"@string/appbar_scrolling_view_behavior\"\n" +
                    "    >\n" +
                    "\n" +
                    "        <TextView\n" +
                    "            android:id=\"@+id/first\"\n" +
                    "            android:layout_width=\"wrap_content\"\n" +
                    "            android:layout_alignParentStart=\"true\"\n" +
                    "            android:layout_alignParentLeft=\"true\"\n" +
                    "            android:layout_height=\"wrap_content\"\n" +
                    "            android:text=\"@{user.firstName,default=World}\" />\n" +
                    "\n" +
                    "        <TextView\n" +
                    "            android:id=\"@+id/last\"\n" +
                    "            android:layout_width=\"wrap_content\"\n" +
                    "            android:layout_height=\"wrap_content\"\n" +
                    "            android:layout_toEndOf=\"@id/first\"\n" +
                    "            android:layout_toRightOf=\"@id/first\"\n" +
                    "            android:text=\"@{user.lastName,default=Hello}\" />\n" +
                    "\n" +
                    "        <Button\n" +
                    "            android:layout_width=\"wrap_content\"\n" +
                    "            android:layout_height=\"wrap_content\"\n" +
                    "            android:layout_below=\"@id/last\"\n" +
                    "            android:text=\"Submit\"\n" +
                    "            android:onClick=\"@{activity.onClick}\"/>\n" +
                    "    </RelativeLayout>\n" +
                    "</layout>";

    private static final String sNonDataBindingLayout =
            //language=XML
            "<RelativeLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"\n" +
                    "    android:layout_width=\"match_parent\"\n" +
                    "    android:layout_height=\"match_parent\"\n" +
                    "    android:paddingBottom=\"@dimen/activity_vertical_margin\"\n" +
                    "    android:paddingLeft=\"@dimen/activity_horizontal_margin\"\n" +
                    "    android:paddingRight=\"@dimen/activity_horizontal_margin\"\n" +
                    "    android:paddingTop=\"@dimen/activity_vertical_margin\"\n" +
                    "    app:layout_behavior=\"@string/appbar_scrolling_view_behavior\"\n" +
                    ">\n" +
                    "\n" +
                    "    <TextView\n" +
                    "        android:id=\"@+id/first\"\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_alignParentStart=\"true\"\n" +
                    "        android:layout_alignParentLeft=\"true\"\n" +
                    "        android:layout_height=\"wrap_content\"\n" +
                    "        android:text=\"@{user.firstName,default=World}\" />\n" +
                    "\n" +
                    "    <TextView\n" +
                    "        android:id=\"@+id/last\"\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_height=\"wrap_content\"\n" +
                    "        android:layout_toEndOf=\"@id/first\"\n" +
                    "        android:layout_toRightOf=\"@id/first\"\n" +
                    "        android:text=\"@{user.lastName,default=Hello}\" />\n" +
                    "\n" +
                    "    <Button\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_height=\"wrap_content\"\n" +
                    "        android:layout_below=\"@id/last\"\n" +
                    "        android:text=\"Submit\"\n" +
                    "        android:onClick=\"@{activity.onClick}\"/>\n" +
                    "</RelativeLayout>";
}
