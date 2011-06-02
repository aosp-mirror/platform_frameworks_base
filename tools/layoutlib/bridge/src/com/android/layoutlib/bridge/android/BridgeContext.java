/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.ide.common.rendering.api.IProjectCallback;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.BridgeConstants;
import com.android.layoutlib.bridge.impl.Stack;
import com.android.resources.ResourceType;
import com.android.util.Pair;

import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;

/**
 * Custom implementation of Context/Activity to handle non compiled resources.
 */
public final class BridgeContext extends Activity {

    private Resources mSystemResources;
    private final HashMap<View, Object> mViewKeyMap = new HashMap<View, Object>();
    private final Object mProjectKey;
    private final DisplayMetrics mMetrics;
    private final RenderResources mRenderResources;
    private final ApplicationInfo mApplicationInfo;

    private final Map<Object, Map<String, String>> mDefaultPropMaps =
        new IdentityHashMap<Object, Map<String,String>>();

    // maps for dynamically generated id representing style objects (StyleResourceValue)
    private Map<Integer, StyleResourceValue> mDynamicIdToStyleMap;
    private Map<StyleResourceValue, Integer> mStyleToDynamicIdMap;
    private int mDynamicIdGenerator = 0x01030000; // Base id for framework R.style

    // cache for TypedArray generated from IStyleResourceValue object
    private Map<int[], Map<Integer, TypedArray>> mTypedArrayCache;
    private BridgeInflater mBridgeInflater;

    private final IProjectCallback mProjectCallback;
    private BridgeContentResolver mContentResolver;

    private final Stack<BridgeXmlBlockParser> mParserStack = new Stack<BridgeXmlBlockParser>();

    /**
     * @param projectKey An Object identifying the project. This is used for the cache mechanism.
     * @param metrics the {@link DisplayMetrics}.
     * @param themeName The name of the theme to use.
     * @param projectResources the resources of the project. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the
     * map contains (String, {@link }) pairs where the key is the resource name,
     * and the value is the resource value.
     * @param frameworkResources the framework resources. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the map
     * contains (String, {@link ResourceValue}) pairs where the key is the resource name, and the
     * value is the resource value.
     * @param styleInheritanceMap
     * @param projectCallback
     * @param targetSdkVersion the targetSdkVersion of the application.
     */
    public BridgeContext(Object projectKey, DisplayMetrics metrics,
            RenderResources renderResources,
            IProjectCallback projectCallback,
            int targetSdkVersion) {
        mProjectKey = projectKey;
        mMetrics = metrics;
        mProjectCallback = projectCallback;

        mRenderResources = renderResources;

        mApplicationInfo = new ApplicationInfo();
        mApplicationInfo.targetSdkVersion = targetSdkVersion;
    }

    /**
     * Initializes the {@link Resources} singleton to be linked to this {@link Context}, its
     * {@link DisplayMetrics}, {@link Configuration}, and {@link IProjectCallback}.
     *
     * @see #disposeResources()
     */
    public void initResources() {
        AssetManager assetManager = AssetManager.getSystem();
        Configuration config = new Configuration();

        mSystemResources = BridgeResources.initSystem(
                this,
                assetManager,
                mMetrics,
                config,
                mProjectCallback);
        mTheme = mSystemResources.newTheme();
    }

    /**
     * Disposes the {@link Resources} singleton.
     */
    public void disposeResources() {
        BridgeResources.disposeSystem();
    }

    public void setBridgeInflater(BridgeInflater inflater) {
        mBridgeInflater = inflater;
    }

    public void addViewKey(View view, Object viewKey) {
        mViewKeyMap.put(view, viewKey);
    }

    public Object getViewKey(View view) {
        return mViewKeyMap.get(view);
    }

    public Object getProjectKey() {
        return mProjectKey;
    }

    public DisplayMetrics getMetrics() {
        return mMetrics;
    }

    public IProjectCallback getProjectCallback() {
        return mProjectCallback;
    }

    public RenderResources getRenderResources() {
        return mRenderResources;
    }

    public Map<String, String> getDefaultPropMap(Object key) {
        return mDefaultPropMaps.get(key);
    }

    /**
     * Adds a parser to the stack.
     * @param parser the parser to add.
     */
    public void pushParser(BridgeXmlBlockParser parser) {
        mParserStack.push(parser);
    }

    /**
     * Removes the parser at the top of the stack
     */
    public void popParser() {
        mParserStack.pop();
    }

    /**
     * Returns the current parser at the top the of the stack.
     * @return a parser or null.
     */
    public BridgeXmlBlockParser getCurrentParser() {
        return mParserStack.peek();
    }

    /**
     * Returns the previous parser.
     * @return a parser or null if there isn't any previous parser
     */
    public BridgeXmlBlockParser getPreviousParser() {
        if (mParserStack.size() < 2) {
            return null;
        }
        return mParserStack.get(mParserStack.size() - 2);
    }

    public boolean resolveThemeAttribute(int resid, TypedValue outValue, boolean resolveRefs) {
        Pair<ResourceType, String> resourceInfo = Bridge.resolveResourceId(resid);
        if (resourceInfo == null) {
            resourceInfo = mProjectCallback.resolveResourceId(resid);
        }

        if (resourceInfo == null) {
            return false;
        }

        ResourceValue value = mRenderResources.findItemInTheme(resourceInfo.getSecond());
        if (resolveRefs) {
            value = mRenderResources.resolveResValue(value);
        }

        // check if this is a style resource
        if (value instanceof StyleResourceValue) {
            // get the id that will represent this style.
            outValue.resourceId = getDynamicIdByStyle((StyleResourceValue)value);
            return true;
        }


        int a;
        // if this is a framework value.
        if (value.isFramework()) {
            // look for idName in the android R classes.
            // use 0 a default res value as it's not a valid id value.
            a = getFrameworkResourceValue(value.getResourceType(), value.getName(), 0 /*defValue*/);
        } else {
            // look for idName in the project R class.
            // use 0 a default res value as it's not a valid id value.
            a = getProjectResourceValue(value.getResourceType(), value.getName(), 0 /*defValue*/);
        }

        if (a != 0) {
            outValue.resourceId = a;
            return true;
        }

        return false;
    }


    public ResourceReference resolveId(int id) {
        // first get the String related to this id in the framework
        Pair<ResourceType, String> resourceInfo = Bridge.resolveResourceId(id);

        if (resourceInfo != null) {
            return new ResourceReference(resourceInfo.getSecond(), true);
        }

        // didn't find a match in the framework? look in the project.
        if (mProjectCallback != null) {
            resourceInfo = mProjectCallback.resolveResourceId(id);

            if (resourceInfo != null) {
                return new ResourceReference(resourceInfo.getSecond(), false);
            }
        }

        return null;
    }

    public Pair<View, Boolean> inflateView(ResourceReference resource, ViewGroup parent,
            boolean attachToRoot, boolean skipCallbackParser) {
        String layoutName = resource.getName();
        boolean isPlatformLayout = resource.isFramework();

        if (isPlatformLayout == false && skipCallbackParser == false) {
            // check if the project callback can provide us with a custom parser.
            ILayoutPullParser parser = mProjectCallback.getParser(layoutName);
            if (parser != null) {
                BridgeXmlBlockParser blockParser = new BridgeXmlBlockParser(parser,
                        this, resource.isFramework());
                try {
                    pushParser(blockParser);
                    return Pair.of(
                            mBridgeInflater.inflate(blockParser, parent, attachToRoot),
                            true);
                } finally {
                    popParser();
                }
            }
        }

        ResourceValue resValue;
        if (resource instanceof ResourceValue) {
            resValue = (ResourceValue) resource;
        } else {
            if (isPlatformLayout) {
                resValue = mRenderResources.getFrameworkResource(ResourceType.LAYOUT,
                        resource.getName());
            } else {
                resValue = mRenderResources.getProjectResource(ResourceType.LAYOUT,
                        resource.getName());
            }
        }

        if (resValue != null) {

            File xml = new File(resValue.getValue());
            if (xml.isFile()) {
                // we need to create a pull parser around the layout XML file, and then
                // give that to our XmlBlockParser
                try {
                    KXmlParser parser = new KXmlParser();
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                    parser.setInput(new FileInputStream(xml), "UTF-8"); //$NON-NLS-1$);

                    // set the resource ref to have correct view cookies
                    mBridgeInflater.setResourceReference(resource);

                    BridgeXmlBlockParser blockParser = new BridgeXmlBlockParser(parser,
                            this, resource.isFramework());
                    try {
                        pushParser(blockParser);
                        return Pair.of(
                                mBridgeInflater.inflate(blockParser, parent, attachToRoot),
                                false);
                    } finally {
                        popParser();
                    }
                } catch (XmlPullParserException e) {
                    Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                            "Failed to configure parser for " + xml, e, null /*data*/);
                    // we'll return null below.
                } catch (FileNotFoundException e) {
                    // this shouldn't happen since we check above.
                } finally {
                    mBridgeInflater.setResourceReference(null);
                }
            } else {
                Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                        String.format("File %s is missing!", xml), null);
            }
        } else {
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    String.format("Layout %s%s does not exist.", isPlatformLayout ? "android:" : "",
                            layoutName), null);
        }

        return Pair.of(null, false);
    }

    // ------------- Activity Methods

    @Override
    public LayoutInflater getLayoutInflater() {
        return mBridgeInflater;
    }

    // ------------ Context methods

    @Override
    public Resources getResources() {
        return mSystemResources;
    }

    @Override
    public Theme getTheme() {
        return mTheme;
    }

    @Override
    public ClassLoader getClassLoader() {
        return this.getClass().getClassLoader();
    }

    @Override
    public Object getSystemService(String service) {
        if (LAYOUT_INFLATER_SERVICE.equals(service)) {
            return mBridgeInflater;
        }

        // AutoCompleteTextView and MultiAutoCompleteTextView want a window
        // service. We don't have any but it's not worth an exception.
        if (WINDOW_SERVICE.equals(service)) {
            return null;
        }

        // needed by SearchView
        if (INPUT_METHOD_SERVICE.equals(service)) {
            return null;
        }

        throw new UnsupportedOperationException("Unsupported Service: " + service);
    }


    @Override
    public final TypedArray obtainStyledAttributes(int[] attrs) {
        return createStyleBasedTypedArray(mRenderResources.getCurrentTheme(), attrs);
    }

    @Override
    public final TypedArray obtainStyledAttributes(int resid, int[] attrs)
            throws Resources.NotFoundException {
        // get the StyleResourceValue based on the resId;
        StyleResourceValue style = getStyleByDynamicId(resid);

        if (style == null) {
            throw new Resources.NotFoundException();
        }

        if (mTypedArrayCache == null) {
            mTypedArrayCache = new HashMap<int[], Map<Integer,TypedArray>>();

            Map<Integer, TypedArray> map = new HashMap<Integer, TypedArray>();
            mTypedArrayCache.put(attrs, map);

            BridgeTypedArray ta = createStyleBasedTypedArray(style, attrs);
            map.put(resid, ta);

            return ta;
        }

        // get the 2nd map
        Map<Integer, TypedArray> map = mTypedArrayCache.get(attrs);
        if (map == null) {
            map = new HashMap<Integer, TypedArray>();
            mTypedArrayCache.put(attrs, map);
        }

        // get the array from the 2nd map
        TypedArray ta = map.get(resid);

        if (ta == null) {
            ta = createStyleBasedTypedArray(style, attrs);
            map.put(resid, ta);
        }

        return ta;
    }

    @Override
    public final TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs) {
        return obtainStyledAttributes(set, attrs, 0, 0);
    }

    @Override
    public TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs,
            int defStyleAttr, int defStyleRes) {

        Map<String, String> defaultPropMap = null;
        boolean isPlatformFile = true;

        // Hint: for XmlPullParser, attach source //DEVICE_SRC/dalvik/libcore/xml/src/java
        if (set instanceof BridgeXmlBlockParser) {
            BridgeXmlBlockParser parser = null;
            parser = (BridgeXmlBlockParser)set;

            isPlatformFile = parser.isPlatformFile();

            Object key = parser.getViewCookie();
            if (key != null) {
                defaultPropMap = mDefaultPropMaps.get(key);
                if (defaultPropMap == null) {
                    defaultPropMap = new HashMap<String, String>();
                    mDefaultPropMaps.put(key, defaultPropMap);
                }
            }

        } else if (set instanceof BridgeLayoutParamsMapAttributes) {
            // this is only for temp layout params generated dynamically, so this is never
            // platform content.
            isPlatformFile = false;
        } else if (set != null) { // null parser is ok
            // really this should not be happening since its instantiated in Bridge
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    "Parser is not a BridgeXmlBlockParser!", null /*data*/);
            return null;
        }

        boolean[] frameworkAttributes = new boolean[1];
        TreeMap<Integer, String> styleNameMap = searchAttrs(attrs, frameworkAttributes);

        BridgeTypedArray ta = ((BridgeResources) mSystemResources).newTypeArray(attrs.length,
                isPlatformFile);

        // look for a custom style.
        String customStyle = null;
        if (set != null) {
            customStyle = set.getAttributeValue(null /* namespace*/, "style");
        }

        StyleResourceValue customStyleValues = null;
        if (customStyle != null) {
            ResourceValue item = mRenderResources.findResValue(customStyle,
                    false /*forceFrameworkOnly*/);

            // resolve it in case it links to something else
            item = mRenderResources.resolveResValue(item);

            if (item instanceof StyleResourceValue) {
                customStyleValues = (StyleResourceValue)item;
            }
        }

        // resolve the defStyleAttr value into a IStyleResourceValue
        StyleResourceValue defStyleValues = null;

        if (defStyleAttr != 0) {
            // get the name from the int.
            String defStyleName = searchAttr(defStyleAttr);

            if (defaultPropMap != null) {
                defaultPropMap.put("style", defStyleName);
            }

            // look for the style in the current theme, and its parent:
            ResourceValue item = mRenderResources.findItemInTheme(defStyleName);

            if (item != null) {
                // item is a reference to a style entry. Search for it.
                item = mRenderResources.findResValue(item.getValue(),
                        false /*forceFrameworkOnly*/);

                if (item instanceof StyleResourceValue) {
                    defStyleValues = (StyleResourceValue)item;
                }
            } else {
                Bridge.getLog().error(null,
                        String.format(
                                "Failed to find style '%s' in current theme", defStyleName),
                        null /*data*/);
            }
        } else if (defStyleRes != 0) {
            Pair<ResourceType, String> value = Bridge.resolveResourceId(defStyleRes);
            if (value == null) {
                value = mProjectCallback.resolveResourceId(defStyleRes);
            }

            if (value != null) {
                if (value.getFirst() == ResourceType.STYLE) {
                    // look for the style in the current theme, and its parent:
                    ResourceValue item = mRenderResources.findItemInTheme(value.getSecond());
                    if (item != null) {
                        if (item instanceof StyleResourceValue) {
                            if (defaultPropMap != null) {
                                defaultPropMap.put("style", item.getName());
                            }

                            defStyleValues = (StyleResourceValue)item;
                        }
                    } else {
                        Bridge.getLog().error(null,
                                String.format(
                                        "Style with id 0x%x (resolved to '%s') does not exist.",
                                        defStyleRes, value.getSecond()),
                                null /*data*/);
                    }
                } else {
                    Bridge.getLog().error(null,
                            String.format(
                                    "Resouce id 0x%x is not of type STYLE (instead %s)",
                                    defStyleRes, value.getFirst().toString()),
                            null /*data*/);
                }
            } else {
                Bridge.getLog().error(null,
                        String.format(
                                "Failed to find style with id 0x%x in current theme",
                                defStyleRes),
                        null /*data*/);
            }
        }

        String namespace = BridgeConstants.NS_RESOURCES;
        if (frameworkAttributes[0] == false) {
            // need to use the application namespace
            namespace = mProjectCallback.getNamespace();
        }

        if (styleNameMap != null) {
            for (Entry<Integer, String> styleAttribute : styleNameMap.entrySet()) {
                int index = styleAttribute.getKey().intValue();

                String name = styleAttribute.getValue();
                String value = null;
                if (set != null) {
                    value = set.getAttributeValue(namespace, name);
                }

                // if there's no direct value for this attribute in the XML, we look for default
                // values in the widget defStyle, and then in the theme.
                if (value == null) {
                    ResourceValue resValue = null;

                    // look for the value in the custom style first (and its parent if needed)
                    if (customStyleValues != null) {
                        resValue = mRenderResources.findItemInStyle(customStyleValues, name);
                    }

                    // then look for the value in the default Style (and its parent if needed)
                    if (resValue == null && defStyleValues != null) {
                        resValue = mRenderResources.findItemInStyle(defStyleValues, name);
                    }

                    // if the item is not present in the defStyle, we look in the main theme (and
                    // its parent themes)
                    if (resValue == null) {
                        resValue = mRenderResources.findItemInTheme(name);
                    }

                    // if we found a value, we make sure this doesn't reference another value.
                    // So we resolve it.
                    if (resValue != null) {
                        // put the first default value, before the resolution.
                        if (defaultPropMap != null) {
                            defaultPropMap.put(name, resValue.getValue());
                        }

                        resValue = mRenderResources.resolveResValue(resValue);
                    }

                    ta.bridgeSetValue(index, name, resValue);
                } else {
                    // there is a value in the XML, but we need to resolve it in case it's
                    // referencing another resource or a theme value.
                    ta.bridgeSetValue(index, name,
                            mRenderResources.resolveValue(null, name, value, isPlatformFile));
                }
            }
        }

        ta.sealArray();

        return ta;
    }

    @Override
    public Looper getMainLooper() {
        return Looper.myLooper();
    }


    // ------------- private new methods

    /**
     * Creates a {@link BridgeTypedArray} by filling the values defined by the int[] with the
     * values found in the given style.
     * @see #obtainStyledAttributes(int, int[])
     */
    private BridgeTypedArray createStyleBasedTypedArray(StyleResourceValue style, int[] attrs)
            throws Resources.NotFoundException {
        TreeMap<Integer, String> styleNameMap = searchAttrs(attrs, null);

        BridgeTypedArray ta = ((BridgeResources) mSystemResources).newTypeArray(attrs.length,
                false /* platformResourceFlag */);

        // loop through all the values in the style map, and init the TypedArray with
        // the style we got from the dynamic id
        for (Entry<Integer, String> styleAttribute : styleNameMap.entrySet()) {
            int index = styleAttribute.getKey().intValue();

            String name = styleAttribute.getValue();

            // get the value from the style, or its parent styles.
            ResourceValue resValue = mRenderResources.findItemInStyle(style, name);

            // resolve it to make sure there are no references left.
            ta.bridgeSetValue(index, name, mRenderResources.resolveResValue(resValue));
        }

        ta.sealArray();

        return ta;
    }


    /**
     * The input int[] attrs is one of com.android.internal.R.styleable fields where the name
     * of the field is the style being referenced and the array contains one index per attribute.
     * <p/>
     * searchAttrs() finds all the names of the attributes referenced so for example if
     * attrs == com.android.internal.R.styleable.View, this returns the list of the "xyz" where
     * there's a field com.android.internal.R.styleable.View_xyz and the field value is the index
     * that is used to reference the attribute later in the TypedArray.
     *
     * @param attrs An attribute array reference given to obtainStyledAttributes.
     * @return A sorted map Attribute-Value to Attribute-Name for all attributes declared by the
     *         attribute array. Returns null if nothing is found.
     */
    private TreeMap<Integer,String> searchAttrs(int[] attrs, boolean[] outFrameworkFlag) {
        // get the name of the array from the framework resources
        String arrayName = Bridge.resolveResourceId(attrs);
        if (arrayName != null) {
            // if we found it, get the name of each of the int in the array.
            TreeMap<Integer,String> attributes = new TreeMap<Integer, String>();
            for (int i = 0 ; i < attrs.length ; i++) {
                Pair<ResourceType, String> info = Bridge.resolveResourceId(attrs[i]);
                if (info != null) {
                    attributes.put(i, info.getSecond());
                } else {
                    // FIXME Not sure what we should be doing here...
                    attributes.put(i, null);
                }
            }

            if (outFrameworkFlag != null) {
                outFrameworkFlag[0] = true;
            }

            return attributes;
        }

        // if the name was not found in the framework resources, look in the project
        // resources
        arrayName = mProjectCallback.resolveResourceId(attrs);
        if (arrayName != null) {
            TreeMap<Integer,String> attributes = new TreeMap<Integer, String>();
            for (int i = 0 ; i < attrs.length ; i++) {
                Pair<ResourceType, String> info = mProjectCallback.resolveResourceId(attrs[i]);
                if (info != null) {
                    attributes.put(i, info.getSecond());
                } else {
                    // FIXME Not sure what we should be doing here...
                    attributes.put(i, null);
                }
            }

            if (outFrameworkFlag != null) {
                outFrameworkFlag[0] = false;
            }

            return attributes;
        }

        return null;
    }

    /**
     * Searches for the attribute referenced by its internal id.
     *
     * @param attr An attribute reference given to obtainStyledAttributes such as defStyle.
     * @return The unique name of the attribute, if found, e.g. "buttonStyle". Returns null
     *         if nothing is found.
     */
    public String searchAttr(int attr) {
        Pair<ResourceType, String> info = Bridge.resolveResourceId(attr);
        if (info != null) {
            return info.getSecond();
        }

        info = mProjectCallback.resolveResourceId(attr);
        if (info != null) {
            return info.getSecond();
        }

        return null;
    }

    int getDynamicIdByStyle(StyleResourceValue resValue) {
        if (mDynamicIdToStyleMap == null) {
            // create the maps.
            mDynamicIdToStyleMap = new HashMap<Integer, StyleResourceValue>();
            mStyleToDynamicIdMap = new HashMap<StyleResourceValue, Integer>();
        }

        // look for an existing id
        Integer id = mStyleToDynamicIdMap.get(resValue);

        if (id == null) {
            // generate a new id
            id = Integer.valueOf(++mDynamicIdGenerator);

            // and add it to the maps.
            mDynamicIdToStyleMap.put(id, resValue);
            mStyleToDynamicIdMap.put(resValue, id);
        }

        return id;
    }

    private StyleResourceValue getStyleByDynamicId(int i) {
        if (mDynamicIdToStyleMap != null) {
            return mDynamicIdToStyleMap.get(i);
        }

        return null;
    }

    int getFrameworkResourceValue(ResourceType resType, String resName, int defValue) {
        Integer value = Bridge.getResourceId(resType, resName);
        if (value != null) {
            return value.intValue();
        }

        return defValue;
    }

    int getProjectResourceValue(ResourceType resType, String resName, int defValue) {
        if (mProjectCallback != null) {
            Integer value = mProjectCallback.getResourceId(resType, resName);
            if (value != null) {
                return value.intValue();
            }
        }

        return defValue;
    }

    //------------ NOT OVERRIDEN --------------------

    @Override
    public boolean bindService(Intent arg0, ServiceConnection arg1, int arg2) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public int checkCallingOrSelfPermission(String arg0) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri arg0, int arg1) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int checkCallingPermission(String arg0) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int checkCallingUriPermission(Uri arg0, int arg1) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int checkPermission(String arg0, int arg1, int arg2) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int checkUriPermission(Uri arg0, int arg1, int arg2, int arg3) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int checkUriPermission(Uri arg0, String arg1, String arg2, int arg3,
            int arg4, int arg5) {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void clearWallpaper() {
        // TODO Auto-generated method stub

    }

    @Override
    public Context createPackageContext(String arg0, int arg1) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String[] databaseList() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean deleteDatabase(String arg0) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean deleteFile(String arg0) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void enforceCallingOrSelfPermission(String arg0, String arg1) {
        // TODO Auto-generated method stub

    }

    @Override
    public void enforceCallingOrSelfUriPermission(Uri arg0, int arg1,
            String arg2) {
        // TODO Auto-generated method stub

    }

    @Override
    public void enforceCallingPermission(String arg0, String arg1) {
        // TODO Auto-generated method stub

    }

    @Override
    public void enforceCallingUriPermission(Uri arg0, int arg1, String arg2) {
        // TODO Auto-generated method stub

    }

    @Override
    public void enforcePermission(String arg0, int arg1, int arg2, String arg3) {
        // TODO Auto-generated method stub

    }

    @Override
    public void enforceUriPermission(Uri arg0, int arg1, int arg2, int arg3,
            String arg4) {
        // TODO Auto-generated method stub

    }

    @Override
    public void enforceUriPermission(Uri arg0, String arg1, String arg2,
            int arg3, int arg4, int arg5, String arg6) {
        // TODO Auto-generated method stub

    }

    @Override
    public String[] fileList() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public AssetManager getAssets() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public File getCacheDir() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public File getExternalCacheDir() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ContentResolver getContentResolver() {
        if (mContentResolver == null) {
            mContentResolver = new BridgeContentResolver(this);
        }
        return mContentResolver;
    }

    @Override
    public File getDatabasePath(String arg0) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public File getDir(String arg0, int arg1) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public File getFileStreamPath(String arg0) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public File getFilesDir() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public File getExternalFilesDir(String type) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getPackageCodePath() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public PackageManager getPackageManager() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getPackageName() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return mApplicationInfo;
    }

    @Override
    public String getPackageResourcePath() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public File getSharedPrefsFile(String name) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SharedPreferences getSharedPreferences(String arg0, int arg1) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Drawable getWallpaper() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public int getWallpaperDesiredMinimumWidth() {
        return -1;
    }

    @Override
    public int getWallpaperDesiredMinimumHeight() {
        return -1;
    }

    @Override
    public void grantUriPermission(String arg0, Uri arg1, int arg2) {
        // TODO Auto-generated method stub

    }

    @Override
    public FileInputStream openFileInput(String arg0) throws FileNotFoundException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public FileOutputStream openFileOutput(String arg0, int arg1) throws FileNotFoundException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String arg0, int arg1, CursorFactory arg2) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Drawable peekWallpaper() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver arg0, IntentFilter arg1) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver arg0, IntentFilter arg1,
            String arg2, Handler arg3) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void removeStickyBroadcast(Intent arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void revokeUriPermission(Uri arg0, int arg1) {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendBroadcast(Intent arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendBroadcast(Intent arg0, String arg1) {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendOrderedBroadcast(Intent arg0, String arg1) {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendOrderedBroadcast(Intent arg0, String arg1,
            BroadcastReceiver arg2, Handler arg3, int arg4, String arg5,
            Bundle arg6) {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendStickyBroadcast(Intent arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent,
            BroadcastReceiver resultReceiver, Handler scheduler, int initialCode, String initialData,
           Bundle initialExtras) {
        // TODO Auto-generated method stub
    }

    @Override
    public void setTheme(int arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void setWallpaper(Bitmap arg0) throws IOException {
        // TODO Auto-generated method stub

    }

    @Override
    public void setWallpaper(InputStream arg0) throws IOException {
        // TODO Auto-generated method stub

    }

    @Override
    public void startActivity(Intent arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void startIntentSender(IntentSender intent,
            Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags)
            throws IntentSender.SendIntentException {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean startInstrumentation(ComponentName arg0, String arg1,
            Bundle arg2) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public ComponentName startService(Intent arg0) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean stopService(Intent arg0) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void unbindService(ServiceConnection arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void unregisterReceiver(BroadcastReceiver arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public Context getApplicationContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRestricted() {
        return false;
    }
}
