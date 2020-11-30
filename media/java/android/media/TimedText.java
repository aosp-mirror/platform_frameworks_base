/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.media;

import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcel;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Class to hold the timed text's metadata, including:
 * <ul>
 * <li> The characters for rendering</li>
 * <li> The rendering position for the timed text</li>
 * </ul>
 *
 * <p> To render the timed text, applications need to do the following:
 *
 * <ul>
 * <li> Implement the {@link MediaPlayer.OnTimedTextListener} interface</li>
 * <li> Register the {@link MediaPlayer.OnTimedTextListener} callback on a MediaPlayer object that is used for playback</li>
 * <li> When a onTimedText callback is received, do the following:
 * <ul>
 * <li> call {@link #getText} to get the characters for rendering</li>
 * <li> call {@link #getBounds} to get the text rendering area/region</li>
 * </ul>
 * </li>
 * </ul>
 *
 * @see android.media.MediaPlayer
 */
public final class TimedText
{
    private static final int FIRST_PUBLIC_KEY                 = 1;

    // These keys must be in sync with the keys in TextDescription.h
    private static final int KEY_DISPLAY_FLAGS                 = 1; // int
    private static final int KEY_STYLE_FLAGS                   = 2; // int
    private static final int KEY_BACKGROUND_COLOR_RGBA         = 3; // int
    private static final int KEY_HIGHLIGHT_COLOR_RGBA          = 4; // int
    private static final int KEY_SCROLL_DELAY                  = 5; // int
    private static final int KEY_WRAP_TEXT                     = 6; // int
    private static final int KEY_START_TIME                    = 7; // int
    private static final int KEY_STRUCT_BLINKING_TEXT_LIST     = 8; // List<CharPos>
    private static final int KEY_STRUCT_FONT_LIST              = 9; // List<Font>
    private static final int KEY_STRUCT_HIGHLIGHT_LIST         = 10; // List<CharPos>
    private static final int KEY_STRUCT_HYPER_TEXT_LIST        = 11; // List<HyperText>
    private static final int KEY_STRUCT_KARAOKE_LIST           = 12; // List<Karaoke>
    private static final int KEY_STRUCT_STYLE_LIST             = 13; // List<Style>
    private static final int KEY_STRUCT_TEXT_POS               = 14; // TextPos
    private static final int KEY_STRUCT_JUSTIFICATION          = 15; // Justification
    private static final int KEY_STRUCT_TEXT                   = 16; // Text

    private static final int LAST_PUBLIC_KEY                  = 16;

    private static final int FIRST_PRIVATE_KEY                = 101;

    // The following keys are used between TimedText.java and
    // TextDescription.cpp in order to parce the Parcel.
    private static final int KEY_GLOBAL_SETTING               = 101;
    private static final int KEY_LOCAL_SETTING                = 102;
    private static final int KEY_START_CHAR                   = 103;
    private static final int KEY_END_CHAR                     = 104;
    private static final int KEY_FONT_ID                      = 105;
    private static final int KEY_FONT_SIZE                    = 106;
    private static final int KEY_TEXT_COLOR_RGBA              = 107;

    private static final int LAST_PRIVATE_KEY                 = 107;

    private static final String TAG = "TimedText";

    private final HashMap<Integer, Object> mKeyObjectMap =
            new HashMap<Integer, Object>();

    private int mDisplayFlags = -1;
    private int mBackgroundColorRGBA = -1;
    private int mHighlightColorRGBA = -1;
    private int mScrollDelay = -1;
    private int mWrapText = -1;

    private List<CharPos> mBlinkingPosList = null;
    private List<CharPos> mHighlightPosList = null;
    private List<Karaoke> mKaraokeList = null;
    private List<Font> mFontList = null;
    private List<Style> mStyleList = null;
    private List<HyperText> mHyperTextList = null;

    private Rect mTextBounds = null;
    private String mTextChars = null;

    private Justification mJustification;

    /**
     * Helper class to hold the start char offset and end char offset
     * for Blinking Text or Highlight Text. endChar is the end offset
     * of the text (startChar + number of characters to be highlighted
     * or blinked). The member variables in this class are read-only.
     * {@hide}
     */
    public static final class CharPos {
        /**
         * The offset of the start character
         */
        public final int startChar;

        /**
         * The offset of the end character
         */
        public final int endChar;

        /**
         * Constuctor
         * @param startChar the offset of the start character.
         * @param endChar the offset of the end character.
         */
        public CharPos(int startChar, int endChar) {
            this.startChar = startChar;
            this.endChar = endChar;
        }
    }

    /**
     * Helper class to hold the justification for text display in the text box.
     * The member variables in this class are read-only.
     * {@hide}
     */
    public static final class Justification {
        /**
         * horizontal justification  0: left, 1: centered, -1: right
         */
        public final int horizontalJustification;

        /**
         * vertical justification  0: top, 1: centered, -1: bottom
         */
        public final int verticalJustification;

        /**
         * Constructor
         * @param horizontal the horizontal justification of the text.
         * @param vertical the vertical justification of the text.
         */
        public Justification(int horizontal, int vertical) {
            this.horizontalJustification = horizontal;
            this.verticalJustification = vertical;
        }
    }

    /**
     * Helper class to hold the style information to display the text.
     * The member variables in this class are read-only.
     * {@hide}
     */
    public static final class Style {
        /**
         * The offset of the start character which applys this style
         */
        public final int startChar;

        /**
         * The offset of the end character which applys this style
         */
        public final int endChar;

        /**
         * ID of the font. This ID will be used to choose the font
         * to be used from the font list.
         */
        public final int fontID;

        /**
         * True if the characters should be bold
         */
        public final boolean isBold;

        /**
         * True if the characters should be italic
         */
        public final boolean isItalic;

        /**
         * True if the characters should be underlined
         */
        public final boolean isUnderlined;

        /**
         * The size of the font
         */
        public final int fontSize;

        /**
         * To specify the RGBA color: 8 bits each of red, green, blue,
         * and an alpha(transparency) value
         */
        public final int colorRGBA;

        /**
         * Constructor
         * @param startChar the offset of the start character which applys this style
         * @param endChar the offset of the end character which applys this style
         * @param fontId the ID of the font.
         * @param isBold whether the characters should be bold.
         * @param isItalic whether the characters should be italic.
         * @param isUnderlined whether the characters should be underlined.
         * @param fontSize the size of the font.
         * @param colorRGBA red, green, blue, and alpha value for color.
         */
        public Style(int startChar, int endChar, int fontId,
                     boolean isBold, boolean isItalic, boolean isUnderlined,
                     int fontSize, int colorRGBA) {
            this.startChar = startChar;
            this.endChar = endChar;
            this.fontID = fontId;
            this.isBold = isBold;
            this.isItalic = isItalic;
            this.isUnderlined = isUnderlined;
            this.fontSize = fontSize;
            this.colorRGBA = colorRGBA;
        }
    }

    /**
     * Helper class to hold the font ID and name.
     * The member variables in this class are read-only.
     * {@hide}
     */
    public static final class Font {
        /**
         * The font ID
         */
        public final int ID;

        /**
         * The font name
         */
        public final String name;

        /**
         * Constructor
         * @param id the font ID.
         * @param name the font name.
         */
        public Font(int id, String name) {
            this.ID = id;
            this.name = name;
        }
    }

    /**
     * Helper class to hold the karaoke information.
     * The member variables in this class are read-only.
     * {@hide}
     */
    public static final class Karaoke {
        /**
         * The start time (in milliseconds) to highlight the characters
         * specified by startChar and endChar.
         */
        public final int startTimeMs;

        /**
         * The end time (in milliseconds) to highlight the characters
         * specified by startChar and endChar.
         */
        public final int endTimeMs;

        /**
         * The offset of the start character to be highlighted
         */
        public final int startChar;

        /**
         * The offset of the end character to be highlighted
         */
        public final int endChar;

        /**
         * Constructor
         * @param startTimeMs the start time (in milliseconds) to highlight
         * the characters between startChar and endChar.
         * @param endTimeMs the end time (in milliseconds) to highlight
         * the characters between startChar and endChar.
         * @param startChar the offset of the start character to be highlighted.
         * @param endChar the offset of the end character to be highlighted.
         */
        public Karaoke(int startTimeMs, int endTimeMs, int startChar, int endChar) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.startChar = startChar;
            this.endChar = endChar;
        }
    }

    /**
     * Helper class to hold the hyper text information.
     * The member variables in this class are read-only.
     * {@hide}
     */
    public static final class HyperText {
        /**
         * The offset of the start character
         */
        public final int startChar;

        /**
         * The offset of the end character
         */
        public final int endChar;

        /**
         * The linked-to URL
         */
        public final String URL;

        /**
         * The "alt" string for user display
         */
        public final String altString;


        /**
         * Constructor
         * @param startChar the offset of the start character.
         * @param endChar the offset of the end character.
         * @param url the linked-to URL.
         * @param alt the "alt" string for display.
         */
        public HyperText(int startChar, int endChar, String url, String alt) {
            this.startChar = startChar;
            this.endChar = endChar;
            this.URL = url;
            this.altString = alt;
        }
    }

    /**
     * @param obj the byte array which contains the timed text.
     * @throws IllegalArgumentExcept if parseParcel() fails.
     * {@hide}
     */
    public TimedText(Parcel parcel) {
        if (!parseParcel(parcel)) {
            mKeyObjectMap.clear();
            throw new IllegalArgumentException("parseParcel() fails");
        }
    }

    /**
     * @param text the characters in the timed text.
     * @param bounds the rectangle area or region for rendering the timed text.
     * {@hide}
     */
    public TimedText(String text, Rect bounds) {
        mTextChars = text;
        mTextBounds = bounds;
    }

    /**
     * Get the characters in the timed text.
     *
     * @return the characters as a String object in the TimedText. Applications
     * should stop rendering previous timed text at the current rendering region if
     * a null is returned, until the next non-null timed text is received.
     */
    public String getText() {
        return mTextChars;
    }

    /**
     * Get the rectangle area or region for rendering the timed text as specified
     * by a Rect object.
     *
     * @return the rectangle region to render the characters in the timed text.
     * If no bounds information is available (a null is returned), render the
     * timed text at the center bottom of the display.
     */
    public Rect getBounds() {
        return mTextBounds;
    }

    /*
     * Go over all the records, collecting metadata keys and fields in the
     * Parcel. These are stored in mKeyObjectMap for application to retrieve.
     * @return false if an error occurred during parsing. Otherwise, true.
     */
    private boolean parseParcel(Parcel parcel) {
        parcel.setDataPosition(0);
        if (parcel.dataAvail() == 0) {
            return false;
        }

        int type = parcel.readInt();
        if (type == KEY_LOCAL_SETTING) {
            type = parcel.readInt();
            if (type != KEY_START_TIME) {
                return false;
            }
            int mStartTimeMs = parcel.readInt();
            mKeyObjectMap.put(type, mStartTimeMs);

            type = parcel.readInt();
            if (type != KEY_STRUCT_TEXT) {
                return false;
            }

            int textLen = parcel.readInt();
            byte[] text = parcel.createByteArray();
            if (text == null || text.length == 0) {
                mTextChars = null;
            } else {
                mTextChars = new String(text);
            }

        } else if (type != KEY_GLOBAL_SETTING) {
            Log.w(TAG, "Invalid timed text key found: " + type);
            return false;
        }

        while (parcel.dataAvail() > 0) {
            int key = parcel.readInt();
            if (!isValidKey(key)) {
                Log.w(TAG, "Invalid timed text key found: " + key);
                return false;
            }

            Object object = null;

            switch (key) {
                case KEY_STRUCT_STYLE_LIST: {
                    readStyle(parcel);
                    object = mStyleList;
                    break;
                }
                case KEY_STRUCT_FONT_LIST: {
                    readFont(parcel);
                    object = mFontList;
                    break;
                }
                case KEY_STRUCT_HIGHLIGHT_LIST: {
                    readHighlight(parcel);
                    object = mHighlightPosList;
                    break;
                }
                case KEY_STRUCT_KARAOKE_LIST: {
                    readKaraoke(parcel);
                    object = mKaraokeList;
                    break;
                }
                case KEY_STRUCT_HYPER_TEXT_LIST: {
                    readHyperText(parcel);
                    object = mHyperTextList;

                    break;
                }
                case KEY_STRUCT_BLINKING_TEXT_LIST: {
                    readBlinkingText(parcel);
                    object = mBlinkingPosList;

                    break;
                }
                case KEY_WRAP_TEXT: {
                    mWrapText = parcel.readInt();
                    object = mWrapText;
                    break;
                }
                case KEY_HIGHLIGHT_COLOR_RGBA: {
                    mHighlightColorRGBA = parcel.readInt();
                    object = mHighlightColorRGBA;
                    break;
                }
                case KEY_DISPLAY_FLAGS: {
                    mDisplayFlags = parcel.readInt();
                    object = mDisplayFlags;
                    break;
                }
                case KEY_STRUCT_JUSTIFICATION: {

                    int horizontal = parcel.readInt();
                    int vertical = parcel.readInt();
                    mJustification = new Justification(horizontal, vertical);

                    object = mJustification;
                    break;
                }
                case KEY_BACKGROUND_COLOR_RGBA: {
                    mBackgroundColorRGBA = parcel.readInt();
                    object = mBackgroundColorRGBA;
                    break;
                }
                case KEY_STRUCT_TEXT_POS: {
                    int top = parcel.readInt();
                    int left = parcel.readInt();
                    int bottom = parcel.readInt();
                    int right = parcel.readInt();
                    mTextBounds = new Rect(left, top, right, bottom);

                    break;
                }
                case KEY_SCROLL_DELAY: {
                    mScrollDelay = parcel.readInt();
                    object = mScrollDelay;
                    break;
                }
                default: {
                    break;
                }
            }

            if (object != null) {
                if (mKeyObjectMap.containsKey(key)) {
                    mKeyObjectMap.remove(key);
                }
                // Previous mapping will be replaced with the new object, if there was one.
                mKeyObjectMap.put(key, object);
            }
        }

        return true;
    }

    /*
     * To parse and store the Style list.
     */
    private void readStyle(Parcel parcel) {
        boolean endOfStyle = false;
        int startChar = -1;
        int endChar = -1;
        int fontId = -1;
        boolean isBold = false;
        boolean isItalic = false;
        boolean isUnderlined = false;
        int fontSize = -1;
        int colorRGBA = -1;
        while (!endOfStyle && (parcel.dataAvail() > 0)) {
            int key = parcel.readInt();
            switch (key) {
                case KEY_START_CHAR: {
                    startChar = parcel.readInt();
                    break;
                }
                case KEY_END_CHAR: {
                    endChar = parcel.readInt();
                    break;
                }
                case KEY_FONT_ID: {
                    fontId = parcel.readInt();
                    break;
                }
                case KEY_STYLE_FLAGS: {
                    int flags = parcel.readInt();
                    // In the absence of any bits set in flags, the text
                    // is plain. Otherwise, 1: bold, 2: italic, 4: underline
                    isBold = ((flags % 2) == 1);
                    isItalic = ((flags % 4) >= 2);
                    isUnderlined = ((flags / 4) == 1);
                    break;
                }
                case KEY_FONT_SIZE: {
                    fontSize = parcel.readInt();
                    break;
                }
                case KEY_TEXT_COLOR_RGBA: {
                    colorRGBA = parcel.readInt();
                    break;
                }
                default: {
                    // End of the Style parsing. Reset the data position back
                    // to the position before the last parcel.readInt() call.
                    parcel.setDataPosition(parcel.dataPosition() - 4);
                    endOfStyle = true;
                    break;
                }
            }
        }

        Style style = new Style(startChar, endChar, fontId, isBold,
                                isItalic, isUnderlined, fontSize, colorRGBA);
        if (mStyleList == null) {
            mStyleList = new ArrayList<Style>();
        }
        mStyleList.add(style);
    }

    /*
     * To parse and store the Font list
     */
    private void readFont(Parcel parcel) {
        int entryCount = parcel.readInt();

        for (int i = 0; i < entryCount; i++) {
            int id = parcel.readInt();
            int nameLen = parcel.readInt();

            byte[] text = parcel.createByteArray();
            final String name = new String(text, 0, nameLen);

            Font font = new Font(id, name);

            if (mFontList == null) {
                mFontList = new ArrayList<Font>();
            }
            mFontList.add(font);
        }
    }

    /*
     * To parse and store the Highlight list
     */
    private void readHighlight(Parcel parcel) {
        int startChar = parcel.readInt();
        int endChar = parcel.readInt();
        CharPos pos = new CharPos(startChar, endChar);

        if (mHighlightPosList == null) {
            mHighlightPosList = new ArrayList<CharPos>();
        }
        mHighlightPosList.add(pos);
    }

    /*
     * To parse and store the Karaoke list
     */
    private void readKaraoke(Parcel parcel) {
        int entryCount = parcel.readInt();

        for (int i = 0; i < entryCount; i++) {
            int startTimeMs = parcel.readInt();
            int endTimeMs = parcel.readInt();
            int startChar = parcel.readInt();
            int endChar = parcel.readInt();
            Karaoke kara = new Karaoke(startTimeMs, endTimeMs,
                                       startChar, endChar);

            if (mKaraokeList == null) {
                mKaraokeList = new ArrayList<Karaoke>();
            }
            mKaraokeList.add(kara);
        }
    }

    /*
     * To parse and store HyperText list
     */
    private void readHyperText(Parcel parcel) {
        int startChar = parcel.readInt();
        int endChar = parcel.readInt();

        int len = parcel.readInt();
        byte[] url = parcel.createByteArray();
        final String urlString = new String(url, 0, len);

        len = parcel.readInt();
        byte[] alt = parcel.createByteArray();
        final String altString = new String(alt, 0, len);
        HyperText hyperText = new HyperText(startChar, endChar, urlString, altString);


        if (mHyperTextList == null) {
            mHyperTextList = new ArrayList<HyperText>();
        }
        mHyperTextList.add(hyperText);
    }

    /*
     * To parse and store blinking text list
     */
    private void readBlinkingText(Parcel parcel) {
        int startChar = parcel.readInt();
        int endChar = parcel.readInt();
        CharPos blinkingPos = new CharPos(startChar, endChar);

        if (mBlinkingPosList == null) {
            mBlinkingPosList = new ArrayList<CharPos>();
        }
        mBlinkingPosList.add(blinkingPos);
    }

    /*
     * To check whether the given key is valid.
     * @param key the key to be checked.
     * @return true if the key is a valid one. Otherwise, false.
     */
    private boolean isValidKey(final int key) {
        if (!((key >= FIRST_PUBLIC_KEY) && (key <= LAST_PUBLIC_KEY))
                && !((key >= FIRST_PRIVATE_KEY) && (key <= LAST_PRIVATE_KEY))) {
            return false;
        }
        return true;
    }

    /*
     * To check whether the given key is contained in this TimedText object.
     * @param key the key to be checked.
     * @return true if the key is contained in this TimedText object.
     *         Otherwise, false.
     */
    private boolean containsKey(final int key) {
        if (isValidKey(key) && mKeyObjectMap.containsKey(key)) {
            return true;
        }
        return false;
    }

    /*
     * @return a set of the keys contained in this TimedText object.
     */
    private Set keySet() {
        return mKeyObjectMap.keySet();
    }

    /*
     * To retrieve the object associated with the key. Caller must make sure
     * the key is present using the containsKey method otherwise a
     * RuntimeException will occur.
     * @param key the key used to retrieve the object.
     * @return an object. The object could be 1) an instance of Integer; 2) a
     * List of CharPos, Karaoke, Font, Style, and HyperText, or 3) an instance of
     * Justification.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Object getObject(final int key) {
        if (containsKey(key)) {
            return mKeyObjectMap.get(key);
        } else {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
    }
}
