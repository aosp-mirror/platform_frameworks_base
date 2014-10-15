package android.text;

import com.android.annotations.NonNull;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.text.StaticLayout.LineBreaks;
import android.text.Primitive.PrimitiveType;

import java.util.ArrayList;
import java.util.List;

import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;
import javax.swing.text.Segment;

/**
 * Delegate that provides implementation for native methods in {@link android.text.StaticLayout}
 * <p/>
 * Through the layoutlib_create tool, selected methods of Handler have been replaced by calls to
 * methods of the same name in this delegate class.
 */
public class StaticLayout_Delegate {

    private static final char CHAR_SPACE     = 0x20;
    private static final char CHAR_TAB       = 0x09;
    private static final char CHAR_NEWLINE   = 0x0A;
    private static final char CHAR_ZWSP      = 0x200B;  // Zero width space.

    @LayoutlibDelegate
    /*package*/ static int nComputeLineBreaks(String locale, char[] inputText, float[] widths,
            int length, float firstWidth, int firstWidthLineCount, float restWidth,
            int[] variableTabStops, int defaultTabStop, boolean optimize, LineBreaks recycle,
            int[] recycleBreaks, float[] recycleWidths, boolean[] recycleFlags, int recycleLength) {

        // compute all possible breakpoints.
        BreakIterator it = BreakIterator.getLineInstance(new ULocale(locale));
        it.setText(new Segment(inputText, 0, length));
        // average word length in english is 5. So, initialize the possible breaks with a guess.
        List<Integer> breaks = new ArrayList<Integer>((int) Math.ceil(length / 5d));
        int loc;
        it.first();
        while ((loc = it.next()) != BreakIterator.DONE) {
            breaks.add(loc);
        }

        LineWidth lineWidth = new LineWidth(firstWidth, firstWidthLineCount, restWidth);
        TabStops tabStopCalculator = new TabStops(variableTabStops, defaultTabStop);
        List<Primitive> primitives = computePrimitives(inputText, widths, length, breaks);
        LineBreaker lineBreaker;
        if (optimize) {
            lineBreaker = new OptimizingLineBreaker(primitives, lineWidth, tabStopCalculator);
        } else {
            lineBreaker = new GreedyLineBreaker(primitives, lineWidth, tabStopCalculator);
        }
        lineBreaker.computeBreaks(recycle);
        return recycle.breaks.length;
    }

    /**
     * Compute metadata each character - things which help in deciding if it's possible to break
     * at a point or not.
     */
    @NonNull
    private static List<Primitive> computePrimitives(@NonNull char[] text, @NonNull float[] widths,
            int length, @NonNull List<Integer> breaks) {
        // Initialize the list with a guess of the number of primitives:
        // 2 Primitives per non-whitespace char and approx 5 chars per word (i.e. 83% chars)
        List<Primitive> primitives = new ArrayList<Primitive>(((int) Math.ceil(length * 1.833)));
        int breaksSize = breaks.size();
        int breakIndex = 0;
        for (int i = 0; i < length; i++) {
            char c = text[i];
            if (c == CHAR_SPACE || c == CHAR_ZWSP) {
                primitives.add(PrimitiveType.GLUE.getNewPrimitive(i, widths[i]));
            } else if (c == CHAR_TAB) {
                primitives.add(PrimitiveType.VARIABLE.getNewPrimitive(i));
            } else if (c != CHAR_NEWLINE) {
                while (breakIndex < breaksSize && breaks.get(breakIndex) < i) {
                    breakIndex++;
                }
                Primitive p;
                if (widths[i] != 0) {
                    if (breakIndex < breaksSize && breaks.get(breakIndex) == i) {
                        p = PrimitiveType.PENALTY.getNewPrimitive(i, 0, 0);
                    } else {
                        p = PrimitiveType.WORD_BREAK.getNewPrimitive(i, 0);
                    }
                    primitives.add(p);
                }

                primitives.add(PrimitiveType.BOX.getNewPrimitive(i, widths[i]));
            }
        }
        // final break at end of everything
        primitives.add(
                PrimitiveType.PENALTY.getNewPrimitive(length, 0, -PrimitiveType.PENALTY_INFINITY));
        return primitives;
    }
}
