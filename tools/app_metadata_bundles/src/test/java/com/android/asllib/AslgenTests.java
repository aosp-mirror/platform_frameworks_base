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

package com.android.asllib;

import static org.junit.Assert.assertEquals;

import com.android.asllib.marshallable.AndroidSafetyLabel;
import com.android.asllib.testutils.TestUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RunWith(JUnit4.class)
public class AslgenTests {
    private static final String VALID_MAPPINGS_PATH = "com/android/asllib/validmappings";
    private static final List<String> VALID_MAPPINGS_SUBDIRS =
            List.of("location", "contacts", "general");
    private static final String HR_XML_FILENAME = "hr.xml";
    private static final String OD_XML_FILENAME = "od.xml";

    /** Logic for setting up tests (empty if not yet needed). */
    public static void main(String[] params) throws Exception {}

    /** Tests valid mappings between HR and OD. */
    @Test
    public void testValidMappings() throws Exception {
        System.out.println("start testing valid mappings.");

        for (String subdir : VALID_MAPPINGS_SUBDIRS) {
            Path hrPath = Paths.get(VALID_MAPPINGS_PATH, subdir, HR_XML_FILENAME);
            Path odPath = Paths.get(VALID_MAPPINGS_PATH, subdir, OD_XML_FILENAME);

            System.out.println("hr path: " + hrPath.toString());
            System.out.println("od path: " + odPath.toString());

            InputStream hrStream =
                    getClass().getClassLoader().getResourceAsStream(hrPath.toString());
            String hrContents =
                    TestUtils.getFormattedXml(
                            new String(hrStream.readAllBytes(), StandardCharsets.UTF_8), false);
            InputStream odStream =
                    getClass().getClassLoader().getResourceAsStream(odPath.toString());
            String odContents =
                    TestUtils.getFormattedXml(
                            new String(odStream.readAllBytes(), StandardCharsets.UTF_8), false);
            AndroidSafetyLabel aslFromHr =
                    AslConverter.readFromString(hrContents, AslConverter.Format.HUMAN_READABLE);
            String aslToOdStr =
                    TestUtils.getFormattedXml(
                            AslConverter.getXmlAsString(aslFromHr, AslConverter.Format.ON_DEVICE),
                            false);
            AndroidSafetyLabel aslFromOd =
                    AslConverter.readFromString(odContents, AslConverter.Format.ON_DEVICE);
            String aslToHrStr =
                    TestUtils.getFormattedXml(
                            AslConverter.getXmlAsString(
                                    aslFromOd, AslConverter.Format.HUMAN_READABLE),
                            false);

            System.out.println("od expected: " + odContents);
            System.out.println("asl to od: " + aslToOdStr);
            assertEquals(odContents, aslToOdStr);

            System.out.println("hr expected: " + hrContents);
            System.out.println("asl to hr: " + aslToHrStr);
            assertEquals(hrContents, aslToHrStr);
        }
    }
}
