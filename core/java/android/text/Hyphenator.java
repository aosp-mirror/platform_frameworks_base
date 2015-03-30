/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Locale;

/**
 * Hyphenator is a wrapper class for a native implementation of automatic hyphenation,
 * in essence finding valid hyphenation opportunities in a word.
 *
 * @hide
 */
/* package */ class Hyphenator {
    // This class has deliberately simple lifetime management (no finalizer) because in
    // the common case a process will use a very small number of locales.

    private static String TAG = "Hyphenator";

    static HashMap<Locale, Hyphenator> sMap = new HashMap<Locale, Hyphenator>();

    private long mNativePtr;

    private Hyphenator(long nativePtr) {
        mNativePtr = nativePtr;
    }

    public static long get(Locale locale) {
        synchronized (sMap) {
            Hyphenator result = sMap.get(locale);
            if (result == null) {
                result = loadHyphenator(locale);
                sMap.put(locale, result);
            }
            return result == null ? 0 : result.mNativePtr;
        }
    }

    private static Hyphenator loadHyphenator(Locale locale) {
        // TODO: find pattern dictionary (from system location) that best matches locale
        if (Locale.US.equals(locale)) {
            File f = new File("/data/local/tmp/hyph-en-us.pat.txt");
            try {
                RandomAccessFile rf = new RandomAccessFile(f, "r");
                byte[] buf = new byte[(int)rf.length()];
                rf.read(buf);
                rf.close();
                String patternData = new String(buf);
                long nativePtr = StaticLayout.nLoadHyphenator(patternData);
                return new Hyphenator(nativePtr);
            } catch (IOException e) {
                Log.e(TAG, "error loading hyphenation " + f);
            }
        }
        return null;
    }
}
