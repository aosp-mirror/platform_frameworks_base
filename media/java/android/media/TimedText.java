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

import android.os.Parcel;
import android.util.Log;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

/**
 * Class to hold the timed text's metadata.
 *
 * {@hide}
 */
public class TimedText
{
    private static final int FIRST_PUBLIC_KEY                 = 1;

    // These keys must be in sync with the keys in TextDescription.h
    public static final int KEY_DISPLAY_FLAGS                 = 1; // int
    public static final int KEY_STYLE_FLAGS                   = 2; // int
    public static final int KEY_BACKGROUND_COLOR_RGBA         = 3; // int
    public static final int KEY_HIGHLIGHT_COLOR_RGBA          = 4; // int
    public static final int KEY_SCROLL_DELAY                  = 5; // int
    public static final int KEY_WRAP_TEXT                     = 6; // int
    public static final int KEY_START_TIME                    = 7; // int
    public static final int KEY_STRUCT_BLINKING_TEXT_LIST     = 8; // List<CharPos>
    public static final int KEY_STRUCT_FONT_LIST              = 9; // List<Font>
    public static final int KEY_STRUCT_HIGHLIGHT_LIST         = 10; // List<CharPos>
    public static final int KEY_STRUCT_HYPER_TEXT_LIST        = 11; // List<HyperText>
    public static final int KEY_STRUCT_KARAOKE_LIST           = 12; // List<Karaoke>
    public static final int KEY_STRUCT_STYLE_LIST             = 13; // List<Style>
    public static final int KEY_STRUCT_TEXT_POS               = 14; // TextPos
    public static final int KEY_STRUCT_JUSTIFICATION          = 15; // Justification
    public static final int KEY_STRUCT_TEXT                   = 16; // Text

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

    private Parcel mParcel = Parcel.obtain();
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

    private TextPos mTextPos;
    private Justification mJustification;
    private Text mTextStruct;

    /**
     * Helper class to hold the text length and text content of
     * one text sample. The member variables in this class are
     * read-only.
     */
    public class Text {
        /**
         * The byte-count of this text sample
         */
        public int textLen;

        /**
         * The text sample
         */
        public byte[] text;

        public Text() { }
    }

    /**
     * Helper class to hold the start char offset and end char offset
     * for Blinking Text or Highlight Text. endChar is the end offset
     * of the text (startChar + number of characters to be highlighted
     * or blinked). The member variables in this class are read-only.
     */
    public class CharPos {
        /**
         * The offset of the start character
         */
        public int startChar = -1;

        /**
         * The offset of the end character
         */
        public int endChar = -1;

        public CharPos() { }
    }

    /**
     * Helper class to hold the box position to display the text sample.
     * The member variables in this class are read-only.
     */
    public class TextPos {
        /**
         * The top position of the text
         */
        public int top = -1;

        /**
         * The left position of the text
         */
        public int left = -1;

        /**
         * The bottom position of the text
         */
        public int bottom = -1;

        /**
         * The right position of the text
         */
        public int right = -1;

        public TextPos() { }
    }

    /**
     * Helper class to hold the justification for text display in the text box.
     * The member variables in this class are read-only.
     */
    public class Justification {
        /**
         * horizontalJustification  0: left, 1: centered, -1: right
         */
        public int horizontalJustification = -1;

        /**
         * verticalJustification  0: top, 1: centered, -1: bottom
         */
        public int verticalJustification = -1;

        public Justification() { }
    }

    /**
     * Helper class to hold the style information to display the text.
     * The member variables in this class are read-only.
     */
    public class Style {
        /**
         * The offset of the start character which applys this style
         */
        public int startChar = -1;

        /**
         * The offset of the end character which applys this style
         */
        public int endChar = -1;

        /**
         * ID of the font. This ID will be used to choose the font
         * to be used from the font list.
         */
        public int fontID = -1;

        /**
         * True if the characters should be bold
         */
        public boolean isBold = false;

        /**
         * True if the characters should be italic
         */
        public boolean isItalic = false;

        /**
         * True if the characters should be underlined
         */
        public boolean isUnderlined = false;

        /**
         * The size of the font
         */
        public int fontSize = -1;

        /**
         * To specify the RGBA color: 8 bits each of red, green, blue,
         * and an alpha(transparency) value
         */
        public int colorRGBA = -1;

        public Style() { }
    }

    /**
     * Helper class to hold the font ID and name.
     * The member variables in this class are read-only.
     */
    public class Font {
        /**
         * The font ID
         */
        public int ID = -1;

        /**
         * The font name
         */
        public String name;

        public Font() { }
    }

    /**
     * Helper class to hold the karaoke information.
     * The member variables in this class are read-only.
     */
    public class Karaoke {
        /**
         * The start time (in milliseconds) to highlight the characters
         * specified by startChar and endChar.
         */
        public int startTimeMs = -1;

        /**
         * The end time (in milliseconds) to highlight the characters
         * specified by startChar and endChar.
         */
        public int endTimeMs = -1;

        /**
         * The offset of the start character to be highlighted
         */
        public int startChar = -1;

        /**
         * The offset of the end character to be highlighted
         */
        public int endChar = -1;

        public Karaoke() { }
    }

    /**
     * Helper class to hold the hyper text information.
     * The member variables in this class are read-only.
     */
    public class HyperText {
        /**
         * The offset of the start character
         */
        public int startChar = -1;

        /**
         * The offset of the end character
         */
        public int endChar = -1;

        /**
         * The linked-to URL
         */
        public String URL;

        /**
         * The "alt" string for user display
         */
        public String altString;

        public HyperText() { }
    }

    /**
     * @param obj the byte array which contains the timed text.
     * @throws IllegalArgumentExcept if parseParcel() fails.
     * {@hide}
     */
    public TimedText(byte[] obj) {
        mParcel.unmarshall(obj, 0, obj.length);

        if (!parseParcel()) {
            mKeyObjectMap.clear();
            throw new IllegalArgumentException("parseParcel() fails");
        }
    }

    /**
     * Go over all the records, collecting metadata keys and fields in the
     * Parcel. These are stored in mKeyObjectMap for application to retrieve.
     * @return false if an error occurred during parsing. Otherwise, true.
     */
    private boolean parseParcel() {
        mParcel.setDataPosition(0);
        if (mParcel.dataAvail() == 0) {
            return false;
        }

        int type = mParcel.readInt();
        if (type == KEY_LOCAL_SETTING) {
            type = mParcel.readInt();
            if (type != KEY_START_TIME) {
                return false;
            }
            int mStartTimeMs = mParcel.readInt();
            mKeyObjectMap.put(type, mStartTimeMs);

            type = mParcel.readInt();
            if (type != KEY_STRUCT_TEXT) {
                return false;
            }

            mTextStruct = new Text();
            mTextStruct.textLen = mParcel.readInt();

            mTextStruct.text = mParcel.createByteArray();
            mKeyObjectMap.put(type, mTextStruct);

        } else if (type != KEY_GLOBAL_SETTING) {
            Log.w(TAG, "Invalid timed text key found: " + type);
            return false;
        }

        while (mParcel.dataAvail() > 0) {
            int key = mParcel.readInt();
            if (!isValidKey(key)) {
                Log.w(TAG, "Invalid timed text key found: " + key);
                return false;
            }

            Object object = null;

            switch (key) {
                case KEY_STRUCT_STYLE_LIST: {
                    readStyle();
                    object = mStyleList;
                    break;
                }
                case KEY_STRUCT_FONT_LIST: {
                    readFont();
                    object = mFontList;
                    break;
                }
                case KEY_STRUCT_HIGHLIGHT_LIST: {
                    readHighlight();
                    object = mHighlightPosList;
                    break;
                }
                case KEY_STRUCT_KARAOKE_LIST: {
                    readKaraoke();
                    object = mKaraokeList;
                    break;
                }
                case KEY_STRUCT_HYPER_TEXT_LIST: {
                    readHyperText();
                    object = mHyperTextList;

                    break;
                }
                case KEY_STRUCT_BLINKING_TEXT_LIST: {
                    readBlinkingText();
                    object = mBlinkingPosList;

                    break;
                }
                case KEY_WRAP_TEXT: {
                    mWrapText = mParcel.readInt();
                    object = mWrapText;
                    break;
                }
                case KEY_HIGHLIGHT_COLOR_RGBA: {
                    mHighlightColorRGBA = mParcel.readInt();
                    object = mHighlightColorRGBA;
                    break;
                }
                case KEY_DISPLAY_FLAGS: {
                    mDisplayFlags = mParcel.readInt();
                    object = mDisplayFlags;
                    break;
                }
                case KEY_STRUCT_JUSTIFICATION: {
                    mJustification = new Justification();

                    mJustification.horizontalJustification = mParcel.readInt();
                    mJustification.verticalJustification = mParcel.readInt();

                    object = mJustification;
                    break;
                }
                case KEY_BACKGROUND_COLOR_RGBA: {
                    mBackgroundColorRGBA = mParcel.readInt();
                    object = mBackgroundColorRGBA;
                    break;
                }
                case KEY_STRUCT_TEXT_POS: {
                    mTextPos = new TextPos();

                    mTextPos.top = mParcel.readInt();
                    mTextPos.left = mParcel.readInt();
                    mTextPos.bottom = mParcel.readInt();
                    mTextPos.right = mParcel.readInt();

                    object = mTextPos;
                    break;
                }
                case KEY_SCROLL_DELAY: {
                    mScrollDelay = mParcel.readInt();
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
                mKeyObjectMap.put(key, object);
            }
        }

        mParcel.recycle();
        return true;
    }

    /**
     * To parse and store the Style list.
     */
    private void readStyle() {
        Style style = new Style();
        boolean endOfStyle = false;

        while (!endOfStyle && (mParcel.dataAvail() > 0)) {
            int key = mParcel.readInt();
            switch (key) {
                case KEY_START_CHAR: {
                    style.startChar = mParcel.readInt();
                    break;
                }
                case KEY_END_CHAR: {
                    style.endChar = mParcel.readInt();
                    break;
                }
                case KEY_FONT_ID: {
                    style.fontID = mParcel.readInt();
                    break;
                }
                case KEY_STYLE_FLAGS: {
                    int flags = mParcel.readInt();
                    // In the absence of any bits set in flags, the text
                    // is plain. Otherwise, 1: bold, 2: italic, 4: underline
                    style.isBold = ((flags % 2) == 1);
                    style.isItalic = ((flags % 4) >= 2);
                    style.isUnderlined = ((flags / 4) == 1);
                    break;
                }
                case KEY_FONT_SIZE: {
                    style.fontSize = mParcel.readInt();
                    break;
                }
                case KEY_TEXT_COLOR_RGBA: {
                    style.colorRGBA = mParcel.readInt();
                    break;
                }
                default: {
                    // End of the Style parsing. Reset the data position back
                    // to the position before the last mParcel.readInt() call.
                    mParcel.setDataPosition(mParcel.dataPosition() - 4);
                    endOfStyle = true;
                    break;
                }
            }
        }

        if (mStyleList == null) {
            mStyleList = new ArrayList<Style>();
        }
        mStyleList.add(style);
    }

    /**
     * To parse and store the Font list
     */
    private void readFont() {
        int entryCount = mParcel.readInt();

        for (int i = 0; i < entryCount; i++) {
            Font font = new Font();

            font.ID = mParcel.readInt();
            int nameLen = mParcel.readInt();

            byte[] text = mParcel.createByteArray();
            font.name = new String(text, 0, nameLen);

            if (mFontList == null) {
                mFontList = new ArrayList<Font>();
            }
            mFontList.add(font);
        }
    }

    /**
     * To parse and store the Highlight list
     */
    private void readHighlight() {
        CharPos pos = new CharPos();

        pos.startChar = mParcel.readInt();
        pos.endChar = mParcel.readInt();

        if (mHighlightPosList == null) {
            mHighlightPosList = new ArrayList<CharPos>();
        }
        mHighlightPosList.add(pos);
    }

    /**
     * To parse and store the Karaoke list
     */
    private void readKaraoke() {
        int entryCount = mParcel.readInt();

        for (int i = 0; i < entryCount; i++) {
            Karaoke kara = new Karaoke();

            kara.startTimeMs = mParcel.readInt();
            kara.endTimeMs = mParcel.readInt();
            kara.startChar = mParcel.readInt();
            kara.endChar = mParcel.readInt();

            if (mKaraokeList == null) {
                mKaraokeList = new ArrayList<Karaoke>();
            }
            mKaraokeList.add(kara);
        }
    }

    /**
     * To parse and store HyperText list
     */
    private void readHyperText() {
        HyperText hyperText = new HyperText();

        hyperText.startChar = mParcel.readInt();
        hyperText.endChar = mParcel.readInt();

        int len = mParcel.readInt();
        byte[] url = mParcel.createByteArray();
        hyperText.URL = new String(url, 0, len);

        len = mParcel.readInt();
        byte[] alt = mParcel.createByteArray();
        hyperText.altString = new String(alt, 0, len);

        if (mHyperTextList == null) {
            mHyperTextList = new ArrayList<HyperText>();
        }
        mHyperTextList.add(hyperText);
    }

    /**
     * To parse and store blinking text list
     */
    private void readBlinkingText() {
        CharPos blinkingPos = new CharPos();

        blinkingPos.startChar = mParcel.readInt();
        blinkingPos.endChar = mParcel.readInt();

        if (mBlinkingPosList == null) {
            mBlinkingPosList = new ArrayList<CharPos>();
        }
        mBlinkingPosList.add(blinkingPos);
    }

    /**
     * To check whether the given key is valid.
     * @param key the key to be checked.
     * @return true if the key is a valid one. Otherwise, false.
     */
    public boolean isValidKey(final int key) {
        if (!((key >= FIRST_PUBLIC_KEY) && (key <= LAST_PUBLIC_KEY))
                && !((key >= FIRST_PRIVATE_KEY) && (key <= LAST_PRIVATE_KEY))) {
            return false;
        }
        return true;
    }

    /**
     * To check whether the given key is contained in this TimedText object.
     * @param key the key to be checked.
     * @return true if the key is contained in this TimedText object.
     *         Otherwise, false.
     */
    public boolean containsKey(final int key) {
        if (isValidKey(key) && mKeyObjectMap.containsKey(key)) {
            return true;
        }
        return false;
    }
    /**
     * @return a set of the keys contained in this TimedText object.
     */
    public Set keySet() {
        return mKeyObjectMap.keySet();
    }

    /**
     * To retrieve the object associated with the key. Caller must make sure
     * the key is present using the containsKey method otherwise a
     * RuntimeException will occur.
     * @param key the key used to retrieve the object.
     * @return an object. The object could be an instanceof Integer, List, or
     * any of the helper classes such as TextPos, Justification, and Text.
     */
    public Object getObject(final int key) {
        if (containsKey(key)) {
            return mKeyObjectMap.get(key);
        } else {
            throw new IllegalArgumentException("Invalid key: " + key);
        }
    }
}
