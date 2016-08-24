/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content.res;

import android.annotation.AnyRes;
import android.annotation.ArrayRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.res.Configuration.NativeConfig;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/**
 * Provides access to an application's raw asset files; see {@link Resources}
 * for the way most applications will want to retrieve their resource data.
 * This class presents a lower-level API that allows you to open and read raw
 * files that have been bundled with the application as a simple stream of
 * bytes.
 */
public final class AssetManager implements AutoCloseable {
    /* modes used when opening an asset */

    /**
     * Mode for {@link #open(String, int)}: no specific information about how
     * data will be accessed.
     */
    public static final int ACCESS_UNKNOWN = 0;
    /**
     * Mode for {@link #open(String, int)}: Read chunks, and seek forward and
     * backward.
     */
    public static final int ACCESS_RANDOM = 1;
    /**
     * Mode for {@link #open(String, int)}: Read sequentially, with an
     * occasional forward seek.
     */
    public static final int ACCESS_STREAMING = 2;
    /**
     * Mode for {@link #open(String, int)}: Attempt to load contents into
     * memory, for fast small reads.
     */
    public static final int ACCESS_BUFFER = 3;

    private static final String TAG = "AssetManager";
    private static final boolean localLOGV = false || false;
    
    private static final boolean DEBUG_REFS = false;
    
    private static final Object sSync = new Object();
    /*package*/ static AssetManager sSystem = null;

    private final TypedValue mValue = new TypedValue();
    private final long[] mOffsets = new long[2];
    
    // For communication with native code.
    private long mObject;

    private StringBlock mStringBlocks[] = null;
    
    private int mNumRefs = 1;
    private boolean mOpen = true;
    private HashMap<Long, RuntimeException> mRefStacks;
 
    /**
     * Create a new AssetManager containing only the basic system assets.
     * Applications will not generally use this method, instead retrieving the
     * appropriate asset manager with {@link Resources#getAssets}.    Not for
     * use by applications.
     * {@hide}
     */
    public AssetManager() {
        synchronized (this) {
            if (DEBUG_REFS) {
                mNumRefs = 0;
                incRefsLocked(this.hashCode());
            }
            init(false);
            if (localLOGV) Log.v(TAG, "New asset manager: " + this);
            ensureSystemAssets();
        }
    }

    private static void ensureSystemAssets() {
        synchronized (sSync) {
            if (sSystem == null) {
                AssetManager system = new AssetManager(true);
                system.makeStringBlocks(null);
                sSystem = system;
            }
        }
    }
    
    private AssetManager(boolean isSystem) {
        if (DEBUG_REFS) {
            synchronized (this) {
                mNumRefs = 0;
                incRefsLocked(this.hashCode());
            }
        }
        init(true);
        if (localLOGV) Log.v(TAG, "New asset manager: " + this);
    }

    /**
     * Return a global shared asset manager that provides access to only
     * system assets (no application assets).
     * {@hide}
     */
    public static AssetManager getSystem() {
        ensureSystemAssets();
        return sSystem;
    }

    /**
     * Close this asset manager.
     */
    public void close() {
        synchronized(this) {
            //System.out.println("Release: num=" + mNumRefs
            //                   + ", released=" + mReleased);
            if (mOpen) {
                mOpen = false;
                decRefsLocked(this.hashCode());
            }
        }
    }

    /**
     * Retrieves the string value associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @return the string value, or {@code null}
     */
    @Nullable
    final CharSequence getResourceText(@StringRes int resId) {
        synchronized (this) {
            final TypedValue outValue = mValue;
            if (getResourceValue(resId, 0, outValue, true)) {
                return outValue.coerceToString();
            }
            return null;
        }
    }

    /**
     * Retrieves the string value associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @param bagEntryId
     * @return the string value, or {@code null}
     */
    @Nullable
    final CharSequence getResourceBagText(@StringRes int resId, int bagEntryId) {
        synchronized (this) {
            final TypedValue outValue = mValue;
            final int block = loadResourceBagValue(resId, bagEntryId, outValue, true);
            if (block < 0) {
                return null;
            }
            if (outValue.type == TypedValue.TYPE_STRING) {
                return mStringBlocks[block].get(outValue.data);
            }
            return outValue.coerceToString();
        }
    }

    /**
     * Retrieves the string array associated with a particular resource
     * identifier for the current configuration.
     *
     * @param resId the resource identifier of the string array
     * @return the string array, or {@code null}
     */
    @Nullable
    final String[] getResourceStringArray(@ArrayRes int resId) {
        return getArrayStringResource(resId);
    }

    /**
     * Populates {@code outValue} with the data associated a particular
     * resource identifier for the current configuration.
     *
     * @param resId the resource identifier to load
     * @param densityDpi the density bucket for which to load the resource
     * @param outValue the typed value in which to put the data
     * @param resolveRefs {@code true} to resolve references, {@code false}
     *                    to leave them unresolved
     * @return {@code true} if the data was loaded into {@code outValue},
     *         {@code false} otherwise
     */
    final boolean getResourceValue(@AnyRes int resId, int densityDpi, @NonNull TypedValue outValue,
            boolean resolveRefs) {
        final int block = loadResourceValue(resId, (short) densityDpi, outValue, resolveRefs);
        if (block < 0) {
            return false;
        }
        if (outValue.type == TypedValue.TYPE_STRING) {
            outValue.string = mStringBlocks[block].get(outValue.data);
        }
        return true;
    }

    /**
     * Retrieve the text array associated with a particular resource
     * identifier.
     *
     * @param resId the resource id of the string array
     */
    final CharSequence[] getResourceTextArray(@ArrayRes int resId) {
        final int[] rawInfoArray = getArrayStringInfo(resId);
        final int rawInfoArrayLen = rawInfoArray.length;
        final int infoArrayLen = rawInfoArrayLen / 2;
        int block;
        int index;
        final CharSequence[] retArray = new CharSequence[infoArrayLen];
        for (int i = 0, j = 0; i < rawInfoArrayLen; i = i + 2, j++) {
            block = rawInfoArray[i];
            index = rawInfoArray[i + 1];
            retArray[j] = index >= 0 ? mStringBlocks[block].get(index) : null;
        }
        return retArray;
    }

    /**
     * Populates {@code outValue} with the data associated with a particular
     * resource identifier for the current configuration. Resolves theme
     * attributes against the specified theme.
     *
     * @param theme the native pointer of the theme
     * @param resId the resource identifier to load
     * @param outValue the typed value in which to put the data
     * @param resolveRefs {@code true} to resolve references, {@code false}
     *                    to leave them unresolved
     * @return {@code true} if the data was loaded into {@code outValue},
     *         {@code false} otherwise
     */
    final boolean getThemeValue(long theme, @AnyRes int resId, @NonNull TypedValue outValue,
            boolean resolveRefs) {
        final int block = loadThemeAttributeValue(theme, resId, outValue, resolveRefs);
        if (block < 0) {
            return false;
        }
        if (outValue.type == TypedValue.TYPE_STRING) {
            final StringBlock[] blocks = ensureStringBlocks();
            outValue.string = blocks[block].get(outValue.data);
        }
        return true;
    }

    /**
     * Ensures the string blocks are loaded.
     *
     * @return the string blocks
     */
    @NonNull
    final StringBlock[] ensureStringBlocks() {
        synchronized (this) {
            if (mStringBlocks == null) {
                makeStringBlocks(sSystem.mStringBlocks);
            }
            return mStringBlocks;
        }
    }

    /*package*/ final void makeStringBlocks(StringBlock[] seed) {
        final int seedNum = (seed != null) ? seed.length : 0;
        final int num = getStringBlockCount();
        mStringBlocks = new StringBlock[num];
        if (localLOGV) Log.v(TAG, "Making string blocks for " + this
                + ": " + num);
        for (int i=0; i<num; i++) {
            if (i < seedNum) {
                mStringBlocks[i] = seed[i];
            } else {
                mStringBlocks[i] = new StringBlock(getNativeStringBlock(i), true);
            }
        }
    }

    /*package*/ final CharSequence getPooledStringForCookie(int cookie, int id) {
        // Cookies map to string blocks starting at 1.
        return mStringBlocks[cookie - 1].get(id);
    }

    /**
     * Open an asset using ACCESS_STREAMING mode.  This provides access to
     * files that have been bundled with an application as assets -- that is,
     * files placed in to the "assets" directory.
     * 
     * @param fileName The name of the asset to open.  This name can be
     *                 hierarchical.
     * 
     * @see #open(String, int)
     * @see #list
     */
    public final InputStream open(String fileName) throws IOException {
        return open(fileName, ACCESS_STREAMING);
    }

    /**
     * Open an asset using an explicit access mode, returning an InputStream to
     * read its contents.  This provides access to files that have been bundled
     * with an application as assets -- that is, files placed in to the
     * "assets" directory.
     * 
     * @param fileName The name of the asset to open.  This name can be
     *                 hierarchical.
     * @param accessMode Desired access mode for retrieving the data.
     * 
     * @see #ACCESS_UNKNOWN
     * @see #ACCESS_STREAMING
     * @see #ACCESS_RANDOM
     * @see #ACCESS_BUFFER
     * @see #open(String)
     * @see #list
     */
    public final InputStream open(String fileName, int accessMode)
        throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            long asset = openAsset(fileName, accessMode);
            if (asset != 0) {
                AssetInputStream res = new AssetInputStream(asset);
                incRefsLocked(res.hashCode());
                return res;
            }
        }
        throw new FileNotFoundException("Asset file: " + fileName);
    }

    public final AssetFileDescriptor openFd(String fileName)
            throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            ParcelFileDescriptor pfd = openAssetFd(fileName, mOffsets);
            if (pfd != null) {
                return new AssetFileDescriptor(pfd, mOffsets[0], mOffsets[1]);
            }
        }
        throw new FileNotFoundException("Asset file: " + fileName);
    }

    /**
     * Return a String array of all the assets at the given path.
     * 
     * @param path A relative path within the assets, i.e., "docs/home.html".
     * 
     * @return String[] Array of strings, one for each asset.  These file
     *         names are relative to 'path'.  You can open the file by
     *         concatenating 'path' and a name in the returned string (via
     *         File) and passing that to open().
     * 
     * @see #open
     */
    public native final String[] list(String path)
        throws IOException;

    /**
     * {@hide}
     * Open a non-asset file as an asset using ACCESS_STREAMING mode.  This
     * provides direct access to all of the files included in an application
     * package (not only its assets).  Applications should not normally use
     * this.
     * 
     * @see #open(String)
     */
    public final InputStream openNonAsset(String fileName) throws IOException {
        return openNonAsset(0, fileName, ACCESS_STREAMING);
    }

    /**
     * {@hide}
     * Open a non-asset file as an asset using a specific access mode.  This
     * provides direct access to all of the files included in an application
     * package (not only its assets).  Applications should not normally use
     * this.
     * 
     * @see #open(String, int)
     */
    public final InputStream openNonAsset(String fileName, int accessMode)
        throws IOException {
        return openNonAsset(0, fileName, accessMode);
    }

    /**
     * {@hide}
     * Open a non-asset in a specified package.  Not for use by applications.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     */
    public final InputStream openNonAsset(int cookie, String fileName)
        throws IOException {
        return openNonAsset(cookie, fileName, ACCESS_STREAMING);
    }

    /**
     * {@hide}
     * Open a non-asset in a specified package.  Not for use by applications.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     * @param accessMode Desired access mode for retrieving the data.
     */
    public final InputStream openNonAsset(int cookie, String fileName, int accessMode)
        throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            long asset = openNonAssetNative(cookie, fileName, accessMode);
            if (asset != 0) {
                AssetInputStream res = new AssetInputStream(asset);
                incRefsLocked(res.hashCode());
                return res;
            }
        }
        throw new FileNotFoundException("Asset absolute file: " + fileName);
    }

    public final AssetFileDescriptor openNonAssetFd(String fileName)
            throws IOException {
        return openNonAssetFd(0, fileName);
    }
    
    public final AssetFileDescriptor openNonAssetFd(int cookie,
            String fileName) throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            ParcelFileDescriptor pfd = openNonAssetFdNative(cookie,
                    fileName, mOffsets);
            if (pfd != null) {
                return new AssetFileDescriptor(pfd, mOffsets[0], mOffsets[1]);
            }
        }
        throw new FileNotFoundException("Asset absolute file: " + fileName);
    }
    
    /**
     * Retrieve a parser for a compiled XML file.
     * 
     * @param fileName The name of the file to retrieve.
     */
    public final XmlResourceParser openXmlResourceParser(String fileName)
            throws IOException {
        return openXmlResourceParser(0, fileName);
    }
    
    /**
     * Retrieve a parser for a compiled XML file.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName The name of the file to retrieve.
     */
    public final XmlResourceParser openXmlResourceParser(int cookie,
            String fileName) throws IOException {
        XmlBlock block = openXmlBlockAsset(cookie, fileName);
        XmlResourceParser rp = block.newParser();
        block.close();
        return rp;
    }

    /**
     * {@hide}
     * Retrieve a non-asset as a compiled XML file.  Not for use by
     * applications.
     * 
     * @param fileName The name of the file to retrieve.
     */
    /*package*/ final XmlBlock openXmlBlockAsset(String fileName)
            throws IOException {
        return openXmlBlockAsset(0, fileName);
    }

    /**
     * {@hide}
     * Retrieve a non-asset as a compiled XML file.  Not for use by
     * applications.
     * 
     * @param cookie Identifier of the package to be opened.
     * @param fileName Name of the asset to retrieve.
     */
    /*package*/ final XmlBlock openXmlBlockAsset(int cookie, String fileName)
        throws IOException {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            long xmlBlock = openXmlAssetNative(cookie, fileName);
            if (xmlBlock != 0) {
                XmlBlock res = new XmlBlock(this, xmlBlock);
                incRefsLocked(res.hashCode());
                return res;
            }
        }
        throw new FileNotFoundException("Asset XML file: " + fileName);
    }

    /*package*/ void xmlBlockGone(int id) {
        synchronized (this) {
            decRefsLocked(id);
        }
    }

    /*package*/ final long createTheme() {
        synchronized (this) {
            if (!mOpen) {
                throw new RuntimeException("Assetmanager has been closed");
            }
            long res = newTheme();
            incRefsLocked(res);
            return res;
        }
    }

    /*package*/ final void releaseTheme(long theme) {
        synchronized (this) {
            deleteTheme(theme);
            decRefsLocked(theme);
        }
    }

    protected void finalize() throws Throwable {
        try {
            if (DEBUG_REFS && mNumRefs != 0) {
                Log.w(TAG, "AssetManager " + this
                        + " finalized with non-zero refs: " + mNumRefs);
                if (mRefStacks != null) {
                    for (RuntimeException e : mRefStacks.values()) {
                        Log.w(TAG, "Reference from here", e);
                    }
                }
            }
            destroy();
        } finally {
            super.finalize();
        }
    }
    
    public final class AssetInputStream extends InputStream {
        /**
         * @hide
         */
        public final int getAssetInt() {
            throw new UnsupportedOperationException();
        }
        /**
         * @hide
         */
        public final long getNativeAsset() {
            return mAsset;
        }
        private AssetInputStream(long asset)
        {
            mAsset = asset;
            mLength = getAssetLength(asset);
        }
        public final int read() throws IOException {
            return readAssetChar(mAsset);
        }
        public final boolean markSupported() {
            return true;
        }
        public final int available() throws IOException {
            long len = getAssetRemainingLength(mAsset);
            return len > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)len;
        }
        public final void close() throws IOException {
            synchronized (AssetManager.this) {
                if (mAsset != 0) {
                    destroyAsset(mAsset);
                    mAsset = 0;
                    decRefsLocked(hashCode());
                }
            }
        }
        public final void mark(int readlimit) {
            mMarkPos = seekAsset(mAsset, 0, 0);
        }
        public final void reset() throws IOException {
            seekAsset(mAsset, mMarkPos, -1);
        }
        public final int read(byte[] b) throws IOException {
            return readAsset(mAsset, b, 0, b.length);
        }
        public final int read(byte[] b, int off, int len) throws IOException {
            return readAsset(mAsset, b, off, len);
        }
        public final long skip(long n) throws IOException {
            long pos = seekAsset(mAsset, 0, 0);
            if ((pos+n) > mLength) {
                n = mLength-pos;
            }
            if (n > 0) {
                seekAsset(mAsset, n, 0);
            }
            return n;
        }

        protected void finalize() throws Throwable
        {
            close();
        }

        private long mAsset;
        private long mLength;
        private long mMarkPos;
    }

    /**
     * Add an additional set of assets to the asset manager.  This can be
     * either a directory or ZIP file.  Not for use by applications.  Returns
     * the cookie of the added asset, or 0 on failure.
     * {@hide}
     */
    public final int addAssetPath(String path) {
        return  addAssetPathInternal(path, false);
    }

    /**
     * Add an application assets to the asset manager and loading it as shared library.
     * This can be either a directory or ZIP file.  Not for use by applications.  Returns
     * the cookie of the added asset, or 0 on failure.
     * {@hide}
     */
    public final int addAssetPathAsSharedLibrary(String path) {
        return addAssetPathInternal(path, true);
    }

    private final int addAssetPathInternal(String path, boolean appAsLib) {
        synchronized (this) {
            int res = addAssetPathNative(path, appAsLib);
            makeStringBlocks(mStringBlocks);
            return res;
        }
    }

    private native final int addAssetPathNative(String path, boolean appAsLib);

     /**
     * Add a set of assets to overlay an already added set of assets.
     *
     * This is only intended for application resources. System wide resources
     * are handled before any Java code is executed.
     *
     * {@hide}
     */

    public final int addOverlayPath(String idmapPath) {
        synchronized (this) {
            int res = addOverlayPathNative(idmapPath);
            makeStringBlocks(mStringBlocks);
            return res;
        }
    }

    /**
     * See addOverlayPath.
     *
     * {@hide}
     */
    public native final int addOverlayPathNative(String idmapPath);

    /**
     * Add multiple sets of assets to the asset manager at once.  See
     * {@link #addAssetPath(String)} for more information.  Returns array of
     * cookies for each added asset with 0 indicating failure, or null if
     * the input array of paths is null.
     * {@hide}
     */
    public final int[] addAssetPaths(String[] paths) {
        if (paths == null) {
            return null;
        }

        int[] cookies = new int[paths.length];
        for (int i = 0; i < paths.length; i++) {
            cookies[i] = addAssetPath(paths[i]);
        }

        return cookies;
    }

    /**
     * Determine whether the state in this asset manager is up-to-date with
     * the files on the filesystem.  If false is returned, you need to
     * instantiate a new AssetManager class to see the new data.
     * {@hide}
     */
    public native final boolean isUpToDate();

    /**
     * Get the locales that this asset manager contains data for.
     *
     * <p>On SDK 21 (Android 5.0: Lollipop) and above, Locale strings are valid
     * <a href="https://tools.ietf.org/html/bcp47">BCP-47</a> language tags and can be
     * parsed using {@link java.util.Locale#forLanguageTag(String)}.
     *
     * <p>On SDK 20 (Android 4.4W: Kitkat for watches) and below, locale strings
     * are of the form {@code ll_CC} where {@code ll} is a two letter language code,
     * and {@code CC} is a two letter country code.
     */
    public native final String[] getLocales();

    /**
     * Same as getLocales(), except that locales that are only provided by the system (i.e. those
     * present in framework-res.apk or its overlays) will not be listed.
     *
     * For example, if the "system" assets support English, French, and German, and the additional
     * assets support Cherokee and French, getLocales() would return
     * [Cherokee, English, French, German], while getNonSystemLocales() would return
     * [Cherokee, French].
     * {@hide}
     */
    public native final String[] getNonSystemLocales();

    /** {@hide} */
    public native final Configuration[] getSizeConfigurations();

    /**
     * Change the configuation used when retrieving resources.  Not for use by
     * applications.
     * {@hide}
     */
    public native final void setConfiguration(int mcc, int mnc, String locale,
            int orientation, int touchscreen, int density, int keyboard,
            int keyboardHidden, int navigation, int screenWidth, int screenHeight,
            int smallestScreenWidthDp, int screenWidthDp, int screenHeightDp,
            int screenLayout, int uiMode, int majorVersion);

    /**
     * Retrieve the resource identifier for the given resource name.
     */
    /*package*/ native final int getResourceIdentifier(String type,
                                                       String name,
                                                       String defPackage);

    /*package*/ native final String getResourceName(int resid);
    /*package*/ native final String getResourcePackageName(int resid);
    /*package*/ native final String getResourceTypeName(int resid);
    /*package*/ native final String getResourceEntryName(int resid);
    
    private native final long openAsset(String fileName, int accessMode);
    private final native ParcelFileDescriptor openAssetFd(String fileName,
            long[] outOffsets) throws IOException;
    private native final long openNonAssetNative(int cookie, String fileName,
            int accessMode);
    private native ParcelFileDescriptor openNonAssetFdNative(int cookie,
            String fileName, long[] outOffsets) throws IOException;
    private native final void destroyAsset(long asset);
    private native final int readAssetChar(long asset);
    private native final int readAsset(long asset, byte[] b, int off, int len);
    private native final long seekAsset(long asset, long offset, int whence);
    private native final long getAssetLength(long asset);
    private native final long getAssetRemainingLength(long asset);

    /** Returns true if the resource was found, filling in mRetStringBlock and
     *  mRetData. */
    private native final int loadResourceValue(int ident, short density, TypedValue outValue,
            boolean resolve);
    /** Returns true if the resource was found, filling in mRetStringBlock and
     *  mRetData. */
    private native final int loadResourceBagValue(int ident, int bagEntryId, TypedValue outValue,
                                               boolean resolve);
    /*package*/ static final int STYLE_NUM_ENTRIES = 6;
    /*package*/ static final int STYLE_TYPE = 0;
    /*package*/ static final int STYLE_DATA = 1;
    /*package*/ static final int STYLE_ASSET_COOKIE = 2;
    /*package*/ static final int STYLE_RESOURCE_ID = 3;

    /* Offset within typed data array for native changingConfigurations. */
    static final int STYLE_CHANGING_CONFIGURATIONS = 4;

    /*package*/ static final int STYLE_DENSITY = 5;
    /*package*/ native static final boolean applyStyle(long theme,
            int defStyleAttr, int defStyleRes, long xmlParser,
            int[] inAttrs, int[] outValues, int[] outIndices);
    /*package*/ native static final boolean resolveAttrs(long theme,
            int defStyleAttr, int defStyleRes, int[] inValues,
            int[] inAttrs, int[] outValues, int[] outIndices);
    /*package*/ native final boolean retrieveAttributes(
            long xmlParser, int[] inAttrs, int[] outValues, int[] outIndices);
    /*package*/ native final int getArraySize(int resource);
    /*package*/ native final int retrieveArray(int resource, int[] outValues);
    private native final int getStringBlockCount();
    private native final long getNativeStringBlock(int block);

    /**
     * {@hide}
     */
    public native final String getCookieName(int cookie);

    /**
     * {@hide}
     */
    public native final SparseArray<String> getAssignedPackageIdentifiers();

    /**
     * {@hide}
     */
    public native static final int getGlobalAssetCount();
    
    /**
     * {@hide}
     */
    public native static final String getAssetAllocations();
    
    /**
     * {@hide}
     */
    public native static final int getGlobalAssetManagerCount();
    
    private native final long newTheme();
    private native final void deleteTheme(long theme);
    /*package*/ native static final void applyThemeStyle(long theme, int styleRes, boolean force);
    /*package*/ native static final void copyTheme(long dest, long source);
    /*package*/ native static final void clearTheme(long theme);
    /*package*/ native static final int loadThemeAttributeValue(long theme, int ident,
                                                                TypedValue outValue,
                                                                boolean resolve);
    /*package*/ native static final void dumpTheme(long theme, int priority, String tag, String prefix);
    /*package*/ native static final @NativeConfig int getThemeChangingConfigurations(long theme);

    private native final long openXmlAssetNative(int cookie, String fileName);

    private native final String[] getArrayStringResource(int arrayRes);
    private native final int[] getArrayStringInfo(int arrayRes);
    /*package*/ native final int[] getArrayIntResource(int arrayRes);
    /*package*/ native final int[] getStyleAttributes(int themeRes);

    private native final void init(boolean isSystem);
    private native final void destroy();

    private final void incRefsLocked(long id) {
        if (DEBUG_REFS) {
            if (mRefStacks == null) {
                mRefStacks = new HashMap<Long, RuntimeException>();
            }
            RuntimeException ex = new RuntimeException();
            ex.fillInStackTrace();
            mRefStacks.put(id, ex);
        }
        mNumRefs++;
    }
    
    private final void decRefsLocked(long id) {
        if (DEBUG_REFS && mRefStacks != null) {
            mRefStacks.remove(id);
        }
        mNumRefs--;
        //System.out.println("Dec streams: mNumRefs=" + mNumRefs
        //                   + " mReleased=" + mReleased);
        if (mNumRefs == 0) {
            destroy();
        }
    }
}
