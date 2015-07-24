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

import com.android.ide.common.rendering.api.Capability;
import com.android.ide.common.rendering.api.DrawableParams;
import com.android.ide.common.rendering.api.Features;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.Result.Status;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.layoutlib.bridge.impl.RenderDrawable;
import com.android.layoutlib.bridge.impl.RenderSessionImpl;
import com.android.layoutlib.bridge.util.DynamicIdMap;
import com.android.ninepatch.NinePatchChunk;
import com.android.resources.ResourceType;
import com.android.tools.layoutlib.create.MethodAdapter;
import com.android.tools.layoutlib.create.OverrideMethod;
import com.android.util.Pair;

import android.annotation.NonNull;
import android.content.res.BridgeAssetManager;
import android.graphics.Bitmap;
import android.graphics.FontFamily_Delegate;
import android.graphics.Typeface_Delegate;
import android.icu.util.ULocale;
import android.os.Looper;
import android.os.Looper_Accessor;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.io.File;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import libcore.io.MemoryMappedFile_Delegate;

import static com.android.ide.common.rendering.api.Result.Status.ERROR_UNKNOWN;
import static com.android.ide.common.rendering.api.Result.Status.SUCCESS;

/**
 * Main entry point of the LayoutLib Bridge.
 * <p/>To use this bridge, simply instantiate an object of type {@link Bridge} and call
 * {@link #createSession(SessionParams)}
 */
public final class Bridge extends com.android.ide.common.rendering.api.Bridge {

    private static final String ICU_LOCALE_DIRECTION_RTL = "right-to-left";

    public static class StaticMethodNotImplementedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StaticMethodNotImplementedException(String msg) {
            super(msg);
        }
    }

    /**
     * Lock to ensure only one rendering/inflating happens at a time.
     * This is due to some singleton in the Android framework.
     */
    private final static ReentrantLock sLock = new ReentrantLock();

    /**
     * Maps from id to resource type/name. This is for com.android.internal.R
     */
    private final static Map<Integer, Pair<ResourceType, String>> sRMap =
        new HashMap<Integer, Pair<ResourceType, String>>();

    /**
     * Same as sRMap except for int[] instead of int resources. This is for android.R only.
     */
    private final static Map<IntArray, String> sRArrayMap = new HashMap<IntArray, String>(384);
    /**
     * Reverse map compared to sRMap, resource type -> (resource name -> id).
     * This is for com.android.internal.R.
     */
    private final static Map<ResourceType, Map<String, Integer>> sRevRMap =
        new EnumMap<ResourceType, Map<String,Integer>>(ResourceType.class);

    // framework resources are defined as 0x01XX#### where XX is the resource type (layout,
    // drawable, etc...). Using FF as the type allows for 255 resource types before we get a
    // collision which should be fine.
    private final static int DYNAMIC_ID_SEED_START = 0x01ff0000;
    private final static DynamicIdMap sDynamicIds = new DynamicIdMap(DYNAMIC_ID_SEED_START);

    private final static Map<Object, Map<String, SoftReference<Bitmap>>> sProjectBitmapCache =
        new HashMap<Object, Map<String, SoftReference<Bitmap>>>();
    private final static Map<Object, Map<String, SoftReference<NinePatchChunk>>> sProject9PatchCache =
        new HashMap<Object, Map<String, SoftReference<NinePatchChunk>>>();

    private final static Map<String, SoftReference<Bitmap>> sFrameworkBitmapCache =
        new HashMap<String, SoftReference<Bitmap>>();
    private final static Map<String, SoftReference<NinePatchChunk>> sFramework9PatchCache =
        new HashMap<String, SoftReference<NinePatchChunk>>();

    private static Map<String, Map<String, Integer>> sEnumValueMap;
    private static Map<String, String> sPlatformProperties;

    /**
     * int[] wrapper to use as keys in maps.
     */
    private final static class IntArray {
        private int[] mArray;

        private IntArray() {
            // do nothing
        }

        private IntArray(int[] a) {
            mArray = a;
        }

        private void set(int[] a) {
            mArray = a;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(mArray);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;

            IntArray other = (IntArray) obj;
            return Arrays.equals(mArray, other.mArray);
        }
    }

    /** Instance of IntArrayWrapper to be reused in {@link #resolveResourceId(int[])}. */
    private final static IntArray sIntArrayWrapper = new IntArray();

    /**
     * A default log than prints to stdout/stderr.
     */
    private final static LayoutLog sDefaultLog = new LayoutLog() {
        @Override
        public void error(String tag, String message, Object data) {
            System.err.println(message);
        }

        @Override
        public void error(String tag, String message, Throwable throwable, Object data) {
            System.err.println(message);
        }

        @Override
        public void warning(String tag, String message, Object data) {
            System.out.println(message);
        }
    };

    /**
     * Current log.
     */
    private static LayoutLog sCurrentLog = sDefaultLog;

    private static final int LAST_SUPPORTED_FEATURE = Features.RECYCLER_VIEW_ADAPTER;

    @Override
    public int getApiLevel() {
        return com.android.ide.common.rendering.api.Bridge.API_CURRENT;
    }

    @Override
    @Deprecated
    public EnumSet<Capability> getCapabilities() {
        // The Capability class is deprecated and frozen. All Capabilities enumerated there are
        // supported by this version of LayoutLibrary. So, it's safe to use EnumSet.allOf()
        return EnumSet.allOf(Capability.class);
    }

    @Override
    public boolean supports(int feature) {
        return feature <= LAST_SUPPORTED_FEATURE;
    }

    @Override
    public boolean init(Map<String,String> platformProperties,
            File fontLocation,
            Map<String, Map<String, Integer>> enumValueMap,
            LayoutLog log) {
        sPlatformProperties = platformProperties;
        sEnumValueMap = enumValueMap;

        BridgeAssetManager.initSystem();

        // When DEBUG_LAYOUT is set and is not 0 or false, setup a default listener
        // on static (native) methods which prints the signature on the console and
        // throws an exception.
        // This is useful when testing the rendering in ADT to identify static native
        // methods that are ignored -- layoutlib_create makes them returns 0/false/null
        // which is generally OK yet might be a problem, so this is how you'd find out.
        //
        // Currently layoutlib_create only overrides static native method.
        // Static non-natives are not overridden and thus do not get here.
        final String debug = System.getenv("DEBUG_LAYOUT");
        if (debug != null && !debug.equals("0") && !debug.equals("false")) {

            OverrideMethod.setDefaultListener(new MethodAdapter() {
                @Override
                public void onInvokeV(String signature, boolean isNative, Object caller) {
                    sDefaultLog.error(null, "Missing Stub: " + signature +
                            (isNative ? " (native)" : ""), null /*data*/);

                    if (debug.equalsIgnoreCase("throw")) {
                        // Throwing this exception doesn't seem that useful. It breaks
                        // the layout editor yet doesn't display anything meaningful to the
                        // user. Having the error in the console is just as useful. We'll
                        // throw it only if the environment variable is "throw" or "THROW".
                        throw new StaticMethodNotImplementedException(signature);
                    }
                }
            });
        }

        // load the fonts.
        FontFamily_Delegate.setFontLocation(fontLocation.getAbsolutePath());
        MemoryMappedFile_Delegate.setDataDir(fontLocation.getAbsoluteFile().getParentFile());

        // now parse com.android.internal.R (and only this one as android.R is a subset of
        // the internal version), and put the content in the maps.
        try {
            Class<?> r = com.android.internal.R.class;
            // Parse the styleable class first, since it may contribute to attr values.
            parseStyleable();

            for (Class<?> inner : r.getDeclaredClasses()) {
                if (inner == com.android.internal.R.styleable.class) {
                    // Already handled the styleable case. Not skipping attr, as there may be attrs
                    // that are not referenced from styleables.
                    continue;
                }
                String resTypeName = inner.getSimpleName();
                ResourceType resType = ResourceType.getEnum(resTypeName);
                if (resType != null) {
                    Map<String, Integer> fullMap = null;
                    switch (resType) {
                        case ATTR:
                            fullMap = sRevRMap.get(ResourceType.ATTR);
                            break;
                        case STRING:
                        case STYLE:
                            // Slightly less than thousand entries in each.
                            fullMap = new HashMap<String, Integer>(1280);
                            // no break.
                        default:
                            if (fullMap == null) {
                                fullMap = new HashMap<String, Integer>();
                            }
                            sRevRMap.put(resType, fullMap);
                    }

                    for (Field f : inner.getDeclaredFields()) {
                        // only process static final fields. Since the final attribute may have
                        // been altered by layoutlib_create, we only check static
                        if (!isValidRField(f)) {
                            continue;
                        }
                        Class<?> type = f.getType();
                        if (type.isArray()) {
                            // if the object is an int[] we put it in sRArrayMap using an IntArray
                            // wrapper that properly implements equals and hashcode for the array
                            // objects, as required by the map contract.
                            sRArrayMap.put(new IntArray((int[]) f.get(null)), f.getName());
                        } else {
                            Integer value = (Integer) f.get(null);
                            sRMap.put(value, Pair.of(resType, f.getName()));
                            fullMap.put(f.getName(), value);
                        }
                    }
                }
            }
        } catch (Exception throwable) {
            if (log != null) {
                log.error(LayoutLog.TAG_BROKEN,
                        "Failed to load com.android.internal.R from the layout library jar",
                        throwable, null);
            }
            return false;
        }

        return true;
    }

    /**
     * Tests if the field is pubic, static and one of int or int[].
     */
    private static boolean isValidRField(Field field) {
        int modifiers = field.getModifiers();
        boolean isAcceptable = Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers);
        Class<?> type = field.getType();
        return isAcceptable && type == int.class ||
                (type.isArray() && type.getComponentType() == int.class);

    }

    private static void parseStyleable() throws Exception {
        // R.attr doesn't contain all the needed values. There are too many resources in the
        // framework for all to be in the R class. Only the ones specified manually in
        // res/values/symbols.xml are put in R class. Since, we need to create a map of all attr
        // values, we try and find them from the styleables.

        // There were 1500 elements in this map at M timeframe.
        Map<String, Integer> revRAttrMap = new HashMap<String, Integer>(2048);
        sRevRMap.put(ResourceType.ATTR, revRAttrMap);
        // There were 2000 elements in this map at M timeframe.
        Map<String, Integer> revRStyleableMap = new HashMap<String, Integer>(3072);
        sRevRMap.put(ResourceType.STYLEABLE, revRStyleableMap);
        Class<?> c = com.android.internal.R.styleable.class;
        Field[] fields = c.getDeclaredFields();
        // Sort the fields to bring all arrays to the beginning, so that indices into the array are
        // able to refer back to the arrays (i.e. no forward references).
        Arrays.sort(fields, new Comparator<Field>() {
            @Override
            public int compare(Field o1, Field o2) {
                if (o1 == o2) {
                    return 0;
                }
                Class<?> t1 = o1.getType();
                Class<?> t2 = o2.getType();
                if (t1.isArray() && !t2.isArray()) {
                    return -1;
                } else if (t2.isArray() && !t1.isArray()) {
                    return 1;
                }
                return o1.getName().compareTo(o2.getName());
            }
        });
        Map<String, int[]> styleables = new HashMap<String, int[]>();
        for (Field field : fields) {
            if (!isValidRField(field)) {
                // Only consider public static fields that are int or int[].
                // Don't check the final flag as it may have been modified by layoutlib_create.
                continue;
            }
            String name = field.getName();
            if (field.getType().isArray()) {
                int[] styleableValue = (int[]) field.get(null);
                sRArrayMap.put(new IntArray(styleableValue), name);
                styleables.put(name, styleableValue);
                continue;
            }
            // Not an array.
            String arrayName = name;
            int[] arrayValue = null;
            int index;
            while ((index = arrayName.lastIndexOf('_')) >= 0) {
                // Find the name of the corresponding styleable.
                // Search in reverse order so that attrs like LinearLayout_Layout_layout_gravity
                // are mapped to LinearLayout_Layout and not to LinearLayout.
                arrayName = arrayName.substring(0, index);
                arrayValue = styleables.get(arrayName);
                if (arrayValue != null) {
                    break;
                }
            }
            index = (Integer) field.get(null);
            if (arrayValue != null) {
                String attrName = name.substring(arrayName.length() + 1);
                int attrValue = arrayValue[index];
                sRMap.put(attrValue, Pair.of(ResourceType.ATTR, attrName));
                revRAttrMap.put(attrName, attrValue);
            }
            sRMap.put(index, Pair.of(ResourceType.STYLEABLE, name));
            revRStyleableMap.put(name, index);
        }
    }

    @Override
    public boolean dispose() {
        BridgeAssetManager.clearSystem();

        // dispose of the default typeface.
        Typeface_Delegate.resetDefaults();

        return true;
    }

    /**
     * Starts a layout session by inflating and rendering it. The method returns a
     * {@link RenderSession} on which further actions can be taken.
     *
     * @param params the {@link SessionParams} object with all the information necessary to create
     *           the scene.
     * @return a new {@link RenderSession} object that contains the result of the layout.
     * @since 5
     */
    @Override
    public RenderSession createSession(SessionParams params) {
        try {
            Result lastResult = SUCCESS.createResult();
            RenderSessionImpl scene = new RenderSessionImpl(params);
            try {
                prepareThread();
                lastResult = scene.init(params.getTimeout());
                if (lastResult.isSuccess()) {
                    lastResult = scene.inflate();
                    if (lastResult.isSuccess()) {
                        lastResult = scene.render(true /*freshRender*/);
                    }
                }
            } finally {
                scene.release();
                cleanupThread();
            }

            return new BridgeRenderSession(scene, lastResult);
        } catch (Throwable t) {
            // get the real cause of the exception.
            Throwable t2 = t;
            while (t2.getCause() != null) {
                t2 = t.getCause();
            }
            return new BridgeRenderSession(null,
                    ERROR_UNKNOWN.createResult(t2.getMessage(), t));
        }
    }

    @Override
    public Result renderDrawable(DrawableParams params) {
        try {
            Result lastResult = SUCCESS.createResult();
            RenderDrawable action = new RenderDrawable(params);
            try {
                prepareThread();
                lastResult = action.init(params.getTimeout());
                if (lastResult.isSuccess()) {
                    lastResult = action.render();
                }
            } finally {
                action.release();
                cleanupThread();
            }

            return lastResult;
        } catch (Throwable t) {
            // get the real cause of the exception.
            Throwable t2 = t;
            while (t2.getCause() != null) {
                t2 = t.getCause();
            }
            return ERROR_UNKNOWN.createResult(t2.getMessage(), t);
        }
    }

    @Override
    public void clearCaches(Object projectKey) {
        if (projectKey != null) {
            sProjectBitmapCache.remove(projectKey);
            sProject9PatchCache.remove(projectKey);
        }
    }

    @Override
    public Result getViewParent(Object viewObject) {
        if (viewObject instanceof View) {
            return Status.SUCCESS.createResult(((View)viewObject).getParent());
        }

        throw new IllegalArgumentException("viewObject is not a View");
    }

    @Override
    public Result getViewIndex(Object viewObject) {
        if (viewObject instanceof View) {
            View view = (View) viewObject;
            ViewParent parentView = view.getParent();

            if (parentView instanceof ViewGroup) {
                Status.SUCCESS.createResult(((ViewGroup) parentView).indexOfChild(view));
            }

            return Status.SUCCESS.createResult();
        }

        throw new IllegalArgumentException("viewObject is not a View");
    }

    @Override
    public boolean isRtl(String locale) {
        return isLocaleRtl(locale);
    }

    public static boolean isLocaleRtl(String locale) {
        if (locale == null) {
            locale = "";
        }
        ULocale uLocale = new ULocale(locale);
        return uLocale.getCharacterOrientation().equals(ICU_LOCALE_DIRECTION_RTL);
    }

    /**
     * Returns the lock for the bridge
     */
    public static ReentrantLock getLock() {
        return sLock;
    }

    /**
     * Prepares the current thread for rendering.
     *
     * Note that while this can be called several time, the first call to {@link #cleanupThread()}
     * will do the clean-up, and make the thread unable to do further scene actions.
     */
    public static void prepareThread() {
        // we need to make sure the Looper has been initialized for this thread.
        // this is required for View that creates Handler objects.
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
    }

    /**
     * Cleans up thread-specific data. After this, the thread cannot be used for scene actions.
     * <p>
     * Note that it doesn't matter how many times {@link #prepareThread()} was called, a single
     * call to this will prevent the thread from doing further scene actions
     */
    public static void cleanupThread() {
        // clean up the looper
        Looper_Accessor.cleanupThread();
    }

    public static LayoutLog getLog() {
        return sCurrentLog;
    }

    public static void setLog(LayoutLog log) {
        // check only the thread currently owning the lock can do this.
        if (!sLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("scene must be acquired first. see #acquire(long)");
        }

        if (log != null) {
            sCurrentLog = log;
        } else {
            sCurrentLog = sDefaultLog;
        }
    }

    /**
     * Returns details of a framework resource from its integer value.
     * @param value the integer value
     * @return a Pair containing the resource type and name, or null if the id
     *     does not match any resource.
     */
    public static Pair<ResourceType, String> resolveResourceId(int value) {
        Pair<ResourceType, String> pair = sRMap.get(value);
        if (pair == null) {
            pair = sDynamicIds.resolveId(value);
            if (pair == null) {
                //System.out.println(String.format("Missing id: %1$08X (%1$d)", value));
            }
        }
        return pair;
    }

    /**
     * Returns the name of a framework resource whose value is an int array.
     */
    public static String resolveResourceId(int[] array) {
        sIntArrayWrapper.set(array);
        return sRArrayMap.get(sIntArrayWrapper);
    }

    /**
     * Returns the integer id of a framework resource, from a given resource type and resource name.
     * <p/>
     * If no resource is found, it creates a dynamic id for the resource.
     *
     * @param type the type of the resource
     * @param name the name of the resource.
     *
     * @return an {@link Integer} containing the resource id.
     */
    @NonNull
    public static Integer getResourceId(ResourceType type, String name) {
        Map<String, Integer> map = sRevRMap.get(type);
        Integer value = null;
        if (map != null) {
            value = map.get(name);
        }

        return value == null ? sDynamicIds.getId(type, name) : value;

    }

    /**
     * Returns the list of possible enums for a given attribute name.
     */
    public static Map<String, Integer> getEnumValues(String attributeName) {
        if (sEnumValueMap != null) {
            return sEnumValueMap.get(attributeName);
        }

        return null;
    }

    /**
     * Returns the platform build properties.
     */
    public static Map<String, String> getPlatformProperties() {
        return sPlatformProperties;
    }

    /**
     * Returns the bitmap for a specific path, from a specific project cache, or from the
     * framework cache.
     * @param value the path of the bitmap
     * @param projectKey the key of the project, or null to query the framework cache.
     * @return the cached Bitmap or null if not found.
     */
    public static Bitmap getCachedBitmap(String value, Object projectKey) {
        if (projectKey != null) {
            Map<String, SoftReference<Bitmap>> map = sProjectBitmapCache.get(projectKey);
            if (map != null) {
                SoftReference<Bitmap> ref = map.get(value);
                if (ref != null) {
                    return ref.get();
                }
            }
        } else {
            SoftReference<Bitmap> ref = sFrameworkBitmapCache.get(value);
            if (ref != null) {
                return ref.get();
            }
        }

        return null;
    }

    /**
     * Sets a bitmap in a project cache or in the framework cache.
     * @param value the path of the bitmap
     * @param bmp the Bitmap object
     * @param projectKey the key of the project, or null to put the bitmap in the framework cache.
     */
    public static void setCachedBitmap(String value, Bitmap bmp, Object projectKey) {
        if (projectKey != null) {
            Map<String, SoftReference<Bitmap>> map = sProjectBitmapCache.get(projectKey);

            if (map == null) {
                map = new HashMap<String, SoftReference<Bitmap>>();
                sProjectBitmapCache.put(projectKey, map);
            }

            map.put(value, new SoftReference<Bitmap>(bmp));
        } else {
            sFrameworkBitmapCache.put(value, new SoftReference<Bitmap>(bmp));
        }
    }

    /**
     * Returns the 9 patch chunk for a specific path, from a specific project cache, or from the
     * framework cache.
     * @param value the path of the 9 patch
     * @param projectKey the key of the project, or null to query the framework cache.
     * @return the cached 9 patch or null if not found.
     */
    public static NinePatchChunk getCached9Patch(String value, Object projectKey) {
        if (projectKey != null) {
            Map<String, SoftReference<NinePatchChunk>> map = sProject9PatchCache.get(projectKey);

            if (map != null) {
                SoftReference<NinePatchChunk> ref = map.get(value);
                if (ref != null) {
                    return ref.get();
                }
            }
        } else {
            SoftReference<NinePatchChunk> ref = sFramework9PatchCache.get(value);
            if (ref != null) {
                return ref.get();
            }
        }

        return null;
    }

    /**
     * Sets a 9 patch chunk in a project cache or in the framework cache.
     * @param value the path of the 9 patch
     * @param ninePatch the 9 patch object
     * @param projectKey the key of the project, or null to put the bitmap in the framework cache.
     */
    public static void setCached9Patch(String value, NinePatchChunk ninePatch, Object projectKey) {
        if (projectKey != null) {
            Map<String, SoftReference<NinePatchChunk>> map = sProject9PatchCache.get(projectKey);

            if (map == null) {
                map = new HashMap<String, SoftReference<NinePatchChunk>>();
                sProject9PatchCache.put(projectKey, map);
            }

            map.put(value, new SoftReference<NinePatchChunk>(ninePatch));
        } else {
            sFramework9PatchCache.put(value, new SoftReference<NinePatchChunk>(ninePatch));
        }
    }
}
