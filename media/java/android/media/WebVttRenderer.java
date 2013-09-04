package android.media;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

/** @hide */
public class WebVttRenderer extends SubtitleController.Renderer {
    private TextView mMyTextView;

    public WebVttRenderer(Context context, AttributeSet attrs) {
        mMyTextView = new WebVttView(context, attrs);
    }

    @Override
    public boolean supports(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_MIME)) {
            return format.getString(MediaFormat.KEY_MIME).equals("text/vtt");
        }
        return false;
    }

    @Override
    public SubtitleTrack createTrack(MediaFormat format) {
        return new WebVttTrack(format, mMyTextView);
    }
}

/** @hide */
class WebVttView extends TextView {
    public WebVttView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setTextColor(0xffffff00);
        setTextSize(46);
        setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
        setLayoutParams(new LayoutParams(
                LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
    }
}

/** @hide */
class TextTrackCueSpan {
    long mTimestampMs;
    boolean mEnabled;
    String mText;
    TextTrackCueSpan(String text, long timestamp) {
        mTimestampMs = timestamp;
        mText = text;
        // spans with timestamp will be enabled by Cue.onTime
        mEnabled = (mTimestampMs < 0);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TextTrackCueSpan)) {
            return false;
        }
        TextTrackCueSpan span = (TextTrackCueSpan) o;
        return mTimestampMs == span.mTimestampMs &&
                mText.equals(span.mText);
    }
}

/**
 * @hide
 *
 * Extract all text without style, but with timestamp spans.
 */
class UnstyledTextExtractor implements Tokenizer.OnTokenListener {
    StringBuilder mLine = new StringBuilder();
    Vector<TextTrackCueSpan[]> mLines = new Vector<TextTrackCueSpan[]>();
    Vector<TextTrackCueSpan> mCurrentLine = new Vector<TextTrackCueSpan>();
    long mLastTimestamp;

    UnstyledTextExtractor() {
        init();
    }

    private void init() {
        mLine.delete(0, mLine.length());
        mLines.clear();
        mCurrentLine.clear();
        mLastTimestamp = -1;
    }

    @Override
    public void onData(String s) {
        mLine.append(s);
    }

    @Override
    public void onStart(String tag, String[] classes, String annotation) { }

    @Override
    public void onEnd(String tag) { }

    @Override
    public void onTimeStamp(long timestampMs) {
        // finish any prior span
        if (mLine.length() > 0 && timestampMs != mLastTimestamp) {
            mCurrentLine.add(
                    new TextTrackCueSpan(mLine.toString(), mLastTimestamp));
            mLine.delete(0, mLine.length());
        }
        mLastTimestamp = timestampMs;
    }

    @Override
    public void onLineEnd() {
        // finish any pending span
        if (mLine.length() > 0) {
            mCurrentLine.add(
                    new TextTrackCueSpan(mLine.toString(), mLastTimestamp));
            mLine.delete(0, mLine.length());
        }

        TextTrackCueSpan[] spans = new TextTrackCueSpan[mCurrentLine.size()];
        mCurrentLine.toArray(spans);
        mCurrentLine.clear();
        mLines.add(spans);
    }

    public TextTrackCueSpan[][] getText() {
        // for politeness, finish last cue-line if it ends abruptly
        if (mLine.length() > 0 || mCurrentLine.size() > 0) {
            onLineEnd();
        }
        TextTrackCueSpan[][] lines = new TextTrackCueSpan[mLines.size()][];
        mLines.toArray(lines);
        init();
        return lines;
    }
}

/**
 * @hide
 *
 * Tokenizer tokenizes the WebVTT Cue Text into tags and data
 */
class Tokenizer {
    private static final String TAG = "Tokenizer";
    private TokenizerPhase mPhase;
    private TokenizerPhase mDataTokenizer;
    private TokenizerPhase mTagTokenizer;

    private OnTokenListener mListener;
    private String mLine;
    private int mHandledLen;

    interface TokenizerPhase {
        TokenizerPhase start();
        void tokenize();
    }

    class DataTokenizer implements TokenizerPhase {
        // includes both WebVTT data && escape state
        private StringBuilder mData;

        public TokenizerPhase start() {
            mData = new StringBuilder();
            return this;
        }

        private boolean replaceEscape(String escape, String replacement, int pos) {
            if (mLine.startsWith(escape, pos)) {
                mData.append(mLine.substring(mHandledLen, pos));
                mData.append(replacement);
                mHandledLen = pos + escape.length();
                pos = mHandledLen - 1;
                return true;
            }
            return false;
        }

        @Override
        public void tokenize() {
            int end = mLine.length();
            for (int pos = mHandledLen; pos < mLine.length(); pos++) {
                if (mLine.charAt(pos) == '&') {
                    if (replaceEscape("&amp;", "&", pos) ||
                            replaceEscape("&lt;", "<", pos) ||
                            replaceEscape("&gt;", ">", pos) ||
                            replaceEscape("&lrm;", "\u200e", pos) ||
                            replaceEscape("&rlm;", "\u200f", pos) ||
                            replaceEscape("&nbsp;", "\u00a0", pos)) {
                        continue;
                    }
                } else if (mLine.charAt(pos) == '<') {
                    end = pos;
                    mPhase = mTagTokenizer.start();
                    break;
                }
            }
            mData.append(mLine.substring(mHandledLen, end));
            // yield mData
            mListener.onData(mData.toString());
            mData.delete(0, mData.length());
            mHandledLen = end;
        }
    }

    class TagTokenizer implements TokenizerPhase {
        private boolean mAtAnnotation;
        private String mName, mAnnotation;

        public TokenizerPhase start() {
            mName = mAnnotation = "";
            mAtAnnotation = false;
            return this;
        }

        @Override
        public void tokenize() {
            if (!mAtAnnotation)
                mHandledLen++;
            if (mHandledLen < mLine.length()) {
                String[] parts;
                /**
                 * Collect annotations and end-tags to closing >.  Collect tag
                 * name to closing bracket or next white-space.
                 */
                if (mAtAnnotation || mLine.charAt(mHandledLen) == '/') {
                    parts = mLine.substring(mHandledLen).split(">");
                } else {
                    parts = mLine.substring(mHandledLen).split("[\t\f >]");
                }
                String part = mLine.substring(
                            mHandledLen, mHandledLen + parts[0].length());
                mHandledLen += parts[0].length();

                if (mAtAnnotation) {
                    mAnnotation += " " + part;
                } else {
                    mName = part;
                }
            }

            mAtAnnotation = true;

            if (mHandledLen < mLine.length() && mLine.charAt(mHandledLen) == '>') {
                yield_tag();
                mPhase = mDataTokenizer.start();
                mHandledLen++;
            }
        }

        private void yield_tag() {
            if (mName.startsWith("/")) {
                mListener.onEnd(mName.substring(1));
            } else if (mName.length() > 0 && Character.isDigit(mName.charAt(0))) {
                // timestamp
                try {
                    long timestampMs = WebVttParser.parseTimestampMs(mName);
                    mListener.onTimeStamp(timestampMs);
                } catch (NumberFormatException e) {
                    Log.d(TAG, "invalid timestamp tag: <" + mName + ">");
                }
            } else {
                mAnnotation = mAnnotation.replaceAll("\\s+", " ");
                if (mAnnotation.startsWith(" ")) {
                    mAnnotation = mAnnotation.substring(1);
                }
                if (mAnnotation.endsWith(" ")) {
                    mAnnotation = mAnnotation.substring(0, mAnnotation.length() - 1);
                }

                String[] classes = null;
                int dotAt = mName.indexOf('.');
                if (dotAt >= 0) {
                    classes = mName.substring(dotAt + 1).split("\\.");
                    mName = mName.substring(0, dotAt);
                }
                mListener.onStart(mName, classes, mAnnotation);
            }
        }
    }

    Tokenizer(OnTokenListener listener) {
        mDataTokenizer = new DataTokenizer();
        mTagTokenizer = new TagTokenizer();
        reset();
        mListener = listener;
    }

    void reset() {
        mPhase = mDataTokenizer.start();
    }

    void tokenize(String s) {
        mHandledLen = 0;
        mLine = s;
        while (mHandledLen < mLine.length()) {
            mPhase.tokenize();
        }
        /* we are finished with a line unless we are in the middle of a tag */
        if (!(mPhase instanceof TagTokenizer)) {
            // yield END-OF-LINE
            mListener.onLineEnd();
        }
    }

    interface OnTokenListener {
        void onData(String s);
        void onStart(String tag, String[] classes, String annotation);
        void onEnd(String tag);
        void onTimeStamp(long timestampMs);
        void onLineEnd();
    }
}

/** @hide */
class TextTrackRegion {
    final static int SCROLL_VALUE_NONE      = 300;
    final static int SCROLL_VALUE_SCROLL_UP = 301;

    String mId;
    float mWidth;
    int mLines;
    float mAnchorPointX, mAnchorPointY;
    float mViewportAnchorPointX, mViewportAnchorPointY;
    int mScrollValue;

    TextTrackRegion() {
        mId = "";
        mWidth = 100;
        mLines = 3;
        mAnchorPointX = mViewportAnchorPointX = 0.f;
        mAnchorPointY = mViewportAnchorPointY = 100.f;
        mScrollValue = SCROLL_VALUE_NONE;
    }

    public String toString() {
        StringBuilder res = new StringBuilder(" {id:\"").append(mId)
            .append("\", width:").append(mWidth)
            .append(", lines:").append(mLines)
            .append(", anchorPoint:(").append(mAnchorPointX)
            .append(", ").append(mAnchorPointY)
            .append("), viewportAnchorPoints:").append(mViewportAnchorPointX)
            .append(", ").append(mViewportAnchorPointY)
            .append("), scrollValue:")
            .append(mScrollValue == SCROLL_VALUE_NONE ? "none" :
                    mScrollValue == SCROLL_VALUE_SCROLL_UP ? "scroll_up" :
                    "INVALID")
            .append("}");
        return res.toString();
    }
}

/** @hide */
class TextTrackCue extends SubtitleTrack.Cue {
    final static int WRITING_DIRECTION_HORIZONTAL  = 100;
    final static int WRITING_DIRECTION_VERTICAL_RL = 101;
    final static int WRITING_DIRECTION_VERTICAL_LR = 102;

    final static int ALIGNMENT_MIDDLE = 200;
    final static int ALIGNMENT_START  = 201;
    final static int ALIGNMENT_END    = 202;
    final static int ALIGNMENT_LEFT   = 203;
    final static int ALIGNMENT_RIGHT  = 204;
    private static final String TAG = "TTCue";

    String  mId;
    boolean mPauseOnExit;
    int     mWritingDirection;
    String  mRegionId;
    boolean mSnapToLines;
    Integer mLinePosition;  // null means AUTO
    boolean mAutoLinePosition;
    int     mTextPosition;
    int     mSize;
    int     mAlignment;
    // Vector<String> mText;
    String[] mStrings;
    TextTrackCueSpan[][] mLines;
    TextTrackRegion mRegion;

    TextTrackCue() {
        mId = "";
        mPauseOnExit = false;
        mWritingDirection = WRITING_DIRECTION_HORIZONTAL;
        mRegionId = "";
        mSnapToLines = true;
        mLinePosition = null /* AUTO */;
        mTextPosition = 50;
        mSize = 100;
        mAlignment = ALIGNMENT_MIDDLE;
        mLines = null;
        mRegion = null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TextTrackCue)) {
            return false;
        }
        if (this == o) {
            return true;
        }

        try {
            TextTrackCue cue = (TextTrackCue) o;
            boolean res = mId.equals(cue.mId) &&
                    mPauseOnExit == cue.mPauseOnExit &&
                    mWritingDirection == cue.mWritingDirection &&
                    mRegionId.equals(cue.mRegionId) &&
                    mSnapToLines == cue.mSnapToLines &&
                    mAutoLinePosition == cue.mAutoLinePosition &&
                    (mAutoLinePosition || mLinePosition == cue.mLinePosition) &&
                    mTextPosition == cue.mTextPosition &&
                    mSize == cue.mSize &&
                    mAlignment == cue.mAlignment &&
                    mLines.length == cue.mLines.length;
            if (res == true) {
                for (int line = 0; line < mLines.length; line++) {
                    if (!Arrays.equals(mLines[line], cue.mLines[line])) {
                        return false;
                    }
                }
            }
            return res;
        } catch(IncompatibleClassChangeError e) {
            return false;
        }
    }

    public StringBuilder appendStringsToBuilder(StringBuilder builder) {
        if (mStrings == null) {
            builder.append("null");
        } else {
            builder.append("[");
            boolean first = true;
            for (String s: mStrings) {
                if (!first) {
                    builder.append(", ");
                }
                if (s == null) {
                    builder.append("null");
                } else {
                    builder.append("\"");
                    builder.append(s);
                    builder.append("\"");
                }
                first = false;
            }
            builder.append("]");
        }
        return builder;
    }

    public StringBuilder appendLinesToBuilder(StringBuilder builder) {
        if (mLines == null) {
            builder.append("null");
        } else {
            builder.append("[");
            boolean first = true;
            for (TextTrackCueSpan[] spans: mLines) {
                if (!first) {
                    builder.append(", ");
                }
                if (spans == null) {
                    builder.append("null");
                } else {
                    builder.append("\"");
                    boolean innerFirst = true;
                    long lastTimestamp = -1;
                    for (TextTrackCueSpan span: spans) {
                        if (!innerFirst) {
                            builder.append(" ");
                        }
                        if (span.mTimestampMs != lastTimestamp) {
                            builder.append("<")
                                    .append(WebVttParser.timeToString(
                                            span.mTimestampMs))
                                    .append(">");
                            lastTimestamp = span.mTimestampMs;
                        }
                        builder.append(span.mText);
                        innerFirst = false;
                    }
                    builder.append("\"");
                }
                first = false;
            }
            builder.append("]");
        }
        return builder;
    }

    public String toString() {
        StringBuilder res = new StringBuilder();

        res.append(WebVttParser.timeToString(mStartTimeMs))
                .append(" --> ").append(WebVttParser.timeToString(mEndTimeMs))
                .append(" {id:\"").append(mId)
                .append("\", pauseOnExit:").append(mPauseOnExit)
                .append(", direction:")
                .append(mWritingDirection == WRITING_DIRECTION_HORIZONTAL ? "horizontal" :
                        mWritingDirection == WRITING_DIRECTION_VERTICAL_LR ? "vertical_lr" :
                        mWritingDirection == WRITING_DIRECTION_VERTICAL_RL ? "vertical_rl" :
                        "INVALID")
                .append(", regionId:\"").append(mRegionId)
                .append("\", snapToLines:").append(mSnapToLines)
                .append(", linePosition:").append(mAutoLinePosition ? "auto" :
                                                  mLinePosition)
                .append(", textPosition:").append(mTextPosition)
                .append(", size:").append(mSize)
                .append(", alignment:")
                .append(mAlignment == ALIGNMENT_END ? "end" :
                        mAlignment == ALIGNMENT_LEFT ? "left" :
                        mAlignment == ALIGNMENT_MIDDLE ? "middle" :
                        mAlignment == ALIGNMENT_RIGHT ? "right" :
                        mAlignment == ALIGNMENT_START ? "start" : "INVALID")
                .append(", text:");
        appendStringsToBuilder(res).append("}");
        return res.toString();
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public void onTime(long timeMs) {
        for (TextTrackCueSpan[] line: mLines) {
            for (TextTrackCueSpan span: line) {
                span.mEnabled = timeMs >= span.mTimestampMs;
            }
        }
    }
}

/** @hide */
class WebVttParser {
    private static final String TAG = "WebVttParser";
    private Phase mPhase;
    private TextTrackCue mCue;
    private Vector<String> mCueTexts;
    private WebVttCueListener mListener;
    private String mBuffer;

    WebVttParser(WebVttCueListener listener) {
        mPhase = mParseStart;
        mBuffer = "";   /* mBuffer contains up to 1 incomplete line */
        mListener = listener;
        mCueTexts = new Vector<String>();
    }

    /* parsePercentageString */
    public static float parseFloatPercentage(String s)
            throws NumberFormatException {
        if (!s.endsWith("%")) {
            throw new NumberFormatException("does not end in %");
        }
        s = s.substring(0, s.length() - 1);
        // parseFloat allows an exponent or a sign
        if (s.matches(".*[^0-9.].*")) {
            throw new NumberFormatException("contains an invalid character");
        }

        try {
            float value = Float.parseFloat(s);
            if (value < 0.0f || value > 100.0f) {
                throw new NumberFormatException("is out of range");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new NumberFormatException("is not a number");
        }
    }

    public static int parseIntPercentage(String s) throws NumberFormatException {
        if (!s.endsWith("%")) {
            throw new NumberFormatException("does not end in %");
        }
        s = s.substring(0, s.length() - 1);
        // parseInt allows "-0" that returns 0, so check for non-digits
        if (s.matches(".*[^0-9].*")) {
            throw new NumberFormatException("contains an invalid character");
        }

        try {
            int value = Integer.parseInt(s);
            if (value < 0 || value > 100) {
                throw new NumberFormatException("is out of range");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new NumberFormatException("is not a number");
        }
    }

    public static long parseTimestampMs(String s) throws NumberFormatException {
        if (!s.matches("(\\d+:)?[0-5]\\d:[0-5]\\d\\.\\d{3}")) {
            throw new NumberFormatException("has invalid format");
        }

        String[] parts = s.split("\\.", 2);
        long value = 0;
        for (String group: parts[0].split(":")) {
            value = value * 60 + Long.parseLong(group);
        }
        return value * 1000 + Long.parseLong(parts[1]);
    }

    public static String timeToString(long timeMs) {
        return String.format("%d:%02d:%02d.%03d",
                timeMs / 3600000, (timeMs / 60000) % 60,
                (timeMs / 1000) % 60, timeMs % 1000);
    }

    public void parse(String s) {
        boolean trailingCR = false;
        mBuffer = (mBuffer + s.replace("\0", "\ufffd")).replace("\r\n", "\n");

        /* keep trailing '\r' in case matching '\n' arrives in next packet */
        if (mBuffer.endsWith("\r")) {
            trailingCR = true;
            mBuffer = mBuffer.substring(0, mBuffer.length() - 1);
        }

        String[] lines = mBuffer.split("[\r\n]");
        for (int i = 0; i < lines.length - 1; i++) {
            mPhase.parse(lines[i]);
        }

        mBuffer = lines[lines.length - 1];
        if (trailingCR)
            mBuffer += "\r";
    }

    public void eos() {
        if (mBuffer.endsWith("\r")) {
            mBuffer = mBuffer.substring(0, mBuffer.length() - 1);
        }

        mPhase.parse(mBuffer);
        mBuffer = "";

        yieldCue();
        mPhase = mParseStart;
    }

    public void yieldCue() {
        if (mCue != null && mCueTexts.size() > 0) {
            mCue.mStrings = new String[mCueTexts.size()];
            mCueTexts.toArray(mCue.mStrings);
            mCueTexts.clear();
            mListener.onCueParsed(mCue);
        }
        mCue = null;
    }

    interface Phase {
        void parse(String line);
    }

    final private Phase mSkipRest = new Phase() {
        @Override
        public void parse(String line) { }
    };

    final private Phase mParseStart = new Phase() { // 5-9
        @Override
        public void parse(String line) {
            if (!line.equals("WEBVTT") &&
                    !line.startsWith("WEBVTT ") &&
                    !line.startsWith("WEBVTT\t")) {
                log_warning("Not a WEBVTT header", line);
                mPhase = mSkipRest;
            } else {
                mPhase = mParseHeader;
            }
        }
    };

    final private Phase mParseHeader = new Phase() { // 10-13
        TextTrackRegion parseRegion(String s) {
            TextTrackRegion region = new TextTrackRegion();
            for (String setting: s.split(" +")) {
                int equalAt = setting.indexOf('=');
                if (equalAt <= 0 || equalAt == setting.length() - 1) {
                    continue;
                }

                String name = setting.substring(0, equalAt);
                String value = setting.substring(equalAt + 1);
                if (name.equals("id")) {
                    region.mId = value;
                } else if (name.equals("width")) {
                    try {
                        region.mWidth = parseFloatPercentage(value);
                    } catch (NumberFormatException e) {
                        log_warning("region setting", name,
                                "has invalid value", e.getMessage(), value);
                    }
                } else if (name.equals("lines")) {
                    try {
                        int lines = Integer.parseInt(value);
                        if (lines >= 0) {
                            region.mLines = lines;
                        } else {
                            log_warning("region setting", name, "is negative", value);
                        }
                    } catch (NumberFormatException e) {
                        log_warning("region setting", name, "is not numeric", value);
                    }
                } else if (name.equals("regionanchor") ||
                           name.equals("viewportanchor")) {
                    int commaAt = value.indexOf(",");
                    if (commaAt < 0) {
                        log_warning("region setting", name, "contains no comma", value);
                        continue;
                    }

                    String anchorX = value.substring(0, commaAt);
                    String anchorY = value.substring(commaAt + 1);
                    float x, y;

                    try {
                        x = parseFloatPercentage(anchorX);
                    } catch (NumberFormatException e) {
                        log_warning("region setting", name,
                                "has invalid x component", e.getMessage(), anchorX);
                        continue;
                    }
                    try {
                        y = parseFloatPercentage(anchorY);
                    } catch (NumberFormatException e) {
                        log_warning("region setting", name,
                                "has invalid y component", e.getMessage(), anchorY);
                        continue;
                    }

                    if (name.charAt(0) == 'r') {
                        region.mAnchorPointX = x;
                        region.mAnchorPointY = y;
                    } else {
                        region.mViewportAnchorPointX = x;
                        region.mViewportAnchorPointY = y;
                    }
                } else if (name.equals("scroll")) {
                    if (value.equals("up")) {
                        region.mScrollValue =
                            TextTrackRegion.SCROLL_VALUE_SCROLL_UP;
                    } else {
                        log_warning("region setting", name, "has invalid value", value);
                    }
                }
            }
            return region;
        }

        @Override
        public void parse(String line)  {
            if (line.length() == 0) {
                mPhase = mParseCueId;
            } else if (line.contains("-->")) {
                mPhase = mParseCueTime;
                mPhase.parse(line);
            } else {
                int colonAt = line.indexOf(':');
                if (colonAt <= 0 || colonAt >= line.length() - 1) {
                    log_warning("meta data header has invalid format", line);
                }
                String name = line.substring(0, colonAt);
                String value = line.substring(colonAt + 1);

                if (name.equals("Region")) {
                    TextTrackRegion region = parseRegion(value);
                    mListener.onRegionParsed(region);
                }
            }
        }
    };

    final private Phase mParseCueId = new Phase() {
        @Override
        public void parse(String line) {
            if (line.length() == 0) {
                return;
            }

            assert(mCue == null);

            if (line.equals("NOTE") || line.startsWith("NOTE ")) {
                mPhase = mParseCueText;
            }

            mCue = new TextTrackCue();
            mCueTexts.clear();

            mPhase = mParseCueTime;
            if (line.contains("-->")) {
                mPhase.parse(line);
            } else {
                mCue.mId = line;
            }
        }
    };

    final private Phase mParseCueTime = new Phase() {
        @Override
        public void parse(String line) {
            int arrowAt = line.indexOf("-->");
            if (arrowAt < 0) {
                mCue = null;
                mPhase = mParseCueId;
                return;
            }

            String start = line.substring(0, arrowAt).trim();
            // convert only initial and first other white-space to space
            String rest = line.substring(arrowAt + 3)
                    .replaceFirst("^\\s+", "").replaceFirst("\\s+", " ");
            int spaceAt = rest.indexOf(' ');
            String end = spaceAt > 0 ? rest.substring(0, spaceAt) : rest;
            rest = spaceAt > 0 ? rest.substring(spaceAt + 1) : "";

            mCue.mStartTimeMs = parseTimestampMs(start);
            mCue.mEndTimeMs = parseTimestampMs(end);
            for (String setting: rest.split(" +")) {
                int colonAt = setting.indexOf(':');
                if (colonAt <= 0 || colonAt == setting.length() - 1) {
                    continue;
                }
                String name = setting.substring(0, colonAt);
                String value = setting.substring(colonAt + 1);

                if (name.equals("region")) {
                    mCue.mRegionId = value;
                } else if (name.equals("vertical")) {
                    if (value.equals("rl")) {
                        mCue.mWritingDirection =
                            TextTrackCue.WRITING_DIRECTION_VERTICAL_RL;
                    } else if (value.equals("lr")) {
                        mCue.mWritingDirection =
                            TextTrackCue.WRITING_DIRECTION_VERTICAL_LR;
                    } else {
                        log_warning("cue setting", name, "has invalid value", value);
                    }
                } else if (name.equals("line")) {
                    try {
                        int linePosition;
                        /* TRICKY: we know that there are no spaces in value */
                        assert(value.indexOf(' ') < 0);
                        if (value.endsWith("%")) {
                            linePosition = Integer.parseInt(
                                    value.substring(0, value.length() - 1));
                            if (linePosition < 0 || linePosition > 100) {
                                log_warning("cue setting", name, "is out of range", value);
                                continue;
                            }
                            mCue.mSnapToLines = false;
                            mCue.mLinePosition = linePosition;
                        } else {
                            mCue.mSnapToLines = true;
                            mCue.mLinePosition = Integer.parseInt(value);
                        }
                    } catch (NumberFormatException e) {
                        log_warning("cue setting", name,
                               "is not numeric or percentage", value);
                    }
                } else if (name.equals("position")) {
                    try {
                        mCue.mTextPosition = parseIntPercentage(value);
                    } catch (NumberFormatException e) {
                        log_warning("cue setting", name,
                               "is not numeric or percentage", value);
                    }
                } else if (name.equals("size")) {
                    try {
                        mCue.mSize = parseIntPercentage(value);
                    } catch (NumberFormatException e) {
                        log_warning("cue setting", name,
                               "is not numeric or percentage", value);
                    }
                } else if (name.equals("align")) {
                    if (value.equals("start")) {
                        mCue.mAlignment = TextTrackCue.ALIGNMENT_START;
                    } else if (value.equals("middle")) {
                        mCue.mAlignment = TextTrackCue.ALIGNMENT_MIDDLE;
                    } else if (value.equals("end")) {
                        mCue.mAlignment = TextTrackCue.ALIGNMENT_END;
                    } else if (value.equals("left")) {
                        mCue.mAlignment = TextTrackCue.ALIGNMENT_LEFT;
                    } else if (value.equals("right")) {
                        mCue.mAlignment = TextTrackCue.ALIGNMENT_RIGHT;
                    } else {
                        log_warning("cue setting", name, "has invalid value", value);
                        continue;
                    }
                }
            }

            if (mCue.mLinePosition != null ||
                    mCue.mSize != 100 ||
                    (mCue.mWritingDirection !=
                        TextTrackCue.WRITING_DIRECTION_HORIZONTAL)) {
                mCue.mRegionId = "";
            }

            mPhase = mParseCueText;
        }
    };

    /* also used for notes */
    final private Phase mParseCueText = new Phase() {
        @Override
        public void parse(String line) {
            if (line.length() == 0) {
                yieldCue();
                mPhase = mParseCueId;
                return;
            } else if (mCue != null) {
                mCueTexts.add(line);
            }
        }
    };

    private void log_warning(
            String nameType, String name, String message,
            String subMessage, String value) {
        Log.w(this.getClass().getName(), nameType + " '" + name + "' " +
                message + " ('" + value + "' " + subMessage + ")");
    }

    private void log_warning(
            String nameType, String name, String message, String value) {
        Log.w(this.getClass().getName(), nameType + " '" + name + "' " +
                message + " ('" + value + "')");
    }

    private void log_warning(String message, String value) {
        Log.w(this.getClass().getName(), message + " ('" + value + "')");
    }
}

/** @hide */
interface WebVttCueListener {
    void onCueParsed(TextTrackCue cue);
    void onRegionParsed(TextTrackRegion region);
}

/** @hide */
class WebVttTrack extends SubtitleTrack implements WebVttCueListener {
    private static final String TAG = "WebVttTrack";

    private final TextView mTextView;

    private final WebVttParser mParser = new WebVttParser(this);
    private final UnstyledTextExtractor mExtractor =
        new UnstyledTextExtractor();
    private final Tokenizer mTokenizer = new Tokenizer(mExtractor);
    private final Vector<Long> mTimestamps = new Vector<Long>();

    private final Map<String, TextTrackRegion> mRegions =
        new HashMap<String, TextTrackRegion>();
    private Long mCurrentRunID;

    WebVttTrack(MediaFormat format, TextView textView) {
        super(format);
        mTextView = textView;
    }

    @Override
    public View getView() {
        return mTextView;
    }

    @Override
    public void onData(String data, boolean eos, long runID) {
        // implement intermixing restriction for WebVTT only for now
        synchronized(mParser) {
            if (mCurrentRunID != null && runID != mCurrentRunID) {
                throw new IllegalStateException(
                        "Run #" + mCurrentRunID +
                        " in progress.  Cannot process run #" + runID);
            }
            mCurrentRunID = runID;
            mParser.parse(data);
            if (eos) {
                finishedRun(runID);
                mParser.eos();
                mRegions.clear();
                mCurrentRunID = null;
            }
        }
    }

    @Override
    public void onCueParsed(TextTrackCue cue) {
        synchronized (mParser) {
            // resolve region
            if (cue.mRegionId.length() != 0) {
                cue.mRegion = mRegions.get(cue.mRegionId);
            }

            if (DEBUG) Log.v(TAG, "adding cue " + cue);

            // tokenize text track string-lines into lines of spans
            mTokenizer.reset();
            for (String s: cue.mStrings) {
                mTokenizer.tokenize(s);
            }
            cue.mLines = mExtractor.getText();
            if (DEBUG) Log.v(TAG, cue.appendLinesToBuilder(
                    cue.appendStringsToBuilder(
                        new StringBuilder()).append(" simplified to: "))
                            .toString());

            // extract inner timestamps
            for (TextTrackCueSpan[] line: cue.mLines) {
                for (TextTrackCueSpan span: line) {
                    if (span.mTimestampMs > cue.mStartTimeMs &&
                            span.mTimestampMs < cue.mEndTimeMs &&
                            !mTimestamps.contains(span.mTimestampMs)) {
                        mTimestamps.add(span.mTimestampMs);
                    }
                }
            }

            if (mTimestamps.size() > 0) {
                cue.mInnerTimesMs = new long[mTimestamps.size()];
                for (int ix=0; ix < mTimestamps.size(); ++ix) {
                    cue.mInnerTimesMs[ix] = mTimestamps.get(ix);
                }
                mTimestamps.clear();
            } else {
                cue.mInnerTimesMs = null;
            }

            cue.mRunID = mCurrentRunID;
        }

        addCue(cue);
    }

    @Override
    public void onRegionParsed(TextTrackRegion region) {
        synchronized(mParser) {
            mRegions.put(region.mId, region);
        }
    }

    public void updateView(Vector<SubtitleTrack.Cue> activeCues) {
        if (!mVisible) {
            // don't keep the state if we are not visible
            return;
        }

        if (DEBUG && mTimeProvider != null) {
            try {
                Log.d(TAG, "at " +
                        (mTimeProvider.getCurrentTimeUs(false, true) / 1000) +
                        " ms the active cues are:");
            } catch (IllegalStateException e) {
                Log.d(TAG, "at (illegal state) the active cues are:");
            }
        }
        StringBuilder text = new StringBuilder();
        StringBuilder lineBuilder = new StringBuilder();
        for (Cue o: activeCues) {
            TextTrackCue cue = (TextTrackCue)o;
            if (DEBUG) Log.d(TAG, cue.toString());
            for (TextTrackCueSpan[] line: cue.mLines) {
                for (TextTrackCueSpan span: line) {
                    if (!span.mEnabled) {
                        continue;
                    }
                    lineBuilder.append(span.mText);
                }
                if (lineBuilder.length() > 0) {
                    text.append(lineBuilder.toString()).append("\n");
                    lineBuilder.delete(0, lineBuilder.length());
                }
            }
        }

        if (mTextView != null) {
            if (DEBUG) Log.d(TAG, "updating to " + text.toString());
            mTextView.setText(text.toString());
            mTextView.postInvalidate();
        }
    }
}
