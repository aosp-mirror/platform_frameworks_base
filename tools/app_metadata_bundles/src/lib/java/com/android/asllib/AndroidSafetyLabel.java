/*
 * Copyright (C) 2024 The Android Open Source Project
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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class AndroidSafetyLabel implements AslMarshallable {

    public enum Format {
        NULL, HUMAN_READABLE, ON_DEVICE;
    }

    private final SafetyLabels mSafetyLabels;

    public SafetyLabels getSafetyLabels() {
        return mSafetyLabels;
    }

    public AndroidSafetyLabel(SafetyLabels safetyLabels) {
        this.mSafetyLabels = safetyLabels;
    }

    /** Reads a {@link AndroidSafetyLabel} from an {@link InputStream}. */
    // TODO(b/329902686): Support parsing from on-device.
    public static AndroidSafetyLabel readFromStream(InputStream in, Format format)
            throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(in);

        switch (format) {
            case HUMAN_READABLE:
                Element appMetadataBundles =
                        XmlUtils.getSingleElement(document, XmlUtils.HR_TAG_APP_METADATA_BUNDLES);

                return new AndroidSafetyLabelFactory()
                        .createFromHrElements(
                                XmlUtils.asElementList(
                                        document.getElementsByTagName(
                                                XmlUtils.HR_TAG_APP_METADATA_BUNDLES)));
            case ON_DEVICE:
                throw new IllegalArgumentException(
                        "Parsing from on-device format is not supported at this time.");
            default:
                throw new IllegalStateException("Unrecognized input format.");
        }
    }

    /** Write the content of the {@link AndroidSafetyLabel} to a {@link OutputStream}. */
    // TODO(b/329902686): Support outputting human-readable format.
    public void writeToStream(OutputStream out, Format format)
            throws IOException, ParserConfigurationException, TransformerException {
        var docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        var document = docBuilder.newDocument();

        switch (format) {
            case HUMAN_READABLE:
                throw new IllegalArgumentException(
                        "Outputting human-readable format is not supported at this time.");
            case ON_DEVICE:
                for (var child : this.toOdDomElements(document)) {
                    document.appendChild(child);
                }
                break;
            default:
                throw new IllegalStateException("Unrecognized input format.");
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StreamResult streamResult = new StreamResult(out); // out
        DOMSource domSource = new DOMSource(document);
        transformer.transform(domSource, streamResult);
    }

    /** Creates an on-device DOM element from an {@link AndroidSafetyLabel} */
    @Override
    public List<Element> toOdDomElements(Document doc) {
        Element aslEle = doc.createElement(XmlUtils.OD_TAG_BUNDLE);
        XmlUtils.appendChildren(aslEle, mSafetyLabels.toOdDomElements(doc));
        return List.of(aslEle);
    }

    public static void test() {
        // TODO(b/329902686): Add tests.
    }
}
