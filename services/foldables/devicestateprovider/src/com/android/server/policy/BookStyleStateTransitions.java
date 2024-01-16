/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.policy;

import com.android.server.policy.BookStylePreferredScreenCalculator.PreferredScreen;
import com.android.server.policy.BookStylePreferredScreenCalculator.HingeAngle;
import com.android.server.policy.BookStylePreferredScreenCalculator.StateTransition;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes all possible state transitions for {@link BookStylePreferredScreenCalculator}.
 * It contains a default configuration for a foldable device that has two screens: smaller outer
 * screen which has portrait natural orientation and a larger inner screen and allows to use the
 * device in tent mode or wedge mode.
 *
 * As the output state could affect calculating of the new state, it could potentially cause
 * infinite loop and make the state never settle down. This could be avoided using automated test
 * that checks all possible inputs and asserts that the final state is valid.
 * See sample test for the default transitions in {@link BookStyleClosedStateCalculatorTest}.
 *
 * - Tent mode is defined as a posture when the device is partially opened and placed on the ground
 *   on the edges that are parallel to the hinge.
 * - Wedge mode is when the device is partially opened and placed flat on the ground with the part
 *   of the device that doesn't have the display
 * - Reverse wedge mode is when the device is partially opened and placed flat on the ground with
 *   the outer screen down, so the outer screen is not accessible
 *
 * Behavior description:
 * - When unfolding with screens off we assume that no sensor data available except hinge angle
 *   (based on hall sensor), so we switch to the inner screen immediately
 *
 * - When unfolding when screen is 'on' we can check if we are likely in tent or wedge mode
 *   - If not likely tent/wedge mode or sensors data not available, then we unfold immediately
 *     After unfolding, the state of the inner screen 'on' is sticky between 0 and 45 degrees, so
 *     it won't jump back to the outer screen even if you move the phone into tent/wedge mode. The
 *     stickiness is reset after fully closing the device or unfolding past 45 degrees.
 *   - If likely tent or wedge mode, switch only at 90 degrees
 *     Tent/wedge mode is 'sticky' between 0 and 90 degrees, so it won't reset until you either
 *     fully close the device or unfold past 90 degrees.
 *
 * - When folding we can check if we are likely in reverse wedge mode
 *   - If not likely in reverse wedge mode or sensor data is not available we switch to the outer
 *     screen at 45 degrees and enable sticky tent/wedge mode as before, this allows to enter
 *     tent/wedge mode even if you are not on an even surface or holding phone in landscape
 *   - If likely in reverse wedge mode, switch to the outer screen only at 0 degrees to allow
 *     some use cases like using camera in this posture, the check happens after passing 45 degrees
 *     and inner screen becomes sticky turned 'on' until fully closing or unfolding past 45 degrees
 */
public class BookStyleStateTransitions {

    public static final List<StateTransition> DEFAULT_STATE_TRANSITIONS = new ArrayList<>();

    static {
        // region Angle 0
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ true
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ false,
                /* setStickyKeepInnerUntil45Degrees */ true
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ true
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ false,
                /* setStickyKeepInnerUntil45Degrees */ true
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ false
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ false,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ false
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        // endregion

        // region Angle 0-45
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ true,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ true
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ true,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ true
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_0_TO_45,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        // endregion

        // region Angle 45-90
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ false
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ true
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ false
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ true
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.OUTER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_45_TO_90,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        // endregion

        // region Angle 90-180
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ false
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ false,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ false
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ false,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ false,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ false
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ false,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ false,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ false,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ false
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ false,
                PreferredScreen.INNER,
                /* setStickyKeepOuterUntil90Degrees */ false,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        DEFAULT_STATE_TRANSITIONS.add(new StateTransition(
                HingeAngle.ANGLE_90_TO_180,
                /* likelyTentOrWedge */ true,
                /* likelyReverseWedge */ true,
                /* stickyKeepOuterUntil90Degrees */ true,
                /* stickyKeepInnerUntil45Degrees */ true,
                PreferredScreen.INVALID,
                /* setStickyKeepOuterUntil90Degrees */ null,
                /* setStickyKeepInnerUntil45Degrees */ null
        ));
        // endregion
    }
}
