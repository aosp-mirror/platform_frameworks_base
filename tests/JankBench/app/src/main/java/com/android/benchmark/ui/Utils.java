/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.benchmark.ui;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.Random;

public class Utils {

    private static final int RANDOM_WORD_LENGTH = 10;

    public static String getRandomWord(Random random, int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            char base = random.nextBoolean() ? 'A' : 'a';
            char nextChar = (char)(random.nextInt(26) + base);
            builder.append(nextChar);
        }
        return builder.toString();
    }

    public static String[] buildStringList(int count) {
        Random random = new Random(0);
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = getRandomWord(random, RANDOM_WORD_LENGTH);
        }

        return result;
    }

     // a small number of strings reused frequently, expected to hit
    // in the word-granularity text layout cache
    static final String[] CACHE_HIT_STRINGS = new String[] {
            "a",
            "small",
            "number",
            "of",
            "strings",
            "reused",
            "frequently"
    };

    private static final int WORDS_IN_PARAGRAPH = 150;

    // misses are fairly long 'words' to ensure they miss
    private static final int PARAGRAPH_MISS_MIN_LENGTH = 4;
    private static final int PARAGRAPH_MISS_MAX_LENGTH = 9;

    static String[] buildParagraphListWithHitPercentage(int paragraphCount, int hitPercentage) {
        if (hitPercentage < 0 || hitPercentage > 100) throw new IllegalArgumentException();

        String[] strings = new String[paragraphCount];
        Random random = new Random(0);
        for (int i = 0; i < strings.length; i++) {
            StringBuilder result = new StringBuilder();
            for (int word = 0; word < WORDS_IN_PARAGRAPH; word++) {
                if (word != 0) {
                    result.append(" ");
                }
                if (random.nextInt(100) < hitPercentage) {
                    // add a common word, which is very likely to hit in the cache
                    result.append(CACHE_HIT_STRINGS[random.nextInt(CACHE_HIT_STRINGS.length)]);
                } else {
                    // construct a random word, which will *most likely* miss
                    int length = PARAGRAPH_MISS_MIN_LENGTH;
                    length += random.nextInt(PARAGRAPH_MISS_MAX_LENGTH - PARAGRAPH_MISS_MIN_LENGTH);

                    result.append(getRandomWord(random, length));
                }
            }
            strings[i] = result.toString();
        }

        return strings;
    }


    public static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) > reqHeight
                    && (halfWidth / inSampleSize) > reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    public static Bitmap decodeSampledBitmapFromResource(Resources res, int resId,
                                                   int reqWidth, int reqHeight) {

        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        // Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res, resId, options);
    }

}
