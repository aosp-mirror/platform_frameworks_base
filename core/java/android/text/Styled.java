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

package android.text;

import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.MaskFilter;
import android.graphics.Rasterizer;
import android.graphics.LayerRasterizer;
import android.text.style.*;

/* package */ class Styled
{
    private static float each(Canvas canvas,
                              Spanned text, int start, int end,
                              int dir, boolean reverse,
                              float x, int top, int y, int bottom,
                              Paint.FontMetricsInt fm,
                              TextPaint realPaint,
                              TextPaint paint,
                              boolean needwid) {

        boolean havewid = false;
        float ret = 0;
        CharacterStyle[] spans = text.getSpans(start, end, CharacterStyle.class);

        ReplacementSpan replacement = null;

        realPaint.bgColor = 0;
        realPaint.baselineShift = 0;
        paint.set(realPaint);

		if (spans.length > 0) {
			for (int i = 0; i < spans.length; i++) {
				CharacterStyle span = spans[i];

				if (span instanceof ReplacementSpan) {
					replacement = (ReplacementSpan)span;
				}
				else {
					span.updateDrawState(paint);
				}
			}
		}

        if (replacement == null) {
            CharSequence tmp;
            int tmpstart, tmpend;

            if (reverse) {
                tmp = TextUtils.getReverse(text, start, end);
                tmpstart = 0;
                tmpend = end - start;
            } else {
                tmp = text;
                tmpstart = start;
                tmpend = end;
            }

            if (fm != null) {
                paint.getFontMetricsInt(fm);
            }

            if (canvas != null) {
                if (paint.bgColor != 0) {
                    int c = paint.getColor();
                    Paint.Style s = paint.getStyle();
                    paint.setColor(paint.bgColor);
                    paint.setStyle(Paint.Style.FILL);

                    if (!havewid) {
                        ret = paint.measureText(tmp, tmpstart, tmpend);
                        havewid = true;
                    }

                    if (dir == Layout.DIR_RIGHT_TO_LEFT)
                        canvas.drawRect(x - ret, top, x, bottom, paint);
                    else
                        canvas.drawRect(x, top, x + ret, bottom, paint);

                    paint.setStyle(s);
                    paint.setColor(c);
                }

                if (dir == Layout.DIR_RIGHT_TO_LEFT) {
                    if (!havewid) {
                        ret = paint.measureText(tmp, tmpstart, tmpend);
                        havewid = true;
                    }

                    canvas.drawText(tmp, tmpstart, tmpend,
                                    x - ret, y + paint.baselineShift, paint);
                } else {
                    if (needwid) {
                        if (!havewid) {
                            ret = paint.measureText(tmp, tmpstart, tmpend);
                            havewid = true;
                        }
                    }

                    canvas.drawText(tmp, tmpstart, tmpend,
                                    x, y + paint.baselineShift, paint);
                }
            } else {
                if (needwid && !havewid) {
                    ret = paint.measureText(tmp, tmpstart, tmpend);
                    havewid = true;
                }
            }
        } else {
            ret = replacement.getSize(paint, text, start, end, fm);

            if (canvas != null) {
                if (dir == Layout.DIR_RIGHT_TO_LEFT)
                    replacement.draw(canvas, text, start, end,
                                     x - ret, top, y, bottom, paint);
                else
                    replacement.draw(canvas, text, start, end,
                                     x, top, y, bottom, paint);
            }
        }

        if (dir == Layout.DIR_RIGHT_TO_LEFT)
            return -ret;
        else
            return ret;
    }

    public static int getTextWidths(TextPaint realPaint,
                                    TextPaint paint,
                              Spanned text, int start, int end,
                              float[] widths, Paint.FontMetricsInt fm) {

        MetricAffectingSpan[] spans = text.getSpans(start, end, MetricAffectingSpan.class);

		ReplacementSpan replacement = null;
        paint.set(realPaint);
		
		for (int i = 0; i < spans.length; i++) {
			MetricAffectingSpan span = spans[i];
			if (span instanceof ReplacementSpan) {
				replacement = (ReplacementSpan)span;
			}
			else {
				span.updateMeasureState(paint);
			}
		}
	
        if (replacement == null) {
            paint.getFontMetricsInt(fm);
            paint.getTextWidths(text, start, end, widths);
        } else {
            int wid = replacement.getSize(paint, text, start, end, fm);

            if (end > start) {
                widths[0] = wid;

                for (int i = start + 1; i < end; i++)
                    widths[i - start] = 0;
            }
        }
        return end - start;
    }

    private static float foreach(Canvas canvas,
                                 CharSequence text, int start, int end,
                                 int dir, boolean reverse,
                                 float x, int top, int y, int bottom,
                                 Paint.FontMetricsInt fm,
                                 TextPaint paint,
                                 TextPaint workPaint,
                                 boolean needwid) {
        if (! (text instanceof Spanned)) {
            float ret = 0;

            if (reverse) {
                CharSequence tmp = TextUtils.getReverse(text, start, end);
                int tmpend = end - start;

                if (canvas != null || needwid)
                    ret = paint.measureText(tmp, 0, tmpend);

                if (canvas != null)
                    canvas.drawText(tmp, 0, tmpend,
                                    x - ret, y, paint);
            } else {
                if (needwid)
                    ret = paint.measureText(text, start, end);

                if (canvas != null)
                    canvas.drawText(text, start, end, x, y, paint);
            }

            if (fm != null) {
                paint.getFontMetricsInt(fm);
            }

            return ret * dir;   //Layout.DIR_RIGHT_TO_LEFT == -1
        }
        
        float ox = x;
        int asc = 0, desc = 0;
        int ftop = 0, fbot = 0;

        Spanned sp = (Spanned) text;
        Class division;

        if (canvas == null)
            division = MetricAffectingSpan.class;
        else
            division = CharacterStyle.class;

        int next;
        for (int i = start; i < end; i = next) {
            next = sp.nextSpanTransition(i, end, division);

            x += each(canvas, sp, i, next, dir, reverse,
                  x, top, y, bottom, fm, paint, workPaint,
                  needwid || next != end);

            if (fm != null) {
                if (fm.ascent < asc)
                    asc = fm.ascent;
                if (fm.descent > desc)
                    desc = fm.descent;

                if (fm.top < ftop)
                    ftop = fm.top;
                if (fm.bottom > fbot)
                    fbot = fm.bottom;
            }
        }

        if (fm != null) {
            if (start == end) {
                paint.getFontMetricsInt(fm);
            } else {
                fm.ascent = asc;
                fm.descent = desc;
                fm.top = ftop;
                fm.bottom = fbot;
            }
        }

        return x - ox;
    }

    public static float drawText(Canvas canvas,
                                 CharSequence text, int start, int end,
                                 int dir, boolean reverse,
                                 float x, int top, int y, int bottom,
                                 TextPaint paint,
                                 TextPaint workPaint,
                                 boolean needwid) {
        if ((dir == Layout.DIR_RIGHT_TO_LEFT && !reverse)||(reverse && dir == Layout.DIR_LEFT_TO_RIGHT)) {
            float ch = foreach(null, text, start, end, Layout.DIR_LEFT_TO_RIGHT,
                         false, 0, 0, 0, 0, null, paint, workPaint,
                         true);

            ch *= dir;  // DIR_RIGHT_TO_LEFT == -1
            foreach(canvas, text, start, end, -dir,
                    reverse, x + ch, top, y, bottom, null, paint,
                    workPaint, true);

            return ch;
        }

        return foreach(canvas, text, start, end, dir, reverse,
                       x, top, y, bottom, null, paint, workPaint,
                       needwid);
    }

    public static float measureText(TextPaint paint,
                                    TextPaint workPaint,
                                    CharSequence text, int start, int end,
                                    Paint.FontMetricsInt fm) {
        return foreach(null, text, start, end,
                       Layout.DIR_LEFT_TO_RIGHT, false,
                       0, 0, 0, 0, fm, paint, workPaint, true);
    }
}
