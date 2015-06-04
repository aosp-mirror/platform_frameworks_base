/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.text.style.UpdateAppearance;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;
import android.view.accessibility.CaptioningManager.CaptioningChangeListener;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Vector;

/** @hide */
public class ClosedCaptionRenderer extends SubtitleController.Renderer {
    private final Context mContext;
    private ClosedCaptionWidget mRenderingWidget;

    public ClosedCaptionRenderer(Context context) {
        mContext = context;
    }

    @Override
    public boolean supports(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_MIME)) {
            return format.getString(MediaFormat.KEY_MIME).equals(
                    MediaPlayer.MEDIA_MIMETYPE_TEXT_CEA_608);
        }
        return false;
    }

    @Override
    public SubtitleTrack createTrack(MediaFormat format) {
        if (mRenderingWidget == null) {
            mRenderingWidget = new ClosedCaptionWidget(mContext);
        }
        return new ClosedCaptionTrack(mRenderingWidget, format);
    }
}

/** @hide */
class ClosedCaptionTrack extends SubtitleTrack {
    private final ClosedCaptionWidget mRenderingWidget;
    private final CCParser mCCParser;

    ClosedCaptionTrack(ClosedCaptionWidget renderingWidget, MediaFormat format) {
        super(format);

        mRenderingWidget = renderingWidget;
        mCCParser = new CCParser(renderingWidget);
    }

    @Override
    public void onData(byte[] data, boolean eos, long runID) {
        mCCParser.parse(data);
    }

    @Override
    public RenderingWidget getRenderingWidget() {
        return mRenderingWidget;
    }

    @Override
    public void updateView(Vector<Cue> activeCues) {
        // Overriding with NO-OP, CC rendering by-passes this
    }
}

/**
 * @hide
 *
 * CCParser processes CEA-608 closed caption data.
 *
 * It calls back into OnDisplayChangedListener upon
 * display change with styled text for rendering.
 *
 */
class CCParser {
    public static final int MAX_ROWS = 15;
    public static final int MAX_COLS = 32;

    private static final String TAG = "CCParser";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int INVALID = -1;

    // EIA-CEA-608: Table 70 - Control Codes
    private static final int RCL = 0x20;
    private static final int BS  = 0x21;
    private static final int AOF = 0x22;
    private static final int AON = 0x23;
    private static final int DER = 0x24;
    private static final int RU2 = 0x25;
    private static final int RU3 = 0x26;
    private static final int RU4 = 0x27;
    private static final int FON = 0x28;
    private static final int RDC = 0x29;
    private static final int TR  = 0x2a;
    private static final int RTD = 0x2b;
    private static final int EDM = 0x2c;
    private static final int CR  = 0x2d;
    private static final int ENM = 0x2e;
    private static final int EOC = 0x2f;

    // Transparent Space
    private static final char TS = '\u00A0';

    // Captioning Modes
    private static final int MODE_UNKNOWN = 0;
    private static final int MODE_PAINT_ON = 1;
    private static final int MODE_ROLL_UP = 2;
    private static final int MODE_POP_ON = 3;
    private static final int MODE_TEXT = 4;

    private final DisplayListener mListener;

    private int mMode = MODE_PAINT_ON;
    private int mRollUpSize = 4;
    private int mPrevCtrlCode = INVALID;

    private CCMemory mDisplay = new CCMemory();
    private CCMemory mNonDisplay = new CCMemory();
    private CCMemory mTextMem = new CCMemory();

    CCParser(DisplayListener listener) {
        mListener = listener;
    }

    void parse(byte[] data) {
        CCData[] ccData = CCData.fromByteArray(data);

        for (int i = 0; i < ccData.length; i++) {
            if (DEBUG) {
                Log.d(TAG, ccData[i].toString());
            }

            if (handleCtrlCode(ccData[i])
                    || handleTabOffsets(ccData[i])
                    || handlePACCode(ccData[i])
                    || handleMidRowCode(ccData[i])) {
                continue;
            }

            handleDisplayableChars(ccData[i]);
        }
    }

    interface DisplayListener {
        public void onDisplayChanged(SpannableStringBuilder[] styledTexts);
        public CaptionStyle getCaptionStyle();
    }

    private CCMemory getMemory() {
        // get the CC memory to operate on for current mode
        switch (mMode) {
        case MODE_POP_ON:
            return mNonDisplay;
        case MODE_TEXT:
            // TODO(chz): support only caption mode for now,
            // in text mode, dump everything to text mem.
            return mTextMem;
        case MODE_PAINT_ON:
        case MODE_ROLL_UP:
            return mDisplay;
        default:
            Log.w(TAG, "unrecoginized mode: " + mMode);
        }
        return mDisplay;
    }

    private boolean handleDisplayableChars(CCData ccData) {
        if (!ccData.isDisplayableChar()) {
            return false;
        }

        // Extended char includes 1 automatic backspace
        if (ccData.isExtendedChar()) {
            getMemory().bs();
        }

        getMemory().writeText(ccData.getDisplayText());

        if (mMode == MODE_PAINT_ON || mMode == MODE_ROLL_UP) {
            updateDisplay();
        }

        return true;
    }

    private boolean handleMidRowCode(CCData ccData) {
        StyleCode m = ccData.getMidRow();
        if (m != null) {
            getMemory().writeMidRowCode(m);
            return true;
        }
        return false;
    }

    private boolean handlePACCode(CCData ccData) {
        PAC pac = ccData.getPAC();

        if (pac != null) {
            if (mMode == MODE_ROLL_UP) {
                getMemory().moveBaselineTo(pac.getRow(), mRollUpSize);
            }
            getMemory().writePAC(pac);
            return true;
        }

        return false;
    }

    private boolean handleTabOffsets(CCData ccData) {
        int tabs = ccData.getTabOffset();

        if (tabs > 0) {
            getMemory().tab(tabs);
            return true;
        }

        return false;
    }

    private boolean handleCtrlCode(CCData ccData) {
        int ctrlCode = ccData.getCtrlCode();

        if (mPrevCtrlCode != INVALID && mPrevCtrlCode == ctrlCode) {
            // discard double ctrl codes (but if there's a 3rd one, we still take that)
            mPrevCtrlCode = INVALID;
            return true;
        }

        switch(ctrlCode) {
        case RCL:
            // select pop-on style
            mMode = MODE_POP_ON;
            break;
        case BS:
            getMemory().bs();
            break;
        case DER:
            getMemory().der();
            break;
        case RU2:
        case RU3:
        case RU4:
            mRollUpSize = (ctrlCode - 0x23);
            // erase memory if currently in other style
            if (mMode != MODE_ROLL_UP) {
                mDisplay.erase();
                mNonDisplay.erase();
            }
            // select roll-up style
            mMode = MODE_ROLL_UP;
            break;
        case FON:
            Log.i(TAG, "Flash On");
            break;
        case RDC:
            // select paint-on style
            mMode = MODE_PAINT_ON;
            break;
        case TR:
            mMode = MODE_TEXT;
            mTextMem.erase();
            break;
        case RTD:
            mMode = MODE_TEXT;
            break;
        case EDM:
            // erase display memory
            mDisplay.erase();
            updateDisplay();
            break;
        case CR:
            if (mMode == MODE_ROLL_UP) {
                getMemory().rollUp(mRollUpSize);
            } else {
                getMemory().cr();
            }
            if (mMode == MODE_ROLL_UP) {
                updateDisplay();
            }
            break;
        case ENM:
            // erase non-display memory
            mNonDisplay.erase();
            break;
        case EOC:
            // swap display/non-display memory
            swapMemory();
            // switch to pop-on style
            mMode = MODE_POP_ON;
            updateDisplay();
            break;
        case INVALID:
        default:
            mPrevCtrlCode = INVALID;
            return false;
        }

        mPrevCtrlCode = ctrlCode;

        // handled
        return true;
    }

    private void updateDisplay() {
        if (mListener != null) {
            CaptionStyle captionStyle = mListener.getCaptionStyle();
            mListener.onDisplayChanged(mDisplay.getStyledText(captionStyle));
        }
    }

    private void swapMemory() {
        CCMemory temp = mDisplay;
        mDisplay = mNonDisplay;
        mNonDisplay = temp;
    }

    private static class StyleCode {
        static final int COLOR_WHITE = 0;
        static final int COLOR_GREEN = 1;
        static final int COLOR_BLUE = 2;
        static final int COLOR_CYAN = 3;
        static final int COLOR_RED = 4;
        static final int COLOR_YELLOW = 5;
        static final int COLOR_MAGENTA = 6;
        static final int COLOR_INVALID = 7;

        static final int STYLE_ITALICS   = 0x00000001;
        static final int STYLE_UNDERLINE = 0x00000002;

        static final String[] mColorMap = {
            "WHITE", "GREEN", "BLUE", "CYAN", "RED", "YELLOW", "MAGENTA", "INVALID"
        };

        final int mStyle;
        final int mColor;

        static StyleCode fromByte(byte data2) {
            int style = 0;
            int color = (data2 >> 1) & 0x7;

            if ((data2 & 0x1) != 0) {
                style |= STYLE_UNDERLINE;
            }

            if (color == COLOR_INVALID) {
                // WHITE ITALICS
                color = COLOR_WHITE;
                style |= STYLE_ITALICS;
            }

            return new StyleCode(style, color);
        }

        StyleCode(int style, int color) {
            mStyle = style;
            mColor = color;
        }

        boolean isItalics() {
            return (mStyle & STYLE_ITALICS) != 0;
        }

        boolean isUnderline() {
            return (mStyle & STYLE_UNDERLINE) != 0;
        }

        int getColor() {
            return mColor;
        }

        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            str.append("{");
            str.append(mColorMap[mColor]);
            if ((mStyle & STYLE_ITALICS) != 0) {
                str.append(", ITALICS");
            }
            if ((mStyle & STYLE_UNDERLINE) != 0) {
                str.append(", UNDERLINE");
            }
            str.append("}");

            return str.toString();
        }
    }

    private static class PAC extends StyleCode {
        final int mRow;
        final int mCol;

        static PAC fromBytes(byte data1, byte data2) {
            int[] rowTable = {11, 1, 3, 12, 14, 5, 7, 9};
            int row = rowTable[data1 & 0x07] + ((data2 & 0x20) >> 5);
            int style = 0;
            if ((data2 & 1) != 0) {
                style |= STYLE_UNDERLINE;
            }
            if ((data2 & 0x10) != 0) {
                // indent code
                int indent = (data2 >> 1) & 0x7;
                return new PAC(row, indent * 4, style, COLOR_WHITE);
            } else {
                // style code
                int color = (data2 >> 1) & 0x7;

                if (color == COLOR_INVALID) {
                    // WHITE ITALICS
                    color = COLOR_WHITE;
                    style |= STYLE_ITALICS;
                }
                return new PAC(row, -1, style, color);
            }
        }

        PAC(int row, int col, int style, int color) {
            super(style, color);
            mRow = row;
            mCol = col;
        }

        boolean isIndentPAC() {
            return (mCol >= 0);
        }

        int getRow() {
            return mRow;
        }

        int getCol() {
            return mCol;
        }

        @Override
        public String toString() {
            return String.format("{%d, %d}, %s",
                    mRow, mCol, super.toString());
        }
    }

    /* CCLineBuilder keeps track of displayable chars, as well as
     * MidRow styles and PACs, for a single line of CC memory.
     *
     * It generates styled text via getStyledText() method.
     */
    private static class CCLineBuilder {
        private final StringBuilder mDisplayChars;
        private final StyleCode[] mMidRowStyles;
        private final StyleCode[] mPACStyles;

        CCLineBuilder(String str) {
            mDisplayChars = new StringBuilder(str);
            mMidRowStyles = new StyleCode[mDisplayChars.length()];
            mPACStyles = new StyleCode[mDisplayChars.length()];
        }

        void setCharAt(int index, char ch) {
            mDisplayChars.setCharAt(index, ch);
            mMidRowStyles[index] = null;
        }

        void setMidRowAt(int index, StyleCode m) {
            mDisplayChars.setCharAt(index, ' ');
            mMidRowStyles[index] = m;
        }

        void setPACAt(int index, PAC pac) {
            mPACStyles[index] = pac;
        }

        char charAt(int index) {
            return mDisplayChars.charAt(index);
        }

        int length() {
            return mDisplayChars.length();
        }

        void applyStyleSpan(
                SpannableStringBuilder styledText,
                StyleCode s, int start, int end) {
            if (s.isItalics()) {
                styledText.setSpan(
                        new StyleSpan(android.graphics.Typeface.ITALIC),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (s.isUnderline()) {
                styledText.setSpan(
                        new UnderlineSpan(),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        SpannableStringBuilder getStyledText(CaptionStyle captionStyle) {
            SpannableStringBuilder styledText = new SpannableStringBuilder(mDisplayChars);
            int start = -1, next = 0;
            int styleStart = -1;
            StyleCode curStyle = null;
            while (next < mDisplayChars.length()) {
                StyleCode newStyle = null;
                if (mMidRowStyles[next] != null) {
                    // apply mid-row style change
                    newStyle = mMidRowStyles[next];
                } else if (mPACStyles[next] != null
                    && (styleStart < 0 || start < 0)) {
                    // apply PAC style change, only if:
                    // 1. no style set, or
                    // 2. style set, but prev char is none-displayable
                    newStyle = mPACStyles[next];
                }
                if (newStyle != null) {
                    curStyle = newStyle;
                    if (styleStart >= 0 && start >= 0) {
                        applyStyleSpan(styledText, newStyle, styleStart, next);
                    }
                    styleStart = next;
                }

                if (mDisplayChars.charAt(next) != TS) {
                    if (start < 0) {
                        start = next;
                    }
                } else if (start >= 0) {
                    int expandedStart = mDisplayChars.charAt(start) == ' ' ? start : start - 1;
                    int expandedEnd = mDisplayChars.charAt(next - 1) == ' ' ? next : next + 1;
                    styledText.setSpan(
                            new MutableBackgroundColorSpan(captionStyle.backgroundColor),
                            expandedStart, expandedEnd,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (styleStart >= 0) {
                        applyStyleSpan(styledText, curStyle, styleStart, expandedEnd);
                    }
                    start = -1;
                }
                next++;
            }

            return styledText;
        }
    }

    /*
     * CCMemory models a console-style display.
     */
    private static class CCMemory {
        private final String mBlankLine;
        private final CCLineBuilder[] mLines = new CCLineBuilder[MAX_ROWS + 2];
        private int mRow;
        private int mCol;

        CCMemory() {
            char[] blank = new char[MAX_COLS + 2];
            Arrays.fill(blank, TS);
            mBlankLine = new String(blank);
        }

        void erase() {
            // erase all lines
            for (int i = 0; i < mLines.length; i++) {
                mLines[i] = null;
            }
            mRow = MAX_ROWS;
            mCol = 1;
        }

        void der() {
            if (mLines[mRow] != null) {
                for (int i = 0; i < mCol; i++) {
                    if (mLines[mRow].charAt(i) != TS) {
                        for (int j = mCol; j < mLines[mRow].length(); j++) {
                            mLines[j].setCharAt(j, TS);
                        }
                        return;
                    }
                }
                mLines[mRow] = null;
            }
        }

        void tab(int tabs) {
            moveCursorByCol(tabs);
        }

        void bs() {
            moveCursorByCol(-1);
            if (mLines[mRow] != null) {
                mLines[mRow].setCharAt(mCol, TS);
                if (mCol == MAX_COLS - 1) {
                    // Spec recommendation:
                    // if cursor was at col 32, move cursor
                    // back to col 31 and erase both col 31&32
                    mLines[mRow].setCharAt(MAX_COLS, TS);
                }
            }
        }

        void cr() {
            moveCursorTo(mRow + 1, 1);
        }

        void rollUp(int windowSize) {
            int i;
            for (i = 0; i <= mRow - windowSize; i++) {
                mLines[i] = null;
            }
            int startRow = mRow - windowSize + 1;
            if (startRow < 1) {
                startRow = 1;
            }
            for (i = startRow; i < mRow; i++) {
                mLines[i] = mLines[i + 1];
            }
            for (i = mRow; i < mLines.length; i++) {
                // clear base row
                mLines[i] = null;
            }
            // default to col 1, in case PAC is not sent
            mCol = 1;
        }

        void writeText(String text) {
            for (int i = 0; i < text.length(); i++) {
                getLineBuffer(mRow).setCharAt(mCol, text.charAt(i));
                moveCursorByCol(1);
            }
        }

        void writeMidRowCode(StyleCode m) {
            getLineBuffer(mRow).setMidRowAt(mCol, m);
            moveCursorByCol(1);
        }

        void writePAC(PAC pac) {
            if (pac.isIndentPAC()) {
                moveCursorTo(pac.getRow(), pac.getCol());
            } else {
                moveCursorTo(pac.getRow(), 1);
            }
            getLineBuffer(mRow).setPACAt(mCol, pac);
        }

        SpannableStringBuilder[] getStyledText(CaptionStyle captionStyle) {
            ArrayList<SpannableStringBuilder> rows =
                    new ArrayList<SpannableStringBuilder>(MAX_ROWS);
            for (int i = 1; i <= MAX_ROWS; i++) {
                rows.add(mLines[i] != null ?
                        mLines[i].getStyledText(captionStyle) : null);
            }
            return rows.toArray(new SpannableStringBuilder[MAX_ROWS]);
        }

        private static int clamp(int x, int min, int max) {
            return x < min ? min : (x > max ? max : x);
        }

        private void moveCursorTo(int row, int col) {
            mRow = clamp(row, 1, MAX_ROWS);
            mCol = clamp(col, 1, MAX_COLS);
        }

        private void moveCursorToRow(int row) {
            mRow = clamp(row, 1, MAX_ROWS);
        }

        private void moveCursorByCol(int col) {
            mCol = clamp(mCol + col, 1, MAX_COLS);
        }

        private void moveBaselineTo(int baseRow, int windowSize) {
            if (mRow == baseRow) {
                return;
            }
            int actualWindowSize = windowSize;
            if (baseRow < actualWindowSize) {
                actualWindowSize = baseRow;
            }
            if (mRow < actualWindowSize) {
                actualWindowSize = mRow;
            }

            int i;
            if (baseRow < mRow) {
                // copy from bottom to top row
                for (i = actualWindowSize - 1; i >= 0; i--) {
                    mLines[baseRow - i] = mLines[mRow - i];
                }
            } else {
                // copy from top to bottom row
                for (i = 0; i < actualWindowSize; i++) {
                    mLines[baseRow - i] = mLines[mRow - i];
                }
            }
            // clear rest of the rows
            for (i = 0; i <= baseRow - windowSize; i++) {
                mLines[i] = null;
            }
            for (i = baseRow + 1; i < mLines.length; i++) {
                mLines[i] = null;
            }
        }

        private CCLineBuilder getLineBuffer(int row) {
            if (mLines[row] == null) {
                mLines[row] = new CCLineBuilder(mBlankLine);
            }
            return mLines[row];
        }
    }

    /*
     * CCData parses the raw CC byte pair into displayable chars,
     * misc control codes, Mid-Row or Preamble Address Codes.
     */
    private static class CCData {
        private final byte mType;
        private final byte mData1;
        private final byte mData2;

        private static final String[] mCtrlCodeMap = {
            "RCL", "BS" , "AOF", "AON",
            "DER", "RU2", "RU3", "RU4",
            "FON", "RDC", "TR" , "RTD",
            "EDM", "CR" , "ENM", "EOC",
        };

        private static final String[] mSpecialCharMap = {
            "\u00AE",
            "\u00B0",
            "\u00BD",
            "\u00BF",
            "\u2122",
            "\u00A2",
            "\u00A3",
            "\u266A", // Eighth note
            "\u00E0",
            "\u00A0", // Transparent space
            "\u00E8",
            "\u00E2",
            "\u00EA",
            "\u00EE",
            "\u00F4",
            "\u00FB",
        };

        private static final String[] mSpanishCharMap = {
            // Spanish and misc chars
            "\u00C1", // A
            "\u00C9", // E
            "\u00D3", // I
            "\u00DA", // O
            "\u00DC", // U
            "\u00FC", // u
            "\u2018", // opening single quote
            "\u00A1", // inverted exclamation mark
            "*",
            "'",
            "\u2014", // em dash
            "\u00A9", // Copyright
            "\u2120", // Servicemark
            "\u2022", // round bullet
            "\u201C", // opening double quote
            "\u201D", // closing double quote
            // French
            "\u00C0",
            "\u00C2",
            "\u00C7",
            "\u00C8",
            "\u00CA",
            "\u00CB",
            "\u00EB",
            "\u00CE",
            "\u00CF",
            "\u00EF",
            "\u00D4",
            "\u00D9",
            "\u00F9",
            "\u00DB",
            "\u00AB",
            "\u00BB"
        };

        private static final String[] mProtugueseCharMap = {
            // Portuguese
            "\u00C3",
            "\u00E3",
            "\u00CD",
            "\u00CC",
            "\u00EC",
            "\u00D2",
            "\u00F2",
            "\u00D5",
            "\u00F5",
            "{",
            "}",
            "\\",
            "^",
            "_",
            "|",
            "~",
            // German and misc chars
            "\u00C4",
            "\u00E4",
            "\u00D6",
            "\u00F6",
            "\u00DF",
            "\u00A5",
            "\u00A4",
            "\u2502", // vertical bar
            "\u00C5",
            "\u00E5",
            "\u00D8",
            "\u00F8",
            "\u250C", // top-left corner
            "\u2510", // top-right corner
            "\u2514", // lower-left corner
            "\u2518", // lower-right corner
        };

        static CCData[] fromByteArray(byte[] data) {
            CCData[] ccData = new CCData[data.length / 3];

            for (int i = 0; i < ccData.length; i++) {
                ccData[i] = new CCData(
                        data[i * 3],
                        data[i * 3 + 1],
                        data[i * 3 + 2]);
            }

            return ccData;
        }

        CCData(byte type, byte data1, byte data2) {
            mType = type;
            mData1 = data1;
            mData2 = data2;
        }

        int getCtrlCode() {
            if ((mData1 == 0x14 || mData1 == 0x1c)
                    && mData2 >= 0x20 && mData2 <= 0x2f) {
                return mData2;
            }
            return INVALID;
        }

        StyleCode getMidRow() {
            // only support standard Mid-row codes, ignore
            // optional background/foreground mid-row codes
            if ((mData1 == 0x11 || mData1 == 0x19)
                    && mData2 >= 0x20 && mData2 <= 0x2f) {
                return StyleCode.fromByte(mData2);
            }
            return null;
        }

        PAC getPAC() {
            if ((mData1 & 0x70) == 0x10
                    && (mData2 & 0x40) == 0x40
                    && ((mData1 & 0x07) != 0 || (mData2 & 0x20) == 0)) {
                return PAC.fromBytes(mData1, mData2);
            }
            return null;
        }

        int getTabOffset() {
            if ((mData1 == 0x17 || mData1 == 0x1f)
                    && mData2 >= 0x21 && mData2 <= 0x23) {
                return mData2 & 0x3;
            }
            return 0;
        }

        boolean isDisplayableChar() {
            return isBasicChar() || isSpecialChar() || isExtendedChar();
        }

        String getDisplayText() {
            String str = getBasicChars();

            if (str == null) {
                str =  getSpecialChar();

                if (str == null) {
                    str = getExtendedChar();
                }
            }

            return str;
        }

        private String ctrlCodeToString(int ctrlCode) {
            return mCtrlCodeMap[ctrlCode - 0x20];
        }

        private boolean isBasicChar() {
            return mData1 >= 0x20 && mData1 <= 0x7f;
        }

        private boolean isSpecialChar() {
            return ((mData1 == 0x11 || mData1 == 0x19)
                    && mData2 >= 0x30 && mData2 <= 0x3f);
        }

        private boolean isExtendedChar() {
            return ((mData1 == 0x12 || mData1 == 0x1A
                    || mData1 == 0x13 || mData1 == 0x1B)
                    && mData2 >= 0x20 && mData2 <= 0x3f);
        }

        private char getBasicChar(byte data) {
            char c;
            // replace the non-ASCII ones
            switch (data) {
                case 0x2A: c = '\u00E1'; break;
                case 0x5C: c = '\u00E9'; break;
                case 0x5E: c = '\u00ED'; break;
                case 0x5F: c = '\u00F3'; break;
                case 0x60: c = '\u00FA'; break;
                case 0x7B: c = '\u00E7'; break;
                case 0x7C: c = '\u00F7'; break;
                case 0x7D: c = '\u00D1'; break;
                case 0x7E: c = '\u00F1'; break;
                case 0x7F: c = '\u2588'; break; // Full block
                default: c = (char) data; break;
            }
            return c;
        }

        private String getBasicChars() {
            if (mData1 >= 0x20 && mData1 <= 0x7f) {
                StringBuilder builder = new StringBuilder(2);
                builder.append(getBasicChar(mData1));
                if (mData2 >= 0x20 && mData2 <= 0x7f) {
                    builder.append(getBasicChar(mData2));
                }
                return builder.toString();
            }

            return null;
        }

        private String getSpecialChar() {
            if ((mData1 == 0x11 || mData1 == 0x19)
                    && mData2 >= 0x30 && mData2 <= 0x3f) {
                return mSpecialCharMap[mData2 - 0x30];
            }

            return null;
        }

        private String getExtendedChar() {
            if ((mData1 == 0x12 || mData1 == 0x1A)
                    && mData2 >= 0x20 && mData2 <= 0x3f){
                // 1 Spanish/French char
                return mSpanishCharMap[mData2 - 0x20];
            } else if ((mData1 == 0x13 || mData1 == 0x1B)
                    && mData2 >= 0x20 && mData2 <= 0x3f){
                // 1 Portuguese/German/Danish char
                return mProtugueseCharMap[mData2 - 0x20];
            }

            return null;
        }

        @Override
        public String toString() {
            String str;

            if (mData1 < 0x10 && mData2 < 0x10) {
                // Null Pad, ignore
                return String.format("[%d]Null: %02x %02x", mType, mData1, mData2);
            }

            int ctrlCode = getCtrlCode();
            if (ctrlCode != INVALID) {
                return String.format("[%d]%s", mType, ctrlCodeToString(ctrlCode));
            }

            int tabOffset = getTabOffset();
            if (tabOffset > 0) {
                return String.format("[%d]Tab%d", mType, tabOffset);
            }

            PAC pac = getPAC();
            if (pac != null) {
                return String.format("[%d]PAC: %s", mType, pac.toString());
            }

            StyleCode m = getMidRow();
            if (m != null) {
                return String.format("[%d]Mid-row: %s", mType, m.toString());
            }

            if (isDisplayableChar()) {
                return String.format("[%d]Displayable: %s (%02x %02x)",
                        mType, getDisplayText(), mData1, mData2);
            }

            return String.format("[%d]Invalid: %02x %02x", mType, mData1, mData2);
        }
    }
}

/**
 * Mutable version of BackgroundSpan to facilitate text rendering with edge
 * styles.
 *
 * @hide
 */
class MutableBackgroundColorSpan extends CharacterStyle implements UpdateAppearance {
    private int mColor;

    public MutableBackgroundColorSpan(int color) {
        mColor = color;
    }

    public void setBackgroundColor(int color) {
        mColor = color;
    }

    public int getBackgroundColor() {
        return mColor;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        ds.bgColor = mColor;
    }
}

/**
 * Widget capable of rendering CEA-608 closed captions.
 *
 * @hide
 */
class ClosedCaptionWidget extends ViewGroup implements
        SubtitleTrack.RenderingWidget,
        CCParser.DisplayListener {
    private static final String TAG = "ClosedCaptionWidget";

    private static final Rect mTextBounds = new Rect();
    private static final String mDummyText = "1234567890123456789012345678901234";
    private static final CaptionStyle DEFAULT_CAPTION_STYLE = CaptionStyle.DEFAULT;

    /** Captioning manager, used to obtain and track caption properties. */
    private final CaptioningManager mManager;

    /** Callback for rendering changes. */
    private OnChangedListener mListener;

    /** Current caption style. */
    private CaptionStyle mCaptionStyle;

    /* Closed caption layout. */
    private CCLayout mClosedCaptionLayout;

    /** Whether a caption style change listener is registered. */
    private boolean mHasChangeListener;

    public ClosedCaptionWidget(Context context) {
        this(context, null);
    }

    public ClosedCaptionWidget(Context context, AttributeSet attrs) {
        this(context, null, 0);
    }

    public ClosedCaptionWidget(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        // Cannot render text over video when layer type is hardware.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        mManager = (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
        mCaptionStyle = DEFAULT_CAPTION_STYLE.applyStyle(mManager.getUserStyle());

        mClosedCaptionLayout = new CCLayout(context);
        mClosedCaptionLayout.setCaptionStyle(mCaptionStyle);
        addView(mClosedCaptionLayout, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        requestLayout();
    }

    @Override
    public void setOnChangedListener(OnChangedListener listener) {
        mListener = listener;
    }

    @Override
    public void setSize(int width, int height) {
        final int widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        final int heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);

        measure(widthSpec, heightSpec);
        layout(0, 0, width, height);
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) {
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }

        manageChangeListener();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        manageChangeListener();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        manageChangeListener();
    }

    @Override
    public void onDisplayChanged(SpannableStringBuilder[] styledTexts) {
        mClosedCaptionLayout.update(styledTexts);

        if (mListener != null) {
            mListener.onChanged(this);
        }
    }

    @Override
    public CaptionStyle getCaptionStyle() {
        return mCaptionStyle;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mClosedCaptionLayout.measure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mClosedCaptionLayout.layout(l, t, r, b);
    }

    /**
     * Manages whether this renderer is listening for caption style changes.
     */
    private final CaptioningChangeListener mCaptioningListener = new CaptioningChangeListener() {
        @Override
        public void onUserStyleChanged(CaptionStyle userStyle) {
            mCaptionStyle = DEFAULT_CAPTION_STYLE.applyStyle(userStyle);
            mClosedCaptionLayout.setCaptionStyle(mCaptionStyle);
        }
    };

    private void manageChangeListener() {
        final boolean needsListener = isAttachedToWindow() && getVisibility() == View.VISIBLE;
        if (mHasChangeListener != needsListener) {
            mHasChangeListener = needsListener;

            if (needsListener) {
                mManager.addCaptioningChangeListener(mCaptioningListener);
            } else {
                mManager.removeCaptioningChangeListener(mCaptioningListener);
            }
        }
    }

    private static class CCLineBox extends TextView {
        private static final float FONT_PADDING_RATIO = 0.75f;
        private static final float EDGE_OUTLINE_RATIO = 0.1f;
        private static final float EDGE_SHADOW_RATIO = 0.05f;
        private float mOutlineWidth;
        private float mShadowRadius;
        private float mShadowOffset;

        private int mTextColor = Color.WHITE;
        private int mBgColor = Color.BLACK;
        private int mEdgeType = CaptionStyle.EDGE_TYPE_NONE;
        private int mEdgeColor = Color.TRANSPARENT;

        CCLineBox(Context context) {
            super(context);
            setGravity(Gravity.CENTER);
            setBackgroundColor(Color.TRANSPARENT);
            setTextColor(Color.WHITE);
            setTypeface(Typeface.MONOSPACE);
            setVisibility(View.INVISIBLE);

            final Resources res = getContext().getResources();

            // get the default (will be updated later during measure)
            mOutlineWidth = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.subtitle_outline_width);
            mShadowRadius = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.subtitle_shadow_radius);
            mShadowOffset = res.getDimensionPixelSize(
                    com.android.internal.R.dimen.subtitle_shadow_offset);
        }

        void setCaptionStyle(CaptionStyle captionStyle) {
            mTextColor = captionStyle.foregroundColor;
            mBgColor = captionStyle.backgroundColor;
            mEdgeType = captionStyle.edgeType;
            mEdgeColor = captionStyle.edgeColor;

            setTextColor(mTextColor);
            if (mEdgeType == CaptionStyle.EDGE_TYPE_DROP_SHADOW) {
                setShadowLayer(mShadowRadius, mShadowOffset, mShadowOffset, mEdgeColor);
            } else {
                setShadowLayer(0, 0, 0, 0);
            }
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float fontSize = MeasureSpec.getSize(heightMeasureSpec)
                    * FONT_PADDING_RATIO;
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);

            mOutlineWidth = EDGE_OUTLINE_RATIO * fontSize + 1.0f;
            mShadowRadius = EDGE_SHADOW_RATIO * fontSize + 1.0f;;
            mShadowOffset = mShadowRadius;

            // set font scale in the X direction to match the required width
            setScaleX(1.0f);
            getPaint().getTextBounds(mDummyText, 0, mDummyText.length(), mTextBounds);
            float actualTextWidth = mTextBounds.width();
            float requiredTextWidth = MeasureSpec.getSize(widthMeasureSpec);
            setScaleX(requiredTextWidth / actualTextWidth);

            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mEdgeType == CaptionStyle.EDGE_TYPE_UNSPECIFIED
                    || mEdgeType == CaptionStyle.EDGE_TYPE_NONE
                    || mEdgeType == CaptionStyle.EDGE_TYPE_DROP_SHADOW) {
                // these edge styles don't require a second pass
                super.onDraw(c);
                return;
            }

            if (mEdgeType == CaptionStyle.EDGE_TYPE_OUTLINE) {
                drawEdgeOutline(c);
            } else {
                // Raised or depressed
                drawEdgeRaisedOrDepressed(c);
            }
        }

        private void drawEdgeOutline(Canvas c) {
            TextPaint textPaint = getPaint();

            Paint.Style previousStyle = textPaint.getStyle();
            Paint.Join previousJoin = textPaint.getStrokeJoin();
            float previousWidth = textPaint.getStrokeWidth();

            setTextColor(mEdgeColor);
            textPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            textPaint.setStrokeJoin(Paint.Join.ROUND);
            textPaint.setStrokeWidth(mOutlineWidth);

            // Draw outline and background only.
            super.onDraw(c);

            // Restore original settings.
            setTextColor(mTextColor);
            textPaint.setStyle(previousStyle);
            textPaint.setStrokeJoin(previousJoin);
            textPaint.setStrokeWidth(previousWidth);

            // Remove the background.
            setBackgroundSpans(Color.TRANSPARENT);
            // Draw foreground only.
            super.onDraw(c);
            // Restore the background.
            setBackgroundSpans(mBgColor);
        }

        private void drawEdgeRaisedOrDepressed(Canvas c) {
            TextPaint textPaint = getPaint();

            Paint.Style previousStyle = textPaint.getStyle();
            textPaint.setStyle(Paint.Style.FILL);

            final boolean raised = mEdgeType == CaptionStyle.EDGE_TYPE_RAISED;
            final int colorUp = raised ? Color.WHITE : mEdgeColor;
            final int colorDown = raised ? mEdgeColor : Color.WHITE;
            final float offset = mShadowRadius / 2f;

            // Draw background and text with shadow up
            setShadowLayer(mShadowRadius, -offset, -offset, colorUp);
            super.onDraw(c);

            // Remove the background.
            setBackgroundSpans(Color.TRANSPARENT);

            // Draw text with shadow down
            setShadowLayer(mShadowRadius, +offset, +offset, colorDown);
            super.onDraw(c);

            // Restore settings
            textPaint.setStyle(previousStyle);

            // Restore the background.
            setBackgroundSpans(mBgColor);
        }

        private void setBackgroundSpans(int color) {
            CharSequence text = getText();
            if (text instanceof Spannable) {
                Spannable spannable = (Spannable) text;
                MutableBackgroundColorSpan[] bgSpans = spannable.getSpans(
                        0, spannable.length(), MutableBackgroundColorSpan.class);
                for (int i = 0; i < bgSpans.length; i++) {
                    bgSpans[i].setBackgroundColor(color);
                }
            }
        }
    }

    private static class CCLayout extends LinearLayout {
        private static final int MAX_ROWS = CCParser.MAX_ROWS;
        private static final float SAFE_AREA_RATIO = 0.9f;

        private final CCLineBox[] mLineBoxes = new CCLineBox[MAX_ROWS];

        CCLayout(Context context) {
            super(context);
            setGravity(Gravity.START);
            setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < MAX_ROWS; i++) {
                mLineBoxes[i] = new CCLineBox(getContext());
                addView(mLineBoxes[i], LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            }
        }

        void setCaptionStyle(CaptionStyle captionStyle) {
            for (int i = 0; i < MAX_ROWS; i++) {
                mLineBoxes[i].setCaptionStyle(captionStyle);
            }
        }

        void update(SpannableStringBuilder[] textBuffer) {
            for (int i = 0; i < MAX_ROWS; i++) {
                if (textBuffer[i] != null) {
                    mLineBoxes[i].setText(textBuffer[i], TextView.BufferType.SPANNABLE);
                    mLineBoxes[i].setVisibility(View.VISIBLE);
                } else {
                    mLineBoxes[i].setVisibility(View.INVISIBLE);
                }
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);

            int safeWidth = getMeasuredWidth();
            int safeHeight = getMeasuredHeight();

            // CEA-608 assumes 4:3 video
            if (safeWidth * 3 >= safeHeight * 4) {
                safeWidth = safeHeight * 4 / 3;
            } else {
                safeHeight = safeWidth * 3 / 4;
            }
            safeWidth *= SAFE_AREA_RATIO;
            safeHeight *= SAFE_AREA_RATIO;

            int lineHeight = safeHeight / MAX_ROWS;
            int lineHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                    lineHeight, MeasureSpec.EXACTLY);
            int lineWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                    safeWidth, MeasureSpec.EXACTLY);

            for (int i = 0; i < MAX_ROWS; i++) {
                mLineBoxes[i].measure(lineWidthMeasureSpec, lineHeightMeasureSpec);
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // safe caption area
            int viewPortWidth = r - l;
            int viewPortHeight = b - t;
            int safeWidth, safeHeight;
            // CEA-608 assumes 4:3 video
            if (viewPortWidth * 3 >= viewPortHeight * 4) {
                safeWidth = viewPortHeight * 4 / 3;
                safeHeight = viewPortHeight;
            } else {
                safeWidth = viewPortWidth;
                safeHeight = viewPortWidth * 3 / 4;
            }
            safeWidth *= SAFE_AREA_RATIO;
            safeHeight *= SAFE_AREA_RATIO;
            int left = (viewPortWidth - safeWidth) / 2;
            int top = (viewPortHeight - safeHeight) / 2;

            for (int i = 0; i < MAX_ROWS; i++) {
                mLineBoxes[i].layout(
                        left,
                        top + safeHeight * i / MAX_ROWS,
                        left + safeWidth,
                        top + safeHeight * (i + 1) / MAX_ROWS);
            }
        }
    }
};
