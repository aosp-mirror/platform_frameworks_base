// Copyright 2008, The Android Open Source Project
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
//  1. Redistributions of source code must retain the above copyright notice,
//     this list of conditions and the following disclaimer.
//  2. Redistributions in binary form must reproduce the above copyright notice,
//     this list of conditions and the following disclaimer in the documentation
//     and/or other materials provided with the distribution.
//  3. Neither the name of Google Inc. nor the names of its contributors may be
//     used to endorse or promote products derived from this software without
//     specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
// WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
// EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
// PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
// OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
// WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
// OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
// ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package android.webkit.gears;

import android.util.Log;
import java.io.IOException;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.w3c.dom.Document;
import org.w3c.dom.DOMException;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A class that can extract the Gears version and upgrade URL from an
 * xml document.
 */
public final class VersionExtractor {

  /**
   * Logging tag
   */
  private static final String TAG = "Gears-J-VersionExtractor";
  /**
   * XML element names.
   */
  private static final String VERSION = "em:version";
  private static final String URL = "em:updateLink";

  /**
   * Parses the input xml string and invokes the native
   * setVersionAndUrl method.
   * @param xml is the XML string to parse.
   * @return true if the extraction is successful and false otherwise.
   */
  public static boolean extract(String xml, long nativeObject) {
    try {
      // Build the builders.
      DocumentBuilderFactory factory =  DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      DocumentBuilder builder = factory.newDocumentBuilder();

      // Create the document.
      Document doc = builder.parse(new InputSource(new StringReader(xml)));

      // Look for the version and url elements and get their text
      // contents.
      String version = extractText(doc, VERSION);
      String url = extractText(doc, URL);

      // If we have both, let the native side know.
      if (version != null && url != null) {
        setVersionAndUrl(version, url, nativeObject);
        return true;
      }

      return false;

    } catch (FactoryConfigurationError ex) {
      Log.e(TAG, "Could not create the DocumentBuilderFactory " + ex);
    } catch (ParserConfigurationException ex) {
      Log.e(TAG, "Could not create the DocumentBuilder " + ex);
    } catch (SAXException ex) {
      Log.e(TAG, "Could not parse the xml " + ex);
    } catch (IOException ex) {
      Log.e(TAG, "Could not read the xml " + ex);
    }

    return false;
  }

 /**
   * Extracts the text content of the first element with the given name.
   * @param doc is the Document where the element is searched for.
   * @param elementName is name of the element to searched for.
   * @return the text content of the element or null if no such
   *         element is found.
   */
  private static String extractText(Document doc, String elementName) {
    String text = null;
    NodeList node_list = doc.getElementsByTagName(elementName);

    if (node_list.getLength() > 0) {
      // We are only interested in the first node. Normally there
      // should not be more than one anyway.
      Node  node = node_list.item(0);

      // Iterate through the text children.
      NodeList child_list = node.getChildNodes();

      try {
        for (int i = 0; i < child_list.getLength(); ++i) {
          Node child = child_list.item(i);
          if (child.getNodeType() == Node.TEXT_NODE) {
            if (text == null) {
              text = new String();
            }
            text += child.getNodeValue();
          }
        }
      } catch (DOMException ex) {
        Log.e(TAG, "getNodeValue() failed " + ex);
      }
    }

    if (text != null) {
      text = text.trim();
    }

    return text;
  }

  /**
   * Native method used to send the version and url back to the C++
   * side.
   */
  private static native void setVersionAndUrl(
      String version, String url, long nativeObject);
}
