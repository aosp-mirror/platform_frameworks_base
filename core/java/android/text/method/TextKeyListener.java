/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.text.method;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.provider.Settings.System;
import android.text.*;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;

/**
 * This is the key listener for typing normal text.  It delegates to
 * other key listeners appropriate to the current keyboard and language.
 */
public class TextKeyListener extends BaseKeyListener implements SpanWatcher {
    private static TextKeyListener[] sInstance =
        new TextKeyListener[Capitalize.values().length * 2];

    /* package */ static final Object ACTIVE = new Object();
    /* package */ static final Object CAPPED = new Object();
    /* package */ static final Object INHIBIT_REPLACEMENT = new Object();
    /* package */ static final Object LAST_TYPED = new Object();

    private Capitalize mAutoCap;
    private boolean mAutoText;

    private int mPrefs;
    private boolean mPrefsInited;

    /* package */ static final int AUTO_CAP = 1;
    /* package */ static final int AUTO_TEXT = 2;
    /* package */ static final int AUTO_PERIOD = 4;
    /* package */ static final int SHOW_PASSWORD = 8;
    private WeakReference<ContentResolver> mResolver;
    private TextKeyListener.SettingsObserver mObserver;

    /**
     * Creates a new TextKeyListener with the specified capitalization
     * and correction properties.
     *
     * @param cap when, if ever, to automatically capitalize.
     * @param autotext whether to automatically do spelling corrections.
     */
    public TextKeyListener(Capitalize cap, boolean autotext) {
        mAutoCap = cap;
        mAutoText = autotext;
    }

    /**
     * Returns a new or existing instance with the specified capitalization
     * and correction properties.
     *
     * @param cap when, if ever, to automatically capitalize.
     * @param autotext whether to automatically do spelling corrections.
     */
    public static TextKeyListener getInstance(boolean autotext,
                                              Capitalize cap) {
        int off = cap.ordinal() * 2 + (autotext ? 1 : 0);

        if (sInstance[off] == null) {
            sInstance[off] = new TextKeyListener(cap, autotext);
        }

        return sInstance[off];
    }

    /**
     * Returns a new or existing instance with no automatic capitalization
     * or correction.
     */
    public static TextKeyListener getInstance() {
        return getInstance(false, Capitalize.NONE);
    }

    /**
     * Returns whether it makes sense to automatically capitalize at the
     * specified position in the specified text, with the specified rules.
     *
     * @param cap the capitalization rules to consider.
     * @param cs the text in which an insertion is being made.
     * @param off the offset into that text where the insertion is being made.
     *
     * @return whether the character being inserted should be capitalized.
     */
    public static boolean shouldCap(Capitalize cap, CharSequence cs, int off) {
        int i;
        char c;

        if (cap == Capitalize.NONE) {
            return false;
        }
        if (cap == Capitalize.CHARACTERS) {
            return true;
        }

        // Back over allowed opening punctuation.

        for (i = off; i > 0; i--) {
            c = cs.charAt(i - 1);

            if (c != '"' && c != '(' && c != '[' && c != '\'') {
                break;
            }
        }

        // Start of paragraph, with optional whitespace.

        int j = i;
        while (j > 0 && ((c = cs.charAt(j - 1)) == ' ' || c == '\t')) {
            j--;
        }
        if (j == 0 || cs.charAt(j - 1) == '\n') {
            return true;
        }

        // Or start of word if we are that style.

        if (cap == Capitalize.WORDS) {
            return i != j;
        }

        // There must be a space if not the start of paragraph.

        if (i == j) {
            return false;
        }

        // Back over allowed closing punctuation.

        for (; j > 0; j--) {
            c = cs.charAt(j - 1);

            if (c != '"' && c != ')' && c != ']' && c != '\'') {
                break;
            }
        }

        if (j > 0) {
            c = cs.charAt(j - 1);

            if (c == '.' || c == '?' || c == '!') {
                // Do not capitalize if the word ends with a period but
                // also contains a period, in which case it is an abbreviation.

                if (c == '.') {
                    for (int k = j - 2; k >= 0; k--) {
                        c = cs.charAt(k);

                        if (c == '.') {
                            return false;
                        }

                        if (!Character.isLetter(c)) {
                            break;
                        }
                    }
                }

                return true;
            }
        }

        return false;
    }

    @Override
    public boolean onKeyDown(View view, Editable content,
                             int keyCode, KeyEvent event) {
        KeyListener im = getKeyListener(event);

        return im.onKeyDown(view, content, keyCode, event);
    }

    @Override
    public boolean onKeyUp(View view, Editable content,
                           int keyCode, KeyEvent event) {
        KeyListener im = getKeyListener(event);

        return im.onKeyUp(view, content, keyCode, event);
    }

    /**
     * Clear all the input state (autotext, autocap, multitap, undo)
     * from the specified Editable, going beyond Editable.clear(), which
     * just clears the text but not the input state.
     *
     * @param e the buffer whose text and state are to be cleared.
     */
    public static void clear(Editable e) {
        e.clear();
        e.removeSpan(ACTIVE);
        e.removeSpan(CAPPED);
        e.removeSpan(INHIBIT_REPLACEMENT);
        e.removeSpan(LAST_TYPED);

        QwertyKeyListener.Replaced[] repl = e.getSpans(0, e.length(),
                                   QwertyKeyListener.Replaced.class);
        final int count = repl.length;
        for (int i = 0; i < count; i++) {
            e.removeSpan(repl[i]);
        }
    }

    public void onSpanAdded(Spannable s, Object what, int start, int end) { }
    public void onSpanRemoved(Spannable s, Object what, int start, int end) { }

    public void onSpanChanged(Spannable s, Object what, int start, int end,
                              int st, int en) {
        if (what == Selection.SELECTION_END) {
            s.removeSpan(ACTIVE);
        }
    }

    private KeyListener getKeyListener(KeyEvent event) {
        KeyCharacterMap kmap = KeyCharacterMap.load(event.getKeyboardDevice());
        int kind = kmap.getKeyboardType();

        if (kind == KeyCharacterMap.ALPHA) {
            return QwertyKeyListener.getInstance(mAutoText, mAutoCap);
        } else if (kind == KeyCharacterMap.NUMERIC) {
            return MultiTapKeyListener.getInstance(mAutoText, mAutoCap);
        }

        return NullKeyListener.getInstance();
    }

    public enum Capitalize {
        NONE, SENTENCES, WORDS, CHARACTERS,
    }

    private static class NullKeyListener implements KeyListener
    {
        public boolean onKeyDown(View view, Editable content,
                                 int keyCode, KeyEvent event) {
            return false;
        }

        public boolean onKeyUp(View view, Editable content, int keyCode,
                                        KeyEvent event) {
            return false;
        }

        public static NullKeyListener getInstance() {
            if (sInstance != null)
                return sInstance;

            sInstance = new NullKeyListener();
            return sInstance;
        }

        private static NullKeyListener sInstance;
    }

    public void release() {
        if (mResolver != null) {
            final ContentResolver contentResolver = mResolver.get();
            if (contentResolver != null) {
                contentResolver.unregisterContentObserver(mObserver);
                mResolver.clear();
            }
            mObserver = null;
            mResolver = null;
            mPrefsInited = false;
        }
    }

    private void initPrefs(Context context) {
        final ContentResolver contentResolver = context.getContentResolver();
        mResolver = new WeakReference<ContentResolver>(contentResolver);
        mObserver = new SettingsObserver();
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, mObserver);

        updatePrefs(contentResolver);
        mPrefsInited = true;
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            if (mResolver != null) {
                final ContentResolver contentResolver = mResolver.get();
                if (contentResolver == null) {
                    mPrefsInited = false;
                } else {
                    updatePrefs(contentResolver);
                }
            } else {
                mPrefsInited = false;
            }
        }
    }

    private void updatePrefs(ContentResolver resolver) {
        boolean cap = System.getInt(resolver, System.TEXT_AUTO_CAPS, 1) > 0;
        boolean text = System.getInt(resolver, System.TEXT_AUTO_REPLACE, 1) > 0;
        boolean period = System.getInt(resolver, System.TEXT_AUTO_PUNCTUATE, 1) > 0;
        boolean pw = System.getInt(resolver, System.TEXT_SHOW_PASSWORD, 1) > 0;

        mPrefs = (cap ? AUTO_CAP : 0) |
                 (text ? AUTO_TEXT : 0) |
                 (period ? AUTO_PERIOD : 0) |
                 (pw ? SHOW_PASSWORD : 0);
    }

    /* package */ int getPrefs(Context context) {
        synchronized (this) {
            if (!mPrefsInited || mResolver.get() == null) {
                initPrefs(context);
            }
        }

        return mPrefs;
    }
}
