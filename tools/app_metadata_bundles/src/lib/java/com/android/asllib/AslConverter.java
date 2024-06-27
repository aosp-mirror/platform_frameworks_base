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

import com.android.asllib.marshallable.AndroidSafetyLabel;
import com.android.asllib.marshallable.AndroidSafetyLabelFactory;
import com.android.asllib.util.MalformedXmlException;
import com.android.asllib.util.XmlUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class AslConverter {
    public enum Format {
        NULL,
        HUMAN_READABLE,
        ON_DEVICE;
    }

    /** Reads a {@link AndroidSafetyLabel} from an {@link InputStream}. */
    // TODO(b/329902686): Support parsing from on-device.
    public static AndroidSafetyLabel readFromStream(InputStream in, Format format)
            throws IOException, ParserConfigurationException, SAXException, MalformedXmlException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(in);

        switch (format) {
            case HUMAN_READABLE:
                Element appMetadataBundles =
                        XmlUtils.getSingleChildElement(
                                document, XmlUtils.HR_TAG_APP_METADATA_BUNDLES, true);

                return new AndroidSafetyLabelFactory().createFromHrElement(appMetadataBundles);
            case ON_DEVICE:
                Element bundleEle =
                        XmlUtils.getSingleChildElement(document, XmlUtils.OD_TAG_BUNDLE, true);
                return new AndroidSafetyLabelFactory().createFromOdElement(bundleEle);
            default:
                throw new IllegalStateException("Unrecognized input format.");
        }
    }

    /** Reads a {@link AndroidSafetyLabel} from a String. */
    public static AndroidSafetyLabel readFromString(String in, Format format)
            throws IOException, ParserConfigurationException, SAXException, MalformedXmlException {
        InputStream stream = new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8));
        return readFromStream(stream, format);
    }

    /** Write the content of the {@link AndroidSafetyLabel} to a {@link OutputStream}. */
    // TODO(b/329902686): Support outputting human-readable format.
    public static void writeToStream(
            OutputStream out, AndroidSafetyLabel asl, AslConverter.Format format)
            throws IOException, ParserConfigurationException, TransformerException {
        var docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        var document = docBuilder.newDocument();

        switch (format) {
            case HUMAN_READABLE:
                document.appendChild(asl.toHrDomElement(document));
                break;
            case ON_DEVICE:
                document.appendChild(asl.toOdDomElement(document));
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

    /** Get the content of the {@link AndroidSafetyLabel} as String. */
    public static String getXmlAsString(AndroidSafetyLabel asl, AslConverter.Format format)
            throws IOException, ParserConfigurationException, TransformerException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeToStream(out, asl, format);
        return out.toString(StandardCharsets.UTF_8);
    }
}
