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

package android.graphics;

import static android.content.res.FontResourcesParser.ProviderResourceEntry;
import static android.content.res.FontResourcesParser.FontFileResourceEntry;
import static android.content.res.FontResourcesParser.FontFamilyFilesResourceEntry;
import static android.content.res.FontResourcesParser.FamilyResourceEntry;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.FontListParser;
import android.graphics.fonts.FontRequest;
import android.graphics.fonts.FontResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.provider.FontsContract;
import android.text.FontConfig;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.LruCache;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Typeface class specifies the typeface and intrinsic style of a font.
 * This is used in the paint, along with optionally Paint settings like
 * textSize, textSkewX, textScaleX to specify
 * how text appears when drawn (and measured).
 */
public class Typeface {

    private static String TAG = "Typeface";

    /** The default NORMAL typeface object */
    public static final Typeface DEFAULT;
    /**
     * The default BOLD typeface object. Note: this may be not actually be
     * bold, depending on what fonts are installed. Call getStyle() to know
     * for sure.
     */
    public static final Typeface DEFAULT_BOLD;
    /** The NORMAL style of the default sans serif typeface. */
    public static final Typeface SANS_SERIF;
    /** The NORMAL style of the default serif typeface. */
    public static final Typeface SERIF;
    /** The NORMAL style of the default monospace typeface. */
    public static final Typeface MONOSPACE;

    static Typeface[] sDefaults;
    private static final LongSparseArray<SparseArray<Typeface>> sTypefaceCache =
            new LongSparseArray<>(3);
    @GuardedBy("sLock")
    private static FontsContract sFontsContract;
    @GuardedBy("sLock")
    private static Handler sHandler;

    /**
     * Cache for Typeface objects dynamically loaded from assets. Currently max size is 16.
     */
    private static final LruCache<String, Typeface> sDynamicTypefaceCache = new LruCache<>(16);

    static Typeface sDefaultTypeface;
    static Map<String, Typeface> sSystemFontMap;
    static FontFamily[] sFallbackFonts;
    private static final Object sLock = new Object();

    static final String FONTS_CONFIG = "fonts.xml";

    /**
     * @hide
     */
    public long native_instance;

    // Style
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;

    private int mStyle = 0;

    private int[] mSupportedAxes;
    private static final int[] EMPTY_AXES = {};

    private static void setDefault(Typeface t) {
        sDefaultTypeface = t;
        nativeSetDefault(t.native_instance);
    }

    /** Returns the typeface's intrinsic style attributes */
    public int getStyle() {
        return mStyle;
    }

    /** Returns true if getStyle() has the BOLD bit set. */
    public final boolean isBold() {
        return (mStyle & BOLD) != 0;
    }

    /** Returns true if getStyle() has the ITALIC bit set. */
    public final boolean isItalic() {
        return (mStyle & ITALIC) != 0;
    }

    /**
     * @hide
     * Used by Resources to load a font resource of type font file.
     */
    @Nullable
    public static Typeface createFromResources(AssetManager mgr, String path, int cookie) {
        if (sFallbackFonts != null) {
            synchronized (sDynamicTypefaceCache) {
                final String key = Builder.createAssetUid(
                        mgr, path, 0 /* ttcIndex */, null /* axes */);
                Typeface typeface = sDynamicTypefaceCache.get(key);
                if (typeface != null) return typeface;

                FontFamily fontFamily = new FontFamily();
                // TODO: introduce ttc index and variation settings to resource type font.
                if (fontFamily.addFontFromAssetManager(mgr, path, cookie, false /* isAsset */,
                        0 /* ttcIndex */, Builder.RESOLVE_BY_FONT_TABLE /* weight */,
                        Builder.RESOLVE_BY_FONT_TABLE /* italic */, null /* axes */)) {
                    fontFamily.freeze();
                    FontFamily[] families = {fontFamily};
                    typeface = createFromFamiliesWithDefault(families);
                    sDynamicTypefaceCache.put(key, typeface);
                    return typeface;
                }
            }
        }
        return null;
    }

    /**
     * @hide
     * Used by Resources to load a font resource of type xml.
     */
    @Nullable
    public static Typeface createFromResources(
            FamilyResourceEntry entry, AssetManager mgr, String path) {
        if (sFallbackFonts != null) {
            Typeface typeface = findFromCache(mgr, path);
            if (typeface != null) return typeface;

            if (entry instanceof ProviderResourceEntry) {
                final ProviderResourceEntry providerEntry = (ProviderResourceEntry) entry;
                // Downloadable font
                typeface = findFromCache(providerEntry.getAuthority(), providerEntry.getQuery());
                if (typeface != null) {
                    return typeface;
                }
                // Downloaded font and it wasn't cached, request it again and return a
                // default font instead (nothing we can do now).
                create(new FontRequest(providerEntry.getAuthority(), providerEntry.getPackage(),
                        providerEntry.getQuery()), NO_OP_REQUEST_CALLBACK);
                return DEFAULT;
            }

            // family is FontFamilyFilesResourceEntry
            final FontFamilyFilesResourceEntry filesEntry =
                    (FontFamilyFilesResourceEntry) entry;

            FontFamily fontFamily = new FontFamily();
            for (final FontFileResourceEntry fontFile : filesEntry.getEntries()) {
                if (!fontFamily.addFontFromAssetManager(mgr, fontFile.getFileName(),
                        0 /* resourceCookie */, false /* isAsset */, 0 /* ttcIndex */,
                        fontFile.getWeight(),
                        fontFile.isItalic() ? Builder.ITALIC : Builder.NORMAL,
                        null /* axes */)) {
                    return null;
                }
            }
            fontFamily.freeze();
            FontFamily[] familyChain = { fontFamily };
            typeface = createFromFamiliesWithDefault(familyChain);
            synchronized (sDynamicTypefaceCache) {
                final String key = Builder.createAssetUid(mgr, path, 0 /* ttcIndex */,
                        null /* axes */);
                sDynamicTypefaceCache.put(key, typeface);
            }
            return typeface;
        }
        return null;
    }

    /**
     * Used by resources for cached loading if the font is available.
     * @hide
     */
    public static Typeface findFromCache(AssetManager mgr, String path) {
        synchronized (sDynamicTypefaceCache) {
            final String key = Builder.createAssetUid(mgr, path, 0 /* ttcIndex */, null /* axes */);
            Typeface typeface = sDynamicTypefaceCache.get(key);
            if (typeface != null) {
                return typeface;
            }
        }
        return null;
    }

    /**
     * Set the application context so we can generate font requests from the provider. This should
     * be called from ActivityThread when the application binds, as we preload fonts.
     * @hide
     */
    public static void setApplicationContext(Context context) {
        synchronized (sLock) {
            if (sFontsContract == null) {
                sFontsContract = new FontsContract(context);
                sHandler = new Handler();
            }
        }
    }

    /**
     * Create a typeface object given a font request. The font will be asynchronously fetched,
     * therefore the result is delivered to the given callback. See {@link FontRequest}.
     * Only one of the methods in callback will be invoked, depending on whether the request
     * succeeds or fails. These calls will happen on the main thread.
     * @param request A {@link FontRequest} object that identifies the provider and query for the
     *                request. May not be null.
     * @param callback A callback that will be triggered when results are obtained. May not be null.
     */
    public static void create(@NonNull FontRequest request, @NonNull FontRequestCallback callback) {
        // Check the cache first
        // TODO: would the developer want to avoid a cache hit and always ask for the freshest
        // result?
        Typeface cachedTypeface = findFromCache(
                request.getProviderAuthority(), request.getQuery());
        if (cachedTypeface != null) {
            sHandler.post(() -> callback.onTypefaceRetrieved(cachedTypeface));
            return;
        }
        synchronized (sLock) {
            if (sFontsContract == null) {
                throw new RuntimeException("Context not initialized, can't query provider");
            }
            final ResultReceiver receiver = new ResultReceiver(null) {
                @Override
                public void onReceiveResult(int resultCode, Bundle resultData) {
                    sHandler.post(() -> receiveResult(request, callback, resultCode, resultData));
                }
            };
            sFontsContract.getFont(request, receiver);
        }
    }

    private static Typeface findFromCache(String providerAuthority, String query) {
        synchronized (sDynamicTypefaceCache) {
            final String key = createProviderUid(providerAuthority, query);
            Typeface typeface = sDynamicTypefaceCache.get(key);
            if (typeface != null) {
                return typeface;
            }
        }
        return null;
    }

    private static void receiveResult(FontRequest request, FontRequestCallback callback,
            int resultCode, Bundle resultData) {
        Typeface cachedTypeface = findFromCache(
                request.getProviderAuthority(), request.getQuery());
        if (cachedTypeface != null) {
            // We already know the result.
            // Probably the requester requests the same font again in a short interval.
            callback.onTypefaceRetrieved(cachedTypeface);
            return;
        }
        if (resultCode != FontsContract.Columns.RESULT_CODE_OK) {
            callback.onTypefaceRequestFailed(resultCode);
            return;
        }
        if (resultData == null) {
            callback.onTypefaceRequestFailed(
                    FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND);
            return;
        }
        List<FontResult> resultList =
                resultData.getParcelableArrayList(FontsContract.PARCEL_FONT_RESULTS);
        if (resultList == null || resultList.isEmpty()) {
            callback.onTypefaceRequestFailed(
                    FontRequestCallback.FAIL_REASON_FONT_NOT_FOUND);
            return;
        }
        FontFamily fontFamily = new FontFamily();
        for (int i = 0; i < resultList.size(); ++i) {
            FontResult result = resultList.get(i);
            ParcelFileDescriptor fd = result.getFileDescriptor();
            if (fd == null) {
                callback.onTypefaceRequestFailed(
                        FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR);
                return;
            }
            try (FileInputStream is = new FileInputStream(fd.getFileDescriptor())) {
                FileChannel fileChannel = is.getChannel();
                long fontSize = fileChannel.size();
                ByteBuffer fontBuffer = fileChannel.map(
                        FileChannel.MapMode.READ_ONLY, 0, fontSize);
                int style = result.getStyle();
                int weight = (style & BOLD) != 0 ? 700 : 400;
                // TODO: this method should be
                // create(fd, ttcIndex, fontVariationSettings, style).
                if (!fontFamily.addFontFromBuffer(fontBuffer, result.getTtcIndex(),
                                null, weight,
                                (style & ITALIC) == 0 ? Builder.NORMAL : Builder.ITALIC)) {
                    Log.e(TAG, "Error creating font " + request.getQuery());
                    callback.onTypefaceRequestFailed(
                            FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR);
                    return;
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading font " + request.getQuery(), e);
                callback.onTypefaceRequestFailed(
                        FontRequestCallback.FAIL_REASON_FONT_LOAD_ERROR);
                return;
            } finally {
                IoUtils.closeQuietly(fd);
            }
        }
        fontFamily.freeze();
        Typeface typeface = Typeface.createFromFamiliesWithDefault(new FontFamily[] { fontFamily });
        synchronized (sDynamicTypefaceCache) {
            String key = createProviderUid(request.getProviderAuthority(), request.getQuery());
            sDynamicTypefaceCache.put(key, typeface);
        }
        callback.onTypefaceRetrieved(typeface);
    }

    /**
     * Interface used to receive asynchronously fetched typefaces.
     */
    public interface FontRequestCallback {
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the given
         * provider was not found on the device.
         */
        int FAIL_REASON_PROVIDER_NOT_FOUND = FontsContract.RESULT_CODE_PROVIDER_NOT_FOUND;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the given
         * provider must be authenticated and the given certificates do not match its signature.
         */
        int FAIL_REASON_WRONG_CERTIFICATES = FontsContract.RESULT_CODE_WRONG_CERTIFICATES;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the font
         * returned by the provider was not loaded properly.
         */
        int FAIL_REASON_FONT_LOAD_ERROR = -3;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the font
         * provider did not return any results for the given query.
         */
        int FAIL_REASON_FONT_NOT_FOUND = FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the font
         * provider found the queried font, but it is currently unavailable.
         */
        int FAIL_REASON_FONT_UNAVAILABLE = FontsContract.Columns.RESULT_CODE_FONT_UNAVAILABLE;
        /**
         * Constant returned by {@link #onTypefaceRequestFailed(int)} signaling that the given
         * query was not supported by the provider.
         */
        int FAIL_REASON_MALFORMED_QUERY = FontsContract.Columns.RESULT_CODE_MALFORMED_QUERY;

        /** @hide */
        @IntDef({ FAIL_REASON_PROVIDER_NOT_FOUND, FAIL_REASON_FONT_LOAD_ERROR,
                FAIL_REASON_FONT_NOT_FOUND, FAIL_REASON_FONT_UNAVAILABLE,
                FAIL_REASON_MALFORMED_QUERY })
        @Retention(RetentionPolicy.SOURCE)
        @interface FontRequestFailReason {}

        /**
         * Called then a Typeface request done via {@link Typeface#create(FontRequest,
         * FontRequestCallback)} is complete. Note that this method will not be called if
         * {@link #onTypefaceRequestFailed(int)} is called instead.
         * @param typeface  The Typeface object retrieved.
         */
        void onTypefaceRetrieved(Typeface typeface);

        /**
         * Called when a Typeface request done via {@link Typeface#create(FontRequest,
         * FontRequestCallback)} fails.
         * @param reason One of {@link #FAIL_REASON_PROVIDER_NOT_FOUND},
         *               {@link #FAIL_REASON_FONT_NOT_FOUND},
         *               {@link #FAIL_REASON_FONT_LOAD_ERROR},
         *               {@link #FAIL_REASON_FONT_UNAVAILABLE} or
         *               {@link #FAIL_REASON_MALFORMED_QUERY}.
         */
        void onTypefaceRequestFailed(@FontRequestFailReason int reason);
    }

    private static final FontRequestCallback NO_OP_REQUEST_CALLBACK = new FontRequestCallback() {
        @Override
        public void onTypefaceRetrieved(Typeface typeface) {
            // Do nothing.
        }

        @Override
        public void onTypefaceRequestFailed(@FontRequestFailReason int reason) {
            // Do nothing.
        }
    };

    /**
     * A builder class for creating new Typeface instance.
     *
     * Examples,
     * 1) Create Typeface from ttf file.
     * <pre>
     * <code>
     * Typeface.Builder buidler = new Typeface.Builder.obtain();
     * builder.setSourceFromFilePath("your_font_file.ttf");
     * Typeface typeface = builder.build();
     * builder.recycle();
     * </code>
     * </pre>
     *
     * 2) Create Typeface from ttc file in assets directory.
     * <pre>
     * <code>
     * Typeface.Builder buidler = new Typeface.Builder.obtain();
     * builder.setSourceFromAsset(getAssets(), "your_font_file.ttc");
     * builder.setTtcIndex(2);  // set index of font collection.
     * Typeface typeface = builder.build();
     * builder.recycle();
     * </code>
     * </pre>
     *
     * 3) Create Typeface from existing Typeface with variation settings.
     * <pre>
     *
     * <p>Note that only one source can be specified for the single Typeface.</p>
     */
    public static final class Builder {
        /**
         * Value for weight and italic.
         *
         * Indicates the value is resolved by font metadata.
         */
        // Must be same with C++ constant in core/jni/android/graphics/FontFamily.cpp
        public static final int RESOLVE_BY_FONT_TABLE = -1;

        /**
         * Value for italic.
         *
         * Indicates the font style is not italic.
         */
        public static final int NORMAL = 0;

        /**
         * Value for italic.
         *
         * Indicates the font style is italic.
         */
        public static final int ITALIC = 1;

        private int mTtcIndex;
        private FontConfig.Axis[] mAxes;

        private AssetManager mAssetManager;
        private String mPath;
        private FileDescriptor mFd;
        private @IntRange(from = -1) int mWeight = RESOLVE_BY_FONT_TABLE;

        /** @hide */
        @Retention(SOURCE)
        @IntDef({RESOLVE_BY_FONT_TABLE, NORMAL, ITALIC})
        public @interface Italic {}
        private @Italic int mItalic = RESOLVE_BY_FONT_TABLE;

        private boolean mHasSourceSet = false;
        private boolean mRecycled = false;

        /** Use Builder.obtain() instead */
        private void Builder() {}

        private static AtomicReference<Builder> mCache = new AtomicReference<>();

        /**
         * Returns Typeface.Builder from pool.
         */
        public static Builder obtain() {
            final Builder builder = mCache.getAndSet(null);
            if (builder != null) {
                builder.mRecycled = false;
                return builder;
            }
            return new Builder();
        }

        /**
         * Resets the internal states.
         */
        public void reset() {
            checkNotRecycled();
            mTtcIndex = 0;
            mAxes = null;

            mAssetManager = null;
            mPath = null;
            mFd = null;

            mWeight = RESOLVE_BY_FONT_TABLE;
            mItalic = RESOLVE_BY_FONT_TABLE;

            mHasSourceSet = false;
        }

        /**
         * Returns the instance to the pool.
         */
        public void recycle() {
            reset();
            mRecycled = true;

            mCache.compareAndSet(null, this);
        }

        private void checkNotRecycled() {
            if (mRecycled) {
                throw new IllegalStateException("Don't use Builder after calling recycle()");
            }
        }

        private void checkSingleFontSource() {
            if (mHasSourceSet) {
                throw new IllegalStateException("Typeface can only built with single font source.");
            }
        }

        /**
         * Sets a font file as a source of Typeface.
         *
         * @param path The file object refers to the font file.
         */
        public Builder setSourceFromFile(@NonNull File path) {
            return setSourceFromFilePath(path.getAbsolutePath());
        }

        /**
         * Sets a font file as a source of Typeface.
         *
         * @param fd The file descriptor. The passed fd must be mmap-able.
         */
        public Builder setSourceFromFile(@NonNull FileDescriptor fd) {
            checkNotRecycled();
            checkSingleFontSource();
            mFd = fd;
            mHasSourceSet = true;
            return this;
        }

        /**
         * Sets a font file as a source of Typeface.
         *
         * @param path The full path to the font file.
         */
        public Builder setSourceFromFilePath(@NonNull String path) {
            checkNotRecycled();
            checkSingleFontSource();
            mPath = path;
            mHasSourceSet = true;
            return this;
        }

        /**
         * Sets an asset entry as a source of Typeface.
         *
         * @param assetManager The application's asset manager
         * @param path The file name of the font data in the asset directory
         */
        public Builder setSourceFromAsset(@NonNull AssetManager assetManager,
                @NonNull String path) {
            checkNotRecycled();
            checkSingleFontSource();
            mAssetManager = Preconditions.checkNotNull(assetManager);
            mPath = Preconditions.checkStringNotEmpty(path);
            mHasSourceSet = true;
            return this;
        }

        /**
         * Sets weight of the font.
         *
         * By passing {@link #RESOLVE_BY_FONT_TABLE}, weight value is resolved by OS/2 table in
         * font file if possible.
         * @param weight a weight value or {@link #RESOLVE_BY_FONT_TABLE}
         */
        public Builder setWeight(@IntRange(from = -1) int weight) {
            checkNotRecycled();
            mWeight = weight;
            return this;
        }

        /**
         * Sets italic information of the font.
         *
         * By passing {@link #RESOLVE_BY_FONT_TABLE}, italic or normal is determined by OS/2 table
         * in font file if possible.
         * @param italic One of {@link #NORMAL}, {@link #ITALIC}, {@link #RESOLVE_BY_FONT_TABLE}.
         *                 will be used.
         */
        public Builder setItalic(@Italic int italic) {
            checkNotRecycled();
            mItalic = italic;
            return this;
        }

        /**
         * Sets an idex of the font collection.
         *
         * Can not be used for Typeface source. build() method will return null for invalid index.
         * @param ttcIndex An index of the font collection. If the font source is not font
         *                 collection, do not call this method or specify 0.
         */
        public Builder setTtcIndex(@IntRange(from = 0) int ttcIndex) {
            checkNotRecycled();
            mTtcIndex = ttcIndex;
            return this;
        }

        /**
         * Sets a font variation settings.
         *
         * @param variationSettings See {@link android.widget.TextView#setFontVariationSettings}.
         */
        public Builder setFontVariationSettings(@Nullable String variationSettings) {
            checkNotRecycled();
            if (mAxes != null) {
                throw new IllegalStateException("Font variation settings are already set.");
            }
            final List<FontConfig.Axis> axesList = FontListParser.parseFontVariationSettings(
                    variationSettings);
            mAxes = axesList.toArray(new FontConfig.Axis[axesList.size()]);
            return this;
        }

        /**
         * Sets a font variation settings.
         *
         * @param axes An array of font variation axis tag-value pairs.
         */
        public Builder setFontVariationSettings(@Nullable FontConfig.Axis[] axes) {
            checkNotRecycled();
            if (mAxes != null) {
                throw new IllegalStateException("Font variation settings are already set.");
            }
            mAxes = axes;
            return this;
        }

        /**
         * Creates a unique id for a given AssetManager and asset path.
         *
         * @param mgr  AssetManager instance
         * @param path The path for the asset.
         * @param ttcIndex The TTC index for the font.
         * @param axes The font variation settings.
         * @return Unique id for a given AssetManager and asset path.
         */
        private static String createAssetUid(final AssetManager mgr, String path, int ttcIndex,
                @Nullable FontConfig.Axis[] axes) {
            final SparseArray<String> pkgs = mgr.getAssignedPackageIdentifiers();
            final StringBuilder builder = new StringBuilder();
            final int size = pkgs.size();
            for (int i = 0; i < size; i++) {
                builder.append(pkgs.valueAt(i));
                builder.append("-");
            }
            builder.append(path);
            builder.append("-");
            builder.append(Integer.toString(ttcIndex));
            builder.append("-");
            if (axes != null) {
                for (FontConfig.Axis axis : axes) {
                    builder.append(Integer.toHexString(axis.getTag()));
                    builder.append("-");
                    builder.append(Float.toString(axis.getStyleValue()));
                }
            }
            return builder.toString();
        }

        /**
         * Generates new Typeface from specified configuration.
         *
         * @return Newly created Typeface. May return null if some parameters are invalid.
         */
        public Typeface build() {
            checkNotRecycled();
            if (!mHasSourceSet) {
                return null;
            }

            if (mFd != null) {  // set source by setSourceFromFile(FileDescriptor)
                try (FileInputStream fis = new FileInputStream(mFd)) {
                    FileChannel channel = fis.getChannel();
                    long size = channel.size();
                    ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);

                    final FontFamily fontFamily = new FontFamily();
                    if (!fontFamily.addFontFromBuffer(buffer, mTtcIndex, mAxes, mWeight, mItalic)) {
                        fontFamily.abortCreation();
                        return null;
                    }
                    fontFamily.freeze();
                    FontFamily[] families = { fontFamily };
                    return createFromFamiliesWithDefault(families);
                } catch (IOException e) {
                    return null;
                }
            } else if (mAssetManager != null) {  // set source by setSourceFromAsset()
                final String key = createAssetUid(mAssetManager, mPath, mTtcIndex, mAxes);
                synchronized (sDynamicTypefaceCache) {
                    Typeface typeface = sDynamicTypefaceCache.get(key);
                    if (typeface != null) return typeface;
                    final FontFamily fontFamily = new FontFamily();
                    if (!fontFamily.addFontFromAssetManager(mAssetManager, mPath, mTtcIndex,
                            true /* isAsset */, mTtcIndex, mWeight, mItalic, mAxes)) {
                        fontFamily.abortCreation();
                        return null;
                    }
                    fontFamily.freeze();
                    FontFamily[] families = { fontFamily };
                    typeface = createFromFamiliesWithDefault(families);
                    sDynamicTypefaceCache.put(key, typeface);
                    return typeface;
                }
            } else if (mPath != null) {  // set source by setSourceFromFile(File)
                final FontFamily fontFamily = new FontFamily();
                if (!fontFamily.addFont(mPath, mTtcIndex, mAxes, mWeight, mItalic)) {
                    fontFamily.abortCreation();
                    return null;
                }
                fontFamily.freeze();
                FontFamily[] families = { fontFamily };
                return createFromFamiliesWithDefault(families);
            } else {
                throw new IllegalArgumentException("No source was set.");
            }
        }
    }

    /**
     * Create a typeface object given a family name, and option style information.
     * If null is passed for the name, then the "default" font will be chosen.
     * The resulting typeface object can be queried (getStyle()) to discover what
     * its "real" style characteristics are.
     *
     * @param familyName May be null. The name of the font family.
     * @param style  The style (normal, bold, italic) of the typeface.
     *               e.g. NORMAL, BOLD, ITALIC, BOLD_ITALIC
     * @return The best matching typeface.
     */
    public static Typeface create(String familyName, int style) {
        if (sSystemFontMap != null) {
            return create(sSystemFontMap.get(familyName), style);
        }
        return null;
    }

    /**
     * Create a typeface object that best matches the specified existing
     * typeface and the specified Style. Use this call if you want to pick a new
     * style from the same family of an existing typeface object. If family is
     * null, this selects from the default font's family.
     *
     * @param family May be null. The name of the existing type face.
     * @param style  The style (normal, bold, italic) of the typeface.
     *               e.g. NORMAL, BOLD, ITALIC, BOLD_ITALIC
     * @return The best matching typeface.
     */
    public static Typeface create(Typeface family, int style) {
        if (style < 0 || style > 3) {
            style = 0;
        }
        long ni = 0;
        if (family != null) {
            // Return early if we're asked for the same face/style
            if (family.mStyle == style) {
                return family;
            }

            ni = family.native_instance;
        }

        Typeface typeface;
        SparseArray<Typeface> styles = sTypefaceCache.get(ni);

        if (styles != null) {
            typeface = styles.get(style);
            if (typeface != null) {
                return typeface;
            }
        }

        typeface = new Typeface(nativeCreateFromTypeface(ni, style));
        if (styles == null) {
            styles = new SparseArray<Typeface>(4);
            sTypefaceCache.put(ni, styles);
        }
        styles.put(style, typeface);

        return typeface;
    }

    /** @hide */
    public static Typeface createFromTypefaceWithVariation(Typeface family,
            List<FontConfig.Axis> axes) {
        final long ni = family == null ? 0 : family.native_instance;
        return new Typeface(nativeCreateFromTypefaceWithVariation(ni, axes));
    }

    /**
     * Returns one of the default typeface objects, based on the specified style
     *
     * @return the default typeface that corresponds to the style
     */
    public static Typeface defaultFromStyle(int style) {
        return sDefaults[style];
    }

    /**
     * Create a new typeface from the specified font data.
     *
     * @param mgr  The application's asset manager
     * @param path The file name of the font data in the assets directory
     * @return The new typeface.
     */
    public static Typeface createFromAsset(AssetManager mgr, String path) {
        if (path == null) {
            throw new NullPointerException();  // for backward compatibility
        }
        if (sFallbackFonts != null) {
            final Builder builder = Builder.obtain();
            try {
                builder.setSourceFromAsset(mgr, path);
                Typeface typeface = builder.build();
                if (typeface != null) {
                    return typeface;
                }
            } finally {
                builder.recycle();
            }
        }
        // For the compatibility reasons, throw runtime exception if failed to create Typeface.
        throw new RuntimeException("Font asset not found " + path);
    }

    /**
     * Creates a unique id for a given font provider and query.
     */
    private static String createProviderUid(String authority, String query) {
        final StringBuilder builder = new StringBuilder();
        builder.append("provider:");
        builder.append(authority);
        builder.append("-");
        builder.append(query);
        return builder.toString();
    }

    /**
     * Create a new typeface from the specified font file.
     *
     * @param path The path to the font data.
     * @return The new typeface.
     */
    public static Typeface createFromFile(@Nullable File path) {
        // For the compatibility reasons, leaving possible NPE here.
        // See android.graphics.cts.TypefaceTest#testCreateFromFileByFileReferenceNull
        return createFromFile(path.getAbsolutePath());
    }

    /**
     * Create a new typeface from the specified font file.
     *
     * @param path The full path to the font data.
     * @return The new typeface.
     */
    public static Typeface createFromFile(@Nullable String path) {
        if (path == null) {
            // For the compatibility reasons, need to throw NPE if the argument is null.
            // See android.graphics.cts.TypefaceTest#testCreateFromFileByFileNameNull
            throw new NullPointerException();
        }
        if (sFallbackFonts != null) {
            final Builder builder = Builder.obtain();
            try {
                builder.setSourceFromFilePath(path);
                Typeface typeface = builder.build();
                if (typeface != null) {
                    // For the compatibility reasons, throw runtime exception if failed to create
                    // Typeface.
                    return typeface;
                }
            } finally {
                builder.recycle();
            }
        }
        throw new RuntimeException("Font not found " + path);
    }

    /**
     * Create a new typeface from an array of font families.
     *
     * @param families array of font families
     */
    private static Typeface createFromFamilies(FontFamily[] families) {
        long[] ptrArray = new long[families.length];
        for (int i = 0; i < families.length; i++) {
            ptrArray[i] = families[i].mNativePtr;
        }
        return new Typeface(nativeCreateFromArray(ptrArray));
    }

    /**
     * Create a new typeface from an array of font families, including
     * also the font families in the fallback list.
     *
     * @param families array of font families
     */
    private static Typeface createFromFamiliesWithDefault(FontFamily[] families) {
        long[] ptrArray = new long[families.length + sFallbackFonts.length];
        for (int i = 0; i < families.length; i++) {
            ptrArray[i] = families[i].mNativePtr;
        }
        for (int i = 0; i < sFallbackFonts.length; i++) {
            ptrArray[i + families.length] = sFallbackFonts[i].mNativePtr;
        }
        return new Typeface(nativeCreateFromArray(ptrArray));
    }

    // don't allow clients to call this directly
    private Typeface(long ni) {
        if (ni == 0) {
            throw new RuntimeException("native typeface cannot be made");
        }

        native_instance = ni;
        mStyle = nativeGetStyle(ni);
    }

    private static FontFamily makeFamilyFromParsed(FontConfig.Family family,
            Map<String, ByteBuffer> bufferForPath) {
        FontFamily fontFamily = new FontFamily(family.getLanguage(), family.getVariant());
        for (FontConfig.Font font : family.getFonts()) {
            ByteBuffer fontBuffer = bufferForPath.get(font.getFontName());
            if (fontBuffer == null) {
                try (FileInputStream file = new FileInputStream(font.getFontName())) {
                    FileChannel fileChannel = file.getChannel();
                    long fontSize = fileChannel.size();
                    fontBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fontSize);
                    bufferForPath.put(font.getFontName(), fontBuffer);
                } catch (IOException e) {
                    Log.e(TAG, "Error mapping font file " + font.getFontName());
                    continue;
                }
            }
            if (!fontFamily.addFontFromBuffer(fontBuffer, font.getTtcIndex(), font.getAxes(),
                    font.getWeight(), font.isItalic() ? Builder.ITALIC : Builder.NORMAL)) {
                Log.e(TAG, "Error creating font " + font.getFontName() + "#" + font.getTtcIndex());
            }
        }
        fontFamily.freeze();
        return fontFamily;
    }

    /*
     * (non-Javadoc)
     *
     * This should only be called once, from the static class initializer block.
     */
    private static void init() {
        // Load font config and initialize Minikin state
        File systemFontConfigLocation = getSystemFontConfigLocation();
        File configFilename = new File(systemFontConfigLocation, FONTS_CONFIG);
        try {
            FileInputStream fontsIn = new FileInputStream(configFilename);
            FontConfig fontConfig = FontListParser.parse(fontsIn);

            Map<String, ByteBuffer> bufferForPath = new HashMap<String, ByteBuffer>();

            List<FontFamily> familyList = new ArrayList<FontFamily>();
            // Note that the default typeface is always present in the fallback list;
            // this is an enhancement from pre-Minikin behavior.
            for (int i = 0; i < fontConfig.getFamilies().length; i++) {
                FontConfig.Family f = fontConfig.getFamilies()[i];
                if (i == 0 || f.getName() == null) {
                    familyList.add(makeFamilyFromParsed(f, bufferForPath));
                }
            }
            sFallbackFonts = familyList.toArray(new FontFamily[familyList.size()]);
            setDefault(Typeface.createFromFamilies(sFallbackFonts));

            Map<String, Typeface> systemFonts = new HashMap<String, Typeface>();
            for (int i = 0; i < fontConfig.getFamilies().length; i++) {
                Typeface typeface;
                FontConfig.Family f = fontConfig.getFamilies()[i];
                if (f.getName() != null) {
                    if (i == 0) {
                        // The first entry is the default typeface; no sense in
                        // duplicating the corresponding FontFamily.
                        typeface = sDefaultTypeface;
                    } else {
                        FontFamily fontFamily = makeFamilyFromParsed(f, bufferForPath);
                        FontFamily[] families = { fontFamily };
                        typeface = Typeface.createFromFamiliesWithDefault(families);
                    }
                    systemFonts.put(f.getName(), typeface);
                }
            }
            for (FontConfig.Alias alias : fontConfig.getAliases()) {
                Typeface base = systemFonts.get(alias.getToName());
                Typeface newFace = base;
                int weight = alias.getWeight();
                if (weight != 400) {
                    newFace = new Typeface(nativeCreateWeightAlias(base.native_instance, weight));
                }
                systemFonts.put(alias.getName(), newFace);
            }
            sSystemFontMap = systemFonts;

        } catch (RuntimeException e) {
            Log.w(TAG, "Didn't create default family (most likely, non-Minikin build)", e);
            // TODO: normal in non-Minikin case, remove or make error when Minikin-only
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Error opening " + configFilename, e);
        } catch (IOException e) {
            Log.e(TAG, "Error reading " + configFilename, e);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "XML parse exception for " + configFilename, e);
        }
    }

    static {
        init();
        // Set up defaults and typefaces exposed in public API
        DEFAULT         = create((String) null, 0);
        DEFAULT_BOLD    = create((String) null, Typeface.BOLD);
        SANS_SERIF      = create("sans-serif", 0);
        SERIF           = create("serif", 0);
        MONOSPACE       = create("monospace", 0);

        sDefaults = new Typeface[] {
            DEFAULT,
            DEFAULT_BOLD,
            create((String) null, Typeface.ITALIC),
            create((String) null, Typeface.BOLD_ITALIC),
        };

    }

    private static File getSystemFontConfigLocation() {
        return new File("/system/etc/");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nativeUnref(native_instance);
            native_instance = 0;  // Other finalizers can still call us.
        } finally {
            super.finalize();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Typeface typeface = (Typeface) o;

        return mStyle == typeface.mStyle && native_instance == typeface.native_instance;
    }

    @Override
    public int hashCode() {
        /*
         * Modified method for hashCode with long native_instance derived from
         * http://developer.android.com/reference/java/lang/Object.html
         */
        int result = 17;
        result = 31 * result + (int) (native_instance ^ (native_instance >>> 32));
        result = 31 * result + mStyle;
        return result;
    }

    /** @hide */
    public boolean isSupportedAxes(int axis) {
        if (mSupportedAxes == null) {
            synchronized (this) {
                if (mSupportedAxes == null) {
                    mSupportedAxes = nativeGetSupportedAxes(native_instance);
                    if (mSupportedAxes == null) {
                        mSupportedAxes = EMPTY_AXES;
                    }
                }
            }
        }
        return Arrays.binarySearch(mSupportedAxes, axis) > 0;
    }

    private static native long nativeCreateFromTypeface(long native_instance, int style);
    // TODO: clean up: change List<FontConfig.Axis> to FontConfig.Axis[]
    private static native long nativeCreateFromTypefaceWithVariation(
            long native_instance, List<FontConfig.Axis> axes);
    private static native long nativeCreateWeightAlias(long native_instance, int weight);
    private static native void nativeUnref(long native_instance);
    private static native int  nativeGetStyle(long native_instance);
    private static native long nativeCreateFromArray(long[] familyArray);
    private static native void nativeSetDefault(long native_instance);
    private static native int[] nativeGetSupportedAxes(long native_instance);
}
