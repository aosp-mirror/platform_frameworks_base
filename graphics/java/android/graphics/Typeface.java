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
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetManager;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontVariationAxis;
import android.graphics.fonts.SystemFonts;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.FontRequest;
import android.provider.FontsContract;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.text.FontConfig;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.LruCache;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Typeface class specifies the typeface and intrinsic style of a font.
 * This is used in the paint, along with optionally Paint settings like
 * textSize, textSkewX, textScaleX to specify
 * how text appears when drawn (and measured).
 */
public class Typeface {

    private static String TAG = "Typeface";

    /** @hide */
    public static final boolean ENABLE_LAZY_TYPEFACE_INITIALIZATION = true;

    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(
            Typeface.class.getClassLoader(), nativeGetReleaseFunc());

    /** The default NORMAL typeface object */
    public static final Typeface DEFAULT = null;
    /**
     * The default BOLD typeface object. Note: this may be not actually be
     * bold, depending on what fonts are installed. Call getStyle() to know
     * for sure.
     */
    public static final Typeface DEFAULT_BOLD = null;
    /** The NORMAL style of the default sans serif typeface. */
    public static final Typeface SANS_SERIF = null;
    /** The NORMAL style of the default serif typeface. */
    public static final Typeface SERIF = null;
    /** The NORMAL style of the default monospace typeface. */
    public static final Typeface MONOSPACE = null;

    /**
     * The default {@link Typeface}s for different text styles.
     * Call {@link #defaultFromStyle(int)} to get the default typeface for the given text style.
     * It shouldn't be changed for app wide typeface settings. Please use theme and font XML for
     * the same purpose.
     */
    @GuardedBy("SYSTEM_FONT_MAP_LOCK")
    @UnsupportedAppUsage(trackingBug = 123769446)
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


    @GuardedBy("SYSTEM_FONT_MAP_LOCK")
    static Typeface sDefaultTypeface;

    /**
     * sSystemFontMap is read only and unmodifiable.
     * Use public API {@link #create(String, int)} to get the typeface for given familyName.
     */
    @GuardedBy("SYSTEM_FONT_MAP_LOCK")
    @UnsupportedAppUsage(trackingBug = 123769347)
    static final Map<String, Typeface> sSystemFontMap = new ArrayMap<>();

    // DirectByteBuffer object to hold sSystemFontMap's backing memory mapping.
    static ByteBuffer sSystemFontMapBuffer = null;
    static SharedMemory sSystemFontMapSharedMemory = null;

    // Lock to guard sSystemFontMap and derived default or public typefaces.
    // sStyledCacheLock may be held while this lock is held. Holding them in the reverse order may
    // introduce deadlock.
    private static final Object SYSTEM_FONT_MAP_LOCK = new Object();

    // This field is used but left for hiddenapi private list
    // We cannot support sSystemFallbackMap since we will migrate to public FontFamily API.
    /**
     * @deprecated Use {@link android.graphics.fonts.FontFamily} instead.
     */
    @UnsupportedAppUsage(trackingBug = 123768928)
    @Deprecated
    static final Map<String, android.graphics.FontFamily[]> sSystemFallbackMap =
            Collections.emptyMap();

    /**
     * Returns the shared memory that used for creating Typefaces.
     *
     * @return A SharedMemory used for creating Typeface. Maybe null if the lazy initialization is
     *         disabled or inside SystemServer or Zygote.
     * @hide
     */
    @TestApi
    public static @Nullable SharedMemory getSystemFontMapSharedMemory() {
        if (ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
            Objects.requireNonNull(sSystemFontMapSharedMemory);
        }
        return sSystemFontMapSharedMemory;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public final long native_instance;

    private final String mSystemFontFamilyName;

    private final Runnable mCleaner;

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

    @UnsupportedAppUsage
    private @Style final int mStyle;

    private @IntRange(from = 0, to = FontStyle.FONT_WEIGHT_MAX) final int mWeight;

    // Value for weight and italic. Indicates the value is resolved by font metadata.
    // Must be the same as the C++ constant in core/jni/android/graphics/FontFamily.cpp
    /** @hide */
    public static final int RESOLVE_BY_FONT_TABLE = -1;
    /**
     * The key of the default font family.
     * @hide
     */
    public static final String DEFAULT_FAMILY = "sans-serif";

    // Style value for building typeface.
    private static final int STYLE_NORMAL = 0;
    private static final int STYLE_ITALIC = 1;

    @GuardedBy("this")
    private int[] mSupportedAxes;
    private static final int[] EMPTY_AXES = {};

    /**
     * Please use font in xml and also your application global theme to change the default Typeface.
     * android:textViewStyle and its attribute android:textAppearance can be used in order to change
     * typeface and other text related properties.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static void setDefault(Typeface t) {
        synchronized (SYSTEM_FONT_MAP_LOCK) {
            sDefaultTypeface = t;
            nativeSetDefault(t.native_instance);
        }
    }

    private static Typeface getDefault() {
        synchronized (SYSTEM_FONT_MAP_LOCK) {
            return sDefaultTypeface;
        }
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
     * Returns the system font family name if the typeface was created from a system font family,
     * otherwise returns null.
     */
    public final @Nullable String getSystemFontFamilyName() {
        return mSystemFontFamilyName;
    }

    /**
     * Returns true if the system has the font family with the name [familyName]. For example
     * querying with "sans-serif" would check if the "sans-serif" family is defined in the system
     * and return true if does.
     *
     * @param familyName The name of the font family, cannot be null. If null, exception will be
     *                   thrown.
     */
    private static boolean hasFontFamily(@NonNull String familyName) {
        Objects.requireNonNull(familyName, "familyName cannot be null");
        synchronized (SYSTEM_FONT_MAP_LOCK) {
            return sSystemFontMap.containsKey(familyName);
        }
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

            String systemFontFamilyName = providerEntry.getSystemFontFamilyName();
            if (systemFontFamilyName != null && hasFontFamily(systemFontFamilyName)) {
                return Typeface.create(systemFontFamilyName, NORMAL);
            }
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

        try {
            FontFamily.Builder familyBuilder = null;
            for (final FontFileResourceEntry fontFile : filesEntry.getEntries()) {
                final Font.Builder fontBuilder = new Font.Builder(mgr, fontFile.getFileName(),
                        false /* isAsset */, AssetManager.COOKIE_UNKNOWN)
                        .setTtcIndex(fontFile.getTtcIndex())
                        .setFontVariationSettings(fontFile.getVariationSettings());
                if (fontFile.getWeight() != Typeface.RESOLVE_BY_FONT_TABLE) {
                    fontBuilder.setWeight(fontFile.getWeight());
                }
                if (fontFile.getItalic() != Typeface.RESOLVE_BY_FONT_TABLE) {
                    fontBuilder.setSlant(fontFile.getItalic() == FontFileResourceEntry.ITALIC
                            ?  FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT);
                }

                if (familyBuilder == null) {
                    familyBuilder = new FontFamily.Builder(fontBuilder.build());
                } else {
                    familyBuilder.addFont(fontBuilder.build());
                }
            }
            if (familyBuilder == null) {
                return Typeface.DEFAULT;
            }
            final FontFamily family = familyBuilder.build();
            final FontStyle normal = new FontStyle(FontStyle.FONT_WEIGHT_NORMAL,
                    FontStyle.FONT_SLANT_UPRIGHT);
            Font bestFont = family.getFont(0);
            int bestScore = normal.getMatchScore(bestFont.getStyle());
            for (int i = 1; i < family.getSize(); ++i) {
                final Font candidate = family.getFont(i);
                final int score = normal.getMatchScore(candidate.getStyle());
                if (score < bestScore) {
                    bestFont = candidate;
                    bestScore = score;
                }
            }
            typeface = new Typeface.CustomFallbackBuilder(family)
                    .setStyle(bestFont.getStyle())
                    .build();
        } catch (IllegalArgumentException e) {
            // To be a compatible behavior with API28 or before, catch IllegalArgumentExcetpion
            // thrown by native code and returns null.
            return null;
        } catch (IOException e) {
            typeface = Typeface.DEFAULT;
        }
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
     * Typeface.Builder builder = new Typeface.Builder("your_font_file.ttf");
     * Typeface typeface = builder.build();
     * </code>
     * </pre>
     *
     * 2) Create Typeface from ttc file in assets directory.
     * <pre>
     * <code>
     * Typeface.Builder builder = new Typeface.Builder(getAssets(), "your_font_file.ttc");
     * builder.setTtcIndex(2);  // Set index of font collection.
     * Typeface typeface = builder.build();
     * </code>
     * </pre>
     *
     * 3) Create Typeface with variation settings.
     * <pre>
     * <code>
     * Typeface.Builder builder = new Typeface.Builder("your_font_file.ttf");
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

        // Kept for generating asset cache key.
        private final AssetManager mAssetManager;
        private final String mPath;

        private final @Nullable Font.Builder mFontBuilder;

        private String mFallbackFamilyName;

        private int mWeight = RESOLVE_BY_FONT_TABLE;
        private int mItalic = RESOLVE_BY_FONT_TABLE;

        /**
         * Constructs a builder with a file path.
         *
         * @param path The file object refers to the font file.
         */
        public Builder(@NonNull File path) {
            mFontBuilder = new Font.Builder(path);
            mAssetManager = null;
            mPath = null;
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
            Font.Builder builder;
            try {
                builder = new Font.Builder(ParcelFileDescriptor.dup(fd));
            } catch (IOException e) {
                // We cannot tell the error to developer at this moment since we cannot change the
                // public API signature. Instead, silently fallbacks to system fallback in the build
                // method as the same as other error cases.
                builder = null;
            }
            mFontBuilder = builder;
            mAssetManager = null;
            mPath = null;
        }

        /**
         * Constructs a builder with a file path.
         *
         * @param path The full path to the font file.
         */
        public Builder(@NonNull String path) {
            mFontBuilder = new Font.Builder(new File(path));
            mAssetManager = null;
            mPath = null;
        }

        /**
         * Constructs a builder from an asset manager and a file path in an asset directory.
         *
         * @param assetManager The application's asset manager
         * @param path The file name of the font data in the asset directory
         */
        public Builder(@NonNull AssetManager assetManager, @NonNull String path) {
            this(assetManager, path, true /* is asset */, 0 /* cookie */);
        }

        /**
         * Constructs a builder from an asset manager and a file path in an asset directory.
         *
         * @param assetManager The application's asset manager
         * @param path The file name of the font data in the asset directory
         * @param cookie a cookie for the asset
         * @hide
         */
        public Builder(@NonNull AssetManager assetManager, @NonNull String path, boolean isAsset,
                int cookie) {
            mFontBuilder = new Font.Builder(assetManager, path, isAsset, cookie);
            mAssetManager = assetManager;
            mPath = path;
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
            mFontBuilder.setWeight(weight);
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
            mItalic = italic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT;
            mFontBuilder.setSlant(mItalic);
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
            mFontBuilder.setTtcIndex(ttcIndex);
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
            mFontBuilder.setFontVariationSettings(variationSettings);
            return this;
        }

        /**
         * Sets a font variation settings.
         *
         * @param axes An array of font variation axis tag-value pairs.
         */
        public Builder setFontVariationSettings(@Nullable FontVariationAxis[] axes) {
            mFontBuilder.setFontVariationSettings(axes);
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
         * Similarly, if {@link #setItalic} is not called, the fallback family keeps the default
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

            final Typeface base =  getSystemDefaultTypeface(mFallbackFamilyName);
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
            if (mFontBuilder == null) {
                return resolveFallbackTypeface();
            }
            try {
                final Font font = mFontBuilder.build();
                final String key = mAssetManager == null ? null : createAssetUid(
                        mAssetManager, mPath, font.getTtcIndex(), font.getAxes(),
                        mWeight, mItalic,
                        mFallbackFamilyName == null ? DEFAULT_FAMILY : mFallbackFamilyName);
                if (key != null) {
                    // Dynamic cache lookup is only for assets.
                    synchronized (sDynamicCacheLock) {
                        final Typeface typeface = sDynamicTypefaceCache.get(key);
                        if (typeface != null) {
                            return typeface;
                        }
                    }
                }
                final FontFamily family = new FontFamily.Builder(font).build();
                final int weight = mWeight == RESOLVE_BY_FONT_TABLE
                        ? font.getStyle().getWeight() : mWeight;
                final int slant = mItalic == RESOLVE_BY_FONT_TABLE
                        ? font.getStyle().getSlant() : mItalic;
                final CustomFallbackBuilder builder = new CustomFallbackBuilder(family)
                        .setStyle(new FontStyle(weight, slant));
                if (mFallbackFamilyName != null) {
                    builder.setSystemFallback(mFallbackFamilyName);
                }
                final Typeface typeface = builder.build();
                if (key != null) {
                    synchronized (sDynamicCacheLock) {
                        sDynamicTypefaceCache.put(key, typeface);
                    }
                }
                return typeface;
            } catch (IOException | IllegalArgumentException e) {
                return resolveFallbackTypeface();
            }
        }
    }

    /**
     * A builder class for creating new Typeface instance.
     *
     * There are two font fallback mechanisms, custom font fallback and system font fallback.
     * The custom font fallback is a simple ordered list. The text renderer tries to see if it can
     * render a character with the first font and if that font does not support the character, try
     * next one and so on. It will keep trying until end of the custom fallback chain. The maximum
     * length of the custom fallback chain is 64.
     * The system font fallback is a system pre-defined fallback chain. The system fallback is
     * processed only when no matching font is found in the custom font fallback.
     *
     * <p>
     * Examples,
     * 1) Create Typeface from single ttf file.
     * <pre>
     * <code>
     * Font font = new Font.Builder("your_font_file.ttf").build();
     * FontFamily family = new FontFamily.Builder(font).build();
     * Typeface typeface = new Typeface.CustomFallbackBuilder(family).build();
     * </code>
     * </pre>
     *
     * 2) Create Typeface from multiple font files and select bold style by default.
     * <pre>
     * <code>
     * Font regularFont = new Font.Builder("regular.ttf").build();
     * Font boldFont = new Font.Builder("bold.ttf").build();
     * FontFamily family = new FontFamily.Builder(regularFont)
     *     .addFont(boldFont).build();
     * Typeface typeface = new Typeface.CustomFallbackBuilder(family)
     *     .setWeight(Font.FONT_WEIGHT_BOLD)  // Set bold style as the default style.
     *                                        // If the font family doesn't have bold style font,
     *                                        // system will select the closest font.
     *     .build();
     * </code>
     * </pre>
     *
     * 3) Create Typeface from single ttf file and if that font does not have glyph for the
     * characters, use "serif" font family instead.
     * <pre>
     * <code>
     * Font font = new Font.Builder("your_font_file.ttf").build();
     * FontFamily family = new FontFamily.Builder(font).build();
     * Typeface typeface = new Typeface.CustomFallbackBuilder(family)
     *     .setSystemFallback("serif")  // Set serif font family as the fallback.
     *     .build();
     * </code>
     * </pre>
     * 4) Create Typeface from single ttf file and set another ttf file for the fallback.
     * <pre>
     * <code>
     * Font font = new Font.Builder("English.ttf").build();
     * FontFamily family = new FontFamily.Builder(font).build();
     *
     * Font fallbackFont = new Font.Builder("Arabic.ttf").build();
     * FontFamily fallbackFamily = new FontFamily.Builder(fallbackFont).build();
     * Typeface typeface = new Typeface.CustomFallbackBuilder(family)
     *     .addCustomFallback(fallbackFamily)  // Specify fallback family.
     *     .setSystemFallback("serif")  // Set serif font family as the fallback.
     *     .build();
     * </code>
     * </pre>
     * </p>
     */
    public static final class CustomFallbackBuilder {
        private static final int MAX_CUSTOM_FALLBACK = 64;
        private final ArrayList<FontFamily> mFamilies = new ArrayList<>();
        private String mFallbackName = null;
        private @Nullable FontStyle mStyle;

        /**
         * Returns the maximum capacity of custom fallback families.
         *
         * This includes the first font family passed to the constructor.
         * It is guaranteed that the value will be greater than or equal to 64.
         *
         * @return the maximum number of font families for the custom fallback
         */
        public static @IntRange(from = 64) int getMaxCustomFallbackCount() {
            return MAX_CUSTOM_FALLBACK;
        }

        /**
         * Constructs a builder with a font family.
         *
         * @param family a family object
         */
        public CustomFallbackBuilder(@NonNull FontFamily family) {
            Preconditions.checkNotNull(family);
            mFamilies.add(family);
        }

        /**
         * Sets a system fallback by name.
         *
         * You can specify generic font family names or OEM specific family names. If the system
         * don't have a specified fallback, the default fallback is used instead.
         * For more information about generic font families, see <a
         * href="https://www.w3.org/TR/css-fonts-4/#generic-font-families">CSS specification</a>
         *
         * For more information about fallback, see class description.
         *
         * @param familyName a family name to be used for fallback if the provided fonts can not be
         *                   used
         */
        public @NonNull CustomFallbackBuilder setSystemFallback(@NonNull String familyName) {
            Preconditions.checkNotNull(familyName);
            mFallbackName = familyName;
            return this;
        }

        /**
         * Sets a font style of the Typeface.
         *
         * If the font family doesn't have a font of given style, system will select the closest
         * font from font family. For example, if a font family has fonts of 300 weight and 700
         * weight then setWeight(400) is called, system will select the font of 300 weight.
         *
         * @param style a font style
         */
        public @NonNull CustomFallbackBuilder setStyle(@NonNull FontStyle style) {
            mStyle = style;
            return this;
        }

        /**
         * Append a font family to the end of the custom font fallback.
         *
         * You can set up to 64 custom fallback families including the first font family you passed
         * to the constructor.
         * For more information about fallback, see class description.
         *
         * @param family a fallback family
         * @throws IllegalArgumentException if you give more than 64 custom fallback families
         */
        public @NonNull CustomFallbackBuilder addCustomFallback(@NonNull FontFamily family) {
            Preconditions.checkNotNull(family);
            Preconditions.checkArgument(mFamilies.size() < getMaxCustomFallbackCount(),
                    "Custom fallback limit exceeded(%d)", getMaxCustomFallbackCount());
            mFamilies.add(family);
            return this;
        }

        /**
         * Create the Typeface based on the configured values.
         *
         * @return the Typeface object
         */
        public @NonNull Typeface build() {
            final int userFallbackSize = mFamilies.size();
            final Typeface fallbackTypeface = getSystemDefaultTypeface(mFallbackName);
            final long[] ptrArray = new long[userFallbackSize];
            for (int i = 0; i < userFallbackSize; ++i) {
                ptrArray[i] = mFamilies.get(i).getNativePtr();
            }
            final int weight = mStyle == null ? 400 : mStyle.getWeight();
            final int italic =
                    (mStyle == null || mStyle.getSlant() == FontStyle.FONT_SLANT_UPRIGHT) ?  0 : 1;
            return new Typeface(nativeCreateFromArray(
                    ptrArray, fallbackTypeface.native_instance, weight, italic), null);
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
        return create(getSystemDefaultTypeface(familyName), style);
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
            family = getDefault();
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

            typeface = new Typeface(nativeCreateFromTypeface(ni, style),
                    family.getSystemFontFamilyName());
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
            family = getDefault();
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
                    nativeCreateFromTypefaceWithExactStyle(base.native_instance, weight, italic),
                    base.getSystemFontFamilyName());
            innerCache.put(key, typeface);
        }
        return typeface;
    }

    /** @hide */
    public static Typeface createFromTypefaceWithVariation(@Nullable Typeface family,
            @NonNull List<FontVariationAxis> axes) {
        final Typeface base = family == null ? Typeface.DEFAULT : family;
        Typeface typeface = new Typeface(
                nativeCreateFromTypefaceWithVariation(base.native_instance, axes),
                base.getSystemFontFamilyName());
        return typeface;
    }

    /**
     * Returns one of the default typeface objects, based on the specified style
     *
     * @return the default typeface that corresponds to the style
     */
    public static Typeface defaultFromStyle(@Style int style) {
        synchronized (SYSTEM_FONT_MAP_LOCK) {
            return sDefaults[style];
        }
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
     * @deprecated
     */
    @Deprecated
    @UnsupportedAppUsage(trackingBug = 123768928)
    private static Typeface createFromFamilies(android.graphics.FontFamily[] families) {
        long[] ptrArray = new long[families.length];
        for (int i = 0; i < families.length; i++) {
            ptrArray[i] = families[i].mNativePtr;
        }
        return new Typeface(nativeCreateFromArray(
                ptrArray, 0, RESOLVE_BY_FONT_TABLE,
                RESOLVE_BY_FONT_TABLE), null);
    }

    /**
     * Create a new typeface from an array of android.graphics.fonts.FontFamily.
     *
     * @param families array of font families
     */
    private static Typeface createFromFamilies(@NonNull String familyName,
            @Nullable FontFamily[] families) {
        final long[] ptrArray = new long[families.length];
        for (int i = 0; i < families.length; ++i) {
            ptrArray[i] = families[i].getNativePtr();
        }
        return new Typeface(nativeCreateFromArray(ptrArray, 0,
                  RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE), familyName);
    }

    /**
     * This method is used by supportlib-v27.
     *
     * @deprecated Use {@link android.graphics.fonts.FontFamily} instead.
     */
    @UnsupportedAppUsage(trackingBug = 123768395)
    @Deprecated
    private static Typeface createFromFamiliesWithDefault(
            android.graphics.FontFamily[] families, int weight, int italic) {
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
     *
     * @deprecated Use {@link android.graphics.fonts.FontFamily} instead.
     */
    @UnsupportedAppUsage(trackingBug = 123768928)
    @Deprecated
    private static Typeface createFromFamiliesWithDefault(android.graphics.FontFamily[] families,
                String fallbackName, int weight, int italic) {
        final Typeface fallbackTypeface = getSystemDefaultTypeface(fallbackName);
        long[] ptrArray = new long[families.length];
        for (int i = 0; i < families.length; i++) {
            ptrArray[i] = families[i].mNativePtr;
        }
        return new Typeface(nativeCreateFromArray(
                ptrArray, fallbackTypeface.native_instance, weight, italic), null);
    }

    // don't allow clients to call this directly
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private Typeface(long ni) {
        this(ni, null);
    }

    // don't allow clients to call this directly
    private Typeface(long ni, @Nullable String systemFontFamilyName) {
        if (ni == 0) {
            throw new RuntimeException("native typeface cannot be made");
        }

        native_instance = ni;
        mCleaner = sRegistry.registerNativeAllocation(this, native_instance);
        mStyle = nativeGetStyle(ni);
        mWeight = nativeGetWeight(ni);
        mSystemFontFamilyName = systemFontFamilyName;
    }

    /**
     * Releases the underlying native object.
     *
     * <p>For testing only. Do not use the instance after this method is called.
     * It is safe to call this method twice or more on the same instance.
     * @hide
     */
    @TestApi
    public void releaseNativeObjectForTest() {
        mCleaner.run();
    }

    private static Typeface getSystemDefaultTypeface(@NonNull String familyName) {
        Typeface tf = sSystemFontMap.get(familyName);
        return tf == null ? Typeface.DEFAULT : tf;
    }

    /** @hide */
    @VisibleForTesting
    public static void initSystemDefaultTypefaces(Map<String, FontFamily[]> fallbacks,
            List<FontConfig.Alias> aliases,
            Map<String, Typeface> outSystemFontMap) {
        for (Map.Entry<String, FontFamily[]> entry : fallbacks.entrySet()) {
            outSystemFontMap.put(entry.getKey(),
                    createFromFamilies(entry.getKey(), entry.getValue()));
        }

        for (int i = 0; i < aliases.size(); ++i) {
            final FontConfig.Alias alias = aliases.get(i);
            if (outSystemFontMap.containsKey(alias.getName())) {
                continue; // If alias and named family are conflict, use named family.
            }
            final Typeface base = outSystemFontMap.get(alias.getOriginal());
            if (base == null) {
                // The missing target is a valid thing, some configuration don't have font files,
                // e.g. wear devices. Just skip this alias.
                continue;
            }
            final int weight = alias.getWeight();
            final Typeface newFace = weight == 400 ? base : new Typeface(
                    nativeCreateWeightAlias(base.native_instance, weight), alias.getName());
            outSystemFontMap.put(alias.getName(), newFace);
        }
    }

    private static void registerGenericFamilyNative(@NonNull String familyName,
            @Nullable Typeface typeface) {
        if (typeface != null) {
            nativeRegisterGenericFamily(familyName, typeface.native_instance);
        }
    }

    /**
     * Create a serialized system font mappings.
     *
     * @hide
     */
    @TestApi
    public static @NonNull SharedMemory serializeFontMap(@NonNull Map<String, Typeface> fontMap)
            throws IOException, ErrnoException {
        long[] nativePtrs = new long[fontMap.size()];
        // The name table will not be large, so let's create a byte array in memory.
        ByteArrayOutputStream namesBytes = new ByteArrayOutputStream();
        int i = 0;
        for (Map.Entry<String, Typeface> entry : fontMap.entrySet()) {
            nativePtrs[i++] = entry.getValue().native_instance;
            writeString(namesBytes, entry.getKey());
        }
        // int (typefacesBytesCount), typefaces, namesBytes
        final int typefaceBytesCountSize = Integer.BYTES;
        int typefacesBytesCount = nativeWriteTypefaces(null, typefaceBytesCountSize, nativePtrs);
        SharedMemory sharedMemory = SharedMemory.create(
                "fontMap", typefaceBytesCountSize + typefacesBytesCount + namesBytes.size());
        ByteBuffer writableBuffer = sharedMemory.mapReadWrite().order(ByteOrder.BIG_ENDIAN);
        try {
            writableBuffer.putInt(typefacesBytesCount);
            int writtenBytesCount =
                    nativeWriteTypefaces(writableBuffer, writableBuffer.position(), nativePtrs);
            if (writtenBytesCount != typefacesBytesCount) {
                throw new IOException(String.format("Unexpected bytes written: %d, expected: %d",
                        writtenBytesCount, typefacesBytesCount));
            }
            writableBuffer.position(writableBuffer.position() + writtenBytesCount);
            writableBuffer.put(namesBytes.toByteArray());
        } finally {
            SharedMemory.unmap(writableBuffer);
        }
        sharedMemory.setProtect(OsConstants.PROT_READ);
        return sharedMemory;
    }

    // buffer's byte order should be BIG_ENDIAN.
    /**
     * Deserialize the font mapping from the serialized byte buffer.
     *
     * <p>Warning: the given {@code buffer} must outlive generated Typeface
     * objects in {@code out}. In production code, this is guaranteed by
     * storing the buffer in {@link #sSystemFontMapBuffer}.
     * If you call this method in a test, please make sure to destroy the
     * generated Typeface objects by calling
     * {@link #releaseNativeObjectForTest()}.
     *
     * @hide
     */
    @TestApi
    public static @NonNull long[] deserializeFontMap(
            @NonNull ByteBuffer buffer, @NonNull Map<String, Typeface> out)
            throws IOException {
        int typefacesBytesCount = buffer.getInt();
        // Note: Do not call buffer.slice(), as nativeReadTypefaces() expects
        // that buffer.address() is page-aligned.
        long[] nativePtrs = nativeReadTypefaces(buffer, buffer.position());
        if (nativePtrs == null) {
            throw new IOException("Could not read typefaces");
        }
        out.clear();
        buffer.position(buffer.position() + typefacesBytesCount);
        for (long nativePtr : nativePtrs) {
            String name = readString(buffer);
            out.put(name, new Typeface(nativePtr, name));
        }
        return nativePtrs;
    }

    private static String readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes);
    }

    private static void writeString(ByteArrayOutputStream bos, String value) throws IOException {
        byte[] bytes = value.getBytes();
        writeInt(bos, bytes.length);
        bos.write(bytes);
    }

    private static void writeInt(ByteArrayOutputStream bos, int value) {
        // Write in the big endian order.
        bos.write((value >> 24) & 0xFF);
        bos.write((value >> 16) & 0xFF);
        bos.write((value >> 8) & 0xFF);
        bos.write(value & 0xFF);
    }

    /** @hide */
    public static Map<String, Typeface> getSystemFontMap() {
        synchronized (SYSTEM_FONT_MAP_LOCK) {
            return sSystemFontMap;
        }
    }

    /**
     * Deserialize font map and set it as system font map. This method should be called at most once
     * per process.
     */
    /** @hide */
    @UiThread
    public static void setSystemFontMap(@Nullable SharedMemory sharedMemory)
            throws IOException, ErrnoException {
        if (sSystemFontMapBuffer != null) {
            // Apps can re-send BIND_APPLICATION message from their code. This is a work around to
            // detect it and avoid crashing.
            if (sharedMemory == null || sharedMemory == sSystemFontMapSharedMemory) {
                return;
            }
            throw new UnsupportedOperationException(
                    "Once set, buffer-based system font map cannot be updated");
        }
        sSystemFontMapSharedMemory = sharedMemory;
        Trace.traceBegin(Trace.TRACE_TAG_GRAPHICS, "setSystemFontMap");
        try {
            if (sharedMemory == null) {
                // FontManagerService is not started. This may happen in FACTORY_TEST_LOW_LEVEL
                // mode for example.
                loadPreinstalledSystemFontMap();
                return;
            }
            sSystemFontMapBuffer = sharedMemory.mapReadOnly().order(ByteOrder.BIG_ENDIAN);
            Map<String, Typeface> systemFontMap = new ArrayMap<>();
            long[] nativePtrs = deserializeFontMap(sSystemFontMapBuffer, systemFontMap);

            // Initialize native font APIs. The native font API will read fonts.xml by itself if
            // Typeface is initialized with loadPreinstalledSystemFontMap.
            for (long ptr : nativePtrs) {
                nativeAddFontCollections(ptr);
            }
            setSystemFontMap(systemFontMap);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_GRAPHICS);
        }
    }

    /** @hide */
    @VisibleForTesting
    public static void setSystemFontMap(Map<String, Typeface> systemFontMap) {
        synchronized (SYSTEM_FONT_MAP_LOCK) {
            sSystemFontMap.clear();
            sSystemFontMap.putAll(systemFontMap);

            // We can't assume DEFAULT_FAMILY available on Roboletric.
            if (sSystemFontMap.containsKey(DEFAULT_FAMILY)) {
                setDefault(sSystemFontMap.get(DEFAULT_FAMILY));
            }

            // Set up defaults and typefaces exposed in public API
            // Use sDefaultTypeface here, because create(String, int) uses DEFAULT as fallback.
            nativeForceSetStaticFinalField("DEFAULT", create(sDefaultTypeface, 0));
            nativeForceSetStaticFinalField("DEFAULT_BOLD", create(sDefaultTypeface, Typeface.BOLD));
            nativeForceSetStaticFinalField("SANS_SERIF", create("sans-serif", 0));
            nativeForceSetStaticFinalField("SERIF", create("serif", 0));
            nativeForceSetStaticFinalField("MONOSPACE", create("monospace", 0));

            sDefaults = new Typeface[]{
                DEFAULT,
                DEFAULT_BOLD,
                create((String) null, Typeface.ITALIC),
                create((String) null, Typeface.BOLD_ITALIC),
            };

            // A list of generic families to be registered in native.
            // https://www.w3.org/TR/css-fonts-4/#generic-font-families
            String[] genericFamilies = {
                "serif", "sans-serif", "cursive", "fantasy", "monospace", "system-ui"
            };

            for (String genericFamily : genericFamilies) {
                registerGenericFamilyNative(genericFamily, systemFontMap.get(genericFamily));
            }
        }
    }

    /**
     * Change default typefaces for testing purpose.
     *
     * Note: The existing TextView or Paint instance still holds the old Typeface.
     *
     * @param defaults array of [default, default_bold, default_italic, default_bolditalic].
     * @param genericFamilies array of [sans-serif, serif, monospace]
     * @return return the old defaults and genericFamilies
     * @hide
     */
    @TestApi
    @NonNull
    public static Pair<List<Typeface>, List<Typeface>> changeDefaultFontForTest(
            @NonNull List<Typeface> defaults,
            @NonNull List<Typeface> genericFamilies
    ) {
        synchronized (SYSTEM_FONT_MAP_LOCK) {
            List<Typeface> oldDefaults = Arrays.asList(sDefaults);
            sDefaults = defaults.toArray(new Typeface[4]);
            setDefault(defaults.get(0));

            ArrayList<Typeface> oldGenerics = new ArrayList<>();
            oldGenerics.add(sSystemFontMap.get("sans-serif"));
            sSystemFontMap.put("sans-serif", genericFamilies.get(0));

            oldGenerics.add(sSystemFontMap.get("serif"));
            sSystemFontMap.put("serif", genericFamilies.get(1));

            oldGenerics.add(sSystemFontMap.get("monospace"));
            sSystemFontMap.put("monospace", genericFamilies.get(2));

            return new Pair<>(oldDefaults, oldGenerics);
        }
    }

    static {
        // Preload Roboto-Regular.ttf in Zygote for improving app launch performance.
        preloadFontFile("/system/fonts/Roboto-Regular.ttf");
        preloadFontFile("/system/fonts/RobotoStatic-Regular.ttf");

        String locale = SystemProperties.get("persist.sys.locale", "en-US");
        String script = ULocale.addLikelySubtags(ULocale.forLanguageTag(locale)).getScript();

        // The feature flag cannot be referred from Zygote. Use legacy fonts.xml for preloading font
        // files.
        // TODO(nona): Use new XML file once the feature is fully launched.
        FontConfig config = SystemFonts.getSystemPreinstalledFontConfigFromLegacyXml();
        for (int i = 0; i < config.getFontFamilies().size(); ++i) {
            FontConfig.FontFamily family = config.getFontFamilies().get(i);
            if (!family.getLocaleList().isEmpty()) {
                nativeRegisterLocaleList(family.getLocaleList().toLanguageTags());
            }
            boolean loadFamily = false;
            for (int j = 0; j < family.getLocaleList().size(); ++j) {
                String fontScript = ULocale.addLikelySubtags(
                        ULocale.forLocale(family.getLocaleList().get(j))).getScript();
                loadFamily = fontScript.equals(script);
                if (loadFamily) {
                    break;
                }
            }
            if (loadFamily) {
                for (int j = 0; j < family.getFontList().size(); ++j) {
                    preloadFontFile(family.getFontList().get(j).getFile().getAbsolutePath());
                }
            }
        }
    }

    private static void preloadFontFile(String filePath) {
        File file = new File(filePath);
        if (file.exists()) {
            Log.i(TAG, "Preloading " + file.getAbsolutePath());
            nativeWarmUpCache(filePath);
        }
    }

    /** @hide */
    @VisibleForTesting
    public static void destroySystemFontMap() {
        synchronized (SYSTEM_FONT_MAP_LOCK) {
            for (Typeface typeface : sSystemFontMap.values()) {
                typeface.releaseNativeObjectForTest();
            }
            sSystemFontMap.clear();
            if (sSystemFontMapBuffer != null) {
                SharedMemory.unmap(sSystemFontMapBuffer);
            }
            sSystemFontMapBuffer = null;
            sSystemFontMapSharedMemory = null;
            synchronized (sStyledCacheLock) {
                destroyTypefaceCacheLocked(sStyledTypefaceCache);
            }
            synchronized (sWeightCacheLock) {
                destroyTypefaceCacheLocked(sWeightTypefaceCache);
            }
        }
    }

    private static void destroyTypefaceCacheLocked(LongSparseArray<SparseArray<Typeface>> cache) {
        for (int i = 0; i < cache.size(); i++) {
            SparseArray<Typeface> array = cache.valueAt(i);
            for (int j = 0; j < array.size(); j++) {
                array.valueAt(j).releaseNativeObjectForTest();
            }
        }
        cache.clear();
    }

    /** @hide */
    public static void loadPreinstalledSystemFontMap() {
        final FontConfig fontConfig = SystemFonts.getSystemPreinstalledFontConfig();
        final Map<String, FontFamily[]> fallback = SystemFonts.buildSystemFallback(fontConfig);
        final Map<String, Typeface> typefaceMap =
                SystemFonts.buildSystemTypefaces(fontConfig, fallback);
        setSystemFontMap(typefaceMap);
    }

    static {
        if (!ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
            loadPreinstalledSystemFontMap();
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
        synchronized (this) {
            if (mSupportedAxes == null) {
                mSupportedAxes = nativeGetSupportedAxes(native_instance);
                if (mSupportedAxes == null) {
                    mSupportedAxes = EMPTY_AXES;
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
    @UnsupportedAppUsage
    private static native long nativeCreateWeightAlias(long native_instance, int weight);
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static native long nativeCreateFromArray(
            long[] familyArray, long fallbackTypeface, int weight, int italic);
    private static native int[] nativeGetSupportedAxes(long native_instance);

    @CriticalNative
    private static native void nativeSetDefault(long nativePtr);

    @CriticalNative
    private static native int  nativeGetStyle(long nativePtr);

    @CriticalNative
    private static native int  nativeGetWeight(long nativePtr);

    @CriticalNative
    private static native long nativeGetReleaseFunc();

    private static native void nativeRegisterGenericFamily(String str, long nativePtr);

    private static native int nativeWriteTypefaces(
            @Nullable ByteBuffer buffer, int position, @NonNull long[] nativePtrs);

    private static native
            @Nullable long[] nativeReadTypefaces(@NonNull ByteBuffer buffer, int position);

    private static native void nativeForceSetStaticFinalField(String fieldName, Typeface typeface);

    @CriticalNative
    private static native void nativeAddFontCollections(long nativePtr);

    private static native void nativeWarmUpCache(String fileName);

    @FastNative
    private static native void nativeRegisterLocaleList(String locales);
}
