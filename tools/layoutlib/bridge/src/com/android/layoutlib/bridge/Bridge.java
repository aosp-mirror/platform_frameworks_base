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

import com.android.layoutlib.api.Capabilities;
import com.android.layoutlib.api.LayoutLog;
import com.android.layoutlib.api.IProjectCallback;
import com.android.layoutlib.api.IResourceValue;
import com.android.layoutlib.api.IXmlPullParser;
import com.android.layoutlib.api.LayoutBridge;
import com.android.layoutlib.api.SceneParams;
import com.android.layoutlib.api.SceneResult;
import com.android.layoutlib.api.SceneResult.SceneStatus;
import com.android.layoutlib.bridge.android.BridgeAssetManager;
import com.android.layoutlib.bridge.impl.FontLoader;
import com.android.layoutlib.bridge.impl.LayoutSceneImpl;
import com.android.ninepatch.NinePatchChunk;
import com.android.tools.layoutlib.create.MethodAdapter;
import com.android.tools.layoutlib.create.OverrideMethod;

import android.graphics.Bitmap;
import android.graphics.Typeface_Delegate;
import android.os.Looper;
import android.util.Finalizers;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Main entry point of the LayoutLib Bridge.
 * <p/>To use this bridge, simply instantiate an object of type {@link Bridge} and call
 * {@link #computeLayout(IXmlPullParser, Object, int, int, String, boolean, Map, Map, IProjectCallback, ILayoutLog)}.
 */
public final class Bridge extends LayoutBridge {

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
     * Maps from id to resource name/type. This is for android.R only.
     */
    private final static Map<Integer, String[]> sRMap = new HashMap<Integer, String[]>();
    /**
     * Same as sRMap except for int[] instead of int resources. This is for android.R only.
     */
    private final static Map<IntArray, String> sRArrayMap = new HashMap<IntArray, String>();
    /**
     * Reverse map compared to sRMap, resource type -> (resource name -> id).
     * This is for android.R only.
     */
    private final static Map<String, Map<String, Integer>> sRFullMap =
        new HashMap<String, Map<String,Integer>>();

    private final static Map<Object, Map<String, SoftReference<Bitmap>>> sProjectBitmapCache =
        new HashMap<Object, Map<String, SoftReference<Bitmap>>>();
    private final static Map<Object, Map<String, SoftReference<NinePatchChunk>>> sProject9PatchCache =
        new HashMap<Object, Map<String, SoftReference<NinePatchChunk>>>();

    private final static Map<String, SoftReference<Bitmap>> sFrameworkBitmapCache =
        new HashMap<String, SoftReference<Bitmap>>();
    private final static Map<String, SoftReference<NinePatchChunk>> sFramework9PatchCache =
        new HashMap<String, SoftReference<NinePatchChunk>>();

    private static Map<String, Map<String, Integer>> sEnumValueMap;

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
            if (!Arrays.equals(mArray, other.mArray)) return false;
            return true;
        }
    }

    /** Instance of IntArrayWrapper to be reused in {@link #resolveResourceValue(int[])}. */
    private final static IntArray sIntArrayWrapper = new IntArray();

    /**
     * A default log than prints to stdout/stderr.
     */
    private final static LayoutLog sDefaultLog = new LayoutLog() {
        @Override
        public void error(String tag, String message) {
            System.err.println(message);
        }

        @Override
        public void error(String tag, Throwable t) {
            String message = t.getMessage();
            if (message == null) {
                message = t.getClass().getName();
            }

            System.err.println(message);
        }

        @Override
        public void error(String tag, String message, Throwable throwable) {
            System.err.println(message);
        }

        @Override
        public void warning(String tag, String message) {
            System.out.println(message);
        }
    };

    /**
     * Current log.
     */
    private static LayoutLog sCurrentLog = sDefaultLog;


    private EnumSet<Capabilities> mCapabilities;


    @Override
    public int getApiLevel() {
        return LayoutBridge.API_CURRENT;
    }

    @Override
    public EnumSet<Capabilities> getCapabilities() {
        return mCapabilities;
    }

    /*
     * (non-Javadoc)
     * @see com.android.layoutlib.api.ILayoutLibBridge#init(java.lang.String, java.util.Map)
     */
    @Override
    public boolean init(String fontOsLocation, Map<String, Map<String, Integer>> enumValueMap) {
        sEnumValueMap = enumValueMap;

        // don't use EnumSet.allOf(), because the bridge doesn't come with its specific version
        // of layoutlib_api. It is provided by the client which could have a more recent version
        // with newer, unsupported capabilities.
        mCapabilities = EnumSet.of(
                Capabilities.RENDER,
                Capabilities.VIEW_MANIPULATION,
                Capabilities.ANIMATE);


        Finalizers.init();

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
                            (isNative ? " (native)" : ""));

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
        FontLoader fontLoader = FontLoader.create(fontOsLocation);
        if (fontLoader != null) {
            Typeface_Delegate.init(fontLoader);
        } else {
            return false;
        }

        // now parse com.android.internal.R (and only this one as android.R is a subset of
        // the internal version), and put the content in the maps.
        try {
            // WARNING: this only works because the class is already loaded, and therefore
            // the objects returned by Field.get() are the same as the ones used by
            // the code accessing the R class.
            // int[] does not implement equals/hashCode, and if the parsing used a different class
            // loader for the R class, this would NOT work.
            Class<?> r = com.android.internal.R.class;

            for (Class<?> inner : r.getDeclaredClasses()) {
                String resType = inner.getSimpleName();

                Map<String, Integer> fullMap = new HashMap<String, Integer>();
                sRFullMap.put(resType, fullMap);

                for (Field f : inner.getDeclaredFields()) {
                    // only process static final fields. Since the final attribute may have
                    // been altered by layoutlib_create, we only check static
                    int modifiers = f.getModifiers();
                    if (Modifier.isStatic(modifiers)) {
                        Class<?> type = f.getType();
                        if (type.isArray() && type.getComponentType() == int.class) {
                            // if the object is an int[] we put it in sRArrayMap
                            sRArrayMap.put(new IntArray((int[]) f.get(null)), f.getName());
                        } else if (type == int.class) {
                            Integer value = (Integer) f.get(null);
                            sRMap.put(value, new String[] { f.getName(), resType });
                            fullMap.put(f.getName(), value);
                        } else {
                            assert false;
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // FIXME: log/return the error (there's no logger object at this point!)
            e.printStackTrace();
            return false;
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    @Override
    public boolean dispose() {
        BridgeAssetManager.clearSystem();
        return true;
    }

    /**
     * Starts a layout session by inflating and rendering it. The method returns a
     * {@link ILayoutScene} on which further actions can be taken.
     *
     * @param layoutDescription the {@link IXmlPullParser} letting the LayoutLib Bridge visit the
     * layout file.
     * @param projectKey An Object identifying the project. This is used for the cache mechanism.
     * @param screenWidth the screen width
     * @param screenHeight the screen height
     * @param renderFullSize if true, the rendering will render the full size needed by the
     * layout. This size is never smaller than <var>screenWidth</var> x <var>screenHeight</var>.
     * @param density the density factor for the screen.
     * @param xdpi the screen actual dpi in X
     * @param ydpi the screen actual dpi in Y
     * @param themeName The name of the theme to use.
     * @param isProjectTheme true if the theme is a project theme, false if it is a framework theme.
     * @param projectResources the resources of the project. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the
     * map contains (String, {@link IResourceValue}) pairs where the key is the resource name,
     * and the value is the resource value.
     * @param frameworkResources the framework resources. The map contains (String, map) pairs
     * where the string is the type of the resource reference used in the layout file, and the map
     * contains (String, {@link IResourceValue}) pairs where the key is the resource name, and the
     * value is the resource value.
     * @param projectCallback The {@link IProjectCallback} object to get information from
     * the project.
     * @param logger the object responsible for displaying warning/errors to the user.
     * @return a new {@link ILayoutScene} object that contains the result of the layout.
     * @since 5
     */
    @Override
    public BridgeLayoutScene createScene(SceneParams params) {
        try {
            SceneResult lastResult = SceneStatus.SUCCESS.createResult();
            LayoutSceneImpl scene = new LayoutSceneImpl(params);
            try {
                prepareThread();
                lastResult = scene.init(params.getTimeout());
                if (lastResult.isSuccess()) {
                    lastResult = scene.inflate();
                    if (lastResult.isSuccess()) {
                        lastResult = scene.render();
                    }
                }
            } finally {
                scene.release();
                cleanupThread();
            }

            return new BridgeLayoutScene(scene, lastResult);
        } catch (Throwable t) {
            // get the real cause of the exception.
            Throwable t2 = t;
            while (t2.getCause() != null) {
                t2 = t.getCause();
            }
            return new BridgeLayoutScene(null,
                    SceneStatus.ERROR_UNKNOWN.createResult(t2.getMessage(), t2));
        }
    }

    /*
     * (non-Javadoc)
     * @see com.android.layoutlib.api.ILayoutLibBridge#clearCaches(java.lang.Object)
     */
    @Override
    public void clearCaches(Object projectKey) {
        if (projectKey != null) {
            sProjectBitmapCache.remove(projectKey);
            sProject9PatchCache.remove(projectKey);
        }
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
            Looper.prepare();
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
        Looper.sThreadLocal.remove();
    }

    public static LayoutLog getLog() {
        return sCurrentLog;
    }

    public static void setLog(LayoutLog log) {
        // check only the thread currently owning the lock can do this.
        if (sLock.isHeldByCurrentThread() == false) {
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
     * @return an array of 2 strings containing the resource name and type, or null if the id
     * does not match any resource.
     */
    public static String[] resolveResourceValue(int value) {
        return sRMap.get(value);
    }

    /**
     * Returns the name of a framework resource whose value is an int array.
     * @param array
     */
    public static String resolveResourceValue(int[] array) {
        sIntArrayWrapper.set(array);
        return sRArrayMap.get(sIntArrayWrapper);
    }

    /**
     * Returns the integer id of a framework resource, from a given resource type and resource name.
     * @param type the type of the resource
     * @param name the name of the resource.
     * @return an {@link Integer} containing the resource id, or null if no resource were found.
     */
    public static Integer getResourceValue(String type, String name) {
        Map<String, Integer> map = sRFullMap.get(type);
        if (map != null) {
            return map.get(name);
        }

        return null;
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
