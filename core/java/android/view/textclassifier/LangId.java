/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.view.textclassifier;

/**
 *  Java wrapper for LangId native library interface.
 *  This class is used to detect languages in text.
 */
final class LangId {

    static {
        System.loadLibrary("textclassifier");
    }

    private final long mModelPtr;

    /**
     * Creates a new instance of LangId predictor, using the provided model image.
     */
    LangId(int fd) {
        mModelPtr = nativeNew(fd);
    }

    /**
     * Detects the language for given text.
     */
    public ClassificationResult[] findLanguages(String text) {
        return nativeFindLanguages(mModelPtr, text);
    }

    /**
     * Frees up the allocated memory.
     */
    public void close() {
        nativeClose(mModelPtr);
    }

    private static native long nativeNew(int fd);

    private static native ClassificationResult[] nativeFindLanguages(
            long context, String text);

    private static native void nativeClose(long context);

    /** Classification result for findLanguage method. */
    static final class ClassificationResult {
        final String mLanguage;
        /** float range: 0 - 1 */
        final float mScore;

        ClassificationResult(String language, float score) {
            mLanguage = language;
            mScore = score;
        }
    }
}
