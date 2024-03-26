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

package com.android.aslgen;

import static org.junit.Assert.assertEquals;

import com.android.asllib.AndroidSafetyLabel;
import com.android.asllib.AslConverter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

@RunWith(JUnit4.class)
public class AslgenTests {
    private static final String VALID_MAPPINGS_PATH = "com/android/aslgen/validmappings";
    private static final List<String> VALID_MAPPINGS_SUBDIRS = List.of("location", "contacts");
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
            String hrContents = new String(hrStream.readAllBytes(), StandardCharsets.UTF_8);
            InputStream odStream =
                    getClass().getClassLoader().getResourceAsStream(odPath.toString());
            String odContents = new String(odStream.readAllBytes(), StandardCharsets.UTF_8);
            AndroidSafetyLabel asl =
                    AslConverter.readFromString(hrContents, AslConverter.Format.HUMAN_READABLE);
            String out = AslConverter.getXmlAsString(asl, AslConverter.Format.ON_DEVICE);
            System.out.println("out: " + out);

            assertEquals(getFormattedXml(out), getFormattedXml(odContents));
        }
    }

    private static String getFormattedXml(String xmlStr)
            throws ParserConfigurationException, IOException, SAXException, TransformerException {
        InputStream stream = new ByteArrayInputStream(xmlStr.getBytes(StandardCharsets.UTF_8));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(stream);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        StreamResult streamResult = new StreamResult(outStream); // out
        DOMSource domSource = new DOMSource(document);
        transformer.transform(domSource, streamResult);

        return outStream.toString(StandardCharsets.UTF_8);
    }
}
