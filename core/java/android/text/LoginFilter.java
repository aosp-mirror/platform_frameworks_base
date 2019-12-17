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

package android.text;

/**
 * Abstract class for filtering login-related text (user names and passwords)
 * 
 * @deprecated Password requirements should not be hardcoded in clients. This class also does not
 * handle non-BMP characters.
 */
@Deprecated
public abstract class LoginFilter implements InputFilter {
    private boolean mAppendInvalid;  // whether to append or ignore invalid characters
    /**
     * Base constructor for LoginFilter
     * @param appendInvalid whether or not to append invalid characters.
     */
    LoginFilter(boolean appendInvalid) {
        mAppendInvalid = appendInvalid;
    }
    
    /**
     * Default constructor for LoginFilter doesn't append invalid characters.
     */
    LoginFilter() {
        mAppendInvalid = false;
    }
    
    /**
     * This method is called when the buffer is going to replace the
     * range <code>dstart &hellip; dend</code> of <code>dest</code>
     * with the new text from the range <code>start &hellip; end</code>
     * of <code>source</code>.  Returns the CharSequence that we want
     * placed there instead, including an empty string
     * if appropriate, or <code>null</code> to accept the original
     * replacement.  Be careful to not to reject 0-length replacements,
     * as this is what happens when you delete text.
     */
    public CharSequence filter(CharSequence source, int start, int end,
            Spanned dest, int dstart, int dend) {
        onStart();
        
        // Scan through beginning characters in dest, calling onInvalidCharacter() 
        // for each invalid character.
        for (int i = 0; i < dstart; i++) {
            char c = dest.charAt(i);
            if (!isAllowed(c)) onInvalidCharacter(c);
        }

        // Scan through changed characters rejecting disallowed chars
        SpannableStringBuilder modification = null;
        int modoff = 0;

        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (isAllowed(c)) {
                // Character allowed.
                modoff++;
            } else {
                if (mAppendInvalid) {
                    modoff++;
                } else {
                    if (modification == null) {
                        modification = new SpannableStringBuilder(source, start, end);
                        modoff = i - start;
                    }

                    modification.delete(modoff, modoff + 1);
                }

                onInvalidCharacter(c);
            }
        }
        
        // Scan through remaining characters in dest, calling onInvalidCharacter() 
        // for each invalid character.
        for (int i = dend; i < dest.length(); i++) {
            char c = dest.charAt(i);
            if (!isAllowed(c)) onInvalidCharacter(c);
        }
        
        onStop();

        // Either returns null if we made no changes,
        // or what we wanted to change it to if there were changes.
        return modification;
    }
    
    /**
     * Called when we start processing filter.
     */
    public void onStart() {
        
    }
    
    /**
     * Called whenever we encounter an invalid character.
     * @param c the invalid character
     */
    public void onInvalidCharacter(char c) {
        
    }
    
    /**
     * Called when we're done processing filter
     */
    public void onStop() {
        
    }
    
    /**
     * Returns whether or not we allow character c. 
     * Subclasses must override this method.
     */
    public abstract boolean isAllowed(char c);

    /**
     * This filter rejects characters in the user name that are not compatible with GMail 
     * account creation. It prevents the user from entering user names with characters other than 
     * [a-zA-Z0-9.]. 
     * 
     * @deprecated Do not encode assumptions about Google account names into client applications.
     */
    @Deprecated
    public static class UsernameFilterGMail extends LoginFilter {
        
        public UsernameFilterGMail() {
            super(false);
        }
        
        public UsernameFilterGMail(boolean appendInvalid) {
            super(appendInvalid);
        }
        
        @Override
        public boolean isAllowed(char c) {
            // Allow [a-zA-Z0-9@.]
            if ('0' <= c && c <= '9')
                return true;
            if ('a' <= c && c <= 'z')
                return true;
            if ('A' <= c && c <= 'Z')
                return true;
            if ('.' == c)
                return true;
            return false;
        }
    }

    /**
     * This filter rejects characters in the user name that are not compatible with Google login.
     * It is slightly less restrictive than the above filter in that it allows [a-zA-Z0-9._-+]. 
     * 
     */
    public static class UsernameFilterGeneric extends LoginFilter {
        private static final String mAllowed = "@_-+."; // Additional characters
        
        public UsernameFilterGeneric() {
            super(false);
        }
        
        public UsernameFilterGeneric(boolean appendInvalid) {
            super(appendInvalid);
        }
        
        @Override
        public boolean isAllowed(char c) {
            // Allow [a-zA-Z0-9@.]
            if ('0' <= c && c <= '9')
                return true;
            if ('a' <= c && c <= 'z')
                return true;
            if ('A' <= c && c <= 'Z')
                return true;
            if (mAllowed.indexOf(c) != -1)
                return true;
            return false;
        }
    }

    /**
     * This filter is compatible with GMail passwords which restricts characters to 
     * the Latin-1 (ISO8859-1) char set.
     *
     * @deprecated Do not handle a user's Google password. Refer to
     *   <a href="https://support.google.com/accounts/answer/32040">Google Help</a> for
     *   password restriction information.
     */
    @Deprecated
    public static class PasswordFilterGMail extends LoginFilter {
        
        public PasswordFilterGMail() {
            super(false);
        }
        
        public PasswordFilterGMail(boolean appendInvalid) {
            super(appendInvalid);
        }
        
        // We should reject anything not in the Latin-1 (ISO8859-1) charset
        @Override
        public boolean isAllowed(char c) {
            if (32 <= c && c <= 127)
                return true; // standard charset
            // if (128 <= c && c <= 159) return true;  // nonstandard (Windows(TM)(R)) charset
            if (160 <= c && c <= 255)
                return true; // extended charset
            return false;
        }
    }
}
