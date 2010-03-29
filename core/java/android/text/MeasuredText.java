/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.text;

import com.android.internal.util.ArrayUtils;

import android.graphics.Paint;
import android.icu.text.ArabicShaping;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.util.Log;

/**
 * @hide
 */
class MeasuredText {
    /* package */ CharSequence mText;
    /* package */ int mTextStart;
    /* package */ float[] mWidths;
    /* package */ char[] mChars;
    /* package */ byte[] mLevels;
    /* package */ int mDir;
    /* package */ boolean mEasy;
    /* package */ int mLen;
    private int mPos;
    private float[] mWorkWidths; // temp buffer for Paint.measureText, arrgh
    private TextPaint mWorkPaint;

    private MeasuredText() {
        mWorkPaint = new TextPaint();
    }

    private static MeasuredText[] cached = new MeasuredText[3];

    /* package */
    static MeasuredText obtain() {
        MeasuredText mt;
        synchronized (cached) {
            for (int i = cached.length; --i >= 0;) {
                if (cached[i] != null) {
                    mt = cached[i];
                    cached[i] = null;
                    return mt;
                }
            }
        }
        mt = new MeasuredText();
        Log.e("MEAS", "new: " + mt);
        return mt;
    }

    /* package */
    static MeasuredText recycle(MeasuredText mt) {
        mt.mText = null;
        if (mt.mLen < 1000) {
            synchronized(cached) {
                for (int i = 0; i < cached.length; ++i) {
                    if (cached[i] == null) {
                        cached[i] = mt;
                        break;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Analyzes text for
     * bidirectional runs.  Allocates working buffers.
     */
    /* package */
    void setPara(CharSequence text, int start, int end, int bidiRequest) {
        mText = text;
        mTextStart = start;

        int len = end - start;
        mLen = len;
        mPos = 0;

        if (mWidths == null || mWidths.length < len) {
            mWidths = new float[ArrayUtils.idealFloatArraySize(len)];
            mWorkWidths = new float[mWidths.length];
        }
        if (mChars == null || mChars.length < len) {
            mChars = new char[ArrayUtils.idealCharArraySize(len)];
        }
        TextUtils.getChars(text, start, end, mChars, 0);

        if (text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            ReplacementSpan[] spans = spanned.getSpans(start, end,
                    ReplacementSpan.class);

            for (int i = 0; i < spans.length; i++) {
                int startInPara = spanned.getSpanStart(spans[i]) - start;
                int endInPara = spanned.getSpanEnd(spans[i]) - start;
                for (int j = startInPara; j < endInPara; j++) {
                    mChars[j] = '\uFFFC';
                }
            }
        }

        if (TextUtils.doesNotNeedBidi(mChars, 0, len)) {
            mDir = 1;
            mEasy = true;
        } else {
            if (mLevels == null || mLevels.length < len) {
                mLevels = new byte[ArrayUtils.idealByteArraySize(len)];
            }
            mDir = AndroidBidi.bidi(bidiRequest, mChars, mLevels, len, false);
            mEasy = false;

            // shape
            if (mLen > 0) {
                byte[] levels = mLevels;
                char[] chars = mChars;
                byte level = levels[0];
                int pi = 0;
                for (int i = 1, e = mLen;; ++i) {
                    if (i == e || levels[i] != level) {
                        if ((level & 0x1) != 0) {
                            AndroidCharacter.mirror(chars, pi, i - pi);
                            ArabicShaping.SHAPER.shape(chars, pi, i - pi);
                        }
                        if (i == e) {
                            break;
                        }
                        pi = i;
                        level = levels[i];
                    }
                }
            }
        }
    }

    float addStyleRun(TextPaint paint, int len, Paint.FontMetricsInt fm) {
        int p = mPos;
        float[] w = mWidths, ww = mWorkWidths;
        int count = paint.getTextWidths(mChars, p, len, ww);
        int width = 0;
        if (count < len) {
            // must have surrogate pairs in here, pad out the array with zero
            // for the trailing surrogates
            char[] chars = mChars;
            for (int i = 0, e = mLen; i < count; ++i) {
                width += (w[p++] = ww[i]);
                if (p < e && chars[p] >= '\udc00' && chars[p] < '\ue000' &&
                        chars[p-1] >= '\ud800' && chars[p-1] < '\udc00') {
                    w[p++] = 0;
                }
            }
        } else {
            for (int i = 0; i < len; ++i) {
                width += (w[p++] = ww[i]);
            }
        }
        mPos = p;
        if (fm != null) {
            paint.getFontMetricsInt(fm);
        }
        return width;
    }

    float addStyleRun(TextPaint paint, MetricAffectingSpan[] spans, int len,
            Paint.FontMetricsInt fm) {

        TextPaint workPaint = mWorkPaint;
        workPaint.set(paint);
        // XXX paint should not have a baseline shift, but...
        workPaint.baselineShift = 0;

        ReplacementSpan replacement = null;
        for (int i = 0; i < spans.length; i++) {
            MetricAffectingSpan span = spans[i];
            if (span instanceof ReplacementSpan) {
                replacement = (ReplacementSpan)span;
            } else {
                span.updateMeasureState(workPaint);
            }
        }

        float wid;
        if (replacement == null) {
            wid = addStyleRun(workPaint, len, fm);
        } else {
            // Use original text.  Shouldn't matter.
            wid = replacement.getSize(workPaint, mText, mTextStart + mPos,
                    mTextStart + mPos + len, fm);
            float[] w = mWidths;
            w[mPos] = wid;
            for (int i = mPos + 1, e = mPos + len; i < e; i++)
                w[i] = 0;
        }

        if (fm != null) {
            if (workPaint.baselineShift < 0) {
                fm.ascent += workPaint.baselineShift;
                fm.top += workPaint.baselineShift;
            } else {
                fm.descent += workPaint.baselineShift;
                fm.bottom += workPaint.baselineShift;
            }
        }

        return wid;
    }

    int breakText(int start, int limit, boolean forwards, float width) {
        float[] w = mWidths;
        if (forwards) {
            for (int i = start; i < limit; ++i) {
                if ((width -= w[i]) < 0) {
                    return i - start;
                }
            }
        } else {
            for (int i = limit; --i >= start;) {
                if ((width -= w[i]) < 0) {
                    return limit - i -1;
                }
            }
        }

        return limit - start;
    }

    float measure(int start, int limit) {
        float width = 0;
        float[] w = mWidths;
        for (int i = start; i < limit; ++i) {
            width += w[i];
        }
        return width;
    }
}