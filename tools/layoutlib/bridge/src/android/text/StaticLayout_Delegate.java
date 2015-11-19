package android.text;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.NonNull;
import android.graphics.BidiRenderer;
import android.graphics.Paint;
import android.graphics.Paint_Delegate;
import android.graphics.RectF;
import android.icu.text.BreakIterator;
import android.icu.util.ULocale;
import android.text.Primitive.PrimitiveType;
import android.text.StaticLayout.LineBreaks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.text.Segment;

/**
 * Delegate that provides implementation for native methods in {@link android.text.StaticLayout}
 * <p/>
 * Through the layoutlib_create tool, selected methods of StaticLayout have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class StaticLayout_Delegate {

    private static final char CHAR_SPACE     = 0x20;
    private static final char CHAR_TAB       = 0x09;
    private static final char CHAR_NEWLINE   = 0x0A;
    private static final char CHAR_ZWSP      = 0x200B;  // Zero width space.

    // ---- Builder delegate manager ----
    private static final DelegateManager<Builder> sBuilderManager =
        new DelegateManager<Builder>(Builder.class);

    @LayoutlibDelegate
    /*package*/ static long nNewBuilder() {
        return sBuilderManager.addNewDelegate(new Builder());
    }

    @LayoutlibDelegate
    /*package*/ static void nFreeBuilder(long nativeBuilder) {
        sBuilderManager.removeJavaReferenceFor(nativeBuilder);
    }

    @LayoutlibDelegate
    /*package*/ static void nFinishBuilder(long nativeBuilder) {
    }

    @LayoutlibDelegate
    /*package*/ static long nLoadHyphenator(String patternData) {
        return Hyphenator_Delegate.loadHyphenator(patternData);
    }

    @LayoutlibDelegate
    /*package*/ static void nSetLocale(long nativeBuilder, String locale, long nativeHyphenator) {
        Builder builder = sBuilderManager.getDelegate(nativeBuilder);
        if (builder != null) {
            builder.mLocale = locale;
            builder.mNativeHyphenator = nativeHyphenator;
        }
    }

    @LayoutlibDelegate
    /*package*/ static void nSetIndents(long nativeBuilder, int[] indents) {
        // TODO.
    }

    @LayoutlibDelegate
    /*package*/ static void nSetupParagraph(long nativeBuilder, char[] text, int length,
            float firstWidth, int firstWidthLineCount, float restWidth,
            int[] variableTabStops, int defaultTabStop, int breakStrategy,
            int hyphenationFrequency) {
        Builder builder = sBuilderManager.getDelegate(nativeBuilder);
        if (builder == null) {
            return;
        }

        builder.mText = text;
        builder.mWidths = new float[length];
        builder.mLineWidth = new LineWidth(firstWidth, firstWidthLineCount, restWidth);
        builder.mTabStopCalculator = new TabStops(variableTabStops, defaultTabStop);
    }

    @LayoutlibDelegate
    /*package*/ static float nAddStyleRun(long nativeBuilder, long nativePaint, long nativeTypeface,
            int start, int end, boolean isRtl) {
        Builder builder = sBuilderManager.getDelegate(nativeBuilder);

        int bidiFlags = isRtl ? Paint.BIDI_FORCE_RTL : Paint.BIDI_FORCE_LTR;
        return builder == null ? 0 :
                measureText(nativePaint, builder.mText, start, end - start, builder.mWidths,
                        bidiFlags);
    }

    @LayoutlibDelegate
    /*package*/ static void nAddMeasuredRun(long nativeBuilder, int start, int end, float[] widths) {
        Builder builder = sBuilderManager.getDelegate(nativeBuilder);
        if (builder != null) {
            System.arraycopy(widths, start, builder.mWidths, start, end - start);
        }
    }

    @LayoutlibDelegate
    /*package*/ static void nAddReplacementRun(long nativeBuilder, int start, int end, float width) {
        Builder builder = sBuilderManager.getDelegate(nativeBuilder);
        if (builder == null) {
            return;
        }
        builder.mWidths[start] = width;
        Arrays.fill(builder.mWidths, start + 1, end, 0.0f);
    }

    @LayoutlibDelegate
    /*package*/ static void nGetWidths(long nativeBuilder, float[] floatsArray) {
        Builder builder = sBuilderManager.getDelegate(nativeBuilder);
        if (builder != null) {
            System.arraycopy(builder.mWidths, 0, floatsArray, 0, builder.mWidths.length);
        }
    }

    @LayoutlibDelegate
    /*package*/ static int nComputeLineBreaks(long nativeBuilder,
            LineBreaks recycle, int[] recycleBreaks, float[] recycleWidths,
            int[] recycleFlags, int recycleLength) {

        Builder builder = sBuilderManager.getDelegate(nativeBuilder);
        if (builder == null) {
            return 0;
        }

        // compute all possible breakpoints.
        int length = builder.mWidths.length;
        BreakIterator it = BreakIterator.getLineInstance(new ULocale(builder.mLocale));
        it.setText(new Segment(builder.mText, 0, length));

        // average word length in english is 5. So, initialize the possible breaks with a guess.
        List<Integer> breaks = new ArrayList<Integer>((int) Math.ceil(length / 5d));
        int loc;
        it.first();
        while ((loc = it.next()) != BreakIterator.DONE) {
            breaks.add(loc);
        }

        List<Primitive> primitives =
                computePrimitives(builder.mText, builder.mWidths, length, breaks);
        switch (builder.mBreakStrategy) {
            case Layout.BREAK_STRATEGY_SIMPLE:
                builder.mLineBreaker = new GreedyLineBreaker(primitives, builder.mLineWidth,
                        builder.mTabStopCalculator);
                break;
            case Layout.BREAK_STRATEGY_HIGH_QUALITY:
                // TODO
//                break;
            case Layout.BREAK_STRATEGY_BALANCED:
                builder.mLineBreaker = new OptimizingLineBreaker(primitives, builder.mLineWidth,
                        builder.mTabStopCalculator);
                break;
            default:
                throw new AssertionError("Unknown break strategy: " + builder.mBreakStrategy);
        }
        builder.mLineBreaker.computeBreaks(recycle);
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

    private static float measureText(long nativePaint, char []text, int index, int count,
            float[] widths, int bidiFlags) {
        Paint_Delegate paint = Paint_Delegate.getDelegate(nativePaint);
        RectF bounds = new BidiRenderer(null, paint, text)
            .renderText(index, index + count, bidiFlags, widths, 0, false);
        return bounds.right - bounds.left;
    }

    // TODO: Rename to LineBreakerRef and move everything other than LineBreaker to LineBreaker.
    /**
     * Java representation of the native Builder class.
     */
    private static class Builder {
        String mLocale;
        char[] mText;
        float[] mWidths;
        LineBreaker mLineBreaker;
        long mNativeHyphenator;
        int mBreakStrategy;
        LineWidth mLineWidth;
        TabStops mTabStopCalculator;
    }
}
