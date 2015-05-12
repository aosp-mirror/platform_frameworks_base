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

package android.preference;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.XmlRes;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.ContextThemeWrapper;
import android.view.InflateException;
import android.view.LayoutInflater;

// TODO: fix generics
/**
 * Generic XML inflater. This has been adapted from {@link LayoutInflater} and
 * quickly passed over to use generics.
 * 
 * @hide
 * @param T The type of the items to inflate
 * @param P The type of parents (that is those items that contain other items).
 *            Must implement {@link GenericInflater.Parent}
 */
abstract class GenericInflater<T, P extends GenericInflater.Parent> {
    private final boolean DEBUG = false;

    protected final Context mContext;

    // these are optional, set by the caller
    private boolean mFactorySet;
    private Factory<T> mFactory;

    private final Object[] mConstructorArgs = new Object[2];

    private static final Class[] mConstructorSignature = new Class[] {
            Context.class, AttributeSet.class};

    private static final HashMap sConstructorMap = new HashMap();

    private String mDefaultPackage;

    public interface Parent<T> {
        public void addItemFromInflater(T child);
    }
    
    public interface Factory<T> {
        /**
         * Hook you can supply that is called when inflating from a
         * inflater. You can use this to customize the tag
         * names available in your XML files.
         * <p>
         * Note that it is good practice to prefix these custom names with your
         * package (i.e., com.coolcompany.apps) to avoid conflicts with system
         * names.
         * 
         * @param name Tag name to be inflated.
         * @param context The context the item is being created in.
         * @param attrs Inflation attributes as specified in XML file.
         * @return Newly created item. Return null for the default behavior.
         */
        public T onCreateItem(String name, Context context, AttributeSet attrs);
    }

    private static class FactoryMerger<T> implements Factory<T> {
        private final Factory<T> mF1, mF2;
        
        FactoryMerger(Factory<T> f1, Factory<T> f2) {
            mF1 = f1;
            mF2 = f2;
        }
        
        public T onCreateItem(String name, Context context, AttributeSet attrs) {
            T v = mF1.onCreateItem(name, context, attrs);
            if (v != null) return v;
            return mF2.onCreateItem(name, context, attrs);
        }
    }
    
    /**
     * Create a new inflater instance associated with a
     * particular Context.
     * 
     * @param context The Context in which this inflater will
     *            create its items; most importantly, this supplies the theme
     *            from which the default values for their attributes are
     *            retrieved.
     */
    protected GenericInflater(Context context) {
        mContext = context;
    }

    /**
     * Create a new inflater instance that is a copy of an
     * existing inflater, optionally with its Context
     * changed. For use in implementing {@link #cloneInContext}.
     * 
     * @param original The original inflater to copy.
     * @param newContext The new Context to use.
     */
    protected GenericInflater(GenericInflater<T,P> original, Context newContext) {
        mContext = newContext;
        mFactory = original.mFactory;
    }
    
    /**
     * Create a copy of the existing inflater object, with the copy
     * pointing to a different Context than the original.  This is used by
     * {@link ContextThemeWrapper} to create a new inflater to go along
     * with the new Context theme.
     * 
     * @param newContext The new Context to associate with the new inflater.
     * May be the same as the original Context if desired.
     * 
     * @return Returns a brand spanking new inflater object associated with
     * the given Context.
     */
    public abstract GenericInflater cloneInContext(Context newContext);
    
    /**
     * Sets the default package that will be searched for classes to construct
     * for tag names that have no explicit package.
     * 
     * @param defaultPackage The default package. This will be prepended to the
     *            tag name, so it should end with a period.
     */
    public void setDefaultPackage(String defaultPackage) {
        mDefaultPackage = defaultPackage;
    }
    
    /**
     * Returns the default package, or null if it is not set.
     * 
     * @see #setDefaultPackage(String)
     * @return The default package.
     */
    public String getDefaultPackage() {
        return mDefaultPackage;
    }
    
    /**
     * Return the context we are running in, for access to resources, class
     * loader, etc.
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Return the current factory (or null). This is called on each element
     * name. If the factory returns an item, add that to the hierarchy. If it
     * returns null, proceed to call onCreateItem(name).
     */
    public final Factory<T> getFactory() {
        return mFactory;
    }

    /**
     * Attach a custom Factory interface for creating items while using this
     * inflater. This must not be null, and can only be set
     * once; after setting, you can not change the factory. This is called on
     * each element name as the XML is parsed. If the factory returns an item,
     * that is added to the hierarchy. If it returns null, the next factory
     * default {@link #onCreateItem} method is called.
     * <p>
     * If you have an existing inflater and want to add your
     * own factory to it, use {@link #cloneInContext} to clone the existing
     * instance and then you can use this function (once) on the returned new
     * instance. This will merge your own factory with whatever factory the
     * original instance is using.
     */
    public void setFactory(Factory<T> factory) {
        if (mFactorySet) {
            throw new IllegalStateException("" +
                    "A factory has already been set on this inflater");
        }
        if (factory == null) {
            throw new NullPointerException("Given factory can not be null");
        }
        mFactorySet = true;
        if (mFactory == null) {
            mFactory = factory;
        } else {
            mFactory = new FactoryMerger<T>(factory, mFactory);
        }
    }


    /**
     * Inflate a new item hierarchy from the specified xml resource. Throws
     * InflaterException if there is an error.
     * 
     * @param resource ID for an XML resource to load (e.g.,
     *        <code>R.layout.main_page</code>)
     * @param root Optional parent of the generated hierarchy.
     * @return The root of the inflated hierarchy. If root was supplied,
     *         this is the root item; otherwise it is the root of the inflated
     *         XML file.
     */
    public T inflate(@XmlRes int resource, P root) {
        return inflate(resource, root, root != null);
    }

    /**
     * Inflate a new hierarchy from the specified xml node. Throws
     * InflaterException if there is an error. *
     * <p>
     * <em><strong>Important</strong></em>&nbsp;&nbsp;&nbsp;For performance
     * reasons, inflation relies heavily on pre-processing of XML files
     * that is done at build time. Therefore, it is not currently possible to
     * use inflater with an XmlPullParser over a plain XML file at runtime.
     * 
     * @param parser XML dom node containing the description of the
     *        hierarchy.
     * @param root Optional parent of the generated hierarchy.
     * @return The root of the inflated hierarchy. If root was supplied,
     *         this is the that; otherwise it is the root of the inflated
     *         XML file.
     */
    public T inflate(XmlPullParser parser, P root) {
        return inflate(parser, root, root != null);
    }

    /**
     * Inflate a new hierarchy from the specified xml resource. Throws
     * InflaterException if there is an error.
     * 
     * @param resource ID for an XML resource to load (e.g.,
     *        <code>R.layout.main_page</code>)
     * @param root Optional root to be the parent of the generated hierarchy (if
     *        <em>attachToRoot</em> is true), or else simply an object that
     *        provides a set of values for root of the returned
     *        hierarchy (if <em>attachToRoot</em> is false.)
     * @param attachToRoot Whether the inflated hierarchy should be attached to
     *        the root parameter?
     * @return The root of the inflated hierarchy. If root was supplied and
     *         attachToRoot is true, this is root; otherwise it is the root of
     *         the inflated XML file.
     */
    public T inflate(@XmlRes int resource, P root, boolean attachToRoot) {
        if (DEBUG) System.out.println("INFLATING from resource: " + resource);
        XmlResourceParser parser = getContext().getResources().getXml(resource);
        try {
            return inflate(parser, root, attachToRoot);
        } finally {
            parser.close();
        }
    }

    /**
     * Inflate a new hierarchy from the specified XML node. Throws
     * InflaterException if there is an error.
     * <p>
     * <em><strong>Important</strong></em>&nbsp;&nbsp;&nbsp;For performance
     * reasons, inflation relies heavily on pre-processing of XML files
     * that is done at build time. Therefore, it is not currently possible to
     * use inflater with an XmlPullParser over a plain XML file at runtime.
     * 
     * @param parser XML dom node containing the description of the
     *        hierarchy.
     * @param root Optional to be the parent of the generated hierarchy (if
     *        <em>attachToRoot</em> is true), or else simply an object that
     *        provides a set of values for root of the returned
     *        hierarchy (if <em>attachToRoot</em> is false.)
     * @param attachToRoot Whether the inflated hierarchy should be attached to
     *        the root parameter?
     * @return The root of the inflated hierarchy. If root was supplied and
     *         attachToRoot is true, this is root; otherwise it is the root of
     *         the inflated XML file.
     */
    public T inflate(XmlPullParser parser, P root,
            boolean attachToRoot) {
        synchronized (mConstructorArgs) {
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            mConstructorArgs[0] = mContext;
            T result = (T) root;

            try {
                // Look for the root node.
                int type;
                while ((type = parser.next()) != parser.START_TAG
                        && type != parser.END_DOCUMENT) {
                    ;
                }

                if (type != parser.START_TAG) {
                    throw new InflateException(parser.getPositionDescription()
                            + ": No start tag found!");
                }

                if (DEBUG) {
                    System.out.println("**************************");
                    System.out.println("Creating root: "
                            + parser.getName());
                    System.out.println("**************************");
                }
                // Temp is the root that was found in the xml
                T xmlRoot = createItemFromTag(parser, parser.getName(),
                        attrs);

                result = (T) onMergeRoots(root, attachToRoot, (P) xmlRoot);
                
                if (DEBUG) {
                    System.out.println("-----> start inflating children");
                }
                // Inflate all children under temp
                rInflate(parser, result, attrs);
                if (DEBUG) {
                    System.out.println("-----> done inflating children");
                }

            } catch (InflateException e) {
                throw e;

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
            }

            return result;
        }
    }

    /**
     * Low-level function for instantiating by name. This attempts to
     * instantiate class of the given <var>name</var> found in this
     * inflater's ClassLoader.
     * 
     * <p>
     * There are two things that can happen in an error case: either the
     * exception describing the error will be thrown, or a null will be
     * returned. You must deal with both possibilities -- the former will happen
     * the first time createItem() is called for a class of a particular name,
     * the latter every time there-after for that class name.
     * 
     * @param name The full name of the class to be instantiated.
     * @param attrs The XML attributes supplied for this instance.
     * 
     * @return The newly instantied item, or null.
     */
    public final T createItem(String name, String prefix, AttributeSet attrs)
            throws ClassNotFoundException, InflateException {
        Constructor constructor = (Constructor) sConstructorMap.get(name);

        try {
            if (null == constructor) {
                // Class not found in the cache, see if it's real,
                // and try to add it
                Class clazz = mContext.getClassLoader().loadClass(
                        prefix != null ? (prefix + name) : name);
                constructor = clazz.getConstructor(mConstructorSignature);
                constructor.setAccessible(true);
                sConstructorMap.put(name, constructor);
            }

            Object[] args = mConstructorArgs;
            args[1] = attrs;
            return (T) constructor.newInstance(args);

        } catch (NoSuchMethodException e) {
            InflateException ie = new InflateException(attrs
                    .getPositionDescription()
                    + ": Error inflating class "
                    + (prefix != null ? (prefix + name) : name));
            ie.initCause(e);
            throw ie;

        } catch (ClassNotFoundException e) {
            // If loadClass fails, we should propagate the exception.
            throw e;
        } catch (Exception e) {
            InflateException ie = new InflateException(attrs
                    .getPositionDescription()
                    + ": Error inflating class "
                    + constructor.getClass().getName());
            ie.initCause(e);
            throw ie;
        }
    }

    /**
     * This routine is responsible for creating the correct subclass of item
     * given the xml element name. Override it to handle custom item objects. If
     * you override this in your subclass be sure to call through to
     * super.onCreateItem(name) for names you do not recognize.
     * 
     * @param name The fully qualified class name of the item to be create.
     * @param attrs An AttributeSet of attributes to apply to the item.
     * @return The item created.
     */
    protected T onCreateItem(String name, AttributeSet attrs) throws ClassNotFoundException {
        return createItem(name, mDefaultPackage, attrs);
    }

    private final T createItemFromTag(XmlPullParser parser, String name, AttributeSet attrs) {
        if (DEBUG) System.out.println("******** Creating item: " + name);

        try {
            T item = (mFactory == null) ? null : mFactory.onCreateItem(name, mContext, attrs);

            if (item == null) {
                if (-1 == name.indexOf('.')) {
                    item = onCreateItem(name, attrs);
                } else {
                    item = createItem(name, null, attrs);
                }
            }

            if (DEBUG) System.out.println("Created item is: " + item);
            return item;

        } catch (InflateException e) {
            throw e;

        } catch (ClassNotFoundException e) {
            InflateException ie = new InflateException(attrs
                    .getPositionDescription()
                    + ": Error inflating class " + name);
            ie.initCause(e);
            throw ie;

        } catch (Exception e) {
            InflateException ie = new InflateException(attrs
                    .getPositionDescription()
                    + ": Error inflating class " + name);
            ie.initCause(e);
            throw ie;
        }
    }

    /**
     * Recursive method used to descend down the xml hierarchy and instantiate
     * items, instantiate their children, and then call onFinishInflate().
     */
    private void rInflate(XmlPullParser parser, T parent, final AttributeSet attrs)
            throws XmlPullParserException, IOException {
        final int depth = parser.getDepth();

        int type;
        while (((type = parser.next()) != parser.END_TAG || 
                parser.getDepth() > depth) && type != parser.END_DOCUMENT) {

            if (type != parser.START_TAG) {
                continue;
            }

            if (onCreateCustomFromTag(parser, parent, attrs)) {
                continue;
            }

            if (DEBUG) {
                System.out.println("Now inflating tag: " + parser.getName());
            }
            String name = parser.getName();

            T item = createItemFromTag(parser, name, attrs);

            if (DEBUG) {
                System.out
                        .println("Creating params from parent: " + parent);
            }

            ((P) parent).addItemFromInflater(item);

            if (DEBUG) {
                System.out.println("-----> start inflating children");
            }
            rInflate(parser, item, attrs);
            if (DEBUG) {
                System.out.println("-----> done inflating children");
            }
        }

    }
    
    /**
     * Before this inflater tries to create an item from the tag, this method
     * will be called. The parser will be pointing to the start of a tag, you
     * must stop parsing and return when you reach the end of this element!
     * 
     * @param parser XML dom node containing the description of the hierarchy.
     * @param parent The item that should be the parent of whatever you create.
     * @param attrs An AttributeSet of attributes to apply to the item.
     * @return Whether you created a custom object (true), or whether this
     *         inflater should proceed to create an item.
     */
    protected boolean onCreateCustomFromTag(XmlPullParser parser, T parent,
            final AttributeSet attrs) throws XmlPullParserException {
        return false;
    }
    
    protected P onMergeRoots(P givenRoot, boolean attachToGivenRoot, P xmlRoot) {
        return xmlRoot;
    }
}
