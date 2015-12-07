/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Message;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.text.Layout.Alignment;
import android.util.Log;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;

import com.android.internal.widget.SubtitleView;

/** @hide */
public class Cea708CaptionRenderer extends SubtitleController.Renderer {
    private final Context mContext;
    private Cea708CCWidget mCCWidget;

    public Cea708CaptionRenderer(Context context) {
        mContext = context;
    }

    @Override
    public boolean supports(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_MIME)) {
            String mimeType = format.getString(MediaFormat.KEY_MIME);
            return MediaPlayer.MEDIA_MIMETYPE_TEXT_CEA_708.equals(mimeType);
        }
        return false;
    }

    @Override
    public SubtitleTrack createTrack(MediaFormat format) {
        String mimeType = format.getString(MediaFormat.KEY_MIME);
        if (MediaPlayer.MEDIA_MIMETYPE_TEXT_CEA_708.equals(mimeType)) {
            if (mCCWidget == null) {
                mCCWidget = new Cea708CCWidget(mContext);
            }
            return new Cea708CaptionTrack(mCCWidget, format);
        }
        throw new RuntimeException("No matching format: " + format.toString());
    }
}

/** @hide */
class Cea708CaptionTrack extends SubtitleTrack {
    private final Cea708CCParser mCCParser;
    private final Cea708CCWidget mRenderingWidget;

    Cea708CaptionTrack(Cea708CCWidget renderingWidget, MediaFormat format) {
        super(format);

        mRenderingWidget = renderingWidget;
        mCCParser = new Cea708CCParser(mRenderingWidget);
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
 * A class for parsing CEA-708, which is the standard for closed captioning for ATSC DTV.
 *
 * <p>ATSC DTV closed caption data are carried on picture user data of video streams.
 * This class starts to parse from picture user data payload, so extraction process of user_data
 * from video streams is up to outside of this code.
 *
 * <p>There are 4 steps to decode user_data to provide closed caption services. Step 1 and 2 are
 * done in NuPlayer and libstagefright.
 *
 * <h3>Step 1. user_data -&gt; CcPacket</h3>
 *
 * <p>First, user_data consists of cc_data packets, which are 3-byte segments. Here, CcPacket is a
 * collection of cc_data packets in a frame along with same presentation timestamp. Because cc_data
 * packets must be reassembled in the frame display order, CcPackets are reordered.
 *
 * <h3>Step 2. CcPacket -&gt; DTVCC packet</h3>
 *
 * <p>Each cc_data packet has a one byte for declaring a type of itself and data validity, and the
 * subsequent two bytes for input data of a DTVCC packet. There are 4 types for cc_data packet.
 * We're interested in DTVCC_PACKET_START(type 3) and DTVCC_PACKET_DATA(type 2). Each DTVCC packet
 * begins with DTVCC_PACKET_START(type 3) and the following cc_data packets which has
 * DTVCC_PACKET_DATA(type 2) are appended into the DTVCC packet being assembled.
 *
 * <h3>Step 3. DTVCC packet -&gt; Service Blocks</h3>
 *
 * <p>A DTVCC packet consists of multiple service blocks. Each service block represents a caption
 * track and has a service number, which ranges from 1 to 63, that denotes caption track identity.
 * In here, we listen at most one chosen caption track by service number. Otherwise, just skip the
 * other service blocks.
 *
 * <h3>Step 4. Interpreting Service Block Data ({@link #parseServiceBlockData}, {@code parseXX},
 * and {@link #parseExt1} methods)</h3>
 *
 * <p>Service block data is actual caption stream. it looks similar to telnet. It uses most parts of
 * ASCII table and consists of specially defined commands and some ASCII control codes which work
 * in a behavior slightly different from their original purpose. ASCII control codes and caption
 * commands are explicit instructions that control the state of a closed caption service and the
 * other ASCII and text codes are implicit instructions that send their characters to buffer.
 *
 * <p>There are 4 main code groups and 4 extended code groups. Both the range of code groups are the
 * same as the range of a byte.
 *
 * <p>4 main code groups: C0, C1, G0, G1
 * <br>4 extended code groups: C2, C3, G2, G3
 *
 * <p>Each code group has its own handle method. For example, {@link #parseC0} handles C0 code group
 * and so on. And {@link #parseServiceBlockData} method maps a stream on the main code groups while
 * {@link #parseExt1} method maps on the extended code groups.
 *
 * <p>The main code groups:
 * <ul>
 * <li>C0 - contains modified ASCII control codes. It is not intended by CEA-708 but Korea TTA
 *      standard for ATSC CC uses P16 character heavily, which is unclear entity in CEA-708 doc,
 *      even for the alphanumeric characters instead of ASCII characters.</li>
 * <li>C1 - contains the caption commands. There are 3 categories of a caption command.</li>
 * <ul>
 * <li>Window commands: The window commands control a caption window which is addressable area being
 *                  with in the Safe title area. (CWX, CLW, DSW, HDW, TGW, DLW, SWA, DFX)</li>
 * <li>Pen commands: Th pen commands control text style and location. (SPA, SPC, SPL)</li>
 * <li>Job commands: The job commands make a delay and recover from the delay. (DLY, DLC, RST)</li>
 * </ul>
 * <li>G0 - same as printable ASCII character set except music note character.</li>
 * <li>G1 - same as ISO 8859-1 Latin 1 character set.</li>
 * </ul>
 * <p>Most of the extended code groups are being skipped.
 *
 */
class Cea708CCParser {
    private static final String TAG = "Cea708CCParser";
    private static final boolean DEBUG = false;

    private static final String MUSIC_NOTE_CHAR = new String(
            "\u266B".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

    private final StringBuffer mBuffer = new StringBuffer();
    private int mCommand = 0;

    // Assign a dummy listener in order to avoid null checks.
    private DisplayListener mListener = new DisplayListener() {
        @Override
        public void emitEvent(CaptionEvent event) {
            // do nothing
        }
    };

    /**
     * {@link Cea708Parser} emits caption event of three different types.
     * {@link DisplayListener#emitEvent} is invoked with the parameter
     * {@link CaptionEvent} to pass all the results to an observer of the decoding process .
     *
     * <p>{@link CaptionEvent#type} determines the type of the result and
     * {@link CaptionEvent#obj} contains the output value of a caption event.
     * The observer must do the casting to the corresponding type.
     *
     * <ul><li>{@code CAPTION_EMIT_TYPE_BUFFER}: Passes a caption text buffer to a observer.
     * {@code obj} must be of {@link String}.</li>
     *
     * <li>{@code CAPTION_EMIT_TYPE_CONTROL}: Passes a caption character control code to a observer.
     * {@code obj} must be of {@link Character}.</li>
     *
     * <li>{@code CAPTION_EMIT_TYPE_CLEAR_COMMAND}: Passes a clear command to a observer.
     * {@code obj} must be {@code NULL}.</li></ul>
     */
    public static final int CAPTION_EMIT_TYPE_BUFFER = 1;
    public static final int CAPTION_EMIT_TYPE_CONTROL = 2;
    public static final int CAPTION_EMIT_TYPE_COMMAND_CWX = 3;
    public static final int CAPTION_EMIT_TYPE_COMMAND_CLW = 4;
    public static final int CAPTION_EMIT_TYPE_COMMAND_DSW = 5;
    public static final int CAPTION_EMIT_TYPE_COMMAND_HDW = 6;
    public static final int CAPTION_EMIT_TYPE_COMMAND_TGW = 7;
    public static final int CAPTION_EMIT_TYPE_COMMAND_DLW = 8;
    public static final int CAPTION_EMIT_TYPE_COMMAND_DLY = 9;
    public static final int CAPTION_EMIT_TYPE_COMMAND_DLC = 10;
    public static final int CAPTION_EMIT_TYPE_COMMAND_RST = 11;
    public static final int CAPTION_EMIT_TYPE_COMMAND_SPA = 12;
    public static final int CAPTION_EMIT_TYPE_COMMAND_SPC = 13;
    public static final int CAPTION_EMIT_TYPE_COMMAND_SPL = 14;
    public static final int CAPTION_EMIT_TYPE_COMMAND_SWA = 15;
    public static final int CAPTION_EMIT_TYPE_COMMAND_DFX = 16;

    Cea708CCParser(DisplayListener listener) {
        if (listener != null) {
            mListener = listener;
        }
    }

    interface DisplayListener {
        void emitEvent(CaptionEvent event);
    }

    private void emitCaptionEvent(CaptionEvent captionEvent) {
        // Emit the existing string buffer before a new event is arrived.
        emitCaptionBuffer();
        mListener.emitEvent(captionEvent);
    }

    private void emitCaptionBuffer() {
        if (mBuffer.length() > 0) {
            mListener.emitEvent(new CaptionEvent(CAPTION_EMIT_TYPE_BUFFER, mBuffer.toString()));
            mBuffer.setLength(0);
        }
    }

    // Step 3. DTVCC packet -> Service Blocks (parseDtvCcPacket method)
    public void parse(byte[] data) {
        // From this point, starts to read DTVCC coding layer.
        // First, identify code groups, which is defined in CEA-708B Section 7.1.
        int pos = 0;
        while (pos < data.length) {
            pos = parseServiceBlockData(data, pos);
        }

        // Emit the buffer after reading codes.
        emitCaptionBuffer();
    }

    // Step 4. Main code groups
    private int parseServiceBlockData(byte[] data, int pos) {
        // For the details of the ranges of DTVCC code groups, see CEA-708B Table 6.
        mCommand = data[pos] & 0xff;
        ++pos;
        if (mCommand == Const.CODE_C0_EXT1) {
            if (DEBUG) {
                Log.d(TAG, String.format("parseServiceBlockData EXT1 %x", mCommand));
            }
            pos = parseExt1(data, pos);
        } else if (mCommand >= Const.CODE_C0_RANGE_START
                && mCommand <= Const.CODE_C0_RANGE_END) {
            if (DEBUG) {
                Log.d(TAG, String.format("parseServiceBlockData C0 %x", mCommand));
            }
            pos = parseC0(data, pos);
        } else if (mCommand >= Const.CODE_C1_RANGE_START
                && mCommand <= Const.CODE_C1_RANGE_END) {
            if (DEBUG) {
                Log.d(TAG, String.format("parseServiceBlockData C1 %x", mCommand));
            }
            pos = parseC1(data, pos);
        } else if (mCommand >= Const.CODE_G0_RANGE_START
                && mCommand <= Const.CODE_G0_RANGE_END) {
            if (DEBUG) {
                Log.d(TAG, String.format("parseServiceBlockData G0 %x", mCommand));
            }
            pos = parseG0(data, pos);
        } else if (mCommand >= Const.CODE_G1_RANGE_START
                && mCommand <= Const.CODE_G1_RANGE_END) {
            if (DEBUG) {
                Log.d(TAG, String.format("parseServiceBlockData G1 %x", mCommand));
            }
            pos = parseG1(data, pos);
        }
        return pos;
    }

    private int parseC0(byte[] data, int pos) {
        // For the details of C0 code group, see CEA-708B Section 7.4.1.
        // CL Group: C0 Subset of ASCII Control codes
        if (mCommand >= Const.CODE_C0_SKIP2_RANGE_START
                && mCommand <= Const.CODE_C0_SKIP2_RANGE_END) {
            if (mCommand == Const.CODE_C0_P16) {
                // P16 escapes next two bytes for the large character maps.(no standard rule)
                // For Korea broadcasting, express whole letters by using this.
                try {
                    if (data[pos] == 0) {
                        mBuffer.append((char) data[pos + 1]);
                    } else {
                        String value = new String(Arrays.copyOfRange(data, pos, pos + 2), "EUC-KR");
                        mBuffer.append(value);
                    }
                } catch (UnsupportedEncodingException e) {
                    Log.e(TAG, "P16 Code - Could not find supported encoding", e);
                }
            }
            pos += 2;
        } else if (mCommand >= Const.CODE_C0_SKIP1_RANGE_START
                && mCommand <= Const.CODE_C0_SKIP1_RANGE_END) {
            ++pos;
        } else {
            // NUL, BS, FF, CR interpreted as they are in ASCII control codes.
            // HCR moves the pen location to th beginning of the current line and deletes contents.
            // FF clears the screen and moves the pen location to (0,0).
            // ETX is the NULL command which is used to flush text to the current window when no
            // other command is pending.
            switch (mCommand) {
                case Const.CODE_C0_NUL:
                    break;
                case Const.CODE_C0_ETX:
                    emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_CONTROL, (char) mCommand));
                    break;
                case Const.CODE_C0_BS:
                    emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_CONTROL, (char) mCommand));
                    break;
                case Const.CODE_C0_FF:
                    emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_CONTROL, (char) mCommand));
                    break;
                case Const.CODE_C0_CR:
                    mBuffer.append('\n');
                    break;
                case Const.CODE_C0_HCR:
                    emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_CONTROL, (char) mCommand));
                    break;
                default:
                    break;
            }
        }
        return pos;
    }

    private int parseC1(byte[] data, int pos) {
        // For the details of C1 code group, see CEA-708B Section 8.10.
        // CR Group: C1 Caption Control Codes
        switch (mCommand) {
            case Const.CODE_C1_CW0:
            case Const.CODE_C1_CW1:
            case Const.CODE_C1_CW2:
            case Const.CODE_C1_CW3:
            case Const.CODE_C1_CW4:
            case Const.CODE_C1_CW5:
            case Const.CODE_C1_CW6:
            case Const.CODE_C1_CW7: {
                // SetCurrentWindow0-7
                int windowId = mCommand - Const.CODE_C1_CW0;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_CWX, windowId));
                if (DEBUG) {
                    Log.d(TAG, String.format("CaptionCommand CWX windowId: %d", windowId));
                }
                break;
            }

            case Const.CODE_C1_CLW: {
                // ClearWindows
                int windowBitmap = data[pos] & 0xff;
                ++pos;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_CLW, windowBitmap));
                if (DEBUG) {
                    Log.d(TAG, String.format("CaptionCommand CLW windowBitmap: %d", windowBitmap));
                }
                break;
            }

            case Const.CODE_C1_DSW: {
                // DisplayWindows
                int windowBitmap = data[pos] & 0xff;
                ++pos;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_DSW, windowBitmap));
                if (DEBUG) {
                    Log.d(TAG, String.format("CaptionCommand DSW windowBitmap: %d", windowBitmap));
                }
                break;
            }

            case Const.CODE_C1_HDW: {
                // HideWindows
                int windowBitmap = data[pos] & 0xff;
                ++pos;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_HDW, windowBitmap));
                if (DEBUG) {
                    Log.d(TAG, String.format("CaptionCommand HDW windowBitmap: %d", windowBitmap));
                }
                break;
            }

            case Const.CODE_C1_TGW: {
                // ToggleWindows
                int windowBitmap = data[pos] & 0xff;
                ++pos;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_TGW, windowBitmap));
                if (DEBUG) {
                    Log.d(TAG, String.format("CaptionCommand TGW windowBitmap: %d", windowBitmap));
                }
                break;
            }

            case Const.CODE_C1_DLW: {
                // DeleteWindows
                int windowBitmap = data[pos] & 0xff;
                ++pos;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_DLW, windowBitmap));
                if (DEBUG) {
                    Log.d(TAG, String.format("CaptionCommand DLW windowBitmap: %d", windowBitmap));
                }
                break;
            }

            case Const.CODE_C1_DLY: {
                // Delay
                int tenthsOfSeconds = data[pos] & 0xff;
                ++pos;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_DLY, tenthsOfSeconds));
                if (DEBUG) {
                    Log.d(TAG, String.format("CaptionCommand DLY %d tenths of seconds",
                            tenthsOfSeconds));
                }
                break;
            }
            case Const.CODE_C1_DLC: {
                // DelayCancel
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_DLC, null));
                if (DEBUG) {
                    Log.d(TAG, "CaptionCommand DLC");
                }
                break;
            }

            case Const.CODE_C1_RST: {
                // Reset
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_RST, null));
                if (DEBUG) {
                    Log.d(TAG, "CaptionCommand RST");
                }
                break;
            }

            case Const.CODE_C1_SPA: {
                // SetPenAttributes
                int textTag = (data[pos] & 0xf0) >> 4;
                int penSize = data[pos] & 0x03;
                int penOffset = (data[pos] & 0x0c) >> 2;
                boolean italic = (data[pos + 1] & 0x80) != 0;
                boolean underline = (data[pos + 1] & 0x40) != 0;
                int edgeType = (data[pos + 1] & 0x38) >> 3;
                int fontTag = data[pos + 1] & 0x7;
                pos += 2;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_SPA,
                        new CaptionPenAttr(penSize, penOffset, textTag, fontTag, edgeType,
                                underline, italic)));
                if (DEBUG) {
                    Log.d(TAG, String.format(
                            "CaptionCommand SPA penSize: %d, penOffset: %d, textTag: %d, "
                                    + "fontTag: %d, edgeType: %d, underline: %s, italic: %s",
                            penSize, penOffset, textTag, fontTag, edgeType, underline, italic));
                }
                break;
            }

            case Const.CODE_C1_SPC: {
                // SetPenColor
                int opacity = (data[pos] & 0xc0) >> 6;
                int red = (data[pos] & 0x30) >> 4;
                int green = (data[pos] & 0x0c) >> 2;
                int blue = data[pos] & 0x03;
                CaptionColor foregroundColor = new CaptionColor(opacity, red, green, blue);
                ++pos;
                opacity = (data[pos] & 0xc0) >> 6;
                red = (data[pos] & 0x30) >> 4;
                green = (data[pos] & 0x0c) >> 2;
                blue = data[pos] & 0x03;
                CaptionColor backgroundColor = new CaptionColor(opacity, red, green, blue);
                ++pos;
                red = (data[pos] & 0x30) >> 4;
                green = (data[pos] & 0x0c) >> 2;
                blue = data[pos] & 0x03;
                CaptionColor edgeColor = new CaptionColor(
                        CaptionColor.OPACITY_SOLID, red, green, blue);
                ++pos;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_SPC,
                        new CaptionPenColor(foregroundColor, backgroundColor, edgeColor)));
                if (DEBUG) {
                    Log.d(TAG, String.format(
                            "CaptionCommand SPC foregroundColor %s backgroundColor %s edgeColor %s",
                            foregroundColor, backgroundColor, edgeColor));
                }
                break;
            }

            case Const.CODE_C1_SPL: {
                // SetPenLocation
                // column is normally 0-31 for 4:3 formats, and 0-41 for 16:9 formats
                int row = data[pos] & 0x0f;
                int column = data[pos + 1] & 0x3f;
                pos += 2;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_SPL,
                        new CaptionPenLocation(row, column)));
                if (DEBUG) {
                    Log.d(TAG, String.format("CaptionCommand SPL row: %d, column: %d",
                            row, column));
                }
                break;
            }

            case Const.CODE_C1_SWA: {
                // SetWindowAttributes
                int opacity = (data[pos] & 0xc0) >> 6;
                int red = (data[pos] & 0x30) >> 4;
                int green = (data[pos] & 0x0c) >> 2;
                int blue = data[pos] & 0x03;
                CaptionColor fillColor = new CaptionColor(opacity, red, green, blue);
                int borderType = (data[pos + 1] & 0xc0) >> 6 | (data[pos + 2] & 0x80) >> 5;
                red = (data[pos + 1] & 0x30) >> 4;
                green = (data[pos + 1] & 0x0c) >> 2;
                blue = data[pos + 1] & 0x03;
                CaptionColor borderColor = new CaptionColor(
                        CaptionColor.OPACITY_SOLID, red, green, blue);
                boolean wordWrap = (data[pos + 2] & 0x40) != 0;
                int printDirection = (data[pos + 2] & 0x30) >> 4;
                int scrollDirection = (data[pos + 2] & 0x0c) >> 2;
                int justify = (data[pos + 2] & 0x03);
                int effectSpeed = (data[pos + 3] & 0xf0) >> 4;
                int effectDirection = (data[pos + 3] & 0x0c) >> 2;
                int displayEffect = data[pos + 3] & 0x3;
                pos += 4;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_SWA,
                        new CaptionWindowAttr(fillColor, borderColor, borderType, wordWrap,
                                printDirection, scrollDirection, justify,
                                effectDirection, effectSpeed, displayEffect)));
                if (DEBUG) {
                    Log.d(TAG, String.format(
                            "CaptionCommand SWA fillColor: %s, borderColor: %s, borderType: %d"
                                    + "wordWrap: %s, printDirection: %d, scrollDirection: %d, "
                                    + "justify: %s, effectDirection: %d, effectSpeed: %d, "
                                    + "displayEffect: %d",
                            fillColor, borderColor, borderType, wordWrap, printDirection,
                            scrollDirection, justify, effectDirection, effectSpeed, displayEffect));
                }
                break;
            }

            case Const.CODE_C1_DF0:
            case Const.CODE_C1_DF1:
            case Const.CODE_C1_DF2:
            case Const.CODE_C1_DF3:
            case Const.CODE_C1_DF4:
            case Const.CODE_C1_DF5:
            case Const.CODE_C1_DF6:
            case Const.CODE_C1_DF7: {
                // DefineWindow0-7
                int windowId = mCommand - Const.CODE_C1_DF0;
                boolean visible = (data[pos] & 0x20) != 0;
                boolean rowLock = (data[pos] & 0x10) != 0;
                boolean columnLock = (data[pos] & 0x08) != 0;
                int priority = data[pos] & 0x07;
                boolean relativePositioning = (data[pos + 1] & 0x80) != 0;
                int anchorVertical = data[pos + 1] & 0x7f;
                int anchorHorizontal = data[pos + 2] & 0xff;
                int anchorId = (data[pos + 3] & 0xf0) >> 4;
                int rowCount = data[pos + 3] & 0x0f;
                int columnCount = data[pos + 4] & 0x3f;
                int windowStyle = (data[pos + 5] & 0x38) >> 3;
                int penStyle = data[pos + 5] & 0x07;
                pos += 6;
                emitCaptionEvent(new CaptionEvent(CAPTION_EMIT_TYPE_COMMAND_DFX,
                        new CaptionWindow(windowId, visible, rowLock, columnLock, priority,
                                relativePositioning, anchorVertical, anchorHorizontal, anchorId,
                                rowCount, columnCount, penStyle, windowStyle)));
                if (DEBUG) {
                    Log.d(TAG, String.format(
                            "CaptionCommand DFx windowId: %d, priority: %d, columnLock: %s, "
                                    + "rowLock: %s, visible: %s, anchorVertical: %d, "
                                    + "relativePositioning: %s, anchorHorizontal: %d, "
                                    + "rowCount: %d, anchorId: %d, columnCount: %d, penStyle: %d, "
                                    + "windowStyle: %d",
                            windowId, priority, columnLock, rowLock, visible, anchorVertical,
                            relativePositioning, anchorHorizontal, rowCount, anchorId, columnCount,
                            penStyle, windowStyle));
                }
                break;
            }

            default:
                break;
        }
        return pos;
    }

    private int parseG0(byte[] data, int pos) {
        // For the details of G0 code group, see CEA-708B Section 7.4.3.
        // GL Group: G0 Modified version of ANSI X3.4 Printable Character Set (ASCII)
        if (mCommand == Const.CODE_G0_MUSICNOTE) {
            // Music note.
            mBuffer.append(MUSIC_NOTE_CHAR);
        } else {
            // Put ASCII code into buffer.
            mBuffer.append((char) mCommand);
        }
        return pos;
    }

    private int parseG1(byte[] data, int pos) {
        // For the details of G0 code group, see CEA-708B Section 7.4.4.
        // GR Group: G1 ISO 8859-1 Latin 1 Characters
        // Put ASCII Extended character set into buffer.
        mBuffer.append((char) mCommand);
        return pos;
    }

    // Step 4. Extended code groups
    private int parseExt1(byte[] data, int pos) {
        // For the details of EXT1 code group, see CEA-708B Section 7.2.
        mCommand = data[pos] & 0xff;
        ++pos;
        if (mCommand >= Const.CODE_C2_RANGE_START
                && mCommand <= Const.CODE_C2_RANGE_END) {
            pos = parseC2(data, pos);
        } else if (mCommand >= Const.CODE_C3_RANGE_START
                && mCommand <= Const.CODE_C3_RANGE_END) {
            pos = parseC3(data, pos);
        } else if (mCommand >= Const.CODE_G2_RANGE_START
                && mCommand <= Const.CODE_G2_RANGE_END) {
            pos = parseG2(data, pos);
        } else if (mCommand >= Const.CODE_G3_RANGE_START
                && mCommand <= Const.CODE_G3_RANGE_END) {
            pos = parseG3(data ,pos);
        }
        return pos;
    }

    private int parseC2(byte[] data, int pos) {
        // For the details of C2 code group, see CEA-708B Section 7.4.7.
        // Extended Miscellaneous Control Codes
        // C2 Table : No commands as of CEA-708B. A decoder must skip.
        if (mCommand >= Const.CODE_C2_SKIP0_RANGE_START
                && mCommand <= Const.CODE_C2_SKIP0_RANGE_END) {
            // Do nothing.
        } else if (mCommand >= Const.CODE_C2_SKIP1_RANGE_START
                && mCommand <= Const.CODE_C2_SKIP1_RANGE_END) {
            ++pos;
        } else if (mCommand >= Const.CODE_C2_SKIP2_RANGE_START
                && mCommand <= Const.CODE_C2_SKIP2_RANGE_END) {
            pos += 2;
        } else if (mCommand >= Const.CODE_C2_SKIP3_RANGE_START
                && mCommand <= Const.CODE_C2_SKIP3_RANGE_END) {
            pos += 3;
        }
        return pos;
    }

    private int parseC3(byte[] data, int pos) {
        // For the details of C3 code group, see CEA-708B Section 7.4.8.
        // Extended Control Code Set 2
        // C3 Table : No commands as of CEA-708B. A decoder must skip.
        if (mCommand >= Const.CODE_C3_SKIP4_RANGE_START
                && mCommand <= Const.CODE_C3_SKIP4_RANGE_END) {
            pos += 4;
        } else if (mCommand >= Const.CODE_C3_SKIP5_RANGE_START
                && mCommand <= Const.CODE_C3_SKIP5_RANGE_END) {
            pos += 5;
        }
        return pos;
    }

    private int parseG2(byte[] data, int pos) {
        // For the details of C3 code group, see CEA-708B Section 7.4.5.
        // Extended Control Code Set 1(G2 Table)
        switch (mCommand) {
            case Const.CODE_G2_TSP:
                // TODO : TSP is the Transparent space
                break;
            case Const.CODE_G2_NBTSP:
                // TODO : NBTSP is Non-Breaking Transparent Space.
                break;
            case Const.CODE_G2_BLK:
                // TODO : BLK indicates a solid block which fills the entire character block
                // TODO : with a solid foreground color.
                break;
            default:
                break;
        }
        return pos;
    }

    private int parseG3(byte[] data, int pos) {
        // For the details of C3 code group, see CEA-708B Section 7.4.6.
        // Future characters and icons(G3 Table)
        if (mCommand == Const.CODE_G3_CC) {
            // TODO : [CC] icon with square corners
        }

        // Do nothing
        return pos;
    }

    /**
     * @hide
     *
     * Collection of CEA-708 structures.
     */
    private static class Const {

        private Const() {
        }

        // For the details of the ranges of DTVCC code groups, see CEA-708B Table 6.
        public static final int CODE_C0_RANGE_START = 0x00;
        public static final int CODE_C0_RANGE_END = 0x1f;
        public static final int CODE_C1_RANGE_START = 0x80;
        public static final int CODE_C1_RANGE_END = 0x9f;
        public static final int CODE_G0_RANGE_START = 0x20;
        public static final int CODE_G0_RANGE_END = 0x7f;
        public static final int CODE_G1_RANGE_START = 0xa0;
        public static final int CODE_G1_RANGE_END = 0xff;
        public static final int CODE_C2_RANGE_START = 0x00;
        public static final int CODE_C2_RANGE_END = 0x1f;
        public static final int CODE_C3_RANGE_START = 0x80;
        public static final int CODE_C3_RANGE_END = 0x9f;
        public static final int CODE_G2_RANGE_START = 0x20;
        public static final int CODE_G2_RANGE_END = 0x7f;
        public static final int CODE_G3_RANGE_START = 0xa0;
        public static final int CODE_G3_RANGE_END = 0xff;

        // The following ranges are defined in CEA-708B Section 7.4.1.
        public static final int CODE_C0_SKIP2_RANGE_START = 0x18;
        public static final int CODE_C0_SKIP2_RANGE_END = 0x1f;
        public static final int CODE_C0_SKIP1_RANGE_START = 0x10;
        public static final int CODE_C0_SKIP1_RANGE_END = 0x17;

        // The following ranges are defined in CEA-708B Section 7.4.7.
        public static final int CODE_C2_SKIP0_RANGE_START = 0x00;
        public static final int CODE_C2_SKIP0_RANGE_END = 0x07;
        public static final int CODE_C2_SKIP1_RANGE_START = 0x08;
        public static final int CODE_C2_SKIP1_RANGE_END = 0x0f;
        public static final int CODE_C2_SKIP2_RANGE_START = 0x10;
        public static final int CODE_C2_SKIP2_RANGE_END = 0x17;
        public static final int CODE_C2_SKIP3_RANGE_START = 0x18;
        public static final int CODE_C2_SKIP3_RANGE_END = 0x1f;

        // The following ranges are defined in CEA-708B Section 7.4.8.
        public static final int CODE_C3_SKIP4_RANGE_START = 0x80;
        public static final int CODE_C3_SKIP4_RANGE_END = 0x87;
        public static final int CODE_C3_SKIP5_RANGE_START = 0x88;
        public static final int CODE_C3_SKIP5_RANGE_END = 0x8f;

        // The following values are the special characters of CEA-708 spec.
        public static final int CODE_C0_NUL = 0x00;
        public static final int CODE_C0_ETX = 0x03;
        public static final int CODE_C0_BS = 0x08;
        public static final int CODE_C0_FF = 0x0c;
        public static final int CODE_C0_CR = 0x0d;
        public static final int CODE_C0_HCR = 0x0e;
        public static final int CODE_C0_EXT1 = 0x10;
        public static final int CODE_C0_P16 = 0x18;
        public static final int CODE_G0_MUSICNOTE = 0x7f;
        public static final int CODE_G2_TSP = 0x20;
        public static final int CODE_G2_NBTSP = 0x21;
        public static final int CODE_G2_BLK = 0x30;
        public static final int CODE_G3_CC = 0xa0;

        // The following values are the command bits of CEA-708 spec.
        public static final int CODE_C1_CW0 = 0x80;
        public static final int CODE_C1_CW1 = 0x81;
        public static final int CODE_C1_CW2 = 0x82;
        public static final int CODE_C1_CW3 = 0x83;
        public static final int CODE_C1_CW4 = 0x84;
        public static final int CODE_C1_CW5 = 0x85;
        public static final int CODE_C1_CW6 = 0x86;
        public static final int CODE_C1_CW7 = 0x87;
        public static final int CODE_C1_CLW = 0x88;
        public static final int CODE_C1_DSW = 0x89;
        public static final int CODE_C1_HDW = 0x8a;
        public static final int CODE_C1_TGW = 0x8b;
        public static final int CODE_C1_DLW = 0x8c;
        public static final int CODE_C1_DLY = 0x8d;
        public static final int CODE_C1_DLC = 0x8e;
        public static final int CODE_C1_RST = 0x8f;
        public static final int CODE_C1_SPA = 0x90;
        public static final int CODE_C1_SPC = 0x91;
        public static final int CODE_C1_SPL = 0x92;
        public static final int CODE_C1_SWA = 0x97;
        public static final int CODE_C1_DF0 = 0x98;
        public static final int CODE_C1_DF1 = 0x99;
        public static final int CODE_C1_DF2 = 0x9a;
        public static final int CODE_C1_DF3 = 0x9b;
        public static final int CODE_C1_DF4 = 0x9c;
        public static final int CODE_C1_DF5 = 0x9d;
        public static final int CODE_C1_DF6 = 0x9e;
        public static final int CODE_C1_DF7 = 0x9f;
    }

    /**
     * @hide
     *
     * CEA-708B-specific color.
     */
    public static class CaptionColor {
        public static final int OPACITY_SOLID = 0;
        public static final int OPACITY_FLASH = 1;
        public static final int OPACITY_TRANSLUCENT = 2;
        public static final int OPACITY_TRANSPARENT = 3;

        private static final int[] COLOR_MAP = new int[] { 0x00, 0x0f, 0xf0, 0xff };
        private static final int[] OPACITY_MAP = new int[] { 0xff, 0xfe, 0x80, 0x00 };

        public final int opacity;
        public final int red;
        public final int green;
        public final int blue;

        public CaptionColor(int opacity, int red, int green, int blue) {
            this.opacity = opacity;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        public int getArgbValue() {
            return Color.argb(
                    OPACITY_MAP[opacity], COLOR_MAP[red], COLOR_MAP[green], COLOR_MAP[blue]);
        }
    }

    /**
     * @hide
     *
     * Caption event generated by {@link Cea708CCParser}.
     */
    public static class CaptionEvent {
        public final int type;
        public final Object obj;

        public CaptionEvent(int type, Object obj) {
            this.type = type;
            this.obj = obj;
        }
    }

    /**
     * @hide
     *
     * Pen style information.
     */
    public static class CaptionPenAttr {
        // Pen sizes
        public static final int PEN_SIZE_SMALL = 0;
        public static final int PEN_SIZE_STANDARD = 1;
        public static final int PEN_SIZE_LARGE = 2;

        // Offsets
        public static final int OFFSET_SUBSCRIPT = 0;
        public static final int OFFSET_NORMAL = 1;
        public static final int OFFSET_SUPERSCRIPT = 2;

        public final int penSize;
        public final int penOffset;
        public final int textTag;
        public final int fontTag;
        public final int edgeType;
        public final boolean underline;
        public final boolean italic;

        public CaptionPenAttr(int penSize, int penOffset, int textTag, int fontTag, int edgeType,
                boolean underline, boolean italic) {
            this.penSize = penSize;
            this.penOffset = penOffset;
            this.textTag = textTag;
            this.fontTag = fontTag;
            this.edgeType = edgeType;
            this.underline = underline;
            this.italic = italic;
        }
    }

    /**
     * @hide
     *
     * {@link CaptionColor} objects that indicate the foreground, background, and edge color of a
     * pen.
     */
    public static class CaptionPenColor {
        public final CaptionColor foregroundColor;
        public final CaptionColor backgroundColor;
        public final CaptionColor edgeColor;

        public CaptionPenColor(CaptionColor foregroundColor, CaptionColor backgroundColor,
                CaptionColor edgeColor) {
            this.foregroundColor = foregroundColor;
            this.backgroundColor = backgroundColor;
            this.edgeColor = edgeColor;
        }
    }

    /**
     * @hide
     *
     * Location information of a pen.
     */
    public static class CaptionPenLocation {
        public final int row;
        public final int column;

        public CaptionPenLocation(int row, int column) {
            this.row = row;
            this.column = column;
        }
    }

    /**
     * @hide
     *
     * Attributes of a caption window, which is defined in CEA-708B.
     */
    public static class CaptionWindowAttr {
        public final CaptionColor fillColor;
        public final CaptionColor borderColor;
        public final int borderType;
        public final boolean wordWrap;
        public final int printDirection;
        public final int scrollDirection;
        public final int justify;
        public final int effectDirection;
        public final int effectSpeed;
        public final int displayEffect;

        public CaptionWindowAttr(CaptionColor fillColor, CaptionColor borderColor, int borderType,
                boolean wordWrap, int printDirection, int scrollDirection, int justify,
                int effectDirection,
                int effectSpeed, int displayEffect) {
            this.fillColor = fillColor;
            this.borderColor = borderColor;
            this.borderType = borderType;
            this.wordWrap = wordWrap;
            this.printDirection = printDirection;
            this.scrollDirection = scrollDirection;
            this.justify = justify;
            this.effectDirection = effectDirection;
            this.effectSpeed = effectSpeed;
            this.displayEffect = displayEffect;
        }
    }

    /**
     * @hide
     *
     * Construction information of the caption window of CEA-708B.
     */
    public static class CaptionWindow {
        public final int id;
        public final boolean visible;
        public final boolean rowLock;
        public final boolean columnLock;
        public final int priority;
        public final boolean relativePositioning;
        public final int anchorVertical;
        public final int anchorHorizontal;
        public final int anchorId;
        public final int rowCount;
        public final int columnCount;
        public final int penStyle;
        public final int windowStyle;

        public CaptionWindow(int id, boolean visible,
                boolean rowLock, boolean columnLock, int priority, boolean relativePositioning,
                int anchorVertical, int anchorHorizontal, int anchorId,
                int rowCount, int columnCount, int penStyle, int windowStyle) {
            this.id = id;
            this.visible = visible;
            this.rowLock = rowLock;
            this.columnLock = columnLock;
            this.priority = priority;
            this.relativePositioning = relativePositioning;
            this.anchorVertical = anchorVertical;
            this.anchorHorizontal = anchorHorizontal;
            this.anchorId = anchorId;
            this.rowCount = rowCount;
            this.columnCount = columnCount;
            this.penStyle = penStyle;
            this.windowStyle = windowStyle;
        }
    }
}

/**
 * Widget capable of rendering CEA-708 closed captions.
 *
 * @hide
 */
class Cea708CCWidget extends ClosedCaptionWidget implements Cea708CCParser.DisplayListener {
    private final CCHandler mCCHandler;

    public Cea708CCWidget(Context context) {
        this(context, null);
    }

    public Cea708CCWidget(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Cea708CCWidget(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public Cea708CCWidget(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mCCHandler = new CCHandler((CCLayout) mClosedCaptionLayout);
    }

    @Override
    public ClosedCaptionLayout createCaptionLayout(Context context) {
        return new CCLayout(context);
    }

    @Override
    public void emitEvent(Cea708CCParser.CaptionEvent event) {
        mCCHandler.processCaptionEvent(event);

        setSize(getWidth(), getHeight());

        if (mListener != null) {
            mListener.onChanged(this);
        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ((ViewGroup) mClosedCaptionLayout).draw(canvas);
    }

    /**
     * @hide
     *
     * A layout that scales its children using the given percentage value.
     */
    static class ScaledLayout extends ViewGroup {
        private static final String TAG = "ScaledLayout";
        private static final boolean DEBUG = false;
        private static final Comparator<Rect> mRectTopLeftSorter = new Comparator<Rect>() {
            @Override
            public int compare(Rect lhs, Rect rhs) {
                if (lhs.top != rhs.top) {
                    return lhs.top - rhs.top;
                } else {
                    return lhs.left - rhs.left;
                }
            }
        };

        private Rect[] mRectArray;

        public ScaledLayout(Context context) {
            super(context);
        }

        /**
         * @hide
         *
         * ScaledLayoutParams stores the four scale factors.
         * <br>
         * Vertical coordinate system:   (scaleStartRow * 100) % ~ (scaleEndRow * 100) %
         * Horizontal coordinate system: (scaleStartCol * 100) % ~ (scaleEndCol * 100) %
         * <br>
         * In XML, for example,
         * <pre>
         * {@code
         * <View
         *     app:layout_scaleStartRow="0.1"
         *     app:layout_scaleEndRow="0.5"
         *     app:layout_scaleStartCol="0.4"
         *     app:layout_scaleEndCol="1" />
         * }
         * </pre>
         */
        static class ScaledLayoutParams extends ViewGroup.LayoutParams {
            public static final float SCALE_UNSPECIFIED = -1;
            public float scaleStartRow;
            public float scaleEndRow;
            public float scaleStartCol;
            public float scaleEndCol;

            public ScaledLayoutParams(float scaleStartRow, float scaleEndRow,
                    float scaleStartCol, float scaleEndCol) {
                super(MATCH_PARENT, MATCH_PARENT);
                this.scaleStartRow = scaleStartRow;
                this.scaleEndRow = scaleEndRow;
                this.scaleStartCol = scaleStartCol;
                this.scaleEndCol = scaleEndCol;
            }

            public ScaledLayoutParams(Context context, AttributeSet attrs) {
                super(MATCH_PARENT, MATCH_PARENT);
            }
        }

        @Override
        public LayoutParams generateLayoutParams(AttributeSet attrs) {
            return new ScaledLayoutParams(getContext(), attrs);
        }

        @Override
        protected boolean checkLayoutParams(LayoutParams p) {
            return (p instanceof ScaledLayoutParams);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);
            int width = widthSpecSize - getPaddingLeft() - getPaddingRight();
            int height = heightSpecSize - getPaddingTop() - getPaddingBottom();
            if (DEBUG) {
                Log.d(TAG, String.format("onMeasure width: %d, height: %d", width, height));
            }
            int count = getChildCount();
            mRectArray = new Rect[count];
            for (int i = 0; i < count; ++i) {
                View child = getChildAt(i);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                float scaleStartRow, scaleEndRow, scaleStartCol, scaleEndCol;
                if (!(params instanceof ScaledLayoutParams)) {
                    throw new RuntimeException(
                            "A child of ScaledLayout cannot have the UNSPECIFIED scale factors");
                }
                scaleStartRow = ((ScaledLayoutParams) params).scaleStartRow;
                scaleEndRow = ((ScaledLayoutParams) params).scaleEndRow;
                scaleStartCol = ((ScaledLayoutParams) params).scaleStartCol;
                scaleEndCol = ((ScaledLayoutParams) params).scaleEndCol;
                if (scaleStartRow < 0 || scaleStartRow > 1) {
                    throw new RuntimeException("A child of ScaledLayout should have a range of "
                            + "scaleStartRow between 0 and 1");
                }
                if (scaleEndRow < scaleStartRow || scaleStartRow > 1) {
                    throw new RuntimeException("A child of ScaledLayout should have a range of "
                            + "scaleEndRow between scaleStartRow and 1");
                }
                if (scaleEndCol < 0 || scaleEndCol > 1) {
                    throw new RuntimeException("A child of ScaledLayout should have a range of "
                            + "scaleStartCol between 0 and 1");
                }
                if (scaleEndCol < scaleStartCol || scaleEndCol > 1) {
                    throw new RuntimeException("A child of ScaledLayout should have a range of "
                            + "scaleEndCol between scaleStartCol and 1");
                }
                if (DEBUG) {
                    Log.d(TAG, String.format("onMeasure child scaleStartRow: %f scaleEndRow: %f "
                                    + "scaleStartCol: %f scaleEndCol: %f",
                            scaleStartRow, scaleEndRow, scaleStartCol, scaleEndCol));
                }
                mRectArray[i] = new Rect((int) (scaleStartCol * width), (int) (scaleStartRow
                        * height), (int) (scaleEndCol * width), (int) (scaleEndRow * height));
                int childWidthSpec = MeasureSpec.makeMeasureSpec(
                        (int) (width * (scaleEndCol - scaleStartCol)), MeasureSpec.EXACTLY);
                int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                child.measure(childWidthSpec, childHeightSpec);

                // If the height of the measured child view is bigger than the height of the
                // calculated region by the given ScaleLayoutParams, the height of the region should
                // be increased to fit the size of the child view.
                if (child.getMeasuredHeight() > mRectArray[i].height()) {
                    int overflowedHeight = child.getMeasuredHeight() - mRectArray[i].height();
                    overflowedHeight = (overflowedHeight + 1) / 2;
                    mRectArray[i].bottom += overflowedHeight;
                    mRectArray[i].top -= overflowedHeight;
                    if (mRectArray[i].top < 0) {
                        mRectArray[i].bottom -= mRectArray[i].top;
                        mRectArray[i].top = 0;
                    }
                    if (mRectArray[i].bottom > height) {
                        mRectArray[i].top -= mRectArray[i].bottom - height;
                        mRectArray[i].bottom = height;
                    }
                }
                childHeightSpec = MeasureSpec.makeMeasureSpec(
                        (int) (height * (scaleEndRow - scaleStartRow)), MeasureSpec.EXACTLY);
                child.measure(childWidthSpec, childHeightSpec);
            }

            // Avoid overlapping rectangles.
            // Step 1. Sort rectangles by position (top-left).
            int visibleRectCount = 0;
            int[] visibleRectGroup = new int[count];
            Rect[] visibleRectArray = new Rect[count];
            for (int i = 0; i < count; ++i) {
                if (getChildAt(i).getVisibility() == View.VISIBLE) {
                    visibleRectGroup[visibleRectCount] = visibleRectCount;
                    visibleRectArray[visibleRectCount] = mRectArray[i];
                    ++visibleRectCount;
                }
            }
            Arrays.sort(visibleRectArray, 0, visibleRectCount, mRectTopLeftSorter);

            // Step 2. Move down if there are overlapping rectangles.
            for (int i = 0; i < visibleRectCount - 1; ++i) {
                for (int j = i + 1; j < visibleRectCount; ++j) {
                    if (Rect.intersects(visibleRectArray[i], visibleRectArray[j])) {
                        visibleRectGroup[j] = visibleRectGroup[i];
                        visibleRectArray[j].set(visibleRectArray[j].left,
                                visibleRectArray[i].bottom,
                                visibleRectArray[j].right,
                                visibleRectArray[i].bottom + visibleRectArray[j].height());
                    }
                }
            }

            // Step 3. Move up if there is any overflowed rectangle.
            for (int i = visibleRectCount - 1; i >= 0; --i) {
                if (visibleRectArray[i].bottom > height) {
                    int overflowedHeight = visibleRectArray[i].bottom - height;
                    for (int j = 0; j <= i; ++j) {
                        if (visibleRectGroup[i] == visibleRectGroup[j]) {
                            visibleRectArray[j].set(visibleRectArray[j].left,
                                    visibleRectArray[j].top - overflowedHeight,
                                    visibleRectArray[j].right,
                                    visibleRectArray[j].bottom - overflowedHeight);
                        }
                    }
                }
            }
            setMeasuredDimension(widthSpecSize, heightSpecSize);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int paddingLeft = getPaddingLeft();
            int paddingTop = getPaddingTop();
            int count = getChildCount();
            for (int i = 0; i < count; ++i) {
                View child = getChildAt(i);
                if (child.getVisibility() != GONE) {
                    int childLeft = paddingLeft + mRectArray[i].left;
                    int childTop = paddingTop + mRectArray[i].top;
                    int childBottom = paddingLeft + mRectArray[i].bottom;
                    int childRight = paddingTop + mRectArray[i].right;
                    if (DEBUG) {
                        Log.d(TAG, String.format(
                                "child layout bottom: %d left: %d right: %d top: %d",
                                childBottom, childLeft, childRight, childTop));
                    }
                    child.layout(childLeft, childTop, childRight, childBottom);
                }
            }
        }

        @Override
        public void dispatchDraw(Canvas canvas) {
            int paddingLeft = getPaddingLeft();
            int paddingTop = getPaddingTop();
            int count = getChildCount();
            for (int i = 0; i < count; ++i) {
                View child = getChildAt(i);
                if (child.getVisibility() != GONE) {
                    if (i >= mRectArray.length) {
                        break;
                    }
                    int childLeft = paddingLeft + mRectArray[i].left;
                    int childTop = paddingTop + mRectArray[i].top;
                    final int saveCount = canvas.save();
                    canvas.translate(childLeft, childTop);
                    child.draw(canvas);
                    canvas.restoreToCount(saveCount);
                }
            }
        }
    }

    /**
     * @hide
     *
     * Layout containing the safe title area that helps the closed captions look more prominent.
     *
     * <p>This is required by CEA-708B.
     */
    static class CCLayout extends ScaledLayout implements ClosedCaptionLayout {
        private static final float SAFE_TITLE_AREA_SCALE_START_X = 0.1f;
        private static final float SAFE_TITLE_AREA_SCALE_END_X = 0.9f;
        private static final float SAFE_TITLE_AREA_SCALE_START_Y = 0.1f;
        private static final float SAFE_TITLE_AREA_SCALE_END_Y = 0.9f;

        private final ScaledLayout mSafeTitleAreaLayout;

        public CCLayout(Context context) {
            super(context);

            mSafeTitleAreaLayout = new ScaledLayout(context);
            addView(mSafeTitleAreaLayout, new ScaledLayout.ScaledLayoutParams(
                    SAFE_TITLE_AREA_SCALE_START_X, SAFE_TITLE_AREA_SCALE_END_X,
                    SAFE_TITLE_AREA_SCALE_START_Y, SAFE_TITLE_AREA_SCALE_END_Y));
        }

        public void addOrUpdateViewToSafeTitleArea(CCWindowLayout captionWindowLayout,
                ScaledLayoutParams scaledLayoutParams) {
            int index = mSafeTitleAreaLayout.indexOfChild(captionWindowLayout);
            if (index < 0) {
                mSafeTitleAreaLayout.addView(captionWindowLayout, scaledLayoutParams);
                return;
            }
            mSafeTitleAreaLayout.updateViewLayout(captionWindowLayout, scaledLayoutParams);
        }

        public void removeViewFromSafeTitleArea(CCWindowLayout captionWindowLayout) {
            mSafeTitleAreaLayout.removeView(captionWindowLayout);
        }

        public void setCaptionStyle(CaptionStyle style) {
            final int count = mSafeTitleAreaLayout.getChildCount();
            for (int i = 0; i < count; ++i) {
                final CCWindowLayout windowLayout =
                        (CCWindowLayout) mSafeTitleAreaLayout.getChildAt(i);
                windowLayout.setCaptionStyle(style);
            }
        }

        public void setFontScale(float fontScale) {
            final int count = mSafeTitleAreaLayout.getChildCount();
            for (int i = 0; i < count; ++i) {
                final CCWindowLayout windowLayout =
                        (CCWindowLayout) mSafeTitleAreaLayout.getChildAt(i);
                windowLayout.setFontScale(fontScale);
            }
        }
    }

    /**
     * @hide
     *
     * Renders the selected CC track.
     */
    static class CCHandler implements Handler.Callback {
        // TODO: Remaining works
        // CaptionTrackRenderer does not support the full spec of CEA-708. The remaining works are
        // described in the follows.
        // C0 Table: Backspace, FF, and HCR are not supported. The rule for P16 is not standardized
        //           but it is handled as EUC-KR charset for Korea broadcasting.
        // C1 Table: All the styles of windows and pens except underline, italic, pen size, and pen
        //           offset specified in CEA-708 are ignored and this follows system wide CC
        //           preferences for look and feel. SetPenLocation is not implemented.
        // G2 Table: TSP, NBTSP and BLK are not supported.
        // Text/commands: Word wrapping, fonts, row and column locking are not supported.

        private static final String TAG = "CCHandler";
        private static final boolean DEBUG = false;

        private static final int TENTHS_OF_SECOND_IN_MILLIS = 100;

        // According to CEA-708B, there can exist up to 8 caption windows.
        private static final int CAPTION_WINDOWS_MAX = 8;
        private static final int CAPTION_ALL_WINDOWS_BITMAP = 255;

        private static final int MSG_DELAY_CANCEL = 1;
        private static final int MSG_CAPTION_CLEAR = 2;

        private static final long CAPTION_CLEAR_INTERVAL_MS = 60000;

        private final CCLayout mCCLayout;
        private boolean mIsDelayed = false;
        private CCWindowLayout mCurrentWindowLayout;
        private final CCWindowLayout[] mCaptionWindowLayouts =
                new CCWindowLayout[CAPTION_WINDOWS_MAX];
        private final ArrayList<Cea708CCParser.CaptionEvent> mPendingCaptionEvents
                = new ArrayList<>();
        private final Handler mHandler;

        public CCHandler(CCLayout ccLayout) {
            mCCLayout = ccLayout;
            mHandler = new Handler(this);
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DELAY_CANCEL:
                    delayCancel();
                    return true;
                case MSG_CAPTION_CLEAR:
                    clearWindows(CAPTION_ALL_WINDOWS_BITMAP);
                    return true;
            }
            return false;
        }

        public void processCaptionEvent(Cea708CCParser.CaptionEvent event) {
            if (mIsDelayed) {
                mPendingCaptionEvents.add(event);
                return;
            }
            switch (event.type) {
                case Cea708CCParser.CAPTION_EMIT_TYPE_BUFFER:
                    sendBufferToCurrentWindow((String) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_CONTROL:
                    sendControlToCurrentWindow((char) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_CWX:
                    setCurrentWindowLayout((int) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_CLW:
                    clearWindows((int) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_DSW:
                    displayWindows((int) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_HDW:
                    hideWindows((int) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_TGW:
                    toggleWindows((int) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_DLW:
                    deleteWindows((int) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_DLY:
                    delay((int) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_DLC:
                    delayCancel();
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_RST:
                    reset();
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_SPA:
                    setPenAttr((Cea708CCParser.CaptionPenAttr) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_SPC:
                    setPenColor((Cea708CCParser.CaptionPenColor) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_SPL:
                    setPenLocation((Cea708CCParser.CaptionPenLocation) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_SWA:
                    setWindowAttr((Cea708CCParser.CaptionWindowAttr) event.obj);
                    break;
                case Cea708CCParser.CAPTION_EMIT_TYPE_COMMAND_DFX:
                    defineWindow((Cea708CCParser.CaptionWindow) event.obj);
                    break;
            }
        }

        // The window related caption commands
        private void setCurrentWindowLayout(int windowId) {
            if (windowId < 0 || windowId >= mCaptionWindowLayouts.length) {
                return;
            }
            CCWindowLayout windowLayout = mCaptionWindowLayouts[windowId];
            if (windowLayout == null) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "setCurrentWindowLayout to " + windowId);
            }
            mCurrentWindowLayout = windowLayout;
        }

        // Each bit of windowBitmap indicates a window.
        // If a bit is set, the window id is the same as the number of the trailing zeros of the
        // bit.
        private ArrayList<CCWindowLayout> getWindowsFromBitmap(int windowBitmap) {
            ArrayList<CCWindowLayout> windows = new ArrayList<>();
            for (int i = 0; i < CAPTION_WINDOWS_MAX; ++i) {
                if ((windowBitmap & (1 << i)) != 0) {
                    CCWindowLayout windowLayout = mCaptionWindowLayouts[i];
                    if (windowLayout != null) {
                        windows.add(windowLayout);
                    }
                }
            }
            return windows;
        }

        private void clearWindows(int windowBitmap) {
            if (windowBitmap == 0) {
                return;
            }
            for (CCWindowLayout windowLayout : getWindowsFromBitmap(windowBitmap)) {
                windowLayout.clear();
            }
        }

        private void displayWindows(int windowBitmap) {
            if (windowBitmap == 0) {
                return;
            }
            for (CCWindowLayout windowLayout : getWindowsFromBitmap(windowBitmap)) {
                windowLayout.show();
            }
        }

        private void hideWindows(int windowBitmap) {
            if (windowBitmap == 0) {
                return;
            }
            for (CCWindowLayout windowLayout : getWindowsFromBitmap(windowBitmap)) {
                windowLayout.hide();
            }
        }

        private void toggleWindows(int windowBitmap) {
            if (windowBitmap == 0) {
                return;
            }
            for (CCWindowLayout windowLayout : getWindowsFromBitmap(windowBitmap)) {
                if (windowLayout.isShown()) {
                    windowLayout.hide();
                } else {
                    windowLayout.show();
                }
            }
        }

        private void deleteWindows(int windowBitmap) {
            if (windowBitmap == 0) {
                return;
            }
            for (CCWindowLayout windowLayout : getWindowsFromBitmap(windowBitmap)) {
                windowLayout.removeFromCaptionView();
                mCaptionWindowLayouts[windowLayout.getCaptionWindowId()] = null;
            }
        }

        public void reset() {
            mCurrentWindowLayout = null;
            mIsDelayed = false;
            mPendingCaptionEvents.clear();
            for (int i = 0; i < CAPTION_WINDOWS_MAX; ++i) {
                if (mCaptionWindowLayouts[i] != null) {
                    mCaptionWindowLayouts[i].removeFromCaptionView();
                }
                mCaptionWindowLayouts[i] = null;
            }
            mCCLayout.setVisibility(View.INVISIBLE);
            mHandler.removeMessages(MSG_CAPTION_CLEAR);
        }

        private void setWindowAttr(Cea708CCParser.CaptionWindowAttr windowAttr) {
            if (mCurrentWindowLayout != null) {
                mCurrentWindowLayout.setWindowAttr(windowAttr);
            }
        }

        private void defineWindow(Cea708CCParser.CaptionWindow window) {
            if (window == null) {
                return;
            }
            int windowId = window.id;
            if (windowId < 0 || windowId >= mCaptionWindowLayouts.length) {
                return;
            }
            CCWindowLayout windowLayout = mCaptionWindowLayouts[windowId];
            if (windowLayout == null) {
                windowLayout = new CCWindowLayout(mCCLayout.getContext());
            }
            windowLayout.initWindow(mCCLayout, window);
            mCurrentWindowLayout = mCaptionWindowLayouts[windowId] = windowLayout;
        }

        // The job related caption commands
        private void delay(int tenthsOfSeconds) {
            if (tenthsOfSeconds < 0 || tenthsOfSeconds > 255) {
                return;
            }
            mIsDelayed = true;
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_DELAY_CANCEL),
                    tenthsOfSeconds * TENTHS_OF_SECOND_IN_MILLIS);
        }

        private void delayCancel() {
            mIsDelayed = false;
            processPendingBuffer();
        }

        private void processPendingBuffer() {
            for (Cea708CCParser.CaptionEvent event : mPendingCaptionEvents) {
                processCaptionEvent(event);
            }
            mPendingCaptionEvents.clear();
        }

        // The implicit write caption commands
        private void sendControlToCurrentWindow(char control) {
            if (mCurrentWindowLayout != null) {
                mCurrentWindowLayout.sendControl(control);
            }
        }

        private void sendBufferToCurrentWindow(String buffer) {
            if (mCurrentWindowLayout != null) {
                mCurrentWindowLayout.sendBuffer(buffer);
                mHandler.removeMessages(MSG_CAPTION_CLEAR);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CAPTION_CLEAR),
                        CAPTION_CLEAR_INTERVAL_MS);
            }
        }

        // The pen related caption commands
        private void setPenAttr(Cea708CCParser.CaptionPenAttr attr) {
            if (mCurrentWindowLayout != null) {
                mCurrentWindowLayout.setPenAttr(attr);
            }
        }

        private void setPenColor(Cea708CCParser.CaptionPenColor color) {
            if (mCurrentWindowLayout != null) {
                mCurrentWindowLayout.setPenColor(color);
            }
        }

        private void setPenLocation(Cea708CCParser.CaptionPenLocation location) {
            if (mCurrentWindowLayout != null) {
                mCurrentWindowLayout.setPenLocation(location.row, location.column);
            }
        }
    }

    /**
     * @hide
     *
     * Layout which renders a caption window of CEA-708B. It contains a {@link TextView} that takes
     * care of displaying the actual CC text.
     */
    static class CCWindowLayout extends RelativeLayout implements View.OnLayoutChangeListener {
        private static final String TAG = "CCWindowLayout";

        private static final float PROPORTION_PEN_SIZE_SMALL = .75f;
        private static final float PROPORTION_PEN_SIZE_LARGE = 1.25f;

        // The following values indicates the maximum cell number of a window.
        private static final int ANCHOR_RELATIVE_POSITIONING_MAX = 99;
        private static final int ANCHOR_VERTICAL_MAX = 74;
        private static final int ANCHOR_HORIZONTAL_16_9_MAX = 209;
        private static final int MAX_COLUMN_COUNT_16_9 = 42;

        // The following values indicates a gravity of a window.
        private static final int ANCHOR_MODE_DIVIDER = 3;
        private static final int ANCHOR_HORIZONTAL_MODE_LEFT = 0;
        private static final int ANCHOR_HORIZONTAL_MODE_CENTER = 1;
        private static final int ANCHOR_HORIZONTAL_MODE_RIGHT = 2;
        private static final int ANCHOR_VERTICAL_MODE_TOP = 0;
        private static final int ANCHOR_VERTICAL_MODE_CENTER = 1;
        private static final int ANCHOR_VERTICAL_MODE_BOTTOM = 2;

        private CCLayout mCCLayout;

        private CCView mCCView;
        private CaptionStyle mCaptionStyle;
        private int mRowLimit = 0;
        private final SpannableStringBuilder mBuilder = new SpannableStringBuilder();
        private final List<CharacterStyle> mCharacterStyles = new ArrayList<>();
        private int mCaptionWindowId;
        private int mRow = -1;
        private float mFontScale;
        private float mTextSize;
        private String mWidestChar;
        private int mLastCaptionLayoutWidth;
        private int mLastCaptionLayoutHeight;

        public CCWindowLayout(Context context) {
            this(context, null);
        }

        public CCWindowLayout(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public CCWindowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public CCWindowLayout(Context context, AttributeSet attrs, int defStyleAttr,
                int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);

            // Add a subtitle view to the layout.
            mCCView = new CCView(context);
            LayoutParams params = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addView(mCCView, params);

            // Set the system wide CC preferences to the subtitle view.
            CaptioningManager captioningManager =
                    (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
            mFontScale = captioningManager.getFontScale();
            setCaptionStyle(captioningManager.getUserStyle());
            mCCView.setText("");
            updateWidestChar();
        }

        public void setCaptionStyle(CaptionStyle style) {
            mCaptionStyle = style;
            mCCView.setCaptionStyle(style);
        }

        public void setFontScale(float fontScale) {
            mFontScale = fontScale;
            updateTextSize();
        }

        public int getCaptionWindowId() {
            return mCaptionWindowId;
        }

        public void setCaptionWindowId(int captionWindowId) {
            mCaptionWindowId = captionWindowId;
        }

        public void clear() {
            clearText();
            hide();
        }

        public void show() {
            setVisibility(View.VISIBLE);
            requestLayout();
        }

        public void hide() {
            setVisibility(View.INVISIBLE);
            requestLayout();
        }

        public void setPenAttr(Cea708CCParser.CaptionPenAttr penAttr) {
            mCharacterStyles.clear();
            if (penAttr.italic) {
                mCharacterStyles.add(new StyleSpan(Typeface.ITALIC));
            }
            if (penAttr.underline) {
                mCharacterStyles.add(new UnderlineSpan());
            }
            switch (penAttr.penSize) {
                case Cea708CCParser.CaptionPenAttr.PEN_SIZE_SMALL:
                    mCharacterStyles.add(new RelativeSizeSpan(PROPORTION_PEN_SIZE_SMALL));
                    break;
                case Cea708CCParser.CaptionPenAttr.PEN_SIZE_LARGE:
                    mCharacterStyles.add(new RelativeSizeSpan(PROPORTION_PEN_SIZE_LARGE));
                    break;
            }
            switch (penAttr.penOffset) {
                case Cea708CCParser.CaptionPenAttr.OFFSET_SUBSCRIPT:
                    mCharacterStyles.add(new SubscriptSpan());
                    break;
                case Cea708CCParser.CaptionPenAttr.OFFSET_SUPERSCRIPT:
                    mCharacterStyles.add(new SuperscriptSpan());
                    break;
            }
        }

        public void setPenColor(Cea708CCParser.CaptionPenColor penColor) {
            // TODO: apply pen colors or skip this and use the style of system wide CC style as is.
        }

        public void setPenLocation(int row, int column) {
            // TODO: change the location of pen based on row and column both.
            if (mRow >= 0) {
                for (int r = mRow; r < row; ++r) {
                    appendText("\n");
                }
            }
            mRow = row;
        }

        public void setWindowAttr(Cea708CCParser.CaptionWindowAttr windowAttr) {
            // TODO: apply window attrs or skip this and use the style of system wide CC style as
            // is.
        }

        public void sendBuffer(String buffer) {
            appendText(buffer);
        }

        public void sendControl(char control) {
            // TODO: there are a bunch of ASCII-style control codes.
        }

        /**
         * This method places the window on a given CaptionLayout along with the anchor of the
         * window.
         * <p>
         * According to CEA-708B, the anchor id indicates the gravity of the window as the follows.
         * For example, A value 7 of a anchor id says that a window is align with its parent bottom
         * and is located at the center horizontally of its parent.
         * </p>
         * <h4>Anchor id and the gravity of a window</h4>
         * <table>
         *     <tr>
         *         <th>GRAVITY</th>
         *         <th>LEFT</th>
         *         <th>CENTER_HORIZONTAL</th>
         *         <th>RIGHT</th>
         *     </tr>
         *     <tr>
         *         <th>TOP</th>
         *         <td>0</td>
         *         <td>1</td>
         *         <td>2</td>
         *     </tr>
         *     <tr>
         *         <th>CENTER_VERTICAL</th>
         *         <td>3</td>
         *         <td>4</td>
         *         <td>5</td>
         *     </tr>
         *     <tr>
         *         <th>BOTTOM</th>
         *         <td>6</td>
         *         <td>7</td>
         *         <td>8</td>
         *     </tr>
         * </table>
         * <p>
         * In order to handle the gravity of a window, there are two steps. First, set the size of
         * the window. Since the window will be positioned at ScaledLayout, the size factors are
         * determined in a ratio. Second, set the gravity of the window. CaptionWindowLayout is
         * inherited from RelativeLayout. Hence, we could set the gravity of its child view,
         * SubtitleView.
         * </p>
         * <p>
         * The gravity of the window is also related to its size. When it should be pushed to a one
         * of the end of the window, like LEFT, RIGHT, TOP or BOTTOM, the anchor point should be a
         * boundary of the window. When it should be pushed in the horizontal/vertical center of its
         * container, the horizontal/vertical center point of the window should be the same as the
         * anchor point.
         * </p>
         *
         * @param ccLayout a given CaptionLayout, which contains a safe title area.
         * @param captionWindow a given CaptionWindow, which stores the construction info of the
         *                      window.
         */
        public void initWindow(CCLayout ccLayout, Cea708CCParser.CaptionWindow captionWindow) {
            if (mCCLayout != ccLayout) {
                if (mCCLayout != null) {
                    mCCLayout.removeOnLayoutChangeListener(this);
                }
                mCCLayout = ccLayout;
                mCCLayout.addOnLayoutChangeListener(this);
                updateWidestChar();
            }

            // Both anchor vertical and horizontal indicates the position cell number of the window.
            float scaleRow = (float) captionWindow.anchorVertical /
                    (captionWindow.relativePositioning
                            ? ANCHOR_RELATIVE_POSITIONING_MAX : ANCHOR_VERTICAL_MAX);

            // Assumes it has a wide aspect ratio track.
            float scaleCol = (float) captionWindow.anchorHorizontal /
                    (captionWindow.relativePositioning ? ANCHOR_RELATIVE_POSITIONING_MAX
                            : ANCHOR_HORIZONTAL_16_9_MAX);

            // The range of scaleRow/Col need to be verified to be in [0, 1].
            // Otherwise a RuntimeException will be raised in ScaledLayout.
            if (scaleRow < 0 || scaleRow > 1) {
                Log.i(TAG, "The vertical position of the anchor point should be at the range of 0 "
                        + "and 1 but " + scaleRow);
                scaleRow = Math.max(0, Math.min(scaleRow, 1));
            }
            if (scaleCol < 0 || scaleCol > 1) {
                Log.i(TAG, "The horizontal position of the anchor point should be at the range of 0"
                        + " and 1 but " + scaleCol);
                scaleCol = Math.max(0, Math.min(scaleCol, 1));
            }
            int gravity = Gravity.CENTER;
            int horizontalMode = captionWindow.anchorId % ANCHOR_MODE_DIVIDER;
            int verticalMode = captionWindow.anchorId / ANCHOR_MODE_DIVIDER;
            float scaleStartRow = 0;
            float scaleEndRow = 1;
            float scaleStartCol = 0;
            float scaleEndCol = 1;
            switch (horizontalMode) {
                case ANCHOR_HORIZONTAL_MODE_LEFT:
                    gravity = Gravity.LEFT;
                    mCCView.setAlignment(Alignment.ALIGN_NORMAL);
                    scaleStartCol = scaleCol;
                    break;
                case ANCHOR_HORIZONTAL_MODE_CENTER:
                    float gap = Math.min(1 - scaleCol, scaleCol);

                    // Since all TV sets use left text alignment instead of center text alignment
                    // for this case, we follow the industry convention if possible.
                    int columnCount = captionWindow.columnCount + 1;
                    columnCount = Math.min(getScreenColumnCount(), columnCount);
                    StringBuilder widestTextBuilder = new StringBuilder();
                    for (int i = 0; i < columnCount; ++i) {
                        widestTextBuilder.append(mWidestChar);
                    }
                    Paint paint = new Paint();
                    paint.setTypeface(mCaptionStyle.getTypeface());
                    paint.setTextSize(mTextSize);
                    float maxWindowWidth = paint.measureText(widestTextBuilder.toString());
                    float halfMaxWidthScale = mCCLayout.getWidth() > 0
                            ? maxWindowWidth / 2.0f / (mCCLayout.getWidth() * 0.8f) : 0.0f;
                    if (halfMaxWidthScale > 0f && halfMaxWidthScale < scaleCol) {
                        // Calculate the expected max window size based on the column count of the
                        // caption window multiplied by average alphabets char width, then align the
                        // left side of the window with the left side of the expected max window.
                        gravity = Gravity.LEFT;
                        mCCView.setAlignment(Alignment.ALIGN_NORMAL);
                        scaleStartCol = scaleCol - halfMaxWidthScale;
                        scaleEndCol = 1.0f;
                    } else {
                        // The gap will be the minimum distance value of the distances from both
                        // horizontal end points to the anchor point.
                        // If scaleCol <= 0.5, the range of scaleCol is [0, the anchor point * 2].
                        // If scaleCol > 0.5, the range of scaleCol is
                        // [(1 - the anchor point) * 2, 1].
                        // The anchor point is located at the horizontal center of the window in
                        // both cases.
                        gravity = Gravity.CENTER_HORIZONTAL;
                        mCCView.setAlignment(Alignment.ALIGN_CENTER);
                        scaleStartCol = scaleCol - gap;
                        scaleEndCol = scaleCol + gap;
                    }
                    break;
                case ANCHOR_HORIZONTAL_MODE_RIGHT:
                    gravity = Gravity.RIGHT;
                    mCCView.setAlignment(Alignment.ALIGN_RIGHT);
                    scaleEndCol = scaleCol;
                    break;
            }
            switch (verticalMode) {
                case ANCHOR_VERTICAL_MODE_TOP:
                    gravity |= Gravity.TOP;
                    scaleStartRow = scaleRow;
                    break;
                case ANCHOR_VERTICAL_MODE_CENTER:
                    gravity |= Gravity.CENTER_VERTICAL;

                    // See the above comment.
                    float gap = Math.min(1 - scaleRow, scaleRow);
                    scaleStartRow = scaleRow - gap;
                    scaleEndRow = scaleRow + gap;
                    break;
                case ANCHOR_VERTICAL_MODE_BOTTOM:
                    gravity |= Gravity.BOTTOM;
                    scaleEndRow = scaleRow;
                    break;
            }
            mCCLayout.addOrUpdateViewToSafeTitleArea(this, new ScaledLayout
                    .ScaledLayoutParams(scaleStartRow, scaleEndRow, scaleStartCol, scaleEndCol));
            setCaptionWindowId(captionWindow.id);
            setRowLimit(captionWindow.rowCount);
            setGravity(gravity);
            if (captionWindow.visible) {
                show();
            } else {
                hide();
            }
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            int width = right - left;
            int height = bottom - top;
            if (width != mLastCaptionLayoutWidth || height != mLastCaptionLayoutHeight) {
                mLastCaptionLayoutWidth = width;
                mLastCaptionLayoutHeight = height;
                updateTextSize();
            }
        }

        private void updateWidestChar() {
            Paint paint = new Paint();
            paint.setTypeface(mCaptionStyle.getTypeface());
            Charset latin1 = Charset.forName("ISO-8859-1");
            float widestCharWidth = 0f;
            for (int i = 0; i < 256; ++i) {
                String ch = new String(new byte[]{(byte) i}, latin1);
                float charWidth = paint.measureText(ch);
                if (widestCharWidth < charWidth) {
                    widestCharWidth = charWidth;
                    mWidestChar = ch;
                }
            }
            updateTextSize();
        }

        private void updateTextSize() {
            if (mCCLayout == null) return;

            // Calculate text size based on the max window size.
            StringBuilder widestTextBuilder = new StringBuilder();
            int screenColumnCount = getScreenColumnCount();
            for (int i = 0; i < screenColumnCount; ++i) {
                widestTextBuilder.append(mWidestChar);
            }
            String widestText = widestTextBuilder.toString();
            Paint paint = new Paint();
            paint.setTypeface(mCaptionStyle.getTypeface());
            float startFontSize = 0f;
            float endFontSize = 255f;
            while (startFontSize < endFontSize) {
                float testTextSize = (startFontSize + endFontSize) / 2f;
                paint.setTextSize(testTextSize);
                float width = paint.measureText(widestText);
                if (mCCLayout.getWidth() * 0.8f > width) {
                    startFontSize = testTextSize + 0.01f;
                } else {
                    endFontSize = testTextSize - 0.01f;
                }
            }
            mTextSize = endFontSize * mFontScale;
            mCCView.setTextSize(mTextSize);
        }

        private int getScreenColumnCount() {
            // Assume it has a wide aspect ratio track.
            return MAX_COLUMN_COUNT_16_9;
        }

        public void removeFromCaptionView() {
            if (mCCLayout != null) {
                mCCLayout.removeViewFromSafeTitleArea(this);
                mCCLayout.removeOnLayoutChangeListener(this);
                mCCLayout = null;
            }
        }

        public void setText(String text) {
            updateText(text, false);
        }

        public void appendText(String text) {
            updateText(text, true);
        }

        public void clearText() {
            mBuilder.clear();
            mCCView.setText("");
        }

        private void updateText(String text, boolean appended) {
            if (!appended) {
                mBuilder.clear();
            }
            if (text != null && text.length() > 0) {
                int length = mBuilder.length();
                mBuilder.append(text);
                for (CharacterStyle characterStyle : mCharacterStyles) {
                    mBuilder.setSpan(characterStyle, length, mBuilder.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            String[] lines = TextUtils.split(mBuilder.toString(), "\n");

            // Truncate text not to exceed the row limit.
            // Plus one here since the range of the rows is [0, mRowLimit].
            String truncatedText = TextUtils.join("\n", Arrays.copyOfRange(
                    lines, Math.max(0, lines.length - (mRowLimit + 1)), lines.length));
            mBuilder.delete(0, mBuilder.length() - truncatedText.length());

            // Trim the buffer first then set text to CCView.
            int start = 0, last = mBuilder.length() - 1;
            int end = last;
            while ((start <= end) && (mBuilder.charAt(start) <= ' ')) {
                ++start;
            }
            while ((end >= start) && (mBuilder.charAt(end) <= ' ')) {
                --end;
            }
            if (start == 0 && end == last) {
                mCCView.setText(mBuilder);
            } else {
                SpannableStringBuilder trim = new SpannableStringBuilder();
                trim.append(mBuilder);
                if (end < last) {
                    trim.delete(end + 1, last + 1);
                }
                if (start > 0) {
                    trim.delete(0, start);
                }
                mCCView.setText(trim);
            }
        }

        public void setRowLimit(int rowLimit) {
            if (rowLimit < 0) {
                throw new IllegalArgumentException("A rowLimit should have a positive number");
            }
            mRowLimit = rowLimit;
        }
    }

    /** @hide */
    static class CCView extends SubtitleView {
        private static final CaptionStyle DEFAULT_CAPTION_STYLE = CaptionStyle.DEFAULT;

        public CCView(Context context) {
            this(context, null);
        }

        public CCView(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public CCView(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public CCView(Context context, AttributeSet attrs, int defStyleAttr,
                int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        public void setCaptionStyle(CaptionStyle style) {
            setForegroundColor(style.hasForegroundColor()
                    ? style.foregroundColor : DEFAULT_CAPTION_STYLE.foregroundColor);
            setBackgroundColor(style.hasBackgroundColor()
                    ? style.backgroundColor : DEFAULT_CAPTION_STYLE.backgroundColor);
            setEdgeType(style.hasEdgeType()
                    ? style.edgeType : DEFAULT_CAPTION_STYLE.edgeType);
            setEdgeColor(style.hasEdgeColor()
                    ? style.edgeColor : DEFAULT_CAPTION_STYLE.edgeColor);
            setTypeface(style.getTypeface());
        }
    }
}
