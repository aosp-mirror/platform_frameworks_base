/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import android.content.Context;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.documentsui.NavigationViewManager.Breadcrumb;
import com.android.documentsui.NavigationViewManager.Environment;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;

import java.util.function.Consumer;

/**
 * Horizontal implementation of breadcrumb used for tablet / desktop device layouts
 */
public final class HorizontalBreadcrumb extends RecyclerView
        implements Breadcrumb, ItemDragListener.DragHost {

    private static final int USER_NO_SCROLL_OFFSET_THRESHOLD = 5;

    private LinearLayoutManager mLayoutManager;
    private BreadcrumbAdapter mAdapter;
    private Consumer<Integer> mListener;

    public HorizontalBreadcrumb(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public HorizontalBreadcrumb(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public HorizontalBreadcrumb(Context context) {
        super(context);
    }

    @Override
    public void setup(Environment env,
            com.android.documentsui.State state,
            Consumer<Integer> listener) {

        mListener = listener;
        mLayoutManager = new LinearLayoutManager(
                getContext(), LinearLayoutManager.HORIZONTAL, false);
        mAdapter = new BreadcrumbAdapter(
                state, env, new ItemDragListener<>(this));

        setLayoutManager(mLayoutManager);
        addOnItemTouchListener(new ClickListener(getContext(), this::onSingleTapUp));
    }

    @Override
    public void show(boolean visibility) {
        if (visibility) {
            setVisibility(VISIBLE);
            boolean shouldScroll = !hasUserDefineScrollOffset();
            if (getAdapter() == null) {
                setAdapter(mAdapter);
            } else {
                int currentItemCount = mAdapter.getItemCount();
                int lastItemCount = mAdapter.getLastItemSize();
                if (currentItemCount > lastItemCount) {
                    mAdapter.notifyItemRangeInserted(lastItemCount,
                            currentItemCount - lastItemCount);
                    mAdapter.notifyItemChanged(lastItemCount - 1);
                } else if (currentItemCount < lastItemCount) {
                    mAdapter.notifyItemRangeRemoved(currentItemCount,
                            lastItemCount - currentItemCount);
                    mAdapter.notifyItemChanged(currentItemCount - 1);
                }
            }
            if (shouldScroll) {
                mLayoutManager.scrollToPosition(mAdapter.getItemCount() - 1);
            }
        } else {
            setVisibility(GONE);
            setAdapter(null);
        }
        mAdapter.updateLastItemSize();
    }

    private boolean hasUserDefineScrollOffset() {
        final int maxOffset = computeHorizontalScrollRange() - computeHorizontalScrollExtent();
        return (maxOffset - computeHorizontalScrollOffset() > USER_NO_SCROLL_OFFSET_THRESHOLD);
    }

    @Override
    public void postUpdate() {
    }

    @Override
    public void runOnUiThread(Runnable runnable) {
        post(runnable);
    }

    @Override
    public void setDropTargetHighlight(View v, boolean highlight) {
        RecyclerView.ViewHolder vh = getChildViewHolder(v);
        if (vh instanceof BreadcrumbHolder) {
            ((BreadcrumbHolder) vh).setHighlighted(highlight);
        }
    }

    @Override
    public void onViewHovered(View v) {
        int pos = getChildAdapterPosition(v);
        if (pos != mAdapter.getItemCount() - 1) {
            mListener.accept(pos);
        }
    }

    private void onSingleTapUp(MotionEvent e) {
        View itemView = findChildViewUnder(e.getX(), e.getY());
        int pos = getChildAdapterPosition(itemView);
        if (pos != mAdapter.getItemCount() - 1) {
            mListener.accept(pos);
        }
    }

    private static final class BreadcrumbAdapter
            extends RecyclerView.Adapter<BreadcrumbHolder> {

        private final Environment mEnv;
        private final com.android.documentsui.State mState;
        private final OnDragListener mDragListener;
        // We keep the old item size so the breadcrumb will only re-render views that are necessary
        private int mLastItemSize;

        public BreadcrumbAdapter(com.android.documentsui.State state,
                Environment env,
                OnDragListener dragListener) {
            mState = state;
            mEnv = env;
            mDragListener = dragListener;
            mLastItemSize = mState.stack.size();
        }

        @Override
        public BreadcrumbHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.navigation_breadcrumb_item, null);
            return new BreadcrumbHolder(v);
        }

        @Override
        public void onBindViewHolder(BreadcrumbHolder holder, int position) {
            final DocumentInfo doc = getItem(position);
            final int horizontalPadding = (int) holder.itemView.getResources()
                    .getDimension(R.dimen.breadcrumb_item_padding);

            if (position == 0) {
                final RootInfo root = mEnv.getCurrentRoot();
                holder.title.setText(root.title);
                holder.title.setPadding(0, 0, horizontalPadding, 0);
            } else {
                holder.title.setText(doc.displayName);
                holder.title.setPadding(horizontalPadding, 0, horizontalPadding, 0);
            }

            if (position == getItemCount() - 1) {
                holder.arrow.setVisibility(View.GONE);
            } else {
                holder.arrow.setVisibility(View.VISIBLE);
            }
            holder.itemView.setOnDragListener(mDragListener);
        }

        private DocumentInfo getItem(int position) {
            return mState.stack.get(mState.stack.size() - position - 1);
        }

        @Override
        public int getItemCount() {
            return mState.stack.size();
        }

        public int getLastItemSize() {
            return mLastItemSize;
        }

        public void updateLastItemSize() {
            mLastItemSize = mState.stack.size();
        }
    }

    private static class BreadcrumbHolder extends RecyclerView.ViewHolder {

        protected DragOverTextView title;
        protected ImageView arrow;

        public BreadcrumbHolder(View itemView) {
            super(itemView);
            title = (DragOverTextView) itemView.findViewById(R.id.breadcrumb_text);
            arrow = (ImageView) itemView.findViewById(R.id.breadcrumb_arrow);
        }

        /**
         * Highlights the associated item view.
         * @param highlighted
         */
        public void setHighlighted(boolean highlighted) {
            title.setHighlight(highlighted);
        }
    }

    private static final class ClickListener extends GestureDetector
            implements OnItemTouchListener {

        public ClickListener(Context context, Consumer<MotionEvent> listener) {
            super(context, new SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    listener.accept(e);
                    return true;
                }
            });
        }

        @Override
        public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
            onTouchEvent(e);
            return false;
        }

        @Override
        public void onTouchEvent(RecyclerView rv, MotionEvent e) {
            onTouchEvent(e);
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        }
    }
}
