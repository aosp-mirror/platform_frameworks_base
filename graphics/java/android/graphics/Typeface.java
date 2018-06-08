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

import static android.content.res.FontResourcesParser.FamilyResourceEntry;
import static android.content.res.FontResourcesParser.FontFamilyFilesResourceEntry;
import static android.content.res.FontResourcesParser.FontFileResourceEntry;
import static android.content.res.FontResourcesParser.ProviderResourceEntry;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetManager;
import android.graphics.fonts.FontVariationAxis;
import android.net.Uri;
import android.provider.FontRequest;
import android.provider.FontsContract;
import android.text.FontConfig;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.LruCache;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;

import libcore.util.NativeAllocationRegistry;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Typeface class specifies the typeface and intrinsic style of a font.
 * This is used in the paint, along with optionally Paint settings like
 * textSize, textSkewX, textScaleX to specify
 * how text appears when drawn (and measured).
 */
public class Typeface {

    private static String TAG = "Typeface";

    private static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
            Typeface.class.getClassLoader(), nativeGetReleaseFunc(), 64);

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

    /**
     * Cache for Typeface objects for style variant. Currently max size is 3.
     */
    @GuardedBy("sStyledCacheLock")
    private static final LongSparseArray<SparseArray<Typeface>> sStyledTypefaceCache =
            new LongSparseArray<>(3);
    private static final Object sStyledCacheLock = new Object();

    /**
     * Cache for Typeface objects for weight variant. Currently max size is 3.
     */
    @GuardedBy("sWeightCacheLock")
    private static final LongSparseArray<SparseArray<Typeface>> sWeightTypefaceCache =
            new LongSparseArray<>(3);
    private static final Object sWeightCacheLock = new Object();

    /**
     * Cache for Typeface objects dynamically loaded from assets. Currently max size is 16.
     */
    @GuardedBy("sDynamicCacheLock")
    private static final LruCache<String, Typeface> sDynamicTypefaceCache = new LruCache<>(16);
    private static final Object sDynamicCacheLock = new Object();

    static Typeface sDefaultTypeface;
    static final Map<String, Typeface> sSystemFontMap;
    static final Map<String, FontFamily[]> sSystemFallbackMap;

    /**
     * @hide
     */
    public long native_instance;

    /** @hide */
    @IntDef(value = {NORMAL, BOLD, ITALIC, BOLD_ITALIC})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Style {}

    // Style
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;
    /** @hide */ public static final int STYLE_MASK = 0x03;

    private @Style int mStyle = 0;

    /**
     * A maximum value for the weight value.
     * @hide
     */
    public static final int MAX_WEIGHT = 1000;

    private @IntRange(from = 0, to = MAX_WEIGHT) int mWeight = 0;

    // Value for weight and italic. Indicates the value is resolved by font metadata.
    // Must be the same as the C++ constant in core/jni/android/graphics/FontFamily.cpp
    /** @hide */
    public static final int RESOLVE_BY_FONT_TABLE = -1;
    private static final String DEFAULT_FAMILY = "sans-serif";

    // Style value for building typeface.
    private static final int STYLE_NORMAL = 0;
    private static final int STYLE_ITALIC = 1;

    private int[] mSupportedAxes;
    private static final int[] EMPTY_AXES = {};

    private static void setDefault(Typeface t) {
        sDefaultTypeface = t;
        nativeSetDefault(t.native_instance);
    }

    /** Returns the typeface's weight value */
    public @IntRange(from = 0, to = 1000) int getWeight() {
        return mWeight;
    }

    /** Returns the typeface's intrinsic style attributes */
    public @Style int getStyle() {
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
        synchronized (sDynamicCacheLock) {
            final String key = Builder.createAssetUid(
                    mgr, path, 0 /* ttcIndex */, null /* axes */,
                    RESOLVE_BY_FONT_TABLE /* weight */, RESOLVE_BY_FONT_TABLE /* italic */,
                    DEFAULT_FAMILY);
            Typeface typeface = sDynamicTypefaceCache.get(key);
            if (typeface != null) return typeface;

            FontFamily fontFamily = new FontFamily();
            // TODO: introduce ttc index and variation settings to resource type font.
            if (fontFamily.addFontFromAssetManager(mgr, path, cookie, false /* isAsset */,
                    0 /* ttcIndex */, RESOLVE_BY_FONT_TABLE /* weight */,
                    RESOLVE_BY_FONT_TABLE /* italic */, null /* axes */)) {
                if (!fontFamily.freeze()) {
                    return null;
                }
                FontFamily[] families = {fontFamily};
                typeface = createFromFamiliesWithDefault(families, DEFAULT_FAMILY,
                        RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE);
                sDynamicTypefaceCache.put(key, typeface);
                return typeface;
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
        if (entry instanceof ProviderResourceEntry) {
            final ProviderResourceEntry providerEntry = (ProviderResourceEntry) entry;
            // Downloadable font
            List<List<String>> givenCerts = providerEntry.getCerts();
            List<List<byte[]>> certs = new ArrayList<>();
            if (givenCerts != null) {
                for (int i = 0; i < givenCerts.size(); i++) {
                    List<String> certSet = givenCerts.get(i);
                    List<byte[]> byteArraySet = new ArrayList<>();
                    for (int j = 0; j < certSet.size(); j++) {
                        byteArraySet.add(Base64.decode(certSet.get(j), Base64.DEFAULT));
                    }
                    certs.add(byteArraySet);
                }
            }
            // Downloaded font and it wasn't cached, request it again and return a
            // default font instead (nothing we can do now).
            FontRequest request = new FontRequest(providerEntry.getAuthority(),
                    providerEntry.getPackage(), providerEntry.getQuery(), certs);
            Typeface typeface = FontsContract.getFontSync(request);
            return typeface == null ? DEFAULT : typeface;
        }

        Typeface typeface = findFromCache(mgr, path);
        if (typeface != null) return typeface;

        // family is FontFamilyFilesResourceEntry
        final FontFamilyFilesResourceEntry filesEntry = (FontFamilyFilesResourceEntry) entry;

        FontFamily fontFamily = new FontFamily();
        for (final FontFileResourceEntry fontFile : filesEntry.getEntries()) {
            if (!fontFamily.addFontFromAssetManager(mgr, fontFile.getFileName(),
                    0 /* resourceCookie */, false /* isAsset */, fontFile.getTtcIndex(),
                    fontFile.getWeight(), fontFile.getItalic(),
                    FontVariationAxis.fromFontVariationSettings(fontFile.getVariationSettings()))) {
                return null;
            }
        }
        if (!fontFamily.freeze()) {
            return null;
        }
        FontFamily[] familyChain = { fontFamily };
        typeface = createFromFamiliesWithDefault(familyChain, DEFAULT_FAMILY,
                RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE);
        synchronized (sDynamicCacheLock) {
            final String key = Builder.createAssetUid(mgr, path, 0 /* ttcIndex */,
                    null /* axes */, RESOLVE_BY_FONT_TABLE /* weight */,
                    RESOLVE_BY_FONT_TABLE /* italic */, DEFAULT_FAMILY);
            sDynamicTypefaceCache.put(key, typeface);
        }
        return typeface;
    }

    /**
     * Used by resources for cached loading if the font is available.
     * @hide
     */
    public static Typeface findFromCache(AssetManager mgr, String path) {
        synchronized (sDynamicCacheLock) {
            final String key = Builder.createAssetUid(mgr, path, 0 /* ttcIndex */, null /* axes */,
                    RESOLVE_BY_FONT_TABLE /* weight */, RESOLVE_BY_FONT_TABLE /* italic */,
                    DEFAULT_FAMILY);
            Typeface typeface = sDynamicTypefaceCache.get(key);
            if (typeface != null) {
                return typeface;
            }
        }
        return null;
    }

    /**
     * A builder class for creating new Typeface instance.
     *
     * <p>
     * Examples,
     * 1) Create Typeface from ttf file.
     * <pre>
     * <code>
     * Typeface.Builder buidler = new Typeface.Builder("your_font_file.ttf");
     * Typeface typeface = builder.build();
     * </code>
     * </pre>
     *
     * 2) Create Typeface from ttc file in assets directory.
     * <pre>
     * <code>
     * Typeface.Builder buidler = new Typeface.Builder(getAssets(), "your_font_file.ttc");
     * builder.setTtcIndex(2);  // Set index of font collection.
     * Typeface typeface = builder.build();
     * </code>
     * </pre>
     *
     * 3) Create Typeface with variation settings.
     * <pre>
     * <code>
     * Typeface.Builder buidler = new Typeface.Builder("your_font_file.ttf");
     * builder.setFontVariationSettings("'wght' 700, 'slnt' 20, 'ital' 1");
     * builder.setWeight(700);  // Tell the system that this is a bold font.
     * builder.setItalic(true);  // Tell the system that this is an italic style font.
     * Typeface typeface = builder.build();
     * </code>
     * </pre>
     * </p>
     */
    public static final class Builder {
        /** @hide */
        public static final int NORMAL_WEIGHT = 400;
        /** @hide */
        public static final int BOLD_WEIGHT = 700;

        private int mTtcIndex;
        private FontVariationAxis[] mAxes;

        private AssetManager mAssetManager;
        private String mPath;
        private FileDescriptor mFd;

        private FontsContract.FontInfo[] mFonts;
        private Map<Uri, ByteBuffer> mFontBuffers;

        private String mFallbackFamilyName;

        private int mWeight = RESOLVE_BY_FONT_TABLE;
        private int mItalic = RESOLVE_BY_FONT_TABLE;

        /**
         * Constructs a builder with a file path.
         *
         * @param path The file object refers to the font file.
         */
        public Builder(@NonNull File path) {
            mPath = path.getAbsolutePath();
        }

        /**
         * Constructs a builder with a file descriptor.
         *
         * Caller is responsible for closing the passed file descriptor after {@link #build} is
         * called.
         *
         * @param fd The file descriptor. The passed fd must be mmap-able.
         */
        public Builder(@NonNull FileDescriptor fd) {
            mFd = fd;
        }

        /**
         * Constructs a builder with a file path.
         *
         * @param path The full path to the font file.
         */
        public Builder(@NonNull String path) {
            mPath = path;
        }

        /**
         * Constructs a builder from an asset manager and a file path in an asset directory.
         *
         * @param assetManager The application's asset manager
         * @param path The file name of the font data in the asset directory
         */
        public Builder(@NonNull AssetManager assetManager, @NonNull String path) {
            mAssetManager = Preconditions.checkNotNull(assetManager);
            mPath = Preconditions.checkStringNotEmpty(path);
        }

        /**
         * Constracts a builder from an array of FontsContract.FontInfo.
         *
         * Since {@link FontsContract.FontInfo} holds information about TTC indices and
         * variation settings, there is no need to call {@link #setTtcIndex} or
         * {@link #setFontVariationSettings}. Similary, {@link FontsContract.FontInfo} holds
         * weight and italic information, so {@link #setWeight} and {@link #setItalic} are used
         * for style matching during font selection.
         *
         * @param fonts The array of {@link FontsContract.FontInfo}
         * @param buffers The mapping from URI to buffers to be used during building.
         * @hide
         */
        public Builder(@NonNull FontsContract.FontInfo[] fonts,
                @NonNull Map<Uri, ByteBuffer> buffers) {
            mFonts = fonts;
            mFontBuffers = buffers;
        }

        /**
         * Sets weight of the font.
         *
         * Tells the system the weight of the given font. If not provided, the system will resolve
         * the weight value by reading font tables.
         * @param weight a weight value.
         */
        public Builder setWeight(@IntRange(from = 1, to = 1000) int weight) {
            mWeight = weight;
            return this;
        }

        /**
         * Sets italic information of the font.
         *
         * Tells the system the style of the given font. If not provided, the system will resolve
         * the style by reading font tables.
         * @param italic {@code true} if the font is italic. Otherwise {@code false}.
         */
        public Builder setItalic(boolean italic) {
            mItalic = italic ? STYLE_ITALIC : STYLE_NORMAL;
            return this;
        }

        /**
         * Sets an index of the font collection. See {@link android.R.attr#ttcIndex}.
         *
         * Can not be used for Typeface source. build() method will return null for invalid index.
         * @param ttcIndex An index of the font collection. If the font source is not font
         *                 collection, do not call this method or specify 0.
         */
        public Builder setTtcIndex(@IntRange(from = 0) int ttcIndex) {
            if (mFonts != null) {
                throw new IllegalArgumentException(
                        "TTC index can not be specified for FontResult source.");
            }
            mTtcIndex = ttcIndex;
            return this;
        }

        /**
         * Sets a font variation settings.
         *
         * @param variationSettings See {@link android.widget.TextView#setFontVariationSettings}.
         * @throws IllegalArgumentException If given string is not a valid font variation settings
         *                                  format.
         */
        public Builder setFontVariationSettings(@Nullable String variationSettings) {
            if (mFonts != null) {
                throw new IllegalArgumentException(
                        "Font variation settings can not be specified for FontResult source.");
            }
            if (mAxes != null) {
                throw new IllegalStateException("Font variation settings are already set.");
            }
            mAxes = FontVariationAxis.fromFontVariationSettings(variationSettings);
            return this;
        }

        /**
         * Sets a font variation settings.
         *
         * @param axes An array of font variation axis tag-value pairs.
         */
        public Builder setFontVariationSettings(@Nullable FontVariationAxis[] axes) {
            if (mFonts != null) {
                throw new IllegalArgumentException(
                        "Font variation settings can not be specified for FontResult source.");
            }
            if (mAxes != null) {
                throw new IllegalStateException("Font variation settings are already set.");
            }
            mAxes = axes;
            return this;
        }

        /**
         * Sets a fallback family name.
         *
         * By specifying a fallback family name, a fallback Typeface will be returned if the
         * {@link #build} method fails to create a Typeface from the provided font. The fallback
         * family will be resolved with the provided weight and italic information specified by
         * {@link #setWeight} and {@link #setItalic}.
         *
         * If {@link #setWeight} is not called, the fallback family keeps the default weight.
         * Similary, if {@link #setItalic} is not called, the fallback family keeps the default
         * italic information. For example, calling {@code builder.setFallback("sans-serif-light")}
         * is equivalent to calling {@code builder.setFallback("sans-serif").setWeight(300)} in
         * terms of fallback. The default weight and italic information are overridden by calling
         * {@link #setWeight} and {@link #setItalic}. For example, if a Typeface is constructed
         * using {@code builder.setFallback("sans-serif-light").setWeight(700)}, the fallback text
         * will render as sans serif bold.
         *
         * @param familyName A family name to be used for fallback if the provided font can not be
         *                   used. By passing {@code null}, build() returns {@code null}.
         *                   If {@link #setFallback} is not called on the builder, {@code null}
         *                   is assumed.
         */
        public Builder setFallback(@Nullable String familyName) {
            mFallbackFamilyName = familyName;
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
                @Nullable FontVariationAxis[] axes, int weight, int italic, String fallback) {
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
            builder.append(Integer.toString(weight));
            builder.append("-");
            builder.append(Integer.toString(italic));
            // Family name may contain hyphen. Use double hyphen for avoiding key conflicts before
            // and after appending falblack name.
            builder.append("--");
            builder.append(fallback);
            builder.append("--");
            if (axes != null) {
                for (FontVariationAxis axis : axes) {
                    builder.append(axis.getTag());
                    builder.append("-");
                    builder.append(Float.toString(axis.getStyleValue()));
                }
            }
            return builder.toString();
        }

        private Typeface resolveFallbackTypeface() {
            if (mFallbackFamilyName == null) {
                return null;
            }

            Typeface base =  sSystemFontMap.get(mFallbackFamilyName);
            if (base == null) {
                base = sDefaultTypeface;
            }

            if (mWeight == RESOLVE_BY_FONT_TABLE && mItalic == RESOLVE_BY_FONT_TABLE) {
                return base;
            }

            final int weight = (mWeight == RESOLVE_BY_FONT_TABLE) ? base.mWeight : mWeight;
            final boolean italic =
                    (mItalic == RESOLVE_BY_FONT_TABLE) ? (base.mStyle & ITALIC) != 0 : mItalic == 1;
            return createWeightStyle(base, weight, italic);
        }

        /**
         * Generates new Typeface from specified configuration.
         *
         * @return Newly created Typeface. May return null if some parameters are invalid.
         */
        public Typeface build() {
            if (mFd != null) {  // Builder is created with file descriptor.
                try (FileInputStream fis = new FileInputStream(mFd)) {
                    FileChannel channel = fis.getChannel();
                    long size = channel.size();
                    ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);

                    final FontFamily fontFamily = new FontFamily();
                    if (!fontFamily.addFontFromBuffer(buffer, mTtcIndex, mAxes, mWeight, mItalic)) {
                        fontFamily.abortCreation();
                        return resolveFallbackTypeface();
                    }
                    if (!fontFamily.freeze()) {
                        return resolveFallbackTypeface();
                    }
                    FontFamily[] families = { fontFamily };
                    return createFromFamiliesWithDefault(families, mFallbackFamilyName, mWeight,
                            mItalic);
                } catch (IOException e) {
                    return resolveFallbackTypeface();
                }
            } else if (mAssetManager != null) {  // Builder is created with asset manager.
                final String key = createAssetUid(
                        mAssetManager, mPath, mTtcIndex, mAxes, mWeight, mItalic,
                        mFallbackFamilyName);
                synchronized (sDynamicCacheLock) {
                    Typeface typeface = sDynamicTypefaceCache.get(key);
                    if (typeface != null) return typeface;
                    final FontFamily fontFamily = new FontFamily();
                    if (!fontFamily.addFontFromAssetManager(mAssetManager, mPath, mTtcIndex,
                            true /* isAsset */, mTtcIndex, mWeight, mItalic, mAxes)) {
                        fontFamily.abortCreation();
                        return resolveFallbackTypeface();
                    }
                    if (!fontFamily.freeze()) {
                        return resolveFallbackTypeface();
                    }
                    FontFamily[] families = { fontFamily };
                    typeface = createFromFamiliesWithDefault(families, mFallbackFamilyName,
                            mWeight, mItalic);
                    sDynamicTypefaceCache.put(key, typeface);
                    return typeface;
                }
            } else if (mPath != null) {  // Builder is created with file path.
                final FontFamily fontFamily = new FontFamily();
                if (!fontFamily.addFont(mPath, mTtcIndex, mAxes, mWeight, mItalic)) {
                    fontFamily.abortCreation();
                    return resolveFallbackTypeface();
                }
                if (!fontFamily.freeze()) {
                    return resolveFallbackTypeface();
                }
                FontFamily[] families = { fontFamily };
                return createFromFamiliesWithDefault(families, mFallbackFamilyName, mWeight,
                        mItalic);
            } else if (mFonts != null) {
                final FontFamily fontFamily = new FontFamily();
                boolean atLeastOneFont = false;
                for (FontsContract.FontInfo font : mFonts) {
                    final ByteBuffer fontBuffer = mFontBuffers.get(font.getUri());
                    if (fontBuffer == null) {
                        continue;  // skip
                    }
                    final boolean success = fontFamily.addFontFromBuffer(fontBuffer,
                            font.getTtcIndex(), font.getAxes(), font.getWeight(),
                            font.isItalic() ? STYLE_ITALIC : STYLE_NORMAL);
                    if (!success) {
                        fontFamily.abortCreation();
                        return null;
                    }
                    atLeastOneFont = true;
                }
                if (!atLeastOneFont) {
                    // No fonts are avaialble. No need to create new Typeface and returns fallback
                    // Typeface instead.
                    fontFamily.abortCreation();
                    return null;
                }
                fontFamily.freeze();
                FontFamily[] families = { fontFamily };
                return createFromFamiliesWithDefault(families, mFallbackFamilyName, mWeight,
                        mItalic);
            }

            // Must not reach here.
            throw new IllegalArgumentException("No source was set.");
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
    public static Typeface create(String familyName, @Style int style) {
        return create(sSystemFontMap.get(familyName), style);
    }

    /**
     * Create a typeface object that best matches the specified existing
     * typeface and the specified Style. Use this call if you want to pick a new
     * style from the same family of an existing typeface object. If family is
     * null, this selects from the default font's family.
     *
     * <p>
     * This method is not thread safe on API 27 or before.
     * This method is thread safe on API 28 or after.
     * </p>
     *
     * @param family An existing {@link Typeface} object. In case of {@code null}, the default
     *               typeface is used instead.
     * @param style  The style (normal, bold, italic) of the typeface.
     *               e.g. NORMAL, BOLD, ITALIC, BOLD_ITALIC
     * @return The best matching typeface.
     */
    public static Typeface create(Typeface family, @Style int style) {
        if ((style & ~STYLE_MASK) != 0) {
            style = NORMAL;
        }
        if (family == null) {
            family = sDefaultTypeface;
        }

        // Return early if we're asked for the same face/style
        if (family.mStyle == style) {
            return family;
        }

        final long ni = family.native_instance;

        Typeface typeface;
        synchronized (sStyledCacheLock) {
            SparseArray<Typeface> styles = sStyledTypefaceCache.get(ni);

            if (styles == null) {
                styles = new SparseArray<Typeface>(4);
                sStyledTypefaceCache.put(ni, styles);
            } else {
                typeface = styles.get(style);
                if (typeface != null) {
                    return typeface;
                }
            }

            typeface = new Typeface(nativeCreateFromTypeface(ni, style));
            styles.put(style, typeface);
        }
        return typeface;
    }

    /**
     * Creates a typeface object that best matches the specified existing typeface and the specified
     * weight and italic style
     * <p>Below are numerical values and corresponding common weight names.</p>
     * <table>
     * <thead>
     * <tr><th>Value</th><th>Common weight name</th></tr>
     * </thead>
     * <tbody>
     * <tr><td>100</td><td>Thin</td></tr>
     * <tr><td>200</td><td>Extra Light</td></tr>
     * <tr><td>300</td><td>Light</td></tr>
     * <tr><td>400</td><td>Normal</td></tr>
     * <tr><td>500</td><td>Medium</td></tr>
     * <tr><td>600</td><td>Semi Bold</td></tr>
     * <tr><td>700</td><td>Bold</td></tr>
     * <tr><td>800</td><td>Extra Bold</td></tr>
     * <tr><td>900</td><td>Black</td></tr>
     * </tbody>
     * </table>
     *
     * <p>
     * This method is thread safe.
     * </p>
     *
     * @param family An existing {@link Typeface} object. In case of {@code null}, the default
     *               typeface is used instead.
     * @param weight The desired weight to be drawn.
     * @param italic {@code true} if italic style is desired to be drawn. Otherwise, {@code false}
     * @return A {@link Typeface} object for drawing specified weight and italic style. Never
     *         returns {@code null}
     *
     * @see #getWeight()
     * @see #isItalic()
     */
    public static @NonNull Typeface create(@Nullable Typeface family,
            @IntRange(from = 1, to = 1000) int weight, boolean italic) {
        Preconditions.checkArgumentInRange(weight, 0, 1000, "weight");
        if (family == null) {
            family = sDefaultTypeface;
        }
        return createWeightStyle(family, weight, italic);
    }

    private static @NonNull Typeface createWeightStyle(@NonNull Typeface base,
            @IntRange(from = 1, to = 1000) int weight, boolean italic) {
        final int key = (weight << 1) | (italic ? 1 : 0);

        Typeface typeface;
        synchronized(sWeightCacheLock) {
            SparseArray<Typeface> innerCache = sWeightTypefaceCache.get(base.native_instance);
            if (innerCache == null) {
                innerCache = new SparseArray<>(4);
                sWeightTypefaceCache.put(base.native_instance, innerCache);
            } else {
                typeface = innerCache.get(key);
                if (typeface != null) {
                    return typeface;
                }
            }

            typeface = new Typeface(
                    nativeCreateFromTypefaceWithExactStyle(
                            base.native_instance, weight, italic));
            innerCache.put(key, typeface);
        }
        return typeface;
    }

    /** @hide */
    public static Typeface createFromTypefaceWithVariation(@Nullable Typeface family,
            @NonNull List<FontVariationAxis> axes) {
        final long ni = family == null ? 0 : family.native_instance;
        return new Typeface(nativeCreateFromTypefaceWithVariation(ni, axes));
    }

    /**
     * Returns one of the default typeface objects, based on the specified style
     *
     * @return the default typeface that corresponds to the style
     */
    public static Typeface defaultFromStyle(@Style int style) {
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
        Preconditions.checkNotNull(path); // for backward compatibility
        Preconditions.checkNotNull(mgr);

        Typeface typeface = new Builder(mgr, path).build();
        if (typeface != null) return typeface;
        // check if the file exists, and throw an exception for backward compatibility
        try (InputStream inputStream = mgr.open(path)) {
        } catch (IOException e) {
            throw new RuntimeException("Font asset not found " + path);
        }

        return Typeface.DEFAULT;
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
     * @param file The path to the font data.
     * @return The new typeface.
     */
    public static Typeface createFromFile(@Nullable File file) {
        // For the compatibility reasons, leaving possible NPE here.
        // See android.graphics.cts.TypefaceTest#testCreateFromFileByFileReferenceNull

        Typeface typeface = new Builder(file).build();
        if (typeface != null) return typeface;

        // check if the file exists, and throw an exception for backward compatibility
        if (!file.exists()) {
            throw new RuntimeException("Font asset not found " + file.getAbsolutePath());
        }

        return Typeface.DEFAULT;
    }

    /**
     * Create a new typeface from the specified font file.
     *
     * @param path The full path to the font data.
     * @return The new typeface.
     */
    public static Typeface createFromFile(@Nullable String path) {
        Preconditions.checkNotNull(path); // for backward compatibility
        return createFromFile(new File(path));
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
        return new Typeface(nativeCreateFromArray(
                ptrArray, RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE));
    }

    /**
     * This method is used by supportlib-v27.
     * TODO: Remove private API use in supportlib: http://b/72665240
     */
    private static Typeface createFromFamiliesWithDefault(FontFamily[] families, int weight,
                int italic) {
        return createFromFamiliesWithDefault(families, DEFAULT_FAMILY, weight, italic);
    }

    /**
     * Create a new typeface from an array of font families, including
     * also the font families in the fallback list.
     * @param fallbackName the family name. If given families don't support characters, the
     *               characters will be rendered with this family.
     * @param weight the weight for this family. In that case, the table information in the first
     *               family's font is used. If the first family has multiple fonts, the closest to
     *               the regular weight and upright font is used.
     * @param italic the italic information for this family. In that case, the table information in
     *               the first family's font is used. If the first family has multiple fonts, the
     *               closest to the regular weight and upright font is used.
     * @param families array of font families
     */
    private static Typeface createFromFamiliesWithDefault(FontFamily[] families,
                String fallbackName, int weight, int italic) {
        FontFamily[] fallback = sSystemFallbackMap.get(fallbackName);
        if (fallback == null) {
            fallback = sSystemFallbackMap.get(DEFAULT_FAMILY);
        }
        long[] ptrArray = new long[families.length + fallback.length];
        for (int i = 0; i < families.length; i++) {
            ptrArray[i] = families[i].mNativePtr;
        }
        for (int i = 0; i < fallback.length; i++) {
            ptrArray[i + families.length] = fallback[i].mNativePtr;
        }
        return new Typeface(nativeCreateFromArray(ptrArray, weight, italic));
    }

    // don't allow clients to call this directly
    private Typeface(long ni) {
        if (ni == 0) {
            throw new RuntimeException("native typeface cannot be made");
        }

        native_instance = ni;
        sRegistry.registerNativeAllocation(this, native_instance);
        mStyle = nativeGetStyle(ni);
        mWeight = nativeGetWeight(ni);
    }

    private static @Nullable ByteBuffer mmap(String fullPath) {
        try (FileInputStream file = new FileInputStream(fullPath)) {
            final FileChannel fileChannel = file.getChannel();
            final long fontSize = fileChannel.size();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fontSize);
        } catch (IOException e) {
            Log.e(TAG, "Error mapping font file " + fullPath);
            return null;
        }
    }

    private static @Nullable FontFamily createFontFamily(
            String familyName, List<FontConfig.Font> fonts, String[] languageTags, int variant,
            Map<String, ByteBuffer> cache, String fontDir) {
        final FontFamily family = new FontFamily(languageTags, variant);
        for (int i = 0; i < fonts.size(); i++) {
            final FontConfig.Font font = fonts.get(i);
            final String fullPath = fontDir + font.getFontName();
            ByteBuffer buffer = cache.get(fullPath);
            if (buffer == null) {
                if (cache.containsKey(fullPath)) {
                    continue;  // Already failed to mmap. Skip it.
                }
                buffer = mmap(fullPath);
                cache.put(fullPath, buffer);
                if (buffer == null) {
                    continue;
                }
            }
            if (!family.addFontFromBuffer(buffer, font.getTtcIndex(), font.getAxes(),
                    font.getWeight(), font.isItalic() ? STYLE_ITALIC : STYLE_NORMAL)) {
                Log.e(TAG, "Error creating font " + fullPath + "#" + font.getTtcIndex());
            }
        }
        if (!family.freeze()) {
            Log.e(TAG, "Unable to load Family: " + familyName + " : "
                    + Arrays.toString(languageTags));
            return null;
        }
        return family;
    }

    private static void pushFamilyToFallback(FontConfig.Family xmlFamily,
            ArrayMap<String, ArrayList<FontFamily>> fallbackMap,
            Map<String, ByteBuffer> cache,
            String fontDir) {

        final String[] languageTags = xmlFamily.getLanguages();
        final int variant = xmlFamily.getVariant();

        final ArrayList<FontConfig.Font> defaultFonts = new ArrayList<>();
        final ArrayMap<String, ArrayList<FontConfig.Font>> specificFallbackFonts = new ArrayMap<>();

        // Collect default fallback and specific fallback fonts.
        for (final FontConfig.Font font : xmlFamily.getFonts()) {
            final String fallbackName = font.getFallbackFor();
            if (fallbackName == null) {
                defaultFonts.add(font);
            } else {
                ArrayList<FontConfig.Font> fallback = specificFallbackFonts.get(fallbackName);
                if (fallback == null) {
                    fallback = new ArrayList<>();
                    specificFallbackFonts.put(fallbackName, fallback);
                }
                fallback.add(font);
            }
        }

        final FontFamily defaultFamily = defaultFonts.isEmpty() ? null : createFontFamily(
                xmlFamily.getName(), defaultFonts, languageTags, variant, cache, fontDir);

        // Insert family into fallback map.
        for (int i = 0; i < fallbackMap.size(); i++) {
            final ArrayList<FontConfig.Font> fallback =
                    specificFallbackFonts.get(fallbackMap.keyAt(i));
            if (fallback == null) {
                if (defaultFamily != null) {
                    fallbackMap.valueAt(i).add(defaultFamily);
                }
            } else {
                final FontFamily family = createFontFamily(
                        xmlFamily.getName(), fallback, languageTags, variant, cache, fontDir);
                if (family != null) {
                    fallbackMap.valueAt(i).add(family);
                } else if (defaultFamily != null) {
                    fallbackMap.valueAt(i).add(defaultFamily);
                } else {
                    // There is no valid for for default fallback. Ignore.
                }
            }
        }
    }

    /**
     * Build the system fallback from xml file.
     *
     * @param xmlPath A full path string to the fonts.xml file.
     * @param fontDir A full path string to the system font directory. This must end with
     *                slash('/').
     * @param fontMap An output system font map. Caller must pass empty map.
     * @param fallbackMap An output system fallback map. Caller must pass empty map.
     * @hide
     */
    @VisibleForTesting
    public static void buildSystemFallback(String xmlPath, String fontDir,
            ArrayMap<String, Typeface> fontMap, ArrayMap<String, FontFamily[]> fallbackMap) {
        try {
            final FileInputStream fontsIn = new FileInputStream(xmlPath);
            final FontConfig fontConfig = FontListParser.parse(fontsIn);

            final HashMap<String, ByteBuffer> bufferCache = new HashMap<String, ByteBuffer>();
            final FontConfig.Family[] xmlFamilies = fontConfig.getFamilies();

            final ArrayMap<String, ArrayList<FontFamily>> fallbackListMap = new ArrayMap<>();
            // First traverse families which have a 'name' attribute to create fallback map.
            for (final FontConfig.Family xmlFamily : xmlFamilies) {
                final String familyName = xmlFamily.getName();
                if (familyName == null) {
                    continue;
                }
                final FontFamily family = createFontFamily(
                        xmlFamily.getName(), Arrays.asList(xmlFamily.getFonts()),
                        xmlFamily.getLanguages(), xmlFamily.getVariant(), bufferCache, fontDir);
                if (family == null) {
                    continue;
                }
                final ArrayList<FontFamily> fallback = new ArrayList<>();
                fallback.add(family);
                fallbackListMap.put(familyName, fallback);
            }

            // Then, add fallback fonts to the each fallback map.
            for (int i = 0; i < xmlFamilies.length; i++) {
                final FontConfig.Family xmlFamily = xmlFamilies[i];
                // The first family (usually the sans-serif family) is always placed immediately
                // after the primary family in the fallback.
                if (i == 0 || xmlFamily.getName() == null) {
                    pushFamilyToFallback(xmlFamily, fallbackListMap, bufferCache, fontDir);
                }
            }

            // Build the font map and fallback map.
            for (int i = 0; i < fallbackListMap.size(); i++) {
                final String fallbackName = fallbackListMap.keyAt(i);
                final List<FontFamily> familyList = fallbackListMap.valueAt(i);
                final FontFamily[] families = familyList.toArray(new FontFamily[familyList.size()]);

                fallbackMap.put(fallbackName, families);
                final long[] ptrArray = new long[families.length];
                for (int j = 0; j < families.length; j++) {
                    ptrArray[j] = families[j].mNativePtr;
                }
                fontMap.put(fallbackName, new Typeface(nativeCreateFromArray(
                        ptrArray, RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE)));
            }

            // Insert alias to font maps.
            for (final FontConfig.Alias alias : fontConfig.getAliases()) {
                Typeface base = fontMap.get(alias.getToName());
                Typeface newFace = base;
                int weight = alias.getWeight();
                if (weight != 400) {
                    newFace = new Typeface(nativeCreateWeightAlias(base.native_instance, weight));
                }
                fontMap.put(alias.getName(), newFace);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Didn't create default family (most likely, non-Minikin build)", e);
            // TODO: normal in non-Minikin case, remove or make error when Minikin-only
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Error opening " + xmlPath, e);
        } catch (IOException e) {
            Log.e(TAG, "Error reading " + xmlPath, e);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "XML parse exception for " + xmlPath, e);
        }
    }

    static {
        final ArrayMap<String, Typeface> systemFontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> systemFallbackMap = new ArrayMap<>();
        buildSystemFallback("/system/etc/fonts.xml", "/system/fonts/", systemFontMap,
                systemFallbackMap);
        sSystemFontMap = Collections.unmodifiableMap(systemFontMap);
        sSystemFallbackMap = Collections.unmodifiableMap(systemFallbackMap);

        setDefault(sSystemFontMap.get(DEFAULT_FAMILY));

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
        return Arrays.binarySearch(mSupportedAxes, axis) >= 0;
    }

    private static native long nativeCreateFromTypeface(long native_instance, int style);
    private static native long nativeCreateFromTypefaceWithExactStyle(
            long native_instance, int weight, boolean italic);
    // TODO: clean up: change List<FontVariationAxis> to FontVariationAxis[]
    private static native long nativeCreateFromTypefaceWithVariation(
            long native_instance, List<FontVariationAxis> axes);
    private static native long nativeCreateWeightAlias(long native_instance, int weight);
    private static native long nativeCreateFromArray(long[] familyArray, int weight, int italic);
    private static native int[] nativeGetSupportedAxes(long native_instance);

    @CriticalNative
    private static native void nativeSetDefault(long nativePtr);

    @CriticalNative
    private static native int  nativeGetStyle(long nativePtr);

    @CriticalNative
    private static native int  nativeGetWeight(long nativePtr);

    @CriticalNative
    private static native long nativeGetReleaseFunc();
}
