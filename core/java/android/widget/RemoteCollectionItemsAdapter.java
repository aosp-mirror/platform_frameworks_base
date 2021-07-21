/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.widget;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews.ColorResources;
import android.widget.RemoteViews.InteractionHandler;
import android.widget.RemoteViews.RemoteCollectionItems;

import com.android.internal.R;

import java.util.stream.IntStream;

/**
 * List {@link Adapter} backed by a {@link RemoteCollectionItems}.
 *
 * @hide
 */
class RemoteCollectionItemsAdapter extends BaseAdapter {

    private final int mViewTypeCount;

    private RemoteCollectionItems mItems;
    private InteractionHandler mInteractionHandler;
    private ColorResources mColorResources;

    private SparseIntArray mLayoutIdToViewType;

    RemoteCollectionItemsAdapter(
            @NonNull RemoteCollectionItems items,
            @NonNull InteractionHandler interactionHandler,
            @NonNull ColorResources colorResources) {
        // View type count can never increase after an adapter has been set on a ListView.
        // Additionally, decreasing it could inhibit view recycling if the count were to back and
        // forth between 3-2-3-2 for example. Therefore, the view type count, should be fixed for
        // the lifetime of the adapter.
        mViewTypeCount = items.getViewTypeCount();

        mItems = items;
        mInteractionHandler = interactionHandler;
        mColorResources = colorResources;

        initLayoutIdToViewType();
    }

    /**
     * Updates the data for the adapter, allowing recycling of views. Note that if the view type
     * count has increased, a new adapter should be created and set on the AdapterView instead of
     * calling this method.
     */
    void setData(
            @NonNull RemoteCollectionItems items,
            @NonNull InteractionHandler interactionHandler,
            @NonNull ColorResources colorResources) {
        if (mViewTypeCount < items.getViewTypeCount()) {
            throw new IllegalArgumentException(
                    "RemoteCollectionItemsAdapter cannot increase view type count after creation");
        }

        mItems = items;
        mInteractionHandler = interactionHandler;
        mColorResources = colorResources;

        initLayoutIdToViewType();

        notifyDataSetChanged();
    }

    private void initLayoutIdToViewType() {
        SparseIntArray previousLayoutIdToViewType = mLayoutIdToViewType;
        mLayoutIdToViewType = new SparseIntArray(mViewTypeCount);

        int[] layoutIds = IntStream.range(0, mItems.getItemCount())
                .map(position -> mItems.getItemView(position).getLayoutId())
                .distinct()
                .toArray();
        if (layoutIds.length > mViewTypeCount) {
            throw new IllegalArgumentException(
                    "Collection items uses " + layoutIds.length + " distinct layouts, which is "
                            + "more than view type count of " + mViewTypeCount);
        }

        // Tracks whether a layout id (by index, not value) has been assigned a view type.
        boolean[] processedLayoutIdIndices = new boolean[layoutIds.length];
        // Tracks whether a view type has been assigned to a layout id already.
        boolean[] assignedViewTypes = new boolean[mViewTypeCount];

        if (previousLayoutIdToViewType != null) {
            for (int i = 0; i < layoutIds.length; i++) {
                int layoutId = layoutIds[i];
                // Copy over any previously used view types for layout ids in the collection to keep
                // view types stable across data updates.
                int previousViewType = previousLayoutIdToViewType.get(layoutId, -1);
                // Skip this layout id if it wasn't assigned to a view type previously.
                if (previousViewType < 0) continue;

                mLayoutIdToViewType.put(layoutId, previousViewType);
                processedLayoutIdIndices[i] = true;
                assignedViewTypes[previousViewType] = true;
            }
        }

        int lastViewType = -1;
        for (int i = 0; i < layoutIds.length; i++) {
            // If a view type has already been assigned to the layout id, skip it.
            if (processedLayoutIdIndices[i]) continue;

            int layoutId = layoutIds[i];
            // If no view type is assigned for the layout id, choose the next possible value that
            // isn't already assigned to a layout id. There is guaranteed to be some value available
            // due to the prior validation logic that count(distinct layout ids) <= viewTypeCount.
            int viewType = IntStream.range(lastViewType + 1, layoutIds.length)
                    .filter(type -> !assignedViewTypes[type])
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalStateException(
                                    "RemoteCollectionItems has more distinct layout ids than its "
                                            + "view type count"));
            mLayoutIdToViewType.put(layoutId, viewType);
            processedLayoutIdIndices[i] = true;
            assignedViewTypes[viewType] = true;
            lastViewType = viewType;
        }
    }

    @Override
    public int getCount() {
        return mItems.getItemCount();
    }

    @Override
    public RemoteViews getItem(int position) {
        return mItems.getItemView(position);
    }

    @Override
    public long getItemId(int position) {
        return mItems.getItemId(position);
    }

    @Override
    public int getItemViewType(int position) {
        return mLayoutIdToViewType.get(mItems.getItemView(position).getLayoutId());
    }

    @Override
    public int getViewTypeCount() {
        return mViewTypeCount;
    }

    @Override
    public boolean hasStableIds() {
        return mItems.hasStableIds();
    }

    @Nullable
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (position >= getCount()) return null;

        RemoteViews item = mItems.getItemView(position);
        item.addFlags(RemoteViews.FLAG_WIDGET_IS_COLLECTION_CHILD);
        View reapplyView = getViewToReapply(convertView, item);

        // Reapply the RemoteViews if we can.
        if (reapplyView != null) {
            try {
                item.reapply(
                        parent.getContext(),
                        reapplyView,
                        mInteractionHandler,
                        null /* size */,
                        mColorResources);
                return reapplyView;
            } catch (RuntimeException e) {
                // We can't reapply for some reason, we'll fallback to an apply and inflate a
                // new view.
            }
        }

        return item.apply(
                parent.getContext(),
                parent,
                mInteractionHandler,
                null /* size */,
                mColorResources);
    }

    /** Returns {@code convertView} if it can be used to reapply {@code item}, or null otherwise. */
    @Nullable
    private static View getViewToReapply(@Nullable View convertView, @NonNull RemoteViews item) {
        if (convertView == null) return null;

        Object layoutIdTag = convertView.getTag(R.id.widget_frame);
        if (!(layoutIdTag instanceof Integer)) return null;

        return item.getLayoutId() == (Integer) layoutIdTag ? convertView : null;
    }
}
