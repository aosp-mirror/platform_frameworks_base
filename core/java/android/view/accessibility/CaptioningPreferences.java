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
import android.provider.Settings;

import java.util.Locale;

/**
 * Contains methods for accessing preferred video captioning state and
 * properties.
 */
public class CaptioningPreferences {
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

    /**
     * Value specifying no character edges.
     *
     * @see #getEdgeType
     */
    public static final int EDGE_TYPE_NONE = 0;

    /**
     * Value specifying uniform (outlined) character edges.
     *
     * @see #getEdgeType
     */
    public static final int EDGE_TYPE_UNIFORM = 1;

    /**
     * Value specifying drop-shadowed character edges.
     *
     * @see #getEdgeType
     */
    public static final int EDGE_TYPE_DROP_SHADOWED = 2;

    // Typeface values MUST be synced with arrays.xml
    private static final String TYPEFACE_DEFAULT = "DEFAULT";
    private static final String TYPEFACE_MONOSPACE = "MONOSPACE";
    private static final String TYPEFACE_SANS_SERIF = "SANS_SERIF";
    private static final String TYPEFACE_SERIF = "SERIF";

    private static final int DEFAULT_ENABLED = 0;
    private static final int DEFAULT_FOREGROUND_COLOR = Color.WHITE;
    private static final int DEFAULT_BACKGROUND_COLOR = Color.TRANSPARENT;
    private static final int DEFAULT_EDGE_TYPE = EDGE_TYPE_UNIFORM;
    private static final int DEFAULT_EDGE_COLOR = Color.BLACK;
    private static final Typeface DEFAULT_TYPEFACE = Typeface.DEFAULT;
    private static final int DEFAULT_FONT_SIZE = 24;
    private static final String DEFAULT_LOCALE = "";

    /**
     * Returns the preferred enabled state for video captions.
     *
     * @param cr Resolver to access the database with.
     * @return True if captions should be shown in supported video players.
     */
    public static final boolean isEnabled(ContentResolver cr) {
        return Settings.Secure.getInt(
                cr, Settings.Secure.ACCESSIBILITY_CAPTIONING_ENABLED, DEFAULT_ENABLED) == 1;
    }

    /**
     * Returns the preferred foreground color for video captions.
     *
     * @param cr Resolver to access the database with.
     * @return The preferred foreground color for video captions.
     */
    public static final int getForegroundColor(ContentResolver cr) {
        return Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR,
                DEFAULT_FOREGROUND_COLOR);
    }

    /**
     * Returns the preferred background color for video captions.
     *
     * @param cr Resolver to access the database with.
     * @return The preferred background color for video captions.
     */
    public static final int getBackgroundColor(ContentResolver cr) {
        return Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR,
                DEFAULT_BACKGROUND_COLOR);
    }

    /**
     * Returns the preferred edge type for video captions, one of:
     * <ul>
     * <li>{@link #EDGE_TYPE_NONE}
     * <li>{@link #EDGE_TYPE_UNIFORM}
     * <li>{@link #EDGE_TYPE_DROP_SHADOWED}
     * </ul>
     *
     * @param cr Resolver to access the database with.
     * @return The preferred edge type for video captions.
     */
    public static final int getEdgeType(ContentResolver cr) {
        return Settings.Secure.getInt(
                cr, Settings.Secure.ACCESSIBILITY_CAPTIONING_EDGE_TYPE, DEFAULT_EDGE_TYPE);
    }

    /**
     * Returns the preferred shadow color for video captions.
     *
     * @param cr Resolver to access the database with.
     * @return The preferred shadow color for video captions.
     */
    public static final int getEdgeColor(ContentResolver cr) {
        return Settings.Secure.getInt(
                cr, Settings.Secure.ACCESSIBILITY_CAPTIONING_EDGE_COLOR, DEFAULT_EDGE_COLOR);
    }

    /**
     * Returns the raw value representing the preferred typeface for video
     * captions.
     *
     * @param cr Resolver to access the database with.
     * @return The raw value representing the preferred typeface for video
     *         captions.
     * @hide
     */
    public static final String getRawTypeface(ContentResolver cr) {
        final String rawTypeface = Settings.Secure.getString(
                cr, Settings.Secure.ACCESSIBILITY_CAPTIONING_TYPEFACE);
        if (rawTypeface != null) {
            return rawTypeface;
        }

        return TYPEFACE_DEFAULT;
    }

    /**
     * Returns the preferred typeface for video captions.
     *
     * @param cr Resolver to access the database with.
     * @return The preferred typeface for video captions.
     */
    public static final Typeface getTypeface(ContentResolver cr) {
        final String rawTypeface = getRawTypeface(cr);
        if (TYPEFACE_DEFAULT.equals(rawTypeface)) {
            return Typeface.DEFAULT;
        } else if (TYPEFACE_MONOSPACE.equals(rawTypeface)) {
            return Typeface.MONOSPACE;
        } else if (TYPEFACE_SANS_SERIF.equals(rawTypeface)) {
            return Typeface.SANS_SERIF;
        } else if (TYPEFACE_SERIF.equals(rawTypeface)) {
            return Typeface.SERIF;
        }

        return DEFAULT_TYPEFACE;
    }

    /**
     * Returns the raw value representing the preferred font size for video
     * captions.
     *
     * @param cr Resolver to access the database with.
     * @return The raw value representing the preferred font size for video
     *         captions.
     * @hide
     */
    public static final int getRawFontSize(ContentResolver cr) {
        return Settings.Secure.getInt(
                cr, Settings.Secure.ACCESSIBILITY_CAPTIONING_FONT_SIZE, DEFAULT_FONT_SIZE);
    }

    /**
     * Returns the preferred font size for video captions.
     *
     * @param cr Resolver to access the database with.
     * @return The preferred font size for video captions.
     */
    public static final float getFontSize(ContentResolver cr) {
        return getRawFontSize(cr);
    }

    /**
     * Returns the raw value representing the preferred locale for video
     * captions.
     *
     * @param cr Resolver to access the database with.
     * @return The raw value representing the preferred locale for video
     *         captions.
     * @hide
     */
    public static final String getRawLocale(ContentResolver cr) {
        final String rawLocale = Settings.Secure.getString(
                cr, Settings.Secure.ACCESSIBILITY_CAPTIONING_LOCALE);
        if (rawLocale != null) {
            return rawLocale;
        }

        return DEFAULT_LOCALE;
    }

    /**
     * Returns the preferred locale for video captions, or null to use the
     * device locale.
     *
     * @param cr Resolver to access the database with.
     * @return The preferred locale for video captions, or null to use the
     *         device locale.
     */
    public static final Locale getLocale(ContentResolver cr) {
        final String rawLocale = getRawLocale(cr);
        if (rawLocale.length() > 0) {
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
}
