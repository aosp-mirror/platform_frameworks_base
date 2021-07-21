/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.fonts.FontUpdateRequest;
import android.platform.test.annotations.Presubmit;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class PersistentSystemFontConfigTest {

    @Test
    public void testWriteRead() throws Exception {
        long expectedModifiedDate = 1234567890;
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        config.lastModifiedMillis = expectedModifiedDate;
        config.updatedFontDirs.add("~~abc");
        config.updatedFontDirs.add("~~def");

        FontUpdateRequest.Family fontFamily = parseFontFamily(
                "<family name='test'>"
                + "  <font name=\"test\" />"
                + "</family>");
        config.fontFamilies.add(fontFamily);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PersistentSystemFontConfig.writeToXml(baos, config);

            byte[] written = baos.toByteArray();
            assertThat(written).isNotEmpty();

            try (ByteArrayInputStream bais = new ByteArrayInputStream(written)) {
                PersistentSystemFontConfig.Config another = new PersistentSystemFontConfig.Config();
                PersistentSystemFontConfig.loadFromXml(bais, another);

                assertThat(another.lastModifiedMillis).isEqualTo(expectedModifiedDate);
                assertThat(another.updatedFontDirs).containsExactly("~~abc", "~~def");
                assertThat(another.fontFamilies).containsExactly(fontFamily);
            }
        }
    }

    @Test
    public void testWrongType() throws IOException, XmlPullParserException {
        String xml = "<fontConfig>"
                + "  <lastModifiedDate value=\"string\" />"
                + "</fontConfig>";

        try (ByteArrayInputStream bais =
                     new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
            PersistentSystemFontConfig.loadFromXml(bais, config);
            assertThat(config.lastModifiedMillis).isEqualTo(0);
        }
    }

    private static FontUpdateRequest.Family parseFontFamily(String xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        ByteArrayInputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        parser.setInput(is, "UTF-8");
        parser.nextTag();
        return FontUpdateRequest.Family.readFromXml(parser);
    }
}
