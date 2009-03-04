/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.sax;

import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;

/**
 * The root XML element. The entry point for this API. Not safe for concurrent
 * use.
 *
 * <p>For example, passing this XML:
 *
 * <pre>
 * &lt;feed xmlns='http://www.w3.org/2005/Atom'>
 *   &lt;entry>
 *     &lt;id>bob&lt;/id>
 *   &lt;/entry>
 * &lt;/feed>
 * </pre>
 *
 * to this code:
 *
 * <pre>
 * static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
 *
 * ...
 * 
 * RootElement root = new RootElement(ATOM_NAMESPACE, "feed");
 * Element entry = root.getChild(ATOM_NAMESPACE, "entry");
 * entry.getChild(ATOM_NAMESPACE, "id").setEndTextElementListener(
 *   new EndTextElementListener() {
 *     public void end(String body) {
 *       System.out.println("Entry ID: " + body);
 *     }
 *   });
 *
 * XMLReader reader = ...;
 * reader.setContentHandler(root.getContentHandler());
 * reader.parse(...);
 * </pre>
 *
 * would output:
 *
 * <pre>
 * Entry ID: bob
 * </pre>
 */
public class RootElement extends Element {

    final Handler handler = new Handler();

    /**
     * Constructs a new root element with the given name.
     *
     * @param uri the namespace
     * @param localName the local name
     */
    public RootElement(String uri, String localName) {
        super(null, uri, localName, 0);
    }

    /**
     * Constructs a new root element with the given name. Uses an empty string
     * as the namespace.
     *
     * @param localName the local name
     */
    public RootElement(String localName) {
        this("", localName);
    }

    /**
     * Gets the SAX {@code ContentHandler}. Pass this to your SAX parser.
     */
    public ContentHandler getContentHandler() {
        return this.handler;
    }

    class Handler extends DefaultHandler {

        Locator locator;
        int depth = -1;
        Element current = null;
        StringBuilder bodyBuilder = null;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                Attributes attributes) throws SAXException {
            int depth = ++this.depth;

            if (depth == 0) {
                // This is the root element.
                startRoot(uri, localName, attributes);
                return;
            }

            // Prohibit mixed text and elements.
            if (bodyBuilder != null) {
                throw new BadXmlException("Encountered mixed content"
                        + " within text element named " + current + ".",
                        locator);
            }

            // If we're one level below the current element.
            if (depth == current.depth + 1) {
                // Look for a child to push onto the stack.
                Children children = current.children;
                if (children != null) {
                    Element child = children.get(uri, localName);
                    if (child != null) {
                        start(child, attributes);
                    }
                }
            }
        }

        void startRoot(String uri, String localName, Attributes attributes)
                throws SAXException {
            Element root = RootElement.this;
            if (root.uri.compareTo(uri) != 0
                    || root.localName.compareTo(localName) != 0) {
                throw new BadXmlException("Root element name does"
                        + " not match. Expected: " + root + ", Got: "
                        + Element.toString(uri, localName), locator);
            }

            start(root, attributes);
        }

        void start(Element e, Attributes attributes) {
            // Push element onto the stack.
            this.current = e;

            if (e.startElementListener != null) {
                e.startElementListener.start(attributes);
            }

            if (e.endTextElementListener != null) {
                this.bodyBuilder = new StringBuilder();
            }
            
            e.resetRequiredChildren();
            e.visited = true;
        }

        @Override
        public void characters(char[] buffer, int start, int length)
                throws SAXException {
            if (bodyBuilder != null) {
                bodyBuilder.append(buffer, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            Element current = this.current;

            // If we've ended the current element...
            if (depth == current.depth) {
                current.checkRequiredChildren(locator);

                // Invoke end element listener.
                if (current.endElementListener != null) {
                    current.endElementListener.end();
                }

                // Invoke end text element listener.
                if (bodyBuilder != null) {
                    String body = bodyBuilder.toString();
                    bodyBuilder = null;

                    // We can assume that this listener is present.
                    current.endTextElementListener.end(body);
                }

                // Pop element off the stack.
                this.current = current.parent;
            }

            depth--;
        }
    }
}
