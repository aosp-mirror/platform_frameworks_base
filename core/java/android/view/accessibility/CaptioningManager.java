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
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings.Secure;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Contains methods for accessing and monitoring preferred video captioning state and visual
 * properties.
 * <p>
 * To obtain a handle to the captioning manager, do the following:
 * <p>
 * <code>
 * <pre>CaptioningManager captioningManager =
 *        (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);</pre>
 * </code>
 */
public class CaptioningManager {
    /** Default captioning enabled value. */
    private static final int DEFAULT_ENABLED = 0;

    /** Default style preset as an index into {@link CaptionStyle#PRESETS}. */
    private static final int DEFAULT_PRESET = 0;

    /** Default scaling value for caption fonts. */
    private static final float DEFAULT_FONT_SCALE = 1;

    private final ArrayList<CaptioningChangeListener>
            mListeners = new ArrayList<CaptioningChangeListener>();
    private final Handler mHandler = new Handler();

    private final ContentResolver mContentResolver;

    /**
     * Creates a new captioning manager for the specified context.
     *
     * @hide
     */
    public CaptioningManager(Context context) {
        mContentResolver = context.getContentResolver();
    }

    /**
     * @return the user's preferred captioning enabled state
     */
    public final boolean isEnabled() {
        return Secure.getInt(
                mContentResolver, Secure.ACCESSIBILITY_CAPTIONING_ENABLED, DEFAULT_ENABLED) == 1;
    }

    /**
     * @return the raw locale string for the user's preferred captioning
     *         language
     * @hide
     */
    public final String getRawLocale() {
        return Secure.getString(mContentResolver, Secure.ACCESSIBILITY_CAPTIONING_LOCALE);
    }

    /**
     * @return the locale for the user's preferred captioning language, or null
     *         if not specified
     */
    public final Locale getLocale() {
        final String rawLocale = getRawLocale();
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
     * @return the user's preferred font scaling factor for video captions, or 1 if not
     *         specified
     */
    public final float getFontScale() {
        return Secure.getFloat(
                mContentResolver, Secure.ACCESSIBILITY_CAPTIONING_FONT_SCALE, DEFAULT_FONT_SCALE);
    }

    /**
     * @return the raw preset number, or the first preset if not specified
     * @hide
     */
    public int getRawUserStyle() {
        return Secure.getInt(
                mContentResolver, Secure.ACCESSIBILITY_CAPTIONING_PRESET, DEFAULT_PRESET);
    }

    /**
     * @return the user's preferred visual properties for captions as a
     *         {@link CaptionStyle}, or the default style if not specified
     */
    public CaptionStyle getUserStyle() {
        final int preset = getRawUserStyle();
        if (preset == CaptionStyle.PRESET_CUSTOM) {
            return CaptionStyle.getCustomStyle(mContentResolver);
        }

        return CaptionStyle.PRESETS[preset];
    }

    /**
     * Adds a listener for changes in the user's preferred captioning enabled
     * state and visual properties.
     *
     * @param listener the listener to add
     */
    public void addCaptioningChangeListener(CaptioningChangeListener listener) {
        synchronized (mListeners) {
            if (mListeners.isEmpty()) {
                registerObserver(Secure.ACCESSIBILITY_CAPTIONING_ENABLED);
                registerObserver(Secure.ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR);
                registerObserver(Secure.ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR);
                registerObserver(Secure.ACCESSIBILITY_CAPTIONING_EDGE_TYPE);
                registerObserver(Secure.ACCESSIBILITY_CAPTIONING_EDGE_COLOR);
                registerObserver(Secure.ACCESSIBILITY_CAPTIONING_TYPEFACE);
                registerObserver(Secure.ACCESSIBILITY_CAPTIONING_FONT_SCALE);
                registerObserver(Secure.ACCESSIBILITY_CAPTIONING_LOCALE);
            }

            mListeners.add(listener);
        }
    }

    private void registerObserver(String key) {
        mContentResolver.registerContentObserver(Secure.getUriFor(key), false, mContentObserver);
    }

    /**
     * Removes a listener previously added using
     * {@link #addCaptioningChangeListener}.
     *
     * @param listener the listener to remove
     */
    public void removeCaptioningChangeListener(CaptioningChangeListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);

            if (mListeners.isEmpty()) {
                mContentResolver.unregisterContentObserver(mContentObserver);
            }
        }
    }

    private void notifyEnabledChanged() {
        final boolean enabled = isEnabled();
        synchronized (mListeners) {
            for (CaptioningChangeListener listener : mListeners) {
                listener.onEnabledChanged(enabled);
            }
        }
    }

    private void notifyUserStyleChanged() {
        final CaptionStyle userStyle = getUserStyle();
        synchronized (mListeners) {
            for (CaptioningChangeListener listener : mListeners) {
                listener.onUserStyleChanged(userStyle);
            }
        }
    }

    private void notifyLocaleChanged() {
        final Locale locale = getLocale();
        synchronized (mListeners) {
            for (CaptioningChangeListener listener : mListeners) {
                listener.onLocaleChanged(locale);
            }
        }
    }

    private void notifyFontScaleChanged() {
        final float fontScale = getFontScale();
        synchronized (mListeners) {
            for (CaptioningChangeListener listener : mListeners) {
                listener.onFontScaleChanged(fontScale);
            }
        }
    }

    private final ContentObserver mContentObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            final String uriPath = uri.getPath();
            final String name = uriPath.substring(uriPath.lastIndexOf('/') + 1);
            if (Secure.ACCESSIBILITY_CAPTIONING_ENABLED.equals(name)) {
                notifyEnabledChanged();
            } else if (Secure.ACCESSIBILITY_CAPTIONING_LOCALE.equals(name)) {
                notifyLocaleChanged();
            } else if (Secure.ACCESSIBILITY_CAPTIONING_FONT_SCALE.equals(name)) {
                notifyFontScaleChanged();
            } else {
                // We only need a single callback when multiple style properties
                // change in rapid succession.
                mHandler.removeCallbacks(mStyleChangedRunnable);
                mHandler.post(mStyleChangedRunnable);
            }
        }
    };

    /**
     * Runnable posted when user style properties change. This is used to
     * prevent unnecessary change notifications when multiple properties change
     * in rapid succession.
     */
    private final Runnable mStyleChangedRunnable = new Runnable() {
        @Override
        public void run() {
            notifyUserStyleChanged();
        }
    };

    /**
     * Specifies visual properties for video captions, including foreground and
     * background colors, edge properties, and typeface.
     */
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
         * @return the preferred {@link Typeface} for video captions, or null if
         *         not specified
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
        public static CaptionStyle getCustomStyle(ContentResolver cr) {
            final CaptionStyle defStyle = CaptionStyle.DEFAULT_CUSTOM;
            final int foregroundColor = Secure.getInt(
                    cr, Secure.ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR, defStyle.foregroundColor);
            final int backgroundColor = Secure.getInt(
                    cr, Secure.ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR, defStyle.backgroundColor);
            final int edgeType = Secure.getInt(
                    cr, Secure.ACCESSIBILITY_CAPTIONING_EDGE_TYPE, defStyle.edgeType);
            final int edgeColor = Secure.getInt(
                    cr, Secure.ACCESSIBILITY_CAPTIONING_EDGE_COLOR, defStyle.edgeColor);

            String rawTypeface = Secure.getString(cr, Secure.ACCESSIBILITY_CAPTIONING_TYPEFACE);
            if (rawTypeface == null) {
                rawTypeface = defStyle.mRawTypeface;
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

    /**
     * Listener for changes in captioning properties, including enabled state
     * and user style preferences.
     */
    public static abstract class CaptioningChangeListener {
        /**
         * Called when the captioning enabled state changes.
         *
         * @param enabled the user's new preferred captioning enabled state
         */
        public void onEnabledChanged(boolean enabled) {
        }

        /**
         * Called when the captioning user style changes.
         *
         * @param userStyle the user's new preferred style
         * @see CaptioningManager#getUserStyle()
         */
        public void onUserStyleChanged(CaptionStyle userStyle) {
        }

        /**
         * Called when the captioning locale changes.
         *
         * @param locale the preferred captioning locale
         * @see CaptioningManager#getLocale()
         */
        public void onLocaleChanged(Locale locale) {
        }

        /**
         * Called when the captioning font scaling factor changes.
         *
         * @param fontScale the preferred font scaling factor
         * @see CaptioningManager#getFontScale()
         */
        public void onFontScaleChanged(float fontScale) {
        }
    }
}
