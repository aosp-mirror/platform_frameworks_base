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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.CaptioningManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** @hide */
public class TtmlRenderer extends SubtitleController.Renderer {
    private final Context mContext;

    private static final String MEDIA_MIMETYPE_TEXT_TTML = "application/ttml+xml";

    private TtmlRenderingWidget mRenderingWidget;

    @UnsupportedAppUsage
    public TtmlRenderer(Context context) {
        mContext = context;
    }

    @Override
    public boolean supports(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_MIME)) {
            return format.getString(MediaFormat.KEY_MIME).equals(MEDIA_MIMETYPE_TEXT_TTML);
        }
        return false;
    }

    @Override
    public SubtitleTrack createTrack(MediaFormat format) {
        if (mRenderingWidget == null) {
            mRenderingWidget = new TtmlRenderingWidget(mContext);
        }
        return new TtmlTrack(mRenderingWidget, format);
    }
}

/**
 * A class which provides utillity methods for TTML parsing.
 *
 * @hide
 */
final class TtmlUtils {
    public static final String TAG_TT = "tt";
    public static final String TAG_HEAD = "head";
    public static final String TAG_BODY = "body";
    public static final String TAG_DIV = "div";
    public static final String TAG_P = "p";
    public static final String TAG_SPAN = "span";
    public static final String TAG_BR = "br";
    public static final String TAG_STYLE = "style";
    public static final String TAG_STYLING = "styling";
    public static final String TAG_LAYOUT = "layout";
    public static final String TAG_REGION = "region";
    public static final String TAG_METADATA = "metadata";
    public static final String TAG_SMPTE_IMAGE = "smpte:image";
    public static final String TAG_SMPTE_DATA = "smpte:data";
    public static final String TAG_SMPTE_INFORMATION = "smpte:information";
    public static final String PCDATA = "#pcdata";
    public static final String ATTR_BEGIN = "begin";
    public static final String ATTR_DURATION = "dur";
    public static final String ATTR_END = "end";
    public static final long INVALID_TIMESTAMP = Long.MAX_VALUE;

    /**
     * Time expression RE according to the spec:
     * http://www.w3.org/TR/ttaf1-dfxp/#timing-value-timeExpression
     */
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "^([0-9][0-9]+):([0-9][0-9]):([0-9][0-9])"
            + "(?:(\\.[0-9]+)|:([0-9][0-9])(?:\\.([0-9]+))?)?$");

    private static final Pattern OFFSET_TIME = Pattern.compile(
            "^([0-9]+(?:\\.[0-9]+)?)(h|m|s|ms|f|t)$");

    private TtmlUtils() {
    }

    /**
     * Parses the given time expression and returns a timestamp in millisecond.
     * <p>
     * For the format of the time expression, please refer <a href=
     * "http://www.w3.org/TR/ttaf1-dfxp/#timing-value-timeExpression">timeExpression</a>
     *
     * @param time A string which includes time expression.
     * @param frameRate the framerate of the stream.
     * @param subframeRate the sub-framerate of the stream
     * @param tickRate the tick rate of the stream.
     * @return the parsed timestamp in micro-second.
     * @throws NumberFormatException if the given string does not match to the
     *             format.
     */
    public static long parseTimeExpression(String time, int frameRate, int subframeRate,
            int tickRate) throws NumberFormatException {
        Matcher matcher = CLOCK_TIME.matcher(time);
        if (matcher.matches()) {
            String hours = matcher.group(1);
            double durationSeconds = Long.parseLong(hours) * 3600;
            String minutes = matcher.group(2);
            durationSeconds += Long.parseLong(minutes) * 60;
            String seconds = matcher.group(3);
            durationSeconds += Long.parseLong(seconds);
            String fraction = matcher.group(4);
            durationSeconds += (fraction != null) ? Double.parseDouble(fraction) : 0;
            String frames = matcher.group(5);
            durationSeconds += (frames != null) ? ((double)Long.parseLong(frames)) / frameRate : 0;
            String subframes = matcher.group(6);
            durationSeconds += (subframes != null) ? ((double)Long.parseLong(subframes))
                    / subframeRate / frameRate
                    : 0;
            return (long)(durationSeconds * 1000);
        }
        matcher = OFFSET_TIME.matcher(time);
        if (matcher.matches()) {
            String timeValue = matcher.group(1);
            double value = Double.parseDouble(timeValue);
            String unit = matcher.group(2);
            if (unit.equals("h")) {
                value *= 3600L * 1000000L;
            } else if (unit.equals("m")) {
                value *= 60 * 1000000;
            } else if (unit.equals("s")) {
                value *= 1000000;
            } else if (unit.equals("ms")) {
                value *= 1000;
            } else if (unit.equals("f")) {
                value = value / frameRate * 1000000;
            } else if (unit.equals("t")) {
                value = value / tickRate * 1000000;
            }
            return (long)value;
        }
        throw new NumberFormatException("Malformed time expression : " + time);
    }

    /**
     * Applies <a href
     * src="http://www.w3.org/TR/ttaf1-dfxp/#content-attribute-space">the
     * default space policy</a> to the given string.
     *
     * @param in A string to apply the policy.
     */
    public static String applyDefaultSpacePolicy(String in) {
        return applySpacePolicy(in, true);
    }

    /**
     * Applies the space policy to the given string. This applies <a href
     * src="http://www.w3.org/TR/ttaf1-dfxp/#content-attribute-space">the
     * default space policy</a> with linefeed-treatment as treat-as-space
     * or preserve.
     *
     * @param in A string to apply the policy.
     * @param treatLfAsSpace Whether convert line feeds to spaces or not.
     */
    public static String applySpacePolicy(String in, boolean treatLfAsSpace) {
        // Removes CR followed by LF. ref:
        // http://www.w3.org/TR/xml/#sec-line-ends
        String crRemoved = in.replaceAll("\r\n", "\n");
        // Apply suppress-at-line-break="auto" and
        // white-space-treatment="ignore-if-surrounding-linefeed"
        String spacesNeighboringLfRemoved = crRemoved.replaceAll(" *\n *", "\n");
        // Apply linefeed-treatment="treat-as-space"
        String lfToSpace = treatLfAsSpace ? spacesNeighboringLfRemoved.replaceAll("\n", " ")
                : spacesNeighboringLfRemoved;
        // Apply white-space-collapse="true"
        String spacesCollapsed = lfToSpace.replaceAll("[ \t\\x0B\f\r]+", " ");
        return spacesCollapsed;
    }

    /**
     * Returns the timed text for the given time period.
     *
     * @param root The root node of the TTML document.
     * @param startUs The start time of the time period in microsecond.
     * @param endUs The end time of the time period in microsecond.
     */
    public static String extractText(TtmlNode root, long startUs, long endUs) {
        StringBuilder text = new StringBuilder();
        extractText(root, startUs, endUs, text, false);
        return text.toString().replaceAll("\n$", "");
    }

    private static void extractText(TtmlNode node, long startUs, long endUs, StringBuilder out,
            boolean inPTag) {
        if (node.mName.equals(TtmlUtils.PCDATA) && inPTag) {
            out.append(node.mText);
        } else if (node.mName.equals(TtmlUtils.TAG_BR) && inPTag) {
            out.append("\n");
        } else if (node.mName.equals(TtmlUtils.TAG_METADATA)) {
            // do nothing.
        } else if (node.isActive(startUs, endUs)) {
            boolean pTag = node.mName.equals(TtmlUtils.TAG_P);
            int length = out.length();
            for (int i = 0; i < node.mChildren.size(); ++i) {
                extractText(node.mChildren.get(i), startUs, endUs, out, pTag || inPTag);
            }
            if (pTag && length != out.length()) {
                out.append("\n");
            }
        }
    }

    /**
     * Returns a TTML fragment string for the given time period.
     *
     * @param root The root node of the TTML document.
     * @param startUs The start time of the time period in microsecond.
     * @param endUs The end time of the time period in microsecond.
     */
    public static String extractTtmlFragment(TtmlNode root, long startUs, long endUs) {
        StringBuilder fragment = new StringBuilder();
        extractTtmlFragment(root, startUs, endUs, fragment);
        return fragment.toString();
    }

    private static void extractTtmlFragment(TtmlNode node, long startUs, long endUs,
            StringBuilder out) {
        if (node.mName.equals(TtmlUtils.PCDATA)) {
            out.append(node.mText);
        } else if (node.mName.equals(TtmlUtils.TAG_BR)) {
            out.append("<br/>");
        } else if (node.isActive(startUs, endUs)) {
            out.append("<");
            out.append(node.mName);
            out.append(node.mAttributes);
            out.append(">");
            for (int i = 0; i < node.mChildren.size(); ++i) {
                extractTtmlFragment(node.mChildren.get(i), startUs, endUs, out);
            }
            out.append("</");
            out.append(node.mName);
            out.append(">");
        }
    }
}

/**
 * A container class which represents a cue in TTML.
 * @hide
 */
class TtmlCue extends SubtitleTrack.Cue {
    public String mText;
    public String mTtmlFragment;

    public TtmlCue(long startTimeMs, long endTimeMs, String text, String ttmlFragment) {
        this.mStartTimeMs = startTimeMs;
        this.mEndTimeMs = endTimeMs;
        this.mText = text;
        this.mTtmlFragment = ttmlFragment;
    }
}

/**
 * A container class which represents a node in TTML.
 *
 * @hide
 */
class TtmlNode {
    public final String mName;
    public final String mAttributes;
    public final TtmlNode mParent;
    public final String mText;
    public final List<TtmlNode> mChildren = new ArrayList<TtmlNode>();
    public final long mRunId;
    public final long mStartTimeMs;
    public final long mEndTimeMs;

    public TtmlNode(String name, String attributes, String text, long startTimeMs, long endTimeMs,
            TtmlNode parent, long runId) {
        this.mName = name;
        this.mAttributes = attributes;
        this.mText = text;
        this.mStartTimeMs = startTimeMs;
        this.mEndTimeMs = endTimeMs;
        this.mParent = parent;
        this.mRunId = runId;
    }

    /**
     * Check if this node is active in the given time range.
     *
     * @param startTimeMs The start time of the range to check in microsecond.
     * @param endTimeMs The end time of the range to check in microsecond.
     * @return return true if the given range overlaps the time range of this
     *         node.
     */
    public boolean isActive(long startTimeMs, long endTimeMs) {
        return this.mEndTimeMs > startTimeMs && this.mStartTimeMs < endTimeMs;
    }
}

/**
 * A simple TTML parser (http://www.w3.org/TR/ttaf1-dfxp/) which supports DFXP
 * presentation profile.
 * <p>
 * Supported features in this parser are:
 * <ul>
 * <li>content
 * <li>core
 * <li>presentation
 * <li>profile
 * <li>structure
 * <li>time-offset
 * <li>timing
 * <li>tickRate
 * <li>time-clock-with-frames
 * <li>time-clock
 * <li>time-offset-with-frames
 * <li>time-offset-with-ticks
 * </ul>
 * </p>
 *
 * @hide
 */
class TtmlParser {
    static final String TAG = "TtmlParser";

    // TODO: read and apply the following attributes if specified.
    private static final int DEFAULT_FRAMERATE = 30;
    private static final int DEFAULT_SUBFRAMERATE = 1;
    private static final int DEFAULT_TICKRATE = 1;

    private XmlPullParser mParser;
    private final TtmlNodeListener mListener;
    private long mCurrentRunId;

    public TtmlParser(TtmlNodeListener listener) {
        mListener = listener;
    }

    /**
     * Parse TTML data. Once this is called, all the previous data are
     * reset and it starts parsing for the given text.
     *
     * @param ttmlText TTML text to parse.
     * @throws XmlPullParserException
     * @throws IOException
     */
    public void parse(String ttmlText, long runId) throws XmlPullParserException, IOException {
        mParser = null;
        mCurrentRunId = runId;
        loadParser(ttmlText);
        parseTtml();
    }

    private void loadParser(String ttmlFragment) throws XmlPullParserException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        mParser = factory.newPullParser();
        StringReader in = new StringReader(ttmlFragment);
        mParser.setInput(in);
    }

    private void extractAttribute(XmlPullParser parser, int i, StringBuilder out) {
        out.append(" ");
        out.append(parser.getAttributeName(i));
        out.append("=\"");
        out.append(parser.getAttributeValue(i));
        out.append("\"");
    }

    private void parseTtml() throws XmlPullParserException, IOException {
        LinkedList<TtmlNode> nodeStack = new LinkedList<TtmlNode>();
        int depthInUnsupportedTag = 0;
        boolean active = true;
        while (!isEndOfDoc()) {
            int eventType = mParser.getEventType();
            TtmlNode parent = nodeStack.peekLast();
            if (active) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (!isSupportedTag(mParser.getName())) {
                        Log.w(TAG, "Unsupported tag " + mParser.getName() + " is ignored.");
                        depthInUnsupportedTag++;
                        active = false;
                    } else {
                        TtmlNode node = parseNode(parent);
                        nodeStack.addLast(node);
                        if (parent != null) {
                            parent.mChildren.add(node);
                        }
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    String text = TtmlUtils.applyDefaultSpacePolicy(mParser.getText());
                    if (!TextUtils.isEmpty(text)) {
                        parent.mChildren.add(new TtmlNode(
                                TtmlUtils.PCDATA, "", text, 0, TtmlUtils.INVALID_TIMESTAMP,
                                parent, mCurrentRunId));

                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (mParser.getName().equals(TtmlUtils.TAG_P)) {
                        mListener.onTtmlNodeParsed(nodeStack.getLast());
                    } else if (mParser.getName().equals(TtmlUtils.TAG_TT)) {
                        mListener.onRootNodeParsed(nodeStack.getLast());
                    }
                    nodeStack.removeLast();
                }
            } else {
                if (eventType == XmlPullParser.START_TAG) {
                    depthInUnsupportedTag++;
                } else if (eventType == XmlPullParser.END_TAG) {
                    depthInUnsupportedTag--;
                    if (depthInUnsupportedTag == 0) {
                        active = true;
                    }
                }
            }
            mParser.next();
        }
    }

    private TtmlNode parseNode(TtmlNode parent) throws XmlPullParserException, IOException {
        int eventType = mParser.getEventType();
        if (!(eventType == XmlPullParser.START_TAG)) {
            return null;
        }
        StringBuilder attrStr = new StringBuilder();
        long start = 0;
        long end = TtmlUtils.INVALID_TIMESTAMP;
        long dur = 0;
        for (int i = 0; i < mParser.getAttributeCount(); ++i) {
            String attr = mParser.getAttributeName(i);
            String value = mParser.getAttributeValue(i);
            // TODO: check if it's safe to ignore the namespace of attributes as follows.
            attr = attr.replaceFirst("^.*:", "");
            if (attr.equals(TtmlUtils.ATTR_BEGIN)) {
                start = TtmlUtils.parseTimeExpression(value, DEFAULT_FRAMERATE,
                        DEFAULT_SUBFRAMERATE, DEFAULT_TICKRATE);
            } else if (attr.equals(TtmlUtils.ATTR_END)) {
                end = TtmlUtils.parseTimeExpression(value, DEFAULT_FRAMERATE, DEFAULT_SUBFRAMERATE,
                        DEFAULT_TICKRATE);
            } else if (attr.equals(TtmlUtils.ATTR_DURATION)) {
                dur = TtmlUtils.parseTimeExpression(value, DEFAULT_FRAMERATE, DEFAULT_SUBFRAMERATE,
                        DEFAULT_TICKRATE);
            } else {
                extractAttribute(mParser, i, attrStr);
            }
        }
        if (parent != null) {
            start += parent.mStartTimeMs;
            if (end != TtmlUtils.INVALID_TIMESTAMP) {
                end += parent.mStartTimeMs;
            }
        }
        if (dur > 0) {
            if (end != TtmlUtils.INVALID_TIMESTAMP) {
                Log.e(TAG, "'dur' and 'end' attributes are defined at the same time." +
                        "'end' value is ignored.");
            }
            end = start + dur;
        }
        if (parent != null) {
            // If the end time remains unspecified, then the end point is
            // interpreted as the end point of the external time interval.
            if (end == TtmlUtils.INVALID_TIMESTAMP &&
                    parent.mEndTimeMs != TtmlUtils.INVALID_TIMESTAMP &&
                    end > parent.mEndTimeMs) {
                end = parent.mEndTimeMs;
            }
        }
        TtmlNode node = new TtmlNode(mParser.getName(), attrStr.toString(), null, start, end,
                parent, mCurrentRunId);
        return node;
    }

    private boolean isEndOfDoc() throws XmlPullParserException {
        return (mParser.getEventType() == XmlPullParser.END_DOCUMENT);
    }

    private static boolean isSupportedTag(String tag) {
        if (tag.equals(TtmlUtils.TAG_TT) || tag.equals(TtmlUtils.TAG_HEAD) ||
                tag.equals(TtmlUtils.TAG_BODY) || tag.equals(TtmlUtils.TAG_DIV) ||
                tag.equals(TtmlUtils.TAG_P) || tag.equals(TtmlUtils.TAG_SPAN) ||
                tag.equals(TtmlUtils.TAG_BR) || tag.equals(TtmlUtils.TAG_STYLE) ||
                tag.equals(TtmlUtils.TAG_STYLING) || tag.equals(TtmlUtils.TAG_LAYOUT) ||
                tag.equals(TtmlUtils.TAG_REGION) || tag.equals(TtmlUtils.TAG_METADATA) ||
                tag.equals(TtmlUtils.TAG_SMPTE_IMAGE) || tag.equals(TtmlUtils.TAG_SMPTE_DATA) ||
                tag.equals(TtmlUtils.TAG_SMPTE_INFORMATION)) {
            return true;
        }
        return false;
    }
}

/** @hide */
interface TtmlNodeListener {
    void onTtmlNodeParsed(TtmlNode node);
    void onRootNodeParsed(TtmlNode node);
}

/** @hide */
class TtmlTrack extends SubtitleTrack implements TtmlNodeListener {
    private static final String TAG = "TtmlTrack";

    private final TtmlParser mParser = new TtmlParser(this);
    private final TtmlRenderingWidget mRenderingWidget;
    private String mParsingData;
    private Long mCurrentRunID;

    private final LinkedList<TtmlNode> mTtmlNodes;
    private final TreeSet<Long> mTimeEvents;
    private TtmlNode mRootNode;

    TtmlTrack(TtmlRenderingWidget renderingWidget, MediaFormat format) {
        super(format);

        mTtmlNodes = new LinkedList<TtmlNode>();
        mTimeEvents = new TreeSet<Long>();
        mRenderingWidget = renderingWidget;
        mParsingData = "";
    }

    @Override
    public TtmlRenderingWidget getRenderingWidget() {
        return mRenderingWidget;
    }

    @Override
    public void onData(byte[] data, boolean eos, long runID) {
        try {
            // TODO: handle UTF-8 conversion properly
            String str = new String(data, "UTF-8");

            // implement intermixing restriction for TTML.
            synchronized(mParser) {
                if (mCurrentRunID != null && runID != mCurrentRunID) {
                    throw new IllegalStateException(
                            "Run #" + mCurrentRunID +
                            " in progress.  Cannot process run #" + runID);
                }
                mCurrentRunID = runID;
                mParsingData += str;
                if (eos) {
                    try {
                        mParser.parse(mParsingData, mCurrentRunID);
                    } catch (XmlPullParserException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    finishedRun(runID);
                    mParsingData = "";
                    mCurrentRunID = null;
                }
            }
        } catch (java.io.UnsupportedEncodingException e) {
            Log.w(TAG, "subtitle data is not UTF-8 encoded: " + e);
        }
    }

    @Override
    public void onTtmlNodeParsed(TtmlNode node) {
        mTtmlNodes.addLast(node);
        addTimeEvents(node);
    }

    @Override
    public void onRootNodeParsed(TtmlNode node) {
        mRootNode = node;
        TtmlCue cue = null;
        while ((cue = getNextResult()) != null) {
            addCue(cue);
        }
        mRootNode = null;
        mTtmlNodes.clear();
        mTimeEvents.clear();
    }

    @Override
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

        mRenderingWidget.setActiveCues(activeCues);
    }

    /**
     * Returns a {@link TtmlCue} in the presentation time order.
     * {@code null} is returned if there is no more timed text to show.
     */
    public TtmlCue getNextResult() {
        while (mTimeEvents.size() >= 2) {
            long start = mTimeEvents.pollFirst();
            long end = mTimeEvents.first();
            List<TtmlNode> activeCues = getActiveNodes(start, end);
            if (!activeCues.isEmpty()) {
                return new TtmlCue(start, end,
                        TtmlUtils.applySpacePolicy(TtmlUtils.extractText(
                                mRootNode, start, end), false),
                        TtmlUtils.extractTtmlFragment(mRootNode, start, end));
            }
        }
        return null;
    }

    private void addTimeEvents(TtmlNode node) {
        mTimeEvents.add(node.mStartTimeMs);
        mTimeEvents.add(node.mEndTimeMs);
        for (int i = 0; i < node.mChildren.size(); ++i) {
            addTimeEvents(node.mChildren.get(i));
        }
    }

    private List<TtmlNode> getActiveNodes(long startTimeUs, long endTimeUs) {
        List<TtmlNode> activeNodes = new ArrayList<TtmlNode>();
        for (int i = 0; i < mTtmlNodes.size(); ++i) {
            TtmlNode node = mTtmlNodes.get(i);
            if (node.isActive(startTimeUs, endTimeUs)) {
                activeNodes.add(node);
            }
        }
        return activeNodes;
    }
}

/**
 * Widget capable of rendering TTML captions.
 *
 * @hide
 */
class TtmlRenderingWidget extends LinearLayout implements SubtitleTrack.RenderingWidget {

    /** Callback for rendering changes. */
    private OnChangedListener mListener;
    private final TextView mTextView;

    public TtmlRenderingWidget(Context context) {
        this(context, null);
    }

    public TtmlRenderingWidget(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TtmlRenderingWidget(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TtmlRenderingWidget(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        // Cannot render text over video when layer type is hardware.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        CaptioningManager captionManager = (CaptioningManager) context.getSystemService(
                Context.CAPTIONING_SERVICE);
        mTextView = new TextView(context);
        mTextView.setTextColor(captionManager.getUserStyle().foregroundColor);
        addView(mTextView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        mTextView.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
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
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    public void setActiveCues(Vector<SubtitleTrack.Cue> activeCues) {
        final int count = activeCues.size();
        String subtitleText = "";
        for (int i = 0; i < count; i++) {
            TtmlCue cue = (TtmlCue) activeCues.get(i);
            subtitleText += cue.mText + "\n";
        }
        mTextView.setText(subtitleText);

        if (mListener != null) {
            mListener.onChanged(this);
        }
    }
}
