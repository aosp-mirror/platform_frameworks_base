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

package com.android.asllib.testutils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.android.asllib.marshallable.AslMarshallable;
import com.android.asllib.marshallable.AslMarshallableFactory;
import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
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

public class TestUtils {
    public static final String HOLDER_TAG_NAME = "holder_of_flattened_for_testing";

    /** Reads a Resource file into a String. */
    public static String readStrFromResource(Path filePath) throws IOException {
        InputStream hrStream =
                TestUtils.class.getClassLoader().getResourceAsStream(filePath.toString());
        return new String(hrStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Gets List of Element from a path to an existing Resource. */
    public static List<Element> getElementsFromResource(Path filePath)
            throws ParserConfigurationException, IOException, SAXException {
        String str = readStrFromResource(filePath);
        InputStream stream = new ByteArrayInputStream(str.getBytes(StandardCharsets.UTF_8));

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(stream);
        Element root = document.getDocumentElement();
        if (root.getTagName().equals(HOLDER_TAG_NAME)) {
            String tagName =
                    XmlUtils.asElementList(root.getChildNodes()).stream()
                            .findFirst()
                            .get()
                            .getTagName();
            return XmlUtils.asElementList(root.getElementsByTagName(tagName));
        } else {
            return List.of(root);
        }
    }

    /** Reads a Document into a String. */
    public static String docToStr(Document doc, boolean omitXmlDeclaration)
            throws TransformerException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(
                OutputKeys.OMIT_XML_DECLARATION, omitXmlDeclaration ? "yes" : "no");

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        StreamResult streamResult = new StreamResult(outStream); // out
        DOMSource domSource = new DOMSource(doc);
        transformer.transform(domSource, streamResult);

        return outStream.toString(StandardCharsets.UTF_8);
    }

    /**
     * Gets formatted XML for slightly more robust comparison checking than naive string comparison.
     */
    public static String getFormattedXml(String xmlStr, boolean omitXmlDeclaration)
            throws ParserConfigurationException, IOException, SAXException, TransformerException {
        InputStream stream = new ByteArrayInputStream(xmlStr.getBytes(StandardCharsets.UTF_8));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(stream);

        return docToStr(document, omitXmlDeclaration);
    }

    /** Helper for getting a new Document */
    public static Document document() throws ParserConfigurationException {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    }

    /** Helper for testing human-readable to on-device conversion expecting exception */
    public static <T extends AslMarshallable> void hrToOdExpectException(
            AslMarshallableFactory<T> factory, String hrFolderPath, String fileName) {
        assertThrows(
                MalformedXmlException.class,
                () -> {
                    factory.createFromHrElements(
                            TestUtils.getElementsFromResource(Paths.get(hrFolderPath, fileName)));
                });
    }

    /** Helper for testing human-readable to on-device conversion */
    public static <T extends AslMarshallable> void testHrToOd(
            Document doc,
            AslMarshallableFactory<T> factory,
            String hrFolderPath,
            String odFolderPath,
            String fileName)
            throws Exception {
        AslMarshallable marshallable =
                factory.createFromHrElements(
                        TestUtils.getElementsFromResource(Paths.get(hrFolderPath, fileName)));

        for (var child : marshallable.toOdDomElements(doc)) {
            doc.appendChild(child);
        }
        String converted = TestUtils.docToStr(doc, true);
        System.out.println("converted: " + converted);

        String expectedOdContents =
                TestUtils.readStrFromResource(Paths.get(odFolderPath, fileName));
        assertEquals(
                TestUtils.getFormattedXml(expectedOdContents, true),
                TestUtils.getFormattedXml(converted, true));
    }
}
