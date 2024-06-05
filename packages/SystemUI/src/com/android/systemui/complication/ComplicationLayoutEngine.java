/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.complication;

import static com.android.systemui.complication.ComplicationLayoutParams.DIRECTION_DOWN;
import static com.android.systemui.complication.ComplicationLayoutParams.DIRECTION_END;
import static com.android.systemui.complication.ComplicationLayoutParams.DIRECTION_START;
import static com.android.systemui.complication.ComplicationLayoutParams.DIRECTION_UP;
import static com.android.systemui.complication.ComplicationLayoutParams.POSITION_BOTTOM;
import static com.android.systemui.complication.ComplicationLayoutParams.POSITION_END;
import static com.android.systemui.complication.ComplicationLayoutParams.POSITION_START;
import static com.android.systemui.complication.ComplicationLayoutParams.POSITION_TOP;
import static com.android.systemui.complication.dagger.ComplicationHostViewModule.COMPLICATIONS_FADE_IN_DURATION;
import static com.android.systemui.complication.dagger.ComplicationHostViewModule.COMPLICATIONS_FADE_OUT_DURATION;
import static com.android.systemui.complication.dagger.ComplicationHostViewModule.COMPLICATION_DIRECTIONAL_SPACING_DEFAULT;
import static com.android.systemui.complication.dagger.ComplicationHostViewModule.COMPLICATION_MARGIN_POSITION_BOTTOM;
import static com.android.systemui.complication.dagger.ComplicationHostViewModule.COMPLICATION_MARGIN_POSITION_END;
import static com.android.systemui.complication.dagger.ComplicationHostViewModule.COMPLICATION_MARGIN_POSITION_START;
import static com.android.systemui.complication.dagger.ComplicationHostViewModule.COMPLICATION_MARGIN_POSITION_TOP;
import static com.android.systemui.complication.dagger.ComplicationHostViewModule.SCOPED_COMPLICATIONS_LAYOUT;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Constraints;

import com.android.systemui.complication.ComplicationLayoutParams.Direction;
import com.android.systemui.complication.ComplicationLayoutParams.Position;
import com.android.systemui.complication.dagger.ComplicationModule;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.touch.TouchInsetManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link ComplicationLayoutEngine} arranges a collection of {@link ComplicationViewModel} based on
 * their layout parameters and attributes. The management of this set is done by
 * {@link ComplicationHostViewController}.
 */
@ComplicationModule.ComplicationScope
public class ComplicationLayoutEngine implements Complication.VisibilityController {
    public static final String TAG = "ComplicationLayoutEng";

    /**
     * Container for storing and operating on a tuple of margin values.
     */
    public static class Margins {
        public final int start;
        public final int top;
        public final int end;
        public final int bottom;

        /**
         * Default constructor with all margins set to 0.
         */
        public Margins() {
            this(0, 0, 0, 0);
        }

        /**
         * Cosntructor to specify margin in each direction.
         * @param start start margin
         * @param top top margin
         * @param end end margin
         * @param bottom bottom margin
         */
        public Margins(int start, int top, int end, int bottom) {
            this.start = start;
            this.top = top;
            this.end = end;
            this.bottom = bottom;
        }

        /**
         * Creates a new {@link Margins} by adding the corresponding dimensions together.
         */
        public static Margins combine(Margins margins1, Margins margins2) {
            return new Margins(margins1.start + margins2.start,
                    margins1.top + margins2.top,
                    margins1.end + margins2.end,
                    margins1.bottom + margins2.bottom);
        }
    }

    /**
     * {@link ViewEntry} is an internal container, capturing information necessary for working with
     * a particular {@link Complication} view.
     */
    private static class ViewEntry implements Comparable<ViewEntry> {
        private final View mView;
        private final ComplicationLayoutParams mLayoutParams;
        private final TouchInsetManager.TouchInsetSession mTouchInsetSession;
        private final Parent mParent;
        @Complication.Category
        private final int mCategory;

        /**
         * Default constructor. {@link Parent} allows for the {@link ViewEntry}'s surrounding
         * view hierarchy to be accessed without traversing the entire view tree.
         */
        ViewEntry(View view, ComplicationLayoutParams layoutParams,
                TouchInsetManager.TouchInsetSession touchSession, int category, Parent parent) {
            mView = view;
            // Views that are generated programmatically do not have a unique id assigned to them
            // at construction. A new id is assigned here to enable ConstraintLayout relative
            // specifications. Existing ids for inflated views are not preserved.
            // {@link Complication.ViewHolder} should not reference the root container by id.
            mView.setId(View.generateViewId());
            mLayoutParams = layoutParams;
            mTouchInsetSession = touchSession;
            mCategory = category;
            mParent = parent;

            touchSession.addViewToTracking(mView);
        }

        /**
         * Returns the {@link View} associated with the {@link Complication}. This is the instance
         * passed in at construction. The reference to this {@link View} is captured when the
         * {@link Complication} is added to the {@link ComplicationLayoutEngine}. The
         * {@link Complication} cannot modify the {@link View} reference beyond this point.
         */
        private View getView() {
            return mView;
        }

        /**
         * Returns The {@link ComplicationLayoutParams} associated with the view.
         */
        public ComplicationLayoutParams getLayoutParams() {
            return mLayoutParams;
        }

        /**
         * Interprets the {@link #getLayoutParams()} into {@link ConstraintLayout.LayoutParams} and
         * applies them to the view. The method accounts for the relationship of the {@link View} to
         * the other {@link Complication} views around it. The organization of the {@link View}
         * instances in {@link ComplicationLayoutEngine} can be seen as lists. A {@link View} is
         * either the head of its list or a following node. This head is passed into this method,
         * which can be a reference to the {@link View} to indicate it is the head.
         */
        public void applyLayoutParams(View head) {
            // Only the basic dimension parameters from the base ViewGroup.LayoutParams are carried
            // over verbatim from the complication specified LayoutParam. Other fields are
            // interpreted.
            final ConstraintLayout.LayoutParams params =
                    new Constraints.LayoutParams(mLayoutParams.width, mLayoutParams.height);

            final int direction = getLayoutParams().getDirection();

            final boolean snapsToGuide = getLayoutParams().snapsToGuide();

            // If no parent, view is the anchor. In this case, it is given the highest priority for
            // alignment. All alignment preferences are done in relation to the parent container.
            final boolean isRoot = head == mView;

            // Each view can be seen as a vector, having a point (described here as position) and
            // direction. When a view is the head of a position, then it is the first in a sequence
            // of complications to appear from that position. For example, being the head for
            // position POSITION_TOP | POSITION_END will cause the view to be shown as the first
            // view in that corner. In this case, the positions specify which sides to align with
            // the parent. If the view is not the head, the positions perpendicular to the direction
            // of the view specify which side to align with the opposing side of the head view.
            // Otherwise, the position aligns with the containing view. This means a
            // POSITION_BOTTOM | POSITION_START with DIRECTION_UP non-head view's bottom to be
            // aligned with the preceding view node's top and start to be aligned with the
            // parent's start.
            mLayoutParams.iteratePositions(position -> {
                switch(position) {
                    case ComplicationLayoutParams.POSITION_START:
                        if (isRoot || direction != ComplicationLayoutParams.DIRECTION_END) {
                            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                        } else {
                            params.startToEnd = head.getId();
                        }
                        if (snapsToGuide
                                && (direction == ComplicationLayoutParams.DIRECTION_DOWN
                                || direction == ComplicationLayoutParams.DIRECTION_UP)) {
                            params.endToStart = R.id.complication_start_guide;
                        }
                        break;
                    case ComplicationLayoutParams.POSITION_TOP:
                        if (isRoot || direction != ComplicationLayoutParams.DIRECTION_DOWN) {
                            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                        } else {
                            params.topToBottom = head.getId();
                        }
                        if (snapsToGuide
                                && (direction == ComplicationLayoutParams.DIRECTION_END
                                || direction == ComplicationLayoutParams.DIRECTION_START)) {
                            params.endToStart = R.id.complication_top_guide;
                        }
                        break;
                    case ComplicationLayoutParams.POSITION_BOTTOM:
                        if (isRoot || direction != ComplicationLayoutParams.DIRECTION_UP) {
                            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                        } else {
                            params.bottomToTop = head.getId();
                        }
                        if (snapsToGuide
                                && (direction == ComplicationLayoutParams.DIRECTION_END
                                || direction == ComplicationLayoutParams.DIRECTION_START)) {
                            params.topToBottom = R.id.complication_bottom_guide;
                        }
                        break;
                    case ComplicationLayoutParams.POSITION_END:
                        if (isRoot || direction != ComplicationLayoutParams.DIRECTION_START) {
                            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                        } else {
                            params.endToStart = head.getId();
                        }
                        if (snapsToGuide
                                && (direction == ComplicationLayoutParams.DIRECTION_UP
                                || direction == ComplicationLayoutParams.DIRECTION_DOWN)) {
                            params.startToEnd = R.id.complication_end_guide;
                        }
                        break;
                }

                final Margins margins = mParent.getMargins(this, isRoot);
                params.setMarginsRelative(margins.start, margins.top, margins.end, margins.bottom);
            });

            if (mLayoutParams.constraintSpecified()) {
                switch (direction) {
                    case ComplicationLayoutParams.DIRECTION_START:
                    case ComplicationLayoutParams.DIRECTION_END:
                        params.matchConstraintMaxWidth = mLayoutParams.getConstraint();
                        break;
                    case ComplicationLayoutParams.DIRECTION_UP:
                    case ComplicationLayoutParams.DIRECTION_DOWN:
                        params.matchConstraintMaxHeight = mLayoutParams.getConstraint();
                        break;
                }
            }

            mView.setLayoutParams(params);
        }

        private void setGuide(ConstraintLayout.LayoutParams lp, int validDirections,
                Consumer<ConstraintLayout.LayoutParams> consumer) {
            final ComplicationLayoutParams layoutParams = getLayoutParams();
            if (!layoutParams.snapsToGuide()) {
                return;
            }

            consumer.accept(lp);
        }

        /**
         * Informs the {@link ViewEntry}'s parent entity to remove the {@link ViewEntry} from
         * being shown further.
         */
        public void remove() {
            mParent.removeEntry(this);

            ((ViewGroup) mView.getParent()).removeView(mView);
            mTouchInsetSession.removeViewFromTracking(mView);
        }

        @Override
        public int compareTo(ViewEntry viewEntry) {
            // If the two entries have different categories, system complications take precedence.
            if (viewEntry.mCategory != mCategory) {
                // Note that this logic will need to be adjusted if more categories are introduced.
                return mCategory == Complication.CATEGORY_SYSTEM ? 1 : -1;
            }

            // A higher weight indicates greater precedence if all else being equal.
            if (viewEntry.mLayoutParams.getWeight() != mLayoutParams.getWeight()) {
                return mLayoutParams.getWeight() > viewEntry.mLayoutParams.getWeight() ? 1 : -1;
            }

            return 0;
        }

        /**
         * {@link Builder} allows for a multiple entities to contribute to the {@link ViewEntry}
         * construction. This is necessary for setting an immutable parent, which might not be
         * known until the view hierarchy is traversed.
         */
        private static class Builder {
            private final View mView;
            private final TouchInsetManager.TouchInsetSession mTouchSession;
            private final ComplicationLayoutParams mLayoutParams;
            private final int mCategory;
            private Parent mParent;

            Builder(View view, TouchInsetManager.TouchInsetSession touchSession,
                    ComplicationLayoutParams lp, @Complication.Category int category) {
                mView = view;
                mLayoutParams = lp;
                mCategory = category;
                mTouchSession = touchSession;
            }

            /**
             * Returns the set {@link ComplicationLayoutParams}
             */
            public ComplicationLayoutParams getLayoutParams() {
                return mLayoutParams;
            }

            /**
             * Returns the set {@link Complication.Category}.
             */
            @Complication.Category
            public int getCategory() {
                return mCategory;
            }

            /**
             * Sets the parent. Note that this references to the entity for handling events, such as
             * requesting the removal of the {@link View}. It is not the
             * {@link android.view.ViewGroup} which contains the {@link View}.
             */
            Builder setParent(Parent parent) {
                mParent = parent;
                return this;
            }

            /**
             * Builds and returns the resulting {@link ViewEntry}.
             */
            ViewEntry build() {
                return new ViewEntry(mView, mLayoutParams, mTouchSession, mCategory, mParent);
            }
        }

        /**
         * An interface allowing an {@link ViewEntry} to signal events.
         */
        interface Parent {
            /**
             * Indicates the {@link ViewEntry} requests removal.
             */
            void removeEntry(ViewEntry entry);

            /**
             * Returns the margins to be applied to the entry
             */
            Margins getMargins(ViewEntry entry, boolean isRoot);
        }
    }

    /**
     * {@link PositionGroup} represents a collection of {@link Complication} at a given location.
     * It further organizes the {@link Complication} by the direction in which they emanate from
     * this position.
     */
    private static class PositionGroup implements DirectionGroup.Parent {
        private final HashMap<Integer, DirectionGroup> mDirectionGroups = new HashMap<>();

        private final HashMap<Integer, Margins> mDirectionalMargins;

        private final int mDefaultDirectionalSpacing;

        PositionGroup(int defaultDirectionalSpacing, HashMap<Integer, Margins> directionalMargins) {
            mDefaultDirectionalSpacing = defaultDirectionalSpacing;
            mDirectionalMargins = directionalMargins;
        }

        /**
         * Invoked by the {@link PositionGroup} holder to introduce a {@link Complication} view to
         * this group. It is assumed that the caller has correctly identified this
         * {@link PositionGroup} as the proper home for the {@link Complication} based on its
         * declared position.
         */
        public ViewEntry add(ViewEntry.Builder entryBuilder) {
            final int direction = entryBuilder.getLayoutParams().getDirection();
            if (!mDirectionGroups.containsKey(direction)) {
                mDirectionGroups.put(direction, new DirectionGroup(this));
            }

            return mDirectionGroups.get(direction).add(entryBuilder);
        }

        @Override
        public void onEntriesChanged() {
            // Whenever an entry is added/removed from a child {@link DirectionGroup}, it is vital
            // that all {@link DirectionGroup} children are visited. It is possible the overall
            // head has changed, requiring constraints to be adjusted.
            updateViews();
        }

        @Override
        public int getDefaultDirectionalSpacing() {
            return mDefaultDirectionalSpacing;
        }

        @Override
        public Margins getMargins(ViewEntry entry, boolean isRoot) {
            if (isRoot) {
                Margins cumulativeMargins = new Margins();

                for (Margins margins : mDirectionalMargins.values()) {
                    cumulativeMargins = Margins.combine(margins, cumulativeMargins);
                }

                return cumulativeMargins;
            }

            return mDirectionalMargins.get(entry.getLayoutParams().getDirection());
        }

        private void updateViews() {
            ViewEntry head = null;

            // Identify which {@link Complication} head from the set of {@link DirectionGroup}
            // should be treated as the {@link PositionGroup} head.
            for (DirectionGroup directionGroup : mDirectionGroups.values()) {
                final ViewEntry groupHead = directionGroup.getHead();
                if (head == null || (groupHead != null && groupHead.compareTo(head) > 0)) {
                    head = groupHead;
                }
            }

            // A headless position group indicates no complications.
            if (head == null) {
                return;
            }

            for (DirectionGroup directionGroup : mDirectionGroups.values()) {
                // Tell each {@link DirectionGroup} to update its containing {@link ViewEntry} based
                // on the identified head. This iteration will also capture any newly added views.
                directionGroup.updateViews(head.getView());
            }
        }

        private ArrayList<ViewEntry> getViews() {
            final ArrayList<ViewEntry> views = new ArrayList<>();
            for (DirectionGroup directionGroup : mDirectionGroups.values()) {
                views.addAll(directionGroup.getViews());
            }
            return views;
        }
    }

    /**
     * A {@link DirectionGroup} organizes the {@link ViewEntry} of a parent group that point are
     * laid out in the same direction.
     */
    private static class DirectionGroup implements ViewEntry.Parent {
        /**
         * An interface implemented by the {@link DirectionGroup} parent to receive updates.
         */
        interface Parent {
            /**
             * Invoked to indicate a change to the {@link ViewEntry} composition for this
             * {@link DirectionGroup}.
             */
            void onEntriesChanged();

            /**
             * Returns the default spacing between elements.
             */
            int getDefaultDirectionalSpacing();

            /**
             * Returns the margins for the view entry.
             */
            Margins getMargins(ViewEntry entry, boolean isRoot);
        }
        private final ArrayList<ViewEntry> mViews = new ArrayList<>();
        private final Parent mParent;

        /**
         * Creates a new {@link DirectionGroup} with the specified parent.
         */
        DirectionGroup(Parent parent) {
            mParent = parent;
        }

        /**
         * Returns the head of the group. It is assumed that the order of the {@link ViewEntry} is
         * proactively maintained.
         */
        public ViewEntry getHead() {
            return mViews.isEmpty() ? null : mViews.get(0);
        }

        /**
         * Adds a {@link ViewEntry} via {@link ViewEntry.Builder} to this group.
         */
        public ViewEntry add(ViewEntry.Builder entryBuilder) {
            final ViewEntry entry = entryBuilder.setParent(this).build();
            mViews.add(entry);

            // After adding view, reverse sort collection.
            Collections.sort(mViews);
            Collections.reverse(mViews);

            mParent.onEntriesChanged();

            return entry;
        }

        @Override
        public void removeEntry(ViewEntry entry) {
            // Sort is handled when the view is added, so should still be correct after removal.
            // However, the head may have been removed, which may affect the layout of views in
            // other DirectionGroups of the same PositionGroup.
            mViews.remove(entry);
            mParent.onEntriesChanged();
        }

        @Override
        public Margins getMargins(ViewEntry entry, boolean isRoot) {
            int directionalSpacing = entry.getLayoutParams().getDirectionalSpacing(
                    mParent.getDefaultDirectionalSpacing());

            Margins margins = new Margins();

            if (!isRoot) {
                switch (entry.getLayoutParams().getDirection()) {
                    case ComplicationLayoutParams.DIRECTION_START:
                        margins = new Margins(0, 0, directionalSpacing, 0);
                        break;
                    case ComplicationLayoutParams.DIRECTION_UP:
                        margins = new Margins(0, 0, 0, directionalSpacing);
                        break;
                    case ComplicationLayoutParams.DIRECTION_END:
                        margins = new Margins(directionalSpacing, 0, 0, 0);
                        break;
                    case ComplicationLayoutParams.DIRECTION_DOWN:
                        margins = new Margins(0, directionalSpacing, 0, 0);
                        break;
                }
            }

            return Margins.combine(mParent.getMargins(entry, isRoot), margins);
        }

        /**
         * Invoked by {@link Parent} to update the layout of all children {@link ViewEntry} with
         * the specified head. Note that the head might not be in this group and instead part of a
         * neighboring group.
         */
        public void updateViews(View groupHead) {
            Iterator<ViewEntry> it = mViews.iterator();

            while (it.hasNext()) {
                final ViewEntry viewEntry = it.next();
                viewEntry.applyLayoutParams(groupHead);
                groupHead = viewEntry.getView();
            }
        }

        private List<ViewEntry> getViews() {
            return mViews;
        }
    }

    private final ConstraintLayout mLayout;
    private final int mDefaultDirectionalSpacing;
    private final HashMap<ComplicationId, ViewEntry> mEntries = new HashMap<>();
    private final HashMap<Integer, PositionGroup> mPositions = new HashMap<>();
    private final TouchInsetManager.TouchInsetSession mSession;
    private final int mFadeInDuration;
    private final int mFadeOutDuration;
    private final HashMap<Integer, HashMap<Integer, Margins>> mPositionDirectionMarginMapping;

    /** */
    @Inject
    public ComplicationLayoutEngine(@Named(SCOPED_COMPLICATIONS_LAYOUT) ConstraintLayout layout,
            @Named(COMPLICATION_DIRECTIONAL_SPACING_DEFAULT) int defaultDirectionalSpacing,
            @Named(COMPLICATION_MARGIN_POSITION_START) int complicationMarginPositionStart,
            @Named(COMPLICATION_MARGIN_POSITION_TOP) int complicationMarginPositionTop,
            @Named(COMPLICATION_MARGIN_POSITION_END) int complicationMarginPositionEnd,
            @Named(COMPLICATION_MARGIN_POSITION_BOTTOM) int complicationMarginPositionBottom,
            TouchInsetManager.TouchInsetSession session,
            @Named(COMPLICATIONS_FADE_IN_DURATION) int fadeInDuration,
            @Named(COMPLICATIONS_FADE_OUT_DURATION) int fadeOutDuration) {
        mLayout = layout;
        mDefaultDirectionalSpacing = defaultDirectionalSpacing;
        mSession = session;
        mFadeInDuration = fadeInDuration;
        mFadeOutDuration = fadeOutDuration;
        mPositionDirectionMarginMapping = generatePositionDirectionalMarginsMapping(
                complicationMarginPositionStart,
                complicationMarginPositionTop,
                complicationMarginPositionEnd,
                complicationMarginPositionBottom);
    }

    private static HashMap<Integer, HashMap<Integer, Margins>>
            generatePositionDirectionalMarginsMapping(int complicationMarginPositionStart,
            int complicationMarginPositionTop,
            int complicationMarginPositionEnd,
            int complicationMarginPositionBottom) {
        HashMap<Integer, HashMap<Integer, Margins>> mapping = new HashMap<>();

        final Margins startMargins = new Margins(complicationMarginPositionStart, 0, 0, 0);
        final Margins topMargins = new Margins(0, complicationMarginPositionTop, 0, 0);
        final Margins endMargins = new Margins(0, 0, complicationMarginPositionEnd, 0);
        final Margins bottomMargins = new Margins(0, 0, 0, complicationMarginPositionBottom);

        addToMapping(mapping, POSITION_START | POSITION_TOP, DIRECTION_END, topMargins);
        addToMapping(mapping, POSITION_START | POSITION_TOP, DIRECTION_DOWN, startMargins);

        addToMapping(mapping, POSITION_START | POSITION_BOTTOM, DIRECTION_END, bottomMargins);
        addToMapping(mapping, POSITION_START | POSITION_BOTTOM, DIRECTION_UP, startMargins);

        addToMapping(mapping, POSITION_END | POSITION_TOP, DIRECTION_START, topMargins);
        addToMapping(mapping, POSITION_END | POSITION_TOP, DIRECTION_DOWN, endMargins);

        addToMapping(mapping, POSITION_END | POSITION_BOTTOM, DIRECTION_START, bottomMargins);
        addToMapping(mapping, POSITION_END | POSITION_BOTTOM, DIRECTION_UP, endMargins);

        return mapping;
    }

    private static void addToMapping(HashMap<Integer, HashMap<Integer, Margins>> mapping,
            @Position int position, @Direction int direction, Margins margins) {
        if (!mapping.containsKey(position)) {
            mapping.put(position, new HashMap<>());
        }
        mapping.get(position).put(direction, margins);
    }

    @Override
    public void setVisibility(int visibility) {
        if (visibility == View.VISIBLE) {
            CrossFadeHelper.fadeIn(mLayout, mFadeInDuration, /* delay= */ 0);
        } else {
            CrossFadeHelper.fadeOut(
                    mLayout,
                    mFadeOutDuration,
                    /* delay= */ 0);
        }
    }

    /**
     * Adds a complication to this {@link ComplicationLayoutEngine}.
     * @param id A {@link ComplicationId} unique to this complication. If this matches a
     *           complication within this {@link ComplicationViewModel}, the existing complication
     *           will be removed.
     * @param view The {@link View} to be shown.
     * @param lp The {@link ComplicationLayoutParams} as expressed by the {@link Complication}.
     *           These will be interpreted into the final applied parameters.
     * @param category The {@link Complication.Category} for the {@link Complication}.
     */
    public void addComplication(ComplicationId id, View view,
            ComplicationLayoutParams lp, @Complication.Category int category) {
        Log.d(TAG, "@" + Integer.toHexString(this.hashCode()) + " addComplication: " + id);

        // If the complication is present, remove.
        if (mEntries.containsKey(id)) {
            removeComplication(id);
        }

        final ViewEntry.Builder entryBuilder = new ViewEntry.Builder(view, mSession, lp, category);

        // Add position group if doesn't already exist
        final int position = lp.getPosition();
        if (!mPositions.containsKey(position)) {
            mPositions.put(position, new PositionGroup(mDefaultDirectionalSpacing,
                    mPositionDirectionMarginMapping.get(lp.getPosition())));
        }

        // Insert entry into group
        final ViewEntry entry = mPositions.get(position).add(entryBuilder);
        mEntries.put(id, entry);

        mLayout.addView(entry.getView());
    }

    /**
     * Removes a complication by {@link ComplicationId}.
     */
    public boolean removeComplication(ComplicationId id) {
        final ViewEntry entry = mEntries.remove(id);

        if (entry == null) {
            Log.e(TAG, "could not find id:" + id);
            return false;
        }

        entry.remove();
        return true;
    }

    /**
     * Gets an unordered list of all the views at a particular position.
     */
    public List<View> getViewsAtPosition(@Position int position) {
        return mPositions.entrySet().stream()
                .filter(entry -> (entry.getKey() & position) == position)
                .flatMap(entry -> entry.getValue().getViews().stream())
                .map(ViewEntry::getView)
                .collect(Collectors.toList());
    }
}
