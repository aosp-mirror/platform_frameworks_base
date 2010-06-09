/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.content;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.content.ContentResolver.OpenResourceIdResult;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.util.Log;
import android.widget.CursorAdapter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;
import java.util.Stack;
import java.util.regex.Pattern;

/**
 * A read-only content provider which extracts data out of an XML document.
 *
 * <p>A XPath-like selection pattern is used to select some nodes in the XML document. Each such
 * node will create a row in the {@link Cursor} result.</p>
 *
 * Each row is then populated with columns that are also defined as XPath-like projections. These
 * projections fetch attributes values or text in the matching row node or its children.
 *
 * <p>To add this provider in your application, you should add its declaration to your application
 * manifest:
 * <pre class="prettyprint">
 * &lt;provider android:name="android.content.XmlDocumentProvider" android:authorities="xmldocument" /&gt;
 * </pre>
 * </p>
 *
 * <h2>Node selection syntax</h2>
 * The node selection syntax is made of the concatenation of an arbitrary number (at least one) of
 * <code>/node_name</code> node selection patterns.
 *
 * <p>The <code>/root/child1/child2</code> pattern will for instance match all nodes named
 * <code>child2</code> which are children of a node named <code>child1</code> which are themselves
 * children of a root node named <code>root</code>.</p>
 *
 * Any <code>/</code> separator in the previous expression can be replaced by a <code>//</code>
 * separator instead, which indicated a <i>descendant</i> instead of a child.
 *
 * <p>The <code>//node1//node2</code> pattern will for instance match all nodes named
 * <code>node2</code> which are descendant of a node named <code>node1</code> located anywhere in
 * the document hierarchy.</p>
 *
 * Node names can contain namespaces in the form <code>namespace:node</code>.
 *
 * <h2>Projection syntax</h2>
 * For every selected node, the projection will then extract actual data from this node and its
 * descendant.
 *
 * <p>Use a syntax similar to the selection syntax described above to select the text associated
 * with a child of the selected node. The implicit root of this projection pattern is the selected
 * node. <code>/</code> will hence refer to the text of the selected node, while
 * <code>/child1</code> will fetch the text of its child named <code>child1</code> and
 * <code>//child1</code> will match any <i>descendant</i> named <code>child1</code>. If several
 * nodes match the projection pattern, their texts are appended as a result.</p>
 *
 * A projection can also fetch any node attribute by appending a <code>@attribute_name</code>
 * pattern to the previously described syntax. <code>//child1@price</code> will for instance match
 * the attribute <code>price</code> of any <code>child1</code> descendant.
 *
 * <p>If a projection does not match any node/attribute, its associated value will be an empty
 * string.</p>
 *
 * <h2>Example</h2>
 * Using the following XML document:
 * <pre class="prettyprint">
 * &lt;library&gt;
 *   &lt;book id="EH94"&gt;
 *     &lt;title&gt;The Old Man and the Sea&lt;/title&gt;
 *     &lt;author&gt;Ernest Hemingway&lt;/author&gt;
 *   &lt;/book&gt;
 *   &lt;book id="XX10"&gt;
 *     &lt;title&gt;The Arabian Nights: Tales of 1,001 Nights&lt;/title&gt;
 *   &lt;/book&gt;
 *   &lt;no-id&gt;
 *     &lt;book&gt;
 *       &lt;title&gt;Animal Farm&lt;/title&gt;
 *       &lt;author&gt;George Orwell&lt;/author&gt;
 *     &lt;/book&gt;
 *   &lt;/no-id&gt;
 * &lt;/library&gt;
 * </pre>
 * A selection pattern of <code>/library//book</code> will match the three book entries (while
 * <code>/library/book</code> will only match the first two ones).
 *
 * <p>Defining the projections as <code>/title</code>, <code>/author</code> and <code>@id</code>
 * will retrieve the associated data. Note that the author of the second book as well as the id of
 * the third are empty strings.
 */
public class XmlDocumentProvider extends ContentProvider {
    /*
     * Ideas for improvement:
     * - Expand XPath-like syntax to allow for [nb] child number selector
     * - Address the starting . bug in AbstractCursor which prevents a true XPath syntax.
     * - Provide an alternative to concatenation when several node match (list-like).
     * - Support namespaces in attribute names.
     * - Incremental Cursor creation, pagination
     */
    private static final String LOG_TAG = "XmlDocumentProvider";
    private AndroidHttpClient mHttpClient;

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Query data from the XML document referenced in the URI.
     *
     * <p>The XML document can be a local resource or a file that will be downloaded from the
     * Internet. In the latter case, your application needs to request the INTERNET permission in
     * its manifest.</p>
     *
     * The URI will be of the form <code>content://xmldocument/?resource=R.xml.myFile</code> for a
     * local resource. <code>xmldocument</code> should match the authority declared for this
     * provider in your manifest. Internet documents are referenced using
     * <code>content://xmldocument/?url=</code> followed by an encoded version of the URL of your
     * document (see {@link Uri#encode(String)}).
     *
     * <p>The number of columns of the resulting Cursor is equal to the size of the projection
     * array plus one, named <code>_id</code> which will contain a unique row id (allowing the
     * Cursor to be used with a {@link CursorAdapter}). The other columns' names are the projection
     * patterns.</p>
     *
     * @param uri The URI of your local resource or Internet document.
     * @param projection A set of patterns that will be used to extract data from each selected
     * node. See class documentation for pattern syntax.
     * @param selection A selection pattern which will select the nodes that will create the
     * Cursor's rows. See class documentation for pattern syntax.
     * @param selectionArgs This parameter is ignored.
     * @param sortOrder The row order in the resulting cursor is determined from the node order in
     * the XML document. This parameter is ignored.
     * @return A Cursor or null in case of error.
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {

        XmlPullParser parser = null;
        mHttpClient = null;

        final String url = uri.getQueryParameter("url");
        if (url != null) {
            parser = getUriXmlPullParser(url);
        } else {
            final String resource = uri.getQueryParameter("resource");
            if (resource != null) {
                Uri resourceUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" +
                        getContext().getPackageName() + "/" + resource);
                parser = getResourceXmlPullParser(resourceUri);
            }
        }

        if (parser != null) {
            XMLCursor xmlCursor = new XMLCursor(selection, projection);
            try {
                xmlCursor.parseWith(parser);
                return xmlCursor;
            } catch (IOException e) {
                Log.w(LOG_TAG, "I/O error while parsing XML " + uri, e);
            } catch (XmlPullParserException e) {
                Log.w(LOG_TAG, "Error while parsing XML " + uri, e);
            } finally {
                if (mHttpClient != null) {
                    mHttpClient.close();
                }
            }
        }

        return null;
    }

    /**
     * Creates an XmlPullParser for the provided URL. Can be overloaded to provide your own parser.
     * @param url The URL of the XML document that is to be parsed.
     * @return An XmlPullParser on this document.
     */
    protected XmlPullParser getUriXmlPullParser(String url) {
        XmlPullParser parser = null;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            parser = factory.newPullParser();
        } catch (XmlPullParserException e) {
            Log.e(LOG_TAG, "Unable to create XmlPullParser", e);
            return null;
        }

        InputStream inputStream = null;
        try {
            final HttpGet get = new HttpGet(url);
            mHttpClient = AndroidHttpClient.newInstance("Android");
            HttpResponse response = mHttpClient.execute(get);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                final HttpEntity entity = response.getEntity();
                if (entity != null) {
                    inputStream = entity.getContent();
                }
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Error while retrieving XML file " + url, e);
            return null;
        }

        try {
            parser.setInput(inputStream, null);
        } catch (XmlPullParserException e) {
            Log.w(LOG_TAG, "Error while reading XML file from " + url, e);
            return null;
        }

        return parser;
    }

    /**
     * Creates an XmlPullParser for the provided local resource. Can be overloaded to provide your
     * own parser.
     * @param resourceUri A fully qualified resource name referencing a local XML resource.
     * @return An XmlPullParser on this resource.
     */
    protected XmlPullParser getResourceXmlPullParser(Uri resourceUri) {
        OpenResourceIdResult resourceId;
        try {
            resourceId = getContext().getContentResolver().getResourceId(resourceUri);
            return resourceId.r.getXml(resourceId.id);
        } catch (FileNotFoundException e) {
            Log.w(LOG_TAG, "XML resource not found: " + resourceUri.toString(), e);
            return null;
        }
    }

    /**
     * Returns "vnd.android.cursor.dir/xmldoc".
     */
    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/xmldoc";
    }

    /**
     * This ContentProvider is read-only. This method throws an UnsupportedOperationException.
     **/
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    /**
     * This ContentProvider is read-only. This method throws an UnsupportedOperationException.
     **/
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    /**
     * This ContentProvider is read-only. This method throws an UnsupportedOperationException.
     **/
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private static class XMLCursor extends MatrixCursor {
        private final Pattern mSelectionPattern;
        private Pattern[] mProjectionPatterns;
        private String[] mAttributeNames;
        private String[] mCurrentValues;
        private BitSet[] mActiveTextDepthMask;
        private final int mNumberOfProjections;

        public XMLCursor(String selection, String[] projections) {
            super(projections);
            // The first column in projections is used for the _ID
            mNumberOfProjections = projections.length - 1;
            mSelectionPattern = createPattern(selection);
            createProjectionPattern(projections);
        }

        private Pattern createPattern(String input) {
            String pattern = input.replaceAll("//", "/(.*/|)").replaceAll("^/", "^/") + "$";
            return Pattern.compile(pattern);
        }

        private void createProjectionPattern(String[] projections) {
            mProjectionPatterns = new Pattern[mNumberOfProjections];
            mAttributeNames = new String[mNumberOfProjections];
            mActiveTextDepthMask = new BitSet[mNumberOfProjections];
            // Add a column to store _ID
            mCurrentValues = new String[mNumberOfProjections + 1];

            for (int i=0; i<mNumberOfProjections; i++) {
                mActiveTextDepthMask[i] = new BitSet();
                String projection = projections[i + 1]; // +1 to skip the _ID column
                int atIndex = projection.lastIndexOf('@', projection.length());
                if (atIndex >= 0) {
                    mAttributeNames[i] = projection.substring(atIndex+1);
                    projection = projection.substring(0, atIndex);
                } else {
                    mAttributeNames[i] = null;
                }

                // Conforms to XPath standard: reference to local context starts with a .
                if (projection.charAt(0) == '.') {
                    projection = projection.substring(1);
                }
                mProjectionPatterns[i] = createPattern(projection);
            }
        }

        public void parseWith(XmlPullParser parser) throws IOException, XmlPullParserException {
            StringBuilder path = new StringBuilder();
            Stack<Integer> pathLengthStack = new Stack<Integer>();

            // There are two parsing mode: in root mode, rootPath is updated and nodes matching
            // selectionPattern are searched for and currentNodeDepth is negative.
            // When a node matching selectionPattern is found, currentNodeDepth is set to 0 and
            // updated as children are parsed and projectionPatterns are searched in nodePath.
            int currentNodeDepth = -1;

            // Index where local selected node path starts from in path
            int currentNodePathStartIndex = 0;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {

                if (eventType == XmlPullParser.START_TAG) {
                    // Update path
                    pathLengthStack.push(path.length());
                    path.append('/');
                    String prefix = null;
                    try {
                        // getPrefix is not supported by local Xml resource parser
                        prefix = parser.getPrefix();
                    } catch (RuntimeException e) {
                        prefix = null;
                    }
                    if (prefix != null) {
                        path.append(prefix);
                        path.append(':');
                    }
                    path.append(parser.getName());

                    if (currentNodeDepth >= 0) {
                        currentNodeDepth++;
                    } else {
                        // A node matching selection is found: initialize child parsing mode
                        if (mSelectionPattern.matcher(path.toString()).matches()) {
                            currentNodeDepth = 0;
                            currentNodePathStartIndex = path.length();
                            mCurrentValues[0] = Integer.toString(getCount()); // _ID
                            for (int i = 0; i < mNumberOfProjections; i++) {
                                // Reset values to default (empty string)
                                mCurrentValues[i + 1] = "";
                                mActiveTextDepthMask[i].clear();
                            }
                        }
                    }

                    // This test has to be separated from the previous one as currentNodeDepth can
                    // be modified above (when a node matching selection is found).
                    if (currentNodeDepth >= 0) {
                        final String localNodePath = path.substring(currentNodePathStartIndex);
                        for (int i = 0; i < mNumberOfProjections; i++) {
                            if (mProjectionPatterns[i].matcher(localNodePath).matches()) {
                                String attribute = mAttributeNames[i];
                                if (attribute != null) {
                                    mCurrentValues[i + 1] =
                                        parser.getAttributeValue(null, attribute);
                                } else {
                                    mActiveTextDepthMask[i].set(currentNodeDepth, true);
                                }
                            }
                        }
                    }

                } else if (eventType == XmlPullParser.END_TAG) {
                    // Pop last node from path
                    final int length = pathLengthStack.pop();
                    path.setLength(length);

                    if (currentNodeDepth >= 0) {
                        if (currentNodeDepth == 0) {
                            // Leaving a selection matching node: add a new row with results
                            addRow(mCurrentValues);
                        } else {
                            for (int i = 0; i < mNumberOfProjections; i++) {
                                mActiveTextDepthMask[i].set(currentNodeDepth, false);
                            }
                        }
                        currentNodeDepth--;
                    }

                } else if ((eventType == XmlPullParser.TEXT) && (!parser.isWhitespace())) {
                    for (int i = 0; i < mNumberOfProjections; i++) {
                        if ((currentNodeDepth >= 0) &&
                            (mActiveTextDepthMask[i].get(currentNodeDepth))) {
                            mCurrentValues[i + 1] += parser.getText();
                        }
                    }
                }

                eventType = parser.next();
            }
        }
    }
}
