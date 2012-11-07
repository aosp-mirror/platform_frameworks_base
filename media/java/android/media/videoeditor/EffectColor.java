/*
 * Copyright (C) 2010 The Android Open Source Project
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


package android.media.videoeditor;

/**
 * This class allows to apply color effect on a media item.
 * {@hide}
 */
public class EffectColor extends Effect {

    /**
     * Change the video frame color to the RGB color value provided
     */
    public static final int TYPE_COLOR = 1;
    /**
     * Change the video frame color to a gradation from RGB color (at the top of
     * the frame) to black (at the bottom of the frame).
     */
    public static final int TYPE_GRADIENT = 2;
    /**
     * Change the video frame color to sepia
     */
    public static final int TYPE_SEPIA = 3;
    /**
     * Invert the video frame color
     */
    public static final int TYPE_NEGATIVE = 4;
    /**
     * Make the video look like as if it was recorded in 50's
     */
    public static final int TYPE_FIFTIES = 5;
    /**
     * Change the video frame color to the RGB color value GREEN
     */
    public static final int GREEN = 0x0000ff00;
    /**
     * Change the video frame color to the RGB color value PINK
     */
    public static final int PINK = 0x00ff66cc;
    /**
     * Change the video frame color to the RGB color value GRAY
     */
    public static final int GRAY = 0x007f7f7f;

    /**
     *  The effect type
     */
    private final int mType;

    /**
     *  The effect color
     */
    private final int mColor;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private EffectColor() {
        this(null, null, 0, 0, 0, 0);
    }

    /**
     * Constructor
     *
     * @param mediaItem The media item owner
     * @param effectId The effect id
     * @param startTimeMs The start time relative to the media item to which it
     *            is applied
     * @param durationMs The duration of this effect in milliseconds
     * @param type type of the effect. type is one of: TYPE_COLOR,
     *            TYPE_GRADIENT, TYPE_SEPIA, TYPE_NEGATIVE, TYPE_FIFTIES.
     * @param color If type is TYPE_COLOR, color is the RGB color as 888.
     *              If type is TYPE_GRADIENT, color is the RGB color at the
     *              top of the frame. Otherwise, color is ignored
     */
    public EffectColor(MediaItem mediaItem, String effectId, long startTimeMs,
                      long durationMs, int type, int color) {
        super(mediaItem, effectId, startTimeMs, durationMs);
        switch (type) {
            case TYPE_COLOR:
            case TYPE_GRADIENT: {
                switch (color) {
                    case GREEN:
                    case PINK:
                    case GRAY:
                        mColor = color;
                        break;

                    default:
                        throw new IllegalArgumentException("Invalid Color: " + color);
                    }
                    break;
            }
            case TYPE_SEPIA:
            case TYPE_NEGATIVE:
            case TYPE_FIFTIES: {
                mColor = -1;
                break;
            }

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
        mType = type;
    }

    /**
     * Get the effect type.
     *
     * @return The effect type
     */
    public int getType() {
        return mType;
    }

    /**
     * Get the color if effect type is TYPE_COLOR or TYPE_GRADIENT.
     *
     * @return the color as RGB 888 if type is TYPE_COLOR or TYPE_GRADIENT.
     */
    public int getColor() {
        return mColor;
    }
}
