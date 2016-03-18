/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.customize;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.GridLayoutManager.SpanSizeLookup;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ItemDecoration;
import android.support.v7.widget.RecyclerView.State;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.android.systemui.R;
import com.android.systemui.qs.QSIconView;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.customize.TileAdapter.Holder;
import com.android.systemui.qs.customize.TileQueryHelper.TileInfo;
import com.android.systemui.qs.customize.TileQueryHelper.TileStateListener;
import com.android.systemui.statusbar.phone.QSTileHost;

import java.util.ArrayList;
import java.util.List;

public class TileAdapter extends RecyclerView.Adapter<Holder> implements TileStateListener {

    private static final long DRAG_LENGTH = 100;
    private static final float DRAG_SCALE = 1.2f;
    public static final long MOVE_DURATION = 150;

    private static final int TYPE_TILE = 0;
    private static final int TYPE_EDIT = 1;

    private final Context mContext;

    private final Handler mHandler = new Handler();
    private final List<TileInfo> mTiles = new ArrayList<>();
    private final ItemTouchHelper mItemTouchHelper;
    private int mDividerIndex;
    private List<String> mCurrentSpecs;
    private List<TileInfo> mOtherTiles;
    private List<TileInfo> mAllTiles;

    private Holder mCurrentDrag;

    public TileAdapter(Context context) {
        mContext = context;
        mItemTouchHelper = new ItemTouchHelper(mCallbacks);
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return mTiles.get(position) != null ? mAllTiles.indexOf(mTiles.get(position)) : -1;
    }

    public ItemTouchHelper getItemTouchHelper() {
        return mItemTouchHelper;
    }

    public ItemDecoration getItemDecoration() {
        return mDecoration;
    }

    public void saveSpecs(QSTileHost host) {
        List<String> newSpecs = new ArrayList<>();
        for (int i = 0; mTiles.get(i) != null; i++) {
            newSpecs.add(mTiles.get(i).spec);
        }
        host.changeTiles(mCurrentSpecs, newSpecs);
        setTileSpecs(newSpecs);
    }

    public void setTileSpecs(List<String> currentSpecs) {
        mCurrentSpecs = currentSpecs;
        recalcSpecs();
    }

    @Override
    public void onTilesChanged(List<TileInfo> tiles) {
        mAllTiles = tiles;
        recalcSpecs();
    }

    private void recalcSpecs() {
        if (mCurrentSpecs == null || mAllTiles == null) {
            return;
        }
        mOtherTiles = new ArrayList<TileInfo>(mAllTiles);
        mTiles.clear();
        for (int i = 0; i < mCurrentSpecs.size(); i++) {
            final TileInfo tile = getAndRemoveOther(mCurrentSpecs.get(i));
            if (tile != null) {
                mTiles.add(tile);
            }
        }
        mTiles.add(null);
        mTiles.addAll(mOtherTiles);
        mDividerIndex = mTiles.indexOf(null);
        notifyDataSetChanged();
    }

    private TileInfo getAndRemoveOther(String s) {
        for (int i = 0; i < mOtherTiles.size(); i++) {
            if (mOtherTiles.get(i).spec.equals(s)) {
                return mOtherTiles.remove(i);
            }
        }
        return null;
    }

    @Override
    public int getItemViewType(int position) {
        if (mTiles.get(position) == null) {
            return TYPE_EDIT;
        }
        return TYPE_TILE;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        final Context context = parent.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == 1) {
            return new Holder(inflater.inflate(R.layout.qs_customize_divider, parent, false));
        }
        FrameLayout frame = (FrameLayout) inflater.inflate(R.layout.qs_customize_tile_frame, parent,
                false);
        frame.addView(new QSTileView(context, new QSIconView(context)));
        return new Holder(frame);
    }

    @Override
    public int getItemCount() {
        return mTiles.size();
    }

    @Override
    public void onBindViewHolder(final Holder holder, int position) {
        if (holder.getItemViewType() == TYPE_EDIT) {
            ((TextView) holder.itemView.findViewById(android.R.id.title)).setText(
                    mCurrentDrag != null ? R.string.drag_to_remove_tiles
                    : R.string.drag_to_add_tiles);
            return;
        }

        TileInfo info = mTiles.get(position);
        holder.mTileView.onStateChanged(info.state);
    }

    public SpanSizeLookup getSizeLookup() {
        return mSizeLookup;
    }

    public class Holder extends ViewHolder {
        private QSTileView mTileView;

        public Holder(View itemView) {
            super(itemView);
            if (itemView instanceof FrameLayout) {
                mTileView = (QSTileView) ((FrameLayout) itemView).getChildAt(0);
                mTileView.setBackground(null);
                mTileView.getIcon().disableAnimation();
            }
        }

        public void startDrag() {
            itemView.animate()
                    .setDuration(DRAG_LENGTH)
                    .scaleX(DRAG_SCALE)
                    .scaleY(DRAG_SCALE);
            mTileView.findViewById(R.id.tile_label).animate()
                    .setDuration(DRAG_LENGTH)
                    .alpha(0);
        }

        public void stopDrag() {
            itemView.animate()
                    .setDuration(DRAG_LENGTH)
                    .scaleX(1)
                    .scaleY(1);
            mTileView.findViewById(R.id.tile_label).animate()
                    .setDuration(DRAG_LENGTH)
                    .alpha(1);
        }
    }

    private final SpanSizeLookup mSizeLookup = new SpanSizeLookup() {
        @Override
        public int getSpanSize(int position) {
            return getItemViewType(position) == TYPE_EDIT ? 3 : 1;
        }
    };

    private final ItemDecoration mDecoration = new ItemDecoration() {
        // TODO: Move this to resource.
        private final ColorDrawable mDrawable = new ColorDrawable(0xff384248);

        @Override
        public void onDraw(Canvas c, RecyclerView parent, State state) {
            super.onDraw(c, parent, state);

            final int childCount = parent.getChildCount();
            final int width = parent.getWidth();
            final int bottom = parent.getBottom();
            for (int i = 0; i < childCount; i++) {
                final View child = parent.getChildAt(i);
                final ViewHolder holder = parent.getChildViewHolder(child);
                if (holder.getAdapterPosition() < mDividerIndex) {
                    continue;
                }

                final RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) child
                        .getLayoutParams();
                final int top = child.getTop() + params.topMargin +
                        Math.round(ViewCompat.getTranslationY(child));
                // Draw full width, in case there aren't tiles all the way across.
                mDrawable.setBounds(0, top, width, bottom);
                mDrawable.draw(c);
                break;
            }
        }
    };

    private final ItemTouchHelper.Callback mCallbacks = new ItemTouchHelper.Callback() {

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return false;
        }

        @Override
        public void onSelectedChanged(ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (mCurrentDrag != null) {
                mCurrentDrag.stopDrag();
                mCurrentDrag = null;
            }
            if (viewHolder != null) {
                mCurrentDrag = (Holder) viewHolder;
                mCurrentDrag.startDrag();
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyItemChanged(mDividerIndex);
                }
            });
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, ViewHolder viewHolder) {
            if (viewHolder.getItemViewType() == TYPE_EDIT) {
                return makeMovementFlags(0, 0);
            }
            int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.RIGHT
                    | ItemTouchHelper.LEFT;
            return makeMovementFlags(dragFlags, 0);
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, ViewHolder viewHolder, ViewHolder target) {
            int from = viewHolder.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (to > mDividerIndex) {
                if (from >= mDividerIndex) {
                    return false;
                }
            }
            move(from, to, mTiles);
            mDividerIndex = mTiles.indexOf(null);
            notifyItemMoved(from, to);
            return true;
        }

        private <T> void move(int from, int to, List<T> list) {
            list.add(from > to ? to : to + 1, list.get(from));
            list.remove(from > to ? from + 1 : from);
        }

        @Override
        public void onSwiped(ViewHolder viewHolder, int direction) {
        }
    };
}
