/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content.res;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.Application;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.text.LineBreakConfig;
import android.ravenwood.annotation.RavenwoodClassLoadHook;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.text.Annotation;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBreakConfigSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.io.Closeable;
import java.util.Arrays;

/**
 * Conveniences for retrieving data out of a compiled string resource.
 *
 * {@hide}
 */
@RavenwoodKeepWholeClass
@RavenwoodClassLoadHook(RavenwoodClassLoadHook.LIBANDROID_LOADING_HOOK)
public final class StringBlock implements Closeable {
    private static final String TAG = "AssetManager";
    private static final boolean localLOGV = false;

    private long mNative;   // final, but gets modified when closed
    private final boolean mUseSparse;
    private final boolean mOwnsNative;

    private CharSequence[] mStrings;
    private SparseArray<CharSequence> mSparseStrings;

    @GuardedBy("this") private boolean mOpen = true;

    StyleIDs mStyleIDs = null;

    public StringBlock(byte[] data, boolean useSparse) {
        mNative = nativeCreate(data, 0, data.length);
        mUseSparse = useSparse;
        mOwnsNative = true;
        if (localLOGV) Log.v(TAG, "Created string block " + this
                + ": " + nativeGetSize(mNative));
    }

    public StringBlock(byte[] data, int offset, int size, boolean useSparse) {
        mNative = nativeCreate(data, offset, size);
        mUseSparse = useSparse;
        mOwnsNative = true;
        if (localLOGV) Log.v(TAG, "Created string block " + this
                + ": " + nativeGetSize(mNative));
    }

    /**
     * @deprecated use {@link #getSequence(int)} which can return null when a string cannot be found
     *             due to incremental installation.
     */
    @Deprecated
    @UnsupportedAppUsage
    public CharSequence get(int idx) {
        CharSequence seq = getSequence(idx);
        return seq == null ? "" : seq;
    }

    @Nullable
    public CharSequence getSequence(int idx) {
        synchronized (this) {
            if (mStrings != null) {
                CharSequence res = mStrings[idx];
                if (res != null) {
                    return res;
                }
            } else if (mSparseStrings != null) {
                CharSequence res = mSparseStrings.get(idx);
                if (res != null) {
                    return res;
                }
            } else {
                final int num = nativeGetSize(mNative);
                if (mUseSparse && num > 250) {
                    mSparseStrings = new SparseArray<CharSequence>();
                } else {
                    mStrings = new CharSequence[num];
                }
            }
            String str = nativeGetString(mNative, idx);
            if (str == null) {
                return null;
            }
            CharSequence res = str;
            int[] style = nativeGetStyle(mNative, idx);
            if (localLOGV) Log.v(TAG, "Got string: " + str);
            if (localLOGV) Log.v(TAG, "Got styles: " + Arrays.toString(style));
            if (style != null) {
                if (mStyleIDs == null) {
                    mStyleIDs = new StyleIDs();
                }

                // the style array is a flat array of <type, start, end> hence
                // the magic constant 3.
                for (int styleIndex = 0; styleIndex < style.length; styleIndex += 3) {
                    int styleId = style[styleIndex];

                    if (styleId == mStyleIDs.boldId || styleId == mStyleIDs.italicId
                            || styleId == mStyleIDs.underlineId || styleId == mStyleIDs.ttId
                            || styleId == mStyleIDs.bigId || styleId == mStyleIDs.smallId
                            || styleId == mStyleIDs.subId || styleId == mStyleIDs.supId
                            || styleId == mStyleIDs.strikeId || styleId == mStyleIDs.listItemId
                            || styleId == mStyleIDs.marqueeId) {
                        // id already found skip to next style
                        continue;
                    }

                    String styleTag = nativeGetString(mNative, styleId);
                    if (styleTag == null) {
                        return null;
                    }

                    if (styleTag.equals("b")) {
                        mStyleIDs.boldId = styleId;
                    } else if (styleTag.equals("i")) {
                        mStyleIDs.italicId = styleId;
                    } else if (styleTag.equals("u")) {
                        mStyleIDs.underlineId = styleId;
                    } else if (styleTag.equals("tt")) {
                        mStyleIDs.ttId = styleId;
                    } else if (styleTag.equals("big")) {
                        mStyleIDs.bigId = styleId;
                    } else if (styleTag.equals("small")) {
                        mStyleIDs.smallId = styleId;
                    } else if (styleTag.equals("sup")) {
                        mStyleIDs.supId = styleId;
                    } else if (styleTag.equals("sub")) {
                        mStyleIDs.subId = styleId;
                    } else if (styleTag.equals("strike")) {
                        mStyleIDs.strikeId = styleId;
                    } else if (styleTag.equals("li")) {
                        mStyleIDs.listItemId = styleId;
                    } else if (styleTag.equals("marquee")) {
                        mStyleIDs.marqueeId = styleId;
                    } else if (styleTag.equals("nobreak")) {
                        mStyleIDs.mNoBreakId = styleId;
                    } else if (styleTag.equals("nohyphen")) {
                        mStyleIDs.mNoHyphenId = styleId;
                    }
                }

                res = applyStyles(str, style, mStyleIDs);
            }
            if (res != null) {
                if (mStrings != null) mStrings[idx] = res;
                else mSparseStrings.put(idx, res);
            }
            return res;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (mOpen) {
                mOpen = false;

                if (mOwnsNative) {
                    nativeDestroy(mNative);
                }
                mNative = 0;
            }
        }
    }

    static final class StyleIDs {
        private int boldId = -1;
        private int italicId = -1;
        private int underlineId = -1;
        private int ttId = -1;
        private int bigId = -1;
        private int smallId = -1;
        private int subId = -1;
        private int supId = -1;
        private int strikeId = -1;
        private int listItemId = -1;
        private int marqueeId = -1;
        private int mNoBreakId = -1;
        private int mNoHyphenId = -1;
    }

    @Nullable
    private CharSequence applyStyles(String str, int[] style, StyleIDs ids) {
        if (style.length == 0)
            return str;

        SpannableString buffer = new SpannableString(str);
        int i=0;
        while (i < style.length) {
            int type = style[i];
            if (localLOGV) Log.v(TAG, "Applying style span id=" + type
                    + ", start=" + style[i+1] + ", end=" + style[i+2]);


            if (type == ids.boldId) {
                Application application = ActivityThread.currentApplication();
                int fontWeightAdjustment =
                        application.getResources().getConfiguration().fontWeightAdjustment;
                buffer.setSpan(new StyleSpan(Typeface.BOLD, fontWeightAdjustment),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.italicId) {
                buffer.setSpan(new StyleSpan(Typeface.ITALIC),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.underlineId) {
                buffer.setSpan(new UnderlineSpan(),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.ttId) {
                buffer.setSpan(new TypefaceSpan("monospace"),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.bigId) {
                buffer.setSpan(new RelativeSizeSpan(1.25f),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.smallId) {
                buffer.setSpan(new RelativeSizeSpan(0.8f),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.subId) {
                buffer.setSpan(new SubscriptSpan(),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.supId) {
                buffer.setSpan(new SuperscriptSpan(),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.strikeId) {
                buffer.setSpan(new StrikethroughSpan(),
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (type == ids.listItemId) {
                addParagraphSpan(buffer, new BulletSpan(10),
                                style[i+1], style[i+2]+1);
            } else if (type == ids.marqueeId) {
                buffer.setSpan(TextUtils.TruncateAt.MARQUEE,
                               style[i+1], style[i+2]+1,
                               Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            } else if (type == ids.mNoBreakId) {
                buffer.setSpan(LineBreakConfigSpan.createNoBreakSpan(),
                        style[i + 1], style[i + 2] + 1,
                        Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
            } else if (type == ids.mNoHyphenId) {
                buffer.setSpan(LineBreakConfigSpan.createNoHyphenationSpan(),
                        style[i + 1], style[i + 2] + 1,
                        Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
            } else {
                String tag = nativeGetString(mNative, type);
                if (tag == null) {
                    return null;
                }
                if (tag.startsWith("font;")) {
                    String sub;

                    sub = subtag(tag, ";height=");
                    if (sub != null) {
                        int size = Integer.parseInt(sub);
                        addParagraphSpan(buffer, new Height(size),
                                       style[i+1], style[i+2]+1);
                    }

                    sub = subtag(tag, ";size=");
                    if (sub != null) {
                        int size = Integer.parseInt(sub);
                        buffer.setSpan(new AbsoluteSizeSpan(size, true),
                                       style[i+1], style[i+2]+1,
                                       Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }

                    sub = subtag(tag, ";fgcolor=");
                    if (sub != null) {
                        buffer.setSpan(getColor(sub, true),
                                       style[i+1], style[i+2]+1,
                                       Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }

                    sub = subtag(tag, ";color=");
                    if (sub != null) {
                        buffer.setSpan(getColor(sub, true),
                                style[i+1], style[i+2]+1,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }

                    sub = subtag(tag, ";bgcolor=");
                    if (sub != null) {
                        buffer.setSpan(getColor(sub, false),
                                       style[i+1], style[i+2]+1,
                                       Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }

                    sub = subtag(tag, ";face=");
                    if (sub != null) {
                        buffer.setSpan(new TypefaceSpan(sub),
                                style[i+1], style[i+2]+1,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if (tag.startsWith("a;")) {
                    String sub;

                    sub = subtag(tag, ";href=");
                    if (sub != null) {
                        buffer.setSpan(new URLSpan(sub),
                                       style[i+1], style[i+2]+1,
                                       Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if (tag.startsWith("annotation;")) {
                    int len = tag.length();
                    int next;

                    for (int t = tag.indexOf(';'); t < len; t = next) {
                        int eq = tag.indexOf('=', t);
                        if (eq < 0) {
                            break;
                        }

                        next = tag.indexOf(';', eq);
                        if (next < 0) {
                            next = len;
                        }

                        String key = tag.substring(t + 1, eq);
                        String value = tag.substring(eq + 1, next);

                        buffer.setSpan(new Annotation(key, value),
                                       style[i+1], style[i+2]+1,
                                       Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if (tag.startsWith("lineBreakConfig;")) {
                    String lbStyleStr = subtag(tag, ";style=");
                    int lbStyle = LineBreakConfig.LINE_BREAK_STYLE_UNSPECIFIED;
                    if (lbStyleStr != null) {
                        if (lbStyleStr.equals("none")) {
                            lbStyle = LineBreakConfig.LINE_BREAK_STYLE_NONE;
                        } else if (lbStyleStr.equals("normal")) {
                            lbStyle = LineBreakConfig.LINE_BREAK_STYLE_NORMAL;
                        } else if (lbStyleStr.equals("loose")) {
                            lbStyle = LineBreakConfig.LINE_BREAK_STYLE_LOOSE;
                        } else if (lbStyleStr.equals("strict")) {
                            lbStyle = LineBreakConfig.LINE_BREAK_STYLE_STRICT;
                        } else {
                            Log.w(TAG, "Unknown LineBreakConfig style: " + lbStyleStr);
                        }
                    }

                    String lbWordStyleStr = subtag(tag, ";wordStyle=");
                    int lbWordStyle = LineBreakConfig.LINE_BREAK_STYLE_UNSPECIFIED;
                    if (lbWordStyleStr != null) {
                        if (lbWordStyleStr.equals("none")) {
                            lbWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE;
                        } else if (lbWordStyleStr.equals("phrase")) {
                            lbWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE;
                        } else {
                            Log.w(TAG, "Unknown LineBreakConfig word style: " + lbWordStyleStr);
                        }
                    }

                    // Attach span only when the both lbStyle and lbWordStyle are valid.
                    if (lbStyle != LineBreakConfig.LINE_BREAK_STYLE_UNSPECIFIED
                            || lbWordStyle != LineBreakConfig.LINE_BREAK_WORD_STYLE_UNSPECIFIED) {
                        buffer.setSpan(new LineBreakConfigSpan(
                                new LineBreakConfig(lbStyle, lbWordStyle,
                                        LineBreakConfig.HYPHENATION_UNSPECIFIED)),
                                style[i + 1], style[i + 2] + 1,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }

            i += 3;
        }
        return new SpannedString(buffer);
    }

    /**
     * Returns a span for the specified color string representation.
     * If the specified string does not represent a color (null, empty, etc.)
     * the color black is returned instead.
     *
     * @param color The color as a string. Can be a resource reference,
     *              hexadecimal, octal or a name
     * @param foreground True if the color will be used as the foreground color,
     *                   false otherwise
     *
     * @return A CharacterStyle
     *
     * @see Color#parseColor(String)
     */
    private static CharacterStyle getColor(String color, boolean foreground) {
        int c = 0xff000000;

        if (!TextUtils.isEmpty(color)) {
            if (color.startsWith("@")) {
                Resources res = Resources.getSystem();
                String name = color.substring(1);
                int colorRes = res.getIdentifier(name, "color", "android");
                if (colorRes != 0) {
                    ColorStateList colors = res.getColorStateList(colorRes, null);
                    if (foreground) {
                        return new TextAppearanceSpan(null, 0, 0, colors, null);
                    } else {
                        c = colors.getDefaultColor();
                    }
                }
            } else {
                try {
                    c = Color.parseColor(color);
                } catch (IllegalArgumentException e) {
                    c = Color.BLACK;
                }
            }
        }

        if (foreground) {
            return new ForegroundColorSpan(c);
        } else {
            return new BackgroundColorSpan(c);
        }
    }

    /**
     * If a translator has messed up the edges of paragraph-level markup,
     * fix it to actually cover the entire paragraph that it is attached to
     * instead of just whatever range they put it on.
     */
    private static void addParagraphSpan(Spannable buffer, Object what,
                                         int start, int end) {
        int len = buffer.length();

        if (start != 0 && start != len && buffer.charAt(start - 1) != '\n') {
            for (start--; start > 0; start--) {
                if (buffer.charAt(start - 1) == '\n') {
                    break;
                }
            }
        }

        if (end != 0 && end != len && buffer.charAt(end - 1) != '\n') {
            for (end++; end < len; end++) {
                if (buffer.charAt(end - 1) == '\n') {
                    break;
                }
            }
        }

        buffer.setSpan(what, start, end, Spannable.SPAN_PARAGRAPH);
    }

    private static String subtag(String full, String attribute) {
        int start = full.indexOf(attribute);
        if (start < 0) {
            return null;
        }

        start += attribute.length();
        int end = full.indexOf(';', start);

        if (end < 0) {
            return full.substring(start);
        } else {
            return full.substring(start, end);
        }
    }

    /**
     * Forces the text line to be the specified height, shrinking/stretching
     * the ascent if possible, or the descent if shrinking the ascent further
     * will make the text unreadable.
     */
    private static class Height implements LineHeightSpan.WithDensity {
        private int mSize;
        private static float sProportion = 0;

        public Height(int size) {
            mSize = size;
        }

        public void chooseHeight(CharSequence text, int start, int end,
                                 int spanstartv, int v,
                                 Paint.FontMetricsInt fm) {
            // Should not get called, at least not by StaticLayout.
            chooseHeight(text, start, end, spanstartv, v, fm, null);
        }

        public void chooseHeight(CharSequence text, int start, int end,
                                 int spanstartv, int v,
                                 Paint.FontMetricsInt fm, TextPaint paint) {
            int size = mSize;
            if (paint != null) {
                size *= paint.density;
            }

            if (fm.bottom - fm.top < size) {
                fm.top = fm.bottom - size;
                fm.ascent = fm.ascent - size;
            } else {
                if (sProportion == 0) {
                    /*
                     * Calculate what fraction of the nominal ascent
                     * the height of a capital letter actually is,
                     * so that we won't reduce the ascent to less than
                     * that unless we absolutely have to.
                     */

                    Paint p = new Paint();
                    p.setTextSize(100);
                    Rect r = new Rect();
                    p.getTextBounds("ABCDEFG", 0, 7, r);

                    sProportion = (r.top) / p.ascent();
                }

                int need = (int) Math.ceil(-fm.top * sProportion);

                if (size - fm.descent >= need) {
                    /*
                     * It is safe to shrink the ascent this much.
                     */

                    fm.top = fm.bottom - size;
                    fm.ascent = fm.descent - size;
                } else if (size >= need) {
                    /*
                     * We can't show all the descent, but we can at least
                     * show all the ascent.
                     */

                    fm.top = fm.ascent = -need;
                    fm.bottom = fm.descent = fm.top + size;
                } else {
                    /*
                     * Show as much of the ascent as we can, and no descent.
                     */

                    fm.top = fm.ascent = -size;
                    fm.bottom = fm.descent = 0;
                }
            }
        }
    }

    /**
     * Create from an existing string block native object.  This is
     * -extremely- dangerous -- only use it if you absolutely know what you
     *  are doing!  The given native object must exist for the entire lifetime
     *  of this newly creating StringBlock.
     */
    @UnsupportedAppUsage
    public StringBlock(long obj, boolean useSparse) {
        mNative = obj;
        mUseSparse = useSparse;
        mOwnsNative = false;
        if (localLOGV) Log.v(TAG, "Created string block " + this
                + ": " + nativeGetSize(mNative));
    }

    private static native long nativeCreate(byte[] data,
                                                 int offset,
                                                 int size);
    private static native int nativeGetSize(long obj);
    private static native String nativeGetString(long obj, int idx);
    private static native int[] nativeGetStyle(long obj, int idx);
    private static native void nativeDestroy(long obj);
}
