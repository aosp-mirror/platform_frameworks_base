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

import android.annotation.Nullable;
import android.content.res.AssetFileDescriptor;

/**
 *  Java wrapper for SmartSelection native library interface.
 *  This library is used for detecting entities in text.
 */
final class SmartSelection {

    static {
        System.loadLibrary("textclassifier");
    }

    /** Hints the classifier that this may be a url. */
    static final int HINT_FLAG_URL = 0x01;
    /** Hints the classifier that this may be an email. */
    static final int HINT_FLAG_EMAIL = 0x02;

    private final long mCtx;

    /**
     * Creates a new instance of SmartSelect predictor, using the provided model image,
     * given as a file descriptor.
     */
    SmartSelection(int fd) {
        mCtx = nativeNew(fd);
    }

    /**
     * Creates a new instance of SmartSelect predictor, using the provided model image, given as a
     * file path.
     */
    SmartSelection(String path) {
        mCtx = nativeNewFromPath(path);
    }

    /**
     * Creates a new instance of SmartSelect predictor, using the provided model image, given as an
     * AssetFileDescriptor.
     */
    SmartSelection(AssetFileDescriptor afd) {
        mCtx = nativeNewFromAssetFileDescriptor(afd, afd.getStartOffset(), afd.getLength());
        if (mCtx == 0L) {
            throw new IllegalArgumentException(
                "Couldn't initialize TC from given AssetFileDescriptor");
        }
    }

    /**
     * Given a string context and current selection, computes the SmartSelection suggestion.
     *
     * The begin and end are character indices into the context UTF8 string. selectionBegin is the
     * character index where the selection begins, and selectionEnd is the index of one character
     * past the selection span.
     *
     * The return value is an array of two ints: suggested selection beginning and end, with the
     * same semantics as the input selectionBeginning and selectionEnd.
     */
    public int[] suggest(String context, int selectionBegin, int selectionEnd) {
        return nativeSuggest(mCtx, context, selectionBegin, selectionEnd);
    }

    /**
     * Given a string context and current selection, classifies the type of the selected text.
     *
     * The begin and end params are character indices in the context string.
     *
     * Returns an array of ClassificationResult objects with the probability
     * scores for different collections.
     */
    public ClassificationResult[] classifyText(
            String context, int selectionBegin, int selectionEnd, int hintFlags) {
        return nativeClassifyText(mCtx, context, selectionBegin, selectionEnd, hintFlags);
    }

    /**
     * Annotates given input text. Every word of the input is a part of some annotation.
     * The annotations are sorted by their position in the context string.
     * The annotations do not overlap.
     */
    public AnnotatedSpan[] annotate(String text) {
        return nativeAnnotate(mCtx, text);
    }

    /**
     * Frees up the allocated memory.
     */
    public void close() {
        nativeClose(mCtx);
    }

    /**
     * Returns a comma separated list of locales supported by the model as BCP 47 tags.
     */
    public static String getLanguages(int fd) {
        return nativeGetLanguage(fd);
    }

    /**
     * Returns the version of the model.
     */
    public static int getVersion(int fd) {
        return nativeGetVersion(fd);
    }

    private static native long nativeNew(int fd);

    private static native long nativeNewFromPath(String path);

    private static native long nativeNewFromAssetFileDescriptor(AssetFileDescriptor afd,
                                                                long offset, long size);

    private static native int[] nativeSuggest(
            long context, String text, int selectionBegin, int selectionEnd);

    private static native ClassificationResult[] nativeClassifyText(
            long context, String text, int selectionBegin, int selectionEnd, int hintFlags);

    private static native AnnotatedSpan[] nativeAnnotate(long context, String text);

    private static native void nativeClose(long context);

    private static native String nativeGetLanguage(int fd);

    private static native int nativeGetVersion(int fd);

    /** Classification result for classifyText method. */
    static final class ClassificationResult {
        final String mCollection;
        /** float range: 0 - 1 */
        final float mScore;
        @Nullable final DatetimeParseResult mDatetime;

        ClassificationResult(String collection, float score) {
            mCollection = collection;
            mScore = score;
            mDatetime = null;
        }

        ClassificationResult(String collection, float score, DatetimeParseResult datetime) {
            mCollection = collection;
            mScore = score;
            mDatetime = datetime;
        }
    }

    /** Parsed date information for the classification result. */
    static final class DatetimeParseResult {
        long mMsSinceEpoch;
    }

    /** Represents a result of Annotate call. */
    public static final class AnnotatedSpan {
        final int mStartIndex;
        final int mEndIndex;
        final ClassificationResult[] mClassification;

        AnnotatedSpan(int startIndex, int endIndex, ClassificationResult[] classification) {
            mStartIndex = startIndex;
            mEndIndex = endIndex;
            mClassification = classification;
        }

        public int getStartIndex() {
            return mStartIndex;
        }

        public int getEndIndex() {
            return mEndIndex;
        }

        public ClassificationResult[] getClassification() {
            return mClassification;
        }
    }
}
