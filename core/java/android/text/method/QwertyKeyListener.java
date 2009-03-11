/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.text.*;
import android.text.method.TextKeyListener.Capitalize;
import android.util.SparseArray;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;

/**
 * This is the standard key listener for alphabetic input on qwerty
 * keyboards.  You should generally not need to instantiate this yourself;
 * TextKeyListener will do it for you.
 */
public class QwertyKeyListener extends BaseKeyListener {
    private static QwertyKeyListener[] sInstance =
        new QwertyKeyListener[Capitalize.values().length * 2];

    public QwertyKeyListener(Capitalize cap, boolean autotext) {
        mAutoCap = cap;
        mAutoText = autotext;
    }

    /**
     * Returns a new or existing instance with the specified capitalization
     * and correction properties.
     */
    public static QwertyKeyListener getInstance(boolean autotext,
                                              Capitalize cap) {
        int off = cap.ordinal() * 2 + (autotext ? 1 : 0);

        if (sInstance[off] == null) {
            sInstance[off] = new QwertyKeyListener(cap, autotext);
        }

        return sInstance[off];
    }

    public int getInputType() {
        return makeTextContentType(mAutoCap, mAutoText);
    }
    
    public boolean onKeyDown(View view, Editable content,
                             int keyCode, KeyEvent event) {
        int selStart, selEnd;
        int pref = 0;

        if (view != null) {
            pref = TextKeyListener.getInstance().getPrefs(view.getContext());
        }

        {
            int a = Selection.getSelectionStart(content);
            int b = Selection.getSelectionEnd(content);

            selStart = Math.min(a, b);
            selEnd = Math.max(a, b);

            if (selStart < 0 || selEnd < 0) {
                selStart = selEnd = 0;
                Selection.setSelection(content, 0, 0);
            }
        }

        int activeStart = content.getSpanStart(TextKeyListener.ACTIVE);
        int activeEnd = content.getSpanEnd(TextKeyListener.ACTIVE);

        // QWERTY keyboard normal case

        int i = event.getUnicodeChar(getMetaState(content));

        int count = event.getRepeatCount();
        if (count > 0 && selStart == selEnd && selStart > 0) {
            char c = content.charAt(selStart - 1);

            if (c == i || c == Character.toUpperCase(i) && view != null) {
                if (showCharacterPicker(view, content, c, false, count)) {
                    resetMetaState(content);
                    return true;
                }
            }
        }

        if (i == KeyCharacterMap.PICKER_DIALOG_INPUT) {
            if (view != null) {
                showCharacterPicker(view, content,
                                    KeyCharacterMap.PICKER_DIALOG_INPUT, true, 1);
            }
            resetMetaState(content);
            return true;
        }

        if (i == KeyCharacterMap.HEX_INPUT) {
            int start;

            if (selStart == selEnd) {
                start = selEnd;

                while (start > 0 && selEnd - start < 4 &&
                       Character.digit(content.charAt(start - 1), 16) >= 0) {
                    start--;
                }
            } else {
                start = selStart;
            }

            int ch = -1;
            try {
                String hex = TextUtils.substring(content, start, selEnd);
                ch = Integer.parseInt(hex, 16);
            } catch (NumberFormatException nfe) { }

            if (ch >= 0) {
                selStart = start;
                Selection.setSelection(content, selStart, selEnd);
                i = ch;
            } else {
                i = 0;
            }
        }

        if (i != 0) {
            boolean dead = false;

            if ((i & KeyCharacterMap.COMBINING_ACCENT) != 0) {
                dead = true;
                i = i & KeyCharacterMap.COMBINING_ACCENT_MASK;
            }

            if (activeStart == selStart && activeEnd == selEnd) {
                boolean replace = false;

                if (selEnd - selStart - 1 == 0) {
                    char accent = content.charAt(selStart);
                    int composed = event.getDeadChar(accent, i);

                    if (composed != 0) {
                        i = composed;
                        replace = true;
                    }
                }

                if (!replace) {
                    Selection.setSelection(content, selEnd);
                    content.removeSpan(TextKeyListener.ACTIVE);
                    selStart = selEnd;
                }
            }

            if ((pref & TextKeyListener.AUTO_CAP) != 0 &&
                Character.isLowerCase(i) && 
                TextKeyListener.shouldCap(mAutoCap, content, selStart)) {
                int where = content.getSpanEnd(TextKeyListener.CAPPED);
                int flags = content.getSpanFlags(TextKeyListener.CAPPED);

                if (where == selStart && (((flags >> 16) & 0xFFFF) == i)) {
                    content.removeSpan(TextKeyListener.CAPPED);
                } else {
                    flags = i << 16;
                    i = Character.toUpperCase(i);

                    if (selStart == 0)
                        content.setSpan(TextKeyListener.CAPPED, 0, 0,
                                        Spannable.SPAN_MARK_MARK | flags);
                    else
                        content.setSpan(TextKeyListener.CAPPED,
                                        selStart - 1, selStart,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE |
                                        flags);
                }
            }

            if (selStart != selEnd) {
                Selection.setSelection(content, selEnd);
            }
            content.setSpan(OLD_SEL_START, selStart, selStart,
                            Spannable.SPAN_MARK_MARK);

            content.replace(selStart, selEnd, String.valueOf((char) i));

            int oldStart = content.getSpanStart(OLD_SEL_START);
            selEnd = Selection.getSelectionEnd(content);

            if (oldStart < selEnd) {
                content.setSpan(TextKeyListener.LAST_TYPED,
                                oldStart, selEnd,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                if (dead) {
                    Selection.setSelection(content, oldStart, selEnd);
                    content.setSpan(TextKeyListener.ACTIVE, oldStart, selEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            adjustMetaAfterKeypress(content);

            // potentially do autotext replacement if the character
            // that was typed was an autotext terminator

            if ((pref & TextKeyListener.AUTO_TEXT) != 0 && mAutoText &&
                (i == ' ' || i == '\t' || i == '\n' ||
                 i == ',' || i == '.' || i == '!' || i == '?' ||
                 i == '"' || Character.getType(i) == Character.END_PUNCTUATION) &&
                 content.getSpanEnd(TextKeyListener.INHIBIT_REPLACEMENT)
                     != oldStart) {
                int x;

                for (x = oldStart; x > 0; x--) {
                    char c = content.charAt(x - 1);
                    if (c != '\'' && !Character.isLetter(c)) {
                        break;
                    }
                }

                String rep = getReplacement(content, x, oldStart, view);

                if (rep != null) {
                    Replaced[] repl = content.getSpans(0, content.length(),
                                                     Replaced.class);
                    for (int a = 0; a < repl.length; a++)
                        content.removeSpan(repl[a]);

                    char[] orig = new char[oldStart - x];
                    TextUtils.getChars(content, x, oldStart, orig, 0);

                    content.setSpan(new Replaced(orig), x, oldStart,
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    content.replace(x, oldStart, rep);
                }
            }

            // Replace two spaces by a period and a space.

            if ((pref & TextKeyListener.AUTO_PERIOD) != 0 && mAutoText) {
                selEnd = Selection.getSelectionEnd(content);
                if (selEnd - 3 >= 0) {
                    if (content.charAt(selEnd - 1) == ' ' &&
                        content.charAt(selEnd - 2) == ' ') {
                        char c = content.charAt(selEnd - 3);

                        for (int j = selEnd - 3; j > 0; j--) {
                            if (c == '"' ||
                                Character.getType(c) == Character.END_PUNCTUATION) {
                                c = content.charAt(j - 1);
                            } else {
                                break;
                            }
                        }

                        if (Character.isLetter(c) || Character.isDigit(c)) {
                            content.replace(selEnd - 2, selEnd - 1, ".");
                        }
                    }
                }
            }

            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DEL && selStart == selEnd) {
            // special backspace case for undoing autotext

            int consider = 1;

            // if backspacing over the last typed character,
            // it undoes the autotext prior to that character
            // (unless the character typed was newline, in which
            // case this behavior would be confusing)

            if (content.getSpanEnd(TextKeyListener.LAST_TYPED) == selStart) {
                if (content.charAt(selStart - 1) != '\n')
                    consider = 2;
            }

            Replaced[] repl = content.getSpans(selStart - consider, selStart,
                                             Replaced.class);

            if (repl.length > 0) {
                int st = content.getSpanStart(repl[0]);
                int en = content.getSpanEnd(repl[0]);
                String old = new String(repl[0].mText);

                content.removeSpan(repl[0]);

                // only cancel the autocomplete if the cursor is at the end of
                // the replaced span (or after it, because the user is
                // backspacing over the space after the word, not the word
                // itself).
                if (selStart >= en) {
                    content.setSpan(TextKeyListener.INHIBIT_REPLACEMENT,
                                    en, en, Spannable.SPAN_POINT_POINT);
                    content.replace(st, en, old);

                    en = content.getSpanStart(TextKeyListener.INHIBIT_REPLACEMENT);
                    if (en - 1 >= 0) {
                        content.setSpan(TextKeyListener.INHIBIT_REPLACEMENT,
                                        en - 1, en,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        content.removeSpan(TextKeyListener.INHIBIT_REPLACEMENT);
                    }
                    adjustMetaAfterKeypress(content);
                } else {
                    adjustMetaAfterKeypress(content);
                    return super.onKeyDown(view, content, keyCode, event);
                }

                return true;
            }
        }

        return super.onKeyDown(view, content, keyCode, event);
    }

    private String getReplacement(CharSequence src, int start, int end,
                                  View view) {
        int len = end - start;
        boolean changecase = false;
        
        String replacement = AutoText.get(src, start, end, view);
        
        if (replacement == null) {
            String key = TextUtils.substring(src, start, end).toLowerCase();
            replacement = AutoText.get(key, 0, end - start, view);
            changecase = true;

            if (replacement == null)
                return null;
        }
        
        int caps = 0;

        if (changecase) {
            for (int j = start; j < end; j++) {
                if (Character.isUpperCase(src.charAt(j)))
                    caps++;
            }
        }

        String out;

        if (caps == 0)
            out = replacement;
        else if (caps == 1)
            out = toTitleCase(replacement);
        else if (caps == len)
            out = replacement.toUpperCase();
        else
            out = toTitleCase(replacement);

        if (out.length() == len &&
            TextUtils.regionMatches(src, start, out, 0, len))
            return null;

        return out;
    }

    /**
     * Marks the specified region of <code>content</code> as having
     * contained <code>original</code> prior to AutoText replacement.
     * Call this method when you have done or are about to do an
     * AutoText-style replacement on a region of text and want to let
     * the same mechanism (the user pressing DEL immediately after the
     * change) undo the replacement.
     *
     * @param content the Editable text where the replacement was made
     * @param start the start of the replaced region
     * @param end the end of the replaced region; the location of the cursor
     * @param original the text to be restored if the user presses DEL
     */
    public static void markAsReplaced(Spannable content, int start, int end,
                                      String original) {
        Replaced[] repl = content.getSpans(0, content.length(), Replaced.class);
        for (int a = 0; a < repl.length; a++) {
            content.removeSpan(repl[a]);
        }

        int len = original.length();
        char[] orig = new char[len];
        original.getChars(0, len, orig, 0);

        content.setSpan(new Replaced(orig), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static SparseArray<String> PICKER_SETS =
                        new SparseArray<String>();
    static {
        PICKER_SETS.put('!', "\u00A1");
        PICKER_SETS.put('<', "\u00AB");
        PICKER_SETS.put('>', "\u00BB");
        PICKER_SETS.put('?', "\u00BF");
        PICKER_SETS.put('A', "\u00C0\u00C1\u00C2\u00C4\u00C6\u00C3\u00C5");
        PICKER_SETS.put('C', "\u00C7");
        PICKER_SETS.put('E', "\u00C8\u00C9\u00CA\u00CB");
        PICKER_SETS.put('I', "\u00CC\u00CD\u00CE\u00CF");
        PICKER_SETS.put('N', "\u00D1");
        PICKER_SETS.put('O', "\u00D8\u0152\u00D5\u00D2\u00D3\u00D4\u00D6");
        PICKER_SETS.put('U', "\u00D9\u00DA\u00DB\u00DC");
        PICKER_SETS.put('Y', "\u00DD\u0178");
        PICKER_SETS.put('a', "\u00E0\u00E1\u00E2\u00E4\u00E6\u00E3\u00E5");
        PICKER_SETS.put('c', "\u00E7");
        PICKER_SETS.put('e', "\u00E8\u00E9\u00EA\u00EB");
        PICKER_SETS.put('i', "\u00EC\u00ED\u00EE\u00EF");
        PICKER_SETS.put('n', "\u00F1");
        PICKER_SETS.put('o', "\u00F8\u0153\u00F5\u00F2\u00F3\u00F4\u00F6");
        PICKER_SETS.put('s', "\u00A7\u00DF");
        PICKER_SETS.put('u', "\u00F9\u00FA\u00FB\u00FC");
        PICKER_SETS.put('y', "\u00FD\u00FF");
        PICKER_SETS.put(KeyCharacterMap.PICKER_DIALOG_INPUT,
                             "\u2026\u00A5\u2022\u00AE\u00A9\u00B1");
    };

    private boolean showCharacterPicker(View view, Editable content, char c,
                                        boolean insert, int count) {
        String set = PICKER_SETS.get(c);
        if (set == null) {
            return false;
        }

        if (count == 1) {
            new CharacterPickerDialog(view.getContext(),
                                      view, content, set, insert).show();
        }

        return true;
    }

    private static String toTitleCase(String src) {
        return Character.toUpperCase(src.charAt(0)) + src.substring(1);
    }

    /* package */ static class Replaced implements NoCopySpan
    {
        public Replaced(char[] text) {
            mText = text;
        }

        private char[] mText;
    }

    private Capitalize mAutoCap;
    private boolean mAutoText;
}

