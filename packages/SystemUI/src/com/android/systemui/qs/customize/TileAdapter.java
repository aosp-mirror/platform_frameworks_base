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

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
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
import android.view.View.OnClickListener;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto;
import com.android.systemui.R;
import com.android.systemui.qs.QSIconView;
import com.android.systemui.qs.customize.TileAdapter.Holder;
import com.android.systemui.qs.customize.TileQueryHelper.TileInfo;
import com.android.systemui.qs.customize.TileQueryHelper.TileStateListener;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.ArrayList;
import java.util.List;

public class TileAdapter extends RecyclerView.Adapter<Holder> implements TileStateListener {

    private static final long DRAG_LENGTH = 100;
    private static final float DRAG_SCALE = 1.2f;
    public static final long MOVE_DURATION = 150;

    private static final int TYPE_TILE = 0;
    private static final int TYPE_EDIT = 1;
    private static final int TYPE_ACCESSIBLE_DROP = 2;
    private static final int TYPE_DIVIDER = 4;

    private static final long EDIT_ID = 10000;
    private static final long DIVIDER_ID = 20000;

    private final Context mContext;

    private final Handler mHandler = new Handler();
    private final List<TileInfo> mTiles = new ArrayList<>();
    private final ItemTouchHelper mItemTouchHelper;
    private final ItemDecoration mDecoration;
    private final AccessibilityManager mAccessibilityManager;
    private int mEditIndex;
    private int mTileDividerIndex;
    private boolean mNeedsFocus;
    private List<String> mCurrentSpecs;
    private List<TileInfo> mOtherTiles;
    private List<TileInfo> mAllTiles;

    private Holder mCurrentDrag;
    private boolean mAccessibilityMoving;
    private int mAccessibilityFromIndex;
    private QSTileHost mHost;

    public TileAdapter(Context context) {
        mContext = context;
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mItemTouchHelper = new ItemTouchHelper(mCallbacks);
        mDecoration = new TileItemDecoration(context);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
    }

    public ItemTouchHelper getItemTouchHelper() {
        return mItemTouchHelper;
    }

    public ItemDecoration getItemDecoration() {
        return mDecoration;
    }

    public void saveSpecs(QSTileHost host) {
        List<String> newSpecs = new ArrayList<>();
        for (int i = 0; i < mTiles.size() && mTiles.get(i) != null; i++) {
            newSpecs.add(mTiles.get(i).spec);
        }
        host.changeTiles(mCurrentSpecs, newSpecs);
        mCurrentSpecs = newSpecs;
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
        if (mAccessibilityMoving && position == mEditIndex - 1) {
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
        if (viewType == TYPE_DIVIDER) {
            return new Holder(inflater.inflate(R.layout.qs_customize_tile_divider, parent, false));
        }
        if (viewType == TYPE_EDIT) {
            return new Holder(inflater.inflate(R.layout.qs_customize_divider, parent, false));
        }
        FrameLayout frame = (FrameLayout) inflater.inflate(R.layout.qs_customize_tile_frame, parent,
                false);
        frame.addView(new CustomizeTileView(context, new QSIconView(context)));
        return new Holder(frame);
    }

    @Override
    public int getItemCount() {
        return mTiles.size();
    }

    @Override
    public boolean onFailedToRecycleView(Holder holder) {
        holder.clearDrag();
        return true;
    }

    @Override
    public void onBindViewHolder(final Holder holder, int position) {
        if (holder.getItemViewType() == TYPE_DIVIDER) {
            holder.itemView.setVisibility(mTileDividerIndex < mTiles.size() - 1 ? View.VISIBLE
                    : View.INVISIBLE);
            return;
        }
        if (holder.getItemViewType() == TYPE_EDIT) {
            ((TextView) holder.itemView.findViewById(android.R.id.title)).setText(
                    mCurrentDrag != null ? R.string.drag_to_remove_tiles
                    : R.string.drag_to_add_tiles);
            return;
        }
        if (holder.getItemViewType() == TYPE_ACCESSIBLE_DROP) {
            holder.mTileView.setClickable(true);
            holder.mTileView.setFocusable(true);
            holder.mTileView.setFocusableInTouchMode(true);
            holder.mTileView.setVisibility(View.VISIBLE);
            holder.mTileView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            holder.mTileView.setContentDescription(mContext.getString(
                    R.string.accessibility_qs_edit_position_label, position + 1));
            holder.mTileView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectPosition(holder.getAdapterPosition(), v);
                }
            });
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
                    }
                });
                mNeedsFocus = false;
            }
            return;
        }

        TileInfo info = mTiles.get(position);

        if (position > mEditIndex) {
            info.state.contentDescription = mContext.getString(
                    R.string.accessibility_qs_edit_add_tile_label, info.state.label);
        } else if (mAccessibilityMoving) {
            info.state.contentDescription = mContext.getString(
                    R.string.accessibility_qs_edit_position_label, position + 1);
        } else {
            info.state.contentDescription = mContext.getString(
                    R.string.accessibility_qs_edit_tile_label, position + 1, info.state.label);
        }
        holder.mTileView.onStateChanged(info.state);
        holder.mTileView.setAppLabel(info.appLabel);
        holder.mTileView.setShowAppLabel(position > mEditIndex && !info.isSystem);

        if (mAccessibilityManager.isTouchExplorationEnabled()) {
            final boolean selectable = !mAccessibilityMoving || position < mEditIndex;
            holder.mTileView.setClickable(selectable);
            holder.mTileView.setFocusable(selectable);
            holder.mTileView.setImportantForAccessibility(selectable
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (selectable) {
                holder.mTileView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int position = holder.getAdapterPosition();
                        if (mAccessibilityMoving) {
                            selectPosition(position, v);
                        } else {
                            if (position < mEditIndex) {
                                showAccessibilityDialog(position, v);
                            } else {
                                startAccessibleDrag(position);
                            }
                        }
                    }
                });
            }
        }
    }

    private void selectPosition(int position, View v) {
        // Remove the placeholder.
        mAccessibilityMoving = false;
        mTiles.remove(mEditIndex--);
        notifyItemRemoved(mEditIndex - 1);
        // Don't remove items when the last position is selected.
        if (position == mEditIndex) position--;

        move(mAccessibilityFromIndex, position, v);
        notifyDataSetChanged();
    }

    private void showAccessibilityDialog(final int position, final View v) {
        final TileInfo info = mTiles.get(position);
        CharSequence[] options = new CharSequence[] {
                mContext.getString(R.string.accessibility_qs_edit_move_tile, info.state.label),
                mContext.getString(R.string.accessibility_qs_edit_remove_tile, info.state.label),
        };
        AlertDialog dialog = new Builder(mContext)
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            startAccessibleDrag(position);
                        } else {
                            move(position, info.isSystem ? mEditIndex : mTileDividerIndex, v);
                            notifyItemChanged(mTileDividerIndex);
                            notifyDataSetChanged();
                        }
                    }
                }).setNegativeButton(android.R.string.cancel, null)
                .create();
        SystemUIDialog.setShowForAllUsers(dialog, true);
        SystemUIDialog.applyFlags(dialog);
        dialog.show();
    }

    private void startAccessibleDrag(int position) {
        mAccessibilityMoving = true;
        mNeedsFocus = true;
        mAccessibilityFromIndex = position;
        // Add placeholder for last slot.
        mTiles.add(mEditIndex++, null);
        notifyDataSetChanged();
    }

    public SpanSizeLookup getSizeLookup() {
        return mSizeLookup;
    }

    private boolean move(int from, int to, View v) {
        if (to == from) {
            return true;
        }
        CharSequence fromLabel = mTiles.get(from).state.label;
        move(from, to, mTiles);
        updateDividerLocations();
        CharSequence announcement;
        if (to >= mEditIndex) {
            MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_REMOVE_SPEC,
                    strip(mTiles.get(to)));
            MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_REMOVE,
                    from);
            announcement = mContext.getString(R.string.accessibility_qs_edit_tile_removed,
                    fromLabel);
        } else if (from >= mEditIndex) {
            MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_ADD_SPEC,
                    strip(mTiles.get(to)));
            MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_ADD,
                    to);
            announcement = mContext.getString(R.string.accessibility_qs_edit_tile_added,
                    fromLabel, (to + 1));
        } else {
            MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_MOVE_SPEC,
                    strip(mTiles.get(to)));
            MetricsLogger.action(mContext, MetricsProto.MetricsEvent.ACTION_QS_EDIT_MOVE,
                    to);
            announcement = mContext.getString(R.string.accessibility_qs_edit_tile_moved,
                    fromLabel, (to + 1));
        }
        v.announceForAccessibility(announcement);
        saveSpecs(mHost);
        return true;
    }

    private void updateDividerLocations() {
        // The first null is the edit tiles label, the second null is the tile divider.
        // If there is no second null, then there are no non-system tiles.
        mEditIndex = -1;
        mTileDividerIndex = mTiles.size();
        for (int i = 0; i < mTiles.size(); i++) {
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

    private <T> void move(int from, int to, List<T> list) {
        list.add(to, list.remove(from));
        notifyItemMoved(from, to);
    }

    public class Holder extends ViewHolder {
        private CustomizeTileView mTileView;

        public Holder(View itemView) {
            super(itemView);
            if (itemView instanceof FrameLayout) {
                mTileView = (CustomizeTileView) ((FrameLayout) itemView).getChildAt(0);
                mTileView.setBackground(null);
                mTileView.getIcon().disableAnimation();
            }
        }

        public void clearDrag() {
            itemView.clearAnimation();
            mTileView.findViewById(R.id.tile_label).clearAnimation();
            mTileView.findViewById(R.id.tile_label).setAlpha(1);
            mTileView.getAppLabel().clearAnimation();
            mTileView.getAppLabel().setAlpha(.6f);
        }

        public void startDrag() {
            itemView.animate()
                    .setDuration(DRAG_LENGTH)
                    .scaleX(DRAG_SCALE)
                    .scaleY(DRAG_SCALE);
            mTileView.findViewById(R.id.tile_label).animate()
                    .setDuration(DRAG_LENGTH)
                    .alpha(0);
            mTileView.getAppLabel().animate()
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
            mTileView.getAppLabel().animate()
                    .setDuration(DRAG_LENGTH)
                    .alpha(.6f);
        }
    }

    private final SpanSizeLookup mSizeLookup = new SpanSizeLookup() {
        @Override
        public int getSpanSize(int position) {
            final int type = getItemViewType(position);
            return type == TYPE_EDIT || type == TYPE_DIVIDER ? 3 : 1;
        }
    };

    private class TileItemDecoration extends ItemDecoration {
        private final ColorDrawable mDrawable;

        private TileItemDecoration(Context context) {
            TypedArray ta =
                    context.obtainStyledAttributes(new int[]{android.R.attr.colorSecondary});
            mDrawable = new ColorDrawable(ta.getColor(0, 0));
            ta.recycle();
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
                if (holder.getAdapterPosition() < mEditIndex && !(child instanceof TextView)) {
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
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder = null;
            }
            if (viewHolder == mCurrentDrag) return;
            if (mCurrentDrag != null) {
                int position = mCurrentDrag.getAdapterPosition();
                TileInfo info = mTiles.get(position);
                mCurrentDrag.mTileView.setShowAppLabel(
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
            return target.getAdapterPosition() <= mEditIndex + 1;
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
            return move(from, to, target.itemView);
        }

        @Override
        public void onSwiped(ViewHolder viewHolder, int direction) {
        }
    };
}
