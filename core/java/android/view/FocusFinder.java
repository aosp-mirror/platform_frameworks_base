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

package android.view;

import android.graphics.Rect;
import android.view.ViewGroup.ChildListForAccessibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Stack;

/**
 * The algorithm used for finding the next focusable view in a given direction
 * from a view that currently has focus.
 */
public class FocusFinder {

    private static ThreadLocal<FocusFinder> tlFocusFinder =
            new ThreadLocal<FocusFinder>() {
                @Override
                protected FocusFinder initialValue() {
                    return new FocusFinder();
                }
            };

    /**
     * Get the focus finder for this thread.
     */
    public static FocusFinder getInstance() {
        return tlFocusFinder.get();
    }

    Rect mFocusedRect = new Rect();
    Rect mOtherRect = new Rect();
    Rect mBestCandidateRect = new Rect();
    SequentialFocusComparator mSequentialFocusComparator = new SequentialFocusComparator();

    private final ArrayList<View> mTempList = new ArrayList<View>();

    private Stack<View> mTempStack;

    // enforce thread local access
    private FocusFinder() {}

    /**
     * Find the next view to take focus in root's descendants, starting from the view
     * that currently is focused.
     * @param root Contains focused. Cannot be null.
     * @param focused Has focus now.
     * @param direction Direction to look.
     * @return The next focusable view, or null if none exists.
     */
    public final View findNextFocus(ViewGroup root, View focused, int direction) {
        return findNextFocus(root, focused, mFocusedRect, direction);
    }

    /**
     * Find the next view to take focus in root's descendants, searching from
     * a particular rectangle in root's coordinates.
     * @param root Contains focusedRect. Cannot be null.
     * @param focusedRect The starting point of the search.
     * @param direction Direction to look.
     * @return The next focusable view, or null if none exists.
     */
    public View findNextFocusFromRect(ViewGroup root, Rect focusedRect, int direction) {
        return findNextFocus(root, null, focusedRect, direction);
    }

    private View findNextFocus(ViewGroup root, View focused, Rect focusedRect, int direction) {
        if ((direction & View.FOCUS_ACCESSIBILITY) != View.FOCUS_ACCESSIBILITY) {
            return findNextInputFocus(root, focused, focusedRect, direction);
        } else {
            return findNextAccessibilityFocus(root, focused, direction);
        }
    }

    private View findNextInputFocus(ViewGroup root, View focused, Rect focusedRect, int direction) {
        if (focused != null) {
            // check for user specified next focus
            View userSetNextFocus = focused.findUserSetNextFocus(root, direction);
            if (userSetNextFocus != null &&
                userSetNextFocus.isFocusable() &&
                (!userSetNextFocus.isInTouchMode() ||
                 userSetNextFocus.isFocusableInTouchMode())) {
                return userSetNextFocus;
            }

            // fill in interesting rect from focused
            focused.getFocusedRect(mFocusedRect);
            root.offsetDescendantRectToMyCoords(focused, mFocusedRect);
        } else {
            // make up a rect at top left or bottom right of root
            switch (direction) {
                case View.FOCUS_RIGHT:
                case View.FOCUS_DOWN:
                    setFocusTopLeft(root);
                    break;
                case View.FOCUS_FORWARD:
                    if (root.isLayoutRtl()) {
                        setFocusBottomRight(root);
                    } else {
                        setFocusTopLeft(root);
                    }
                    break;

                case View.FOCUS_LEFT:
                case View.FOCUS_UP:
                    setFocusBottomRight(root);
                    break;
                case View.FOCUS_BACKWARD:
                    if (root.isLayoutRtl()) {
                        setFocusTopLeft(root);
                    } else {
                        setFocusBottomRight(root);
                    break;
                }
            }
        }

        ArrayList<View> focusables = mTempList;
        focusables.clear();
        root.addFocusables(focusables, direction);
        if (focusables.isEmpty()) {
            // The focus cannot change.
            return null;
        }

        try {
            switch (direction) {
                case View.FOCUS_FORWARD:
                case View.FOCUS_BACKWARD:
                    return findNextInputFocusInRelativeDirection(focusables, root, focused,
                            focusedRect, direction);
                case View.FOCUS_UP:
                case View.FOCUS_DOWN:
                case View.FOCUS_LEFT:
                case View.FOCUS_RIGHT:
                    return findNextInputFocusInAbsoluteDirection(focusables, root, focused,
                            focusedRect, direction);
                default:
                    throw new IllegalArgumentException("Unknown direction: " + direction);
            }
        } finally {
            focusables.clear();
        }
    }

    /**
     * Find the next view to take accessibility focus in root's descendants,
     * starting from the view that currently is accessibility focused.
     *
     * @param root The root which also contains the focused view.
     * @param focused The current accessibility focused view.
     * @param direction Direction to look.
     * @return The next focusable view, or null if none exists.
     */
    private View findNextAccessibilityFocus(ViewGroup root, View focused, int direction) {
        switch (direction) {
            case View.ACCESSIBILITY_FOCUS_IN:
            case View.ACCESSIBILITY_FOCUS_OUT:
            case View.ACCESSIBILITY_FOCUS_FORWARD:
            case View.ACCESSIBILITY_FOCUS_BACKWARD: {
                return findNextHierarchicalAcessibilityFocus(root, focused, direction);
            }
            case View.ACCESSIBILITY_FOCUS_LEFT:
            case View.ACCESSIBILITY_FOCUS_RIGHT:
            case View.ACCESSIBILITY_FOCUS_UP:
            case View.ACCESSIBILITY_FOCUS_DOWN: {
                return findNextDirectionalAccessibilityFocus(root, focused, direction);
            }
            default:
                throw new IllegalArgumentException("Unknown direction: " + direction);
        }
    }

    private View findNextHierarchicalAcessibilityFocus(ViewGroup root, View focused,
            int direction) {
        View current = (focused != null) ? focused : root;
        switch (direction) {
            case View.ACCESSIBILITY_FOCUS_IN: {
                return findNextAccessibilityFocusIn(current);
            }
            case View.ACCESSIBILITY_FOCUS_OUT: {
                return findNextAccessibilityFocusOut(current);
            }
            case View.ACCESSIBILITY_FOCUS_FORWARD: {
                return findNextAccessibilityFocusForward(current);
            }
            case View.ACCESSIBILITY_FOCUS_BACKWARD: {
                return findNextAccessibilityFocusBackward(current);
            }
        }
        return null;
    }

    private View findNextDirectionalAccessibilityFocus(ViewGroup root, View focused,
            int direction) {
        ArrayList<View> focusables = mTempList;
        focusables.clear();
        root.addFocusables(focusables, direction, View.FOCUSABLES_ACCESSIBILITY);
        Rect focusedRect = getFocusedRect(root, focused, direction);
        final int inputFocusDirection = getCorrespondingInputFocusDirection(direction);
        View next = findNextInputFocusInAbsoluteDirection(focusables, root,
                focused, focusedRect, inputFocusDirection);
        focusables.clear();
        return next;
    }

    private View findNextInputFocusInRelativeDirection(ArrayList<View> focusables, ViewGroup root,
            View focused, Rect focusedRect, int direction) {
        try {
            // Note: This sort is stable.
            mSequentialFocusComparator.setRoot(root);
            Collections.sort(focusables, mSequentialFocusComparator);
        } finally {
            mSequentialFocusComparator.recycle();
        }

        final int count = focusables.size();
        switch (direction) {
            case View.FOCUS_FORWARD:
                return getForwardFocusable(root, focused, focusables, count);
            case View.FOCUS_BACKWARD:
                return getBackwardFocusable(root, focused, focusables, count);
        }
        return focusables.get(count - 1);
    }

    private void setFocusBottomRight(ViewGroup root) {
        final int rootBottom = root.getScrollY() + root.getHeight();
        final int rootRight = root.getScrollX() + root.getWidth();
        mFocusedRect.set(rootRight, rootBottom,
                rootRight, rootBottom);
    }

    private void setFocusTopLeft(ViewGroup root) {
        final int rootTop = root.getScrollY();
        final int rootLeft = root.getScrollX();
        mFocusedRect.set(rootLeft, rootTop, rootLeft, rootTop);
    }

    View findNextInputFocusInAbsoluteDirection(ArrayList<View> focusables, ViewGroup root, View focused,
            Rect focusedRect, int direction) {
        // initialize the best candidate to something impossible
        // (so the first plausible view will become the best choice)
        mBestCandidateRect.set(focusedRect);
        switch(direction) {
            case View.FOCUS_LEFT:
                mBestCandidateRect.offset(focusedRect.width() + 1, 0);
                break;
            case View.FOCUS_RIGHT:
                mBestCandidateRect.offset(-(focusedRect.width() + 1), 0);
                break;
            case View.FOCUS_UP:
                mBestCandidateRect.offset(0, focusedRect.height() + 1);
                break;
            case View.FOCUS_DOWN:
                mBestCandidateRect.offset(0, -(focusedRect.height() + 1));
        }

        View closest = null;

        int numFocusables = focusables.size();
        for (int i = 0; i < numFocusables; i++) {
            View focusable = focusables.get(i);

            // only interested in other non-root views
            if (focusable == focused || focusable == root) continue;

            // get visible bounds of other view in same coordinate system
            focusable.getDrawingRect(mOtherRect);
            root.offsetDescendantRectToMyCoords(focusable, mOtherRect);

            if (isBetterCandidate(direction, focusedRect, mOtherRect, mBestCandidateRect)) {
                mBestCandidateRect.set(mOtherRect);
                closest = focusable;
            }
        }
        return closest;
    }

    private View findNextAccessibilityFocusIn(View view) {
        // We have to traverse the full view tree to make sure
        // we consider views in the order specified by their
        // parent layout managers since some managers could be
        // LTR while some could be RTL.
        if (mTempStack == null) {
            mTempStack = new Stack<View>();
        }
        Stack<View> fringe = mTempStack;
        fringe.clear();
        fringe.add(view);
        while (!fringe.isEmpty()) {
            View current = fringe.pop();
            if (current.getAccessibilityNodeProvider() != null) {
                fringe.clear();
                return current;
            }
            if (current != view && current.includeForAccessibility()) {
                fringe.clear();
                return current;
            }
            if (current instanceof ViewGroup) {
                ViewGroup currentGroup = (ViewGroup) current;
                ChildListForAccessibility children = ChildListForAccessibility.obtain(
                        currentGroup, true);
                final int childCount = children.getChildCount();
                for (int i = childCount - 1; i >= 0; i--) {
                    fringe.push(children.getChildAt(i));
                }
                children.recycle();
            }
        }
        return null;
    }

    private View findNextAccessibilityFocusOut(View view) {
        ViewParent parent = view.getParentForAccessibility();
        if (parent instanceof View) {
            return (View) parent;
        }
        return null;
    }

    private View findNextAccessibilityFocusForward(View view) {
        // We have to traverse the full view tree to make sure
        // we consider views in the order specified by their
        // parent layout managers since some managers could be
        // LTR while some could be RTL.
        View current = view;
        while (current != null) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof ViewGroup)) {
                return null;
            }
            ViewGroup parentGroup = (ViewGroup) parent;
            // Ask the parent to find a sibling after the current view
            // that can take accessibility focus.
            ChildListForAccessibility children = ChildListForAccessibility.obtain(
                    parentGroup, true);
            final int fromIndex = children.getChildIndex(current) + 1;
            final int childCount = children.getChildCount();
            for (int i = fromIndex; i < childCount; i++) {
                View child = children.getChildAt(i);
                View next = null;
                if (child.getAccessibilityNodeProvider() != null) {
                    next = child;
                } else if (child.includeForAccessibility()) {
                    next = child;
                } else {
                    next = findNextAccessibilityFocusIn(child);
                }
                if (next != null) {
                    children.recycle();
                    return next;
                }
            }
            children.recycle();
            // Reaching a regarded for accessibility predecessor without
            // finding a next view to take focus means that at this level
            // there is no next accessibility focusable sibling.
            if (parentGroup.includeForAccessibility()) {
                return null;
            }
            // Try asking a predecessor to find a focusable.
            current = parentGroup;
        }
        return null;
    }

    private View findNextAccessibilityFocusBackward(View view) {
        // We have to traverse the full view tree to make sure
        // we consider views in the order specified by their
        // parent layout managers since some managers could be
        // LTR while some could be RTL.
        View current = view;
        while (current != null) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof ViewGroup)) {
                return null;
            }
            ViewGroup parentGroup = (ViewGroup) parent;
            // Ask the parent to find a sibling after the current view
            // to take accessibility focus
            ChildListForAccessibility children = ChildListForAccessibility.obtain(
                    parentGroup, true);
            final int fromIndex = children.getChildIndex(current) - 1;
            for (int i = fromIndex; i >= 0; i--) {
                View child = children.getChildAt(i);
                View next = null;
                if (child.getAccessibilityNodeProvider() != null) {
                    next = child;
                } else if (child.includeForAccessibility()) {
                    next = child;
                } else {
                    next = findNextAccessibilityFocusIn(child);
                }
                if (next != null) {
                    children.recycle();
                    return next;
                }
            }
            children.recycle();
            // Reaching a regarded for accessibility predecessor without
            // finding a previous view to take focus means that at this level
            // there is no previous accessibility focusable sibling.
            if (parentGroup.includeForAccessibility()) {
                return null;
            }
            // Try asking a predecessor to find a focusable.
            current = parentGroup;
        }
        return null;
    }

    private static View getForwardFocusable(ViewGroup root, View focused,
                                            ArrayList<View> focusables, int count) {
        return (root.isLayoutRtl()) ?
                getPreviousFocusable(focused, focusables, count) :
                getNextFocusable(focused, focusables, count);
    }

    private static View getNextFocusable(View focused, ArrayList<View> focusables, int count) {
        if (focused != null) {
            int position = focusables.lastIndexOf(focused);
            if (position >= 0 && position + 1 < count) {
                return focusables.get(position + 1);
            }
        }
        return focusables.get(0);
    }

    private static View getBackwardFocusable(ViewGroup root, View focused,
                                             ArrayList<View> focusables, int count) {
        return (root.isLayoutRtl()) ?
                getNextFocusable(focused, focusables, count) :
                getPreviousFocusable(focused, focusables, count);
    }

    private static View getPreviousFocusable(View focused, ArrayList<View> focusables, int count) {
        if (focused != null) {
            int position = focusables.indexOf(focused);
            if (position > 0) {
                return focusables.get(position - 1);
            }
        }
        return focusables.get(count - 1);
    }

    private Rect getFocusedRect(ViewGroup root, View focused, int direction) {
        Rect focusedRect = mFocusedRect;
        if (focused != null) {
            focused.getFocusedRect(focusedRect);
            root.offsetDescendantRectToMyCoords(focused, focusedRect);
        } else {
            switch (direction) {
                case View.FOCUS_RIGHT:
                case View.FOCUS_DOWN:
                    final int rootTop = root.getScrollY();
                    final int rootLeft = root.getScrollX();
                    focusedRect.set(rootLeft, rootTop, rootLeft, rootTop);
                    break;

                case View.FOCUS_LEFT:
                case View.FOCUS_UP:
                    final int rootBottom = root.getScrollY() + root.getHeight();
                    final int rootRight = root.getScrollX() + root.getWidth();
                    focusedRect.set(rootRight, rootBottom, rootRight, rootBottom);
                    break;
            }
        }
        return focusedRect;
    }

    private int getCorrespondingInputFocusDirection(int accessFocusDirection) {
        switch (accessFocusDirection) {
            case View.ACCESSIBILITY_FOCUS_LEFT:
                return View.FOCUS_LEFT;
            case View.ACCESSIBILITY_FOCUS_RIGHT:
                return View.FOCUS_RIGHT;
            case View.ACCESSIBILITY_FOCUS_UP:
                return View.FOCUS_UP;
            case View.ACCESSIBILITY_FOCUS_DOWN:
                return View.FOCUS_DOWN;
            default:
                throw new IllegalArgumentException("Cannot map accessiblity focus"
                        + " direction: " + accessFocusDirection);
        }
    }

    /**
     * Is rect1 a better candidate than rect2 for a focus search in a particular
     * direction from a source rect?  This is the core routine that determines
     * the order of focus searching.
     * @param direction the direction (up, down, left, right)
     * @param source The source we are searching from
     * @param rect1 The candidate rectangle
     * @param rect2 The current best candidate.
     * @return Whether the candidate is the new best.
     */
    boolean isBetterCandidate(int direction, Rect source, Rect rect1, Rect rect2) {

        // to be a better candidate, need to at least be a candidate in the first
        // place :)
        if (!isCandidate(source, rect1, direction)) {
            return false;
        }

        // we know that rect1 is a candidate.. if rect2 is not a candidate,
        // rect1 is better
        if (!isCandidate(source, rect2, direction)) {
            return true;
        }

        // if rect1 is better by beam, it wins
        if (beamBeats(direction, source, rect1, rect2)) {
            return true;
        }

        // if rect2 is better, then rect1 cant' be :)
        if (beamBeats(direction, source, rect2, rect1)) {
            return false;
        }

        // otherwise, do fudge-tastic comparison of the major and minor axis
        return (getWeightedDistanceFor(
                        majorAxisDistance(direction, source, rect1),
                        minorAxisDistance(direction, source, rect1))
                < getWeightedDistanceFor(
                        majorAxisDistance(direction, source, rect2),
                        minorAxisDistance(direction, source, rect2)));
    }

    /**
     * One rectangle may be another candidate than another by virtue of being
     * exclusively in the beam of the source rect.
     * @return Whether rect1 is a better candidate than rect2 by virtue of it being in src's
     *      beam
     */
    boolean beamBeats(int direction, Rect source, Rect rect1, Rect rect2) {
        final boolean rect1InSrcBeam = beamsOverlap(direction, source, rect1);
        final boolean rect2InSrcBeam = beamsOverlap(direction, source, rect2);

        // if rect1 isn't exclusively in the src beam, it doesn't win
        if (rect2InSrcBeam || !rect1InSrcBeam) {
            return false;
        }

        // we know rect1 is in the beam, and rect2 is not

        // if rect1 is to the direction of, and rect2 is not, rect1 wins.
        // for example, for direction left, if rect1 is to the left of the source
        // and rect2 is below, then we always prefer the in beam rect1, since rect2
        // could be reached by going down.
        if (!isToDirectionOf(direction, source, rect2)) {
            return true;
        }

        // for horizontal directions, being exclusively in beam always wins
        if ((direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) {
            return true;
        }        

        // for vertical directions, beams only beat up to a point:
        // now, as long as rect2 isn't completely closer, rect1 wins
        // e.g for direction down, completely closer means for rect2's top
        // edge to be closer to the source's top edge than rect1's bottom edge.
        return (majorAxisDistance(direction, source, rect1)
                < majorAxisDistanceToFarEdge(direction, source, rect2));
    }

    /**
     * Fudge-factor opportunity: how to calculate distance given major and minor
     * axis distances.  Warning: this fudge factor is finely tuned, be sure to
     * run all focus tests if you dare tweak it.
     */
    int getWeightedDistanceFor(int majorAxisDistance, int minorAxisDistance) {
        return 13 * majorAxisDistance * majorAxisDistance
                + minorAxisDistance * minorAxisDistance;
    }

    /**
     * Is destRect a candidate for the next focus given the direction?  This
     * checks whether the dest is at least partially to the direction of (e.g left of)
     * from source.
     *
     * Includes an edge case for an empty rect (which is used in some cases when
     * searching from a point on the screen).
     */
    boolean isCandidate(Rect srcRect, Rect destRect, int direction) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return (srcRect.right > destRect.right || srcRect.left >= destRect.right) 
                        && srcRect.left > destRect.left;
            case View.FOCUS_RIGHT:
                return (srcRect.left < destRect.left || srcRect.right <= destRect.left)
                        && srcRect.right < destRect.right;
            case View.FOCUS_UP:
                return (srcRect.bottom > destRect.bottom || srcRect.top >= destRect.bottom)
                        && srcRect.top > destRect.top;
            case View.FOCUS_DOWN:
                return (srcRect.top < destRect.top || srcRect.bottom <= destRect.top)
                        && srcRect.bottom < destRect.bottom;
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }


    /**
     * Do the "beams" w.r.t the given direction's axis of rect1 and rect2 overlap?
     * @param direction the direction (up, down, left, right)
     * @param rect1 The first rectangle
     * @param rect2 The second rectangle
     * @return whether the beams overlap
     */
    boolean beamsOverlap(int direction, Rect rect1, Rect rect2) {
        switch (direction) {
            case View.FOCUS_LEFT:
            case View.FOCUS_RIGHT:
                return (rect2.bottom >= rect1.top) && (rect2.top <= rect1.bottom);
            case View.FOCUS_UP:
            case View.FOCUS_DOWN:
                return (rect2.right >= rect1.left) && (rect2.left <= rect1.right);
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }

    /**
     * e.g for left, is 'to left of'
     */
    boolean isToDirectionOf(int direction, Rect src, Rect dest) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return src.left >= dest.right;
            case View.FOCUS_RIGHT:
                return src.right <= dest.left;
            case View.FOCUS_UP:
                return src.top >= dest.bottom;
            case View.FOCUS_DOWN:
                return src.bottom <= dest.top;
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }

    /**
     * @return The distance from the edge furthest in the given direction
     *   of source to the edge nearest in the given direction of dest.  If the
     *   dest is not in the direction from source, return 0.
     */
    static int majorAxisDistance(int direction, Rect source, Rect dest) {
        return Math.max(0, majorAxisDistanceRaw(direction, source, dest));
    }

    static int majorAxisDistanceRaw(int direction, Rect source, Rect dest) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return source.left - dest.right;
            case View.FOCUS_RIGHT:
                return dest.left - source.right;
            case View.FOCUS_UP:
                return source.top - dest.bottom;
            case View.FOCUS_DOWN:
                return dest.top - source.bottom;
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }

    /**
     * @return The distance along the major axis w.r.t the direction from the
     *   edge of source to the far edge of dest. If the
     *   dest is not in the direction from source, return 1 (to break ties with
     *   {@link #majorAxisDistance}).
     */
    static int majorAxisDistanceToFarEdge(int direction, Rect source, Rect dest) {
        return Math.max(1, majorAxisDistanceToFarEdgeRaw(direction, source, dest));
    }

    static int majorAxisDistanceToFarEdgeRaw(int direction, Rect source, Rect dest) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return source.left - dest.left;
            case View.FOCUS_RIGHT:
                return dest.right - source.right;
            case View.FOCUS_UP:
                return source.top - dest.top;
            case View.FOCUS_DOWN:
                return dest.bottom - source.bottom;
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }

    /**
     * Find the distance on the minor axis w.r.t the direction to the nearest
     * edge of the destination rectangle.
     * @param direction the direction (up, down, left, right)
     * @param source The source rect.
     * @param dest The destination rect.
     * @return The distance.
     */
    static int minorAxisDistance(int direction, Rect source, Rect dest) {
        switch (direction) {
            case View.FOCUS_LEFT:
            case View.FOCUS_RIGHT:
                // the distance between the center verticals
                return Math.abs(
                        ((source.top + source.height() / 2) -
                        ((dest.top + dest.height() / 2))));
            case View.FOCUS_UP:
            case View.FOCUS_DOWN:
                // the distance between the center horizontals
                return Math.abs(
                        ((source.left + source.width() / 2) -
                        ((dest.left + dest.width() / 2))));
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }

    /**
     * Find the nearest touchable view to the specified view.
     * 
     * @param root The root of the tree in which to search
     * @param x X coordinate from which to start the search
     * @param y Y coordinate from which to start the search
     * @param direction Direction to look
     * @param deltas Offset from the <x, y> to the edge of the nearest view. Note that this array
     *        may already be populated with values.
     * @return The nearest touchable view, or null if none exists.
     */
    public View findNearestTouchable(ViewGroup root, int x, int y, int direction, int[] deltas) {
        ArrayList<View> touchables = root.getTouchables();
        int minDistance = Integer.MAX_VALUE;
        View closest = null;

        int numTouchables = touchables.size();
        
        int edgeSlop = ViewConfiguration.get(root.mContext).getScaledEdgeSlop();
        
        Rect closestBounds = new Rect();
        Rect touchableBounds = mOtherRect;
        
        for (int i = 0; i < numTouchables; i++) {
            View touchable = touchables.get(i);

            // get visible bounds of other view in same coordinate system
            touchable.getDrawingRect(touchableBounds);
            
            root.offsetRectBetweenParentAndChild(touchable, touchableBounds, true, true);

            if (!isTouchCandidate(x, y, touchableBounds, direction)) {
                continue;
            }

            int distance = Integer.MAX_VALUE;

            switch (direction) {
            case View.FOCUS_LEFT:
                distance = x - touchableBounds.right + 1;
                break;
            case View.FOCUS_RIGHT:
                distance = touchableBounds.left;
                break;
            case View.FOCUS_UP:
                distance = y - touchableBounds.bottom + 1;
                break;
            case View.FOCUS_DOWN:
                distance = touchableBounds.top;
                break;
            }

            if (distance < edgeSlop) {
                // Give preference to innermost views
                if (closest == null ||
                        closestBounds.contains(touchableBounds) ||
                        (!touchableBounds.contains(closestBounds) && distance < minDistance)) {
                    minDistance = distance;
                    closest = touchable;
                    closestBounds.set(touchableBounds);
                    switch (direction) {
                    case View.FOCUS_LEFT:
                        deltas[0] = -distance;
                        break;
                    case View.FOCUS_RIGHT:
                        deltas[0] = distance;
                        break;
                    case View.FOCUS_UP:
                        deltas[1] = -distance;
                        break;
                    case View.FOCUS_DOWN:
                        deltas[1] = distance;
                        break;
                    }
                }
            }
        }
        return closest;
    }


    /**
     * Is destRect a candidate for the next touch given the direction?
     */
    private boolean isTouchCandidate(int x, int y, Rect destRect, int direction) {
        switch (direction) {
            case View.FOCUS_LEFT:
                return destRect.left <= x && destRect.top <= y && y <= destRect.bottom;
            case View.FOCUS_RIGHT:
                return destRect.left >= x && destRect.top <= y && y <= destRect.bottom;
            case View.FOCUS_UP:
                return destRect.top <= y && destRect.left <= x && x <= destRect.right;
            case View.FOCUS_DOWN:
                return destRect.top >= y && destRect.left <= x && x <= destRect.right;
        }
        throw new IllegalArgumentException("direction must be one of "
                + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
    }

    /**
     * Sorts views according to their visual layout and geometry for default tab order.
     * This is used for sequential focus traversal.
     */
    private static final class SequentialFocusComparator implements Comparator<View> {
        private final Rect mFirstRect = new Rect();
        private final Rect mSecondRect = new Rect();
        private ViewGroup mRoot;

        public void recycle() {
            mRoot = null;
        }

        public void setRoot(ViewGroup root) {
            mRoot = root;
        }

        public int compare(View first, View second) {
            if (first == second) {
                return 0;
            }

            getRect(first, mFirstRect);
            getRect(second, mSecondRect);

            if (mFirstRect.top < mSecondRect.top) {
                return -1;
            } else if (mFirstRect.top > mSecondRect.top) {
                return 1;
            } else if (mFirstRect.left < mSecondRect.left) {
                return -1;
            } else if (mFirstRect.left > mSecondRect.left) {
                return 1;
            } else if (mFirstRect.bottom < mSecondRect.bottom) {
                return -1;
            } else if (mFirstRect.bottom > mSecondRect.bottom) {
                return 1;
            } else if (mFirstRect.right < mSecondRect.right) {
                return -1;
            } else if (mFirstRect.right > mSecondRect.right) {
                return 1;
            } else {
                // The view are distinct but completely coincident so we consider
                // them equal for our purposes.  Since the sort is stable, this
                // means that the views will retain their layout order relative to one another.
                return 0;
            }
        }

        private void getRect(View view, Rect rect) {
            view.getDrawingRect(rect);
            mRoot.offsetDescendantRectToMyCoords(view, rect);
        }
    }
}
