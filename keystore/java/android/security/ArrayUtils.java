package android.security;

import libcore.util.EmptyArray;

/**
 * @hide
 */
public abstract class ArrayUtils {
    private ArrayUtils() {}

    public static String[] nullToEmpty(String[] array) {
        return (array != null) ? array : EmptyArray.STRING;
    }

    public static String[] cloneIfNotEmpty(String[] array) {
        return ((array != null) && (array.length > 0)) ? array.clone() : array;
    }

    public static byte[] concat(byte[] arr1, byte[] arr2) {
        return concat(arr1, 0, (arr1 != null) ? arr1.length : 0,
                arr2, 0, (arr2 != null) ? arr2.length : 0);
    }

    public static byte[] concat(byte[] arr1, int offset1, int len1, byte[] arr2, int offset2,
            int len2) {
        if (len1 == 0) {
            return subarray(arr2, offset2, len2);
        } else if (len2 == 0) {
            return subarray(arr1, offset1, len1);
        } else {
            byte[] result = new byte[len1 + len2];
            System.arraycopy(arr1, offset1, result, 0, len1);
            System.arraycopy(arr2, offset2, result, len1, len2);
            return result;
        }
    }

    public static byte[] subarray(byte[] arr, int offset, int len) {
        if (len == 0) {
            return EmptyArray.BYTE;
        }
        if ((offset == 0) && (len == arr.length)) {
            return arr;
        }
        byte[] result = new byte[len];
        System.arraycopy(arr, offset, result, 0, len);
        return result;
    }

    public static int[] concat(int[] arr1, int[] arr2) {
        if ((arr1 == null) || (arr1.length == 0)) {
            return arr2;
        } else if ((arr2 == null) || (arr2.length == 0)) {
            return arr1;
        } else {
            int[] result = new int[arr1.length + arr2.length];
            System.arraycopy(arr1, 0, result, 0, arr1.length);
            System.arraycopy(arr2, 0, result, arr1.length, arr2.length);
            return result;
        }
    }
}
