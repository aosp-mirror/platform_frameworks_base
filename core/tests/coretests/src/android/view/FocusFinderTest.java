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

package android.view;

import android.graphics.Rect;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class FocusFinderTest extends AndroidTestCase {

    private FocusFinderHelper mFocusFinder;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mFocusFinder = new FocusFinderHelper(FocusFinder.getInstance());
    }

    @SmallTest
    public void testPreconditions() {
        assertNotNull("focus finder instance", mFocusFinder);
    }

    @SmallTest
    public void testBelowNotCandidateForDirectionUp() {
        assertIsNotCandidate(View.FOCUS_UP,
                new Rect(0, 30, 10, 40),  // src  (left, top, right, bottom)
                new Rect(0, 50, 10, 60));  // dest (left, top, right, bottom)
    }

    @SmallTest
    public void testAboveShareEdgeEdgeOkForDirectionUp() {
        final Rect src = new Rect(0, 30, 10, 40);

        final Rect dest = new Rect(src);
        dest.offset(0, -src.height());
        assertEquals(src.top, dest.bottom);

        assertDirectionIsCandidate(View.FOCUS_UP, src, dest);
    }

    @SmallTest
    public void testCompletelyContainedNotCandidate() {
        assertIsNotCandidate(
                View.FOCUS_DOWN,
                //       L  T   R   B
                new Rect(0, 0,  50, 50),
                new Rect(0, 1,  50, 49));
    }

    @SmallTest
    public void testContinaedWithCommonBottomNotCandidate() {
        assertIsNotCandidate(
                View.FOCUS_DOWN,
                //       L  T   R   B
                new Rect(0, 0,  50, 50),
                new Rect(0, 1,  50, 50));
    }

    @SmallTest
    public void testOverlappingIsCandidateWhenBothEdgesAreInDirection() {
        assertDirectionIsCandidate(
                View.FOCUS_DOWN,
                //       L  T   R   B
                new Rect(0, 0,  50, 50),
                new Rect(0, 1,  50, 51));
    }

    @SmallTest
    public void testTopEdgeOfDestAtOrAboveTopOfSrcNotCandidateForDown() {
        assertIsNotCandidate(
                View.FOCUS_DOWN,
                //       L  T   R   B
                new Rect(0, 0,  50, 50),
                new Rect(0, 0,  50, 51));
        assertIsNotCandidate(
                View.FOCUS_DOWN,
                //       L  T   R   B
                new Rect(0, 0,  50, 50),
                new Rect(0, -1, 50, 51));
    }

    @SmallTest
    public void testSameRectBeamsOverlap() {
        final Rect rect = new Rect(0, 0, 20, 20);

        assertBeamsOverlap(View.FOCUS_LEFT, rect, rect);
        assertBeamsOverlap(View.FOCUS_RIGHT, rect, rect);
        assertBeamsOverlap(View.FOCUS_UP, rect, rect);
        assertBeamsOverlap(View.FOCUS_DOWN, rect, rect);
    }

    @SmallTest
    public void testOverlapBeamsRightLeftUpToEdge() {
        final Rect rect1 = new Rect(0, 0, 20, 20);
        final Rect rect2 = new Rect(rect1);

        // just below bottom edge
        rect2.offset(0, rect1.height() - 1);
        assertBeamsOverlap(View.FOCUS_LEFT, rect1, rect2);
        assertBeamsOverlap(View.FOCUS_RIGHT, rect1, rect2);

        // at edge
        rect2.offset(0, 1);
        assertBeamsDontOverlap(View.FOCUS_LEFT, rect1, rect2);
        assertBeamsDontOverlap(View.FOCUS_RIGHT, rect1, rect2);

        // just beyond
        rect2.offset(0, 1);
        assertBeamsDontOverlap(View.FOCUS_LEFT, rect1, rect2);
        assertBeamsDontOverlap(View.FOCUS_RIGHT, rect1, rect2);

        // just below top edge
        rect2.set(rect1);
        rect2.offset(0, -(rect1.height() - 1));
        assertBeamsOverlap(View.FOCUS_LEFT, rect1, rect2);
        assertBeamsOverlap(View.FOCUS_RIGHT, rect1, rect2);

        // at top edge
        rect2.offset(0, -1);
        assertBeamsDontOverlap(View.FOCUS_LEFT, rect1, rect2);
        assertBeamsDontOverlap(View.FOCUS_RIGHT, rect1, rect2);

        // just beyond top edge
        rect2.offset(0, -1);
        assertBeamsDontOverlap(View.FOCUS_LEFT, rect1, rect2);
        assertBeamsDontOverlap(View.FOCUS_RIGHT, rect1, rect2);
    }

    @SmallTest
    public void testOverlapBeamsUpDownUpToEdge() {
        final Rect rect1 = new Rect(0, 0, 20, 20);
        final Rect rect2 = new Rect(rect1);

        // just short of right edge
        rect2.offset(rect1.width() - 1, 0);
        assertBeamsOverlap(View.FOCUS_UP, rect1, rect2);
        assertBeamsOverlap(View.FOCUS_DOWN, rect1, rect2);

        // at edge
        rect2.offset(1, 0);
        assertBeamsDontOverlap(View.FOCUS_UP, rect1, rect2);
        assertBeamsDontOverlap(View.FOCUS_DOWN, rect1, rect2);

        // just beyond
        rect2.offset(1, 0);
        assertBeamsDontOverlap(View.FOCUS_UP, rect1, rect2);
        assertBeamsDontOverlap(View.FOCUS_DOWN, rect1, rect2);

        // just short of left edge
        rect2.set(rect1);
        rect2.offset(-(rect1.width() - 1), 0);
        assertBeamsOverlap(View.FOCUS_UP, rect1, rect2);
        assertBeamsOverlap(View.FOCUS_DOWN, rect1, rect2);

        // at edge
        rect2.offset(-1, 0);
        assertBeamsDontOverlap(View.FOCUS_UP, rect1, rect2);
        assertBeamsDontOverlap(View.FOCUS_DOWN, rect1, rect2);

        // just beyond edge
        rect2.offset(-1, 0);
        assertBeamsDontOverlap(View.FOCUS_UP, rect1, rect2);
        assertBeamsDontOverlap(View.FOCUS_DOWN, rect1, rect2);
    }

    @SmallTest
    public void testDirectlyAboveTrumpsAboveLeft() {
        Rect src = new Rect(0, 50, 20, 70);  // src (left, top, right, bottom)

        Rect directlyAbove = new Rect(src);
        directlyAbove.offset(0, -(1 + src.height()));

        Rect aboveLeft = new Rect(src);
        aboveLeft.offset(-(1 + src.width()), -(1 + src.height()));

        assertBetterCandidate(View.FOCUS_UP, src, directlyAbove, aboveLeft);
    }

    @SmallTest
    public void testAboveInBeamTrumpsSlightlyCloserOutOfBeam() {
        Rect src = new Rect(0, 50, 20, 70);  // src (left, top, right, bottom)

        Rect directlyAbove = new Rect(src);
        directlyAbove.offset(0, -(1 + src.height()));

        Rect aboveLeft = new Rect(src);
        aboveLeft.offset(-(1 + src.width()), -(1 + src.height()));

        // offset directly above a little further up
        directlyAbove.offset(0, -5);
        assertBetterCandidate(View.FOCUS_UP, src, directlyAbove, aboveLeft);
    }

    @SmallTest
    public void testOutOfBeamBeatsInBeamUp() {

        Rect src = new Rect(0, 0, 50, 50); // (left, top, right, bottom)

        Rect aboveLeftOfBeam = new Rect(src);
        aboveLeftOfBeam.offset(-(src.width() + 1), -src.height());
        assertBeamsDontOverlap(View.FOCUS_UP, src, aboveLeftOfBeam);

        Rect aboveInBeam = new Rect(src);
        aboveInBeam.offset(0, -src.height());
        assertBeamsOverlap(View.FOCUS_UP, src, aboveInBeam);

        // in beam wins
        assertBetterCandidate(View.FOCUS_UP, src, aboveInBeam, aboveLeftOfBeam);

        // still wins while aboveInBeam's bottom edge is < out of beams' top
        aboveInBeam.offset(0, -(aboveLeftOfBeam.height() - 1));
        assertTrue("aboveInBeam.bottom > aboveLeftOfBeam.top", aboveInBeam.bottom > aboveLeftOfBeam.top);
        assertBetterCandidate(View.FOCUS_UP, src, aboveInBeam, aboveLeftOfBeam);

        // cross the threshold: the out of beam prevails
        aboveInBeam.offset(0, -1);
        assertEquals(aboveInBeam.bottom, aboveLeftOfBeam.top);
        assertBetterCandidate(View.FOCUS_UP, src, aboveLeftOfBeam, aboveInBeam);
    }

    /**
     * A non-candidate (even a much closer one) is always a worse choice
     * than a real candidate.
     */
    @SmallTest
    public void testSomeCandidateBetterThanNonCandidate() {
        Rect src = new Rect(0, 0, 50, 50); // (left, top, right, bottom)

        Rect nonCandidate = new Rect(src);
        nonCandidate.offset(src.width() + 1, 0);

        assertIsNotCandidate(View.FOCUS_LEFT, src, nonCandidate);

        Rect candidate = new Rect(src);
        candidate.offset(-(4 * src.width()), 0);
        assertDirectionIsCandidate(View.FOCUS_LEFT, src, candidate);

        assertBetterCandidate(View.FOCUS_LEFT, src, candidate, nonCandidate);
    }

    /**
     * Grabbed from {@link android.widget.focus.VerticalFocusSearchTest#testSearchFromMidLeft()}
     */
    @SmallTest
    public void testVerticalFocusSearchScenario() {
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L    T    R    B
                new Rect(0,   109, 153, 169),   // src
                new Rect(166, 169, 319, 229),  // expectedbetter
                new Rect(0,   229, 320, 289)); // expectedworse

        // failing test 4/10/2008, the values were tweaked somehow in functional
        // test...
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L    T    R    B
                new Rect(0,   91, 153, 133),   // src
                new Rect(166, 133, 319, 175),  // expectedbetter
                new Rect(0,   175, 320, 217)); // expectedworse

    }

    /**
     * Example: going down from a thin button all the way to the left of a
     * screen where, just below, is a very wide button, and just below that,
     * is an equally skinny button all the way to the left.  want to make
     * sure any minor axis factor doesn't override the fact that the one below
     * in vertical beam should be next focus
     */
    @SmallTest
    public void testBeamsOverlapMajorAxisCloserMinorAxisFurther() {
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L   T    R    B
                new Rect(0,  0,   100,  100),  // src
                new Rect(0,  100, 480,  200),  // expectedbetter
                new Rect(0,  200, 100,  300)); // expectedworse
    }

    /**
     * Real scenario grabbed from song playback screen.
     */
    @SmallTest
    public void testMusicPlaybackScenario() {
        assertBetterCandidate(View.FOCUS_LEFT,
                //       L    T    R    B
                new Rect(227, 185, 312, 231),   // src
                new Rect(195, 386, 266, 438),   // expectedbetter
                new Rect(124, 386, 195, 438));  // expectedworse
    }

    /**
     * more generalized version of {@link #testMusicPlaybackScenario()}
     */
    @SmallTest
    public void testOutOfBeamOverlapBeatsOutOfBeamFurtherOnMajorAxis() {
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L    T    R    B
                new Rect(0,   0,   50,  50),   // src
                new Rect(60,  40,  110, 90),   // expectedbetter
                new Rect(60,  70,  110, 120));  // expectedworse
    }

    /**
     * Make sure that going down prefers views that are actually
     * down (and not those next to but still a candidate because
     * they are overlapping on the major axis)
     */
    @SmallTest
    public void testInBeamTrumpsOutOfBeamOverlapping() {
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L    T    R    B
                new Rect(0,   0,   50,  50),   // src
                new Rect(0,   60,  50,  110),  // expectedbetter
                new Rect(51,  1,   101, 51)); // expectedworse
    }

    @SmallTest
    public void testOverlappingBeatsNonOverlapping() {
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L    T    R    B
                new Rect(0,   0,   50,  50),   // src
                new Rect(0,   40,  50,  90),   // expectedbetter
                new Rect(0,   75,  50,  125)); // expectedworse
    }

    @SmallTest
    public void testEditContactScenarioLeftFromDiscardChangesGoesToSaveContactInLandscape() {
        assertBetterCandidate(View.FOCUS_LEFT,
                //       L    T    R    B
                new Rect(357, 258, 478, 318),  // src
                new Rect(2,   258, 100, 318),  // better
                new Rect(106, 120, 424, 184)); // worse
    }

    /**
     * A dial pad with 9 squares arranged in a grid.  no padding, so
     * the edges are equal.  see {@link android.widget.focus.LinearLayoutGrid}
     */
    @SmallTest
    public void testGridWithTouchingEdges() {
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L    T    R    B
                new Rect(106, 49,  212, 192),  // src
                new Rect(106, 192, 212, 335),  // better
                new Rect(0,   192, 106, 335)); // worse

        assertBetterCandidate(View.FOCUS_DOWN,
                //       L    T    R    B
                new Rect(106, 49,  212, 192),  // src
                new Rect(106, 192, 212, 335),  // better
                new Rect(212, 192, 318, 335)); // worse
    }

    @SmallTest
    public void testSearchFromEmptyRect() {
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L   T    R    B
                new Rect(0,  0,   0,   0),    // src
                new Rect(0,  0,   320, 45),   // better
                new Rect(0,  45,  320, 545)); // worse
    }

    /**
     * Reproduce bug 1124559, drilling down to actual bug
     * (majorAxisDistance was wrong for direction left)
     */
    @SmallTest
    public void testGmailReplyButtonsScenario() {
        assertBetterCandidate(View.FOCUS_LEFT,
                //       L    T    R    B
                new Rect(223, 380, 312, 417),  // src
                new Rect(102, 380, 210, 417),  // better
                new Rect(111, 443, 206, 480)); // worse

        assertBeamBeats(View.FOCUS_LEFT,
            //       L    T    R    B
            new Rect(223, 380, 312, 417),  // src
            new Rect(102, 380, 210, 417),  // better
            new Rect(111, 443, 206, 480)); // worse

        assertBeamsOverlap(View.FOCUS_LEFT,
                //       L    T    R    B
                new Rect(223, 380, 312, 417),
                new Rect(102, 380, 210, 417));

        assertBeamsDontOverlap(View.FOCUS_LEFT,
                //       L    T    R    B
                new Rect(223, 380, 312, 417),
                new Rect(111, 443, 206, 480));

        assertTrue(
                "major axis distance less than major axis distance to "
                        + "far edge",
                FocusFinderHelper.majorAxisDistance(View.FOCUS_LEFT,
                        //       L    T    R    B
                        new Rect(223, 380, 312, 417),
                        new Rect(102, 380, 210, 417)) <
                FocusFinderHelper.majorAxisDistanceToFarEdge(View.FOCUS_LEFT,
                        //       L    T    R    B
                        new Rect(223, 380, 312, 417),
                        new Rect(111, 443, 206, 480)));
    }

    @SmallTest
    public void testGmailScenarioBug1203288() {
        assertBetterCandidate(View.FOCUS_DOWN,
                //       L    T    R    B
                new Rect(0,   2,   480, 82),   // src
                new Rect(344, 87,  475, 124),  // better
                new Rect(0,   130, 480, 203)); // worse
    }

    @SmallTest
    public void testHomeShortcutScenarioBug1295354() {
        assertBetterCandidate(View.FOCUS_RIGHT,
                //       L    T    R    B
                new Rect(3, 338, 77, 413),   // src
                new Rect(163, 338, 237, 413),  // better
                new Rect(83, 38, 157, 113)); // worse
    }

    @SmallTest
    public void testBeamAlwaysBeatsHoriz() {
        assertBetterCandidate(View.FOCUS_RIGHT,
                //       L    T    R    B
                new Rect(0,   0,   50,  50),   // src
                new Rect(150, 0,   200, 50),   // better, (way further, but in beam)
                new Rect(60,  51,  110, 101)); // worse, even though it is closer 

        assertBetterCandidate(View.FOCUS_LEFT,
                //       L    T    R    B
                new Rect(150, 0,   200,  50),   // src
                new Rect(0,   0,   50,   50),   // better, (way further, but in beam)
                new Rect(49,  99,  149,  101)); // worse, even though it is closer 
    }

    @SmallTest
    public void testIsCandidateOverlappingEdgeFromEmptyRect() {
        assertDirectionIsCandidate(View.FOCUS_DOWN,
                //       L   T    R    B
                new Rect(0,  0,   0,   0),   // src
                new Rect(0,  0,   20,  1));  // candidate

        assertDirectionIsCandidate(View.FOCUS_UP,
                //       L   T    R    B
                new Rect(0,  0,   0,   0),   // src
                new Rect(0,  -1,  20,  0));  // candidate

        assertDirectionIsCandidate(View.FOCUS_LEFT,
                //       L   T    R    B
                new Rect(0,  0,   0,   0),    // src
                new Rect(-1,  0,  0,   20));  // candidate

        assertDirectionIsCandidate(View.FOCUS_RIGHT,
                //       L   T    R    B
                new Rect(0,  0,   0,   0),    // src
                new Rect(0,  0,   1,   20));  // candidate
    }

    private void assertBeamsOverlap(int direction, Rect rect1, Rect rect2) {
        String directionStr = validateAndGetStringFor(direction);
        String assertMsg = String.format("Expected beams to overlap in direction %s "
                + "for rectangles %s and %s", directionStr, rect1, rect2);
        assertTrue(assertMsg, mFocusFinder.beamsOverlap(direction, rect1, rect2));
    }

    private void assertBeamsDontOverlap(int direction, Rect rect1, Rect rect2) {
        String directionStr = validateAndGetStringFor(direction);
        String assertMsg = String.format("Expected beams not to overlap in direction %s "
                + "for rectangles %s and %s", directionStr, rect1, rect2);
        assertFalse(assertMsg, mFocusFinder.beamsOverlap(direction, rect1, rect2));
    }

    /**
     * Assert that particular rect is a better focus search candidate from a
     * source rect than another.
     * @param direction The direction of focus search.
     * @param srcRect The src rectangle.
     * @param expectedBetter The candidate that should be better.
     * @param expectedWorse The candidate that should be worse.
     */
    private void assertBetterCandidate(int direction, Rect srcRect,
            Rect expectedBetter, Rect expectedWorse) {

        String directionStr = validateAndGetStringFor(direction);
        String assertMsg = String.format(
                "expected %s to be a better focus search candidate than "
                        + "%s when searching "
                        + "from %s in direction %s",
                expectedBetter, expectedWorse, srcRect, directionStr);

        assertTrue(assertMsg,
                mFocusFinder.isBetterCandidate(direction, srcRect,
                        expectedBetter, expectedWorse));

        assertMsg = String.format(
                "expected %s to not be a better focus search candidate than "
                        + "%s when searching "
                        + "from %s in direction %s",
                expectedWorse, expectedBetter, srcRect, directionStr);

        assertFalse(assertMsg,
                mFocusFinder.isBetterCandidate(direction, srcRect,
                        expectedWorse, expectedBetter));
    }

    private void assertIsNotCandidate(int direction, Rect src, Rect dest) {
        String directionStr = validateAndGetStringFor(direction);

        final String assertMsg = String.format(
                "expected going from %s to %s in direction %s to be an invalid "
                        + "focus search candidate",
                src, dest, directionStr);
        assertFalse(assertMsg, mFocusFinder.isCandidate(src, dest, direction));
    }

    private void assertBeamBeats(int direction, Rect srcRect,
            Rect rect1, Rect rect2) {

        String directionStr = validateAndGetStringFor(direction);
        String assertMsg = String.format(
                "expecting %s to beam beat %s w.r.t %s in direction %s",
                rect1, rect2, srcRect, directionStr);
        assertTrue(assertMsg, mFocusFinder.beamBeats(direction, srcRect, rect1, rect2));
    }


    private void assertDirectionIsCandidate(int direction, Rect src, Rect dest) {
        String directionStr = validateAndGetStringFor(direction);

        final String assertMsg = String.format(
                "expected going from %s to %s in direction %s to be a valid "
                        + "focus search candidate",
                src, dest, directionStr);
        assertTrue(assertMsg, mFocusFinder.isCandidate(src, dest, direction));
    }

    private String validateAndGetStringFor(int direction) {
        String directionStr = "??";
        switch(direction) {
            case View.FOCUS_UP:
                directionStr = "FOCUS_UP";
                break;
            case View.FOCUS_DOWN:
                directionStr = "FOCUS_DOWN";
                break;
            case View.FOCUS_LEFT:
                directionStr = "FOCUS_LEFT";
                break;
            case View.FOCUS_RIGHT:
                directionStr = "FOCUS_RIGHT";
                break;
            default:
                fail("passed in unknown direction, ya blewit!");
        }
        return directionStr;
    }


}
