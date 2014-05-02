/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.graphics;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.util.LinkedList;
import java.util.List;

import com.ibm.icu.lang.UScript;
import com.ibm.icu.lang.UScriptRun;

import android.graphics.Paint_Delegate.FontInfo;

/**
 * Render the text by breaking it into various scripts and using the right font for each script.
 * Can be used to measure the text without actually drawing it.
 */
@SuppressWarnings("deprecation")
public class BidiRenderer {

    /* package */ static class ScriptRun {
        int start;
        int limit;
        boolean isRtl;
        int scriptCode;
        FontInfo font;

        public ScriptRun(int start, int limit, boolean isRtl) {
            this.start = start;
            this.limit = limit;
            this.isRtl = isRtl;
            this.scriptCode = UScript.INVALID_CODE;
        }
    }

    /* package */ Graphics2D graphics;
    /* package */ Paint_Delegate paint;
    /* package */ char[] text;

    /**
     * @param graphics May be null.
     * @param paint The Paint to use to get the fonts. Should not be null.
     * @param text Unidirectional text. Should not be null.
     */
    /* package */ BidiRenderer(Graphics2D graphics, Paint_Delegate paint, char[] text) {
        assert (paint != null);
        this.graphics = graphics;
        this.paint = paint;
        this.text = text;
    }

    /**
     * Render unidirectional text.
     *
     * This method can also be used to measure the width of the text without actually drawing it.
     *
     * @param start index of the first character
     * @param limit index of the first character that should not be rendered.
     * @param isRtl is the text right-to-left
     * @param advances If not null, then advances for each character to be rendered are returned
     *            here.
     * @param advancesIndex index into advances from where the advances need to be filled.
     * @param draw If true and {@link graphics} is not null, draw the rendered text on the graphics
     *            at the given co-ordinates
     * @param x The x-coordinate of the left edge of where the text should be drawn on the given
     *            graphics.
     * @param y The y-coordinate at which to draw the text on the given graphics.
     * @return The x-coordinate of the right edge of the drawn text. In other words,
     *            x + the width of the text.
     */
    /* package */ float renderText(int start, int limit, boolean isRtl, float advances[],
            int advancesIndex, boolean draw, float x, float y) {
        // We break the text into scripts and then select font based on it and then render each of
        // the script runs.
        for (ScriptRun run : getScriptRuns(text, start, limit, isRtl, paint.getFonts())) {
            int flag = Font.LAYOUT_NO_LIMIT_CONTEXT | Font.LAYOUT_NO_START_CONTEXT;
            flag |= isRtl ? Font.LAYOUT_RIGHT_TO_LEFT : Font.LAYOUT_LEFT_TO_RIGHT;
            x = renderScript(run.start, run.limit, run.font, flag, advances, advancesIndex, draw,
                    x, y);
            advancesIndex += run.limit - run.start;
        }
        return x;
    }

    /**
     * Render a script run. Use the preferred font to render as much as possible. This also
     * implements a fallback mechanism to render characters that cannot be drawn using the
     * preferred font.
     *
     * @return x + width of the text drawn.
     */
    private float renderScript(int start, int limit, FontInfo preferredFont, int flag,
            float advances[], int advancesIndex, boolean draw, float x, float y) {
        List<FontInfo> fonts = paint.getFonts();
        if (fonts == null || preferredFont == null) {
            return x;
        }

        while (start < limit) {
            boolean foundFont = false;
            int canDisplayUpTo = preferredFont.mFont.canDisplayUpTo(text, start, limit);
            if (canDisplayUpTo == -1) {
                return render(start, limit, preferredFont, flag, advances, advancesIndex, draw,
                        x, y);
            } else if (canDisplayUpTo > start) { // can draw something
                x = render(start, canDisplayUpTo, preferredFont, flag, advances, advancesIndex,
                        draw, x, y);
                advancesIndex += canDisplayUpTo - start;
                start = canDisplayUpTo;
            }

            int charCount = Character.isHighSurrogate(text[start]) ? 2 : 1;
            for (FontInfo font : fonts) {
                canDisplayUpTo = font.mFont.canDisplayUpTo(text, start, start + charCount);
                if (canDisplayUpTo == -1) {
                    x = render(start, start+charCount, font, flag, advances, advancesIndex, draw,
                            x, y);
                    start += charCount;
                    advancesIndex += charCount;
                    foundFont = true;
                    break;
                }
            }
            if (!foundFont) {
                // No font can display this char. Use the preferred font. The char will most
                // probably appear as a box or a blank space. We could, probably, use some
                // heuristics and break the character into the base character and diacritics and
                // then draw it, but it's probably not worth the effort.
                x = render(start, start + charCount, preferredFont, flag, advances, advancesIndex,
                        draw, x, y);
                start += charCount;
                advancesIndex += charCount;
            }
        }
        return x;
    }

    /**
     * Render the text with the given font.
     */
    private float render(int start, int limit, FontInfo font, int flag, float advances[],
            int advancesIndex, boolean draw, float x, float y) {

        float totalAdvance = 0;
        // Since the metrics don't have anti-aliasing set, we create a new FontRenderContext with
        // the anti-aliasing set.
        FontRenderContext f = font.mMetrics.getFontRenderContext();
        FontRenderContext frc = new FontRenderContext(f.getTransform(), paint.isAntiAliased(),
                f.usesFractionalMetrics());
        GlyphVector gv = font.mFont.layoutGlyphVector(frc, text, start, limit, flag);
        int ng = gv.getNumGlyphs();
        int[] ci = gv.getGlyphCharIndices(0, ng, null);
        for (int i = 0; i < ng; i++) {
            float adv = gv.getGlyphMetrics(i).getAdvanceX();
            if (advances != null) {
                int adv_idx = advancesIndex + ci[i];
                advances[adv_idx] += adv;
            }
            totalAdvance += adv;
        }
        if (draw && graphics != null) {
            graphics.drawGlyphVector(gv, x, y);
        }
        return x + totalAdvance;
    }

    // --- Static helper methods ---

    /* package */  static List<ScriptRun> getScriptRuns(char[] text, int start, int limit,
            boolean isRtl, List<FontInfo> fonts) {
        LinkedList<ScriptRun> scriptRuns = new LinkedList<ScriptRun>();

        int count = limit - start;
        UScriptRun uScriptRun = new UScriptRun(text, start, count);
        while (uScriptRun.next()) {
            int scriptStart = uScriptRun.getScriptStart();
            int scriptLimit = uScriptRun.getScriptLimit();
            ScriptRun run = new ScriptRun(scriptStart, scriptLimit, isRtl);
            run.scriptCode = uScriptRun.getScriptCode();
            setScriptFont(text, run, fonts);
            scriptRuns.add(run);
        }

        return scriptRuns;
    }

    // TODO: Replace this method with one which returns the font based on the scriptCode.
    private static void setScriptFont(char[] text, ScriptRun run,
            List<FontInfo> fonts) {
        for (FontInfo fontInfo : fonts) {
            if (fontInfo.mFont.canDisplayUpTo(text, run.start, run.limit) == -1) {
                run.font = fontInfo;
                return;
            }
        }
        run.font = fonts.get(0);
    }
}
