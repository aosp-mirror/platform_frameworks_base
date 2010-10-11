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

package android.telephony;

import android.telephony.PhoneNumberFormattingTextWatcher;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;

import junit.framework.TestCase;

public class PhoneNumberWatcherTest extends TestCase {
    @SmallTest
    public void testHyphenation() throws Exception {
        SpannableStringBuilder number = new SpannableStringBuilder();
        TextWatcher tw = new PhoneNumberFormattingTextWatcher();
        number.append("555-1212");
        // Move the cursor to the left edge
        Selection.setSelection(number, 0);
        tw.beforeTextChanged(number, 0, 0, 1);
        // Insert an 8 at the beginning
        number.insert(0, "8");
        tw.afterTextChanged(number);
        assertEquals("855-512-12", number.toString());
    }
    
    @SmallTest
    public void testHyphenDeletion() throws Exception {
        SpannableStringBuilder number = new SpannableStringBuilder();
        TextWatcher tw = new PhoneNumberFormattingTextWatcher();
        number.append("555-1212");
        // Move the cursor to after the hyphen
        Selection.setSelection(number, 4);
        // Delete the hyphen
        tw.beforeTextChanged(number, 3, 1, 0);
        number.delete(3, 4);
        tw.afterTextChanged(number);
        // Make sure that it deleted the character before the hyphen 
        assertEquals("551-212", number.toString());
        
        // Make sure it deals with left edge boundary case
        number.insert(0, "-");
        Selection.setSelection(number, 1);
        tw.beforeTextChanged(number, 0, 1, 0);
        number.delete(0, 1);
        tw.afterTextChanged(number);
        // Make sure that it deleted the character before the hyphen 
        assertEquals("551-212", number.toString());
    }
}
