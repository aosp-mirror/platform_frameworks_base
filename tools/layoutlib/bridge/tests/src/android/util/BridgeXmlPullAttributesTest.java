/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.util;

import com.android.ide.common.rendering.api.RenderResources;
import com.android.layoutlib.bridge.BridgeConstants;
import com.android.layoutlib.bridge.android.BridgeContext;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;

import com.google.common.collect.ImmutableMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BridgeXmlPullAttributesTest {
    @Test
    public void testGetAttributeIntValueForEnums() {
        RenderResources renderResources = new RenderResources();

        XmlPullParser parser = mock(XmlPullParser.class);
        when(parser.getAttributeValue(BridgeConstants.NS_RESOURCES, "layout_width"))
                .thenReturn("match_parent");
        when(parser.getAttributeName(0)).thenReturn("layout_width");
        when(parser.getAttributeNamespace(0)).thenReturn(BridgeConstants.NS_RESOURCES);
        // Return every value twice since there is one test using name and other using index
        when(parser.getAttributeValue("http://custom", "my_custom_attr"))
                .thenReturn("a", "a", "b", "b", "invalid", "invalid");
        when(parser.getAttributeName(1)).thenReturn("my_custom_attr");
        when(parser.getAttributeNamespace(1)).thenReturn("http://custom");

        BridgeContext context = mock(BridgeContext.class);
        when(context.getRenderResources()).thenReturn(renderResources);

        BridgeXmlPullAttributes attributes = new BridgeXmlPullAttributes(
                parser,
                context,
                false,
                attrName -> {
                    if ("layout_width".equals(attrName)) {
                        return ImmutableMap.of(
                                "match_parent", 123);
                    }
                    return ImmutableMap.of();
                },
                attrName -> {
                    if ("my_custom_attr".equals(attrName)) {
                        return ImmutableMap.of(
                                "a", 1,
                                "b", 2
                        );
                    }
                    return ImmutableMap.of();
                });

        // Test a framework defined enum attribute
        assertEquals(123, attributes.getAttributeIntValue(BridgeConstants.NS_RESOURCES,
                "layout_width", 500));
        assertEquals(123, attributes.getAttributeIntValue(0, 500));
        // Test non existing attribute (it should return the default value)
        assertEquals(500, attributes.getAttributeIntValue(BridgeConstants.NS_RESOURCES,
                "layout_height", 500));
        assertEquals(500, attributes.getAttributeIntValue(2, 500));

        // Test project defined enum attribute
        assertEquals(1, attributes.getAttributeIntValue("http://custom",
                "my_custom_attr", 500));
        assertEquals(1, attributes.getAttributeIntValue(1, 500));
        assertEquals(2, attributes.getAttributeIntValue("http://custom",
                "my_custom_attr", 500));
        assertEquals(2, attributes.getAttributeIntValue(1, 500));
        // Test an invalid enum
        boolean exception = false;
        try {
            attributes.getAttributeIntValue("http://custom", "my_custom_attr", 500);
        } catch(NumberFormatException e) {
            exception = true;
        }
        assertTrue(exception);
        exception = false;
        try {
            attributes.getAttributeIntValue(1, 500);
        } catch(NumberFormatException e) {
            exception = true;
        }
        assertTrue(exception);

        // Test non existing project attribute
        assertEquals(500, attributes.getAttributeIntValue("http://custom",
                "my_other_attr", 500));
    }

}