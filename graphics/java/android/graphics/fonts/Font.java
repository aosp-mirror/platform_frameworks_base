/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.graphics.fonts;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.LongSparseLongArray;
import android.util.TypedValue;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Objects;

/**
 * A font class can be used for creating FontFamily.
 */
public final class Font {
    private static final String TAG = "Font";

    private static final int NOT_SPECIFIED = -1;
    private static final int STYLE_ITALIC = 1;
    private static final int STYLE_NORMAL = 0;

    private static final Object MAP_LOCK = new Object();
    // We need to have mapping from native ptr to Font object for later accessing from TextShape
    // result since Typeface doesn't have reference to Font object and it is not always created from
    // Font object. Sometimes Typeface is created in native layer only and there might not be Font
    // object in Java layer. So, if not found in this cache, create new Font object for API user.
    @GuardedBy("MAP_LOCK")
    private static final LongSparseArray<WeakReference<Font>> FONT_PTR_MAP =
            new LongSparseArray<>();

    private static final Object SOURCE_ID_LOCK = new Object();
    @GuardedBy("SOURCE_ID_LOCK")
    private static final LongSparseLongArray FONT_SOURCE_ID_MAP =
            new LongSparseLongArray(300);  // System font has 200 fonts, so 300 should be enough.

    /**
     * A builder class for creating new Font.
     */
    public static final class Builder {
        private static final NativeAllocationRegistry sFontRegistry =
                NativeAllocationRegistry.createMalloced(Font.class.getClassLoader(),
                    nGetReleaseNativeFont());

        private @Nullable ByteBuffer mBuffer;
        private @Nullable File mFile;
        private @Nullable Font mFont;
        private @NonNull String mLocaleList = "";
        private @IntRange(from = -1, to = 1000) int mWeight = NOT_SPECIFIED;
        private @IntRange(from = -1, to = 1) int mItalic = NOT_SPECIFIED;
        private @IntRange(from = 0) int mTtcIndex = 0;
        private @Nullable FontVariationAxis[] mAxes = null;
        private @Nullable IOException mException;

        /**
         * Constructs a builder with a byte buffer.
         *
         * Note that only direct buffer can be used as the source of font data.
         *
         * @see ByteBuffer#allocateDirect(int)
         * @param buffer a byte buffer of a font data
         */
        public Builder(@NonNull ByteBuffer buffer) {
            Preconditions.checkNotNull(buffer, "buffer can not be null");
            if (!buffer.isDirect()) {
                throw new IllegalArgumentException(
                        "Only direct buffer can be used as the source of font data.");
            }
            mBuffer = buffer;
        }

        /**
         * Construct a builder with a byte buffer and file path.
         *
         * This method is intended to be called only from SystemFonts.
         * @hide
         */
        public Builder(@NonNull ByteBuffer buffer, @NonNull File path,
                @NonNull String localeList) {
            this(buffer);
            mFile = path;
            mLocaleList = localeList;
        }

        /**
         * Construct a builder with a byte buffer and file path.
         *
         * This method is intended to be called only from SystemFonts.
         * @param path font file path
         * @param localeList comma concatenated BCP47 compliant language tag.
         * @hide
         */
        public Builder(@NonNull File path, @NonNull String localeList) {
            this(path);
            mLocaleList = localeList;
        }

        /**
         * Constructs a builder with a file path.
         *
         * @param path a file path to the font file
         */
        public Builder(@NonNull File path) {
            Preconditions.checkNotNull(path, "path can not be null");
            try (FileInputStream fis = new FileInputStream(path)) {
                final FileChannel fc = fis.getChannel();
                mBuffer = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            } catch (IOException e) {
                mException = e;
            }
            mFile = path;
        }

        /**
         * Constructs a builder with a file descriptor.
         *
         * @param fd a file descriptor
         */
        public Builder(@NonNull ParcelFileDescriptor fd) {
            this(fd, 0, -1);
        }

        /**
         * Constructs a builder with a file descriptor.
         *
         * @param fd a file descriptor
         * @param offset an offset to of the font data in the file
         * @param size a size of the font data. If -1 is passed, use until end of the file.
         */
        public Builder(@NonNull ParcelFileDescriptor fd, @IntRange(from = 0) long offset,
                @IntRange(from = -1) long size) {
            try (FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
                final FileChannel fc = fis.getChannel();
                size = (size == -1) ? fc.size() - offset : size;
                mBuffer = fc.map(FileChannel.MapMode.READ_ONLY, offset, size);
            } catch (IOException e) {
                mException = e;
            }
        }

        /**
         * Constructs a builder from an asset manager and a file path in an asset directory.
         *
         * @param am the application's asset manager
         * @param path the file name of the font data in the asset directory
         */
        public Builder(@NonNull AssetManager am, @NonNull String path) {
            try {
                mBuffer = createBuffer(am, path, true /* is asset */, 0 /* cookie */);
            } catch (IOException e) {
                mException = e;
            }
        }

        /**
         * Constructs a builder from an asset manager and a file path in an asset directory.
         *
         * @param am the application's asset manager
         * @param path the file name of the font data in the asset directory
         * @param isAsset true if the undelying data is in asset
         * @param cookie set asset cookie
         * @hide
         */
        public Builder(@NonNull AssetManager am, @NonNull String path, boolean isAsset,
                int cookie) {
            try {
                mBuffer = createBuffer(am, path, isAsset, cookie);
            } catch (IOException e) {
                mException = e;
            }
        }

        /**
         * Constructs a builder from resources.
         *
         * Resource ID must points the font file. XML font can not be used here.
         *
         * @param res the resource of this application.
         * @param resId the resource ID of font file.
         */
        public Builder(@NonNull Resources res, int resId) {
            final TypedValue value = new TypedValue();
            res.getValue(resId, value, true);
            if (value.string == null) {
                mException = new FileNotFoundException(resId + " not found");
                return;
            }
            final String str = value.string.toString();
            if (str.toLowerCase().endsWith(".xml")) {
                mException = new FileNotFoundException(resId + " must be font file.");
                return;
            }

            try {
                mBuffer = createBuffer(res.getAssets(), str, false, value.assetCookie);
            } catch (IOException e) {
                mException = e;
            }
        }

        /**
         * Constructs a builder from existing Font instance.
         *
         * @param font the font instance.
         */
        public Builder(@NonNull Font font) {
            mFont = font;
            // Copies all parameters as a default value.
            mBuffer = font.getBuffer();
            mWeight = font.getStyle().getWeight();
            mItalic = font.getStyle().getSlant();
            mAxes = font.getAxes();
            mFile = font.getFile();
            mTtcIndex = font.getTtcIndex();
        }

        /**
         * Creates a buffer containing font data using the assetManager and other
         * provided inputs.
         *
         * @param am the application's asset manager
         * @param path the file name of the font data in the asset directory
         * @param isAsset true if the undelying data is in asset
         * @param cookie set asset cookie
         * @return buffer containing the contents of the file
         *
         * @hide
         */
        public static ByteBuffer createBuffer(@NonNull AssetManager am, @NonNull String path,
                                              boolean isAsset, int cookie) throws IOException {
            Preconditions.checkNotNull(am, "assetManager can not be null");
            Preconditions.checkNotNull(path, "path can not be null");

            // Attempt to open as FD, which should work unless the asset is compressed
            AssetFileDescriptor assetFD;
            try {
                if (isAsset) {
                    assetFD = am.openFd(path);
                } else if (cookie > 0) {
                    assetFD = am.openNonAssetFd(cookie, path);
                } else {
                    assetFD = am.openNonAssetFd(path);
                }

                try (FileInputStream fis = assetFD.createInputStream()) {
                    final FileChannel fc = fis.getChannel();
                    long startOffset = assetFD.getStartOffset();
                    long declaredLength = assetFD.getDeclaredLength();
                    return fc.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
                }
            } catch (IOException e) {
                // failed to open as FD so now we will attempt to open as an input stream
            }

            try (InputStream assetStream = isAsset ? am.open(path, AssetManager.ACCESS_BUFFER)
                    : am.openNonAsset(cookie, path, AssetManager.ACCESS_BUFFER)) {

                int capacity = assetStream.available();
                ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
                buffer.order(ByteOrder.nativeOrder());
                assetStream.read(buffer.array(), buffer.arrayOffset(), assetStream.available());

                if (assetStream.read() != -1) {
                    throw new IOException("Unable to access full contents of " + path);
                }

                return buffer;
            }
        }

        /**
         * Sets weight of the font.
         *
         * Tells the system the weight of the given font. If this function is not called, the system
         * will resolve the weight value by reading font tables.
         *
         * Here are pairs of the common names and their values.
         * <p>
         *  <table>
         *  <thead>
         *  <tr>
         *  <th align="center">Value</th>
         *  <th align="center">Name</th>
         *  <th align="center">Android Definition</th>
         *  </tr>
         *  </thead>
         *  <tbody>
         *  <tr>
         *  <td align="center">100</td>
         *  <td align="center">Thin</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_THIN}</td>
         *  </tr>
         *  <tr>
         *  <td align="center">200</td>
         *  <td align="center">Extra Light (Ultra Light)</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_EXTRA_LIGHT}</td>
         *  </tr>
         *  <tr>
         *  <td align="center">300</td>
         *  <td align="center">Light</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_LIGHT}</td>
         *  </tr>
         *  <tr>
         *  <td align="center">400</td>
         *  <td align="center">Normal (Regular)</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_NORMAL}</td>
         *  </tr>
         *  <tr>
         *  <td align="center">500</td>
         *  <td align="center">Medium</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_MEDIUM}</td>
         *  </tr>
         *  <tr>
         *  <td align="center">600</td>
         *  <td align="center">Semi Bold (Demi Bold)</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_SEMI_BOLD}</td>
         *  </tr>
         *  <tr>
         *  <td align="center">700</td>
         *  <td align="center">Bold</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_BOLD}</td>
         *  </tr>
         *  <tr>
         *  <td align="center">800</td>
         *  <td align="center">Extra Bold (Ultra Bold)</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_EXTRA_BOLD}</td>
         *  </tr>
         *  <tr>
         *  <td align="center">900</td>
         *  <td align="center">Black (Heavy)</td>
         *  <td align="center">{@link FontStyle#FONT_WEIGHT_BLACK}</td>
         *  </tr>
         *  </tbody>
         * </p>
         *
         * @see FontStyle#FONT_WEIGHT_THIN
         * @see FontStyle#FONT_WEIGHT_EXTRA_LIGHT
         * @see FontStyle#FONT_WEIGHT_LIGHT
         * @see FontStyle#FONT_WEIGHT_NORMAL
         * @see FontStyle#FONT_WEIGHT_MEDIUM
         * @see FontStyle#FONT_WEIGHT_SEMI_BOLD
         * @see FontStyle#FONT_WEIGHT_BOLD
         * @see FontStyle#FONT_WEIGHT_EXTRA_BOLD
         * @see FontStyle#FONT_WEIGHT_BLACK
         * @param weight a weight value
         * @return this builder
         */
        public @NonNull Builder setWeight(
                @IntRange(from = FontStyle.FONT_WEIGHT_MIN, to = FontStyle.FONT_WEIGHT_MAX)
                int weight) {
            Preconditions.checkArgument(
                    FontStyle.FONT_WEIGHT_MIN <= weight && weight <= FontStyle.FONT_WEIGHT_MAX);
            mWeight = weight;
            return this;
        }

        /**
         * Sets italic information of the font.
         *
         * Tells the system the style of the given font. If this function is not called, the system
         * will resolve the style by reading font tables.
         *
         * For example, if you want to use italic font as upright font, call {@code
         * setSlant(FontStyle.FONT_SLANT_UPRIGHT)} explicitly.
         *
         * @return this builder
         */
        public @NonNull Builder setSlant(@FontStyle.FontSlant int slant) {
            mItalic = slant == FontStyle.FONT_SLANT_UPRIGHT ? STYLE_NORMAL : STYLE_ITALIC;
            return this;
        }

        /**
         * Sets an index of the font collection. See {@link android.R.attr#ttcIndex}.
         *
         * @param ttcIndex An index of the font collection. If the font source is not font
         *                 collection, do not call this method or specify 0.
         * @return this builder
         */
        public @NonNull Builder setTtcIndex(@IntRange(from = 0) int ttcIndex) {
            mTtcIndex = ttcIndex;
            return this;
        }

        /**
         * Sets the font variation settings.
         *
         * @param variationSettings see {@link FontVariationAxis#fromFontVariationSettings(String)}
         * @return this builder
         * @throws IllegalArgumentException If given string is not a valid font variation settings
         *                                  format.
         */
        public @NonNull Builder setFontVariationSettings(@Nullable String variationSettings) {
            mAxes = FontVariationAxis.fromFontVariationSettings(variationSettings);
            return this;
        }

        /**
         * Sets the font variation settings.
         *
         * @param axes an array of font variation axis tag-value pairs
         * @return this builder
         */
        public @NonNull Builder setFontVariationSettings(@Nullable FontVariationAxis[] axes) {
            mAxes = axes == null ? null : axes.clone();
            return this;
        }

        /**
         * Creates the font based on the configured values.
         * @return the Font object
         */
        public @NonNull Font build() throws IOException {
            if (mException != null) {
                throw new IOException("Failed to read font contents", mException);
            }
            if (mWeight == NOT_SPECIFIED || mItalic == NOT_SPECIFIED) {
                final int packed = FontFileUtil.analyzeStyle(mBuffer, mTtcIndex, mAxes);
                if (FontFileUtil.isSuccess(packed)) {
                    if (mWeight == NOT_SPECIFIED) {
                        mWeight = FontFileUtil.unpackWeight(packed);
                    }
                    if (mItalic == NOT_SPECIFIED) {
                        mItalic = FontFileUtil.unpackItalic(packed) ? STYLE_ITALIC : STYLE_NORMAL;
                    }
                } else {
                    mWeight = 400;
                    mItalic = STYLE_NORMAL;
                }
            }
            mWeight = Math.max(FontStyle.FONT_WEIGHT_MIN,
                    Math.min(FontStyle.FONT_WEIGHT_MAX, mWeight));
            final boolean italic = (mItalic == STYLE_ITALIC);
            final int slant = (mItalic == STYLE_ITALIC)
                    ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT;
            final long builderPtr = nInitBuilder();
            if (mAxes != null) {
                for (FontVariationAxis axis : mAxes) {
                    nAddAxis(builderPtr, axis.getOpenTypeTagValue(), axis.getStyleValue());
                }
            }
            final ByteBuffer readonlyBuffer = mBuffer.asReadOnlyBuffer();
            final String filePath = mFile == null ? "" : mFile.getAbsolutePath();

            long ptr;
            int fontIdentifier;
            if (mFont == null) {
                ptr = nBuild(builderPtr, readonlyBuffer, filePath, mLocaleList, mWeight, italic,
                        mTtcIndex);
                long fontBufferPtr = nGetFontBufferAddress(ptr);
                synchronized (SOURCE_ID_LOCK) {
                    long id = FONT_SOURCE_ID_MAP.get(fontBufferPtr, -1);
                    if (id == -1) {
                        id = FONT_SOURCE_ID_MAP.size();
                        FONT_SOURCE_ID_MAP.put(fontBufferPtr, id);
                    }
                    fontIdentifier = (int) id;
                }
            } else {
                ptr = nClone(mFont.getNativePtr(), builderPtr, mWeight, italic, mTtcIndex);
                fontIdentifier = mFont.mSourceIdentifier;
            }
            final Font font = new Font(ptr, readonlyBuffer, mFile,
                    new FontStyle(mWeight, slant), mTtcIndex, mAxes, mLocaleList, fontIdentifier);
            sFontRegistry.registerNativeAllocation(font, ptr);
            return font;
        }

        /**
         * Native methods for creating Font
         */
        private static native long nInitBuilder();
        @CriticalNative
        private static native void nAddAxis(long builderPtr, int tag, float value);
        private static native long nBuild(
                long builderPtr, @NonNull ByteBuffer buffer, @NonNull String filePath,
                @NonNull String localeList, int weight, boolean italic, int ttcIndex);
        @CriticalNative
        private static native long nGetReleaseNativeFont();

        @FastNative
        private static native long nClone(long fontPtr, long builderPtr, int weight,
                boolean italic, int ttcIndex);
    }

    private final long mNativePtr;  // address of the shared ptr of minikin::Font
    private final @NonNull ByteBuffer mBuffer;
    private final @Nullable File mFile;
    private final FontStyle mFontStyle;
    private final @IntRange(from = 0) int mTtcIndex;
    private final @Nullable FontVariationAxis[] mAxes;
    private final @NonNull String mLocaleList;
    private final int mSourceIdentifier;  // An identifier of font source data.

    /**
     * Use Builder instead
     */
    private Font(long nativePtr, @NonNull ByteBuffer buffer, @Nullable File file,
            @NonNull FontStyle fontStyle, @IntRange(from = 0) int ttcIndex,
            @Nullable FontVariationAxis[] axes, @NonNull String localeList,
            int sourceIdentifier) {
        mBuffer = buffer;
        mFile = file;
        mFontStyle = fontStyle;
        mNativePtr = nativePtr;
        mTtcIndex = ttcIndex;
        mAxes = axes;
        mLocaleList = localeList;
        mSourceIdentifier = sourceIdentifier;

        synchronized (MAP_LOCK) {
            FONT_PTR_MAP.append(nGetNativeFontPtr(mNativePtr), new WeakReference<>(this));
        }
    }

    /**
     * Returns a font file buffer.
     *
     * Duplicate before reading values by {@link ByteBuffer#duplicate()} for avoiding unexpected
     * reading position sharing.
     *
     * @return a font buffer
     */
    public @NonNull ByteBuffer getBuffer() {
        return mBuffer;
    }

    /**
     * Returns a file path of this font.
     *
     * This returns null if this font is not created from regular file.
     *
     * @return a file path of the font
     */
    public @Nullable File getFile() {
        return mFile;
    }

    /**
     * Get a style associated with this font.
     *
     * @see Builder#setWeight(int)
     * @see Builder#setSlant(int)
     * @return a font style
     */
    public @NonNull FontStyle getStyle() {
        return mFontStyle;
    }

    /**
     * Get a TTC index value associated with this font.
     *
     * If TTF/OTF file is provided, this value is always 0.
     *
     * @see Builder#setTtcIndex(int)
     * @return a TTC index value
     */
    public @IntRange(from = 0) int getTtcIndex() {
        return mTtcIndex;
    }

    /**
     * Get a font variation settings associated with this font
     *
     * @see Builder#setFontVariationSettings(String)
     * @see Builder#setFontVariationSettings(FontVariationAxis[])
     * @return font variation settings
     */
    public @Nullable FontVariationAxis[] getAxes() {
        return mAxes == null ? null : mAxes.clone();
    }

    /**
     * Get a locale list of this font.
     *
     * This is always empty if this font is not a system font.
     * @return a locale list
     */
    public @NonNull LocaleList getLocaleList() {
        return LocaleList.forLanguageTags(mLocaleList);
    }

    /**
     * Retrieve the glyph horizontal advance and bounding box.
     *
     * Note that {@link android.graphics.Typeface} in {@link android.graphics.Paint} is ignored.
     *
     * @param glyphId a glyph ID
     * @param paint a paint object used for resolving glyph style
     * @param outBoundingBox a nullable destination object. If null is passed, this function just
     *                      return the horizontal advance. If non-null is passed, this function
     *                      fills bounding box information to this object.
     * @return the amount of horizontal advance in pixels
     */
    public float getGlyphBounds(@IntRange(from = 0) int glyphId, @NonNull Paint paint,
            @Nullable RectF outBoundingBox) {
        return nGetGlyphBounds(mNativePtr, glyphId, paint.getNativeInstance(), outBoundingBox);
    }

    /**
     * Retrieve the font metrics information.
     *
     * Note that {@link android.graphics.Typeface} in {@link android.graphics.Paint} is ignored.
     *
     * @param paint a paint object used for retrieving font metrics.
     * @param outMetrics a nullable destination object. If null is passed, this function only
     *                  retrieve recommended interline spacing. If non-null is passed, this function
     *                  fills to font metrics to it.
     *
     * @see Paint#getFontMetrics()
     * @see Paint#getFontMetricsInt()
     */
    public void getMetrics(@NonNull Paint paint, @Nullable Paint.FontMetrics outMetrics) {
        nGetFontMetrics(mNativePtr, paint.getNativeInstance(), outMetrics);
    }

    /** @hide */
    public long getNativePtr() {
        return mNativePtr;
    }

    /**
     * Returns the unique ID of the source font data.
     *
     * You can use this identifier as a key of the cache or checking if two fonts can be
     * interpolated with font variation settings.
     * <pre>
     * <code>
     *   // Following three Fonts, fontA, fontB, fontC have the same identifier.
     *   Font fontA = new Font.Builder("/path/to/font").build();
     *   Font fontB = new Font.Builder(fontA).setTtcIndex(1).build();
     *   Font fontC = new Font.Builder(fontB).setFontVariationSettings("'wght' 700).build();
     *
     *   // Following fontD has the different identifier from above three.
     *   Font fontD = new Font.Builder("/path/to/another/font").build();
     *
     *   // Following fontE has different identifier from above four even the font path is the same.
     *   // To get the same identifier, please create new Font instance from existing fonts.
     *   Font fontE = new Font.Builder("/path/to/font").build();
     * </code>
     * </pre>
     *
     * Here is an example of caching font object that has
     * <pre>
     * <code>
     *   private LongSparseArray<SparseArray<Font>> mCache = new LongSparseArray<>();
     *
     *   private Font getFontWeightVariation(Font font, int weight) {
     *       // Different collection index is treated as different font.
     *       long key = ((long) font.getSourceIdentifier()) << 32 | (long) font.getTtcIndex();
     *
     *       SparseArray<Font> weightCache = mCache.get(key);
     *       if (weightCache == null) {
     *          weightCache = new SparseArray<>();
     *          mCache.put(key, weightCache);
     *       }
     *
     *       Font cachedFont = weightCache.get(weight);
     *       if (cachedFont != null) {
     *         return cachedFont;
     *       }
     *
     *       Font newFont = new Font.Builder(cachedFont)
     *           .setFontVariationSettings("'wght' " + weight);
     *           .build();
     *
     *       weightCache.put(weight, newFont);
     *       return newFont;
     *   }
     * </code>
     * </pre>
     * @return an unique identifier for the font source data.
     */
    public int getSourceIdentifier() {
        return mSourceIdentifier;
    }

    /**
     * Returns true if the given font is created from the same source data from this font.
     *
     * This method essentially compares {@link ByteBuffer} inside Font, but has some optimization
     * for faster comparing. This method compares the internal object before going to one-by-one
     * byte compare with {@link ByteBuffer}. This typically works efficiently if you compares the
     * font that is created from {@link Builder#Builder(Font)}.
     *
     * This API is typically useful for checking if two fonts can be interpolated by font variation
     * axes. For example, when you call {@link android.text.TextShaper} for the same
     * string but different style, you may get two font objects which is created from the same
     * source but have different parameters. You may want to animate between them by interpolating
     * font variation settings if these fonts are created from the same source.
     *
     * @param other a font object to be compared.
     * @return true if given font is created from the same source from this font. Otherwise false.
     */
    private boolean isSameSource(@NonNull Font other) {
        Objects.requireNonNull(other);

        // Shortcut for the same instance.
        if (mBuffer == other.mBuffer) {
            return true;
        }

        // Shortcut for different font buffer check by comparing size.
        if (mBuffer.capacity() != other.mBuffer.capacity()) {
            return false;
        }

        // ByteBuffer#equals compares all bytes which is not performant for e.g HashMap. Since
        // underlying native font object holds buffer address, check if this buffer points exactly
        // the same address as a shortcut of equality. For being compatible with of API30 or before,
        // check buffer position even if the buffer points the same address.
        if (mSourceIdentifier == other.mSourceIdentifier
                && mBuffer.position() == other.mBuffer.position()) {
            return true;
        }

        // Unfortunately, need to compare bytes one-by-one since the buffer may be different font
        // file but has the same file size, or two font has same content but they are allocated
        // differently. For being compatible with API30 ore before, compare with ByteBuffer#equals.
        return mBuffer.equals(other.mBuffer);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Font)) {
            return false;
        }
        Font f = (Font) o;
        boolean paramEqual = mFontStyle.equals(f.mFontStyle) && f.mTtcIndex == mTtcIndex
                && Arrays.equals(f.mAxes, mAxes) && Objects.equals(f.mLocaleList, mLocaleList)
                && Objects.equals(mFile, f.mFile);

        if (!paramEqual) {
            return false;
        }

        return isSameSource(f);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mFontStyle,
                mTtcIndex,
                Arrays.hashCode(mAxes),
                // Use Buffer size instead of ByteBuffer#hashCode since ByteBuffer#hashCode traverse
                // data which is not performant e.g. for HashMap. The hash collision are less likely
                // happens because it is unlikely happens the different font files has exactly the
                // same size.
                mLocaleList);
    }

    @Override
    public String toString() {
        return "Font {"
            + "path=" + mFile
            + ", style=" + mFontStyle
            + ", ttcIndex=" + mTtcIndex
            + ", axes=" + FontVariationAxis.toFontVariationSettings(mAxes)
            + ", localeList=" + mLocaleList
            + ", buffer=" + mBuffer
            + "}";
    }

    /**
     * Lookup Font object from native pointer or create new one if not found.
     * @hide
     */
    public static Font findOrCreateFontFromNativePtr(long ptr) {
        // First, lookup from known mapps.
        synchronized (MAP_LOCK) {
            WeakReference<Font> fontRef = FONT_PTR_MAP.get(ptr);
            if (fontRef != null) {
                Font font = fontRef.get();
                if (font != null) {
                    return font;
                }
            }

            // If not found, create Font object from native object for Java API users.
            ByteBuffer buffer = NativeFontBufferHelper.refByteBuffer(ptr);
            NativeFont.Font font = NativeFont.readNativeFont(ptr);

            Font.Builder builder = new Font.Builder(buffer, font.getFile(), "")
                    .setWeight(font.getStyle().getWeight())
                    .setSlant(font.getStyle().getSlant())
                    .setTtcIndex(font.getIndex())
                    .setFontVariationSettings(font.getAxes());

            Font newFont = null;
            try {
                newFont = builder.build();
                FONT_PTR_MAP.append(ptr, new WeakReference<>(newFont));
            } catch (IOException e) {
                // This must not happen since the buffer was already created once.
                Log.e("Font", "Failed to create font object from existing buffer.", e);
            }
            return newFont;
        }
    }

    @FastNative
    private static native float nGetGlyphBounds(long font, int glyphId, long paint, RectF rect);

    @FastNative
    private static native float nGetFontMetrics(long font, long paint, Paint.FontMetrics metrics);

    @CriticalNative
    private static native long nGetNativeFontPtr(long ptr);

    @CriticalNative
    private static native long nGetFontBufferAddress(long font);
}
