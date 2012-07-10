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

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

/**
 * @hide
 */
class MeasuredText {
    private static final boolean localLOGV = false;
    CharSequence mText;
    int mTextStart;
    float[] mWidths;
    char[] mChars;
    byte[] mLevels;
    int mDir;
    boolean mEasy;
    int mLen;

    private int mPos;
    private TextPaint mWorkPaint;

    private MeasuredText() {
        mWorkPaint = new TextPaint();
    }

    private static final Object[] sLock = new Object[0];
    private static MeasuredText[] sCached = new MeasuredText[3];

    static MeasuredText obtain() {
        MeasuredText mt;
        synchronized (sLock) {
            for (int i = sCached.length; --i >= 0;) {
                if (sCached[i] != null) {
                    mt = sCached[i];
                    sCached[i] = null;
                    return mt;
                }
            }
        }
        mt = new MeasuredText();
        if (localLOGV) {
            Log.v("MEAS", "new: " + mt);
        }
        return mt;
    }

    static MeasuredText recycle(MeasuredText mt) {
        mt.mText = null;
        if (mt.mLen < 1000) {
            synchronized(sLock) {
                for (int i = 0; i < sCached.length; ++i) {
                    if (sCached[i] == null) {
                        sCached[i] = mt;
                        mt.mText = null;
                        break;
                    }
                }
            }
        }
        return null;
    }

    void setPos(int pos) {
        mPos = pos - mTextStart;
    }

    /**
     * Analyzes text for bidirectional runs.  Allocates working buffers.
     */
    void setPara(CharSequence text, int start, int end, TextDirectionHeuristic textDir) {
        mText = text;
        mTextStart = start;

        int len = end - start;
        mLen = len;
        mPos = 0;

        if (mWidths == null || mWidths.length < len) {
            mWidths = new float[ArrayUtils.idealFloatArraySize(len)];
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
                // The span interval may be larger and must be restricted to [start, end[
                if (startInPara < 0) startInPara = 0;
                if (endInPara > len) endInPara = len;
                for (int j = startInPara; j < endInPara; j++) {
                    mChars[j] = '\uFFFC'; // object replacement character
                }
            }
        }

        if ((textDir == TextDirectionHeuristics.LTR ||
                textDir == TextDirectionHeuristics.FIRSTSTRONG_LTR ||
                textDir == TextDirectionHeuristics.ANYRTL_LTR) &&
                TextUtils.doesNotNeedBidi(mChars, 0, len)) {
            mDir = Layout.DIR_LEFT_TO_RIGHT;
            mEasy = true;
        } else {
            if (mLevels == null || mLevels.length < len) {
                mLevels = new byte[ArrayUtils.idealByteArraySize(len)];
            }
            int bidiRequest;
            if (textDir == TextDirectionHeuristics.LTR) {
                bidiRequest = Layout.DIR_REQUEST_LTR;
            } else if (textDir == TextDirectionHeuristics.RTL) {
                bidiRequest = Layout.DIR_REQUEST_RTL;
            } else if (textDir == TextDirectionHeuristics.FIRSTSTRONG_LTR) {
                bidiRequest = Layout.DIR_REQUEST_DEFAULT_LTR;
            } else if (textDir == TextDirectionHeuristics.FIRSTSTRONG_RTL) {
                bidiRequest = Layout.DIR_REQUEST_DEFAULT_RTL;
            } else {
                boolean isRtl = textDir.isRtl(mChars, 0, len);
                bidiRequest = isRtl ? Layout.DIR_REQUEST_RTL : Layout.DIR_REQUEST_LTR;
            }
            mDir = AndroidBidi.bidi(bidiRequest, mChars, mLevels, len, false);
            mEasy = false;
        }
    }

    float addStyleRun(TextPaint paint, int len, Paint.FontMetricsInt fm) {
        if (fm != null) {
            paint.getFontMetricsInt(fm);
        }

        int p = mPos;
        mPos = p + len;

        if (mEasy) {
            int flags = mDir == Layout.DIR_LEFT_TO_RIGHT
                ? Canvas.DIRECTION_LTR : Canvas.DIRECTION_RTL;
            return paint.getTextRunAdvances(mChars, p, len, p, len, flags, mWidths, p);
        }

        float totalAdvance = 0;
        int level = mLevels[p];
        for (int q = p, i = p + 1, e = p + len;; ++i) {
            if (i == e || mLevels[i] != level) {
                int flags = (level & 0x1) == 0 ? Canvas.DIRECTION_LTR : Canvas.DIRECTION_RTL;
                totalAdvance +=
                        paint.getTextRunAdvances(mChars, q, i - q, q, i - q, flags, mWidths, q);
                if (i == e) {
                    break;
                }
                q = i;
                level = mLevels[i];
            }
        }
        return totalAdvance;
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
            mPos += len;
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

    int breakText(int limit, boolean forwards, float width) {
        float[] w = mWidths;
        if (forwards) {
            int i = 0;
            while (i < limit) {
                width -= w[i];
                if (width < 0.0f) break;
                i++;
            }
            while (i > 0 && mChars[i - 1] == ' ') i--;
            return i;
        } else {
            int i = limit - 1;
            while (i >= 0) {
                width -= w[i];
                if (width < 0.0f) break;
                i--;
            }
            while (i < limit - 1 && mChars[i + 1] == ' ') i++;
            return limit - i - 1;
        }
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
