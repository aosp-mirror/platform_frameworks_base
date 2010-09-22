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

package android.widget;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.View;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * <p>This class can be used to load {@link android.widget.Adapter adapters} defined in
 * XML resources. XML-defined adapters can be used to easily create adapters in your
 * own application or to pass adapters to other processes.</p>
 * 
 * <h2>Types of adapters</h2>
 * <p>Adapters defined using XML resources can only be one of the following supported
 * types. Arbitrary adapters are not supported to guarantee the safety of the loaded
 * code when adapters are loaded across packages.</p>
 * <ul>
 *  <li><a href="#xml-cursor-adapter">Cursor adapter</a>: a cursor adapter can be used
 *  to display the content of a cursor, most often coming from a content provider</li>
 * </ul>
 * <p>The complete XML format definition of each adapter type is available below.</p>
 * 
 * <a name="xml-cursor-adapter" />
 * <h2>Cursor adapter</h2>
 * <p>A cursor adapter XML definition starts with the
 * <a href="#xml-cursor-adapter-tag"><code>&lt;cursor-adapter /&gt;</code></a>
 * tag and may contain one or more instances of the following tags:</p>
 * <ul>
 *  <li><a href="#xml-cursor-adapter-select-tag"><code>&lt;select /&gt;</code></a></li>
 *  <li><a href="#xml-cursor-adapter-bind-tag"><code>&lt;bind /&gt;</code></a></li>
 * </ul>
 * 
 * <a name="xml-cursor-adapter-tag" />
 * <h3>&lt;cursor-adapter /&gt;</h3>
 * <p>The <code>&lt;cursor-adapter /&gt;</code> element defines the beginning of the
 * document and supports the following attributes:</p>
 * <ul>
 *  <li><code>android:layout</code>: Reference to the XML layout to be inflated for
 *  each item of the adapter. This attribute is mandatory.</li>
 *  <li><code>android:selection</code>: Selection expression, used when the
 *  <code>android:uri</code> attribute is defined or when the adapter is loaded with
 *  {@link android.widget.Adapters#loadCursorAdapter(android.content.Context, int, String, Object[])}.
 *  This attribute is optional.</li>
 *  <li><code>android:sortOrder</code>: Sort expression, used when the
 *  <code>android:uri</code> attribute is defined or when the adapter is loaded with
 *  {@link android.widget.Adapters#loadCursorAdapter(android.content.Context, int, String, Object[])}.
 *  This attribute is optional.</li>
 *  <li><code>android:uri</code>: URI of the content provider to query to retrieve a cursor.
 *  Specifying this attribute is equivalent to calling
 *  {@link android.widget.Adapters#loadCursorAdapter(android.content.Context, int, String, Object[])}.
 *  If you call this method, the value of the XML attribute is ignored. This attribute is
 *  optional.</li>
 * </ul>
 * <p>In addition, you can specify one or more instances of
 * <a href="#xml-cursor-adapter-select-tag"><code>&lt;select /&gt;</code></a> and
 * <a href="#xml-cursor-adapter-bind-tag"><code>&lt;bind /&gt;</code></a> tags as children
 * of <code>&lt;cursor-adapter /&gt;</code>.</p>
 * 
 * <a name="xml-cursor-adapter-select-tag" />
 * <h3>&lt;select /&gt;</h3>
 * <p>The <code>&lt;select /&gt;</code> tag is used to select columns from the cursor
 * when doing the query. This can be very useful when using transformations in the
 * <code>&lt;bind /&gt;</code> elements. It can also be very useful if you are providing
 * your own <a href="#xml-cursor-adapter-bind-data-types">binder</a> or
 * <a href="#xml-cursor-adapter-bind-data-types">transformation</a> classes.
 * <code>&lt;select /&gt;</code> elements are ignored if you supply the cursor yourself.</p>
 * <p>The <code>&lt;select /&gt;</code> supports the following attributes:</p>
 * <ul>
 *  <li><code>android:column</code>: Name of the column to select in the cursor during the
 *  query operation</li>
 * </ul>
 * <p><strong>Note:</strong> The column named <code>_id</code> is always implicitly
 * selected.</p>
 * 
 * <a name="xml-cursor-adapter-bind-tag" />
 * <h3>&lt;bind /&gt;</h3>
 * <p>The <code>&lt;bind /&gt;</code> tag is used to bind a column from the cursor to
 * a {@link android.view.View}. A column bound using this tag is automatically selected
 * during the query and a matching
 * <a href="#xml-cursor-adapter-select-tag"><code>&lt;select /&gt;</code> tag is therefore
 * not required.</p>
 * 
 * <p>Each binding is declared as a one to one matching but
 * custom binder classes or special
 * <a href="#xml-cursor-adapter-bind-data-transformation">data transformations</a> can
 * allow you to bind several columns to a single view. In this case you must use the
 * <a href="#xml-cursor-adapter-select-tag"><code>&lt;select /&gt;</code> tag to make
 * sure any required column is part of the query.</p>
 * 
 * <p>The <code>&lt;bind /&gt;</code> tag supports the following attributes:</p>
 * <ul>
 *  <li><code>android:from</code>: The name of the column to bind from.
 *  This attribute is mandatory. Note that <code>@</code> which are not used to reference resources
 *  should be backslash protected as in <code>\@</code>.</li>
 *  <li><code>android:to</code>: The id of the view to bind to. This attribute is mandatory.</li>
 *  <li><code>android:as</code>: The <a href="#xml-cursor-adapter-bind-data-types">data type</a>
 *  of the binding. This attribute is mandatory.</li>
 * </ul>
 * 
 * <p>In addition, a <code>&lt;bind /&gt;</code> can contain zero or more instances of
 * <a href="#xml-cursor-adapter-bind-data-transformation">data transformations</a> children
 * tags.</p>
 *
 * <a name="xml-cursor-adapter-bind-data-types" />
 * <h4>Binding data types</h4>
 * <p>For a binding to occur the data type of the bound column/view pair must be specified.
 * The following data types are currently supported:</p>
 * <ul>
 *  <li><code>string</code>: The content of the column is interpreted as a string and must be
 *  bound to a {@link android.widget.TextView}</li>
 *  <li><code>image</code>: The content of the column is interpreted as a blob describing an
 *  image and must be bound to an {@link android.widget.ImageView}</li>
 *  <li><code>image-uri</code>: The content of the column is interpreted as a URI to an image
 *  and must be bound to an {@link android.widget.ImageView}</li>
 *  <li><code>drawable</code>: The content of the column is interpreted as a resource id to a
 *  drawable and must be bound to an {@link android.widget.ImageView}</li>
 *  <li><code>tag</code>: The content of the column is interpreted as a string and will be set as
 *  the tag (using {@link View#setTag(Object)} of the associated View. This can be used to
 *  associate meta-data to your view, that can be used for instance by a listener.</li>
 *  <li>A fully qualified class name: The name of a class corresponding to an implementation of
 *  {@link android.widget.Adapters.CursorBinder}. Cursor binders can be used to provide
 *  bindings not supported by default. Custom binders cannot be used with
 *  {@link android.content.Context#isRestricted() restricted contexts}, for instance in an
 *  application widget</li>
 * </ul>
 * 
 * <a name="xml-cursor-adapter-bind-transformation" />
 * <h4>Binding transformations</h4>
 * <p>When defining a data binding you can specify an optional transformation by using one
 * of the following tags as a child of a <code>&lt;bind /&gt;</code> elements:</p>
 * <ul>
 *  <li><code>&lt;map /&gt;</code>: Maps a constant string to a string or a resource. Use
 *  one instance of this tag per value you want to map</li>
 *  <li><code>&lt;transform /&gt;</code>: Transforms a column's value using an expression
 *  or an instance of {@link android.widget.Adapters.CursorTransformation}</li>
 * </ul>
 * <p>While several <code>&lt;map /&gt;</code> tags can be used at the same time, you cannot
 * mix <code>&lt;map /&gt;</code> and <code>&lt;transform /&gt;</code> tags. If several
 * <code>&lt;transform /&gt;</code> tags are specified, only the last one is retained.</p>
 * 
 * <a name="xml-cursor-adapter-bind-transformation-map" />
 * <p><strong>&lt;map /&gt;</strong></p>
 * <p>A map element simply specifies a value to match from and a value to match to. When
 * a column's value equals the value to match from, it is replaced with the value to match
 * to. The following attributes are supported:</p>
 * <ul>
 *  <li><code>android:fromValue</code>: The value to match from. This attribute is mandatory</li>
 *  <li><code>android:toValue</code>: The value to match to. This value can be either a string
 *  or a resource identifier. This value is interpreted as a resource identifier when the
 *  data binding is of type <code>drawable</code>. This attribute is mandatory</li>
 * </ul>
 * 
 * <a name="xml-cursor-adapter-bind-transformation-transform" />
 * <p><strong>&lt;transform /&gt;</strong></p>
 * <p>A simple transform that occurs either by calling a specified class or by performing
 * simple text substitution. The following attributes are supported:</p>
 * <ul>
 *  <li><code>android:withExpression</code>: The transformation expression. The expression is
 *  a string containing column names surrounded with curly braces { and }. During the
 *  transformation each column name is replaced by its value. All columns must have been
 *  selected in the query. An example of expression is <code>"First name: {first_name},
 *  last name: {last_name}"</code>. This attribute is mandatory
 *  if <code>android:withClass</code> is not specified and ignored if <code>android:withClass</code>
 *  is specified</li>
 *  <li><code>android:withClass</code>: A fully qualified class name corresponding to an
 *  implementation of {@link android.widget.Adapters.CursorTransformation}. Custom
 *  transformations cannot be used with
 *  {@link android.content.Context#isRestricted() restricted contexts}, for instance in
 *  an app widget This attribute is mandatory if <code>android:withExpression</code> is
 *  not specified</li>
 * </ul>
 * 
 * <h3>Example</h3>
 * <p>The following example defines a cursor adapter that queries all the contacts with
 * a phone number using the contacts content provider. Each contact is displayed with
 * its display name, its favorite status and its photo. To display photos, a custom data
 * binder is declared:</p>
 * 
 * <pre class="prettyprint">
 * &lt;cursor-adapter xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:uri="content://com.android.contacts/contacts"
 *     android:selection="has_phone_number=1"
 *     android:layout="@layout/contact_item"&gt;
 *
 *     &lt;bind android:from="display_name" android:to="@id/name" android:as="string" /&gt;
 *     &lt;bind android:from="starred" android:to="@id/star" android:as="drawable"&gt;
 *         &lt;map android:fromValue="0" android:toValue="@android:drawable/star_big_off" /&gt;
 *         &lt;map android:fromValue="1" android:toValue="@android:drawable/star_big_on" /&gt;
 *     &lt;/bind&gt;
 *     &lt;bind android:from="_id" android:to="@id/name"
 *              android:as="com.google.android.test.adapters.ContactPhotoBinder" /&gt;
 *
 * &lt;/cursor-adapter&gt;
 * </pre>
 * 
 * <h3>Related APIs</h3>
 * <ul>
 *  <li>{@link android.widget.Adapters#loadAdapter(android.content.Context, int, Object[])}</li>
 *  <li>{@link android.widget.Adapters#loadCursorAdapter(android.content.Context, int, android.database.Cursor, Object[])}</li>
 *  <li>{@link android.widget.Adapters#loadCursorAdapter(android.content.Context, int, String, Object[])}</li>
 *  <li>{@link android.widget.Adapters.CursorBinder}</li>
 *  <li>{@link android.widget.Adapters.CursorTransformation}</li>
 *  <li>{@link android.widget.CursorAdapter}</li>
 * </ul>
 * 
 * @see android.widget.Adapter
 * @see android.content.ContentProvider
 * 
 * @attr ref android.R.styleable#CursorAdapter_layout 
 * @attr ref android.R.styleable#CursorAdapter_selection 
 * @attr ref android.R.styleable#CursorAdapter_sortOrder 
 * @attr ref android.R.styleable#CursorAdapter_uri 
 * @attr ref android.R.styleable#CursorAdapter_BindItem_as 
 * @attr ref android.R.styleable#CursorAdapter_BindItem_from 
 * @attr ref android.R.styleable#CursorAdapter_BindItem_to
 * @attr ref android.R.styleable#CursorAdapter_MapItem_fromValue 
 * @attr ref android.R.styleable#CursorAdapter_MapItem_toValue 
 * @attr ref android.R.styleable#CursorAdapter_SelectItem_column 
 * @attr ref android.R.styleable#CursorAdapter_TransformItem_withClass 
 * @attr ref android.R.styleable#CursorAdapter_TransformItem_withExpression 
 */
@SuppressWarnings({"JavadocReference"})
public class Adapters {
    private static final String ADAPTER_CURSOR = "cursor-adapter";

    /**
     * <p>Interface used to bind a {@link android.database.Cursor} column to a View. This
     * interface can be used to provide bindings for data types not supported by the
     * standard implementation of {@link android.widget.Adapters}.</p>
     * 
     * <p>A binder is provided with a cursor transformation which may or may not be used
     * to transform the value retrieved from the cursor. The transformation is guaranteed
     * to never be null so it's always safe to apply the transformation.</p>
     * 
     * <p>The binder is associated with a Context but can be re-used with multiple cursors.
     * As such, the implementation should make no assumption about the Cursor in use.</p>
     *
     * @see android.view.View 
     * @see android.database.Cursor
     * @see android.widget.Adapters.CursorTransformation
     */
    public static abstract class CursorBinder {
        /**
         * <p>The context associated with this binder.</p>
         */
        protected final Context mContext;

        /**
         * <p>The transformation associated with this binder. This transformation is never
         * null and may or may not be applied to the Cursor data during the
         * {@link #bind(android.view.View, android.database.Cursor, int)} operation.</p>
         * 
         * @see #bind(android.view.View, android.database.Cursor, int) 
         */
        protected final CursorTransformation mTransformation;

        /**
         * <p>Creates a new Cursor binder.</p> 
         * 
         * @param context The context associated with this binder.
         * @param transformation The transformation associated with this binder. This
         *        transformation may or may not be applied by the binder and is guaranteed
         *        to not be null.
         */
        public CursorBinder(Context context, CursorTransformation transformation) {
            mContext = context;
            mTransformation = transformation;
        }

        /**
         * <p>Binds the specified Cursor column to the supplied View. The binding operation
         * can query other Cursor columns as needed. During the binding operation, values
         * retrieved from the Cursor may or may not be transformed using this binder's
         * cursor transformation.</p>
         * 
         * @param view The view to bind data to.
         * @param cursor The cursor to bind data from.
         * @param columnIndex The column index in the cursor where the data to bind resides.
         * 
         * @see #mTransformation
         * 
         * @return True if the column was successfully bound to the View, false otherwise.
         */
        public abstract boolean bind(View view, Cursor cursor, int columnIndex);
    }

    /**
     * <p>Interface used to transform data coming out of a {@link android.database.Cursor}
     * before it is bound to a {@link android.view.View}.</p>
     * 
     * <p>Transformations are used to transform text-based data (in the form of a String),
     * or to transform data into a resource identifier. A default implementation is provided
     * to generate resource identifiers.</p>
     * 
     * @see android.database.Cursor
     * @see android.widget.Adapters.CursorBinder
     */
    public static abstract class CursorTransformation {
        /**
         * <p>The context associated with this transformation.</p>
         */
        protected final Context mContext;

        /**
         * <p>Creates a new Cursor transformation.</p>
         * 
         * @param context The context associated with this transformation.
         */
        public CursorTransformation(Context context) {
            mContext = context;
        }

        /**
         * <p>Transforms the specified Cursor column into a String. The transformation
         * can simply return the content of the column as a String (this is known
         * as the identity transformation) or manipulate the content. For instance,
         * a transformation can perform text substitutions or concatenate other
         * columns with the specified column.</p>
         * 
         * @param cursor The cursor that contains the data to transform. 
         * @param columnIndex The index of the column to transform.
         * 
         * @return A String containing the transformed value of the column.
         */
        public abstract String transform(Cursor cursor, int columnIndex);

        /**
         * <p>Transforms the specified Cursor column into a resource identifier.
         * The default implementation simply interprets the content of the column
         * as an integer.</p>
         * 
         * @param cursor The cursor that contains the data to transform. 
         * @param columnIndex The index of the column to transform.
         * 
         * @return A resource identifier.
         */
        public int transformToResource(Cursor cursor, int columnIndex) {
            return cursor.getInt(columnIndex);
        }        
    }

    /**
     * <p>Loads the {@link android.widget.CursorAdapter} defined in the specified
     * XML resource. The content of the adapter is loaded from the content provider
     * identified by the supplied URI.</p>
     * 
     * <p><strong>Note:</strong> If the supplied {@link android.content.Context} is
     * an {@link android.app.Activity}, the cursor returned by the content provider
     * will be automatically managed. Otherwise, you are responsible for managing the
     * cursor yourself.</p>
     * 
     * <p>The format of the XML definition of the cursor adapter is documented at
     * the top of this page.</p>
     * 
     * @param context The context to load the XML resource from.
     * @param id The identifier of the XML resource declaring the adapter.
     * @param uri The URI of the content provider.
     * @param parameters Optional parameters to pass to the CursorAdapter, used
     *        to substitute values in the selection expression.
     * 
     * @return A {@link android.widget.CursorAdapter}
     * 
     * @throws IllegalArgumentException If the XML resource does not contain
     *         a valid &lt;cursor-adapter /&gt; definition.
     * 
     * @see android.content.ContentProvider
     * @see android.widget.CursorAdapter
     * @see #loadAdapter(android.content.Context, int, Object[]) 
     */
    public static CursorAdapter loadCursorAdapter(Context context, int id, String uri,
            Object... parameters) {

        XmlCursorAdapter adapter = (XmlCursorAdapter) loadAdapter(context, id, ADAPTER_CURSOR,
                parameters);

        if (uri != null) {
            adapter.setUri(uri);
        }
        adapter.load();

        return adapter;
    }

    /**
     * <p>Loads the {@link android.widget.CursorAdapter} defined in the specified
     * XML resource. The content of the adapter is loaded from the specified cursor.
     * You are responsible for managing the supplied cursor.</p>
     * 
     * <p>The format of the XML definition of the cursor adapter is documented at
     * the top of this page.</p>
     * 
     * @param context The context to load the XML resource from.
     * @param id The identifier of the XML resource declaring the adapter.
     * @param cursor The cursor containing the data for the adapter.
     * @param parameters Optional parameters to pass to the CursorAdapter, used
     *        to substitute values in the selection expression.
     * 
     * @return A {@link android.widget.CursorAdapter}
     * 
     * @throws IllegalArgumentException If the XML resource does not contain
     *         a valid &lt;cursor-adapter /&gt; definition.
     * 
     * @see android.content.ContentProvider
     * @see android.widget.CursorAdapter
     * @see android.database.Cursor
     * @see #loadAdapter(android.content.Context, int, Object[]) 
     */
    public static CursorAdapter loadCursorAdapter(Context context, int id, Cursor cursor,
            Object... parameters) {

        XmlCursorAdapter adapter = (XmlCursorAdapter) loadAdapter(context, id, ADAPTER_CURSOR,
                parameters);

        if (cursor != null) {
            adapter.changeCursor(cursor);
        }

        return adapter;
    }

    /**
     * <p>Loads the adapter defined in the specified XML resource. The XML definition of
     * the adapter must follow the format definition of one of the supported adapter
     * types described at the top of this page.</p>
     * 
     * <p><strong>Note:</strong> If the loaded adapter is a {@link android.widget.CursorAdapter}
     * and the supplied {@link android.content.Context} is an {@link android.app.Activity},
     * the cursor returned by the content provider will be automatically managed. Otherwise,
     * you are responsible for managing the cursor yourself.</p>
     * 
     * @param context The context to load the XML resource from.
     * @param id The identifier of the XML resource declaring the adapter.
     * @param parameters Optional parameters to pass to the adapter.
     *  
     * @return An adapter instance.
     * 
     * @see #loadCursorAdapter(android.content.Context, int, android.database.Cursor, Object[]) 
     * @see #loadCursorAdapter(android.content.Context, int, String, Object[]) 
     */
    public static BaseAdapter loadAdapter(Context context, int id, Object... parameters) {
        final BaseAdapter adapter = loadAdapter(context, id, null, parameters);
        if (adapter instanceof ManagedAdapter) {
            ((ManagedAdapter) adapter).load();
        }
        return adapter;
    }

    /**
     * Loads an adapter from the specified XML resource. The optional assertName can
     * be used to exit early if the adapter defined in the XML resource is not of the
     * expected type.
     * 
     * @param context The context to associate with the adapter.
     * @param id The resource id of the XML document defining the adapter.
     * @param assertName The mandatory name of the adapter in the XML document.
     *        Ignored if null.
     * @param parameters Optional parameters passed to the adapter.
     * 
     * @return An instance of {@link android.widget.BaseAdapter}.
     */
    private static BaseAdapter loadAdapter(Context context, int id, String assertName,
            Object... parameters) {

        XmlResourceParser parser = null;
        try {
            parser = context.getResources().getXml(id);
            return createAdapterFromXml(context, parser, Xml.asAttributeSet(parser),
                    id, parameters, assertName);
        } catch (XmlPullParserException ex) {
            Resources.NotFoundException rnf = new Resources.NotFoundException(
                    "Can't load adapter resource ID " +
                    context.getResources().getResourceEntryName(id));
            rnf.initCause(ex);
            throw rnf;
        } catch (IOException ex) {
            Resources.NotFoundException rnf = new Resources.NotFoundException(
                    "Can't load adapter resource ID " +
                    context.getResources().getResourceEntryName(id));
            rnf.initCause(ex);
            throw rnf;
        } finally {
            if (parser != null) parser.close();
        }
    }

    /**
     * Generates an adapter using the specified XML parser. This method is responsible
     * for choosing the type of the adapter to create based on the content of the
     * XML parser.
     * 
     * This method will generate an {@link IllegalArgumentException} if
     * <code>assertName</code> is not null and does not match the root tag of the XML
     * document. 
     */
    private static BaseAdapter createAdapterFromXml(Context c,
            XmlPullParser parser, AttributeSet attrs, int id, Object[] parameters,
            String assertName) throws XmlPullParserException, IOException {

        BaseAdapter adapter = null;

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth) &&
                type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            if (assertName != null && !assertName.equals(name)) {
                throw new IllegalArgumentException("The adapter defined in " +
                        c.getResources().getResourceEntryName(id) + " must be a <" + name + " />");
            }

            if (ADAPTER_CURSOR.equals(name)) {
                adapter = createCursorAdapter(c, parser, attrs, id, parameters);
            } else {
                throw new IllegalArgumentException("Unknown adapter name " + parser.getName() +
                        " in " + c.getResources().getResourceEntryName(id));
            }
        }

        return adapter;

    }

    /**
     * Creates an XmlCursorAdapter using an XmlCursorAdapterParser.
     */
    private static XmlCursorAdapter createCursorAdapter(Context c, XmlPullParser parser,
            AttributeSet attrs, int id, Object[] parameters)
            throws IOException, XmlPullParserException {

        return new XmlCursorAdapterParser(c, parser, attrs, id).parse(parameters);
    }

    /**
     * Parser that can generate XmlCursorAdapter instances. This parser is responsible for
     * handling all the attributes and child nodes for a &lt;cursor-adapter /&gt;.
     */
    private static class XmlCursorAdapterParser {
        private static final String ADAPTER_CURSOR_BIND = "bind";
        private static final String ADAPTER_CURSOR_SELECT = "select";
        private static final String ADAPTER_CURSOR_AS_STRING = "string";
        private static final String ADAPTER_CURSOR_AS_IMAGE = "image";
        private static final String ADAPTER_CURSOR_AS_TAG = "tag";
        private static final String ADAPTER_CURSOR_AS_IMAGE_URI = "image-uri";
        private static final String ADAPTER_CURSOR_AS_DRAWABLE = "drawable";
        private static final String ADAPTER_CURSOR_MAP = "map";
        private static final String ADAPTER_CURSOR_TRANSFORM = "transform";

        private final Context mContext;
        private final XmlPullParser mParser;
        private final AttributeSet mAttrs;
        private final int mId;

        private final HashMap<String, CursorBinder> mBinders;
        private final ArrayList<String> mFrom;
        private final ArrayList<Integer> mTo;
        private final CursorTransformation mIdentity;
        private final Resources mResources;

        public XmlCursorAdapterParser(Context c, XmlPullParser parser, AttributeSet attrs, int id) {
            mContext = c;
            mParser = parser;
            mAttrs = attrs;
            mId = id;

            mResources = mContext.getResources();
            mBinders = new HashMap<String, CursorBinder>();
            mFrom = new ArrayList<String>();
            mTo = new ArrayList<Integer>();
            mIdentity = new IdentityTransformation(mContext);            
        }

        public XmlCursorAdapter parse(Object[] parameters)
               throws IOException, XmlPullParserException {

            Resources resources = mResources;
            TypedArray a = resources.obtainAttributes(mAttrs, android.R.styleable.CursorAdapter);

            String uri = a.getString(android.R.styleable.CursorAdapter_uri);
            String selection = a.getString(android.R.styleable.CursorAdapter_selection);
            String sortOrder = a.getString(android.R.styleable.CursorAdapter_sortOrder);
            int layout = a.getResourceId(android.R.styleable.CursorAdapter_layout, 0);
            if (layout == 0) {
                throw new IllegalArgumentException("The layout specified in " +
                        resources.getResourceEntryName(mId) + " does not exist");
            }

            a.recycle();

            XmlPullParser parser = mParser;
            int type;
            int depth = parser.getDepth();

            while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth) &&
                    type != XmlPullParser.END_DOCUMENT) {

                if (type != XmlPullParser.START_TAG) {
                    continue;
                }

                String name = parser.getName();

                if (ADAPTER_CURSOR_BIND.equals(name)) {
                    parseBindTag();
                } else if (ADAPTER_CURSOR_SELECT.equals(name)) {
                    parseSelectTag();
                } else {
                    throw new RuntimeException("Unknown tag name " + parser.getName() + " in " +
                            resources.getResourceEntryName(mId));
                }
            }

            String[] fromArray = mFrom.toArray(new String[mFrom.size()]);
            int[] toArray = new int[mTo.size()];
            for (int i = 0; i < toArray.length; i++) {
                toArray[i] = mTo.get(i);
            }

            String[] selectionArgs = null;
            if (parameters != null) {
                selectionArgs = new String[parameters.length];
                for (int i = 0; i < selectionArgs.length; i++) {
                    selectionArgs[i] = (String) parameters[i];
                }
            }

            return new XmlCursorAdapter(mContext, layout, uri, fromArray, toArray, selection,
                    selectionArgs, sortOrder, mBinders);
        }

        private void parseSelectTag() {
            TypedArray a = mResources.obtainAttributes(mAttrs,
                    android.R.styleable.CursorAdapter_SelectItem);

            String fromName = a.getString(android.R.styleable.CursorAdapter_SelectItem_column);
            if (fromName == null) {
                throw new IllegalArgumentException("A select item in " +
                        mResources.getResourceEntryName(mId) +
                        " does not have a 'column' attribute");
            }

            a.recycle();

            mFrom.add(fromName);
            mTo.add(View.NO_ID);
        }

        private void parseBindTag() throws IOException, XmlPullParserException {
            Resources resources = mResources;
            TypedArray a = resources.obtainAttributes(mAttrs,
                    android.R.styleable.CursorAdapter_BindItem);

            String fromName = a.getString(android.R.styleable.CursorAdapter_BindItem_from);
            if (fromName == null) {
                throw new IllegalArgumentException("A bind item in " +
                        resources.getResourceEntryName(mId) + " does not have a 'from' attribute");
            }

            int toName = a.getResourceId(android.R.styleable.CursorAdapter_BindItem_to, 0);
            if (toName == 0) {
                throw new IllegalArgumentException("A bind item in " +
                        resources.getResourceEntryName(mId) + " does not have a 'to' attribute");
            }

            String asType = a.getString(android.R.styleable.CursorAdapter_BindItem_as);
            if (asType == null) {
                throw new IllegalArgumentException("A bind item in " +
                        resources.getResourceEntryName(mId) + " does not have an 'as' attribute");
            }

            mFrom.add(fromName);
            mTo.add(toName);
            mBinders.put(fromName, findBinder(asType));

            a.recycle();
        }

        private CursorBinder findBinder(String type) throws IOException, XmlPullParserException {
            final XmlPullParser parser = mParser;
            final Context context = mContext;
            CursorTransformation transformation = mIdentity;

            int tagType;
            int depth = parser.getDepth();

            final boolean isDrawable = ADAPTER_CURSOR_AS_DRAWABLE.equals(type);            

            while (((tagType = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                    && tagType != XmlPullParser.END_DOCUMENT) {

                if (tagType != XmlPullParser.START_TAG) {
                    continue;
                }

                String name = parser.getName();

                if (ADAPTER_CURSOR_TRANSFORM.equals(name)) {
                    transformation = findTransformation();
                } else if (ADAPTER_CURSOR_MAP.equals(name)) {
                    if (!(transformation instanceof MapTransformation)) {
                        transformation = new MapTransformation(context);
                    }
                    findMap(((MapTransformation) transformation), isDrawable);
                } else {
                    throw new RuntimeException("Unknown tag name " + parser.getName() + " in " +
                            context.getResources().getResourceEntryName(mId));
                }
            }

            if (ADAPTER_CURSOR_AS_STRING.equals(type)) {
                return new StringBinder(context, transformation);
            } else if (ADAPTER_CURSOR_AS_TAG.equals(type)) {
                return new TagBinder(context, transformation);
            } else if (ADAPTER_CURSOR_AS_IMAGE.equals(type)) {
                return new ImageBinder(context, transformation);            
            } else if (ADAPTER_CURSOR_AS_IMAGE_URI.equals(type)) {
                return new ImageUriBinder(context, transformation);
            } else if (isDrawable) {
                return new DrawableBinder(context, transformation);
            } else {
                return createBinder(type, transformation);
            }
        }

        private CursorBinder createBinder(String type, CursorTransformation transformation) {
            if (mContext.isRestricted()) return null;

            try {
                final Class<?> klass = Class.forName(type, true, mContext.getClassLoader());
                if (CursorBinder.class.isAssignableFrom(klass)) {
                    final Constructor<?> c = klass.getDeclaredConstructor(
                            Context.class, CursorTransformation.class);
                    return (CursorBinder) c.newInstance(mContext, transformation);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Cannot instanciate binder type in " +
                        mContext.getResources().getResourceEntryName(mId) + ": " + type, e);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("Cannot instanciate binder type in " +
                        mContext.getResources().getResourceEntryName(mId) + ": " + type, e);
            } catch (InvocationTargetException e) {
                throw new IllegalArgumentException("Cannot instanciate binder type in " +
                        mContext.getResources().getResourceEntryName(mId) + ": " + type, e);
            } catch (InstantiationException e) {
                throw new IllegalArgumentException("Cannot instanciate binder type in " +
                        mContext.getResources().getResourceEntryName(mId) + ": " + type, e);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Cannot instanciate binder type in " +
                        mContext.getResources().getResourceEntryName(mId) + ": " + type, e);
            }

            return null;
        }

        private void findMap(MapTransformation transformation, boolean drawable) {
            Resources resources = mResources;

            TypedArray a = resources.obtainAttributes(mAttrs,
                    android.R.styleable.CursorAdapter_MapItem);

            String from = a.getString(android.R.styleable.CursorAdapter_MapItem_fromValue);
            if (from == null) {
                throw new IllegalArgumentException("A map item in " +
                        resources.getResourceEntryName(mId) +
                        " does not have a 'fromValue' attribute");
            }

            if (!drawable) {
                String to = a.getString(android.R.styleable.CursorAdapter_MapItem_toValue);
                if (to == null) {
                    throw new IllegalArgumentException("A map item in " +
                            resources.getResourceEntryName(mId) +
                            " does not have a 'toValue' attribute");
                }
                transformation.addStringMapping(from, to);
            } else {
                int to = a.getResourceId(android.R.styleable.CursorAdapter_MapItem_toValue, 0);
                if (to == 0) {
                    throw new IllegalArgumentException("A map item in " +
                            resources.getResourceEntryName(mId) +
                            " does not have a 'toValue' attribute");
                }
                transformation.addResourceMapping(from, to);
            }

            a.recycle();
        }

        private CursorTransformation findTransformation() {
            Resources resources = mResources;
            CursorTransformation transformation = null;
            TypedArray a = resources.obtainAttributes(mAttrs,
                    android.R.styleable.CursorAdapter_TransformItem);

            String className = a.getString(android.R.styleable.CursorAdapter_TransformItem_withClass);
            if (className == null) {
                String expression = a.getString(
                        android.R.styleable.CursorAdapter_TransformItem_withExpression);
                transformation = createExpressionTransformation(expression);
            } else if (!mContext.isRestricted()) {
                try {
                    final Class<?> klas = Class.forName(className, true, mContext.getClassLoader());
                    if (CursorTransformation.class.isAssignableFrom(klas)) {
                        final Constructor<?> c = klas.getDeclaredConstructor(Context.class);
                        transformation = (CursorTransformation) c.newInstance(mContext);
                    }
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Cannot instanciate transform type in " +
                           mContext.getResources().getResourceEntryName(mId) + ": " + className, e);
                } catch (NoSuchMethodException e) {
                    throw new IllegalArgumentException("Cannot instanciate transform type in " +
                           mContext.getResources().getResourceEntryName(mId) + ": " + className, e);
                } catch (InvocationTargetException e) {
                    throw new IllegalArgumentException("Cannot instanciate transform type in " +
                           mContext.getResources().getResourceEntryName(mId) + ": " + className, e);
                } catch (InstantiationException e) {
                    throw new IllegalArgumentException("Cannot instanciate transform type in " +
                           mContext.getResources().getResourceEntryName(mId) + ": " + className, e);
                } catch (IllegalAccessException e) {
                    throw new IllegalArgumentException("Cannot instanciate transform type in " +
                           mContext.getResources().getResourceEntryName(mId) + ": " + className, e);
                }
            }

            a.recycle();

            if (transformation == null) {
                throw new IllegalArgumentException("A transform item in " +
                    resources.getResourceEntryName(mId) + " must have a 'withClass' or " +
                    "'withExpression' attribute");
            }

            return transformation;
        }

        private CursorTransformation createExpressionTransformation(String expression) {
            return new ExpressionTransformation(mContext, expression);
        }
    }

    /**
     * Interface used by adapters that require to be loaded after creation.
     */
    private static interface ManagedAdapter {
        /**
         * Loads the content of the adapter, asynchronously.
         */
        void load();
    }

    /**
     * Implementation of a Cursor adapter defined in XML. This class is a thin wrapper
     * of a SimpleCursorAdapter. The main difference is the ability to handle CursorBinders.
     */
    private static class XmlCursorAdapter extends SimpleCursorAdapter implements ManagedAdapter {
        private String mUri;
        private final String mSelection;
        private final String[] mSelectionArgs;
        private final String mSortOrder;
        private final String[] mColumns;
        private final CursorBinder[] mBinders;
        private AsyncTask<Void,Void,Cursor> mLoadTask;

        XmlCursorAdapter(Context context, int layout, String uri, String[] from, int[] to,
                String selection, String[] selectionArgs, String sortOrder,
                HashMap<String, CursorBinder> binders) {

            super(context, layout, null, from, to);
            mContext = context;
            mUri = uri;
            mSelection = selection;
            mSelectionArgs = selectionArgs;
            mSortOrder = sortOrder;
            mColumns = new String[from.length + 1];
            // This is mandatory in CursorAdapter
            mColumns[0] = "_id";
            System.arraycopy(from, 0, mColumns, 1, from.length);

            CursorBinder basic = new StringBinder(context, new IdentityTransformation(context));
            final int count = from.length;
            mBinders = new CursorBinder[count];

            for (int i = 0; i < count; i++) {
                CursorBinder binder = binders.get(from[i]);
                if (binder == null) binder = basic;
                mBinders[i] = binder;
            }
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final int count = mTo.length;
            final int[] from = mFrom;
            final int[] to = mTo;
            final CursorBinder[] binders = mBinders;

            for (int i = 0; i < count; i++) {
                final View v = view.findViewById(to[i]);
                if (v != null) {
                    binders[i].bind(v, cursor, from[i]);
                }
            }
        }

        public void load() {
            if (mUri != null) {
                mLoadTask = new QueryTask().execute();
            }
        }

        void setUri(String uri) {
            mUri = uri;
        }

        @Override
        public void changeCursor(Cursor c) {
            if (mLoadTask != null && mLoadTask.getStatus() != QueryTask.Status.FINISHED) {
                mLoadTask.cancel(true);
                mLoadTask = null;
            }
            super.changeCursor(c);
        }

        class QueryTask extends AsyncTask<Void, Void, Cursor> {
            @Override
            protected Cursor doInBackground(Void... params) {
                if (mContext instanceof Activity) {
                    return ((Activity) mContext).managedQuery(
                            Uri.parse(mUri), mColumns, mSelection, mSelectionArgs, mSortOrder);
                } else {
                    return mContext.getContentResolver().query(
                            Uri.parse(mUri), mColumns, mSelection, mSelectionArgs, mSortOrder);
                }
            }

            @Override
            protected void onPostExecute(Cursor cursor) {
                if (!isCancelled()) {
                    XmlCursorAdapter.super.changeCursor(cursor);
                }
            }
        }
    }

    /**
     * Identity transformation, returns the content of the specified column as a String,
     * without performing any manipulation. This is used when no transformation is specified.
     */
    private static class IdentityTransformation extends CursorTransformation {
        public IdentityTransformation(Context context) {
            super(context);
        }

        @Override
        public String transform(Cursor cursor, int columnIndex) {
            return cursor.getString(columnIndex);
        }
    }

    /**
     * An expression transformation is a simple template based replacement utility.
     * In an expression, each segment of the form <code>{([^}]+)}</code> is replaced
     * with the value of the column of name $1.
     */
    private static class ExpressionTransformation extends CursorTransformation {
        private final ExpressionNode mFirstNode = new ConstantExpressionNode("");
        private final StringBuilder mBuilder = new StringBuilder();

        public ExpressionTransformation(Context context, String expression) {
            super(context);

            parse(expression);
        }

        private void parse(String expression) {
            ExpressionNode node = mFirstNode;
            int segmentStart;
            int count = expression.length();

            for (int i = 0; i < count; i++) {
                char c = expression.charAt(i);
                // Start a column name segment
                segmentStart = i;
                if (c == '{') {
                    while (i < count && (c = expression.charAt(i)) != '}') {
                        i++;
                    }
                    // We've reached the end, but the expression didn't close
                    if (c != '}') {
                        throw new IllegalStateException("The transform expression contains a " +
                                "non-closed column name: " +
                                expression.substring(segmentStart + 1, i));
                    }
                    node.next = new ColumnExpressionNode(expression.substring(segmentStart + 1, i));
                } else {
                    while (i < count && (c = expression.charAt(i)) != '{') {
                        i++;
                    }
                    node.next = new ConstantExpressionNode(expression.substring(segmentStart, i));
                    // Rewind if we've reached a column expression
                    if (c == '{') i--;
                }
                node = node.next;
            }
        }

        @Override
        public String transform(Cursor cursor, int columnIndex) {
            final StringBuilder builder = mBuilder;
            builder.delete(0, builder.length());

            ExpressionNode node = mFirstNode;
            // Skip the first node
            while ((node = node.next) != null) {
                builder.append(node.asString(cursor));
            }

            return builder.toString();
        }

        static abstract class ExpressionNode {
            public ExpressionNode next;

            public abstract String asString(Cursor cursor);
        }

        static class ConstantExpressionNode extends ExpressionNode {
            private final String mConstant;

            ConstantExpressionNode(String constant) {
                mConstant = constant;
            }

            @Override
            public String asString(Cursor cursor) {
                return mConstant;
            }
        }

        static class ColumnExpressionNode extends ExpressionNode {
            private final String mColumnName;
            private Cursor mSignature;
            private int mColumnIndex = -1;

            ColumnExpressionNode(String columnName) {
                mColumnName = columnName;
            }

            @Override
            public String asString(Cursor cursor) {
                if (cursor != mSignature || mColumnIndex == -1) {
                    mColumnIndex = cursor.getColumnIndex(mColumnName);
                    mSignature = cursor;
                }

                return cursor.getString(mColumnIndex);
            }
        }
    }

    /**
     * A map transformation offers a simple mapping between specified String values
     * to Strings or integers.
     */
    private static class MapTransformation extends CursorTransformation {
        private final HashMap<String, String> mStringMappings;
        private final HashMap<String, Integer> mResourceMappings;

        public MapTransformation(Context context) {
            super(context);
            mStringMappings = new HashMap<String, String>();
            mResourceMappings = new HashMap<String, Integer>();
        }

        void addStringMapping(String from, String to) {
            mStringMappings.put(from, to);
        }

        void addResourceMapping(String from, int to) {
            mResourceMappings.put(from, to);
        }

        @Override
        public String transform(Cursor cursor, int columnIndex) {
            final String value = cursor.getString(columnIndex);
            final String transformed = mStringMappings.get(value);
            return transformed == null ? value : transformed;
        }

        @Override
        public int transformToResource(Cursor cursor, int columnIndex) {
            final String value = cursor.getString(columnIndex);
            final Integer transformed = mResourceMappings.get(value);
            try {
                return transformed == null ? Integer.parseInt(value) : transformed;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /**
     * Binds a String to a TextView.
     */
    private static class StringBinder extends CursorBinder {
        public StringBinder(Context context, CursorTransformation transformation) {
            super(context, transformation);
        }

        @Override
        public boolean bind(View view, Cursor cursor, int columnIndex) {
            if (view instanceof TextView) {
                final String text = mTransformation.transform(cursor, columnIndex);
                ((TextView) view).setText(text);
                return true;
            }
            return false;
        }
    }

    /**
     * Binds an image blob to an ImageView.
     */
    private static class ImageBinder extends CursorBinder {
        public ImageBinder(Context context, CursorTransformation transformation) {
            super(context, transformation);
        }

        @Override
        public boolean bind(View view, Cursor cursor, int columnIndex) {
            if (view instanceof ImageView) {
                final byte[] data = cursor.getBlob(columnIndex);
                ((ImageView) view).setImageBitmap(BitmapFactory.decodeByteArray(data, 0,
                        data.length));
                return true;
            }
            return false;
        }
    }

    private static class TagBinder extends CursorBinder {
        public TagBinder(Context context, CursorTransformation transformation) {
            super(context, transformation);
        }

        @Override
        public boolean bind(View view, Cursor cursor, int columnIndex) {
            final String text = mTransformation.transform(cursor, columnIndex);
            view.setTag(text);
            return true;
        }
    }

    /**
     * Binds an image URI to an ImageView.
     */
    private static class ImageUriBinder extends CursorBinder {
        public ImageUriBinder(Context context, CursorTransformation transformation) {
            super(context, transformation);
        }

        @Override
        public boolean bind(View view, Cursor cursor, int columnIndex) {
            if (view instanceof ImageView) {
                ((ImageView) view).setImageURI(Uri.parse(
                        mTransformation.transform(cursor, columnIndex)));
                return true;
            }
            return false;
        }
    }

    /**
     * Binds a drawable resource identifier to an ImageView.
     */
    private static class DrawableBinder extends CursorBinder {
        public DrawableBinder(Context context, CursorTransformation transformation) {
            super(context, transformation);
        }

        @Override
        public boolean bind(View view, Cursor cursor, int columnIndex) {
            if (view instanceof ImageView) {
                final int resource = mTransformation.transformToResource(cursor, columnIndex);
                if (resource == 0) return false;

                ((ImageView) view).setImageResource(resource);
                return true;
            }
            return false;
        }
    }
}
