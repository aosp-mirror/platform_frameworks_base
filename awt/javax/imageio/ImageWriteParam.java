/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */

package javax.imageio;

import java.util.Locale;
import java.awt.*;

/**
 * The ImageWriteParam class provides information to an ImageWriter about how an
 * image is to be encoded.
 * 
 * @since Android 1.0
 */
public class ImageWriteParam extends IIOParam {

    /**
     * The Constant MODE_DISABLED indicates that stream is not tiled,
     * progressive, or compressed.
     */
    public static final int MODE_DISABLED = 0;

    /**
     * The Constant MODE_DEFAULT indicates that the stream will be tiled,
     * progressive, or compressed according to the plug-in's default.
     */
    public static final int MODE_DEFAULT = 1;

    /**
     * The Constant MODE_EXPLICIT indicates that the stream will be tiled,
     * progressive, or compressed according to current settings which are
     * defined by set methods.
     */
    public static final int MODE_EXPLICIT = 2;

    /**
     * The Constant MODE_COPY_FROM_METADATA indicates that the stream will be
     * tiled, progressive, or compressed according to stream or image metadata.
     */
    public static final int MODE_COPY_FROM_METADATA = 3;

    /**
     * Whether the ImageWriter can write tiles.
     */
    protected boolean canWriteTiles = false;

    /**
     * The tiling mode.
     */
    protected int tilingMode = MODE_COPY_FROM_METADATA;

    /**
     * The preferred tile sizes.
     */
    protected Dimension[] preferredTileSizes = null;

    /**
     * The tiling set.
     */
    protected boolean tilingSet = false;

    /**
     * The tile width.
     */
    protected int tileWidth = 0;

    /**
     * The tile height.
     */
    protected int tileHeight = 0;

    /**
     * Whether the ImageWriter can offset tiles.
     */
    protected boolean canOffsetTiles = false;

    /**
     * The tile grid x offset.
     */
    protected int tileGridXOffset = 0;

    /**
     * The tile grid y offset.
     */
    protected int tileGridYOffset = 0;

    /**
     * Whether the ImageWriter can write in progressive mode.
     */
    protected boolean canWriteProgressive = false;

    /**
     * The progressive mode.
     */
    protected int progressiveMode = MODE_COPY_FROM_METADATA;

    /**
     * Whether the ImageWriter can write in compressed mode.
     */
    protected boolean canWriteCompressed = false;

    /**
     * The compression mode.
     */
    protected int compressionMode = MODE_COPY_FROM_METADATA;

    /**
     * The compression types.
     */
    protected String[] compressionTypes = null;

    /**
     * The compression type.
     */
    protected String compressionType = null;

    /**
     * The compression quality.
     */
    protected float compressionQuality = 1.0f;

    /**
     * The locale.
     */
    protected Locale locale = null;

    /**
     * Instantiates a new ImageWriteParam.
     */
    protected ImageWriteParam() {
    }

    /**
     * Instantiates a new ImageWriteParam with the specified Locale.
     * 
     * @param locale
     *            the Locale.
     */
    public ImageWriteParam(Locale locale) {
        this.locale = locale;

    }

    /**
     * Gets the mode for writing the stream in a progressive sequence.
     * 
     * @return the current progressive mode.
     */
    public int getProgressiveMode() {
        if (canWriteProgressive()) {
            return progressiveMode;
        }
        throw new UnsupportedOperationException("progressive mode is not supported");
    }

    /**
     * Returns true if images can be written using increasing quality passes by
     * progressive.
     * 
     * @return true if images can be written using increasing quality passes by
     *         progressive, false otherwise.
     */
    public boolean canWriteProgressive() {
        return canWriteProgressive;
    }

    /**
     * Sets the progressive mode which defines whether the stream contains a
     * progressive sequence of increasing quality during writing. The
     * progressive mode should be one of the following values: MODE_DISABLED,
     * MODE_DEFAULT, or MODE_COPY_FROM_METADATA.
     * 
     * @param mode
     *            the new progressive mode.
     */
    public void setProgressiveMode(int mode) {
        if (canWriteProgressive()) {
            if (mode < MODE_DISABLED || mode > MODE_COPY_FROM_METADATA || mode == MODE_EXPLICIT) {
                throw new IllegalArgumentException("mode is not supported");
            }
            this.progressiveMode = mode;
        }
        throw new UnsupportedOperationException("progressive mode is not supported");
    }

    /**
     * Returns true if the writer can use tiles with non zero grid offsets while
     * writing.
     * 
     * @return true, if the writer can use tiles with non zero grid offsets
     *         while writing, false otherwise.
     */
    public boolean canOffsetTiles() {
        return canOffsetTiles;
    }

    /**
     * Returns true if this writer can write images with compression.
     * 
     * @return true, if this writer can write images with compression, false
     *         otherwise.
     */
    public boolean canWriteCompressed() {
        return canWriteCompressed;
    }

    /**
     * Returns true if the writer can write tiles.
     * 
     * @return true, if the writer can write tiles, false otherwise.
     */
    public boolean canWriteTiles() {
        return canWriteTiles;
    }

    /**
     * Check write compressed.
     */
    private final void checkWriteCompressed() {
        if (!canWriteCompressed()) {
            throw new UnsupportedOperationException("Compression not supported.");
        }
    }

    /**
     * Check compression mode.
     */
    private final void checkCompressionMode() {
        if (getCompressionMode() != MODE_EXPLICIT) {
            throw new IllegalStateException("Compression mode not MODE_EXPLICIT!");
        }
    }

    /**
     * Check compression type.
     */
    private final void checkCompressionType() {
        if (getCompressionTypes() != null && getCompressionType() == null) {
            throw new IllegalStateException("No compression type set!");
        }
    }

    /**
     * Gets the compression mode.
     * 
     * @return the compression mode if it's supported.
     */
    public int getCompressionMode() {
        checkWriteCompressed();
        return compressionMode;
    }

    /**
     * Gets the an array of supported compression types.
     * 
     * @return the an array of supported compression types.
     */
    public String[] getCompressionTypes() {
        checkWriteCompressed();
        if (compressionTypes != null) {
            return compressionTypes.clone();
        }
        return null;
    }

    /**
     * Gets the current compression type, or returns null.
     * 
     * @return the current compression type, or returns null if it is not set.
     */
    public String getCompressionType() {
        checkWriteCompressed();
        checkCompressionMode();
        return compressionType;
    }

    /**
     * Gets a bit rate which represents an estimate of the number of bits of
     * output data for each bit of input image data with the specified quality.
     * 
     * @param quality
     *            the quality.
     * @return an estimate of the bit rate, or -1.0F if there is no estimate.
     */
    public float getBitRate(float quality) {
        checkWriteCompressed();
        checkCompressionMode();
        checkCompressionType();
        if (quality < 0 || quality > 1) {
            throw new IllegalArgumentException("Quality out-of-bounds!");
        }
        return -1.0f;
    }

    /**
     * Gets the compression quality.
     * 
     * @return the compression quality.
     */
    public float getCompressionQuality() {
        checkWriteCompressed();
        checkCompressionMode();
        checkCompressionType();
        return compressionQuality;
    }

    /**
     * Gets the array of compression quality descriptions.
     * 
     * @return the string array of compression quality descriptions.
     */
    public String[] getCompressionQualityDescriptions() {
        checkWriteCompressed();
        checkCompressionMode();
        checkCompressionType();
        return null;
    }

    /**
     * Gets an array of floats which describes compression quality levels.
     * 
     * @return the array of compression quality values.
     */
    public float[] getCompressionQualityValues() {
        checkWriteCompressed();
        checkCompressionMode();
        checkCompressionType();
        return null;
    }

    /**
     * Gets the locale of this ImageWriteParam.
     * 
     * @return the locale of this ImageWriteParam.
     */
    public Locale getLocale() {
        return locale;
    }

    /**
     * Gets the current compression type using the current Locale.
     * 
     * @return the current compression type using the current Locale.
     */
    public String getLocalizedCompressionTypeName() {
        checkWriteCompressed();
        checkCompressionMode();

        String compressionType = getCompressionType();
        if (compressionType == null) {
            throw new IllegalStateException("No compression type set!");
        }
        return compressionType;

    }

    /**
     * Check tiling.
     */
    private final void checkTiling() {
        if (!canWriteTiles()) {
            throw new UnsupportedOperationException("Tiling not supported!");
        }
    }

    /**
     * Check tiling mode.
     */
    private final void checkTilingMode() {
        if (getTilingMode() != MODE_EXPLICIT) {
            throw new IllegalStateException("Tiling mode not MODE_EXPLICIT!");
        }
    }

    /**
     * Check tiling params.
     */
    private final void checkTilingParams() {
        if (!tilingSet) {
            throw new IllegalStateException("Tiling parameters not set!");
        }
    }

    /**
     * Gets the tiling mode if tiling is supported.
     * 
     * @return the tiling mode if tiling is supported.
     */
    public int getTilingMode() {
        checkTiling();
        return tilingMode;
    }

    /**
     * Gets an array of Dimensions giving the sizes of the tiles as they are
     * encoded in the output file or stream.
     * 
     * @return the preferred tile sizes.
     */
    public Dimension[] getPreferredTileSizes() {
        checkTiling();
        if (preferredTileSizes == null) {
            return null;
        }

        Dimension[] retval = new Dimension[preferredTileSizes.length];
        for (int i = 0; i < preferredTileSizes.length; i++) {
            retval[i] = new Dimension(retval[i]);
        }
        return retval;
    }

    /**
     * Gets the tile grid X offset for encoding.
     * 
     * @return the tile grid X offset for encoding.
     */
    public int getTileGridXOffset() {
        checkTiling();
        checkTilingMode();
        checkTilingParams();
        return tileGridXOffset;
    }

    /**
     * Gets the tile grid Y offset for encoding.
     * 
     * @return the tile grid Y offset for encoding.
     */
    public int getTileGridYOffset() {
        checkTiling();
        checkTilingMode();
        checkTilingParams();
        return tileGridYOffset;
    }

    /**
     * Gets the tile height in an image as it is written to the output stream.
     * 
     * @return the tile height in an image as it is written to the output
     *         stream.
     */
    public int getTileHeight() {
        checkTiling();
        checkTilingMode();
        checkTilingParams();
        return tileHeight;
    }

    /**
     * Gets the tile width in an image as it is written to the output stream.
     * 
     * @return the tile width in an image as it is written to the output stream.
     */
    public int getTileWidth() {
        checkTiling();
        checkTilingMode();
        checkTilingParams();
        return tileWidth;
    }

    /**
     * Checks if the current compression type has lossless compression or not.
     * 
     * @return true, if the current compression type has lossless compression,
     *         false otherwise.
     */
    public boolean isCompressionLossless() {
        checkWriteCompressed();
        checkCompressionMode();
        checkCompressionType();
        return true;
    }

    /**
     * Removes current compression type.
     */
    public void unsetCompression() {
        checkWriteCompressed();
        checkCompressionMode();
        compressionType = null;
        compressionQuality = 1;
    }

    /**
     * Sets the compression mode to the specified value. The specified mode can
     * be one of the predefined constants: MODE_DEFAULT, MODE_DISABLED,
     * MODE_EXPLICIT, or MODE_COPY_FROM_METADATA.
     * 
     * @param mode
     *            the new compression mode to be set.
     */
    public void setCompressionMode(int mode) {
        checkWriteCompressed();
        switch (mode) {
            case MODE_EXPLICIT: {
                compressionMode = mode;
                unsetCompression();
                break;
            }
            case MODE_COPY_FROM_METADATA:
            case MODE_DISABLED:
            case MODE_DEFAULT: {
                compressionMode = mode;
                break;
            }
            default: {
                throw new IllegalArgumentException("Illegal value for mode!");
            }
        }
    }

    /**
     * Sets the compression quality. The value should be between 0 and 1.
     * 
     * @param quality
     *            the new compression quality, float value between 0 and 1.
     */
    public void setCompressionQuality(float quality) {
        checkWriteCompressed();
        checkCompressionMode();
        checkCompressionType();
        if (quality < 0 || quality > 1) {
            throw new IllegalArgumentException("Quality out-of-bounds!");
        }
        compressionQuality = quality;
    }

    /**
     * Sets the compression type. The specified string should be one of the
     * values returned by getCompressionTypes method.
     * 
     * @param compressionType
     *            the new compression type.
     */
    public void setCompressionType(String compressionType) {
        checkWriteCompressed();
        checkCompressionMode();

        if (compressionType == null) { // Don't check anything
            this.compressionType = null;
        } else {
            String[] compressionTypes = getCompressionTypes();
            if (compressionTypes == null) {
                throw new UnsupportedOperationException("No settable compression types");
            }

            for (int i = 0; i < compressionTypes.length; i++) {
                if (compressionTypes[i].equals(compressionType)) {
                    this.compressionType = compressionType;
                    return;
                }
            }

            // Compression type is not in the list.
            throw new IllegalArgumentException("Unknown compression type!");
        }
    }

    /**
     * Sets the instruction that tiling should be performed for the image in the
     * output stream with the specified parameters.
     * 
     * @param tileWidth
     *            the tile's width.
     * @param tileHeight
     *            the tile's height.
     * @param tileGridXOffset
     *            the tile grid's x offset.
     * @param tileGridYOffset
     *            the tile grid's y offset.
     */
    public void setTiling(int tileWidth, int tileHeight, int tileGridXOffset, int tileGridYOffset) {
        checkTiling();
        checkTilingMode();

        if (!canOffsetTiles() && (tileGridXOffset != 0 || tileGridYOffset != 0)) {
            throw new UnsupportedOperationException("Can't offset tiles!");
        }

        if (tileWidth <= 0 || tileHeight <= 0) {
            throw new IllegalArgumentException("tile dimensions are non-positive!");
        }

        Dimension preferredTileSizes[] = getPreferredTileSizes();
        if (preferredTileSizes != null) {
            for (int i = 0; i < preferredTileSizes.length; i += 2) {
                Dimension minSize = preferredTileSizes[i];
                Dimension maxSize = preferredTileSizes[i + 1];
                if (tileWidth < minSize.width || tileWidth > maxSize.width
                        || tileHeight < minSize.height || tileHeight > maxSize.height) {
                    throw new IllegalArgumentException("Illegal tile size!");
                }
            }
        }

        tilingSet = true;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.tileGridXOffset = tileGridXOffset;
        this.tileGridYOffset = tileGridYOffset;
    }

    /**
     * Clears all tiling settings.
     */
    public void unsetTiling() {
        checkTiling();
        checkTilingMode();

        tilingSet = false;
        tileWidth = 0;
        tileHeight = 0;
        tileGridXOffset = 0;
        tileGridYOffset = 0;
    }

    /**
     * Sets the tiling mode. The specified mode should be one of the following
     * values: MODE_DISABLED, MODE_DEFAULT, MODE_EXPLICIT, or
     * MODE_COPY_FROM_METADATA.
     * 
     * @param mode
     *            the new tiling mode.
     */
    public void setTilingMode(int mode) {
        checkTiling();

        switch (mode) {
            case MODE_EXPLICIT: {
                tilingMode = mode;
                unsetTiling();
                break;
            }
            case MODE_COPY_FROM_METADATA:
            case MODE_DISABLED:
            case MODE_DEFAULT: {
                tilingMode = mode;
                break;
            }
            default: {
                throw new IllegalArgumentException("Illegal value for mode!");
            }
        }
    }
}
