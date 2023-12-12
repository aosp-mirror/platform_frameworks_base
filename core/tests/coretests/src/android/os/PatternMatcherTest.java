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

package android.os;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SmallTest
public class PatternMatcherTest {

    @Test
    public void testAdvancedPatternMatchesAnyToken() {
        PatternMatcher matcher = new PatternMatcher(".", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches("a", matcher);
        assertMatches("b", matcher);
        assertNotMatches("", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesSetToken() {
        PatternMatcher matcher = new PatternMatcher("[a]", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches("a", matcher);
        assertNotMatches("b", matcher);

        matcher = new PatternMatcher("[.*+{}\\]\\\\[]", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches(".", matcher);
        assertMatches("*", matcher);
        assertMatches("+", matcher);
        assertMatches("{", matcher);
        assertMatches("}", matcher);
        assertMatches("]", matcher);
        assertMatches("\\", matcher);
        assertMatches("[", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesSetCharacterClassToken() {
        PatternMatcher matcher = new PatternMatcher("[a-z]", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches("a", matcher);
        assertMatches("b", matcher);
        assertNotMatches("A", matcher);
        assertNotMatches("1", matcher);

        matcher = new PatternMatcher("[a-z][0-9]", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches("a1", matcher);
        assertNotMatches("1a", matcher);
        assertNotMatches("aa", matcher);

        matcher = new PatternMatcher("[z-a]", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertNotMatches("a", matcher);
        assertNotMatches("z", matcher);
        assertNotMatches("A", matcher);

        matcher = new PatternMatcher("[^0-9]", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches("a", matcher);
        assertMatches("z", matcher);
        assertMatches("A", matcher);
        assertNotMatches("9", matcher);
        assertNotMatches("5", matcher);
        assertNotMatches("0", matcher);

        assertPoorlyFormattedPattern("[]a]");
        matcher = new PatternMatcher("[\\[a]", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches("a", matcher);
        assertMatches("[", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesEscapedCharacters() {
        PatternMatcher matcher = new PatternMatcher("\\.", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches(".", matcher);
        assertNotMatches("a", matcher);
        assertNotMatches("1", matcher);

        matcher = new PatternMatcher("a\\+", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches("a+", matcher);
        assertNotMatches("a", matcher);
        assertNotMatches("aaaaa", matcher);

        matcher = new PatternMatcher("[\\a-\\z]", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertMatches("a", matcher);
        assertMatches("z", matcher);
        assertNotMatches("A", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesLiteralTokens() {
        PatternMatcher matcher = new PatternMatcher("a", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertNotMatches("", matcher);
        assertMatches("a", matcher);
        assertNotMatches("z", matcher);

        matcher = new PatternMatcher("az", PatternMatcher.PATTERN_ADVANCED_GLOB);
        assertNotMatches("", matcher);
        assertMatches("az", matcher);
        assertNotMatches("za", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesSetZeroOrMore() {
        PatternMatcher matcher = new PatternMatcher("[a-z]*", PatternMatcher.PATTERN_ADVANCED_GLOB);

        assertMatches("", matcher);
        assertMatches("a", matcher);
        assertMatches("abcdefg", matcher);
        assertNotMatches("abc1", matcher);
        assertNotMatches("1abc", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesSetOneOrMore() {
        PatternMatcher matcher = new PatternMatcher("[a-z]+", PatternMatcher.PATTERN_ADVANCED_GLOB);

        assertNotMatches("", matcher);
        assertMatches("a", matcher);
        assertMatches("abcdefg", matcher);
        assertNotMatches("abc1", matcher);
        assertNotMatches("1abc", matcher);
    }


    @Test
    public void testAdvancedPatternMatchesSingleRange() {
        PatternMatcher matcher = new PatternMatcher("[a-z]{1}",
                PatternMatcher.PATTERN_ADVANCED_GLOB);

        assertNotMatches("", matcher);
        assertMatches("a", matcher);
        assertMatches("z", matcher);
        assertNotMatches("1", matcher);
        assertNotMatches("aa", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesFullRange() {
        PatternMatcher matcher = new PatternMatcher("[a-z]{1,5}",
                PatternMatcher.PATTERN_ADVANCED_GLOB);

        assertNotMatches("", matcher);
        assertMatches("a", matcher);
        assertMatches("zazaz", matcher);
        assertNotMatches("azazaz", matcher);
        assertNotMatches("11111", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesPartialRange() {
        PatternMatcher matcher = new PatternMatcher("[a-z]{3,}",
                PatternMatcher.PATTERN_ADVANCED_GLOB);

        assertNotMatches("", matcher);
        assertMatches("aza", matcher);
        assertMatches("zazaz", matcher);
        assertMatches("azazazazazaz", matcher);
        assertNotMatches("aa", matcher);
    }

    @Test
    public void testAdvancedPatternMatchesComplexPatterns() {
        PatternMatcher matcher = new PatternMatcher(
                "/[0-9]{4}/[0-9]{2}/[0-9]{2}/[a-zA-Z0-9_]+\\.html",
                PatternMatcher.PATTERN_ADVANCED_GLOB);

        assertNotMatches("", matcher);
        assertMatches("/2016/09/07/got_this_working.html", matcher);
        assertMatches("/2016/09/07/got_this_working2.html", matcher);
        assertNotMatches("/2016/09/07/got_this_working2dothtml", matcher);
        assertNotMatches("/2016/9/7/got_this_working.html", matcher);

        matcher = new PatternMatcher(
                "/b*a*bar.*",
                PatternMatcher.PATTERN_ADVANCED_GLOB);

        assertMatches("/babar", matcher);
        assertMatches("/babarfff", matcher);
        assertMatches("/bbaabarfff", matcher);
        assertMatches("/babar?blah", matcher);
        assertMatches("/baaaabar?blah", matcher);
        assertNotMatches("?bar", matcher);
        assertNotMatches("/bar", matcher);
        assertNotMatches("/baz", matcher);
        assertNotMatches("/ba/bar", matcher);
        assertNotMatches("/barf", matcher);
        assertNotMatches("/", matcher);
        assertNotMatches("?blah", matcher);
    }

    @Test
    public void testAdvancedPatternPoorFormatThrowsIllegalArgumentException() {
        assertPoorlyFormattedPattern("[a-z");
        assertPoorlyFormattedPattern("a{,4}");
        assertPoorlyFormattedPattern("a{0,a}");
        assertPoorlyFormattedPattern("a{\\1, 2}");
        assertPoorlyFormattedPattern("[]");
        assertPoorlyFormattedPattern("a{}");
        assertPoorlyFormattedPattern("{3,4}");
        assertPoorlyFormattedPattern("a+{3,4}");
        assertPoorlyFormattedPattern("*.");
        assertPoorlyFormattedPattern(".+*");
        assertPoorlyFormattedPattern("a{3,4");
        assertPoorlyFormattedPattern("[a");
        assertPoorlyFormattedPattern("abc\\");
        assertPoorlyFormattedPattern("+.");

        StringBuilder charSet = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            charSet.append('a' + (i % 26));
        }
        charSet.append("]");
        assertPoorlyFormattedPattern(charSet.toString());
    }

    private void assertMatches(String string, PatternMatcher matcher) {
        assertTrue("'" + string + "' should match '" + matcher.toString() + "'",
                matcher.match(string));
    }

    private void assertNotMatches(String string, PatternMatcher matcher) {
        assertTrue("'" + string + "' should not match '" + matcher.toString() + "'",
                !matcher.match(string));
    }

    private void assertPoorlyFormattedPattern(String format) {
        try {
            new PatternMatcher(format, PatternMatcher.PATTERN_ADVANCED_GLOB);
        } catch (IllegalArgumentException e) {
            return;// expected
        }

        fail("'" + format + "' was erroneously created");
    }
}
