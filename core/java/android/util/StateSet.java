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

import com.android.internal.R;

/**
 * State sets are arrays of positive ints where each element
 * represents the state of a {@link android.view.View} (e.g. focused,
 * selected, visible, etc.).  A {@link android.view.View} may be in
 * one or more of those states.
 *
 * A state spec is an array of signed ints where each element
 * represents a required (if positive) or an undesired (if negative)
 * {@link android.view.View} state.
 *
 * Utils dealing with state sets.
 *
 * In theory we could encapsulate the state set and state spec arrays
 * and not have static methods here but there is some concern about
 * performance since these methods are called during view drawing.
 */

public class StateSet {
    /**
     * The order here is very important to
     * {@link android.view.View#getDrawableState()}
     */
    private static final int[][] VIEW_STATE_SETS;

    /** @hide */
    public static final int VIEW_STATE_WINDOW_FOCUSED = 1;
    /** @hide */
    public static final int VIEW_STATE_SELECTED = 1 << 1;
    /** @hide */
    public static final int VIEW_STATE_FOCUSED = 1 << 2;
    /** @hide */
    public static final int VIEW_STATE_ENABLED = 1 << 3;
    /** @hide */
    public static final int VIEW_STATE_PRESSED = 1 << 4;
    /** @hide */
    public static final int VIEW_STATE_ACTIVATED = 1 << 5;
    /** @hide */
    public static final int VIEW_STATE_ACCELERATED = 1 << 6;
    /** @hide */
    public static final int VIEW_STATE_HOVERED = 1 << 7;
    /** @hide */
    public static final int VIEW_STATE_DRAG_CAN_ACCEPT = 1 << 8;
    /** @hide */
    public static final int VIEW_STATE_DRAG_HOVERED = 1 << 9;

    static final int[] VIEW_STATE_IDS = new int[] {
            R.attr.state_window_focused,    VIEW_STATE_WINDOW_FOCUSED,
            R.attr.state_selected,          VIEW_STATE_SELECTED,
            R.attr.state_focused,           VIEW_STATE_FOCUSED,
            R.attr.state_enabled,           VIEW_STATE_ENABLED,
            R.attr.state_pressed,           VIEW_STATE_PRESSED,
            R.attr.state_activated,         VIEW_STATE_ACTIVATED,
            R.attr.state_accelerated,       VIEW_STATE_ACCELERATED,
            R.attr.state_hovered,           VIEW_STATE_HOVERED,
            R.attr.state_drag_can_accept,   VIEW_STATE_DRAG_CAN_ACCEPT,
            R.attr.state_drag_hovered,      VIEW_STATE_DRAG_HOVERED
    };

    static {
        if ((VIEW_STATE_IDS.length / 2) != R.styleable.ViewDrawableStates.length) {
            throw new IllegalStateException(
                    "VIEW_STATE_IDs array length does not match ViewDrawableStates style array");
        }

        final int[] orderedIds = new int[VIEW_STATE_IDS.length];
        for (int i = 0; i < R.styleable.ViewDrawableStates.length; i++) {
            final int viewState = R.styleable.ViewDrawableStates[i];
            for (int j = 0; j < VIEW_STATE_IDS.length; j += 2) {
                if (VIEW_STATE_IDS[j] == viewState) {
                    orderedIds[i * 2] = viewState;
                    orderedIds[i * 2 + 1] = VIEW_STATE_IDS[j + 1];
                }
            }
        }

        final int NUM_BITS = VIEW_STATE_IDS.length / 2;
        VIEW_STATE_SETS = new int[1 << NUM_BITS][];
        for (int i = 0; i < VIEW_STATE_SETS.length; i++) {
            final int numBits = Integer.bitCount(i);
            final int[] set = new int[numBits];
            int pos = 0;
            for (int j = 0; j < orderedIds.length; j += 2) {
                if ((i & orderedIds[j + 1]) != 0) {
                    set[pos++] = orderedIds[j];
                }
            }
            VIEW_STATE_SETS[i] = set;
        }
    }

    /** @hide */
    public static int[] get(int mask) {
        if (mask >= VIEW_STATE_SETS.length) {
            throw new IllegalArgumentException("Invalid state set mask");
        }
        return VIEW_STATE_SETS[mask];
    }

    /** @hide */
    public StateSet() {}

    /**
     * A state specification that will be matched by all StateSets.
     */
    public static final int[] WILD_CARD = new int[0];

    /**
     * A state set that does not contain any valid states.
     */
    public static final int[] NOTHING = new int[] { 0 };

    /**
     * Return whether the stateSetOrSpec is matched by all StateSets.
     *
     * @param stateSetOrSpec a state set or state spec.
     */
    public static boolean isWildCard(int[] stateSetOrSpec) {
        return stateSetOrSpec.length == 0 || stateSetOrSpec[0] == 0;
    }

    /**
     * Return whether the stateSet matches the desired stateSpec.
     *
     * @param stateSpec an array of required (if positive) or
     *        prohibited (if negative) {@link android.view.View} states.
     * @param stateSet an array of {@link android.view.View} states
     */
    public static boolean stateSetMatches(int[] stateSpec, int[] stateSet) {
        if (stateSet == null) {
            return (stateSpec == null || isWildCard(stateSpec));
        }
        int stateSpecSize = stateSpec.length;
        int stateSetSize = stateSet.length;
        for (int i = 0; i < stateSpecSize; i++) {
            int stateSpecState = stateSpec[i];
            if (stateSpecState == 0) {
                // We've reached the end of the cases to match against.
                return true;
            }
            final boolean mustMatch;
            if (stateSpecState > 0) {
                mustMatch = true;
            } else {
                // We use negative values to indicate must-NOT-match states.
                mustMatch = false;
                stateSpecState = -stateSpecState;
            }
            boolean found = false;
            for (int j = 0; j < stateSetSize; j++) {
                final int state = stateSet[j];
                if (state == 0) {
                    // We've reached the end of states to match.
                    if (mustMatch) {
                        // We didn't find this must-match state.
                        return false;
                    } else {
                        // Continue checking other must-not-match states.
                        break;
                    }
                }
                if (state == stateSpecState) {
                    if (mustMatch) {
                        found = true;
                        // Continue checking other other must-match states.
                        break;
                    } else {
                        // Any match of a must-not-match state returns false.
                        return false;
                    }
                }
            }
            if (mustMatch && !found) {
                // We've reached the end of states to match and we didn't
                // find a must-match state.
                return false;
            }
        }
        return true;
    }

    /**
     * Return whether the state matches the desired stateSpec.
     *
     * @param stateSpec an array of required (if positive) or
     *        prohibited (if negative) {@link android.view.View} states.
     * @param state a {@link android.view.View} state
     */
    public static boolean stateSetMatches(int[] stateSpec, int state) {
        int stateSpecSize = stateSpec.length;
        for (int i = 0; i < stateSpecSize; i++) {
            int stateSpecState = stateSpec[i];
            if (stateSpecState == 0) {
                // We've reached the end of the cases to match against.
                return true;
            }
            if (stateSpecState > 0) {
                if (state != stateSpecState) {
                   return false;
                }
            } else {
                // We use negative values to indicate must-NOT-match states.
                if (state == -stateSpecState) {
                    // We matched a must-not-match case.
                    return false;
                }
            }
        }
        return true;
    }

    public static int[] trimStateSet(int[] states, int newSize) {
        if (states.length == newSize) {
            return states;
        }

        int[] trimmedStates = new int[newSize];
        System.arraycopy(states, 0, trimmedStates, 0, newSize);
        return trimmedStates;
    }

    public static String dump(int[] states) {
        StringBuilder sb = new StringBuilder();

        int count = states.length;
        for (int i = 0; i < count; i++) {

            switch (states[i]) {
            case R.attr.state_window_focused:
                sb.append("W ");
                break;
            case R.attr.state_pressed:
                sb.append("P ");
                break;
            case R.attr.state_selected:
                sb.append("S ");
                break;
            case R.attr.state_focused:
                sb.append("F ");
                break;
            case R.attr.state_enabled:
                sb.append("E ");
                break;
            case R.attr.state_checked:
                sb.append("C ");
                break;
            case R.attr.state_activated:
                sb.append("A ");
                break;
            }
        }

        return sb.toString();
    }
}
