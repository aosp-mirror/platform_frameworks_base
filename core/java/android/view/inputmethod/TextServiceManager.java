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

package android.view.inputmethod;

import com.android.internal.view.ITextServiceManager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.ISpellCheckerService;
import android.view.inputmethod.SpellCheckerService;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

public final class TextServiceManager {
    private static final String TAG = TextServiceManager.class.getSimpleName();
    private static final boolean DBG = false;
    private static final int MSG_CANCEL = 1;
    private static final int MSG_IS_CORRECT = 2;
    private static final int MSG_GET_SUGGESTION = 3;

    private static TextServiceManager sInstance;
    private static ITextServiceManager sService;

    private final WeakReference<Context> mContextRef;
    private static final HashMap<String, SpellCheckerConnection> sComponentMap =
            new HashMap<String, SpellCheckerConnection>();

    private TextServiceManager(Context context) {
        mContextRef = new WeakReference<Context>(context);
        synchronized (sComponentMap) {
            if (sService == null) {
                IBinder b = ServiceManager.getService(Context.TEXT_SERVICE_MANAGER_SERVICE);
                sService = ITextServiceManager.Stub.asInterface(b);
            }
        }
    }

    /**
     * Retrieve the global TextServiceManager instance, creating it if it doesn't already exist.
     * @hide
     */
    public static TextServiceManager getInstance(Context context) {
        synchronized (sComponentMap) {
            if (sInstance != null) {
                return sInstance;
            }
            sInstance = new TextServiceManager(context);
        }
        return sInstance;
    }

    private static class SpellCheckerConnection implements ServiceConnection {
        private final String mLocale;
        private ISpellCheckerService mSpellCheckerService;
        private final Queue<Message> mPendingTasks = new LinkedList<Message>();
        private final SpellCheckerInfo mSpellCheckerInfo;

        private static class SpellCheckerParams {
            public final CharSequence mText;
            public final int mStart;
            public final int mEnd;
            public final Locale mLocale;
            public final Callback mCallback;
            public SpellCheckerParams(
                    CharSequence text, int start, int end, Locale locale, Callback callback) {
                mText = text;
                mStart = start;
                mEnd = end;
                mLocale = locale;
                mCallback = callback;
            }
        }

        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_CANCEL:
                        handleCancelMessage();
                        break;
                    case MSG_IS_CORRECT:
                        handleIsCorrectMessage((SpellCheckerParams) msg.obj);
                        break;
                    case MSG_GET_SUGGESTION:
                        handleGetSuggestionMessage((SpellCheckerParams) msg.obj);
                        break;
                }
            }
        };

        public SpellCheckerConnection(String locale, SpellCheckerInfo sci) {
            mLocale = locale;
            mSpellCheckerInfo = sci;
        }

        @Override
        public synchronized void onServiceConnected(
                final ComponentName name, final IBinder service) {
            mSpellCheckerService = ISpellCheckerService.Stub.asInterface(service);
            if (DBG)
                Log.d(TAG, "onServiceConnected - Success");
            while (!mPendingTasks.isEmpty()) {
                mHandler.sendMessage(mPendingTasks.poll());
            }
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            mSpellCheckerService = null;
            mPendingTasks.clear();
            synchronized(sComponentMap) {
                sComponentMap.remove(mLocale);
            }
            if (DBG)
                Log.d(TAG, "onServiceDisconnected - Success");
        }

        public void isCorrect(
                CharSequence text, int start, int end, Locale locale, Callback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("isCorrect: Callback is null.");
            }
            putMessage(Message.obtain(mHandler, MSG_IS_CORRECT,
                    new SpellCheckerParams(text, start, end, locale, callback)));
        }

        public void getSuggestions(
                CharSequence text, int start, int end, Locale locale, Callback callback) {
            if (callback == null) {
                throw new IllegalArgumentException("getSuggestions: Callback is null.");
            }
            putMessage(Message.obtain(mHandler, MSG_GET_SUGGESTION,
                    new SpellCheckerParams(text, start, end, locale, callback)));
        }

        public SpellCheckerInfo getSpellCheckerInfo() {
            return mSpellCheckerInfo;
        }

        private boolean checkOpenConnection() {
            if (mSpellCheckerService != null) {
                return true;
            }
            Log.e(TAG, "not connected to the spellchecker service.");
            return false;
        }

        private void putMessage(Message msg) {
            if (mSpellCheckerService == null) {
                mPendingTasks.offer(msg);
            } else {
                mHandler.sendMessage(msg);
            }
        }

        private void handleCancelMessage() {
            if (!checkOpenConnection()) {
                return;
            }
            try {
                mSpellCheckerService.cancel();
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception in cancel.");
            }
        }

        private void handleIsCorrectMessage(SpellCheckerParams scp) {
            if (!checkOpenConnection()) {
                return;
            }
            try {
                scp.mCallback.isCorrectResult(scp.mText, scp.mStart, scp.mEnd, scp.mLocale,
                        mSpellCheckerService.isCorrect(
                                scp.mText, scp.mStart, scp.mEnd, scp.mLocale.toString()));
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception in isCorrect.");
            }
        }

        private void handleGetSuggestionMessage(SpellCheckerParams scp) {
            if (!checkOpenConnection()) {
                return;
            }
            try {
                scp.mCallback.getSuggestionsResult(scp.mText, scp.mStart, scp.mEnd, scp.mLocale,
                        mSpellCheckerService.getSuggestions(
                                scp.mText, scp.mStart, scp.mEnd, scp.mLocale.toString()));
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception in getSuggestion.");
            }
        }
    }

    /**
     * Check if the substring of text from start to end is a correct word or not in the
     * default locale for the context.
     * @param text text
     * @param callback callback for getting the result from SpellChecker
     * @return true if the substring of text from start to end is a correct word
     */
    public void isCorrect(CharSequence text, Callback callback) {
        final Context context = mContextRef.get();
        if (context == null) {
            return;
        }
        isCorrect(text, mContextRef.get().getResources().getConfiguration().locale, callback);
    }

    /**
     * Check if the substring of text from start to end is a correct word or not in the
     * specified locale.
     * @param text text
     * @param locale the locale for checking the text
     * @param callback callback for getting the result from SpellChecker
     * @return true if the substring of text from start to end is a correct word
     */
    public void isCorrect(CharSequence text, Locale locale, Callback callback) {
        isCorrect(text, 0, text.length(), locale, callback);
    }

    /**
     * Check if the substring of text from start to end is a correct word or not in the
     * specified locale.
     * @param text text
     * @param start the start position of the text to be checked
     * @param end the end position of the text to be checked
     * @param locale the locale for checking the text
     * @param callback callback for getting the result from SpellChecker
     * @return true if the substring of text from start to end is a correct word
     */
    public void isCorrect(CharSequence text, int start, int end, Locale locale, Callback callback) {
        if (TextUtils.isEmpty(text) || locale == null || callback == null) {
            throw new IllegalArgumentException(
                    "text = " + text + ", locale = " + locale + ", callback = " + callback);
        }
        final int textSize = text.length();
        if (start < 0 || textSize <= start || end < 0 || textSize <= end || start >= end) {
            throw new IndexOutOfBoundsException(
                    "text = " + text + ", start = " + start + ", end = " + end);
        }
        final SpellCheckerConnection spellCheckerConnection =
            getCurrentSpellCheckerConnection(locale, false);
        if (spellCheckerConnection == null) {
            Log.e(TAG, "Could not find spellchecker for " + locale);
            return;
        }
        spellCheckerConnection.isCorrect(text, start, end, locale, callback);
    }

    /**
     * Get candidate strings for a substring of the specified text.
     * @param text the substring of text from start to end for getting suggestions
     * @param start the start position of the text
     * @param end the end position of the text
     * @param locale the locale for getting suggestions
     * @param callback callback for getting the result from SpellChecker
     * @return text with SuggestionSpan containing suggestions
     */
    public void getSuggestions(CharSequence text, int start, int end, Locale locale,
            boolean allowMultipleWords, Callback callback) {
        if (TextUtils.isEmpty(text) || locale == null || callback == null) {
            throw new IllegalArgumentException(
                    "text = " + text + ", locale = " + locale + ", callback = " + callback);
        }
        final int textService = text.length();
        if (start < 0 || textService <= start || end < 0 || textService <= end || start >= end) {
            throw new IndexOutOfBoundsException(
                    "text = " + text + ", start = " + start + ", end = " + end);
        }
        final SpellCheckerConnection spellCheckerConnection = getCurrentSpellCheckerConnection(
                locale, false);
        if (spellCheckerConnection == null) {
            Log.e(TAG, "Could not find spellchecker for " + locale);
            return;
        }
        // TODO: Handle multiple words suggestions by using WordBreakIterator
        spellCheckerConnection.getSuggestions(text, start, end, locale, callback);
    }

    /**
     * Get the current spell checker service for the specified locale. It's recommended
     * to call this method before calling other APIs in TextServiceManager.
     * This method may update the current spell checker in use for the specified locale if the user
     * has selected a different spell checker for the locale.
     * @param locale locale of a spell checker
     * @return SpellCheckerInfo for the specified locale.
     */
    // TODO: Add a method to get enabled spell checkers.
    // TODO: Add a method to set a spell checker
    public SpellCheckerInfo requestSpellCheckerConnection(Locale locale) {
        if (locale == null) return null;
        final SpellCheckerConnection scc = getCurrentSpellCheckerConnection(locale, true);
        if (scc == null) return null;
        return scc.getSpellCheckerInfo();
    }

    private SpellCheckerConnection getCurrentSpellCheckerConnection(
            Locale locale, boolean resetIfChanged) {
        final Context context = mContextRef.get();
        if (locale == null) {
            return null;
        }
        if (context == null) {
            throw new RuntimeException("Context was GCed.");
        }
        final String localeStr = locale.toString();
        SpellCheckerConnection connection = null;
        synchronized (sComponentMap) {
            if (sComponentMap.containsKey(localeStr)) {
                connection = sComponentMap.get(localeStr);
            }
            if (connection != null && !resetIfChanged) {
                return connection;
            }
            try {
                final SpellCheckerInfo sci = sService.getCurrentSpellChecker(localeStr);
                if (sci == null) {
                    return null;
                }
                if (connection != null
                        && connection.getSpellCheckerInfo().getId().equals(sci.getId())) {
                    return connection;
                }
                connection = new SpellCheckerConnection(localeStr, sci);
                final Intent serviceIntent = new Intent(SpellCheckerService.SERVICE_INTERFACE);

                serviceIntent.setComponent(sci.getComponent());
                if (!context.bindService(serviceIntent, connection,
                        Context.BIND_AUTO_CREATE)) {
                    Log.e(TAG, "Bind to spell checker service failed.");
                    return null;
                }
                sComponentMap.put(localeStr, connection);
                return connection;
            } catch (RemoteException e) {
                return null;
            }
        }
    }

    /**
     * Callback for getting results from TextService
     */
    public interface Callback {
        /**
         * Callback for "isCorrect"
         * @param text the input for isCorrect
         * @param start the input for isCorrect
         * @param end the input for isCorrect
         * @param locale the input for isCorrect
         * @param result true if the specified text is a correct word.
         */
        public void isCorrectResult(
                CharSequence text, int start, int end, Locale locale, boolean result);
        /**
         * Callback for "getSuggestions"
         * @param text the input for getSuggestions
         * @param start the input for getSuggestions
         * @param end the input for getSuggestions
         * @param locale the input for getSuggestions
         * @param result text with "SuggestionSpan"s attached over CharSequence
         */
        public void getSuggestionsResult(
                CharSequence text, int start, int end, Locale locale, CharSequence result);
    }
}
