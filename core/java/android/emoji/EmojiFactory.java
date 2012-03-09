/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.emoji;

import android.graphics.Bitmap;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A class for the factories which produce Emoji (pictgram) images.
 * This is intended to be used by IME, Email app, etc.
 * There's no plan to make this public for now.
 * @hide
 */
public final class EmojiFactory {
    // private static final String LOG_TAG = "EmojiFactory";
    
    private int sCacheSize = 100;
    
    // HashMap for caching Bitmap object. In order not to make a cache object
    // blow up, we use LinkedHashMap with size limit.
    private class CustomLinkedHashMap<K, V> extends LinkedHashMap<K, V> {
        public CustomLinkedHashMap() {
            // These magic numbers are gotten from the source code of
            // LinkedHashMap.java and HashMap.java.
            super(16, 0.75f, true);
        }
        
        /*
         * If size() becomes more than sCacheSize, least recently used cache
         * is erased. 
         * @see java.util.LinkedHashMap#removeEldestEntry(java.util.Map.Entry)
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > sCacheSize;
        }
    }
    
    // A pointer to native EmojiFactory object.
    private int mNativeEmojiFactory;
    private String mName;
    // Cache.
    private Map<Integer, WeakReference<Bitmap>> mCache;
    
    /**
     * @noinspection UnusedDeclaration
     */
    /*
     * Private constructor that must received an already allocated native
     * EmojiFactory int (pointer).
     *
     * This can be called from JNI code.
     */
    private EmojiFactory(int nativeEmojiFactory, String name) {
        mNativeEmojiFactory = nativeEmojiFactory;
        mName = name;
        mCache = new CustomLinkedHashMap<Integer, WeakReference<Bitmap>>();
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            nativeDestructor(mNativeEmojiFactory);
        } finally {
            super.finalize();
        }
    }
    
    public String name() {
        return mName;
    }
    
    /**
     * Returns Bitmap object corresponding to the AndroidPua.
     * 
     * Note that each Bitmap is cached by this class, which means that, if you modify a
     * Bitmap object (using setPos() method), all same emoji Bitmap will be modified.
     * If it is unacceptable, please copy the object before modifying it.
     *  
     * @param pua A unicode codepoint.
     * @return Bitmap object when this factory knows the Bitmap relevant to the codepoint.
     * Otherwise null is returned.  
     */
    public synchronized Bitmap getBitmapFromAndroidPua(int pua) {
        WeakReference<Bitmap> cache = mCache.get(pua);
        if (cache == null) {
            Bitmap ret = nativeGetBitmapFromAndroidPua(mNativeEmojiFactory, pua);
            // There is no need to cache returned null, since in most cases it means there
            // is no map from the AndroidPua to a specific image. In other words, it usually does
            // not include the cost of creating Bitmap object.
            if (ret != null) {
               mCache.put(pua, new WeakReference<Bitmap>(ret));
            }
            return ret;
        } else {
            Bitmap tmp = cache.get();
            if (tmp == null) {
                Bitmap ret = nativeGetBitmapFromAndroidPua(mNativeEmojiFactory, pua);
                mCache.put(pua, new WeakReference<Bitmap>(ret));
                return ret;
            } else {
                return tmp;
            }
        }
    }

    /**
     * Returns Bitmap object corresponding to the vendor specified sjis.
     * 
     * See comments in getBitmapFromAndroidPua().
     * 
     * @param sjis sjis code specific to each career(vendor)
     * @return Bitmap object when this factory knows the Bitmap relevant to the code. Otherwise
     * null is returned.
     */
    public synchronized Bitmap getBitmapFromVendorSpecificSjis(char sjis) {
        return getBitmapFromAndroidPua(getAndroidPuaFromVendorSpecificSjis(sjis));
    }

    /**
     * Returns Bitmap object corresponding to the vendor specific Unicode.
     * 
     * See comments in getBitmapFromAndroidPua().
     * 
     * @param vsp vendor specific PUA.
     * @return Bitmap object when this factory knows the Bitmap relevant to the code. Otherwise
     * null is returned.
     */
    public synchronized Bitmap getBitmapFromVendorSpecificPua(int vsp) {
        return getBitmapFromAndroidPua(getAndroidPuaFromVendorSpecificPua(vsp));
    }
    
    /**
     * Returns Unicode PUA for Android corresponding to the vendor specific sjis.
     * 
     * @param sjis vendor specific sjis
     * @return Unicode PUA for Android, or -1 if there's no map for the sjis.
     */
    public int getAndroidPuaFromVendorSpecificSjis(char sjis) {
        return nativeGetAndroidPuaFromVendorSpecificSjis(mNativeEmojiFactory, sjis);
    }
    
    /**
     * Returns vendor specific sjis corresponding to the Unicode AndroidPua.
     * 
     * @param pua Unicode PUA for Android,
     * @return vendor specific sjis, or -1 if there's no map for the AndroidPua.
     */
    public int getVendorSpecificSjisFromAndroidPua(int pua) {
        return nativeGetVendorSpecificSjisFromAndroidPua(mNativeEmojiFactory, pua);
    }
    
    /**
     * Returns Unicode PUA for Android corresponding to the vendor specific Unicode.
     * 
     * @param vsp vendor specific PUA.
     * @return Unicode PUA for Android, or -1 if there's no map for the
     * Unicode.
     */
    public int getAndroidPuaFromVendorSpecificPua(int vsp) {
        return nativeGetAndroidPuaFromVendorSpecificPua(mNativeEmojiFactory, vsp);
    }

    public String getAndroidPuaFromVendorSpecificPua(String vspString) {
        if (vspString == null) {
            return null;
        }
        int minVsp = nativeGetMinimumVendorSpecificPua(mNativeEmojiFactory);
        int maxVsp = nativeGetMaximumVendorSpecificPua(mNativeEmojiFactory);
        int len = vspString.length();
        int[] codePoints = new int[vspString.codePointCount(0, len)];

        int new_len = 0;
        for (int i = 0; i < len; i = vspString.offsetByCodePoints(i, 1), new_len++) {
            int codePoint = vspString.codePointAt(i);
            if (minVsp <= codePoint && codePoint <= maxVsp) {
                int newCodePoint = getAndroidPuaFromVendorSpecificPua(codePoint);
                if (newCodePoint > 0) {
                    codePoints[new_len] = newCodePoint;
                    continue;
                }
            }
            codePoints[new_len] = codePoint;
        }
        return new String(codePoints, 0, new_len);
    }
    
    /**
     * Returns vendor specific Unicode corresponding to the Unicode AndroidPua.
     * 
     * @param pua Unicode PUA for Android,
     * @return vendor specific sjis, or -1 if there's no map for the AndroidPua.
     */
    public int getVendorSpecificPuaFromAndroidPua(int pua) {
        return nativeGetVendorSpecificPuaFromAndroidPua(mNativeEmojiFactory, pua);
    }

    public String getVendorSpecificPuaFromAndroidPua(String puaString) {
        if (puaString == null) {
            return null;
        }
        int minVsp = nativeGetMinimumAndroidPua(mNativeEmojiFactory);
        int maxVsp = nativeGetMaximumAndroidPua(mNativeEmojiFactory);
        int len = puaString.length();
        int[] codePoints = new int[puaString.codePointCount(0, len)];

        int new_len = 0;
        for (int i = 0; i < len; i = puaString.offsetByCodePoints(i, 1), new_len++) {
            int codePoint = puaString.codePointAt(i);
            if (minVsp <= codePoint && codePoint <= maxVsp) {
                int newCodePoint = getVendorSpecificPuaFromAndroidPua(codePoint);
                if (newCodePoint > 0) {
                    codePoints[new_len] = newCodePoint;
                    continue;
                }
            }
            codePoints[new_len] = codePoint;
        }
        return new String(codePoints, 0, new_len);
    }

    /**
     * Constructs an instance of EmojiFactory corresponding to the name.
     *  
     * @param class_name Name of the factory. This must include complete package name.
     * @return A concrete EmojiFactory instance corresponding to factory_name.
     * If factory_name is invalid, null is returned. 
     */
    public static native EmojiFactory newInstance(String class_name);
    
    /**
     * Constructs an instance of available EmojiFactory.
     * 
     * @return A concrete EmojiFactory instance. If there are several available
     * EmojiFactory class, preferred one is chosen by the system. If there isn't, null
     * is returned. 
     */
    public static native EmojiFactory newAvailableInstance();

    /**
     * Returns the lowest code point corresponding to an Android
     * emoji character.
     */
    public int getMinimumAndroidPua() {
        return nativeGetMinimumAndroidPua(mNativeEmojiFactory);
    }

    /**
     * Returns the highest code point corresponding to an Android
     * emoji character.
     */
    public int getMaximumAndroidPua() {
        return nativeGetMaximumAndroidPua(mNativeEmojiFactory);
    }
    
    // native methods
    
    private native void nativeDestructor(int factory);
    private native Bitmap nativeGetBitmapFromAndroidPua(int nativeEmojiFactory, int AndroidPua);
    private native int nativeGetAndroidPuaFromVendorSpecificSjis(int nativeEmojiFactory,
            char sjis);
    private native int nativeGetVendorSpecificSjisFromAndroidPua(int nativeEmojiFactory,
            int pua);
    private native int nativeGetAndroidPuaFromVendorSpecificPua(int nativeEmojiFactory,
            int vsp);
    private native int nativeGetVendorSpecificPuaFromAndroidPua(int nativeEmojiFactory,
            int pua);
    private native int nativeGetMaximumVendorSpecificPua(int nativeEmojiFactory);
    private native int nativeGetMinimumVendorSpecificPua(int nativeEmojiFactory);
    private native int nativeGetMaximumAndroidPua(int nativeEmojiFactory);
    private native int nativeGetMinimumAndroidPua(int nativeEmojiFactory);
}
