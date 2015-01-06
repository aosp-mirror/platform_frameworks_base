package android.text;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.text.CharacterIterator;
import java.util.Arrays;
import java.util.Locale;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;
import javax.swing.text.Segment;

/**
 * Delegate that provides implementation for native methods in {@link android.text.StaticLayout}
 * <p/>
 * Through the layoutlib_create tool, selected methods of StaticLayout have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class StaticLayout_Delegate {

    /**
     * Fills the recycle array with positions that are suitable to break the text at. The array
     * must be terminated by '-1'.
     */
    @LayoutlibDelegate
    /*package*/ static int[] nLineBreakOpportunities(String locale, char[] text, int length,
            int[] recycle) {
        BreakIterator iterator = BreakIterator.getLineInstance(new ULocale(locale));
        Segment segment = new Segment(text, 0, length);
        iterator.setText(segment);
        if (recycle == null) {
            // Because 42 is the answer to everything.
            recycle = new int[42];
        }
        int breakOpp = iterator.first();
        recycle[0] = breakOpp;
        //noinspection ConstantConditions
        assert BreakIterator.DONE == -1;
        for (int i = 1; breakOpp != BreakIterator.DONE; ++i) {
            if (i >= recycle.length) {
                recycle = doubleSize(recycle);
            }
            assert (i < recycle.length);
            breakOpp = iterator.next();
            recycle[i] = breakOpp;
        }
        return recycle;
    }

    private static int[] doubleSize(int[] array) {
        return Arrays.copyOf(array, array.length * 2);
    }
}
