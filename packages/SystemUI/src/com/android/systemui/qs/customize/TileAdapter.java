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

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import androidx.recyclerview.widget.RecyclerView.State;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.qs.QSEditEvent;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.customize.TileAdapter.Holder;
import com.android.systemui.qs.customize.TileQueryHelper.TileInfo;
import com.android.systemui.qs.customize.TileQueryHelper.TileStateListener;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.dagger.QSThemedContext;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.tileimpl.QSIconViewImpl;
import com.android.systemui.qs.tileimpl.QSTileViewImpl;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/** */
@QSScope
public class TileAdapter extends RecyclerView.Adapter<Holder> implements TileStateListener {
    private static final long DRAG_LENGTH = 100;
    private static final float DRAG_SCALE = 1.2f;
    public static final long MOVE_DURATION = 150;

    private static final int TYPE_TILE = 0;
    private static final int TYPE_EDIT = 1;
    private static final int TYPE_ACCESSIBLE_DROP = 2;
    private static final int TYPE_HEADER = 3;
    private static final int TYPE_DIVIDER = 4;

    private static final long EDIT_ID = 10000;
    private static final long DIVIDER_ID = 20000;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_ADD = 1;
    private static final int ACTION_MOVE = 2;

    private static final int NUM_COLUMNS_ID = R.integer.quick_settings_num_columns;

    private final Context mContext;

    private final Handler mHandler = new Handler();
    private final List<TileInfo> mTiles = new ArrayList<>();
    private final ItemTouchHelper mItemTouchHelper;
    private ItemDecoration mDecoration;
    private final MarginTileDecoration mMarginDecoration;
    private final int mMinNumTiles;
    private final QSTileHost mHost;
    private int mEditIndex;
    private int mTileDividerIndex;
    private int mFocusIndex;

    private boolean mNeedsFocus;
    private List<String> mCurrentSpecs;
    private List<TileInfo> mOtherTiles;
    private List<TileInfo> mAllTiles;

    private Holder mCurrentDrag;
    private int mAccessibilityAction = ACTION_NONE;
    private int mAccessibilityFromIndex;
    private final UiEventLogger mUiEventLogger;
    private final AccessibilityDelegateCompat mAccessibilityDelegate;
    private RecyclerView mRecyclerView;
    private int mNumColumns;

    @Inject
    public TileAdapter(
            @QSThemedContext Context context,
            QSTileHost qsHost,
            UiEventLogger uiEventLogger) {
        mContext = context;
        mHost = qsHost;
        mUiEventLogger = uiEventLogger;
        mItemTouchHelper = new ItemTouchHelper(mCallbacks);
        mDecoration = new TileItemDecoration(context);
        mMarginDecoration = new MarginTileDecoration();
        mMinNumTiles = context.getResources().getInteger(R.integer.quick_settings_min_num_tiles);
        mNumColumns = context.getResources().getInteger(NUM_COLUMNS_ID);
        mAccessibilityDelegate = new TileAdapterDelegate();
        mSizeLookup.setSpanIndexCacheEnabled(true);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        mRecyclerView = null;
    }

    /**
     * Update the number of columns to show, from resources.
     *
     * @return {@code true} if the number of columns changed, {@code false} otherwise
     */
    public boolean updateNumColumns() {
        int numColumns = mContext.getResources().getInteger(NUM_COLUMNS_ID);
        if (numColumns != mNumColumns) {
            mNumColumns = numColumns;
            return true;
        } else {
            return false;
        }
    }

    public int getNumColumns() {
        return mNumColumns;
    }

    public ItemTouchHelper getItemTouchHelper() {
        return mItemTouchHelper;
    }

    public ItemDecoration getItemDecoration() {
        return mDecoration;
    }

    public ItemDecoration getMarginItemDecoration() {
        return mMarginDecoration;
    }

    public void changeHalfMargin(int halfMargin) {
        mMarginDecoration.setHalfMargin(halfMargin);
    }

    public void saveSpecs(QSTileHost host) {
        List<String> newSpecs = new ArrayList<>();
        clearAccessibilityState();
        for (int i = 1; i < mTiles.size() && mTiles.get(i) != null; i++) {
            newSpecs.add(mTiles.get(i).spec);
        }
        host.changeTiles(mCurrentSpecs, newSpecs);
        mCurrentSpecs = newSpecs;
    }

    private void clearAccessibilityState() {
        if (mAccessibilityAction == ACTION_ADD) {
            // Remove blank tile from last spot
            mTiles.remove(--mEditIndex);
            // Update the tile divider position
            notifyDataSetChanged();
        }
        mAccessibilityAction = ACTION_NONE;
    }

    /** */
    public void resetTileSpecs(List<String> specs) {
        // Notify the host so the tiles get removed callbacks.
        mHost.changeTiles(mCurrentSpecs, specs);
        setTileSpecs(specs);
    }

    public void setTileSpecs(List<String> currentSpecs) {
        if (currentSpecs.equals(mCurrentSpecs)) {
            return;
        }
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
        mTiles.add(null);
        for (int i = 0; i < mCurrentSpecs.size(); i++) {
            final TileInfo tile = getAndRemoveOther(mCurrentSpecs.get(i));
            if (tile != null) {
                mTiles.add(tile);
            }
        }
        mTiles.add(null);
        for (int i = 0; i < mOtherTiles.size(); i++) {
            final TileInfo tile = mOtherTiles.get(i);
            if (tile.isSystem) {
                mOtherTiles.remove(i--);
                mTiles.add(tile);
            }
        }
        mTileDividerIndex = mTiles.size();
        mTiles.add(null);
        mTiles.addAll(mOtherTiles);
        updateDividerLocations();
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
        if (position == 0) {
            return TYPE_HEADER;
        }
        if (mAccessibilityAction == ACTION_ADD && position == mEditIndex - 1) {
            return TYPE_ACCESSIBLE_DROP;
        }
        if (position == mTileDividerIndex) {
            return TYPE_DIVIDER;
        }
        if (mTiles.get(position) == null) {
            return TYPE_EDIT;
        }
        return TYPE_TILE;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        final Context context = parent.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == TYPE_HEADER) {
            return new Holder(inflater.inflate(R.layout.qs_customize_header, parent, false));
        }
        if (viewType == TYPE_DIVIDER) {
            return new Holder(inflater.inflate(R.layout.qs_customize_tile_divider, parent, false));
        }
        if (viewType == TYPE_EDIT) {
            return new Holder(inflater.inflate(R.layout.qs_customize_divider, parent, false));
        }
        FrameLayout frame = (FrameLayout) inflater.inflate(R.layout.qs_customize_tile_frame, parent,
                false);
        View view = new CustomizeTileView(context, new QSIconViewImpl(context));
        frame.addView(view);
        return new Holder(frame);
    }

    @Override
    public int getItemCount() {
        return mTiles.size();
    }

    @Override
    public boolean onFailedToRecycleView(Holder holder) {
        holder.stopDrag();
        holder.clearDrag();
        return true;
    }

    private void setSelectableForHeaders(View view) {
        final boolean selectable = mAccessibilityAction == ACTION_NONE;
        view.setFocusable(selectable);
        view.setImportantForAccessibility(selectable
                ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setFocusableInTouchMode(selectable);
    }

    @Override
    public void onBindViewHolder(final Holder holder, int position) {
        if (holder.getItemViewType() == TYPE_HEADER) {
            setSelectableForHeaders(holder.itemView);
            return;
        }
        if (holder.getItemViewType() == TYPE_DIVIDER) {
            holder.itemView.setVisibility(mTileDividerIndex < mTiles.size() - 1 ? View.VISIBLE
                    : View.INVISIBLE);
            return;
        }
        if (holder.getItemViewType() == TYPE_EDIT) {
            final String titleText;
            Resources res = mContext.getResources();
            if (mCurrentDrag == null) {
                titleText = res.getString(R.string.drag_to_add_tiles);
            } else if (!canRemoveTiles() && mCurrentDrag.getAdapterPosition() < mEditIndex) {
                titleText = res.getString(R.string.drag_to_remove_disabled, mMinNumTiles);
            } else {
                titleText = res.getString(R.string.drag_to_remove_tiles);
            }

            ((TextView) holder.itemView.findViewById(android.R.id.title)).setText(titleText);
            setSelectableForHeaders(holder.itemView);

            return;
        }
        if (holder.getItemViewType() == TYPE_ACCESSIBLE_DROP) {
            holder.mTileView.setClickable(true);
            holder.mTileView.setFocusable(true);
            holder.mTileView.setFocusableInTouchMode(true);
            holder.mTileView.setVisibility(View.VISIBLE);
            holder.mTileView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            holder.mTileView.setContentDescription(mContext.getString(
                    R.string.accessibility_qs_edit_tile_add_to_position, position));
            holder.mTileView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectPosition(holder.getLayoutPosition());
                }
            });
            focusOnHolder(holder);
            return;
        }

        TileInfo info = mTiles.get(position);

        final boolean selectable = 0 < position && position < mEditIndex;
        if (selectable && mAccessibilityAction == ACTION_ADD) {
            info.state.contentDescription = mContext.getString(
                    R.string.accessibility_qs_edit_tile_add_to_position, position);
        } else if (selectable && mAccessibilityAction == ACTION_MOVE) {
            info.state.contentDescription = mContext.getString(
                    R.string.accessibility_qs_edit_tile_move_to_position, position);
        } else {
            info.state.contentDescription = info.state.label;
        }
        info.state.expandedAccessibilityClassName = "";

        // The holder has a tileView, therefore this call is not null
        holder.getTileAsCustomizeView().changeState(info.state);
        holder.getTileAsCustomizeView().setShowAppLabel(position > mEditIndex && !info.isSystem);
        // Don't show the side view for third party tiles, as we don't have the actual state.
        holder.getTileAsCustomizeView().setShowSideView(position < mEditIndex || info.isSystem);
        holder.mTileView.setSelected(true);
        holder.mTileView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        holder.mTileView.setClickable(true);
        holder.mTileView.setOnClickListener(null);
        holder.mTileView.setFocusable(true);
        holder.mTileView.setFocusableInTouchMode(true);

        if (mAccessibilityAction != ACTION_NONE) {
            holder.mTileView.setClickable(selectable);
            holder.mTileView.setFocusable(selectable);
            holder.mTileView.setFocusableInTouchMode(selectable);
            holder.mTileView.setImportantForAccessibility(selectable
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (selectable) {
                holder.mTileView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int position = holder.getLayoutPosition();
                        if (position == RecyclerView.NO_POSITION) return;
                        if (mAccessibilityAction != ACTION_NONE) {
                            selectPosition(position);
                        }
                    }
                });
            }
        }
        if (position == mFocusIndex) {
            focusOnHolder(holder);
        }
    }

    private void focusOnHolder(Holder holder) {
        if (mNeedsFocus) {
            // Wait for this to get laid out then set its focus.
            // Ensure that tile gets laid out so we get the callback.
            holder.mTileView.requestLayout();
            holder.mTileView.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    holder.mTileView.removeOnLayoutChangeListener(this);
                    holder.mTileView.requestFocus();
                    if (mAccessibilityAction == ACTION_NONE) {
                        holder.mTileView.clearFocus();
                    }
                }
            });
            mNeedsFocus = false;
            mFocusIndex = RecyclerView.NO_POSITION;
        }
    }

    private boolean canRemoveTiles() {
        return mCurrentSpecs.size() > mMinNumTiles;
    }

    private void selectPosition(int position) {
        if (mAccessibilityAction == ACTION_ADD) {
            // Remove the placeholder.
            mTiles.remove(mEditIndex--);
        }
        mAccessibilityAction = ACTION_NONE;
        move(mAccessibilityFromIndex, position, false);
        mFocusIndex = position;
        mNeedsFocus = true;
        notifyDataSetChanged();
    }

    private void startAccessibleAdd(int position) {
        mAccessibilityFromIndex = position;
        mAccessibilityAction = ACTION_ADD;
        // Add placeholder for last slot.
        mTiles.add(mEditIndex++, null);
        // Update the tile divider position
        mTileDividerIndex++;
        mFocusIndex = mEditIndex - 1;
        mNeedsFocus = true;
        if (mRecyclerView != null) {
            mRecyclerView.post(() -> mRecyclerView.smoothScrollToPosition(mFocusIndex));
        }
        notifyDataSetChanged();
    }

    private void startAccessibleMove(int position) {
        mAccessibilityFromIndex = position;
        mAccessibilityAction = ACTION_MOVE;
        mFocusIndex = position;
        mNeedsFocus = true;
        notifyDataSetChanged();
    }

    private boolean canRemoveFromPosition(int position) {
        return canRemoveTiles() && isCurrentTile(position);
    }

    private boolean isCurrentTile(int position) {
        return position < mEditIndex;
    }

    private boolean canAddFromPosition(int position) {
        return position > mEditIndex;
    }

    private boolean addFromPosition(int position) {
        if (!canAddFromPosition(position)) return false;
        move(position, mEditIndex);
        return true;
    }

    private boolean removeFromPosition(int position) {
        if (!canRemoveFromPosition(position)) return false;
        TileInfo info = mTiles.get(position);
        move(position, info.isSystem ? mEditIndex : mTileDividerIndex);
        return true;
    }

    public SpanSizeLookup getSizeLookup() {
        return mSizeLookup;
    }

    private boolean move(int from, int to) {
        return move(from, to, true);
    }

    private boolean move(int from, int to, boolean notify) {
        if (to == from) {
            return true;
        }
        move(from, to, mTiles, notify);
        updateDividerLocations();
        if (to >= mEditIndex) {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_REMOVE, 0, strip(mTiles.get(to)));
        } else if (from >= mEditIndex) {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_ADD, 0, strip(mTiles.get(to)));
        } else {
            mUiEventLogger.log(QSEditEvent.QS_EDIT_MOVE, 0, strip(mTiles.get(to)));
        }
        saveSpecs(mHost);
        return true;
    }

    private void updateDividerLocations() {
        // The first null is the header label (index 0) so we can skip it,
        // the second null is the edit tiles label, the third null is the tile divider.
        // If there is no third null, then there are no non-system tiles.
        mEditIndex = -1;
        mTileDividerIndex = mTiles.size();
        for (int i = 1; i < mTiles.size(); i++) {
            if (mTiles.get(i) == null) {
                if (mEditIndex == -1) {
                    mEditIndex = i;
                } else {
                    mTileDividerIndex = i;
                }
            }
        }
        if (mTiles.size() - 1 == mTileDividerIndex) {
            notifyItemChanged(mTileDividerIndex);
        }
    }

    private static String strip(TileInfo tileInfo) {
        String spec = tileInfo.spec;
        if (spec.startsWith(CustomTile.PREFIX)) {
            ComponentName component = CustomTile.getComponentFromSpec(spec);
            return component.getPackageName();
        }
        return spec;
    }

    private <T> void move(int from, int to, List<T> list, boolean notify) {
        list.add(to, list.remove(from));
        if (notify) {
            notifyItemMoved(from, to);
        }
    }

    public class Holder extends ViewHolder {
        private QSTileViewImpl mTileView;

        public Holder(View itemView) {
            super(itemView);
            if (itemView instanceof FrameLayout) {
                mTileView = (QSTileViewImpl) ((FrameLayout) itemView).getChildAt(0);
                mTileView.getIcon().disableAnimation();
                mTileView.setTag(this);
                ViewCompat.setAccessibilityDelegate(mTileView, mAccessibilityDelegate);
            }
        }

        @Nullable
        public CustomizeTileView getTileAsCustomizeView() {
            return (CustomizeTileView) mTileView;
        }

        public void clearDrag() {
            itemView.clearAnimation();
        }

        public void startDrag() {
            itemView.animate()
                    .setDuration(DRAG_LENGTH)
                    .scaleX(DRAG_SCALE)
                    .scaleY(DRAG_SCALE);
        }

        public void stopDrag() {
            itemView.animate()
                    .setDuration(DRAG_LENGTH)
                    .scaleX(1)
                    .scaleY(1);
        }

        boolean canRemove() {
            return canRemoveFromPosition(getLayoutPosition());
        }

        boolean canAdd() {
            return canAddFromPosition(getLayoutPosition());
        }

        void toggleState() {
            if (canAdd()) {
                add();
            } else {
                remove();
            }
        }

        private void add() {
            if (addFromPosition(getLayoutPosition())) {
                itemView.announceForAccessibility(
                        itemView.getContext().getText(R.string.accessibility_qs_edit_tile_added));
            }
        }

        private void remove() {
            if (removeFromPosition(getLayoutPosition())) {
                itemView.announceForAccessibility(
                        itemView.getContext().getText(R.string.accessibility_qs_edit_tile_removed));
            }
        }

        boolean isCurrentTile() {
            return TileAdapter.this.isCurrentTile(getLayoutPosition());
        }

        void startAccessibleAdd() {
            TileAdapter.this.startAccessibleAdd(getLayoutPosition());
        }

        void startAccessibleMove() {
            TileAdapter.this.startAccessibleMove(getLayoutPosition());
        }

        boolean canTakeAccessibleAction() {
            return mAccessibilityAction == ACTION_NONE;
        }
    }

    private final SpanSizeLookup mSizeLookup = new SpanSizeLookup() {
        @Override
        public int getSpanSize(int position) {
            final int type = getItemViewType(position);
            if (type == TYPE_EDIT || type == TYPE_DIVIDER || type == TYPE_HEADER) {
                return mNumColumns;
            } else {
                return 1;
            }
        }
    };

    private class TileItemDecoration extends ItemDecoration {
        private final Drawable mDrawable;

        private TileItemDecoration(Context context) {
            mDrawable = context.getDrawable(R.drawable.qs_customize_tile_decoration);
        }

        @Override
        public void onDraw(Canvas c, RecyclerView parent, State state) {
            super.onDraw(c, parent, state);

            final int childCount = parent.getChildCount();
            final int width = parent.getWidth();
            final int bottom = parent.getBottom();
            for (int i = 0; i < childCount; i++) {
                final View child = parent.getChildAt(i);
                final ViewHolder holder = parent.getChildViewHolder(child);
                // Do not draw background for the holder that's currently being dragged
                if (holder == mCurrentDrag) {
                    continue;
                }
                // Do not draw background for holders before the edit index (header and current
                // tiles)
                if (holder.getAdapterPosition() == 0 ||
                        holder.getAdapterPosition() < mEditIndex && !(child instanceof TextView)) {
                    continue;
                }

                final int top = child.getTop() + Math.round(ViewCompat.getTranslationY(child));
                mDrawable.setBounds(0, top, width, bottom);
                mDrawable.draw(c);
                break;
            }
        }
    }

    private static class MarginTileDecoration extends ItemDecoration {
        private int mHalfMargin;

        public void setHalfMargin(int halfMargin) {
            mHalfMargin = halfMargin;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                @NonNull RecyclerView parent, @NonNull State state) {
            if (parent.getLayoutManager() == null) return;

            GridLayoutManager lm = ((GridLayoutManager) parent.getLayoutManager());
            int column = ((GridLayoutManager.LayoutParams) view.getLayoutParams()).getSpanIndex();

            if (view instanceof TextView) {
                super.getItemOffsets(outRect, view, parent, state);
            } else {
                if (column != 0 && column != lm.getSpanCount() - 1) {
                    // In a column that's not leftmost or rightmost (half of the margin between
                    // columns).
                    outRect.left = mHalfMargin;
                    outRect.right = mHalfMargin;
                } else {
                    // Leftmost or rightmost column
                    if (parent.isLayoutRtl()) {
                        if (column == 0) {
                            // Rightmost column
                            outRect.left = mHalfMargin;
                            outRect.right = 0;
                        } else {
                            // Leftmost column
                            outRect.left = 0;
                            outRect.right = mHalfMargin;
                        }
                    } else {
                        // Non RTL
                        if (column == 0) {
                            // Leftmost column
                            outRect.left = 0;
                            outRect.right = mHalfMargin;
                        } else {
                            // Rightmost column
                            outRect.left = mHalfMargin;
                            outRect.right = 0;
                        }
                    }
                }
            }
        }
    }

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
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder = null;
            }
            if (viewHolder == mCurrentDrag) return;
            if (mCurrentDrag != null) {
                int position = mCurrentDrag.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;
                TileInfo info = mTiles.get(position);
                ((CustomizeTileView) mCurrentDrag.mTileView).setShowAppLabel(
                        position > mEditIndex && !info.isSystem);
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
                    notifyItemChanged(mEditIndex);
                }
            });
        }

        @Override
        public boolean canDropOver(RecyclerView recyclerView, ViewHolder current,
                ViewHolder target) {
            final int position = target.getAdapterPosition();
            if (position == 0 || position == RecyclerView.NO_POSITION){
                return false;
            }
            if (!canRemoveTiles() && current.getAdapterPosition() < mEditIndex) {
                return position < mEditIndex;
            }
            return position <= mEditIndex + 1;
        }

        @Override
        public int getMovementFlags(RecyclerView recyclerView, ViewHolder viewHolder) {
            switch (viewHolder.getItemViewType()) {
                case TYPE_EDIT:
                case TYPE_DIVIDER:
                case TYPE_HEADER:
                    // Fall through
                    return makeMovementFlags(0, 0);
                default:
                    int dragFlags = ItemTouchHelper.UP | ItemTouchHelper.DOWN
                            | ItemTouchHelper.RIGHT | ItemTouchHelper.LEFT;
                    return makeMovementFlags(dragFlags, 0);
            }
        }

        @Override
        public boolean onMove(RecyclerView recyclerView, ViewHolder viewHolder, ViewHolder target) {
            int from = viewHolder.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from == 0 || from == RecyclerView.NO_POSITION ||
                    to == 0 || to == RecyclerView.NO_POSITION) {
                return false;
            }
            return move(from, to);
        }

        @Override
        public void onSwiped(ViewHolder viewHolder, int direction) {
        }
    };
}
