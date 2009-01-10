/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.text.TextUtils;

/**
 * Bit definitions for an integer defining the basic content type of text
 * held in an {@link Editable} object.
 */
public interface InputType {
    /**
     * Mask of bits that determine the overall class
     * of text being given.  Currently supported classes are:
     * {@link #TYPE_CLASS_TEXT}, {@link #TYPE_CLASS_NUMBER},
     * {@link #TYPE_CLASS_PHONE}, {@link #TYPE_CLASS_DATETIME}.
     * If the class is not one you
     * understand, assume {@link #TYPE_CLASS_TEXT} with NO variation
     * or flags.
     */
    public static final int TYPE_MASK_CLASS = 0x0000000f;
    
    /**
     * Mask of bits that determine the variation of
     * the base content class.
     */
    public static final int TYPE_MASK_VARIATION = 0x00000ff0;
    
    /**
     * Mask of bits that provide addition bit flags
     * of options.
     */
    public static final int TYPE_MASK_FLAGS = 0x00fff000;
    
    /**
     * Special content type for when no explicit type has been specified.
     * This should be interpreted to mean that the target input connection
     * is not rich, it can not process and show things like candidate text nor
     * retrieve the current text, so the input method will need to run in a
     * limited "generate key events" mode.
     */
    public static final int TYPE_NULL = 0x00000000;
    
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    
    /**
     * Class for normal text.  This class supports the following flags (only
     * one of which should be set):
     * {@link #TYPE_TEXT_FLAG_CAP_CHARACTERS},
     * {@link #TYPE_TEXT_FLAG_CAP_WORDS}, and.
     * {@link #TYPE_TEXT_FLAG_CAP_SENTENCES}.  It also supports the
     * following variations:
     * {@link #TYPE_TEXT_VARIATION_NORMAL}, and
     * {@link #TYPE_TEXT_VARIATION_URI}.  If you do not recognize the
     * variation, normal should be assumed.
     */
    public static final int TYPE_CLASS_TEXT = 0x00000001;
    
    /**
     * Flag for {@link #TYPE_CLASS_TEXT}: capitalize all characters.  Overrides
     * {@link #TYPE_TEXT_FLAG_CAP_WORDS} and
     * {@link #TYPE_TEXT_FLAG_CAP_SENTENCES}.  This value is explicitly defined
     * to be the same as {@link TextUtils#CAP_MODE_CHARACTERS}.
     */
    public static final int TYPE_TEXT_FLAG_CAP_CHARACTERS = 0x00001000;
    
    /**
     * Flag for {@link #TYPE_CLASS_TEXT}: capitalize first character of
     * all words.  Overrides {@link #TYPE_TEXT_FLAG_CAP_SENTENCES}.  This
     * value is explicitly defined
     * to be the same as {@link TextUtils#CAP_MODE_WORDS}.
     */
    public static final int TYPE_TEXT_FLAG_CAP_WORDS = 0x00002000;
    
    /**
     * Flag for {@link #TYPE_CLASS_TEXT}: capitalize first character of
     * each sentence.  This value is explicitly defined
     * to be the same as {@link TextUtils#CAP_MODE_SENTENCES}.
     */
    public static final int TYPE_TEXT_FLAG_CAP_SENTENCES = 0x00004000;
    
    /**
     * Flag for {@link #TYPE_CLASS_TEXT}: the user is entering free-form
     * text that should have auto-correction applied to it.
     */
    public static final int TYPE_TEXT_FLAG_AUTO_CORRECT = 0x00008000;
    
    /**
     * Flag for {@link #TYPE_CLASS_TEXT}: the text editor is performing
     * auto-completion of the text being entered based on its own semantics,
     * which it will present to the user as they type.  This generally means
     * that the input method should not be showing candidates itself, but can
     * expect for the editor to supply its own completions/candidates from
     * {@link android.view.inputmethod.InputMethodSession#displayCompletions
     * InputMethodSession.displayCompletions()} as a result of the editor calling
     * {@link android.view.inputmethod.InputMethodManager#displayCompletions
     * InputMethodManager.displayCompletions()}.
     */
    public static final int TYPE_TEXT_FLAG_AUTO_COMPLETE = 0x00010000;
    
    /**
     * Flag for {@link #TYPE_CLASS_TEXT}: multiple lines of text can be
     * entered into the field.
     */
    public static final int TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000;
    
    /**
     * Flag for {@link #TYPE_CLASS_TEXT}: flags any text being used as a search string
     */
    public static final int TYPE_TEXT_FLAG_SEARCH = 0x00040000;
    
    // ----------------------------------------------------------------------
    
    /**
     * Default variation of {@link #TYPE_CLASS_TEXT}: plain old normal text.
     */
    public static final int TYPE_TEXT_VARIATION_NORMAL = 0x00000000;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering a URI.
     */
    public static final int TYPE_TEXT_VARIATION_URI = 0x00000010;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering an e-mail address.
     */
    public static final int TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering the subject line of
     * an e-mail.
     */
    public static final int TYPE_TEXT_VARIATION_EMAIL_SUBJECT = 0x00000030;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering the content of
     * an e-mail.
     */
    public static final int TYPE_TEXT_VARIATION_EMAIL_CONTENT = 0x00000040;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering the name of a person.
     */
    public static final int TYPE_TEXT_VARIATION_PERSON_NAME = 0x00000050;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering a postal mailing address.
     */
    public static final int TYPE_TEXT_VARIATION_POSTAL_ADDRESS = 0x00000060;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering a password.
     */
    public static final int TYPE_TEXT_VARIATION_PASSWORD = 0x00000070;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering a simple text search (e.g. web search)
     */
    public static final int TYPE_TEXT_VARIATION_SEARCH_STRING = 0x00000080;
    
    /**
     * Variation of {@link #TYPE_CLASS_TEXT}: entering text inside of a web form.
     */
    public static final int TYPE_TEXT_VARIATION_WEB_EDIT_TEXT = 0x00000090;
    
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    
    /**
     * Class for numeric text.  This class supports the following flag:
     * {@link #TYPE_NUMBER_FLAG_SIGNED} and
     * {@link #TYPE_NUMBER_FLAG_DECIMAL}.
     */
    public static final int TYPE_CLASS_NUMBER = 0x00000002;
    
    /**
     * Flag of {@link #TYPE_CLASS_NUMBER}: the number is signed, allowing
     * a positive or negative sign at the start.
     */
    public static final int TYPE_NUMBER_FLAG_SIGNED = 0x00001000;
    
    /**
     * Flag of {@link #TYPE_CLASS_NUMBER}: the number is decimal, allowing
     * a decimal point to provide fractional values.
     */
    public static final int TYPE_NUMBER_FLAG_DECIMAL = 0x00002000;
    
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    
    /**
     * Class for a phone number.  This class currently supports no variations
     * or flags.
     */
    public static final int TYPE_CLASS_PHONE = 0x00000003;
    
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    // ----------------------------------------------------------------------
    
    /**
     * Class for dates and times.  It supports the
     * following variations:
     * {@link #TYPE_DATETIME_VARIATION_NORMAL}
     * {@link #TYPE_DATETIME_VARIATION_DATE}, and
     * {@link #TYPE_DATETIME_VARIATION_TIME},.
     */
    public static final int TYPE_CLASS_DATETIME = 0x00000004;
    
    /**
     * Default variation of {@link #TYPE_CLASS_DATETIME}: allows entering
     * both a date and time.
     */
    public static final int TYPE_DATETIME_VARIATION_NORMAL = 0x00000000;
    
    /**
     * Default variation of {@link #TYPE_CLASS_DATETIME}: allows entering
     * only a date.
     */
    public static final int TYPE_DATETIME_VARIATION_DATE = 0x00000010;
    
    /**
     * Default variation of {@link #TYPE_CLASS_DATETIME}: allows entering
     * only a time.
     */
    public static final int TYPE_DATETIME_VARIATION_TIME = 0x00000020;
}
