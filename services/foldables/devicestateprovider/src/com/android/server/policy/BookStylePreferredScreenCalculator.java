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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.util.Dumpable;
import android.util.Slog;


import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

/**
 * Calculates if we should use outer or inner display on foldable devices based on a several
 * inputs like device orientation, hinge angle signals.
 *
 * This is a stateful class and acts like a state machine with fixed number of states
 * and transitions. It allows to list all possible state transitions instead of performing
 * imperative logic to make sure that we cover all scenarios and improve debuggability.
 *
 * See {@link BookStyleStateTransitions} for detailed description of the default behavior.
 */
public class BookStylePreferredScreenCalculator implements Dumpable {

    private static final String TAG = "BookStylePreferredScreenCalculator";

    // TODO(b/322137477): disable by default on all builds after flag clean-up
    private static final boolean DEBUG = Build.IS_USERDEBUG || Build.IS_ENG;

    /**
     * When calculating the new state we will re-calculate it until it settles down. We re-calculate
     * it because the new state might trigger another state transition and this might happen
     * several times. We don't want to have infinite loops in state calculation, so this value
     * limits the number of such state transitions.
     * For example, in the default configuration {@link BookStyleStateTransitions}, after each
     * transition with 'set sticky flag' output it will perform a transition to a state without
     * 'set sticky flag' output.
     * We also have a unit test covering all possible states which checks that we don't have such
     * states that could end up in an infinite transition. See sample test for the default
     * transitions in {@link BookStyleClosedStateCalculatorTest}.
     */
    private static final int MAX_STATE_CHANGES = 16;

    private State mState = new State(
            /* stickyKeepOuterUntil90Degrees= */ false,
            /* stickyKeepInnerUntil45Degrees= */ false,
            PreferredScreen.INVALID);

    private final List<StateTransition> mStateTransitions;

    /**
     * Creates BookStyleClosedStateCalculator
     * @param stateTransitions list of all state transitions
     */
    public BookStylePreferredScreenCalculator(List<StateTransition> stateTransitions) {
        mStateTransitions = stateTransitions;
    }

    /**
     * Calculates updated {@link PreferredScreen} based on the current inputs and the current state.
     * The calculation is done based on defined {@link StateTransition}s, it might perform
     * multiple transitions until we settle down on a single state. Multiple transitions could be
     * performed in case if {@link StateTransition} causes another update of the state.
     * There is a limit of maximum {@link MAX_STATE_CHANGES} state transitions, after which
     * this method will throw an {@link IllegalStateException}.
     *
     * @param angle current hinge angle
     * @param likelyTentOrWedge true if the device is likely in tent or wedge mode
     * @param likelyReverseWedge true if the device is likely in reverse wedge mode
     * @return updated {@link PreferredScreen}
     */
    public PreferredScreen calculatePreferredScreen(HingeAngle angle, boolean likelyTentOrWedge,
            boolean likelyReverseWedge) {

        final State oldState = mState;

        int attempts = 0;
        State newState = calculateNewState(mState, angle, likelyTentOrWedge, likelyReverseWedge);
        while (attempts < MAX_STATE_CHANGES && !Objects.equals(mState, newState)) {
            mState = newState;
            newState = calculateNewState(mState, angle, likelyTentOrWedge, likelyReverseWedge);
            attempts++;
        }

        if (attempts >= MAX_STATE_CHANGES) {
            throw new IllegalStateException(
                    "Can't settle state " + mState + ", inputs: hingeAngle = " + angle
                            + ", likelyTentOrWedge = " + likelyTentOrWedge
                            + ", likelyReverseWedge = " + likelyReverseWedge);
        }

        mState = newState;

        if (mState.mPreferredScreen == PreferredScreen.INVALID) {
            throw new IllegalStateException(
                    "Reached invalid state " + mState + ", inputs: hingeAngle = " + angle
                            + ", likelyTentOrWedge = " + likelyTentOrWedge
                            + ", likelyReverseWedge = " + likelyReverseWedge + ", old state: "
                            + oldState);
        }

        if (DEBUG && !Objects.equals(oldState, newState)) {
            Slog.d(TAG, "Moving to state " + mState
                    + " (hingeAngle = " + angle
                    + ", likelyTentOrWedge = " + likelyTentOrWedge
                    + ", likelyReverseWedge = " + likelyReverseWedge + ")");
        }

        return mState.mPreferredScreen;
    }

    /**
     * Returns the current state of the calculator
     */
    public State getState() {
        return mState;
    }

    private State calculateNewState(State current, HingeAngle hingeAngle, boolean likelyTentOrWedge,
            boolean likelyReverseWedge) {
        for (int i = 0; i < mStateTransitions.size(); i++) {
            final State newState = mStateTransitions.get(i).tryTransition(hingeAngle,
                    likelyTentOrWedge, likelyReverseWedge, current);
            if (newState != null) {
                return newState;
            }
        }

        throw new IllegalArgumentException(
                "Entry not found for state: " + current + ", hingeAngle = " + hingeAngle
                        + ", likelyTentOrWedge = " + likelyTentOrWedge + ", likelyReverseWedge = "
                        + likelyReverseWedge);
    }

    @Override
    public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
        writer.println("    " + getDumpableName());
        writer.println("      mState = " + mState);
    }

    @NonNull
    @Override
    public String getDumpableName() {
        return TAG;
    }

    /**
     * The angle between two halves of the foldable device in degrees. The angle is '0' when
     * the device is fully closed and '180' when the device is fully open and flat.
     */
    public enum HingeAngle {
        ANGLE_0,
        ANGLE_0_TO_45,
        ANGLE_45_TO_90,
        ANGLE_90_TO_180
    }

    /**
     * Resulting closed state of the device, where OPEN state indicates that the device should use
     * the inner display and CLOSED means that it should use the outer (cover) screen.
     */
    public enum PreferredScreen {
        INNER,
        OUTER,
        INVALID
    }

    /**
     * Describes a state transition for the posture based active screen calculator
     */
    public static class StateTransition {
        private final Input mInput;
        private final State mOutput;

        public StateTransition(HingeAngle hingeAngle, boolean likelyTentOrWedge,
                boolean likelyReverseWedge,
                boolean stickyKeepOuterUntil90Degrees, boolean stickyKeepInnerUntil45Degrees,
                PreferredScreen preferredScreen, Boolean setStickyKeepOuterUntil90Degrees,
                Boolean setStickyKeepInnerUntil45Degrees) {
            mInput = new Input(hingeAngle, likelyTentOrWedge, likelyReverseWedge,
                    stickyKeepOuterUntil90Degrees, stickyKeepInnerUntil45Degrees);
            mOutput = new State(setStickyKeepOuterUntil90Degrees,
                    setStickyKeepInnerUntil45Degrees, preferredScreen);
        }

        /**
         * Returns true if the state transition is applicable for the given inputs
         */
        private boolean isApplicable(HingeAngle hingeAngle, boolean likelyTentOrWedge,
                boolean likelyReverseWedge, State currentState) {
            return mInput.hingeAngle == hingeAngle
                    && mInput.likelyTentOrWedge == likelyTentOrWedge
                    && mInput.likelyReverseWedge == likelyReverseWedge
                    && Objects.equals(mInput.stickyKeepOuterUntil90Degrees,
                    currentState.stickyKeepOuterUntil90Degrees)
                    && Objects.equals(mInput.stickyKeepInnerUntil45Degrees,
                    currentState.stickyKeepInnerUntil45Degrees);
        }

        /**
         * Try to perform transition for the inputs, returns new state if this
         * transition is applicable for the given state and inputs
         */
        @Nullable
        State tryTransition(HingeAngle hingeAngle, boolean likelyTentOrWedge,
                boolean likelyReverseWedge, State currentState) {
            if (!isApplicable(hingeAngle, likelyTentOrWedge, likelyReverseWedge, currentState)) {
                return null;
            }

            boolean stickyKeepOuterUntil90Degrees = currentState.stickyKeepOuterUntil90Degrees;
            boolean stickyKeepInnerUntil45Degrees = currentState.stickyKeepInnerUntil45Degrees;

            if (mOutput.stickyKeepOuterUntil90Degrees != null) {
                stickyKeepOuterUntil90Degrees =
                        mOutput.stickyKeepOuterUntil90Degrees;
            }

            if (mOutput.stickyKeepInnerUntil45Degrees != null) {
                stickyKeepInnerUntil45Degrees =
                        mOutput.stickyKeepInnerUntil45Degrees;
            }

            return new State(stickyKeepOuterUntil90Degrees, stickyKeepInnerUntil45Degrees,
                    mOutput.mPreferredScreen);
        }
    }

    /**
     * The input part of the {@link StateTransition}, these are the values that are used
     * to decide which {@link State} output to choose.
     */
    private static class Input {
        final HingeAngle hingeAngle;
        final boolean likelyTentOrWedge;
        final boolean likelyReverseWedge;
        final boolean stickyKeepOuterUntil90Degrees;
        final boolean stickyKeepInnerUntil45Degrees;

        public Input(HingeAngle hingeAngle, boolean likelyTentOrWedge,
                boolean likelyReverseWedge,
                boolean stickyKeepOuterUntil90Degrees, boolean stickyKeepInnerUntil45Degrees) {
            this.hingeAngle = hingeAngle;
            this.likelyTentOrWedge = likelyTentOrWedge;
            this.likelyReverseWedge = likelyReverseWedge;
            this.stickyKeepOuterUntil90Degrees = stickyKeepOuterUntil90Degrees;
            this.stickyKeepInnerUntil45Degrees = stickyKeepInnerUntil45Degrees;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Input)) return false;
            Input that = (Input) o;
            return likelyTentOrWedge == that.likelyTentOrWedge
                    && likelyReverseWedge == that.likelyReverseWedge
                    && stickyKeepOuterUntil90Degrees == that.stickyKeepOuterUntil90Degrees
                    && stickyKeepInnerUntil45Degrees == that.stickyKeepInnerUntil45Degrees
                    && hingeAngle == that.hingeAngle;
        }

        @Override
        public int hashCode() {
            return Objects.hash(hingeAngle, likelyTentOrWedge, likelyReverseWedge,
                    stickyKeepOuterUntil90Degrees, stickyKeepInnerUntil45Degrees);
        }

        @Override
        public String toString() {
            return "InputState{" +
                    "hingeAngle=" + hingeAngle +
                    ", likelyTentOrWedge=" + likelyTentOrWedge +
                    ", likelyReverseWedge=" + likelyReverseWedge +
                    ", stickyKeepOuterUntil90Degrees=" + stickyKeepOuterUntil90Degrees +
                    ", stickyKeepInnerUntil45Degrees=" + stickyKeepInnerUntil45Degrees +
                    '}';
        }
    }

    /**
     * Class that holds a state of the calculator, it could be used to store the current
     * state or to define the target (output) state based on some input in {@link StateTransition}.
     */
    public static class State {
        public Boolean stickyKeepOuterUntil90Degrees;
        public Boolean stickyKeepInnerUntil45Degrees;

        PreferredScreen mPreferredScreen;

        public State(Boolean stickyKeepOuterUntil90Degrees,
                Boolean stickyKeepInnerUntil45Degrees,
                PreferredScreen preferredScreen) {
            this.stickyKeepOuterUntil90Degrees = stickyKeepOuterUntil90Degrees;
            this.stickyKeepInnerUntil45Degrees = stickyKeepInnerUntil45Degrees;
            this.mPreferredScreen = preferredScreen;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State that = (State) o;
            return Objects.equals(stickyKeepOuterUntil90Degrees,
                    that.stickyKeepOuterUntil90Degrees) && Objects.equals(
                    stickyKeepInnerUntil45Degrees, that.stickyKeepInnerUntil45Degrees)
                    && mPreferredScreen == that.mPreferredScreen;
        }

        @Override
        public int hashCode() {
            return Objects.hash(stickyKeepOuterUntil90Degrees, stickyKeepInnerUntil45Degrees,
                    mPreferredScreen);
        }

        @Override
        public String toString() {
            return "State{" +
                    "stickyKeepOuterUntil90Degrees=" + stickyKeepOuterUntil90Degrees +
                    ", stickyKeepInnerUntil90Degrees=" + stickyKeepInnerUntil45Degrees +
                    ", closedState=" + mPreferredScreen +
                    '}';
        }
    }
}
