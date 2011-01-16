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

import com.android.ide.common.rendering.api.IProjectCallback;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.ide.common.resources.ResourceResolver;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.BridgeConstants;
import com.android.layoutlib.bridge.impl.Stack;

import android.app.Activity;
import android.app.Fragment;
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
import android.database.DatabaseErrorHandler;
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
import android.view.LayoutInflater;
import android.view.View;

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

    private Resources mResources;
    private Theme mTheme;
    private final HashMap<View, Object> mViewKeyMap = new HashMap<View, Object>();
    private final Object mProjectKey;
    private final DisplayMetrics mMetrics;
    private final ResourceResolver mResourceResolver;

    private final Map<Object, Map<String, String>> mDefaultPropMaps =
        new IdentityHashMap<Object, Map<String,String>>();

    // maps for dynamically generated id representing style objects (IStyleResourceValue)
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
     */
    public BridgeContext(Object projectKey, DisplayMetrics metrics,
            ResourceResolver resourceResolver,
            IProjectCallback projectCallback) {
        mProjectKey = projectKey;
        mMetrics = metrics;
        mProjectCallback = projectCallback;

        mResourceResolver = resourceResolver;

        mFragments.mCurState = Fragment.CREATED;
        mFragments.mActivity = this;
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

        mResources = BridgeResources.initSystem(
                this,
                assetManager,
                mMetrics,
                config,
                mProjectCallback);
        mTheme = mResources.newTheme();
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

    public IProjectCallback getProjectCallback() {
        return mProjectCallback;
    }

    public ResourceResolver getResolver() {
        return mResourceResolver;
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

    // ------------- Activity Methods

    @Override
    public LayoutInflater getLayoutInflater() {
        return mBridgeInflater;
    }

    // ------------ Context methods

    @Override
    public Resources getResources() {
        return mResources;
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
        return createStyleBasedTypedArray(mResourceResolver.getTheme(), attrs);
    }

    @Override
    public final TypedArray obtainStyledAttributes(int resid, int[] attrs)
            throws Resources.NotFoundException {
        // get the IStyleResourceValue based on the resId;
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

        BridgeTypedArray ta = ((BridgeResources) mResources).newTypeArray(attrs.length,
                isPlatformFile);

        // resolve the defStyleAttr value into a IStyleResourceValue
        StyleResourceValue defStyleValues = null;

        // look for a custom style.
        String customStyle = null;
        if (set != null) {
            customStyle = set.getAttributeValue(null /* namespace*/, "style");
        }
        if (customStyle != null) {
            ResourceValue item = mResourceResolver.findResValue(customStyle,
                    false /*forceFrameworkOnly*/);

            if (item instanceof StyleResourceValue) {
                defStyleValues = (StyleResourceValue)item;
            }
        }

        if (defStyleValues == null && defStyleAttr != 0) {
            // get the name from the int.
            String defStyleName = searchAttr(defStyleAttr);

            if (defaultPropMap != null) {
                defaultPropMap.put("style", defStyleName);
            }

            // look for the style in the current theme, and its parent:
            ResourceValue item = mResourceResolver.findItemInTheme(defStyleName);

            if (item != null) {
                // item is a reference to a style entry. Search for it.
                item = mResourceResolver.findResValue(item.getValue(),
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
        }

        if (defStyleRes != 0) {
            // FIXME: See what we need to do with this.
            throw new UnsupportedOperationException();
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

                    // look for the value in the defStyle first (and its parent if needed)
                    if (defStyleValues != null) {
                        resValue = mResourceResolver.findItemInStyle(defStyleValues, name);
                    }

                    // if the item is not present in the defStyle, we look in the main theme (and
                    // its parent themes)
                    if (resValue == null) {
                        resValue = mResourceResolver.findItemInTheme(name);
                    }

                    // if we found a value, we make sure this doesn't reference another value.
                    // So we resolve it.
                    if (resValue != null) {
                        // put the first default value, before the resolution.
                        if (defaultPropMap != null) {
                            defaultPropMap.put(name, resValue.getValue());
                        }

                        resValue = mResourceResolver.resolveResValue(resValue);
                    }

                    ta.bridgeSetValue(index, name, resValue);
                } else {
                    // there is a value in the XML, but we need to resolve it in case it's
                    // referencing another resource or a theme value.
                    ta.bridgeSetValue(index, name,
                            mResourceResolver.resolveValue(null, name, value, isPlatformFile));
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

        BridgeTypedArray ta = ((BridgeResources) mResources).newTypeArray(attrs.length,
                false /* platformResourceFlag */);

        // loop through all the values in the style map, and init the TypedArray with
        // the style we got from the dynamic id
        for (Entry<Integer, String> styleAttribute : styleNameMap.entrySet()) {
            int index = styleAttribute.getKey().intValue();

            String name = styleAttribute.getValue();

            // get the value from the style, or its parent styles.
            ResourceValue resValue = mResourceResolver.findItemInStyle(style, name);

            // resolve it to make sure there are no references left.
            ta.bridgeSetValue(index, name, mResourceResolver.resolveResValue(resValue));
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
        String arrayName = Bridge.resolveResourceValue(attrs);
        if (arrayName != null) {
            // if we found it, get the name of each of the int in the array.
            TreeMap<Integer,String> attributes = new TreeMap<Integer, String>();
            for (int i = 0 ; i < attrs.length ; i++) {
                String[] info = Bridge.resolveResourceValue(attrs[i]);
                if (info != null) {
                    attributes.put(i, info[0]);
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
        arrayName = mProjectCallback.resolveResourceValue(attrs);
        if (arrayName != null) {
            TreeMap<Integer,String> attributes = new TreeMap<Integer, String>();
            for (int i = 0 ; i < attrs.length ; i++) {
                String[] info = mProjectCallback.resolveResourceValue(attrs[i]);
                if (info != null) {
                    attributes.put(i, info[0]);
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
        String[] info = Bridge.resolveResourceValue(attr);
        if (info != null) {
            return info[0];
        }

        info = mProjectCallback.resolveResourceValue(attr);
        if (info != null) {
            return info[0];
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

    int getFrameworkResourceValue(String resType, String resName, int defValue) {
        Integer value = Bridge.getResourceValue(resType, resName);
        if (value != null) {
            return value.intValue();
        }

        return defValue;
    }

    int getProjectResourceValue(String resType, String resName, int defValue) {
        if (mProjectCallback != null) {
            Integer value = mProjectCallback.getResourceValue(resType, resName);
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
        return new ApplicationInfo();
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
    public SQLiteDatabase openOrCreateDatabase(String arg0, int arg1,
            CursorFactory arg2, DatabaseErrorHandler arg3) {
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
    public void startActivities(Intent[] arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isRestricted() {
        return false;
    }
}
