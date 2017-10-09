/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.slice.views;

import android.content.Context;
import android.slice.Slice;
import android.slice.SliceItem;
import android.slice.SliceQuery;
import android.slice.views.LargeSliceAdapter.SliceViewHolder;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.FrameLayout;

import com.android.internal.R;
import com.android.internal.widget.RecyclerView;
import com.android.internal.widget.RecyclerView.ViewHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @hide
 */
public class LargeSliceAdapter extends RecyclerView.Adapter<SliceViewHolder> {

    public static final int TYPE_DEFAULT       = 1;
    public static final int TYPE_HEADER        = 2;
    public static final int TYPE_GRID          = 3;
    public static final int TYPE_MESSAGE       = 4;
    public static final int TYPE_MESSAGE_LOCAL = 5;
    public static final int TYPE_REMOTE_VIEWS  = 6;

    private final IdGenerator mIdGen = new IdGenerator();
    private final Context mContext;
    private List<SliceWrapper> mSlices = new ArrayList<>();
    private SliceItem mColor;

    public LargeSliceAdapter(Context context) {
        mContext = context;
        setHasStableIds(true);
    }

    /**
     * Set the {@link SliceItem}'s to be displayed in the adapter and the accent color.
     */
    public void setSliceItems(List<SliceItem> slices, SliceItem color) {
        mColor = color;
        mIdGen.resetUsage();
        mSlices = slices.stream().map(s -> new SliceWrapper(s, mIdGen))
                .collect(Collectors.toList());
        notifyDataSetChanged();
    }

    @Override
    public SliceViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = inflateforType(viewType);
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return new SliceViewHolder(v);
    }

    @Override
    public int getItemViewType(int position) {
        return mSlices.get(position).mType;
    }

    @Override
    public long getItemId(int position) {
        return mSlices.get(position).mId;
    }

    @Override
    public int getItemCount() {
        return mSlices.size();
    }

    @Override
    public void onBindViewHolder(SliceViewHolder holder, int position) {
        SliceWrapper slice = mSlices.get(position);
        if (holder.mSliceView != null) {
            holder.mSliceView.setColor(mColor);
            holder.mSliceView.setSliceItem(slice.mItem);
        } else if (slice.mType == TYPE_REMOTE_VIEWS) {
            FrameLayout frame = (FrameLayout) holder.itemView;
            frame.removeAllViews();
            frame.addView(slice.mItem.getRemoteView().apply(mContext, frame));
        }
    }

    private View inflateforType(int viewType) {
        switch (viewType) {
            case TYPE_REMOTE_VIEWS:
                return new FrameLayout(mContext);
            case TYPE_GRID:
                return LayoutInflater.from(mContext).inflate(R.layout.slice_grid, null);
            case TYPE_MESSAGE:
                return LayoutInflater.from(mContext).inflate(R.layout.slice_message, null);
            case TYPE_MESSAGE_LOCAL:
                return LayoutInflater.from(mContext).inflate(R.layout.slice_message_local, null);
        }
        return new SmallTemplateView(mContext);
    }

    protected static class SliceWrapper {
        private final SliceItem mItem;
        private final int mType;
        private final long mId;

        public SliceWrapper(SliceItem item, IdGenerator idGen) {
            mItem = item;
            mType = getType(item);
            mId = idGen.getId(item);
        }

        public static int getType(SliceItem item) {
            if (item.getType() == SliceItem.TYPE_REMOTE_VIEW) {
                return TYPE_REMOTE_VIEWS;
            }
            if (item.hasHint(Slice.HINT_MESSAGE)) {
                // TODO: Better way to determine me or not? Something more like Messaging style.
                if (SliceQuery.find(item, -1, Slice.HINT_SOURCE, null) != null) {
                    return TYPE_MESSAGE;
                } else {
                    return TYPE_MESSAGE_LOCAL;
                }
            }
            if (item.hasHint(Slice.HINT_HORIZONTAL)) {
                return TYPE_GRID;
            }
            return TYPE_DEFAULT;
        }
    }

    /**
     * A {@link ViewHolder} for presenting slices in {@link LargeSliceAdapter}.
     */
    public static class SliceViewHolder extends ViewHolder {
        public final SliceListView mSliceView;

        public SliceViewHolder(View itemView) {
            super(itemView);
            mSliceView = itemView instanceof SliceListView ? (SliceListView) itemView : null;
        }
    }

    /**
     * View slices being displayed in {@link LargeSliceAdapter}.
     */
    public interface SliceListView {
        /**
         * Set the slice item for this view.
         */
        void setSliceItem(SliceItem slice);

        /**
         * Set the color for the items in this view.
         */
        default void setColor(SliceItem color) {

        }
    }

    private static class IdGenerator {
        private long mNextLong = 0;
        private final ArrayMap<String, Long> mCurrentIds = new ArrayMap<>();
        private final ArrayMap<String, Integer> mUsedIds = new ArrayMap<>();

        public long getId(SliceItem item) {
            String str = genString(item);
            if (!mCurrentIds.containsKey(str)) {
                mCurrentIds.put(str, mNextLong++);
            }
            long id = mCurrentIds.get(str);
            int index = mUsedIds.getOrDefault(str, 0);
            mUsedIds.put(str, index + 1);
            return id + index * 10000;
        }

        private String genString(SliceItem item) {
            StringBuilder builder = new StringBuilder();
            SliceQuery.stream(item).forEach(i -> {
                builder.append(i.getType());
                i.removeHint(Slice.HINT_SELECTED);
                builder.append(i.getHints());
                switch (i.getType()) {
                    case SliceItem.TYPE_REMOTE_VIEW:
                        builder.append(i.getRemoteView());
                        break;
                    case SliceItem.TYPE_IMAGE:
                        builder.append(i.getIcon());
                        break;
                    case SliceItem.TYPE_TEXT:
                        builder.append(i.getText());
                        break;
                    case SliceItem.TYPE_COLOR:
                        builder.append(i.getColor());
                        break;
                }
            });
            return builder.toString();
        }

        public void resetUsage() {
            mUsedIds.clear();
        }
    }
}
