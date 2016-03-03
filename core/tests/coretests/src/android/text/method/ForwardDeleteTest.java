/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.Activity;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.text.InputType;
import android.text.method.BaseKeyListener;
import android.text.method.KeyListenerTestCase;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView.BufferType;

/**
 * Test forward delete key handling of  {@link android.text.method.BaseKeyListener}.
 *
 * TODO: Move some of test cases to the CTS.
 */
public class ForwardDeleteTest extends KeyListenerTestCase {
    private static final BaseKeyListener mKeyListener = new BaseKeyListener() {
        public int getInputType() {
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        }
    };

    // Sync the state to the TextView and call onKeyDown with KEYCODE_FORWARD_DEL key event.
    // Then update the state to the result of TextView.
    private void forwardDelete(final EditorState state, int modifiers) {
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                mTextView.setText(state.mText, BufferType.EDITABLE);
                mTextView.setKeyListener(mKeyListener);
                mTextView.setSelection(state.mSelectionStart, state.mSelectionEnd);
            }
        });
        mInstrumentation.waitForIdleSync();
        assertTrue(mTextView.hasWindowFocus());

        final KeyEvent keyEvent = getKey(KeyEvent.KEYCODE_FORWARD_DEL, modifiers);
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                mTextView.onKeyDown(keyEvent.getKeyCode(), keyEvent);
            }
        });
        mInstrumentation.waitForIdleSync();

        state.mText = mTextView.getText();
        state.mSelectionStart = mTextView.getSelectionStart();
        state.mSelectionEnd = mTextView.getSelectionEnd();
    }

    @SmallTest
    public void testSurrogatePairs() {
        EditorState state = new EditorState();

        // U+1F441 is EYE
        state.setByString("| U+1F441");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // U+1F5E8 is LEFT SPEECH BUBBLE
        state.setByString("| U+1F441 U+1F5E8");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F5E8");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // TODO: introduce edge cases.
    }

    @SmallTest
    public void testReplacementSpan() {
        EditorState state = new EditorState();

        state.setByString("| 'abc' ( 'de' ) 'fg'");
        forwardDelete(state, 0);
        state.assertEquals("| 'bc' ( 'de' ) 'fg'");
        forwardDelete(state, 0);
        state.assertEquals("| 'c' ( 'de' ) 'fg'");
        forwardDelete(state, 0);
        state.assertEquals("| ( 'de' ) 'fg'");
        forwardDelete(state, 0);
        state.assertEquals("| 'fg'");
        forwardDelete(state, 0);
        state.assertEquals("| 'g'");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("'abc' [ ( 'de' ) ] 'fg'");
        forwardDelete(state, 0);
        state.assertEquals("'abc' | 'fg'");
        forwardDelete(state, 0);
        state.assertEquals("'abc' | 'g'");
        forwardDelete(state, 0);
        state.assertEquals("'abc' |");
        forwardDelete(state, 0);
        state.assertEquals("'abc' |");

        state.setByString("'ab' [ 'c' ( 'de' ) 'f' ] 'g'");
        forwardDelete(state, 0);
        state.assertEquals("'ab' | 'g'");
        forwardDelete(state, 0);
        state.assertEquals("'ab' |");
        forwardDelete(state, 0);
        state.assertEquals("'ab' |");

        // TODO: introduce edge cases.
    }

    @SmallTest
    public void testCombiningEnclosingKeycaps() {
        EditorState state = new EditorState();

        // U+20E3 is COMBINING ENCLOSING KEYCAP.
        state.setByString("| '1' U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Edge cases
        // multiple COMBINING ENCLOSING KEYCAP
        state.setByString("| '1' U+20E3 U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Isolated COMBINING ENCLOSING KEYCAP
        state.setByString("| U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Isolated multiple COMBINING ENCLOSING KEYCAP
        state.setByString("| U+20E3 U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");
    }

    @SmallTest
    public void testVariationSelector() {
        EditorState state = new EditorState();

        // U+FE0F is VARIATION SELECTOR-16.
        state.setByString("| '#' U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // U+E0100 is VARIATION SELECTOR-17.
        state.setByString("| U+845B U+E0100");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Edge cases
        // Isolated variation selectors
        state.setByString("| U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| U+E0100");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Isolated multiple variation selectors
        state.setByString("| U+FE0F U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| U+FE0F U+E0100");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| U+E0100 U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| U+E0100 U+E0100");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Multiple variation selectors
        state.setByString("| '#' U+FE0F U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| '#' U+FE0F U+E0100");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| U+845B U+E0100 U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| U+845B U+E0100 U+E0100");
        forwardDelete(state, 0);
        state.assertEquals("|");
    }

    @SmallTest
    public void testEmojiZeroWidthJoinerSequence() {
        EditorState state = new EditorState();

        // U+200D is ZERO WIDTH JOINER.
        state.setByString("| U+1F441 U+200D U+1F5E8");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| U+1F468 U+200D U+2764 U+FE0F U+200D U+1F48B U+200D U+1F468");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Edge cases
        // End with ZERO WIDTH JOINER
        state.setByString("| U+1F441 U+200D");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Start with ZERO WIDTH JOINER
        state.setByString("| U+200D U+1F5E8");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F5E8");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Multiple ZERO WIDTH JOINER
        state.setByString("| U+1F441 U+200D U+200D U+1F5E8");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F5E8");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Isolated ZERO WIDTH JOINER
        state.setByString("| U+200D");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Isolated multiple ZERO WIDTH JOINER
        state.setByString("| U+200D U+200D");
        forwardDelete(state, 0);
        state.assertEquals("|");
    }

    @SmallTest
    public void testFlags() {
        EditorState state = new EditorState();

        // U+1F1FA is REGIONAL INDICATOR SYMBOL LETTER U.
        // U+1F1F8 is REGIONAL INDICATOR SYMBOL LETTER S.
        state.setByString("| U+1F1FA U+1F1F8");
        forwardDelete(state, 0);
        state.assertEquals("|");

        state.setByString("| U+1F1FA U+1F1F8 U+1F1FA U+1F1F8");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F1FA U+1F1F8");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Edge cases
        // Isolated regional indicator symbol
        state.setByString("| U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Odd numbered regional indicator symbols
        state.setByString("| U+1F1FA U+1F1F8 U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("|");
    }

    @SmallTest
    public void testEmojiModifier() {
        EditorState state = new EditorState();

        // U+1F3FB is EMOJI MODIFIER FITZPATRICK TYPE-1-2.
        state.setByString("| U+1F466 U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Edge cases
        // Isolated emoji modifier
        state.setByString("| U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Isolated multiple emoji modifier
        state.setByString("| U+1F3FB U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Multiple emoji modifiers
        state.setByString("| U+1F466 U+1F3FB U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("|");
    }

    @SmallTest
    public void testMixedEdgeCases() {
        EditorState state = new EditorState();

        // COMBINING ENCLOSING KEYCAP + variation selector
        state.setByString("| '1' U+20E3 U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Variation selector + COMBINING ENCLOSING KEYCAP
        state.setByString("| U+2665 U+FE0F U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // COMBINING ENCLOSING KEYCAP + ending with ZERO WIDTH JOINER
        state.setByString("| '1' U+20E3 U+200D");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // COMBINING ENCLOSING KEYCAP + ZERO WIDTH JOINER
        state.setByString("| '1' U+20E3 U+200D U+1F5E8");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F5E8 ");

        // Start with ZERO WIDTH JOINER + COMBINING ENCLOSING KEYCAP
        state.setByString("| U+200D U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // ZERO WIDTH JOINER + COMBINING ENCLOSING KEYCAP
        state.setByString("| U+1F441 U+200D U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // COMBINING ENCLOSING KEYCAP + regional indicator symbol
        state.setByString("| '1' U+20E3 U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + COMBINING ENCLOSING KEYCAP
        state.setByString("| U+1F1FA U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // COMBINING ENCLOSING KEYCAP + emoji modifier
        state.setByString("| '1' U+20E3 U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F3FB");

        // Emoji modifier + COMBINING ENCLOSING KEYCAP
        state.setByString("| U+1F466 U+1F3FB U+20E3");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Variation selector + end with ZERO WIDTH JOINER
        state.setByString("| U+2665 U+FE0F U+200D");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Variation selector + ZERO WIDTH JOINER
        state.setByString("| U+1F469 U+200D U+2764 U+FE0F U+200D U+1F469");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Start with ZERO WIDTH JOINER + variation selector
        state.setByString("| U+200D U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // ZERO WIDTH JOINER + variation selector
        state.setByString("| U+1F469 U+200D U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Variation selector + regional indicator symbol
        state.setByString("| U+2665 U+FE0F U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + variation selector
        state.setByString("| U+1F1FA U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Variation selector + emoji modifier
        state.setByString("| U+2665 U+FE0F U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F3FB");

        // Emoji modifier + variation selector
        state.setByString("| U+1F466 U+1F3FB U+FE0F");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Start with ZERO WIDTH JOINER + regional indicator symbol
        state.setByString("| U+200D U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // ZERO WIDTH JOINER + regional indicator symbol
        state.setByString("| U+1F469 U+200D U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F1FA");

        // Regional indicator symbol + end with ZERO WIDTH JOINER
        state.setByString("| U+1F1FA U+200D");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + ZERO WIDTH JOINER
        state.setByString("| U+1F1FA U+200D U+1F469");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F469");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Start with ZERO WIDTH JOINER + emoji modifier
        state.setByString("| U+200D U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F3FB");

        // ZERO WIDTH JOINER + emoji modifier
        state.setByString("| U+1F469 U+200D U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F3FB");

        // Emoji modifier + end with ZERO WIDTH JOINER
        state.setByString("| U+1F466 U+1F3FB U+200D");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Emoji modifier + ZERO WIDTH JOINER
        state.setByString("| U+1F466 U+1F3FB U+200D U+1F469");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F469");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + emoji modifier
        state.setByString("| U+1F1FA U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F3FB");
        forwardDelete(state, 0);
        state.assertEquals("|");

        // Emoji modifier + regional indicator symbol
        state.setByString("| U+1F466 U+1F3FB U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("| U+1F1FA");
        forwardDelete(state, 0);
        state.assertEquals("|");
    }
}
