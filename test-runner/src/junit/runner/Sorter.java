package junit.runner;

import java.util.*;

/**
 * A custom quick sort with support to customize the swap behaviour.
 * NOTICE: We can't use the the sorting support from the JDK 1.2 collection
 * classes because of the JDK 1.1.7 compatibility.
 * {@hide} - Not needed for 1.0 SDK
 */
public class Sorter {
    public static interface Swapper {
        public void swap(Vector values, int left, int right);
    }

    public static void sortStrings(Vector values , int left, int right, Swapper swapper) {
        int oleft= left;
        int oright= right;
        String mid= (String)values.elementAt((left + right) / 2);
        do {
            while (((String)(values.elementAt(left))).compareTo(mid) < 0)
                left++;
            while (mid.compareTo((String)(values.elementAt(right))) < 0)
                right--;
            if (left <= right) {
                swapper.swap(values, left, right);
                left++;
                right--;
            }
        } while (left <= right);

        if (oleft < right)
            sortStrings(values, oleft, right, swapper);
        if (left < oright)
            sortStrings(values, left, oright, swapper);
    }
}
