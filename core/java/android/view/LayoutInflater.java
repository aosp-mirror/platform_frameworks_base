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

package android.view;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Message;
import android.widget.FrameLayout;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashMap;

/**
 * Instantiates a layout XML file into its corresponding {@link android.view.View}
 * objects. It is never used directly. Instead, use
 * {@link android.app.Activity#getLayoutInflater()} or
 * {@link Context#getSystemService} to retrieve a standard LayoutInflater instance
 * that is already hooked up to the current context and correctly configured
 * for the device you are running on.  For example:
 *
 * <pre>LayoutInflater inflater = (LayoutInflater)context.getSystemService
 *      (Context.LAYOUT_INFLATER_SERVICE);</pre>
 * 
 * <p>
 * To create a new LayoutInflater with an additional {@link Factory} for your
 * own views, you can use {@link #cloneInContext} to clone an existing
 * ViewFactory, and then call {@link #setFactory} on it to include your
 * Factory.
 * 
 * <p>
 * For performance reasons, view inflation relies heavily on pre-processing of
 * XML files that is done at build time. Therefore, it is not currently possible
 * to use LayoutInflater with an XmlPullParser over a plain XML file at runtime;
 * it only works with an XmlPullParser returned from a compiled resource
 * (R.<em>something</em> file.)
 * 
 * @see Context#getSystemService
 */
public abstract class LayoutInflater {
    private final boolean DEBUG = false;

    /**
     * This field should be made private, so it is hidden from the SDK.
     * {@hide}
     */
    protected final Context mContext;

    // these are optional, set by the caller
    private boolean mFactorySet;
    private Factory mFactory;
    private Factory2 mFactory2;
    private Factory2 mPrivateFactory;
    private Filter mFilter;

    final Object[] mConstructorArgs = new Object[2];

    static final Class<?>[] mConstructorSignature = new Class[] {
            Context.class, AttributeSet.class};

    private static final HashMap<String, Constructor<? extends View>> sConstructorMap =
            new HashMap<String, Constructor<? extends View>>();
    
    private HashMap<String, Boolean> mFilterMap;

    private static final String TAG_MERGE = "merge";
    private static final String TAG_INCLUDE = "include";
    private static final String TAG_1995 = "blink";
    private static final String TAG_REQUEST_FOCUS = "requestFocus";

    /**
     * Hook to allow clients of the LayoutInflater to restrict the set of Views that are allowed
     * to be inflated.
     * 
     */
    public interface Filter {
        /**
         * Hook to allow clients of the LayoutInflater to restrict the set of Views 
         * that are allowed to be inflated.
         * 
         * @param clazz The class object for the View that is about to be inflated
         * 
         * @return True if this class is allowed to be inflated, or false otherwise
         */
        @SuppressWarnings("unchecked")
        boolean onLoadClass(Class clazz);
    }
    
    public interface Factory {
        /**
         * Hook you can supply that is called when inflating from a LayoutInflater.
         * You can use this to customize the tag names available in your XML
         * layout files.
         * 
         * <p>
         * Note that it is good practice to prefix these custom names with your
         * package (i.e., com.coolcompany.apps) to avoid conflicts with system
         * names.
         * 
         * @param name Tag name to be inflated.
         * @param context The context the view is being created in.
         * @param attrs Inflation attributes as specified in XML file.
         * 
         * @return View Newly created view. Return null for the default
         *         behavior.
         */
        public View onCreateView(String name, Context context, AttributeSet attrs);
    }

    public interface Factory2 extends Factory {
        /**
         * Version of {@link #onCreateView(String, Context, AttributeSet)}
         * that also supplies the parent that the view created view will be
         * placed in.
         *
         * @param parent The parent that the created view will be placed
         * in; <em>note that this may be null</em>.
         * @param name Tag name to be inflated.
         * @param context The context the view is being created in.
         * @param attrs Inflation attributes as specified in XML file.
         *
         * @return View Newly created view. Return null for the default
         *         behavior.
         */
        public View onCreateView(View parent, String name, Context context, AttributeSet attrs);
    }

    private static class FactoryMerger implements Factory2 {
        private final Factory mF1, mF2;
        private final Factory2 mF12, mF22;
        
        FactoryMerger(Factory f1, Factory2 f12, Factory f2, Factory2 f22) {
            mF1 = f1;
            mF2 = f2;
            mF12 = f12;
            mF22 = f22;
        }
        
        public View onCreateView(String name, Context context, AttributeSet attrs) {
            View v = mF1.onCreateView(name, context, attrs);
            if (v != null) return v;
            return mF2.onCreateView(name, context, attrs);
        }

        public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
            View v = mF12 != null ? mF12.onCreateView(parent, name, context, attrs)
                    : mF1.onCreateView(name, context, attrs);
            if (v != null) return v;
            return mF22 != null ? mF22.onCreateView(parent, name, context, attrs)
                    : mF2.onCreateView(name, context, attrs);
        }
    }
    
    /**
     * Create a new LayoutInflater instance associated with a particular Context.
     * Applications will almost always want to use
     * {@link Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link Context#LAYOUT_INFLATER_SERVICE Context.INFLATER_SERVICE}.
     * 
     * @param context The Context in which this LayoutInflater will create its
     * Views; most importantly, this supplies the theme from which the default
     * values for their attributes are retrieved.
     */
    protected LayoutInflater(Context context) {
        mContext = context;
    }

    /**
     * Create a new LayoutInflater instance that is a copy of an existing
     * LayoutInflater, optionally with its Context changed.  For use in
     * implementing {@link #cloneInContext}.
     * 
     * @param original The original LayoutInflater to copy.
     * @param newContext The new Context to use.
     */
    protected LayoutInflater(LayoutInflater original, Context newContext) {
        mContext = newContext;
        mFactory = original.mFactory;
        mFactory2 = original.mFactory2;
        mPrivateFactory = original.mPrivateFactory;
        mFilter = original.mFilter;
    }
    
    /**
     * Obtains the LayoutInflater from the given context.
     */
    public static LayoutInflater from(Context context) {
        LayoutInflater LayoutInflater =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (LayoutInflater == null) {
            throw new AssertionError("LayoutInflater not found.");
        }
        return LayoutInflater;
    }

    /**
     * Create a copy of the existing LayoutInflater object, with the copy
     * pointing to a different Context than the original.  This is used by
     * {@link ContextThemeWrapper} to create a new LayoutInflater to go along
     * with the new Context theme.
     * 
     * @param newContext The new Context to associate with the new LayoutInflater.
     * May be the same as the original Context if desired.
     * 
     * @return Returns a brand spanking new LayoutInflater object associated with
     * the given Context.
     */
    public abstract LayoutInflater cloneInContext(Context newContext);
    
    /**
     * Return the context we are running in, for access to resources, class
     * loader, etc.
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Return the current {@link Factory} (or null). This is called on each element
     * name. If the factory returns a View, add that to the hierarchy. If it
     * returns null, proceed to call onCreateView(name).
     */
    public final Factory getFactory() {
        return mFactory;
    }

    /**
     * Return the current {@link Factory2}.  Returns null if no factory is set
     * or the set factory does not implement the {@link Factory2} interface.
     * This is called on each element
     * name. If the factory returns a View, add that to the hierarchy. If it
     * returns null, proceed to call onCreateView(name).
     */
    public final Factory2 getFactory2() {
        return mFactory2;
    }

    /**
     * Attach a custom Factory interface for creating views while using
     * this LayoutInflater.  This must not be null, and can only be set once;
     * after setting, you can not change the factory.  This is
     * called on each element name as the xml is parsed. If the factory returns
     * a View, that is added to the hierarchy. If it returns null, the next
     * factory default {@link #onCreateView} method is called.
     * 
     * <p>If you have an existing
     * LayoutInflater and want to add your own factory to it, use
     * {@link #cloneInContext} to clone the existing instance and then you
     * can use this function (once) on the returned new instance.  This will
     * merge your own factory with whatever factory the original instance is
     * using.
     */
    public void setFactory(Factory factory) {
        if (mFactorySet) {
            throw new IllegalStateException("A factory has already been set on this LayoutInflater");
        }
        if (factory == null) {
            throw new NullPointerException("Given factory can not be null");
        }
        mFactorySet = true;
        if (mFactory == null) {
            mFactory = factory;
        } else {
            mFactory = new FactoryMerger(factory, null, mFactory, mFactory2);
        }
    }

    /**
     * Like {@link #setFactory}, but allows you to set a {@link Factory2}
     * interface.
     */
    public void setFactory2(Factory2 factory) {
        if (mFactorySet) {
            throw new IllegalStateException("A factory has already been set on this LayoutInflater");
        }
        if (factory == null) {
            throw new NullPointerException("Given factory can not be null");
        }
        mFactorySet = true;
        if (mFactory == null) {
            mFactory = mFactory2 = factory;
        } else {
            mFactory = new FactoryMerger(factory, factory, mFactory, mFactory2);
        }
    }

    /**
     * @hide for use by framework
     */
    public void setPrivateFactory(Factory2 factory) {
        mPrivateFactory = factory;
    }

    /**
     * @return The {@link Filter} currently used by this LayoutInflater to restrict the set of Views
     * that are allowed to be inflated.
     */
    public Filter getFilter() {
        return mFilter;
    }
    
    /**
     * Sets the {@link Filter} to by this LayoutInflater. If a view is attempted to be inflated
     * which is not allowed by the {@link Filter}, the {@link #inflate(int, ViewGroup)} call will
     * throw an {@link InflateException}. This filter will replace any previous filter set on this
     * LayoutInflater.
     * 
     * @param filter The Filter which restricts the set of Views that are allowed to be inflated.
     *        This filter will replace any previous filter set on this LayoutInflater.
     */
    public void setFilter(Filter filter) {
        mFilter = filter;
        if (filter != null) {
            mFilterMap = new HashMap<String, Boolean>();
        }
    }

    /**
     * Inflate a new view hierarchy from the specified xml resource. Throws
     * {@link InflateException} if there is an error.
     * 
     * @param resource ID for an XML layout resource to load (e.g.,
     *        <code>R.layout.main_page</code>)
     * @param root Optional view to be the parent of the generated hierarchy.
     * @return The root View of the inflated hierarchy. If root was supplied,
     *         this is the root View; otherwise it is the root of the inflated
     *         XML file.
     */
    public View inflate(int resource, ViewGroup root) {
        return inflate(resource, root, root != null);
    }

    /**
     * Inflate a new view hierarchy from the specified xml node. Throws
     * {@link InflateException} if there is an error. *
     * <p>
     * <em><strong>Important</strong></em>&nbsp;&nbsp;&nbsp;For performance
     * reasons, view inflation relies heavily on pre-processing of XML files
     * that is done at build time. Therefore, it is not currently possible to
     * use LayoutInflater with an XmlPullParser over a plain XML file at runtime.
     * 
     * @param parser XML dom node containing the description of the view
     *        hierarchy.
     * @param root Optional view to be the parent of the generated hierarchy.
     * @return The root View of the inflated hierarchy. If root was supplied,
     *         this is the root View; otherwise it is the root of the inflated
     *         XML file.
     */
    public View inflate(XmlPullParser parser, ViewGroup root) {
        return inflate(parser, root, root != null);
    }

    /**
     * Inflate a new view hierarchy from the specified xml resource. Throws
     * {@link InflateException} if there is an error.
     * 
     * @param resource ID for an XML layout resource to load (e.g.,
     *        <code>R.layout.main_page</code>)
     * @param root Optional view to be the parent of the generated hierarchy (if
     *        <em>attachToRoot</em> is true), or else simply an object that
     *        provides a set of LayoutParams values for root of the returned
     *        hierarchy (if <em>attachToRoot</em> is false.)
     * @param attachToRoot Whether the inflated hierarchy should be attached to
     *        the root parameter? If false, root is only used to create the
     *        correct subclass of LayoutParams for the root view in the XML.
     * @return The root View of the inflated hierarchy. If root was supplied and
     *         attachToRoot is true, this is root; otherwise it is the root of
     *         the inflated XML file.
     */
    public View inflate(int resource, ViewGroup root, boolean attachToRoot) {
        if (DEBUG) System.out.println("INFLATING from resource: " + resource);
        XmlResourceParser parser = getContext().getResources().getLayout(resource);
        try {
            return inflate(parser, root, attachToRoot);
        } finally {
            parser.close();
        }
    }

    /**
     * Inflate a new view hierarchy from the specified XML node. Throws
     * {@link InflateException} if there is an error.
     * <p>
     * <em><strong>Important</strong></em>&nbsp;&nbsp;&nbsp;For performance
     * reasons, view inflation relies heavily on pre-processing of XML files
     * that is done at build time. Therefore, it is not currently possible to
     * use LayoutInflater with an XmlPullParser over a plain XML file at runtime.
     * 
     * @param parser XML dom node containing the description of the view
     *        hierarchy.
     * @param root Optional view to be the parent of the generated hierarchy (if
     *        <em>attachToRoot</em> is true), or else simply an object that
     *        provides a set of LayoutParams values for root of the returned
     *        hierarchy (if <em>attachToRoot</em> is false.)
     * @param attachToRoot Whether the inflated hierarchy should be attached to
     *        the root parameter? If false, root is only used to create the
     *        correct subclass of LayoutParams for the root view in the XML.
     * @return The root View of the inflated hierarchy. If root was supplied and
     *         attachToRoot is true, this is root; otherwise it is the root of
     *         the inflated XML file.
     */
    public View inflate(XmlPullParser parser, ViewGroup root, boolean attachToRoot) {
        synchronized (mConstructorArgs) {
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            Context lastContext = (Context)mConstructorArgs[0];
            mConstructorArgs[0] = mContext;
            View result = root;

            try {
                // Look for the root node.
                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG &&
                        type != XmlPullParser.END_DOCUMENT) {
                    // Empty
                }

                if (type != XmlPullParser.START_TAG) {
                    throw new InflateException(parser.getPositionDescription()
                            + ": No start tag found!");
                }

                final String name = parser.getName();
                
                if (DEBUG) {
                    System.out.println("**************************");
                    System.out.println("Creating root view: "
                            + name);
                    System.out.println("**************************");
                }

                if (TAG_MERGE.equals(name)) {
                    if (root == null || !attachToRoot) {
                        throw new InflateException("<merge /> can be used only with a valid "
                                + "ViewGroup root and attachToRoot=true");
                    }

                    rInflate(parser, root, attrs, false);
                } else {
                    // Temp is the root view that was found in the xml
                    View temp;
                    if (TAG_1995.equals(name)) {
                        temp = new BlinkLayout(mContext, attrs);
                    } else {
                        temp = createViewFromTag(root, name, attrs);
                    }

                    ViewGroup.LayoutParams params = null;

                    if (root != null) {
                        if (DEBUG) {
                            System.out.println("Creating params from root: " +
                                    root);
                        }
                        // Create layout params that match root, if supplied
                        params = root.generateLayoutParams(attrs);
                        if (!attachToRoot) {
                            // Set the layout params for temp if we are not
                            // attaching. (If we are, we use addView, below)
                            temp.setLayoutParams(params);
                        }
                    }

                    if (DEBUG) {
                        System.out.println("-----> start inflating children");
                    }
                    // Inflate all children under temp
                    rInflate(parser, temp, attrs, true);
                    if (DEBUG) {
                        System.out.println("-----> done inflating children");
                    }

                    // We are supposed to attach all the views we found (int temp)
                    // to root. Do that now.
                    if (root != null && attachToRoot) {
                        root.addView(temp, params);
                    }

                    // Decide whether to return the root that was passed in or the
                    // top view found in xml.
                    if (root == null || !attachToRoot) {
                        result = temp;
                    }
                }

            } catch (XmlPullParserException e) {
                InflateException ex = new InflateException(e.getMessage());
                ex.initCause(e);
                throw ex;
            } catch (IOException e) {
                InflateException ex = new InflateException(
                        parser.getPositionDescription()
                        + ": " + e.getMessage());
                ex.initCause(e);
                throw ex;
            } finally {
                // Don't retain static reference on context.
                mConstructorArgs[0] = lastContext;
                mConstructorArgs[1] = null;
            }

            return result;
        }
    }

    /**
     * Low-level function for instantiating a view by name. This attempts to
     * instantiate a view class of the given <var>name</var> found in this
     * LayoutInflater's ClassLoader.
     * 
     * <p>
     * There are two things that can happen in an error case: either the
     * exception describing the error will be thrown, or a null will be
     * returned. You must deal with both possibilities -- the former will happen
     * the first time createView() is called for a class of a particular name,
     * the latter every time there-after for that class name.
     * 
     * @param name The full name of the class to be instantiated.
     * @param attrs The XML attributes supplied for this instance.
     * 
     * @return View The newly instantiated view, or null.
     */
    public final View createView(String name, String prefix, AttributeSet attrs)
            throws ClassNotFoundException, InflateException {
        Constructor<? extends View> constructor = sConstructorMap.get(name);
        Class<? extends View> clazz = null;

        try {
            if (constructor == null) {
                // Class not found in the cache, see if it's real, and try to add it
                clazz = mContext.getClassLoader().loadClass(
                        prefix != null ? (prefix + name) : name).asSubclass(View.class);
                
                if (mFilter != null && clazz != null) {
                    boolean allowed = mFilter.onLoadClass(clazz);
                    if (!allowed) {
                        failNotAllowed(name, prefix, attrs);
                    }
                }
                constructor = clazz.getConstructor(mConstructorSignature);
                sConstructorMap.put(name, constructor);
            } else {
                // If we have a filter, apply it to cached constructor
                if (mFilter != null) {
                    // Have we seen this name before?
                    Boolean allowedState = mFilterMap.get(name);
                    if (allowedState == null) {
                        // New class -- remember whether it is allowed
                        clazz = mContext.getClassLoader().loadClass(
                                prefix != null ? (prefix + name) : name).asSubclass(View.class);
                        
                        boolean allowed = clazz != null && mFilter.onLoadClass(clazz);
                        mFilterMap.put(name, allowed);
                        if (!allowed) {
                            failNotAllowed(name, prefix, attrs);
                        }
                    } else if (allowedState.equals(Boolean.FALSE)) {
                        failNotAllowed(name, prefix, attrs);
                    }
                }
            }

            Object[] args = mConstructorArgs;
            args[1] = attrs;
            return constructor.newInstance(args);

        } catch (NoSuchMethodException e) {
            InflateException ie = new InflateException(attrs.getPositionDescription()
                    + ": Error inflating class "
                    + (prefix != null ? (prefix + name) : name));
            ie.initCause(e);
            throw ie;

        } catch (ClassCastException e) {
            // If loaded class is not a View subclass
            InflateException ie = new InflateException(attrs.getPositionDescription()
                    + ": Class is not a View "
                    + (prefix != null ? (prefix + name) : name));
            ie.initCause(e);
            throw ie;
        } catch (ClassNotFoundException e) {
            // If loadClass fails, we should propagate the exception.
            throw e;
        } catch (Exception e) {
            InflateException ie = new InflateException(attrs.getPositionDescription()
                    + ": Error inflating class "
                    + (clazz == null ? "<unknown>" : clazz.getName()));
            ie.initCause(e);
            throw ie;
        }
    }

    /**
     * Throw an exception because the specified class is not allowed to be inflated.
     */
    private void failNotAllowed(String name, String prefix, AttributeSet attrs) {
        throw new InflateException(attrs.getPositionDescription()
                + ": Class not allowed to be inflated "
                + (prefix != null ? (prefix + name) : name));
    }

    /**
     * This routine is responsible for creating the correct subclass of View
     * given the xml element name. Override it to handle custom view objects. If
     * you override this in your subclass be sure to call through to
     * super.onCreateView(name) for names you do not recognize.
     * 
     * @param name The fully qualified class name of the View to be create.
     * @param attrs An AttributeSet of attributes to apply to the View.
     * 
     * @return View The View created.
     */
    protected View onCreateView(String name, AttributeSet attrs)
            throws ClassNotFoundException {
        return createView(name, "android.view.", attrs);
    }

    /**
     * Version of {@link #onCreateView(String, AttributeSet)} that also
     * takes the future parent of the view being constructure.  The default
     * implementation simply calls {@link #onCreateView(String, AttributeSet)}.
     *
     * @param parent The future parent of the returned view.  <em>Note that
     * this may be null.</em>
     * @param name The fully qualified class name of the View to be create.
     * @param attrs An AttributeSet of attributes to apply to the View.
     *
     * @return View The View created.
     */
    protected View onCreateView(View parent, String name, AttributeSet attrs)
            throws ClassNotFoundException {
        return onCreateView(name, attrs);
    }

    /*
     * default visibility so the BridgeInflater can override it.
     */
    View createViewFromTag(View parent, String name, AttributeSet attrs) {
        if (name.equals("view")) {
            name = attrs.getAttributeValue(null, "class");
        }

        if (DEBUG) System.out.println("******** Creating view: " + name);

        try {
            View view;
            if (mFactory2 != null) view = mFactory2.onCreateView(parent, name, mContext, attrs);
            else if (mFactory != null) view = mFactory.onCreateView(name, mContext, attrs);
            else view = null;

            if (view == null && mPrivateFactory != null) {
                view = mPrivateFactory.onCreateView(parent, name, mContext, attrs);
            }
            
            if (view == null) {
                if (-1 == name.indexOf('.')) {
                    view = onCreateView(parent, name, attrs);
                } else {
                    view = createView(name, null, attrs);
                }
            }

            if (DEBUG) System.out.println("Created view is: " + view);
            return view;

        } catch (InflateException e) {
            throw e;

        } catch (ClassNotFoundException e) {
            InflateException ie = new InflateException(attrs.getPositionDescription()
                    + ": Error inflating class " + name);
            ie.initCause(e);
            throw ie;

        } catch (Exception e) {
            InflateException ie = new InflateException(attrs.getPositionDescription()
                    + ": Error inflating class " + name);
            ie.initCause(e);
            throw ie;
        }
    }

    /**
     * Recursive method used to descend down the xml hierarchy and instantiate
     * views, instantiate their children, and then call onFinishInflate().
     */
    void rInflate(XmlPullParser parser, View parent, final AttributeSet attrs,
            boolean finishInflate) throws XmlPullParserException, IOException {

        final int depth = parser.getDepth();
        int type;

        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final String name = parser.getName();
            
            if (TAG_REQUEST_FOCUS.equals(name)) {
                parseRequestFocus(parser, parent);
            } else if (TAG_INCLUDE.equals(name)) {
                if (parser.getDepth() == 0) {
                    throw new InflateException("<include /> cannot be the root element");
                }
                parseInclude(parser, parent, attrs);
            } else if (TAG_MERGE.equals(name)) {
                throw new InflateException("<merge /> must be the root element");
            } else if (TAG_1995.equals(name)) {
                final View view = new BlinkLayout(mContext, attrs);
                final ViewGroup viewGroup = (ViewGroup) parent;
                final ViewGroup.LayoutParams params = viewGroup.generateLayoutParams(attrs);
                rInflate(parser, view, attrs, true);
                viewGroup.addView(view, params);                
            } else {
                final View view = createViewFromTag(parent, name, attrs);
                final ViewGroup viewGroup = (ViewGroup) parent;
                final ViewGroup.LayoutParams params = viewGroup.generateLayoutParams(attrs);
                rInflate(parser, view, attrs, true);
                viewGroup.addView(view, params);
            }
        }

        if (finishInflate) parent.onFinishInflate();
    }

    private void parseRequestFocus(XmlPullParser parser, View parent)
            throws XmlPullParserException, IOException {
        int type;
        parent.requestFocus();
        final int currentDepth = parser.getDepth();
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > currentDepth) && type != XmlPullParser.END_DOCUMENT) {
            // Empty
        }
    }

    private void parseInclude(XmlPullParser parser, View parent, AttributeSet attrs)
            throws XmlPullParserException, IOException {

        int type;

        if (parent instanceof ViewGroup) {
            final int layout = attrs.getAttributeResourceValue(null, "layout", 0);
            if (layout == 0) {
                final String value = attrs.getAttributeValue(null, "layout");
                if (value == null) {
                    throw new InflateException("You must specifiy a layout in the"
                            + " include tag: <include layout=\"@layout/layoutID\" />");
                } else {
                    throw new InflateException("You must specifiy a valid layout "
                            + "reference. The layout ID " + value + " is not valid.");
                }
            } else {
                final XmlResourceParser childParser =
                        getContext().getResources().getLayout(layout);

                try {
                    final AttributeSet childAttrs = Xml.asAttributeSet(childParser);

                    while ((type = childParser.next()) != XmlPullParser.START_TAG &&
                            type != XmlPullParser.END_DOCUMENT) {
                        // Empty.
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new InflateException(childParser.getPositionDescription() +
                                ": No start tag found!");
                    }

                    final String childName = childParser.getName();

                    if (TAG_MERGE.equals(childName)) {
                        // Inflate all children.
                        rInflate(childParser, parent, childAttrs, false);
                    } else {
                        final View view = createViewFromTag(parent, childName, childAttrs);
                        final ViewGroup group = (ViewGroup) parent;

                        // We try to load the layout params set in the <include /> tag. If
                        // they don't exist, we will rely on the layout params set in the
                        // included XML file.
                        // During a layoutparams generation, a runtime exception is thrown
                        // if either layout_width or layout_height is missing. We catch
                        // this exception and set localParams accordingly: true means we
                        // successfully loaded layout params from the <include /> tag,
                        // false means we need to rely on the included layout params.
                        ViewGroup.LayoutParams params = null;
                        try {
                            params = group.generateLayoutParams(attrs);
                        } catch (RuntimeException e) {
                            params = group.generateLayoutParams(childAttrs);
                        } finally {
                            if (params != null) {
                                view.setLayoutParams(params);
                            }
                        }

                        // Inflate all children.
                        rInflate(childParser, view, childAttrs, true);

                        // Attempt to override the included layout's android:id with the
                        // one set on the <include /> tag itself.
                        TypedArray a = mContext.obtainStyledAttributes(attrs,
                            com.android.internal.R.styleable.View, 0, 0);
                        int id = a.getResourceId(com.android.internal.R.styleable.View_id, View.NO_ID);
                        // While we're at it, let's try to override android:visibility.
                        int visibility = a.getInt(com.android.internal.R.styleable.View_visibility, -1);
                        a.recycle();

                        if (id != View.NO_ID) {
                            view.setId(id);
                        }

                        switch (visibility) {
                            case 0:
                                view.setVisibility(View.VISIBLE);
                                break;
                            case 1:
                                view.setVisibility(View.INVISIBLE);
                                break;
                            case 2:
                                view.setVisibility(View.GONE);
                                break;
                        }

                        group.addView(view);
                    }
                } finally {
                    childParser.close();
                }
            }
        } else {
            throw new InflateException("<include /> can only be used inside of a ViewGroup");
        }

        final int currentDepth = parser.getDepth();
        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > currentDepth) && type != XmlPullParser.END_DOCUMENT) {
            // Empty
        }
    }

    private static class BlinkLayout extends FrameLayout {
        private static final int MESSAGE_BLINK = 0x42;
        private static final int BLINK_DELAY = 500;

        private boolean mBlink;
        private boolean mBlinkState;
        private final Handler mHandler;

        public BlinkLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
            mHandler = new Handler(new Handler.Callback() {
                @Override
                public boolean handleMessage(Message msg) {
                    if (msg.what == MESSAGE_BLINK) {
                        if (mBlink) {
                            mBlinkState = !mBlinkState;
                            makeBlink();
                        }
                        invalidate();
                        return true;
                    }
                    return false;
                }
            });
        }

        private void makeBlink() {
            Message message = mHandler.obtainMessage(MESSAGE_BLINK);
            mHandler.sendMessageDelayed(message, BLINK_DELAY);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();

            mBlink = true;
            mBlinkState = true;

            makeBlink();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();

            mBlink = false;
            mBlinkState = true;

            mHandler.removeMessages(MESSAGE_BLINK);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (mBlinkState) {
                super.dispatchDraw(canvas);
            }
        }
    }
}
