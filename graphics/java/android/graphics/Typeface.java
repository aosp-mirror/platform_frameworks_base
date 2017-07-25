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
import android.content.res.AssetManager;
import android.graphics.FontListParser;
import android.graphics.fonts.FontVariationAxis;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.provider.FontRequest;
import android.provider.FontsContract;
import android.text.FontConfig;
import android.util.Base64;
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

    /**
     * Cache for Typeface objects dynamically loaded from assets. Currently max size is 16.
     */
    @GuardedBy("sLock")
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
    private int mWeight = 0;

    // Value for weight and italic. Indicates the value is resolved by font metadata.
    // Must be the same as the C++ constant in core/jni/android/graphics/FontFamily.cpp
    /** @hide */
    public static final int RESOLVE_BY_FONT_TABLE = -1;

    // Style value for building typeface.
    private static final int STYLE_NORMAL = 0;
    private static final int STYLE_ITALIC = 1;

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
                        mgr, path, 0 /* ttcIndex */, null /* axes */,
                        RESOLVE_BY_FONT_TABLE /* weight */, RESOLVE_BY_FONT_TABLE /* italic */);
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
                    typeface = createFromFamiliesWithDefault(families,
                            RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE);
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
            final FontFamilyFilesResourceEntry filesEntry =
                    (FontFamilyFilesResourceEntry) entry;

            FontFamily fontFamily = new FontFamily();
            for (final FontFileResourceEntry fontFile : filesEntry.getEntries()) {
                // TODO: Add ttc and variation font support. (b/37853920)
                if (!fontFamily.addFontFromAssetManager(mgr, fontFile.getFileName(),
                        0 /* resourceCookie */, false /* isAsset */, 0 /* ttcIndex */,
                        fontFile.getWeight(), fontFile.getItalic(), null /* axes */)) {
                    return null;
                }
            }
            if (!fontFamily.freeze()) {
                return null;
            }
            FontFamily[] familyChain = { fontFamily };
            typeface = createFromFamiliesWithDefault(familyChain,
                    RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE);
            synchronized (sDynamicTypefaceCache) {
                final String key = Builder.createAssetUid(mgr, path, 0 /* ttcIndex */,
                        null /* axes */, RESOLVE_BY_FONT_TABLE /* weight */,
                        RESOLVE_BY_FONT_TABLE /* italic */);
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
            final String key = Builder.createAssetUid(mgr, path, 0 /* ttcIndex */, null /* axes */,
                    RESOLVE_BY_FONT_TABLE /* weight */, RESOLVE_BY_FONT_TABLE /* italic */);
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
         * @param results The array of {@link FontsContract.FontInfo}
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
         * Sets an index of the font collection.
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
                @Nullable FontVariationAxis[] axes, int weight, int italic) {
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
            builder.append("-");
            if (axes != null) {
                for (FontVariationAxis axis : axes) {
                    builder.append(axis.getTag());
                    builder.append("-");
                    builder.append(Float.toString(axis.getStyleValue()));
                }
            }
            return builder.toString();
        }

        private static final Object sLock = new Object();
        // TODO: Unify with Typeface.sTypefaceCache.
        @GuardedBy("sLock")
        private static final LongSparseArray<SparseArray<Typeface>> sTypefaceCache =
                new LongSparseArray<>(3);

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
            final int key = weight << 1 | (italic ? 1 : 0);

            Typeface typeface;
            synchronized(sLock) {
                SparseArray<Typeface> innerCache = sTypefaceCache.get(base.native_instance);
                if (innerCache != null) {
                    typeface = innerCache.get(key);
                    if (typeface != null) {
                        return typeface;
                    }
                }

                typeface = new Typeface(
                        nativeCreateFromTypefaceWithExactStyle(
                                base.native_instance, weight, italic));

                if (innerCache == null) {
                    innerCache = new SparseArray<>(4); // [regular, bold] x [upright, italic]
                    sTypefaceCache.put(base.native_instance, innerCache);
                }
                innerCache.put(key, typeface);
            }
            return typeface;
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
                    return createFromFamiliesWithDefault(families, mWeight, mItalic);
                } catch (IOException e) {
                    return resolveFallbackTypeface();
                }
            } else if (mAssetManager != null) {  // Builder is created with asset manager.
                final String key = createAssetUid(
                        mAssetManager, mPath, mTtcIndex, mAxes, mWeight, mItalic);
                synchronized (sLock) {
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
                    typeface = createFromFamiliesWithDefault(families, mWeight, mItalic);
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
                return createFromFamiliesWithDefault(families, mWeight, mItalic);
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
                return createFromFamiliesWithDefault(families, mWeight, mItalic);
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
            synchronized (sLock) {
                Typeface typeface = new Builder(mgr, path).build();
                if (typeface != null) return typeface;

                final String key = Builder.createAssetUid(mgr, path, 0 /* ttcIndex */,
                        null /* axes */, RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE);
                typeface = sDynamicTypefaceCache.get(key);
                if (typeface != null) return typeface;

                final FontFamily fontFamily = new FontFamily();
                if (fontFamily.addFontFromAssetManager(mgr, path, 0, true /* isAsset */,
                        0 /* ttc index */, RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE,
                        null /* axes */)) {
                    // Due to backward compatibility, even if the font is not supported by our font
                    // stack, we need to place the empty font at the first place. The typeface with
                    // empty font behaves different from default typeface especially in fallback
                    // font selection.
                    fontFamily.allowUnsupportedFont();
                    fontFamily.freeze();
                    final FontFamily[] families = { fontFamily };
                    typeface = createFromFamiliesWithDefault(families, RESOLVE_BY_FONT_TABLE,
                            RESOLVE_BY_FONT_TABLE);
                    sDynamicTypefaceCache.put(key, typeface);
                    return typeface;
                } else {
                    fontFamily.abortCreation();
                }
            }
        }
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
        if (sFallbackFonts != null) {
            final FontFamily fontFamily = new FontFamily();
            if (fontFamily.addFont(path, 0 /* ttcIndex */, null /* axes */,
                      RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE)) {
                // Due to backward compatibility, even if the font is not supported by our font
                // stack, we need to place the empty font at the first place. The typeface with
                // empty font behaves different from default typeface especially in fallback font
                // selection.
                fontFamily.allowUnsupportedFont();
                fontFamily.freeze();
                FontFamily[] families = { fontFamily };
                return createFromFamiliesWithDefault(families, RESOLVE_BY_FONT_TABLE,
                        RESOLVE_BY_FONT_TABLE);
            } else {
                fontFamily.abortCreation();
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
        return new Typeface(nativeCreateFromArray(
                ptrArray, RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE));
    }

    /**
     * Create a new typeface from an array of font families, including
     * also the font families in the fallback list.
     * @param weight the weight for this family. {@link RESOLVE_BY_FONT_TABLE} can be used. In that
     *               case, the table information in the first family's font is used. If the first
     *               family has multiple fonts, the closest to the regular weight and upright font
     *               is used.
     * @param italic the italic information for this family. {@link RESOLVE_BY_FONT_TABLE} can be
     *               used. In that case, the table information in the first family's font is used.
     *               If the first family has multiple fonts, the closest to the regular weight and
     *               upright font is used.
     * @param families array of font families
     */
    private static Typeface createFromFamiliesWithDefault(FontFamily[] families,
                int weight, int italic) {
        long[] ptrArray = new long[families.length + sFallbackFonts.length];
        for (int i = 0; i < families.length; i++) {
            ptrArray[i] = families[i].mNativePtr;
        }
        for (int i = 0; i < sFallbackFonts.length; i++) {
            ptrArray[i + families.length] = sFallbackFonts[i].mNativePtr;
        }
        return new Typeface(nativeCreateFromArray(ptrArray, weight, italic));
    }

    // don't allow clients to call this directly
    private Typeface(long ni) {
        if (ni == 0) {
            throw new RuntimeException("native typeface cannot be made");
        }

        native_instance = ni;
        mStyle = nativeGetStyle(ni);
        mWeight = nativeGetWeight(ni);
    }

    private static FontFamily makeFamilyFromParsed(FontConfig.Family family,
            Map<String, ByteBuffer> bufferForPath) {
        FontFamily fontFamily = new FontFamily(family.getLanguage(), family.getVariant());
        for (FontConfig.Font font : family.getFonts()) {
            String fullPathName = "/system/fonts/" + font.getFontName();
            ByteBuffer fontBuffer = bufferForPath.get(fullPathName);
            if (fontBuffer == null) {
                try (FileInputStream file = new FileInputStream(fullPathName)) {
                    FileChannel fileChannel = file.getChannel();
                    long fontSize = fileChannel.size();
                    fontBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fontSize);
                    bufferForPath.put(fullPathName, fontBuffer);
                } catch (IOException e) {
                    Log.e(TAG, "Error mapping font file " + fullPathName);
                    continue;
                }
            }
            if (!fontFamily.addFontFromBuffer(fontBuffer, font.getTtcIndex(), font.getAxes(),
                    font.getWeight(), font.isItalic() ? STYLE_ITALIC : STYLE_NORMAL)) {
                Log.e(TAG, "Error creating font " + fullPathName + "#" + font.getTtcIndex());
            }
        }
        if (!fontFamily.freeze()) {
            // Treat as system error since reaching here means that a system pre-installed font
            // can't be used by our font stack.
            Log.e(TAG, "Unable to load Family: " + family.getName() + ":" + family.getLanguage());
            return null;
        }
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
                    FontFamily family = makeFamilyFromParsed(f, bufferForPath);
                    if (family != null) {
                        familyList.add(family);
                    }
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
                        if (fontFamily == null) {
                            continue;
                        }
                        FontFamily[] families = { fontFamily };
                        typeface = Typeface.createFromFamiliesWithDefault(families,
                                RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE);
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
        return Arrays.binarySearch(mSupportedAxes, axis) >= 0;
    }

    private static native long nativeCreateFromTypeface(long native_instance, int style);
    private static native long nativeCreateFromTypefaceWithExactStyle(
            long native_instance, int weight, boolean italic);
    // TODO: clean up: change List<FontVariationAxis> to FontVariationAxis[]
    private static native long nativeCreateFromTypefaceWithVariation(
            long native_instance, List<FontVariationAxis> axes);
    private static native long nativeCreateWeightAlias(long native_instance, int weight);
    private static native void nativeUnref(long native_instance);
    private static native int  nativeGetStyle(long native_instance);
    private static native int  nativeGetWeight(long native_instance);
    private static native long nativeCreateFromArray(long[] familyArray, int weight, int italic);
    private static native void nativeSetDefault(long native_instance);
    private static native int[] nativeGetSupportedAxes(long native_instance);
}
