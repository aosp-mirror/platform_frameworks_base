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

package com.android.layoutlib.bridge;

import com.android.layoutlib.api.ILayoutLog;
import com.android.layoutlib.api.IProjectCallback;
import com.android.layoutlib.api.IResourceValue;
import com.android.layoutlib.api.IStyleResourceValue;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
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
import android.view.BridgeInflater;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;

/**
 * Custom implementation of Context to handle non compiled resources.
 */
public final class BridgeContext extends Context {
    
    private Resources mResources;
    private Theme mTheme;
    private HashMap<View, Object> mViewKeyMap = new HashMap<View, Object>();
    private IStyleResourceValue mThemeValues;
    private final Object mProjectKey;
    private Map<String, Map<String, IResourceValue>> mProjectResources;
    private Map<String, Map<String, IResourceValue>> mFrameworkResources;
    private Map<IStyleResourceValue, IStyleResourceValue> mStyleInheritanceMap;
    
    // maps for dynamically generated id representing style objects (IStyleResourceValue)
    private Map<Integer, IStyleResourceValue> mDynamicIdToStyleMap;
    private Map<IStyleResourceValue, Integer> mStyleToDynamicIdMap;
    private int mDynamicIdGenerator = 0x01030000; // Base id for framework R.style
    
    // cache for TypedArray generated from IStyleResourceValue object
    private Map<int[], Map<Integer, TypedArray>> mTypedArrayCache;
    private BridgeInflater mInflater;

    private final IProjectCallback mProjectCallback;
    private final ILayoutLog mLogger;
    private BridgeContentResolver mContentResolver;

    /**
     * @param projectKey An Object identifying the project. This is used for the cache mechanism.
     * @param metrics the {@link DisplayMetrics}.
     * @param themeName The name of the theme to use.
     * @param projectResources the resources of the project. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the
     * map contains (String, {@link IResourceValue}) pairs where the key is the resource name,
     * and the value is the resource value.
     * @param frameworkResources the framework resources. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the map
     * contains (String, {@link IResourceValue}) pairs where the key is the resource name, and the
     * value is the resource value.
     * @param styleInheritanceMap
     * @param customViewLoader
     */
    public BridgeContext(Object projectKey, DisplayMetrics metrics,
            IStyleResourceValue currentTheme,
            Map<String, Map<String, IResourceValue>> projectResources,
            Map<String, Map<String, IResourceValue>> frameworkResources,
            Map<IStyleResourceValue, IStyleResourceValue> styleInheritanceMap,
            IProjectCallback customViewLoader, ILayoutLog logger) {
        mProjectKey = projectKey;
        mProjectCallback = customViewLoader;
        mLogger = logger;
        Configuration config = new Configuration();
        
        AssetManager assetManager = BridgeAssetManager.initSystem();
        mResources = BridgeResources.initSystem(
                this,
                assetManager,
                metrics,
                config,
                customViewLoader);
        
        mTheme = mResources.newTheme();
        
        mThemeValues = currentTheme;
        mProjectResources = projectResources;
        mFrameworkResources = frameworkResources;
        mStyleInheritanceMap = styleInheritanceMap;
    }
    
    public void setBridgeInflater(BridgeInflater inflater) {
        mInflater = inflater;
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
    
    public ILayoutLog getLogger() {
        return mLogger;
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
            return mInflater;
        }
        
        // AutoCompleteTextView and MultiAutoCompleteTextView want a window 
        // service. We don't have any but it's not worth an exception.
        if (WINDOW_SERVICE.equals(service)) {
            return null;
        }

        throw new UnsupportedOperationException("Unsupported Service: " + service);
    }


    @Override
    public final TypedArray obtainStyledAttributes(int[] attrs) {
        return createStyleBasedTypedArray(mThemeValues, attrs);
    }

    @Override
    public final TypedArray obtainStyledAttributes(int resid, int[] attrs)
            throws Resources.NotFoundException {
        // get the IStyleResourceValue based on the resId;
        IStyleResourceValue style = getStyleByDynamicId(resid);
        
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
        
        // Hint: for XmlPullParser, attach source //DEVICE_SRC/dalvik/libcore/xml/src/java
        BridgeXmlBlockParser parser = null;
        if (set instanceof BridgeXmlBlockParser) {
            parser = (BridgeXmlBlockParser)set;
        } else {
            // reall this should not be happening since its instantiated in Bridge
            mLogger.error("Parser is not a BridgeXmlBlockParser!");
            return null;
        }

        boolean[] frameworkAttributes = new boolean[1];
        TreeMap<Integer, String> styleNameMap = searchAttrs(attrs, frameworkAttributes);
        
        BridgeTypedArray ta = ((BridgeResources) mResources).newTypeArray(attrs.length,
                parser.isPlatformFile());
        
        // resolve the defStyleAttr value into a IStyleResourceValue
        IStyleResourceValue defStyleValues = null;
        if (defStyleAttr != 0) {
            // get the name from the int.
            String defStyleName = searchAttr(defStyleAttr);

            // look for the style in the current theme, and its parent:
            if (mThemeValues != null) {
                IResourceValue item = findItemInStyle(mThemeValues, defStyleName);
                
                if (item != null) {
                    // item is a reference to a style entry. Search for it.
                    item = findResValue(item.getValue());

                    if (item instanceof IStyleResourceValue) {
                        defStyleValues = (IStyleResourceValue)item;
                    }
                } else {
                    // TODO: log the error properly
                    System.out.println("Failed to find defStyle: " + defStyleName);
                }
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
                String value = parser.getAttributeValue(namespace, name);
                
                // if there's no direct value for this attribute in the XML, we look for default
                // values in the widget defStyle, and then in the theme.
                if (value == null) {
                    IResourceValue resValue = null;
    
                    // look for the value in the defStyle first (and its parent if needed)
                    if (defStyleValues != null) {
                        resValue = findItemInStyle(defStyleValues, name);
                    }
                    
                    // if the item is not present in the defStyle, we look in the main theme (and
                    // its parent themes)
                    if (resValue == null && mThemeValues != null) {
                        resValue = findItemInStyle(mThemeValues, name);
                    }
    
                    // if we found a value, we make sure this doesn't reference another value.
                    // So we resolve it.
                    if (resValue != null) {
                        resValue = resolveResValue(resValue);
                    }
                    
                    ta.bridgeSetValue(index, name, resValue);
                } else {
                    // there is a value in the XML, but we need to resolve it in case it's
                    // referencing another resource or a theme value.
                    ta.bridgeSetValue(index, name, resolveValue(null, name, value));
                }
            }
        }
        
        ta.sealArray();
        
        return ta;
    }
    
    
    // ------------- private new methods
    
    /**
     * Creates a {@link BridgeTypedArray} by filling the values defined by the int[] with the
     * values found in the given style.
     * @see #obtainStyledAttributes(int, int[])
     */
    private BridgeTypedArray createStyleBasedTypedArray(IStyleResourceValue style, int[] attrs)
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
            IResourceValue resValue = findItemInStyle(style, name);
            
            // resolve it to make sure there are no references left.
            ta.bridgeSetValue(index, name, resolveResValue(resValue));
        }
        
        ta.sealArray();

        return ta;
    }

    
    /**
     * Resolves the value of a resource, if the value references a theme or resource value.
     * <p/>
     * This method ensures that it returns a {@link IResourceValue} object that does not
     * reference another resource.
     * If the resource cannot be resolved, it returns <code>null</code>.
     * <p/>
     * If a value that does not need to be resolved is given, the method will return a new
     * instance of IResourceValue that contains the input value.
     *
     * @param type the type of the resource
     * @param name the name of the attribute containing this value.
     * @param value the resource value, or reference to resolve
     * @return the resolved resource value or <code>null</code> if it failed to resolve it.
     */
    private IResourceValue resolveValue(String type, String name, String value) {
        if (value == null) {
            return null;
        }

        // get the IResourceValue referenced by this value
        IResourceValue resValue = findResValue(value);
        
        // if resValue is null, but value is not null, this means it was not a reference.
        // we return the name/value wrapper in a IResourceValue
        if (resValue == null) {
            return new ResourceValue(type, name, value);
        }
        
        // we resolved a first reference, but we need to make sure this isn't a reference also.
        return resolveResValue(resValue);
    }

    /**
     * Returns the {@link IResourceValue} referenced by the value of <var>value</var>.
     * <p/>
     * This method ensures that it returns a {@link IResourceValue} object that does not
     * reference another resource.
     * If the resource cannot be resolved, it returns <code>null</code>.
     * <p/>
     * If a value that does not need to be resolved is given, the method will return the input
     * value.
     * 
     * @param value the value containing the reference to resolve.
     * @return a {@link IResourceValue} object or <code>null</code>
     */
    IResourceValue resolveResValue(IResourceValue value) {
        if (value == null) {
            return null;
        }
        
        // if the resource value is a style, we simply return it.
        if (value instanceof IStyleResourceValue) {
            return value;
        }

        // else attempt to find another IResourceValue referenced by this one.
        IResourceValue resolvedValue = findResValue(value.getValue());

        // if the value did not reference anything, then we simply return the input value
        if (resolvedValue == null) {
            return value;
        }

        // otherwise, we attempt to resolve this new value as well
        return resolveResValue(resolvedValue);
    }
    
    /**
     * Searches for, and returns a {@link IResourceValue} by its reference.
     * <p/>
     * The reference format can be:
     * <pre>@resType/resName</pre>
     * <pre>@android:resType/resName</pre>
     * <pre>@resType/android:resName</pre>
     * <pre>?resType/resName</pre>
     * <pre>?android:resType/resName</pre>
     * <pre>?resType/android:resName</pre>
     * Any other string format will return <code>null</code>.
     * <p/>
     * The actual format of a reference is <pre>@[namespace:]resType/resName</pre> but this method
     * only support the android namespace.
     * 
     * @param reference the resource reference to search for.
     * @return a {@link IResourceValue} or <code>null</code>.
     */
    IResourceValue findResValue(String reference) {
        if (reference == null) {
            return null;
        }
        if (reference.startsWith(BridgeConstants.PREFIX_THEME_REF)) {
            // no theme? no need to go further!
            if (mThemeValues == null) {
                return null;
            }

            boolean frameworkOnly = false;

            // eleminate the prefix from the string
            if (reference.startsWith(BridgeConstants.PREFIX_ANDROID_THEME_REF)) {
                frameworkOnly = true;
                reference = reference.substring(BridgeConstants.PREFIX_ANDROID_THEME_REF.length());
            } else {
                reference = reference.substring(BridgeConstants.PREFIX_THEME_REF.length());
            }

            // at this point, value can contain type/name (drawable/foo for instance).
            // split it to make sure.
            String[] segments = reference.split("\\/");

            // we look for the referenced item name.
            String referenceName = null;
            
            if (segments.length == 2) {
                // there was a resType in the reference. If it's attr, we ignore it
                // else, we assert for now.
                if (BridgeConstants.RES_ATTR.equals(segments[0])) {
                    referenceName = segments[1];
                } else {
                    // At this time, no support for ?type/name where type is not "attr"
                    return null;
                }
            } else {
                // it's just an item name.
                referenceName = segments[0];
            }
            
            // now we look for android: in the referenceName in order to support format
            // such as: ?attr/android:name
            if (referenceName.startsWith(BridgeConstants.PREFIX_ANDROID)) {
                frameworkOnly = true;
                referenceName = referenceName.substring(BridgeConstants.PREFIX_ANDROID.length());
            }

            // Now look for the item in the theme, starting with the current one.
            if (frameworkOnly) {
                // FIXME for now we do the same as if it didn't specify android:
                return findItemInStyle(mThemeValues, referenceName);
            }

            return findItemInStyle(mThemeValues, referenceName);
        } else if (reference.startsWith(BridgeConstants.PREFIX_RESOURCE_REF)) {
            boolean frameworkOnly = false;
            
            // check for the specific null reference value.
            if (BridgeConstants.REFERENCE_NULL.equals(reference)) { 
                return null;
            }

            // Eliminate the prefix from the string.
            if (reference.startsWith(BridgeConstants.PREFIX_ANDROID_RESOURCE_REF)) {
                frameworkOnly = true;
                reference = reference.substring(
                        BridgeConstants.PREFIX_ANDROID_RESOURCE_REF.length());
            } else {
                reference = reference.substring(BridgeConstants.PREFIX_RESOURCE_REF.length());
            }
            
            // at this point, value contains type/[android:]name (drawable/foo for instance)
            String[] segments = reference.split("\\/");
            
            // now we look for android: in the resource name in order to support format
            // such as: @drawable/android:name
            if (segments[1].startsWith(BridgeConstants.PREFIX_ANDROID)) {
                frameworkOnly = true;
                segments[1] = segments[1].substring(BridgeConstants.PREFIX_ANDROID.length());
            }
            
            return findResValue(segments[0], segments[1], frameworkOnly);
        }
        
        // Looks like the value didn't reference anything. Return null.
        return null;
    }

    /**
     * Searches for, and returns a {@link IResourceValue} by its name, and type.
     * @param resType the type of the resource
     * @param resName  the name of the resource
     * @param frameworkOnly if <code>true</code>, the method does not search in the
     * project resources
     */
    private IResourceValue findResValue(String resType, String resName, boolean frameworkOnly) {
        // map of IResouceValue for the given type
        Map<String, IResourceValue> typeMap;

        // if allowed, search in the project resources first.
        if (frameworkOnly == false) {
            typeMap = mProjectResources.get(resType);
            if (typeMap != null) {
                IResourceValue item = typeMap.get(resName);
                if (item != null) {
                    return item;
                }
            }
        }
        
        // now search in the framework resources.
        typeMap = mFrameworkResources.get(resType);
        if (typeMap != null) {
            IResourceValue item = typeMap.get(resName);
            if (item != null) {
                return item;
            }
        }
        
        // didn't find the resource anywhere.
        return null;
    }
    
    /**
     * Returns a framework resource by type and name. The returned resource is resolved.
     * @param resourceType the type of the resource
     * @param resourceName the name of the resource
     */
    public IResourceValue getFrameworkResource(String resourceType, String resourceName) {
        return getResource(resourceType, resourceName, mFrameworkResources);
    }
    
    /**
     * Returns a project resource by type and name. The returned resource is resolved.
     * @param resourceType the type of the resource
     * @param resourceName the name of the resource
     */
    public IResourceValue getProjectResource(String resourceType, String resourceName) {
        return getResource(resourceType, resourceName, mProjectResources);
    }
    
    IResourceValue getResource(String resourceType, String resourceName,
            Map<String, Map<String, IResourceValue>> resourceRepository) {
        Map<String, IResourceValue> typeMap = resourceRepository.get(resourceType);
        if (typeMap != null) {
            IResourceValue item = typeMap.get(resourceName);
            if (item != null) {
                item = resolveResValue(item);
                return item;
            }
        }
        
        // didn't find the resource anywhere.
        return null;
        
    }
    
    /**
     * Returns the {@link IResourceValue} matching a given name in a given style. If the
     * item is not directly available in the style, the method looks in its parent style.
     * @param style the style to search in
     * @param itemName the name of the item to search for.
     * @return the {@link IResourceValue} object or <code>null</code>
     */
    IResourceValue findItemInStyle(IStyleResourceValue style, String itemName) {
        IResourceValue item = style.findItem(itemName);
        
        // if we didn't find it, we look in the parent style (if applicable)
        if (item == null && mStyleInheritanceMap != null) {
            IStyleResourceValue parentStyle = mStyleInheritanceMap.get(style);
            if (parentStyle != null) {
                return findItemInStyle(parentStyle, itemName);
            }
        }
        
        return item;
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

    int getDynamicIdByStyle(IStyleResourceValue resValue) {
        if (mDynamicIdToStyleMap == null) {
            // create the maps.
            mDynamicIdToStyleMap = new HashMap<Integer, IStyleResourceValue>();
            mStyleToDynamicIdMap = new HashMap<IStyleResourceValue, Integer>();
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
    
    private IStyleResourceValue getStyleByDynamicId(int i) {
        if (mDynamicIdToStyleMap != null) {
            return mDynamicIdToStyleMap.get(i);
        }
   
        return null;
    }

    int getFrameworkIdValue(String idName, int defValue) {
        Integer value = Bridge.getResourceValue(BridgeConstants.RES_ID, idName);
        if (value != null) {
            return value.intValue();
        }
        
        return defValue;
    }
    
    int getProjectIdValue(String idName, int defValue) {
        if (mProjectCallback != null) {
            Integer value = mProjectCallback.getResourceValue(BridgeConstants.RES_ID, idName);
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
    public String getPackageResourcePath() {
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

    @SuppressWarnings("unused")
    @Override
    public FileInputStream openFileInput(String arg0)
            throws FileNotFoundException {
        // TODO Auto-generated method stub
        return null;
    }

    @SuppressWarnings("unused")
    @Override
    public FileOutputStream openFileOutput(String arg0, int arg1)
            throws FileNotFoundException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String arg0, int arg1,
            CursorFactory arg2) {
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
    public void setTheme(int arg0) {
        // TODO Auto-generated method stub
        
    }

    @SuppressWarnings("unused")
    @Override
    public void setWallpaper(Bitmap arg0) throws IOException {
        // TODO Auto-generated method stub
        
    }

    @SuppressWarnings("unused")
    @Override
    public void setWallpaper(InputStream arg0) throws IOException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void startActivity(Intent arg0) {
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
    public Looper getMainLooper() {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public Context getApplicationContext() {
        throw new UnsupportedOperationException();
    }

    /**
     * @hide
     */
    @Override
    public float getApplicationScale() {
        throw new UnsupportedOperationException();
    }
}
