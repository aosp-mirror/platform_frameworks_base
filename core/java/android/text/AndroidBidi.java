/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.text.Layout.Directions;

/**
 * Access the ICU bidi implementation.
 * @hide
 */
/* package */ class AndroidBidi {

    public static int bidi(int dir, char[] chs, byte[] chInfo, int n, boolean haveInfo) {
        if (chs == null || chInfo == null) {
            throw new NullPointerException();
        }

        if (n < 0 || chs.length < n || chInfo.length < n) {
            throw new IndexOutOfBoundsException();
        }

        switch(dir) {
            case Layout.DIR_REQUEST_LTR: dir = 0; break;
            case Layout.DIR_REQUEST_RTL: dir = 1; break;
            case Layout.DIR_REQUEST_DEFAULT_LTR: dir = -2; break;
            case Layout.DIR_REQUEST_DEFAULT_RTL: dir = -1; break;
            default: dir = 0; break;
        }

        int result = runBidi(dir, chs, chInfo, n, haveInfo);
        result = (result & 0x1) == 0 ? Layout.DIR_LEFT_TO_RIGHT : Layout.DIR_RIGHT_TO_LEFT;
        return result;
    }

    /**
     * Returns run direction information for a line within a paragraph.
     *
     * @param dir base line direction, either Layout.DIR_LEFT_TO_RIGHT or
     *     Layout.DIR_RIGHT_TO_LEFT
     * @param levels levels as returned from {@link #bidi}
     * @param lstart start of the line in the levels array
     * @param chars the character array (used to determine whitespace)
     * @param cstart the start of the line in the chars array
     * @param len the length of the line
     * @return the directions
     */
    public static Directions directions(int dir, byte[] levels, int lstart,
            char[] chars, int cstart, int len) {

        int baseLevel = dir == Layout.DIR_LEFT_TO_RIGHT ? 0 : 1;
        int curLevel = levels[lstart];
        int minLevel = curLevel;
        int runCount = 1;
        for (int i = lstart + 1, e = lstart + len; i < e; ++i) {
            int level = levels[i];
            if (level != curLevel) {
                curLevel = level;
                ++runCount;
            }
        }

        // add final run for trailing counter-directional whitespace
        int visLen = len;
        if ((curLevel & 1) != (baseLevel & 1)) {
            // look for visible end
            while (--visLen >= 0) {
                char ch = chars[cstart + visLen];

                if (ch == '\n') {
                    --visLen;
                    break;
                }

                if (ch != ' ' && ch != '\t') {
                    break;
                }
            }
            ++visLen;
            if (visLen != len) {
                ++runCount;
            }
        }

        if (runCount == 1 && minLevel == baseLevel) {
            // we're done, only one run on this line
            if ((minLevel & 1) != 0) {
                return Layout.DIRS_ALL_RIGHT_TO_LEFT;
            }
            return Layout.DIRS_ALL_LEFT_TO_RIGHT;
        }

        int[] ld = new int[runCount * 2];
        int maxLevel = minLevel;
        int levelBits = minLevel << Layout.RUN_LEVEL_SHIFT;
        {
            // Start of first pair is always 0, we write
            // length then start at each new run, and the
            // last run length after we're done.
            int n = 1;
            int prev = lstart;
            curLevel = minLevel;
            for (int i = lstart, e = lstart + visLen; i < e; ++i) {
                int level = levels[i];
                if (level != curLevel) {
                    curLevel = level;
                    if (level > maxLevel) {
                        maxLevel = level;
                    } else if (level < minLevel) {
                        minLevel = level;
                    }
                    // XXX ignore run length limit of 2^RUN_LEVEL_SHIFT
                    ld[n++] = (i - prev) | levelBits;
                    ld[n++] = i - lstart;
                    levelBits = curLevel << Layout.RUN_LEVEL_SHIFT;
                    prev = i;
                }
            }
            ld[n] = (lstart + visLen - prev) | levelBits;
            if (visLen < len) {
                ld[++n] = visLen;
                ld[++n] = (len - visLen) | (baseLevel << Layout.RUN_LEVEL_SHIFT);
            }
        }

        // See if we need to swap any runs.
        // If the min level run direction doesn't match the base
        // direction, we always need to swap (at this point
        // we have more than one run).
        // Otherwise, we don't need to swap the lowest level.
        // Since there are no logically adjacent runs at the same
        // level, if the max level is the same as the (new) min
        // level, we have a series of alternating levels that
        // is already in order, so there's no more to do.
        //
        boolean swap;
        if ((minLevel & 1) == baseLevel) {
            minLevel += 1;
            swap = maxLevel > minLevel;
        } else {
            swap = runCount > 1;
        }
        if (swap) {
            for (int level = maxLevel - 1; level >= minLevel; --level) {
                for (int i = 0; i < ld.length; i += 2) {
                    if (levels[ld[i]] >= level) {
                        int e = i + 2;
                        while (e < ld.length && levels[ld[e]] >= level) {
                            e += 2;
                        }
                        for (int low = i, hi = e - 2; low < hi; low += 2, hi -= 2) {
                            int x = ld[low]; ld[low] = ld[hi]; ld[hi] = x;
                            x = ld[low+1]; ld[low+1] = ld[hi+1]; ld[hi+1] = x;
                        }
                        i = e + 2;
                    }
                }
            }
        }
        return new Directions(ld);
    }

    private native static int runBidi(int dir, char[] chs, byte[] chInfo, int n, boolean haveInfo);
}