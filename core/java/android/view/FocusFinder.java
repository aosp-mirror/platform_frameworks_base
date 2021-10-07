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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * The algorithm used for finding the next focusable view in a given direction
 * from a view that currently has focus.
 */
public class FocusFinder {

    private static final ThreadLocal<FocusFinder> tlFocusFinder =
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

    final Rect mFocusedRect = new Rect();
    final Rect mOtherRect = new Rect();
    final Rect mBestCandidateRect = new Rect();
    private final UserSpecifiedFocusComparator mUserSpecifiedFocusComparator =
            new UserSpecifiedFocusComparator((r, v) -> isValidId(v.getNextFocusForwardId())
                            ? v.findUserSetNextFocus(r, View.FOCUS_FORWARD) : null);
    private final UserSpecifiedFocusComparator mUserSpecifiedClusterComparator =
            new UserSpecifiedFocusComparator((r, v) -> isValidId(v.getNextClusterForwardId())
                    ? v.findUserSetNextKeyboardNavigationCluster(r, View.FOCUS_FORWARD) : null);
    private final FocusSorter mFocusSorter = new FocusSorter();

    private final ArrayList<View> mTempList = new ArrayList<View>();

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
        return findNextFocus(root, focused, null, direction);
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
        mFocusedRect.set(focusedRect);
        return findNextFocus(root, null, mFocusedRect, direction);
    }

    private View findNextFocus(ViewGroup root, View focused, Rect focusedRect, int direction) {
        View next = null;
        ViewGroup effectiveRoot = getEffectiveRoot(root, focused);
        if (focused != null) {
            next = findNextUserSpecifiedFocus(effectiveRoot, focused, direction);
        }
        if (next != null) {
            return next;
        }
        ArrayList<View> focusables = mTempList;
        try {
            focusables.clear();
            effectiveRoot.addFocusables(focusables, direction);
            if (!focusables.isEmpty()) {
                next = findNextFocus(effectiveRoot, focused, focusedRect, direction, focusables);
            }
        } finally {
            focusables.clear();
        }
        return next;
    }

    /**
     * Returns the "effective" root of a view. The "effective" root is the closest ancestor
     * within-which focus should cycle.
     * <p>
     * For example: normal focus navigation would stay within a ViewGroup marked as
     * touchscreenBlocksFocus and keyboardNavigationCluster until a cluster-jump out.
     * @return the "effective" root of {@param focused}
     */
    private ViewGroup getEffectiveRoot(ViewGroup root, View focused) {
        if (focused == null || focused == root) {
            return root;
        }
        ViewGroup effective = null;
        ViewParent nextParent = focused.getParent();
        do {
            if (nextParent == root) {
                return effective != null ? effective : root;
            }
            ViewGroup vg = (ViewGroup) nextParent;
            if (vg.getTouchscreenBlocksFocus()
                    && focused.getContext().getPackageManager().hasSystemFeature(
                            PackageManager.FEATURE_TOUCHSCREEN)
                    && vg.isKeyboardNavigationCluster()) {
                // Don't stop and return here because the cluster could be nested and we only
                // care about the top-most one.
                effective = vg;
            }
            nextParent = nextParent.getParent();
        } while (nextParent instanceof ViewGroup);
        return root;
    }

    /**
     * Find the root of the next keyboard navigation cluster after the current one.
     * @param root The view tree to look inside. Cannot be null
     * @param currentCluster The starting point of the search. Null means the default cluster
     * @param direction Direction to look
     * @return The next cluster, or null if none exists
     */
    public View findNextKeyboardNavigationCluster(
            @NonNull View root,
            @Nullable View currentCluster,
            @View.FocusDirection int direction) {
        View next = null;
        if (currentCluster != null) {
            next = findNextUserSpecifiedKeyboardNavigationCluster(root, currentCluster, direction);
            if (next != null) {
                return next;
            }
        }

        final ArrayList<View> clusters = mTempList;
        try {
            clusters.clear();
            root.addKeyboardNavigationClusters(clusters, direction);
            if (!clusters.isEmpty()) {
                next = findNextKeyboardNavigationCluster(
                        root, currentCluster, clusters, direction);
            }
        } finally {
            clusters.clear();
        }
        return next;
    }

    private View findNextUserSpecifiedKeyboardNavigationCluster(View root, View currentCluster,
            int direction) {
        View userSetNextCluster =
                currentCluster.findUserSetNextKeyboardNavigationCluster(root, direction);
        if (userSetNextCluster != null && userSetNextCluster.hasFocusable()) {
            return userSetNextCluster;
        }
        return null;
    }

    private View findNextUserSpecifiedFocus(ViewGroup root, View focused, int direction) {
        // check for user specified next focus
        View userSetNextFocus = focused.findUserSetNextFocus(root, direction);
        View cycleCheck = userSetNextFocus;
        boolean cycleStep = true; // we want the first toggle to yield false
        while (userSetNextFocus != null) {
            if (userSetNextFocus.isFocusable()
                    && userSetNextFocus.getVisibility() == View.VISIBLE
                    && (!userSetNextFocus.isInTouchMode()
                            || userSetNextFocus.isFocusableInTouchMode())) {
                return userSetNextFocus;
            }
            userSetNextFocus = userSetNextFocus.findUserSetNextFocus(root, direction);
            if (cycleStep = !cycleStep) {
                cycleCheck = cycleCheck.findUserSetNextFocus(root, direction);
                if (cycleCheck == userSetNextFocus) {
                    // found a cycle, user-specified focus forms a loop and none of the views
                    // are currently focusable.
                    break;
                }
            }
        }
        return null;
    }

    private View findNextFocus(ViewGroup root, View focused, Rect focusedRect,
            int direction, ArrayList<View> focusables) {
        if (focused != null) {
            if (focusedRect == null) {
                focusedRect = mFocusedRect;
            }
            // fill in interesting rect from focused
            focused.getFocusedRect(focusedRect);
            root.offsetDescendantRectToMyCoords(focused, focusedRect);
        } else {
            if (focusedRect == null) {
                focusedRect = mFocusedRect;
                // make up a rect at top left or bottom right of root
                switch (direction) {
                    case View.FOCUS_RIGHT:
                    case View.FOCUS_DOWN:
                        setFocusTopLeft(root, focusedRect);
                        break;
                    case View.FOCUS_FORWARD:
                        if (root.isLayoutRtl()) {
                            setFocusBottomRight(root, focusedRect);
                        } else {
                            setFocusTopLeft(root, focusedRect);
                        }
                        break;

                    case View.FOCUS_LEFT:
                    case View.FOCUS_UP:
                        setFocusBottomRight(root, focusedRect);
                        break;
                    case View.FOCUS_BACKWARD:
                        if (root.isLayoutRtl()) {
                            setFocusTopLeft(root, focusedRect);
                        } else {
                            setFocusBottomRight(root, focusedRect);
                        break;
                    }
                }
            }
        }

        switch (direction) {
            case View.FOCUS_FORWARD:
            case View.FOCUS_BACKWARD:
                return findNextFocusInRelativeDirection(focusables, root, focused, focusedRect,
                        direction);
            case View.FOCUS_UP:
            case View.FOCUS_DOWN:
            case View.FOCUS_LEFT:
            case View.FOCUS_RIGHT:
                return findNextFocusInAbsoluteDirection(focusables, root, focused,
                        focusedRect, direction);
            default:
                throw new IllegalArgumentException("Unknown direction: " + direction);
        }
    }

    private View findNextKeyboardNavigationCluster(
            View root,
            View currentCluster,
            List<View> clusters,
            @View.FocusDirection int direction) {
        try {
            // Note: This sort is stable.
            mUserSpecifiedClusterComparator.setFocusables(clusters, root);
            Collections.sort(clusters, mUserSpecifiedClusterComparator);
        } finally {
            mUserSpecifiedClusterComparator.recycle();
        }
        final int count = clusters.size();

        switch (direction) {
            case View.FOCUS_FORWARD:
            case View.FOCUS_DOWN:
            case View.FOCUS_RIGHT:
                return getNextKeyboardNavigationCluster(root, currentCluster, clusters, count);
            case View.FOCUS_BACKWARD:
            case View.FOCUS_UP:
            case View.FOCUS_LEFT:
                return getPreviousKeyboardNavigationCluster(root, currentCluster, clusters, count);
            default:
                throw new IllegalArgumentException("Unknown direction: " + direction);
        }
    }

    private View findNextFocusInRelativeDirection(ArrayList<View> focusables, ViewGroup root,
            View focused, Rect focusedRect, int direction) {
        try {
            // Note: This sort is stable.
            mUserSpecifiedFocusComparator.setFocusables(focusables, root);
            Collections.sort(focusables, mUserSpecifiedFocusComparator);
        } finally {
            mUserSpecifiedFocusComparator.recycle();
        }

        final int count = focusables.size();
        if (count < 2) {
            return null;
        }
        switch (direction) {
            case View.FOCUS_FORWARD:
                return getNextFocusable(focused, focusables, count);
            case View.FOCUS_BACKWARD:
                return getPreviousFocusable(focused, focusables, count);
        }
        return focusables.get(count - 1);
    }

    private void setFocusBottomRight(ViewGroup root, Rect focusedRect) {
        final int rootBottom = root.getScrollY() + root.getHeight();
        final int rootRight = root.getScrollX() + root.getWidth();
        focusedRect.set(rootRight, rootBottom, rootRight, rootBottom);
    }

    private void setFocusTopLeft(ViewGroup root, Rect focusedRect) {
        final int rootTop = root.getScrollY();
        final int rootLeft = root.getScrollX();
        focusedRect.set(rootLeft, rootTop, rootLeft, rootTop);
    }

    View findNextFocusInAbsoluteDirection(ArrayList<View> focusables, ViewGroup root, View focused,
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

            // get focus bounds of other view in same coordinate system
            focusable.getFocusedRect(mOtherRect);
            root.offsetDescendantRectToMyCoords(focusable, mOtherRect);

            if (isBetterCandidate(direction, focusedRect, mOtherRect, mBestCandidateRect)) {
                mBestCandidateRect.set(mOtherRect);
                closest = focusable;
            }
        }
        return closest;
    }

    private static View getNextFocusable(View focused, ArrayList<View> focusables, int count) {
        if (count < 2) {
            return null;
        }
        if (focused != null) {
            int position = focusables.lastIndexOf(focused);
            if (position >= 0 && position + 1 < count) {
                return focusables.get(position + 1);
            }
        }
        return focusables.get(0);
    }

    private static View getPreviousFocusable(View focused, ArrayList<View> focusables, int count) {
        if (count < 2) {
            return null;
        }
        if (focused != null) {
            int position = focusables.indexOf(focused);
            if (position > 0) {
                return focusables.get(position - 1);
            }
        }
        return focusables.get(count - 1);
    }

    private static View getNextKeyboardNavigationCluster(
            View root,
            View currentCluster,
            List<View> clusters,
            int count) {
        if (currentCluster == null) {
            // The current cluster is the default one.
            // The next cluster after the default one is the first one.
            // Note that the caller guarantees that 'clusters' is not empty.
            return clusters.get(0);
        }

        final int position = clusters.lastIndexOf(currentCluster);
        if (position >= 0 && position + 1 < count) {
            // Return the next non-default cluster if we can find it.
            return clusters.get(position + 1);
        }

        // The current cluster is the last one. The next one is the default one, i.e. the
        // root.
        return root;
    }

    private static View getPreviousKeyboardNavigationCluster(
            View root,
            View currentCluster,
            List<View> clusters,
            int count) {
        if (currentCluster == null) {
            // The current cluster is the default one.
            // The previous cluster before the default one is the last one.
            // Note that the caller guarantees that 'clusters' is not empty.
            return clusters.get(count - 1);
        }

        final int position = clusters.indexOf(currentCluster);
        if (position > 0) {
            // Return the previous non-default cluster if we can find it.
            return clusters.get(position - 1);
        }

        // The current cluster is the first one. The previous one is the default one, i.e.
        // the root.
        return root;
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
    long getWeightedDistanceFor(long majorAxisDistance, long minorAxisDistance) {
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
                return (rect2.bottom > rect1.top) && (rect2.top < rect1.bottom);
            case View.FOCUS_UP:
            case View.FOCUS_DOWN:
                return (rect2.right > rect1.left) && (rect2.left < rect1.right);
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

    private static final boolean isValidId(final int id) {
        return id != 0 && id != View.NO_ID;
    }

    static final class FocusSorter {
        private ArrayList<Rect> mRectPool = new ArrayList<>();
        private int mLastPoolRect;
        private int mRtlMult;
        private HashMap<View, Rect> mRectByView = null;

        private Comparator<View> mTopsComparator = (first, second) -> {
            if (first == second) {
                return 0;
            }

            Rect firstRect = mRectByView.get(first);
            Rect secondRect = mRectByView.get(second);

            int result = firstRect.top - secondRect.top;
            if (result == 0) {
                return firstRect.bottom - secondRect.bottom;
            }
            return result;
        };

        private Comparator<View> mSidesComparator = (first, second) -> {
            if (first == second) {
                return 0;
            }

            Rect firstRect = mRectByView.get(first);
            Rect secondRect = mRectByView.get(second);

            int result = firstRect.left - secondRect.left;
            if (result == 0) {
                return firstRect.right - secondRect.right;
            }
            return mRtlMult * result;
        };

        public void sort(View[] views, int start, int end, ViewGroup root, boolean isRtl) {
            int count = end - start;
            if (count < 2) {
                return;
            }
            if (mRectByView == null) {
                mRectByView = new HashMap<>();
            }
            mRtlMult = isRtl ? -1 : 1;
            for (int i = mRectPool.size(); i < count; ++i) {
                mRectPool.add(new Rect());
            }
            for (int i = start; i < end; ++i) {
                Rect next = mRectPool.get(mLastPoolRect++);
                views[i].getDrawingRect(next);
                root.offsetDescendantRectToMyCoords(views[i], next);
                mRectByView.put(views[i], next);
            }

            // Sort top-to-bottom
            Arrays.sort(views, start, count, mTopsComparator);
            // Sweep top-to-bottom to identify rows
            int sweepBottom = mRectByView.get(views[start]).bottom;
            int rowStart = start;
            int sweepIdx = start + 1;
            for (; sweepIdx < end; ++sweepIdx) {
                Rect currRect = mRectByView.get(views[sweepIdx]);
                if (currRect.top >= sweepBottom) {
                    // Next view is on a new row, sort the row we've just finished left-to-right.
                    if ((sweepIdx - rowStart) > 1) {
                        Arrays.sort(views, rowStart, sweepIdx, mSidesComparator);
                    }
                    sweepBottom = currRect.bottom;
                    rowStart = sweepIdx;
                } else {
                    // Next view vertically overlaps, we need to extend our "row height"
                    sweepBottom = Math.max(sweepBottom, currRect.bottom);
                }
            }
            // Sort whatever's left (final row) left-to-right
            if ((sweepIdx - rowStart) > 1) {
                Arrays.sort(views, rowStart, sweepIdx, mSidesComparator);
            }

            mLastPoolRect = 0;
            mRectByView.clear();
        }
    }

    /**
     * Public for testing.
     *
     * @hide
     */
    @TestApi
    public static void sort(View[] views, int start, int end, ViewGroup root, boolean isRtl) {
        getInstance().mFocusSorter.sort(views, start, end, root, isRtl);
    }

    /**
     * Sorts views according to any explicitly-specified focus-chains. If there are no explicitly
     * specified focus chains (eg. no nextFocusForward attributes defined), this should be a no-op.
     */
    private static final class UserSpecifiedFocusComparator implements Comparator<View> {
        private final ArrayMap<View, View> mNextFoci = new ArrayMap<>();
        private final ArraySet<View> mIsConnectedTo = new ArraySet<>();
        private final ArrayMap<View, View> mHeadsOfChains = new ArrayMap<View, View>();
        private final ArrayMap<View, Integer> mOriginalOrdinal = new ArrayMap<>();
        private final NextFocusGetter mNextFocusGetter;
        private View mRoot;

        public interface NextFocusGetter {
            View get(View root, View view);
        }

        UserSpecifiedFocusComparator(NextFocusGetter nextFocusGetter) {
            mNextFocusGetter = nextFocusGetter;
        }

        public void recycle() {
            mRoot = null;
            mHeadsOfChains.clear();
            mIsConnectedTo.clear();
            mOriginalOrdinal.clear();
            mNextFoci.clear();
        }

        public void setFocusables(List<View> focusables, View root) {
            mRoot = root;
            for (int i = 0; i < focusables.size(); ++i) {
                mOriginalOrdinal.put(focusables.get(i), i);
            }

            for (int i = focusables.size() - 1; i >= 0; i--) {
                final View view = focusables.get(i);
                final View next = mNextFocusGetter.get(mRoot, view);
                if (next != null && mOriginalOrdinal.containsKey(next)) {
                    mNextFoci.put(view, next);
                    mIsConnectedTo.add(next);
                }
            }

            for (int i = focusables.size() - 1; i >= 0; i--) {
                final View view = focusables.get(i);
                final View next = mNextFoci.get(view);
                if (next != null && !mIsConnectedTo.contains(view)) {
                    setHeadOfChain(view);
                }
            }
        }

        private void setHeadOfChain(View head) {
            for (View view = head; view != null; view = mNextFoci.get(view)) {
                final View otherHead = mHeadsOfChains.get(view);
                if (otherHead != null) {
                    if (otherHead == head) {
                        return; // This view has already had its head set properly
                    }
                    // A hydra -- multi-headed focus chain (e.g. A->C and B->C)
                    // Use the one we've already chosen instead and reset this chain.
                    view = head;
                    head = otherHead;
                }
                mHeadsOfChains.put(view, head);
            }
        }

        public int compare(View first, View second) {
            if (first == second) {
                return 0;
            }
            // Order between views within a chain is immaterial -- next/previous is
            // within a chain is handled elsewhere.
            View firstHead = mHeadsOfChains.get(first);
            View secondHead = mHeadsOfChains.get(second);
            if (firstHead == secondHead && firstHead != null) {
                if (first == firstHead) {
                    return -1; // first is the head, it should be first
                } else if (second == firstHead) {
                    return 1; // second is the head, it should be first
                } else if (mNextFoci.get(first) != null) {
                    return -1; // first is not the end of the chain
                } else {
                    return 1; // first is end of chain
                }
            }
            boolean involvesChain = false;
            if (firstHead != null) {
                first = firstHead;
                involvesChain = true;
            }
            if (secondHead != null) {
                second = secondHead;
                involvesChain = true;
            }

            if (involvesChain) {
                // keep original order between chains
                return mOriginalOrdinal.get(first) < mOriginalOrdinal.get(second) ? -1 : 1;
            } else {
                return 0;
            }
        }
    }
}
