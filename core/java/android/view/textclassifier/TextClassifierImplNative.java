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

import android.content.res.AssetFileDescriptor;

/**
 * Java wrapper for TextClassifier native library interface. This library is used for detecting
 * entities in text.
 */
final class TextClassifierImplNative {

    static {
        System.loadLibrary("textclassifier");
    }

    private final long mModelPtr;

    /**
     * Creates a new instance of TextClassifierImplNative, using the provided model image, given as
     * a file descriptor.
     */
    TextClassifierImplNative(int fd) {
        mModelPtr = nativeNew(fd);
        if (mModelPtr == 0L) {
            throw new IllegalArgumentException("Couldn't initialize TC from file descriptor.");
        }
    }

    /**
     * Creates a new instance of TextClassifierImplNative, using the provided model image, given as
     * a file path.
     */
    TextClassifierImplNative(String path) {
        mModelPtr = nativeNewFromPath(path);
        if (mModelPtr == 0L) {
            throw new IllegalArgumentException("Couldn't initialize TC from given file.");
        }
    }

    /**
     * Creates a new instance of TextClassifierImplNative, using the provided model image, given as
     * an AssetFileDescriptor.
     */
    TextClassifierImplNative(AssetFileDescriptor afd) {
        mModelPtr = nativeNewFromAssetFileDescriptor(afd, afd.getStartOffset(), afd.getLength());
        if (mModelPtr == 0L) {
            throw new IllegalArgumentException(
                    "Couldn't initialize TC from given AssetFileDescriptor");
        }
    }

    /**
     * Given a string context and current selection, computes the SmartSelection suggestion.
     *
     * <p>The begin and end are character indices into the context UTF8 string. selectionBegin is
     * the character index where the selection begins, and selectionEnd is the index of one
     * character past the selection span.
     *
     * <p>The return value is an array of two ints: suggested selection beginning and end, with the
     * same semantics as the input selectionBeginning and selectionEnd.
     */
    public int[] suggestSelection(
            String context, int selectionBegin, int selectionEnd, SelectionOptions options) {
        return nativeSuggestSelection(mModelPtr, context, selectionBegin, selectionEnd, options);
    }

    /**
     * Given a string context and current selection, classifies the type of the selected text.
     *
     * <p>The begin and end params are character indices in the context string.
     *
     * <p>Returns an array of ClassificationResult objects with the probability scores for different
     * collections.
     */
    public ClassificationResult[] classifyText(
            String context, int selectionBegin, int selectionEnd, ClassificationOptions options) {
        return nativeClassifyText(mModelPtr, context, selectionBegin, selectionEnd, options);
    }

    /**
     * Annotates given input text. The annotations should cover the whole input context except for
     * whitespaces, and are sorted by their position in the context string.
     */
    public AnnotatedSpan[] annotate(String text, AnnotationOptions options) {
        return nativeAnnotate(mModelPtr, text, options);
    }

    /** Frees up the allocated memory. */
    public void close() {
        nativeClose(mModelPtr);
    }

    /** Returns a comma separated list of locales supported by the model as BCP 47 tags. */
    public static String getLocales(int fd) {
        return nativeGetLocales(fd);
    }

    /** Returns the version of the model. */
    public static int getVersion(int fd) {
        return nativeGetVersion(fd);
    }

    /** Represents a datetime parsing result from classifyText calls. */
    public static final class DatetimeResult {
        static final int GRANULARITY_YEAR = 0;
        static final int GRANULARITY_MONTH = 1;
        static final int GRANULARITY_WEEK = 2;
        static final int GRANULARITY_DAY = 3;
        static final int GRANULARITY_HOUR = 4;
        static final int GRANULARITY_MINUTE = 5;
        static final int GRANULARITY_SECOND = 6;

        private final long mTimeMsUtc;
        private final int mGranularity;

        DatetimeResult(long timeMsUtc, int granularity) {
            mGranularity = granularity;
            mTimeMsUtc = timeMsUtc;
        }

        public long getTimeMsUtc() {
            return mTimeMsUtc;
        }

        public int getGranularity() {
            return mGranularity;
        }
    }

    /** Represents a result of classifyText method call. */
    public static final class ClassificationResult {
        private final String mCollection;
        private final float mScore;
        private final DatetimeResult mDatetimeResult;

        ClassificationResult(
                String collection, float score, DatetimeResult datetimeResult) {
            mCollection = collection;
            mScore = score;
            mDatetimeResult = datetimeResult;
        }

        public String getCollection() {
            if (mCollection.equals(TextClassifier.TYPE_DATE) && mDatetimeResult != null) {
                switch (mDatetimeResult.getGranularity()) {
                    case DatetimeResult.GRANULARITY_HOUR:
                        // fall through
                    case DatetimeResult.GRANULARITY_MINUTE:
                        // fall through
                    case DatetimeResult.GRANULARITY_SECOND:
                        return TextClassifier.TYPE_DATE_TIME;
                    default:
                        return TextClassifier.TYPE_DATE;
                }
            }
            return mCollection;
        }

        public float getScore() {
            return mScore;
        }

        public DatetimeResult getDatetimeResult() {
            return mDatetimeResult;
        }
    }

    /** Represents a result of Annotate call. */
    public static final class AnnotatedSpan {
        private final int mStartIndex;
        private final int mEndIndex;
        private final ClassificationResult[] mClassification;

        AnnotatedSpan(
                int startIndex, int endIndex, ClassificationResult[] classification) {
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

    /** Represents options for the suggestSelection call. */
    public static final class SelectionOptions {
        private final String mLocales;

        SelectionOptions(String locales) {
            mLocales = locales;
        }

        public String getLocales() {
            return mLocales;
        }
    }

    /** Represents options for the classifyText call. */
    public static final class ClassificationOptions {
        private final long mReferenceTimeMsUtc;
        private final String mReferenceTimezone;
        private final String mLocales;

        ClassificationOptions(long referenceTimeMsUtc, String referenceTimezone, String locale) {
            mReferenceTimeMsUtc = referenceTimeMsUtc;
            mReferenceTimezone = referenceTimezone;
            mLocales = locale;
        }

        public long getReferenceTimeMsUtc() {
            return mReferenceTimeMsUtc;
        }

        public String getReferenceTimezone() {
            return mReferenceTimezone;
        }

        public String getLocale() {
            return mLocales;
        }
    }

    /** Represents options for the Annotate call. */
    public static final class AnnotationOptions {
        private final long mReferenceTimeMsUtc;
        private final String mReferenceTimezone;
        private final String mLocales;

        AnnotationOptions(long referenceTimeMsUtc, String referenceTimezone, String locale) {
            mReferenceTimeMsUtc = referenceTimeMsUtc;
            mReferenceTimezone = referenceTimezone;
            mLocales = locale;
        }

        public long getReferenceTimeMsUtc() {
            return mReferenceTimeMsUtc;
        }

        public String getReferenceTimezone() {
            return mReferenceTimezone;
        }

        public String getLocale() {
            return mLocales;
        }
    }

    private static native long nativeNew(int fd);

    private static native long nativeNewFromPath(String path);

    private static native long nativeNewFromAssetFileDescriptor(
            AssetFileDescriptor afd, long offset, long size);

    private static native int[] nativeSuggestSelection(
            long context,
            String text,
            int selectionBegin,
            int selectionEnd,
            SelectionOptions options);

    private static native ClassificationResult[] nativeClassifyText(
            long context,
            String text,
            int selectionBegin,
            int selectionEnd,
            ClassificationOptions options);

    private static native AnnotatedSpan[] nativeAnnotate(
            long context, String text, AnnotationOptions options);

    private static native void nativeClose(long context);

    private static native String nativeGetLocales(int fd);

    private static native int nativeGetVersion(int fd);
}
