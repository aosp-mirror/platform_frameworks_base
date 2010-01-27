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

package android.util;

import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests for {@link StateSet}
 */

public class StateSetTest extends TestCase {
    
    @SmallTest
    public void testStateSetPositiveMatches() throws Exception {
         int[] stateSpec = new int[2];
         int[] stateSet = new int[3];
         // Single states in both sets - match
         stateSpec[0] = 1;
         stateSet[0] = 1;
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
         // Single states in both sets - non-match
         stateSet[0] = 2;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         // Add another state to the spec which the stateSet doesn't match
         stateSpec[1] = 2;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         // Add the missing matching element to the stateSet
         stateSet[1] = 1;
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
         // Add an irrelevent state to the stateSpec
         stateSet[2] = 12345;
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
     }

     @SmallTest
     public void testStatesSetMatchMixEmUp() throws Exception {
         int[] stateSpec = new int[2];
         int[] stateSet = new int[2];
         // One element in stateSpec which we must match and one which we must
         // not match.  stateSet only contains the match.
         stateSpec[0] = 1;
         stateSpec[1] = -2;
         stateSet[0] = 1;
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
         // stateSet now contains just the element we must not match
         stateSet[0] = 2;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         // Add another matching state to the the stateSet.  We still fail
         // because stateSet contains a must-not-match element
         stateSet[1] = 1;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         // Switch the must-not-match element in stateSet with a don't care
         stateSet[0] = 12345;
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
     }

     @SmallTest
     public void testStateSetNegativeMatches() throws Exception {
         int[] stateSpec = new int[2];
         int[] stateSet = new int[3];
         // Single states in both sets - match
         stateSpec[0] = -1;
         stateSet[0] = 2;
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
         // Add another arrelevent state to the stateSet
         stateSet[1] = 12345;
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
         // Single states in both sets - non-match
         stateSet[0] = 1;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         // Add another state to the spec which the stateSet doesn't match
         stateSpec[1] = -2;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         // Add an irrelevent state to the stateSet
         stateSet[2] = 12345;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
     }

     @SmallTest
     public void testEmptySetMatchesNegtives() throws Exception {
         int[] stateSpec = {-12345, -6789};
         int[] stateSet = new int[0];
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
         int[] stateSet2 = {0};
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet2));
     }

     @SmallTest
     public void testEmptySetFailsPositives() throws Exception {
         int[] stateSpec = {12345};
         int[] stateSet = new int[0];
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         int[] stateSet2 = {0};
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet2));
     }

     @SmallTest
     public void testEmptySetMatchesWildcard() throws Exception {
         int[] stateSpec = StateSet.WILD_CARD;
         int[] stateSet = new int[0];
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
         int[] stateSet2 = {0};
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet2));
     }

     @SmallTest
     public void testSingleStatePositiveMatches() throws Exception {
         int[] stateSpec = new int[2];
         int state;
         //  match
         stateSpec[0] = 1;
         state = 1;
         assertTrue(StateSet.stateSetMatches(stateSpec, state));
         // non-match
         state = 2;
         assertFalse(StateSet.stateSetMatches(stateSpec, state));
         // add irrelevant must-not-match
         stateSpec[1] = -12345;
         assertFalse(StateSet.stateSetMatches(stateSpec, state));
     }

     @SmallTest
     public void testSingleStateNegativeMatches() throws Exception {
         int[] stateSpec = new int[2];
         int state;
         //  match
         stateSpec[0] = -1;
         state = 1;
         assertFalse(StateSet.stateSetMatches(stateSpec, state));
         // non-match
         state = 2;
         assertTrue(StateSet.stateSetMatches(stateSpec, state));
         // add irrelevant must-not-match
         stateSpec[1] = -12345;
         assertTrue(StateSet.stateSetMatches(stateSpec, state));
     }

     @SmallTest
     public void testZeroStateOnlyMatchesDefault() throws Exception {
         int[] stateSpec = new int[3];
         int state = 0;
         //  non-match
         stateSpec[0] = 1;
         assertFalse(StateSet.stateSetMatches(stateSpec, state));
         // non-match
         stateSpec[1] = -1;
         assertFalse(StateSet.stateSetMatches(stateSpec, state));
         // match
         stateSpec = StateSet.WILD_CARD;
         assertTrue(StateSet.stateSetMatches(stateSpec, state));
     }

     @SmallTest
     public void testNullStateOnlyMatchesDefault() throws Exception {
         int[] stateSpec = new int[3];
         int[] stateSet = null;
         //  non-match
         stateSpec[0] = 1;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         // non-match
         stateSpec[1] = -1;
         assertFalse(StateSet.stateSetMatches(stateSpec, stateSet));
         // match
         stateSpec = StateSet.WILD_CARD;
         assertTrue(StateSet.stateSetMatches(stateSpec, stateSet));
     }
}
