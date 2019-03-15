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
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;

import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;

import libcore.util.NativeAllocationRegistry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
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

    /**
     * A builder class for creating new Font.
     */
    public static final class Builder {
        private static final NativeAllocationRegistry sAssetByteBufferRegistroy =
                new NativeAllocationRegistry(ByteBuffer.class.getClassLoader(),
                    nGetReleaseNativeAssetFunc(), 64);

        private static final NativeAllocationRegistry sFontRegistory =
                new NativeAllocationRegistry(Font.class.getClassLoader(),
                    nGetReleaseNativeFont(), 64);

        private @Nullable ByteBuffer mBuffer;
        private @Nullable File mFile;
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
            this(am, path, true /* is asset */, 0 /* cookie */);
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
            final long nativeAsset = nGetNativeAsset(am, path, isAsset, cookie);
            if (nativeAsset == 0) {
                mException = new FileNotFoundException("Unable to open " + path);
                return;
            }
            final ByteBuffer b = nGetAssetBuffer(nativeAsset);
            sAssetByteBufferRegistroy.registerNativeAllocation(b, nativeAsset);
            if (b == null) {
                mException = new FileNotFoundException(path + " not found");
                return;
            }
            mBuffer = b;
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
            final long nativeAsset = nGetNativeAsset(res.getAssets(), str, false /* is asset */,
                    value.assetCookie);
            if (nativeAsset == 0) {
                mException = new FileNotFoundException("Unable to open " + str);
                return;
            }
            final ByteBuffer b = nGetAssetBuffer(nativeAsset);
            sAssetByteBufferRegistroy.registerNativeAllocation(b, nativeAsset);
            if (b == null) {
                mException = new FileNotFoundException(str + " not found");
                return;
            }
            mBuffer = b;
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
        public @Nullable Font build() throws IOException {
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
            final long ptr = nBuild(builderPtr, readonlyBuffer, filePath, mWeight, italic,
                    mTtcIndex);
            final Font font = new Font(ptr, readonlyBuffer, mFile,
                    new FontStyle(mWeight, slant), mTtcIndex, mAxes, mLocaleList);
            sFontRegistory.registerNativeAllocation(font, ptr);
            return font;
        }

        /**
         * Native methods for accessing underlying buffer in Asset
         */
        private static native long nGetNativeAsset(
                @NonNull AssetManager am, @NonNull String path, boolean isAsset, int cookie);
        private static native ByteBuffer nGetAssetBuffer(long nativeAsset);
        @CriticalNative
        private static native long nGetReleaseNativeAssetFunc();

        /**
         * Native methods for creating Font
         */
        private static native long nInitBuilder();
        @CriticalNative
        private static native void nAddAxis(long builderPtr, int tag, float value);
        private static native long nBuild(
                long builderPtr, @NonNull ByteBuffer buffer, @NonNull String filePath, int weight,
                boolean italic, int ttcIndex);
        @CriticalNative
        private static native long nGetReleaseNativeFont();
    }

    private final long mNativePtr;  // address of the shared ptr of minikin::Font
    private final @NonNull ByteBuffer mBuffer;
    private final @Nullable File mFile;
    private final FontStyle mFontStyle;
    private final @IntRange(from = 0) int mTtcIndex;
    private final @Nullable FontVariationAxis[] mAxes;
    private final @NonNull String mLocaleList;

    /**
     * Use Builder instead
     */
    private Font(long nativePtr, @NonNull ByteBuffer buffer, @Nullable File file,
            @NonNull FontStyle fontStyle, @IntRange(from = 0) int ttcIndex,
            @Nullable FontVariationAxis[] axes, @NonNull String localeList) {
        mBuffer = buffer;
        mFile = file;
        mFontStyle = fontStyle;
        mNativePtr = nativePtr;
        mTtcIndex = ttcIndex;
        mAxes = axes;
        mLocaleList = localeList;
    }

    /**
     * Returns a font file buffer.
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

    /** @hide */
    public long getNativePtr() {
        return mNativePtr;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (o == null || !(o instanceof Font)) {
            return false;
        }
        Font f = (Font) o;
        return mFontStyle.equals(f.mFontStyle) && f.mTtcIndex == mTtcIndex
                && Arrays.equals(f.mAxes, mAxes) && f.mBuffer.equals(mBuffer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFontStyle, mTtcIndex, Arrays.hashCode(mAxes), mBuffer);
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
}
