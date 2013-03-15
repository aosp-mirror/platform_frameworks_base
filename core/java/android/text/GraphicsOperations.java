/*
 * Copyright (C) 2008 The Android Open Source Project
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

/**
 * Please implement this interface if your CharSequence can do quick
 * draw/measure/widths calculations from an internal array.
 * {@hide}
 */
public interface GraphicsOperations
extends CharSequence
{
    /**
     * Just like {@link Canvas#drawText}.
     */
    void drawText(Canvas c, int start, int end,
                         float x, float y, Paint p);

    /**
     * Just like {@link Canvas#drawTextRun}.
     * {@hide}
     */
    void drawTextRun(Canvas c, int start, int end, int contextStart, int contextEnd,
            float x, float y, int flags, Paint p);

   /**
     * Just like {@link Paint#measureText}.
     */
    float measureText(int start, int end, Paint p);

    /**
     * Just like {@link Paint#getTextWidths}.
     */
    public int getTextWidths(int start, int end, float[] widths, Paint p);

    /**
     * Just like {@link Paint#getTextRunAdvances}.
     * @hide
     */
    float getTextRunAdvances(int start, int end, int contextStart, int contextEnd,
            int flags, float[] advances, int advancesIndex, Paint paint);

    /**
     * Just like {@link Paint#getTextRunAdvances}.
     * @hide
     */
    float getTextRunAdvances(int start, int end, int contextStart, int contextEnd,
            int flags, float[] advances, int advancesIndex, Paint paint, int reserved);

    /**
     * Just like {@link Paint#getTextRunCursor}.
     * @hide
     */
    int getTextRunCursor(int contextStart, int contextEnd, int flags, int offset,
            int cursorOpt, Paint p);
}
