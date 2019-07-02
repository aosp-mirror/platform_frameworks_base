/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.BreakIterator;
import java.util.Locale;

// TODO(Bug: 24062099): Add more tests for non-ascii text.
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WordIteratorTest {

    private WordIterator mWordIterator = new WordIterator();

    private void verifyIsWordWithSurrogate(int beginning, int end, int surrogateIndex) {
        for (int i = beginning; i <= end; i++) {
            if (i == surrogateIndex) continue;
            assertEquals(beginning, mWordIterator.getBeginning(i));
            assertEquals(end, mWordIterator.getEnd(i));
        }
    }

    private void setCharSequence(String string) {
        mWordIterator.setCharSequence(string, 0, string.length());
    }

    private void verifyIsWord(int beginning, int end) {
        verifyIsWordWithSurrogate(beginning, end, -1);
    }

    private void verifyIsNotWord(int beginning, int end) {
        for (int i = beginning; i <= end; i++) {
            assertEquals(BreakIterator.DONE, mWordIterator.getBeginning(i));
            assertEquals(BreakIterator.DONE, mWordIterator.getEnd(i));
        }
    }

    @Test
    public void testEmptyString() {
        setCharSequence("");
        assertEquals(BreakIterator.DONE, mWordIterator.following(0));
        assertEquals(BreakIterator.DONE, mWordIterator.preceding(0));

        assertEquals(BreakIterator.DONE, mWordIterator.getBeginning(0));
        assertEquals(BreakIterator.DONE, mWordIterator.getEnd(0));
    }

    @Test
    public void testOneWord() {
        setCharSequence("I");
        verifyIsWord(0, 1);

        setCharSequence("am");
        verifyIsWord(0, 2);

        setCharSequence("zen");
        verifyIsWord(0, 3);
    }

    @Test
    public void testSpacesOnly() {
        setCharSequence(" ");
        verifyIsNotWord(0, 1);

        setCharSequence(", ");
        verifyIsNotWord(0, 2);

        setCharSequence(":-)");
        verifyIsNotWord(0, 3);
    }

    @Test
    public void testBeginningEnd() {
        setCharSequence("Well hello,   there! ");
        //                  0123456789012345678901
        verifyIsWord(0, 4);
        verifyIsWord(5, 10);
        verifyIsNotWord(11, 13);
        verifyIsWord(14, 19);
        verifyIsNotWord(20, 21);

        setCharSequence("  Another - sentence");
        //                  012345678901234567890
        verifyIsNotWord(0, 1);
        verifyIsWord(2, 9);
        verifyIsNotWord(10, 11);
        verifyIsWord(12, 20);

        setCharSequence("This is \u0644\u0627 tested"); // Lama-aleph
        //                  012345678     9     01234567
        verifyIsWord(0, 4);
        verifyIsWord(5, 7);
        verifyIsWord(8, 10);
        verifyIsWord(11, 17);
    }

    @Test
    public void testSurrogate() {
        final String gothicBairkan = "\uD800\uDF31";

        setCharSequence("one we" + gothicBairkan + "ird word");
        //                  012345    67         890123456

        verifyIsWord(0, 3);
        // Skip index 7 (there is no point in starting between the two surrogate characters)
        verifyIsWordWithSurrogate(4, 11, 7);
        verifyIsWord(12, 16);

        setCharSequence("one " + gothicBairkan + "xxx word");
        //                  0123    45         678901234

        verifyIsWord(0, 3);
        verifyIsWordWithSurrogate(4, 9, 5);
        verifyIsWord(10, 14);

        setCharSequence("one xxx" + gothicBairkan + " word");
        //                  0123456    78         901234

        verifyIsWord(0, 3);
        verifyIsWordWithSurrogate(4, 9, 8);
        verifyIsWord(10, 14);
    }

    @Test
    public void testSetCharSequence() {
        final String text = "text";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);

        try {
            wordIterator.setCharSequence(text, 100, 100);
            fail("setCharSequence with invalid start and end values should throw "
                    + "IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }
        try {
            wordIterator.setCharSequence(text, -100, -100);
            fail("setCharSequence with invalid start and end values should throw "
                    + "IndexOutOfBoundsException.");
        } catch (IndexOutOfBoundsException e) {
        }

        wordIterator.setCharSequence(text, 0, text.length());
        wordIterator.setCharSequence(text, 0, 0);
        wordIterator.setCharSequence(text, text.length(), text.length());
    }

    @Test
    public void testWindowWidth() {
        final String text = "aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii jjjj kkkk llll mmmm nnnn";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);

        // The first 'n' is more than 50 characters into the string.
        wordIterator.setCharSequence(text, text.indexOf('n'), text.length());
        final int expectedWindowStart = text.indexOf('n') - 50;
        assertEquals(expectedWindowStart, wordIterator.preceding(expectedWindowStart + 1));
        assertEquals(BreakIterator.DONE, wordIterator.preceding(expectedWindowStart));

        wordIterator.setCharSequence(text, 0, 1);
        final int expectedWindowEnd = 1 + 50;
        assertEquals(expectedWindowEnd, wordIterator.following(expectedWindowEnd - 1));
        assertEquals(BreakIterator.DONE, wordIterator.following(expectedWindowEnd));
    }

    @Test
    public void testPreceding() {
        final String text = "abc def-ghi. jkl";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        try {
            wordIterator.preceding(-1);
            fail("preceding with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.preceding(text.length() + 1);
            fail("preceding with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        assertEquals(BreakIterator.DONE, wordIterator.preceding(text.indexOf('a')));
        assertEquals(text.indexOf('a'), wordIterator.preceding(text.indexOf('c')));
        assertEquals(text.indexOf('a'), wordIterator.preceding(text.indexOf('d')));
        assertEquals(text.indexOf('d'), wordIterator.preceding(text.indexOf('e')));
        assertEquals(text.indexOf('d'), wordIterator.preceding(text.indexOf('g')));
        assertEquals(text.indexOf('g'), wordIterator.preceding(text.indexOf('h')));
        assertEquals(text.indexOf('g'), wordIterator.preceding(text.indexOf('j')));
        assertEquals(text.indexOf('j'), wordIterator.preceding(text.indexOf('l')));

        // The results should be the same even if we set an smaller window, since WordIterator
        // enlargens the window by 50 code units on each side anyway.
        wordIterator.setCharSequence(text, text.indexOf('d'), text.indexOf('e'));

        assertEquals(BreakIterator.DONE, wordIterator.preceding(text.indexOf('a')));
        assertEquals(text.indexOf('a'), wordIterator.preceding(text.indexOf('c')));
        assertEquals(text.indexOf('a'), wordIterator.preceding(text.indexOf('d')));
        assertEquals(text.indexOf('d'), wordIterator.preceding(text.indexOf('e')));
        assertEquals(text.indexOf('d'), wordIterator.preceding(text.indexOf('g')));
        assertEquals(text.indexOf('g'), wordIterator.preceding(text.indexOf('h')));
        assertEquals(text.indexOf('g'), wordIterator.preceding(text.indexOf('j')));
        assertEquals(text.indexOf('j'), wordIterator.preceding(text.indexOf('l')));
    }

    @Test
    public void testFollowing() {
        final String text = "abc def-ghi. jkl";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        try {
            wordIterator.following(-1);
            fail("following with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.following(text.length() + 1);
            fail("following with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        assertEquals(text.indexOf('c') + 1, wordIterator.following(text.indexOf('a')));
        assertEquals(text.indexOf('c') + 1, wordIterator.following(text.indexOf('c')));
        assertEquals(text.indexOf('f') + 1, wordIterator.following(text.indexOf('c') + 1));
        assertEquals(text.indexOf('f') + 1, wordIterator.following(text.indexOf('d')));
        assertEquals(text.indexOf('i') + 1, wordIterator.following(text.indexOf('-')));
        assertEquals(text.indexOf('i') + 1, wordIterator.following(text.indexOf('g')));
        assertEquals(text.length(), wordIterator.following(text.indexOf('j')));
        assertEquals(BreakIterator.DONE, wordIterator.following(text.length()));

        // The results should be the same even if we set an smaller window, since WordIterator
        // enlargens the window by 50 code units on each side anyway.
        wordIterator.setCharSequence(text, text.indexOf('d'), text.indexOf('e'));

        assertEquals(text.indexOf('c') + 1, wordIterator.following(text.indexOf('a')));
        assertEquals(text.indexOf('c') + 1, wordIterator.following(text.indexOf('c')));
        assertEquals(text.indexOf('f') + 1, wordIterator.following(text.indexOf('c') + 1));
        assertEquals(text.indexOf('f') + 1, wordIterator.following(text.indexOf('d')));
        assertEquals(text.indexOf('i') + 1, wordIterator.following(text.indexOf('-')));
        assertEquals(text.indexOf('i') + 1, wordIterator.following(text.indexOf('g')));
        assertEquals(text.length(), wordIterator.following(text.indexOf('j')));
        assertEquals(BreakIterator.DONE, wordIterator.following(text.length()));
    }

    @Test
    public void testIsBoundary() {
        final String text = "abc def-ghi. jkl";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        try {
            wordIterator.isBoundary(-1);
            fail("isBoundary with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.isBoundary(text.length() + 1);
            fail("isBoundary with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        assertTrue(wordIterator.isBoundary(text.indexOf('a')));
        assertFalse(wordIterator.isBoundary(text.indexOf('b')));
        assertTrue(wordIterator.isBoundary(text.indexOf('c') + 1));
        assertTrue(wordIterator.isBoundary(text.indexOf('d')));
        assertTrue(wordIterator.isBoundary(text.indexOf('-')));
        assertTrue(wordIterator.isBoundary(text.indexOf('g')));
        assertTrue(wordIterator.isBoundary(text.indexOf('.')));
        assertTrue(wordIterator.isBoundary(text.indexOf('j')));
        assertTrue(wordIterator.isBoundary(text.length()));
    }

    @Test
    public void testNextBoundary() {
        final String text = "abc def-ghi. jkl";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        try {
            wordIterator.nextBoundary(-1);
            fail("nextBoundary with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.nextBoundary(text.length() + 1);
            fail("nextBoundary with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }


        int currentOffset = 0;
        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(text.indexOf('c') + 1, currentOffset);

        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(text.indexOf('d'), currentOffset);

        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(text.indexOf('f') + 1, currentOffset);

        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(text.indexOf('g'), currentOffset);

        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(text.indexOf('i') + 1, currentOffset);

        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(text.indexOf('.') + 1, currentOffset);

        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(text.indexOf('j'), currentOffset);

        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(text.length(), currentOffset);

        currentOffset = wordIterator.nextBoundary(currentOffset);
        assertEquals(BreakIterator.DONE, currentOffset);
    }

    @Test
    public void testPrevBoundary() {
        final String text = "abc def-ghi. jkl";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        try {
            wordIterator.prevBoundary(-1);
            fail("prevBoundary with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.prevBoundary(text.length() + 1);
            fail("prevBoundary with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        int currentOffset = text.length();
        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(text.indexOf('j'), currentOffset);

        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(text.indexOf('.') + 1, currentOffset);

        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(text.indexOf('i') + 1, currentOffset);

        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(text.indexOf('g'), currentOffset);

        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(text.indexOf('f') + 1, currentOffset);

        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(text.indexOf('d'), currentOffset);

        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(text.indexOf('c') + 1, currentOffset);

        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(text.indexOf('a'), currentOffset);

        currentOffset = wordIterator.prevBoundary(currentOffset);
        assertEquals(BreakIterator.DONE, currentOffset);
    }

    @Test
    public void testGetBeginning() {
        {
            final String text = "abc def-ghi. jkl";
            WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
            wordIterator.setCharSequence(text, 0, text.length());
            try {
                wordIterator.getBeginning(-1);
                fail("getBeginning with invalid offset should throw IllegalArgumentException.");
            } catch (IllegalArgumentException e) {
            }
            try {
                wordIterator.getBeginning(text.length() + 1);
                fail("getBeginning with invalid offset should throw IllegalArgumentException.");
            } catch (IllegalArgumentException e) {
            }
            try {
                wordIterator.getPrevWordBeginningOnTwoWordsBoundary(-1);
                fail("getPrevWordBeginningOnTwoWordsBoundary with invalid offset should throw "
                        + "IllegalArgumentException.");
            } catch (IllegalArgumentException e) {
            }
            try {
                wordIterator.getPrevWordBeginningOnTwoWordsBoundary(text.length() + 1);
                fail("getPrevWordBeginningOnTwoWordsBoundary with invalid offset should throw "
                        + "IllegalArgumentException.");
            } catch (IllegalArgumentException e) {
            }
        }

        {
            final String text = "abc def-ghi. jkl";
            WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
            wordIterator.setCharSequence(text, 0, text.length());

            assertEquals(text.indexOf('a'), wordIterator.getBeginning(text.indexOf('a')));
            assertEquals(text.indexOf('a'), wordIterator.getBeginning(text.indexOf('c')));
            assertEquals(text.indexOf('a'), wordIterator.getBeginning(text.indexOf('c') + 1));
            assertEquals(text.indexOf('d'), wordIterator.getBeginning(text.indexOf('d')));
            assertEquals(text.indexOf('d'), wordIterator.getBeginning(text.indexOf('-')));
            assertEquals(text.indexOf('g'), wordIterator.getBeginning(text.indexOf('g')));
            assertEquals(text.indexOf('g'), wordIterator.getBeginning(text.indexOf('.')));
            assertEquals(BreakIterator.DONE, wordIterator.getBeginning(text.indexOf('.') + 1));
            assertEquals(text.indexOf('j'), wordIterator.getBeginning(text.indexOf('j')));
            assertEquals(text.indexOf('j'), wordIterator.getBeginning(text.indexOf('l') + 1));

            for (int i = 0; i < text.length(); i++) {
                assertEquals(wordIterator.getBeginning(i),
                        wordIterator.getPrevWordBeginningOnTwoWordsBoundary(i));
            }
        }

        {
            // Japanese HIRAGANA letter + KATAKANA letters
            final String text = "\u3042\u30A2\u30A3\u30A4";
            WordIterator wordIterator = new WordIterator(Locale.JAPANESE);
            wordIterator.setCharSequence(text, 0, text.length());

            assertEquals(text.indexOf('\u3042'), wordIterator.getBeginning(text.indexOf('\u3042')));
            assertEquals(text.indexOf('\u30A2'), wordIterator.getBeginning(text.indexOf('\u30A2')));
            assertEquals(text.indexOf('\u30A2'), wordIterator.getBeginning(text.indexOf('\u30A4')));
            assertEquals(text.indexOf('\u30A2'), wordIterator.getBeginning(text.length()));

            assertEquals(text.indexOf('\u3042'),
                    wordIterator.getPrevWordBeginningOnTwoWordsBoundary(text.indexOf('\u3042')));
            assertEquals(text.indexOf('\u3042'),
                    wordIterator.getPrevWordBeginningOnTwoWordsBoundary(text.indexOf('\u30A2')));
            assertEquals(text.indexOf('\u30A2'),
                    wordIterator.getPrevWordBeginningOnTwoWordsBoundary(text.indexOf('\u30A4')));
            assertEquals(text.indexOf('\u30A2'),
                    wordIterator.getPrevWordBeginningOnTwoWordsBoundary(text.length()));
        }
    }

    @Test
    public void testGetEnd() {
        {
            final String text = "abc def-ghi. jkl";
            WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
            wordIterator.setCharSequence(text, 0, text.length());
            try {
                wordIterator.getEnd(-1);
                fail("getEnd with invalid offset should throw IllegalArgumentException.");
            } catch (IllegalArgumentException e) {
            }
            try {
                wordIterator.getEnd(text.length() + 1);
                fail("getEnd with invalid offset should throw IllegalArgumentException.");
            } catch (IllegalArgumentException e) {
            }
            try {
                wordIterator.getNextWordEndOnTwoWordBoundary(-1);
                fail("getNextWordEndOnTwoWordBoundary with invalid offset should throw "
                        + "IllegalArgumentException.");
            } catch (IllegalArgumentException e) {
            }
            try {
                wordIterator.getNextWordEndOnTwoWordBoundary(text.length() + 1);
                fail("getNextWordEndOnTwoWordBoundary with invalid offset should throw "
                        + "IllegalArgumentException.");
            } catch (IllegalArgumentException e) {
            }
        }

        {
            final String text = "abc def-ghi. jkl";
            WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
            wordIterator.setCharSequence(text, 0, text.length());

            assertEquals(text.indexOf('c') + 1, wordIterator.getEnd(text.indexOf('a')));
            assertEquals(text.indexOf('c') + 1, wordIterator.getEnd(text.indexOf('c')));
            assertEquals(text.indexOf('c') + 1, wordIterator.getEnd(text.indexOf('c') + 1));
            assertEquals(text.indexOf('f') + 1, wordIterator.getEnd(text.indexOf('d')));
            assertEquals(text.indexOf('f') + 1, wordIterator.getEnd(text.indexOf('f') + 1));
            assertEquals(text.indexOf('i') + 1, wordIterator.getEnd(text.indexOf('g')));
            assertEquals(text.indexOf('i') + 1, wordIterator.getEnd(text.indexOf('i') + 1));
            assertEquals(BreakIterator.DONE, wordIterator.getEnd(text.indexOf('.') + 1));
            assertEquals(text.indexOf('l') + 1, wordIterator.getEnd(text.indexOf('j')));
            assertEquals(text.indexOf('l') + 1, wordIterator.getEnd(text.indexOf('l') + 1));

            for (int i = 0; i < text.length(); i++) {
                assertEquals(wordIterator.getEnd(i),
                        wordIterator.getNextWordEndOnTwoWordBoundary(i));
            }
        }

        {
            // Japanese HIRAGANA letter + KATAKANA letters
            final String text = "\u3042\u30A2\u30A3\u30A4";
            WordIterator wordIterator = new WordIterator(Locale.JAPANESE);
            wordIterator.setCharSequence(text, 0, text.length());

            assertEquals(text.indexOf('\u3042') + 1, wordIterator.getEnd(text.indexOf('\u3042')));
            assertEquals(text.indexOf('\u3042') + 1, wordIterator.getEnd(text.indexOf('\u30A2')));
            assertEquals(text.indexOf('\u30A4') + 1, wordIterator.getEnd(text.indexOf('\u30A4')));
            assertEquals(text.indexOf('\u30A4') + 1,
                    wordIterator.getEnd(text.indexOf('\u30A4') + 1));

            assertEquals(text.indexOf('\u3042') + 1,
                    wordIterator.getNextWordEndOnTwoWordBoundary(text.indexOf('\u3042')));
            assertEquals(text.indexOf('\u30A4') + 1,
                    wordIterator.getNextWordEndOnTwoWordBoundary(text.indexOf('\u30A2')));
            assertEquals(text.indexOf('\u30A4') + 1,
                    wordIterator.getNextWordEndOnTwoWordBoundary(text.indexOf('\u30A4')));
            assertEquals(text.indexOf('\u30A4') + 1,
                    wordIterator.getNextWordEndOnTwoWordBoundary(text.indexOf('\u30A4') + 1));
        }
    }

    @Test
    public void testGetPunctuationBeginning() {
        final String text = "abc!? (^^;) def";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        try {
            wordIterator.getPunctuationBeginning(BreakIterator.DONE);
            fail("getPunctuationBeginning with invalid offset should throw "
                    + "IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.getPunctuationBeginning(-2);
            fail("getPunctuationBeginning with invalid offset should throw "
                    + "IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.getPunctuationBeginning(text.length() + 1);
            fail("getPunctuationBeginning with invalid offset should throw "
                    + "IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        assertEquals(BreakIterator.DONE, wordIterator.getPunctuationBeginning(text.indexOf('a')));
        assertEquals(BreakIterator.DONE, wordIterator.getPunctuationBeginning(text.indexOf('c')));
        assertEquals(text.indexOf('!'), wordIterator.getPunctuationBeginning(text.indexOf('!')));
        assertEquals(text.indexOf('!'),
                wordIterator.getPunctuationBeginning(text.indexOf('?') + 1));
        assertEquals(text.indexOf(';'), wordIterator.getPunctuationBeginning(text.indexOf(';')));
        assertEquals(text.indexOf(';'), wordIterator.getPunctuationBeginning(text.indexOf(')')));
        assertEquals(text.indexOf(';'), wordIterator.getPunctuationBeginning(text.length()));
    }

    @Test
    public void testGetPunctuationEnd() {
        final String text = "abc!? (^^;) def";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        try {
            wordIterator.getPunctuationEnd(BreakIterator.DONE);
            fail("getPunctuationEnd with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.getPunctuationEnd(-2);
            fail("getPunctuationEnd with invalid offset should throw IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }
        try {
            wordIterator.getPunctuationEnd(text.length() + 1);
            fail("getPunctuationBeginning with invalid offset should throw "
                    + "IllegalArgumentException.");
        } catch (IllegalArgumentException e) {
        }

        assertEquals(text.indexOf('?') + 1, wordIterator.getPunctuationEnd(text.indexOf('a')));
        assertEquals(text.indexOf('?') + 1, wordIterator.getPunctuationEnd(text.indexOf('?') + 1));
        assertEquals(text.indexOf('(') + 1, wordIterator.getPunctuationEnd(text.indexOf('(')));
        assertEquals(text.indexOf(')') + 1, wordIterator.getPunctuationEnd(text.indexOf('(') + 2));
        assertEquals(text.indexOf(')') + 1, wordIterator.getPunctuationEnd(text.indexOf(')') + 1));
        assertEquals(BreakIterator.DONE, wordIterator.getPunctuationEnd(text.indexOf('d')));
        assertEquals(BreakIterator.DONE, wordIterator.getPunctuationEnd(text.length()));
    }

    @Test
    public void testIsAfterPunctuation() {
        final String text = "abc!? (^^;) def";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        assertFalse(wordIterator.isAfterPunctuation(text.indexOf('a')));
        assertFalse(wordIterator.isAfterPunctuation(text.indexOf('!')));
        assertTrue(wordIterator.isAfterPunctuation(text.indexOf('?')));
        assertTrue(wordIterator.isAfterPunctuation(text.indexOf('?') + 1));
        assertFalse(wordIterator.isAfterPunctuation(text.indexOf('d')));

        assertFalse(wordIterator.isAfterPunctuation(BreakIterator.DONE));
        assertFalse(wordIterator.isAfterPunctuation(text.length() + 1));
    }

    @Test
    public void testIsOnPunctuation() {
        final String text = "abc!? (^^;) def";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        assertFalse(wordIterator.isOnPunctuation(text.indexOf('a')));
        assertTrue(wordIterator.isOnPunctuation(text.indexOf('!')));
        assertTrue(wordIterator.isOnPunctuation(text.indexOf('?')));
        assertFalse(wordIterator.isOnPunctuation(text.indexOf('?') + 1));
        assertTrue(wordIterator.isOnPunctuation(text.indexOf(')')));
        assertFalse(wordIterator.isOnPunctuation(text.indexOf(')') + 1));
        assertFalse(wordIterator.isOnPunctuation(text.indexOf('d')));

        assertFalse(wordIterator.isOnPunctuation(BreakIterator.DONE));
        assertFalse(wordIterator.isOnPunctuation(text.length()));
        assertFalse(wordIterator.isOnPunctuation(text.length() + 1));
    }

    @Test
    public void testApostropheMiddleOfWord() {
        // These tests confirm that the word "isn't" is treated like one word.
        final String text = "isn't he";
        WordIterator wordIterator = new WordIterator(Locale.ENGLISH);
        wordIterator.setCharSequence(text, 0, text.length());

        assertEquals(text.indexOf('i'), wordIterator.preceding(text.indexOf('h')));
        assertEquals(text.indexOf('t') + 1, wordIterator.following(text.indexOf('i')));

        assertTrue(wordIterator.isBoundary(text.indexOf('i')));
        assertFalse(wordIterator.isBoundary(text.indexOf('\'')));
        assertFalse(wordIterator.isBoundary(text.indexOf('t')));
        assertTrue(wordIterator.isBoundary(text.indexOf('t') + 1));
        assertTrue(wordIterator.isBoundary(text.indexOf('h')));

        assertEquals(text.indexOf('i'), wordIterator.getBeginning(text.indexOf('i')));
        assertEquals(text.indexOf('i'), wordIterator.getBeginning(text.indexOf('n')));
        assertEquals(text.indexOf('i'), wordIterator.getBeginning(text.indexOf('\'')));
        assertEquals(text.indexOf('i'), wordIterator.getBeginning(text.indexOf('t')));
        assertEquals(text.indexOf('i'), wordIterator.getBeginning(text.indexOf('t') + 1));
        assertEquals(text.indexOf('h'), wordIterator.getBeginning(text.indexOf('h')));

        assertEquals(text.indexOf('t') + 1, wordIterator.getEnd(text.indexOf('i')));
        assertEquals(text.indexOf('t') + 1, wordIterator.getEnd(text.indexOf('n')));
        assertEquals(text.indexOf('t') + 1, wordIterator.getEnd(text.indexOf('\'')));
        assertEquals(text.indexOf('t') + 1, wordIterator.getEnd(text.indexOf('t')));
        assertEquals(text.indexOf('e') + 1, wordIterator.getEnd(text.indexOf('h')));
    }
}
