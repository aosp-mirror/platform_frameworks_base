/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media;

import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Vector;

import static com.android.internal.util.Preconditions.checkNotNull;

// package private
class Utils {
    private static final String TAG = "Utils";

    /**
     * Sorts distinct (non-intersecting) range array in ascending order.
     * @throws java.lang.IllegalArgumentException if ranges are not distinct
     */
    public static <T extends Comparable<? super T>> void sortDistinctRanges(Range<T>[] ranges) {
        Arrays.sort(ranges, new Comparator<Range<T>>() {
            @Override
            public int compare(Range<T> lhs, Range<T> rhs) {
                if (lhs.getUpper().compareTo(rhs.getLower()) < 0) {
                    return -1;
                } else if (lhs.getLower().compareTo(rhs.getUpper()) > 0) {
                    return 1;
                }
                throw new IllegalArgumentException(
                        "sample rate ranges must be distinct (" + lhs + " and " + rhs + ")");
            }
        });
    }

    /**
     * Returns the intersection of two sets of non-intersecting ranges
     * @param one a sorted set of non-intersecting ranges in ascending order
     * @param another another sorted set of non-intersecting ranges in ascending order
     * @return the intersection of the two sets, sorted in ascending order
     */
    public static <T extends Comparable<? super T>>
            Range<T>[] intersectSortedDistinctRanges(Range<T>[] one, Range<T>[] another) {
        int ix = 0;
        Vector<Range<T>> result = new Vector<Range<T>>();
        for (Range<T> range: another) {
            while (ix < one.length &&
                    one[ix].getUpper().compareTo(range.getLower()) < 0) {
                ++ix;
            }
            while (ix < one.length &&
                    one[ix].getUpper().compareTo(range.getUpper()) < 0) {
                result.add(range.intersect(one[ix]));
                ++ix;
            }
            if (ix == one.length) {
                break;
            }
            if (one[ix].getLower().compareTo(range.getUpper()) <= 0) {
                result.add(range.intersect(one[ix]));
            }
        }
        return result.toArray(new Range[result.size()]);
    }

    /**
     * Returns the index of the range that contains a value in a sorted array of distinct ranges.
     * @param ranges a sorted array of non-intersecting ranges in ascending order
     * @param value the value to search for
     * @return if the value is in one of the ranges, it returns the index of that range.  Otherwise,
     * the return value is {@code (-1-index)} for the {@code index} of the range that is
     * immediately following {@code value}.
     */
    public static <T extends Comparable<? super T>>
            int binarySearchDistinctRanges(Range<T>[] ranges, T value) {
        return Arrays.binarySearch(ranges, Range.create(value, value),
                new Comparator<Range<T>>() {
                    @Override
                    public int compare(Range<T> lhs, Range<T> rhs) {
                        if (lhs.getUpper().compareTo(rhs.getLower()) < 0) {
                            return -1;
                        } else if (lhs.getLower().compareTo(rhs.getUpper()) > 0) {
                            return 1;
                        }
                        return 0;
                    }
                });
    }

    /**
     * Returns greatest common divisor
     */
    static int gcd(int a, int b) {
        if (a == 0 && b == 0) {
            return 1;
        }
        if (b < 0) {
            b = -b;
        }
        if (a < 0) {
            a = -a;
        }
        while (a != 0) {
            int c = b % a;
            b = a;
            a = c;
        }
        return b;
    }

    /** Returns the equivalent factored range {@code newrange}, where for every
     * {@code e}: {@code newrange.contains(e)} implies that {@code range.contains(e * factor)},
     * and {@code !newrange.contains(e)} implies that {@code !range.contains(e * factor)}.
     */
    static Range<Integer>factorRange(Range<Integer> range, int factor) {
        if (factor == 1) {
            return range;
        }
        return Range.create(divUp(range.getLower(), factor), range.getUpper() / factor);
    }

    /** Returns the equivalent factored range {@code newrange}, where for every
     * {@code e}: {@code newrange.contains(e)} implies that {@code range.contains(e * factor)},
     * and {@code !newrange.contains(e)} implies that {@code !range.contains(e * factor)}.
     */
    static Range<Long>factorRange(Range<Long> range, long factor) {
        if (factor == 1) {
            return range;
        }
        return Range.create(divUp(range.getLower(), factor), range.getUpper() / factor);
    }

    private static Rational scaleRatio(Rational ratio, int num, int den) {
        int common = gcd(num, den);
        num /= common;
        den /= common;
        return new Rational(
                (int)(ratio.getNumerator() * (double)num),     // saturate to int
                (int)(ratio.getDenominator() * (double)den));  // saturate to int
    }

    static Range<Rational> scaleRange(Range<Rational> range, int num, int den) {
        if (num == den) {
            return range;
        }
        return Range.create(
                scaleRatio(range.getLower(), num, den),
                scaleRatio(range.getUpper(), num, den));
    }

    static Range<Integer> alignRange(Range<Integer> range, int align) {
        return range.intersect(
                divUp(range.getLower(), align) * align,
                (range.getUpper() / align) * align);
    }

    static int divUp(int num, int den) {
        return (num + den - 1) / den;
    }

    private static long divUp(long num, long den) {
        return (num + den - 1) / den;
    }

    /**
     * Returns least common multiple
     */
    private static long lcm(int a, int b) {
        if (a == 0 || b == 0) {
            throw new IllegalArgumentException("lce is not defined for zero arguments");
        }
        return (long)a * b / gcd(a, b);
    }

    static Range<Integer> intRangeFor(double v) {
        return Range.create((int)v, (int)Math.ceil(v));
    }

    static Range<Long> longRangeFor(double v) {
        return Range.create((long)v, (long)Math.ceil(v));
    }

    static Size parseSize(Object o, Size fallback) {
        try {
            return Size.parseSize((String) o);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (NullPointerException e) {
            return fallback;
        }
        Log.w(TAG, "could not parse size '" + o + "'");
        return fallback;
    }

    static int parseIntSafely(Object o, int fallback) {
        try {
            String s = (String)o;
            return Integer.parseInt(s);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (NullPointerException e) {
            return fallback;
        }
        Log.w(TAG, "could not parse integer '" + o + "'");
        return fallback;
    }

    static Range<Integer> parseIntRange(Object o, Range<Integer> fallback) {
        try {
            String s = (String)o;
            int ix = s.indexOf('-');
            if (ix >= 0) {
                return Range.create(
                        Integer.parseInt(s.substring(0, ix), 10),
                        Integer.parseInt(s.substring(ix + 1), 10));
            }
            int value = Integer.parseInt(s);
            return Range.create(value, value);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (NullPointerException e) {
            return fallback;
        } catch (IllegalArgumentException e) {
        }
        Log.w(TAG, "could not parse integer range '" + o + "'");
        return fallback;
    }

    static Range<Long> parseLongRange(Object o, Range<Long> fallback) {
        try {
            String s = (String)o;
            int ix = s.indexOf('-');
            if (ix >= 0) {
                return Range.create(
                        Long.parseLong(s.substring(0, ix), 10),
                        Long.parseLong(s.substring(ix + 1), 10));
            }
            long value = Long.parseLong(s);
            return Range.create(value, value);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (NullPointerException e) {
            return fallback;
        } catch (IllegalArgumentException e) {
        }
        Log.w(TAG, "could not parse long range '" + o + "'");
        return fallback;
    }

    static Range<Rational> parseRationalRange(Object o, Range<Rational> fallback) {
        try {
            String s = (String)o;
            int ix = s.indexOf('-');
            if (ix >= 0) {
                return Range.create(
                        Rational.parseRational(s.substring(0, ix)),
                        Rational.parseRational(s.substring(ix + 1)));
            }
            Rational value = Rational.parseRational(s);
            return Range.create(value, value);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (NullPointerException e) {
            return fallback;
        } catch (IllegalArgumentException e) {
        }
        Log.w(TAG, "could not parse rational range '" + o + "'");
        return fallback;
    }

    static Pair<Size, Size> parseSizeRange(Object o) {
        try {
            String s = (String)o;
            int ix = s.indexOf('-');
            if (ix >= 0) {
                return Pair.create(
                        Size.parseSize(s.substring(0, ix)),
                        Size.parseSize(s.substring(ix + 1)));
            }
            Size value = Size.parseSize(s);
            return Pair.create(value, value);
        } catch (ClassCastException e) {
        } catch (NumberFormatException e) {
        } catch (NullPointerException e) {
            return null;
        } catch (IllegalArgumentException e) {
        }
        Log.w(TAG, "could not parse size range '" + o + "'");
        return null;
    }
}
