/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.service.textservice;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.text.TextUtils;
import android.text.method.WordIterator;
import android.util.Log;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import com.android.internal.textservice.ISpellCheckerService;
import com.android.internal.textservice.ISpellCheckerServiceCallback;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;

import java.lang.ref.WeakReference;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Locale;

/**
 * SpellCheckerService provides an abstract base class for a spell checker.
 * This class combines a service to the system with the spell checker service interface that
 * spell checker must implement.
 *
 * <p>In addition to the normal Service lifecycle methods, this class
 * introduces a new specific callback that subclasses should override
 * {@link #createSession()} to provide a spell checker session that is corresponding
 * to requested language and so on. The spell checker session returned by this method
 * should extend {@link SpellCheckerService.Session}.
 * </p>
 *
 * <h3>Returning spell check results</h3>
 *
 * <p>{@link SpellCheckerService.Session#onGetSuggestions(TextInfo, int)}
 * should return spell check results.
 * It receives {@link android.view.textservice.TextInfo} and returns
 * {@link android.view.textservice.SuggestionsInfo} for the input.
 * You may want to override
 * {@link SpellCheckerService.Session#onGetSuggestionsMultiple(TextInfo[], int, boolean)} for
 * better performance and quality.
 * </p>
 *
 * <p>Please note that {@link SpellCheckerService.Session#getLocale()} does not return a valid
 * locale before {@link SpellCheckerService.Session#onCreate()} </p>
 *
 */
public abstract class SpellCheckerService extends Service {
    private static final String TAG = SpellCheckerService.class.getSimpleName();
    private static final boolean DBG = false;
    public static final String SERVICE_INTERFACE =
            "android.service.textservice.SpellCheckerService";

    private final SpellCheckerServiceBinder mBinder = new SpellCheckerServiceBinder(this);


    /**
     * Implement to return the implementation of the internal spell checker
     * service interface. Subclasses should not override.
     */
    @Override
    public final IBinder onBind(final Intent intent) {
        if (DBG) {
            Log.w(TAG, "onBind");
        }
        return mBinder;
    }

    /**
     * Factory method to create a spell checker session impl
     * @return SpellCheckerSessionImpl which should be overridden by a concrete implementation.
     */
    public abstract Session createSession();

    /**
     * This abstract class should be overridden by a concrete implementation of a spell checker.
     */
    public static abstract class Session {
        private InternalISpellCheckerSession mInternalSession;
        private volatile SentenceLevelAdapter mSentenceLevelAdapter;

        /**
         * @hide
         */
        public final void setInternalISpellCheckerSession(InternalISpellCheckerSession session) {
            mInternalSession = session;
        }

        /**
         * This is called after the class is initialized, at which point it knows it can call
         * getLocale() etc...
         */
        public abstract void onCreate();

        /**
         * Get suggestions for specified text in TextInfo.
         * This function will run on the incoming IPC thread.
         * So, this is not called on the main thread,
         * but will be called in series on another thread.
         * @param textInfo the text metadata
         * @param suggestionsLimit the maximum number of suggestions to be returned
         * @return SuggestionsInfo which contains suggestions for textInfo
         */
        public abstract SuggestionsInfo onGetSuggestions(TextInfo textInfo, int suggestionsLimit);

        /**
         * A batch process of onGetSuggestions.
         * This function will run on the incoming IPC thread.
         * So, this is not called on the main thread,
         * but will be called in series on another thread.
         * @param textInfos an array of the text metadata
         * @param suggestionsLimit the maximum number of suggestions to be returned
         * @param sequentialWords true if textInfos can be treated as sequential words.
         * @return an array of {@link SentenceSuggestionsInfo} returned by
         * {@link SpellCheckerService.Session#onGetSuggestions(TextInfo, int)}
         */
        public SuggestionsInfo[] onGetSuggestionsMultiple(TextInfo[] textInfos,
                int suggestionsLimit, boolean sequentialWords) {
            final int length = textInfos.length;
            final SuggestionsInfo[] retval = new SuggestionsInfo[length];
            for (int i = 0; i < length; ++i) {
                retval[i] = onGetSuggestions(textInfos[i], suggestionsLimit);
                retval[i].setCookieAndSequence(
                        textInfos[i].getCookie(), textInfos[i].getSequence());
            }
            return retval;
        }

        /**
         * Get sentence suggestions for specified texts in an array of TextInfo.
         * The default implementation splits the input text to words and returns
         * {@link SentenceSuggestionsInfo} which contains suggestions for each word.
         * This function will run on the incoming IPC thread.
         * So, this is not called on the main thread,
         * but will be called in series on another thread.
         * When you override this method, make sure that suggestionsLimit is applied to suggestions
         * that share the same start position and length.
         * @param textInfos an array of the text metadata
         * @param suggestionsLimit the maximum number of suggestions to be returned
         * @return an array of {@link SentenceSuggestionsInfo} returned by
         * {@link SpellCheckerService.Session#onGetSuggestions(TextInfo, int)}
         */
        public SentenceSuggestionsInfo[] onGetSentenceSuggestionsMultiple(TextInfo[] textInfos,
                int suggestionsLimit) {
            if (textInfos == null || textInfos.length == 0) {
                return SentenceLevelAdapter.EMPTY_SENTENCE_SUGGESTIONS_INFOS;
            }
            if (DBG) {
                Log.d(TAG, "onGetSentenceSuggestionsMultiple: + " + textInfos.length + ", "
                        + suggestionsLimit);
            }
            if (mSentenceLevelAdapter == null) {
                synchronized(this) {
                    if (mSentenceLevelAdapter == null) {
                        final String localeStr = getLocale();
                        if (!TextUtils.isEmpty(localeStr)) {
                            mSentenceLevelAdapter = new SentenceLevelAdapter(new Locale(localeStr));
                        }
                    }
                }
            }
            if (mSentenceLevelAdapter == null) {
                return SentenceLevelAdapter.EMPTY_SENTENCE_SUGGESTIONS_INFOS;
            }
            final int infosSize = textInfos.length;
            final SentenceSuggestionsInfo[] retval = new SentenceSuggestionsInfo[infosSize];
            for (int i = 0; i < infosSize; ++i) {
                final SentenceLevelAdapter.SentenceTextInfoParams textInfoParams =
                        mSentenceLevelAdapter.getSplitWords(textInfos[i]);
                final ArrayList<SentenceLevelAdapter.SentenceWordItem> mItems =
                        textInfoParams.mItems;
                final int itemsSize = mItems.size();
                final TextInfo[] splitTextInfos = new TextInfo[itemsSize];
                for (int j = 0; j < itemsSize; ++j) {
                    splitTextInfos[j] = mItems.get(j).mTextInfo;
                }
                retval[i] = SentenceLevelAdapter.reconstructSuggestions(
                        textInfoParams, onGetSuggestionsMultiple(
                                splitTextInfos, suggestionsLimit, true));
            }
            return retval;
        }

        /**
         * Request to abort all tasks executed in SpellChecker.
         * This function will run on the incoming IPC thread.
         * So, this is not called on the main thread,
         * but will be called in series on another thread.
         */
        public void onCancel() {}

        /**
         * Request to close this session.
         * This function will run on the incoming IPC thread.
         * So, this is not called on the main thread,
         * but will be called in series on another thread.
         */
        public void onClose() {}

        /**
         * @return Locale for this session
         */
        public String getLocale() {
            return mInternalSession.getLocale();
        }

        /**
         * @return Bundle for this session
         */
        public Bundle getBundle() {
            return mInternalSession.getBundle();
        }

        /**
         * Returns result attributes supported for this session.
         *
         * <p>The session implementation should not set attributes that are not included in the
         * return value of {@code getSupportedAttributes()} when creating {@link SuggestionsInfo}.
         *
         * @return The supported result attributes for this session
         */
        public @SuggestionsInfo.ResultAttrs int getSupportedAttributes() {
            return mInternalSession.getSupportedAttributes();
        }
    }

    // Preventing from exposing ISpellCheckerSession.aidl, create an internal class.
    private static class InternalISpellCheckerSession extends ISpellCheckerSession.Stub {
        private ISpellCheckerSessionListener mListener;
        private final Session mSession;
        private final String mLocale;
        private final Bundle mBundle;
        private final @SuggestionsInfo.ResultAttrs int mSupportedAttributes;

        public InternalISpellCheckerSession(String locale, ISpellCheckerSessionListener listener,
                Bundle bundle, Session session,
                @SuggestionsInfo.ResultAttrs int supportedAttributes) {
            mListener = listener;
            mSession = session;
            mLocale = locale;
            mBundle = bundle;
            mSupportedAttributes = supportedAttributes;
            session.setInternalISpellCheckerSession(this);
        }

        @Override
        public void onGetSuggestionsMultiple(
                TextInfo[] textInfos, int suggestionsLimit, boolean sequentialWords) {
            int pri = Process.getThreadPriority(Process.myTid());
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                mListener.onGetSuggestions(
                        mSession.onGetSuggestionsMultiple(
                                textInfos, suggestionsLimit, sequentialWords));
            } catch (RemoteException e) {
            } finally {
                Process.setThreadPriority(pri);
            }
        }

        @Override
        public void onGetSentenceSuggestionsMultiple(TextInfo[] textInfos, int suggestionsLimit) {
            try {
                mListener.onGetSentenceSuggestions(
                        mSession.onGetSentenceSuggestionsMultiple(textInfos, suggestionsLimit));
            } catch (RemoteException e) {
            }
        }

        @Override
        public void onCancel() {
            int pri = Process.getThreadPriority(Process.myTid());
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                mSession.onCancel();
            } finally {
                Process.setThreadPriority(pri);
            }
        }

        @Override
        public void onClose() {
            int pri = Process.getThreadPriority(Process.myTid());
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                mSession.onClose();
            } finally {
                Process.setThreadPriority(pri);
                mListener = null;
            }
        }

        public String getLocale() {
            return mLocale;
        }

        public Bundle getBundle() {
            return mBundle;
        }

        public @SuggestionsInfo.ResultAttrs int getSupportedAttributes() {
            return mSupportedAttributes;
        }
    }

    private static class SpellCheckerServiceBinder extends ISpellCheckerService.Stub {
        private final WeakReference<SpellCheckerService> mInternalServiceRef;

        public SpellCheckerServiceBinder(SpellCheckerService service) {
            mInternalServiceRef = new WeakReference<SpellCheckerService>(service);
        }

        /**
         * Called from the system when an application is requesting a new spell checker session.
         *
         * <p>Note: This is an internal protocol used by the system to establish spell checker
         * sessions, which is not guaranteed to be stable and is subject to change.</p>
         *
         * @param locale locale to be returned from {@link Session#getLocale()}
         * @param listener IPC channel object to be used to implement
         *                 {@link Session#onGetSuggestionsMultiple(TextInfo[], int, boolean)} and
         *                 {@link Session#onGetSuggestions(TextInfo, int)}
         * @param bundle bundle to be returned from {@link Session#getBundle()}
         * @param supportedAttributes A union of {@link SuggestionsInfo} attributes that the spell
         *                            checker can set in the spell checking results.
         * @param callback IPC channel to return the result to the caller in an asynchronous manner
         */
        @Override
        public void getISpellCheckerSession(
                String locale, ISpellCheckerSessionListener listener, Bundle bundle,
                @SuggestionsInfo.ResultAttrs int supportedAttributes,
                ISpellCheckerServiceCallback callback) {
            final SpellCheckerService service = mInternalServiceRef.get();
            final InternalISpellCheckerSession internalSession;
            if (service == null) {
                // If the owner SpellCheckerService object was already destroyed and got GC-ed,
                // the weak-reference returns null and we should just ignore this request.
                internalSession = null;
            } else {
                final Session session = service.createSession();
                internalSession = new InternalISpellCheckerSession(
                        locale, listener, bundle, session, supportedAttributes);
                session.onCreate();
            }
            try {
                callback.onSessionCreated(internalSession);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Adapter class to accommodate word level spell checking APIs to sentence level spell checking
     * APIs used in
     * {@link SpellCheckerService.Session#onGetSuggestionsMultiple(TextInfo[], int, boolean)}
     */
    private static class SentenceLevelAdapter {
        public static final SentenceSuggestionsInfo[] EMPTY_SENTENCE_SUGGESTIONS_INFOS =
                new SentenceSuggestionsInfo[] {};
        private static final SuggestionsInfo EMPTY_SUGGESTIONS_INFO = new SuggestionsInfo(0, null);
        /**
         * Container for split TextInfo parameters
         */
        public static class SentenceWordItem {
            public final TextInfo mTextInfo;
            public final int mStart;
            public final int mLength;
            public SentenceWordItem(TextInfo ti, int start, int end) {
                mTextInfo = ti;
                mStart = start;
                mLength = end - start;
            }
        }

        /**
         * Container for originally queried TextInfo and parameters
         */
        public static class SentenceTextInfoParams {
            final TextInfo mOriginalTextInfo;
            final ArrayList<SentenceWordItem> mItems;
            final int mSize;
            public SentenceTextInfoParams(TextInfo ti, ArrayList<SentenceWordItem> items) {
                mOriginalTextInfo = ti;
                mItems = items;
                mSize = items.size();
            }
        }

        private final WordIterator mWordIterator;
        public SentenceLevelAdapter(Locale locale) {
            mWordIterator = new WordIterator(locale);
        }

        private SentenceTextInfoParams getSplitWords(TextInfo originalTextInfo) {
            final WordIterator wordIterator = mWordIterator;
            final CharSequence originalText = originalTextInfo.getText();
            final int cookie = originalTextInfo.getCookie();
            final int start = 0;
            final int end = originalText.length();
            final ArrayList<SentenceWordItem> wordItems = new ArrayList<SentenceWordItem>();
            wordIterator.setCharSequence(originalText, 0, originalText.length());
            int wordEnd = wordIterator.following(start);
            int wordStart = wordEnd == BreakIterator.DONE ? BreakIterator.DONE
                    : wordIterator.getBeginning(wordEnd);
            if (DBG) {
                Log.d(TAG, "iterator: break: ---- 1st word start = " + wordStart + ", end = "
                        + wordEnd + "\n" + originalText);
            }
            while (wordStart <= end && wordEnd != BreakIterator.DONE
                    && wordStart != BreakIterator.DONE) {
                if (wordEnd >= start && wordEnd > wordStart) {
                    final CharSequence query = originalText.subSequence(wordStart, wordEnd);
                    final TextInfo ti = new TextInfo(query, 0, query.length(), cookie,
                            query.hashCode());
                    wordItems.add(new SentenceWordItem(ti, wordStart, wordEnd));
                    if (DBG) {
                        Log.d(TAG, "Adapter: word (" + (wordItems.size() - 1) + ") " + query);
                    }
                }
                wordEnd = wordIterator.following(wordEnd);
                if (wordEnd == BreakIterator.DONE) {
                    break;
                }
                wordStart = wordIterator.getBeginning(wordEnd);
            }
            return new SentenceTextInfoParams(originalTextInfo, wordItems);
        }

        public static SentenceSuggestionsInfo reconstructSuggestions(
                SentenceTextInfoParams originalTextInfoParams, SuggestionsInfo[] results) {
            if (results == null || results.length == 0) {
                return null;
            }
            if (DBG) {
                Log.w(TAG, "Adapter: onGetSuggestions: got " + results.length);
            }
            if (originalTextInfoParams == null) {
                if (DBG) {
                    Log.w(TAG, "Adapter: originalTextInfoParams is null.");
                }
                return null;
            }
            final int originalCookie = originalTextInfoParams.mOriginalTextInfo.getCookie();
            final int originalSequence =
                    originalTextInfoParams.mOriginalTextInfo.getSequence();

            final int querySize = originalTextInfoParams.mSize;
            final int[] offsets = new int[querySize];
            final int[] lengths = new int[querySize];
            final SuggestionsInfo[] reconstructedSuggestions = new SuggestionsInfo[querySize];
            for (int i = 0; i < querySize; ++i) {
                final SentenceWordItem item = originalTextInfoParams.mItems.get(i);
                SuggestionsInfo result = null;
                for (int j = 0; j < results.length; ++j) {
                    final SuggestionsInfo cur = results[j];
                    if (cur != null && cur.getSequence() == item.mTextInfo.getSequence()) {
                        result = cur;
                        result.setCookieAndSequence(originalCookie, originalSequence);
                        break;
                    }
                }
                offsets[i] = item.mStart;
                lengths[i] = item.mLength;
                reconstructedSuggestions[i] = result != null ? result : EMPTY_SUGGESTIONS_INFO;
                if (DBG) {
                    final int size = reconstructedSuggestions[i].getSuggestionsCount();
                    Log.w(TAG, "reconstructedSuggestions(" + i + ")" + size + ", first = "
                            + (size > 0 ? reconstructedSuggestions[i].getSuggestionAt(0)
                                    : "<none>") + ", offset = " + offsets[i] + ", length = "
                            + lengths[i]);
                }
            }
            return new SentenceSuggestionsInfo(reconstructedSuggestions, offsets, lengths);
        }
    }
}
