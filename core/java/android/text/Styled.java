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

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.CharacterStyle;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;

/**
 * This class provides static methods for drawing and measuring styled texts, like
 * {@link android.text.Spanned} object with {@link android.text.style.ReplacementSpan}.
 * @hide
 */
public class Styled
{
    private static float each(Canvas canvas,
                              Spanned text, int start, int end,
                              int dir, boolean reverse,
                              float x, int top, int y, int bottom,
                              Paint.FontMetricsInt fmi,
                              TextPaint paint,
                              TextPaint workPaint,
                              boolean needwid) {

        boolean havewid = false;
        float ret = 0;
        CharacterStyle[] spans = text.getSpans(start, end, CharacterStyle.class);

        ReplacementSpan replacement = null;

        paint.bgColor = 0;
        paint.baselineShift = 0;
        workPaint.set(paint);

		if (spans.length > 0) {
			for (int i = 0; i < spans.length; i++) {
				CharacterStyle span = spans[i];

				if (span instanceof ReplacementSpan) {
					replacement = (ReplacementSpan)span;
				}
				else {
					span.updateDrawState(workPaint);
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

            if (fmi != null) {
                workPaint.getFontMetricsInt(fmi);
            }

            if (canvas != null) {
                if (workPaint.bgColor != 0) {
                    int c = workPaint.getColor();
                    Paint.Style s = workPaint.getStyle();
                    workPaint.setColor(workPaint.bgColor);
                    workPaint.setStyle(Paint.Style.FILL);

                    if (!havewid) {
                        ret = workPaint.measureText(tmp, tmpstart, tmpend);
                        havewid = true;
                    }

                    if (dir == Layout.DIR_RIGHT_TO_LEFT)
                        canvas.drawRect(x - ret, top, x, bottom, workPaint);
                    else
                        canvas.drawRect(x, top, x + ret, bottom, workPaint);

                    workPaint.setStyle(s);
                    workPaint.setColor(c);
                }

                if (dir == Layout.DIR_RIGHT_TO_LEFT) {
                    if (!havewid) {
                        ret = workPaint.measureText(tmp, tmpstart, tmpend);
                        havewid = true;
                    }

                    canvas.drawText(tmp, tmpstart, tmpend,
                                    x - ret, y + workPaint.baselineShift, workPaint);
                } else {
                    if (needwid) {
                        if (!havewid) {
                            ret = workPaint.measureText(tmp, tmpstart, tmpend);
                            havewid = true;
                        }
                    }

                    canvas.drawText(tmp, tmpstart, tmpend,
                                    x, y + workPaint.baselineShift, workPaint);
                }
            } else {
                if (needwid && !havewid) {
                    ret = workPaint.measureText(tmp, tmpstart, tmpend);
                    havewid = true;
                }
            }
        } else {
            ret = replacement.getSize(workPaint, text, start, end, fmi);

            if (canvas != null) {
                if (dir == Layout.DIR_RIGHT_TO_LEFT)
                    replacement.draw(canvas, text, start, end,
                                     x - ret, top, y, bottom, workPaint);
                else
                    replacement.draw(canvas, text, start, end,
                                     x, top, y, bottom, workPaint);
            }
        }

        if (dir == Layout.DIR_RIGHT_TO_LEFT)
            return -ret;
        else
            return ret;
    }

    /**
     * Return the advance widths for the characters in the string.
     * See also {@link android.graphics.Paint#getTextWidths(CharSequence, int, int, float[])}.
     * 
     * @param paint The main {@link TextPaint} object.
     * @param workPaint The {@link TextPaint} object used for temporal workspace.
     * @param text The text to measure
     * @param start The index of the first char to to measure
     * @param end The end of the text slice to measure
     * @param widths Array to receive the advance widths of the characters.
     * Must be at least a large as (end - start).
     * @param fmi FontMetrics information. Can be null.
     * @return The actual number of widths returned. 
     */
    public static int getTextWidths(TextPaint paint,
                                    TextPaint workPaint,
                                    Spanned text, int start, int end,
                                    float[] widths, Paint.FontMetricsInt fmi) {
        //  Keep workPaint as is so that developers reuse the workspace.
        MetricAffectingSpan[] spans = text.getSpans(start, end, MetricAffectingSpan.class);

		ReplacementSpan replacement = null;
        workPaint.set(paint);
		
		for (int i = 0; i < spans.length; i++) {
			MetricAffectingSpan span = spans[i];
			if (span instanceof ReplacementSpan) {
				replacement = (ReplacementSpan)span;
			}
			else {
				span.updateMeasureState(workPaint);
			}
		}
	
        if (replacement == null) {
            workPaint.getFontMetricsInt(fmi);
            workPaint.getTextWidths(text, start, end, widths);
        } else {
            int wid = replacement.getSize(workPaint, text, start, end, fmi);

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
                                 Paint.FontMetricsInt fmi,
                                 TextPaint paint,
                                 TextPaint workPaint,
                                 boolean needWidth) {
        if (! (text instanceof Spanned)) {
            float ret = 0;

            if (reverse) {
                CharSequence tmp = TextUtils.getReverse(text, start, end);
                int tmpend = end - start;

                if (canvas != null || needWidth)
                    ret = paint.measureText(tmp, 0, tmpend);

                if (canvas != null)
                    canvas.drawText(tmp, 0, tmpend,
                                    x - ret, y, paint);
            } else {
                if (needWidth)
                    ret = paint.measureText(text, start, end);

                if (canvas != null)
                    canvas.drawText(text, start, end, x, y, paint);
            }

            if (fmi != null) {
                paint.getFontMetricsInt(fmi);
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
                  x, top, y, bottom, fmi, paint, workPaint,
                  needWidth || next != end);

            if (fmi != null) {
                if (fmi.ascent < asc)
                    asc = fmi.ascent;
                if (fmi.descent > desc)
                    desc = fmi.descent;

                if (fmi.top < ftop)
                    ftop = fmi.top;
                if (fmi.bottom > fbot)
                    fbot = fmi.bottom;
            }
        }

        if (fmi != null) {
            if (start == end) {
                paint.getFontMetricsInt(fmi);
            } else {
                fmi.ascent = asc;
                fmi.descent = desc;
                fmi.top = ftop;
                fmi.bottom = fbot;
            }
        }

        return x - ox;
    }


    /* package */ static float drawText(Canvas canvas,
                                       CharSequence text, int start, int end,
                                       int direction, boolean reverse,
                                       float x, int top, int y, int bottom,
                                       TextPaint paint,
                                       TextPaint workPaint,
                                       boolean needWidth) {
        if ((direction == Layout.DIR_RIGHT_TO_LEFT && !reverse) ||
            (reverse && direction == Layout.DIR_LEFT_TO_RIGHT)) {
            float ch = foreach(null, text, start, end, Layout.DIR_LEFT_TO_RIGHT,
                         false, 0, 0, 0, 0, null, paint, workPaint,
                         true);

            ch *= direction;  // DIR_RIGHT_TO_LEFT == -1
            foreach(canvas, text, start, end, -direction,
                    reverse, x + ch, top, y, bottom, null, paint,
                    workPaint, true);

            return ch;
        }

        return foreach(canvas, text, start, end, direction, reverse,
                       x, top, y, bottom, null, paint, workPaint,
                       needWidth);
    }
    
    /**
     * Draw the specified range of text, specified by start/end, with its origin at (x,y),
     * in the specified Paint. The origin is interpreted based on the Align setting in the
     * Paint.
     *  
     * This method considers style information in the text
     * (e.g. Even when text is an instance of {@link android.text.Spanned}, this method
     * correctly draws the text).
     * See also
     * {@link android.graphics.Canvas#drawText(CharSequence, int, int, float, float, Paint)}
     * and
     * {@link android.graphics.Canvas#drawRect(float, float, float, float, Paint)}.
     * 
     * @param canvas The target canvas.
     * @param text The text to be drawn
     * @param start The index of the first character in text to draw
     * @param end (end - 1) is the index of the last character in text to draw
     * @param direction The direction of the text. This must be
     * {@link android.text.Layout#DIR_LEFT_TO_RIGHT} or
     * {@link android.text.Layout#DIR_RIGHT_TO_LEFT}.
     * @param x The x-coordinate of origin for where to draw the text
     * @param top The top side of the rectangle to be drawn
     * @param y The y-coordinate of origin for where to draw the text
     * @param bottom The bottom side of the rectangle to be drawn
     * @param paint The main {@link TextPaint} object.
     * @param workPaint The {@link TextPaint} object used for temporal workspace.
     * @param needWidth If true, this method returns the width of drawn text.
     * @return Width of the drawn text if needWidth is true.
     */
    public static float drawText(Canvas canvas,
                                 CharSequence text, int start, int end,
                                 int direction,
                                 float x, int top, int y, int bottom,
                                 TextPaint paint,
                                 TextPaint workPaint,
                                 boolean needWidth) {
        // For safety.
        direction = direction >= 0 ? Layout.DIR_LEFT_TO_RIGHT : Layout.DIR_RIGHT_TO_LEFT;
        /*
         * Hided "reverse" parameter since it is meaningless for external developers.
         * Kept workPaint as is so that developers reuse the workspace.
         */
        return drawText(canvas, text, start, end, direction, false,
                        x, top, y, bottom, paint, workPaint, needWidth);
    }
    
    /**
     * Return the width of the text, considering style information in the text
     * (e.g. Even when text is an instance of {@link android.text.Spanned}, this method
     * correctly mesures the width of the text).
     * 
     * @param paint The main {@link TextPaint} object.
     * @param workPaint The {@link TextPaint} object used for temporal workspace.
     * @param text The text to measure
     * @param start The index of the first character to start measuring
     * @param end 1 beyond the index of the last character to measure
     * @param fmi FontMetrics information. Can be null
     * @return The width of the text 
     */
    public static float measureText(TextPaint paint,
                                    TextPaint workPaint,
                                    CharSequence text, int start, int end,
                                    Paint.FontMetricsInt fmi) {
        // Keep workPaint as is so that developers reuse the workspace.
        return foreach(null, text, start, end,
                       Layout.DIR_LEFT_TO_RIGHT, false,
                       0, 0, 0, 0, fmi, paint, workPaint, true);
    }
}
