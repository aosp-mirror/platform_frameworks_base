/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.view.accessibility;

import android.content.ContentResolver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings.Secure;
import android.text.TextUtils;

import java.util.Locale;

/**
 * Contains methods for accessing preferred video captioning state and
 * properties.
 */
public class CaptioningManager {
    /**
     * Activity Action: Show settings for video captioning.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    public static final String ACTION_CAPTIONING_SETTINGS = "android.settings.CAPTIONING_SETTINGS";

    private static final int DEFAULT_PRESET = 0;
    private static final int DEFAULT_ENABLED = 0;
    private static final float DEFAULT_FONT_SIZE = 24;

    /**
     * @param cr Resolver to access the database with.
     * @return The user's preferred caption enabled state.
     */
    public static final boolean isEnabled(ContentResolver cr) {
        return Secure.getInt(cr, Secure.ACCESSIBILITY_CAPTIONING_ENABLED, DEFAULT_ENABLED) == 1;
    }

    /**
     * @param cr Resolver to access the database with.
     * @return The raw locale string for the user's preferred caption language.
     * @hide
     */
    public static final String getRawLocale(ContentResolver cr) {
        return Secure.getString(cr, Secure.ACCESSIBILITY_CAPTIONING_LOCALE);
    }

    /**
     * @param cr Resolver to access the database with.
     * @return The locale for the user's preferred caption language, or null if
     *         not specified.
     */
    public static final Locale getLocale(ContentResolver cr) {
        final String rawLocale = getRawLocale(cr);
        if (!TextUtils.isEmpty(rawLocale)) {
            final String[] splitLocale = rawLocale.split("_");
            switch (splitLocale.length) {
                case 3:
                    return new Locale(splitLocale[0], splitLocale[1], splitLocale[2]);
                case 2:
                    return new Locale(splitLocale[0], splitLocale[1]);
                case 1:
                    return new Locale(splitLocale[0]);
            }
        }

        return null;
    }

    /**
     * @param cr Resolver to access the database with.
     * @return The user's preferred font size for video captions, or 0 if not
     *         specified.
     */
    public static final float getFontSize(ContentResolver cr) {
        return Secure.getFloat(cr, Secure.ACCESSIBILITY_CAPTIONING_FONT_SIZE, DEFAULT_FONT_SIZE);
    }

    public static final class CaptionStyle {
        private static final CaptionStyle WHITE_ON_BLACK;
        private static final CaptionStyle BLACK_ON_WHITE;
        private static final CaptionStyle YELLOW_ON_BLACK;
        private static final CaptionStyle YELLOW_ON_BLUE;
        private static final CaptionStyle DEFAULT_CUSTOM;

        /** @hide */
        public static final CaptionStyle[] PRESETS;

        /** @hide */
        public static final int PRESET_CUSTOM = -1;

        /** Edge type value specifying no character edges. */
        public static final int EDGE_TYPE_NONE = 0;

        /** Edge type value specifying uniformly outlined character edges. */
        public static final int EDGE_TYPE_OUTLINE = 1;

        /** Edge type value specifying drop-shadowed character edges. */
        public static final int EDGE_TYPE_DROP_SHADOW = 2;

        /** The preferred foreground color for video captions. */
        public final int foregroundColor;

        /** The preferred background color for video captions. */
        public final int backgroundColor;

        /**
         * The preferred edge type for video captions, one of:
         * <ul>
         * <li>{@link #EDGE_TYPE_NONE}
         * <li>{@link #EDGE_TYPE_OUTLINE}
         * <li>{@link #EDGE_TYPE_DROP_SHADOW}
         * </ul>
         */
        public final int edgeType;

        /**
         * The preferred edge color for video captions, if using an edge type
         * other than {@link #EDGE_TYPE_NONE}.
         */
        public final int edgeColor;

        /**
         * @hide
         */
        public final String mRawTypeface;

        private Typeface mParsedTypeface;

        private CaptionStyle(int foregroundColor, int backgroundColor, int edgeType, int edgeColor,
                String rawTypeface) {
            this.foregroundColor = foregroundColor;
            this.backgroundColor = backgroundColor;
            this.edgeType = edgeType;
            this.edgeColor = edgeColor;

            mRawTypeface = rawTypeface;
        }

        /**
         * @return The preferred {@link Typeface} for video captions, or null if
         *         not specified.
         */
        public Typeface getTypeface() {
            if (mParsedTypeface == null && !TextUtils.isEmpty(mRawTypeface)) {
                mParsedTypeface = Typeface.create(mRawTypeface, Typeface.NORMAL);
            }
            return mParsedTypeface;
        }

        /**
         * @hide
         */
        public static int getRawPreset(ContentResolver cr) {
            return Secure.getInt(cr, Secure.ACCESSIBILITY_CAPTIONING_PRESET, DEFAULT_PRESET);
        }

        /**
         * @param cr Resolver to access the database with.
         * @return The user's preferred caption style.
         */
        public static CaptionStyle defaultUserStyle(ContentResolver cr) {
            final int preset = getRawPreset(cr);
            if (preset == PRESET_CUSTOM) {
                return getCustomStyle(cr);
            }

            return PRESETS[preset];
        }

        /**
         * @hide
         */
        public static CaptionStyle getCustomStyle(ContentResolver cr) {
            final int foregroundColor = Secure.getInt(
                    cr, Secure.ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR,
                    DEFAULT_CUSTOM.foregroundColor);
            final int backgroundColor = Secure.getInt(cr,
                    Secure.ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR,
                    DEFAULT_CUSTOM.backgroundColor);
            final int edgeType = Secure.getInt(
                    cr, Secure.ACCESSIBILITY_CAPTIONING_EDGE_TYPE, DEFAULT_CUSTOM.edgeType);
            final int edgeColor = Secure.getInt(
                    cr, Secure.ACCESSIBILITY_CAPTIONING_EDGE_COLOR, DEFAULT_CUSTOM.edgeColor);

            String rawTypeface = Secure.getString(cr, Secure.ACCESSIBILITY_CAPTIONING_TYPEFACE);
            if (rawTypeface == null) {
                rawTypeface = DEFAULT_CUSTOM.mRawTypeface;
            }

            return new CaptionStyle(
                    foregroundColor, backgroundColor, edgeType, edgeColor, rawTypeface);
        }

        static {
            WHITE_ON_BLACK = new CaptionStyle(
                    Color.WHITE, Color.BLACK, EDGE_TYPE_NONE, Color.BLACK, null);
            BLACK_ON_WHITE = new CaptionStyle(
                    Color.BLACK, Color.WHITE, EDGE_TYPE_NONE, Color.BLACK, null);
            YELLOW_ON_BLACK = new CaptionStyle(
                    Color.YELLOW, Color.BLACK, EDGE_TYPE_NONE, Color.BLACK, null);
            YELLOW_ON_BLUE = new CaptionStyle(
                    Color.YELLOW, Color.BLUE, EDGE_TYPE_NONE, Color.BLACK, null);

            PRESETS = new CaptionStyle[] {
                    WHITE_ON_BLACK, BLACK_ON_WHITE, YELLOW_ON_BLACK, YELLOW_ON_BLUE
            };

            DEFAULT_CUSTOM = WHITE_ON_BLACK;
        }
    }
}
