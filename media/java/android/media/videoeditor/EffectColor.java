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
 * This class allows to apply color on a media item.
 * {@hide}
 */
public class EffectColor extends Effect {

    /**
     * Change the video frame color to the RGB color value provided
     */
    public static final int TYPE_COLOR = 1; // color as 888 RGB
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

    // Colors for the color effect
    public static final int GREEN = 0x0000ff00;
    public static final int PINK = 0x00ff66cc;
    public static final int GRAY = 0x007f7f7f;

    // The effect type
    private final int mType;

    // The effect parameter
    private final int mParam;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private EffectColor() {
        this(null, 0, 0, 0, 0);
    }

    /**
     * Constructor
     *
     * @param effectId The effect id
     * @param startTimeMs The start time relative to the media item to which it
     *            is applied
     * @param durationMs The duration of this effect in milliseconds
     * @param type type of the effect. type is one of: TYPE_COLOR,
     *            TYPE_GRADIENT, TYPE_SEPIA, TYPE_NEGATIVE, TYPE_FIFTIES. If
     *            type is not supported, the argument is ignored
     * @param param if type is TYPE_COLOR, param is the RGB color as 888.
     *            Otherwise, param is ignored
     */
    public EffectColor(String effectId, long startTimeMs, long durationMs,
            int type, int param) {
        super(effectId, startTimeMs, durationMs);
        mType = type;
        mParam = param;
    }

    /**
     * @return The type of this effect
     */
    public int getType() {
        return mType;
    }

    /**
     * @return the color as RGB 888 if type is TYPE_COLOR. Otherwise, ignore.
     */
    public int getParam() {
        return mParam;
    }
}
