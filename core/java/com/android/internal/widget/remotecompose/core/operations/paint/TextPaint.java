/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.paint;

import android.annotation.NonNull;

import java.util.Locale;

// TODO: this interface is unused. Delete it.
public interface TextPaint {

    /**
     * Helper to setColor(), that takes a,r,g,b and constructs the color int
     *
     * @param a The new alpha component (0..255) of the paint's color.
     * @param r The new red component (0..255) of the paint's color.
     * @param g The new green component (0..255) of the paint's color.
     * @param b The new blue component (0..255) of the paint's color.
     */
    void setARGB(int a, int r, int g, int b);

    /**
     * Helper for setFlags(), setting or clearing the DITHER_FLAG bit Dithering affects how colors
     * that are higher precision than the device are down-sampled. No dithering is generally faster,
     * but higher precision colors are just truncated down (e.g. 8888 -> 565). Dithering tries to
     * distribute the error inherent in this process, to reduce the visual artifacts.
     *
     * @param dither true to set the dithering bit in flags, false to clear it
     */
    void setDither(boolean dither);

    /**
     * Set the paint's elegant height metrics flag. This setting selects font variants that have not
     * been compacted to fit Latin-based vertical metrics, and also increases top and bottom bounds
     * to provide more space.
     *
     * @param elegant set the paint's elegant metrics flag for drawing text.
     */
    void setElegantTextHeight(boolean elegant);

    /**
     * Set a end hyphen edit on the paint.
     *
     * <p>By setting end hyphen edit, the measurement and drawing is performed with modifying
     * hyphenation at the end of line. For example, by passing character is appended at the end of
     * line.
     *
     * <pre>
     * <code>
     *   Paint paint = new Paint();
     *   paint.setEndHyphenEdit(Paint.END_HYPHEN_EDIT_INSERT_HYPHEN);
     *   paint.measureText("abc", 0, 3);  // Returns the width of "abc-"
     *   Canvas.drawText("abc", 0, 3, 0f, 0f, paint);  // Draws "abc-"
     * </code>
     * </pre>
     *
     * @param endHyphen a end hyphen edit value.
     */
    void setEndHyphenEdit(int endHyphen);

    /**
     * Helper for setFlags(), setting or clearing the FAKE_BOLD_TEXT_FLAG bit
     *
     * @param fakeBoldText true to set the fakeBoldText bit in the paint's flags, false to clear it.
     */
    void setFakeBoldText(boolean fakeBoldText);

    /**
     * Set the paint's flags. Use the Flag enum to specific flag values.
     *
     * @param flags The new flag bits for the paint
     */
    void setFlags(int flags);

    /**
     * Set font feature settings.
     *
     * <p>The format is the same as the CSS font-feature-settings attribute: <a
     * href="https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop">
     * https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop</a>
     *
     * @param settings the font feature settings string to use, may be null.
     */
    void setFontFeatureSettings(@NonNull String settings);

    /**
     * Set the paint's hinting mode. May be either
     *
     * @param mode The new hinting mode. (HINTING_OFF or HINTING_ON)
     */
    void setHinting(int mode);

    /**
     * Set the paint's letter-spacing for text. The default value is 0. The value is in 'EM' units.
     * Typical values for slight expansion will be around 0.05. Negative values tighten text.
     *
     * @param letterSpacing set the paint's letter-spacing for drawing text.
     */
    void setLetterSpacing(float letterSpacing);

    /**
     * Helper for setFlags(), setting or clearing the LINEAR_TEXT_FLAG bit
     *
     * @param linearText true to set the linearText bit in the paint's flags, false to clear it.
     */
    void setLinearText(boolean linearText);

    /**
     * This draws a shadow layer below the main layer, with the specified offset and color, and blur
     * radius. If radius is 0, then the shadow layer is removed.
     *
     * <p>Can be used to create a blurred shadow underneath text. Support for use with other drawing
     * operations is constrained to the software rendering pipeline.
     *
     * <p>The alpha of the shadow will be the paint's alpha if the shadow color is opaque, or the
     * alpha from the shadow color if not.
     *
     * @param radius the radius of the shadows
     * @param dx the x offset of the shadow
     * @param dy the y offset of the shadow
     * @param shadowColor the color of the shadow
     */
    void setShadowLayer(float radius, float dx, float dy, int shadowColor);

    /**
     * Set a start hyphen edit on the paint.
     *
     * <p>By setting start hyphen edit, the measurement and drawing is performed with modifying
     * hyphenation at the start of line. For example, by passing character is appended at the start
     * of line.
     *
     * <pre>
     * <code>
     *   Paint paint = new Paint();
     *   paint.setStartHyphenEdit(Paint.START_HYPHEN_EDIT_INSERT_HYPHEN);
     *   paint.measureText("abc", 0, 3);  // Returns the width of "-abc"
     *   Canvas.drawText("abc", 0, 3, 0f, 0f, paint);  // Draws "-abc"
     * </code>
     * </pre>
     *
     * The default value is 0 which is equivalent to
     *
     * @param startHyphen a start hyphen edit value.
     */
    void setStartHyphenEdit(int startHyphen);

    /**
     * Helper for setFlags(), setting or clearing the STRIKE_THRU_TEXT_FLAG bit
     *
     * @param strikeThruText true to set the strikeThruText bit in the paint's flags, false to clear
     *     it.
     */
    void setStrikeThruText(boolean strikeThruText);

    /**
     * Set the paint's Cap.
     *
     * @param cap set the paint's line cap style, used whenever the paint's style is Stroke or
     *     StrokeAndFill.
     */
    void setStrokeCap(int cap);

    /**
     * Helper for setFlags(), setting or clearing the SUBPIXEL_TEXT_FLAG bit
     *
     * @param subpixelText true to set the subpixelText bit in the paint's flags, false to clear it.
     */
    void setSubpixelText(boolean subpixelText);

    /**
     * Set the paint's text alignment. This controls how the text is positioned relative to its
     * origin. LEFT align means that all of the text will be drawn to the right of its origin (i.e.
     * the origin specifies the LEFT edge of the text) and so on.
     *
     * @param align set the paint's Align value for drawing text.
     */
    void setTextAlign(int align);

    /**
     * Set the text locale list to a one-member list consisting of just the locale.
     *
     * @param locale the paint's locale value for drawing text, must not be null.
     */
    void setTextLocale(int locale);

    /**
     * Set the text locale list.
     *
     * <p>The text locale list affects how the text is drawn for some languages.
     *
     * <p>For example, if the locale list contains {@link Locale#CHINESE} or {@link Locale#CHINA},
     * then the text renderer will prefer to draw text using a Chinese font. Likewise, if the locale
     * list contains {@link Locale#JAPANESE} or {@link Locale#JAPAN}, then the text renderer will
     * prefer to draw text using a Japanese font. If the locale list contains both, the order those
     * locales appear in the list is considered for deciding the font.
     *
     * <p>This distinction is important because Chinese and Japanese text both use many of the same
     * Unicode code points but their appearance is subtly different for each language.
     *
     * <p>By default, the text locale list is initialized to a one-member list just containing the
     * system locales. This assumes that the text to be rendered will most likely be in the user's
     * preferred language.
     *
     * <p>If the actual language or languages of the text is/are known, then they can be provided to
     * the text renderer using this method. The text renderer may attempt to guess the language
     * script based on the contents of the text to be drawn independent of the text locale here.
     * Specifying the text locales just helps it do a better job in certain ambiguous cases.
     *
     * @param localesArray the paint's locale list for drawing text, must not be null or empty.
     */
    void setTextLocales(int localesArray);

    /**
     * Set the paint's horizontal scale factor for text. The default value is 1.0. Values > 1.0 will
     * stretch the text wider. Values < 1.0 will stretch the text narrower.
     *
     * @param scaleX set the paint's scale in X for drawing/measuring text.
     */
    void setTextScaleX(float scaleX);

    /**
     * Set the paint's text size. This value must be > 0
     *
     * @param textSize set the paint's text size in pixel units.
     */
    void setTextSize(float textSize);

    /**
     * Set the paint's horizontal skew factor for text. The default value is 0. For approximating
     * oblique text, use values around -0.25.
     *
     * @param skewX set the paint's skew factor in X for drawing text.
     */
    void setTextSkewX(float skewX);

    /**
     * Helper for setFlags(), setting or clearing the UNDERLINE_TEXT_FLAG bit
     *
     * @param underlineText true to set the underlineText bit in the paint's flags, false to clear
     *     it.
     */
    void setUnderlineText(boolean underlineText);

    /**
     * Set the paint's extra word-spacing for text.
     *
     * <p>Increases the white space width between words with the given amount of pixels. The default
     * value is 0.
     *
     * @param wordSpacing set the paint's extra word-spacing for drawing text in pixels.
     */
    void setWordSpacing(float wordSpacing);
}
