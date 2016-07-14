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

package com.android.documentsui.dirlist;

import android.content.Context;
import android.graphics.Point;
import android.view.DragEvent;
import android.view.View;
import android.view.View.OnDragListener;

import com.android.documentsui.ItemDragListener;
import com.android.documentsui.ItemDragListener.DragHost;
import com.android.documentsui.dirlist.ViewAutoScroller.ScrollActionDelegate;
import com.android.documentsui.dirlist.ViewAutoScroller.ScrollDistanceDelegate;
import com.android.documentsui.R;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import javax.annotation.Nullable;

/**
 * This class acts as a middle-man handler for potential auto-scrolling before passing the dragEvent
 * onto {@link DirectoryDragListener}.
 */
class DragScrollListener implements OnDragListener {

    private final ItemDragListener<? extends DragHost> mDragHandler;
    private final IntSupplier mHeight;
    private final BooleanSupplier mCanScrollUp;
    private final BooleanSupplier mCanScrollDown;
    private final int mAutoScrollEdgeHeight;
    private final Runnable mDragScroller;

    private boolean mDragHappening;
    private @Nullable Point mCurrentPosition;

    private DragScrollListener(
            Context context,
            ItemDragListener<? extends DragHost> dragHandler,
            IntSupplier heightSupplier,
            BooleanSupplier scrollUpSupplier,
            BooleanSupplier scrollDownSupplier,
            ViewAutoScroller.ScrollActionDelegate actionDelegate) {
        mDragHandler = dragHandler;
        mAutoScrollEdgeHeight = (int) context.getResources()
                .getDimension(R.dimen.autoscroll_edge_height);
        mHeight = heightSupplier;
        mCanScrollUp = scrollUpSupplier;
        mCanScrollDown = scrollDownSupplier;

        ScrollDistanceDelegate distanceDelegate = new ScrollDistanceDelegate() {
            @Override
            public Point getCurrentPosition() {
                return mCurrentPosition;
            }

            @Override
            public int getViewHeight() {
                return mHeight.getAsInt();
            }

            @Override
            public boolean isActive() {
                return mDragHappening;
            }
        };

        mDragScroller = new ViewAutoScroller(
                mAutoScrollEdgeHeight, distanceDelegate, actionDelegate);
    }

    static DragScrollListener create(
            Context context, ItemDragListener<? extends DragHost> dragHandler, View scrollView) {
        ScrollActionDelegate actionDelegate = new ScrollActionDelegate() {
            @Override
            public void scrollBy(int dy) {
                scrollView.scrollBy(0, dy);
            }

            @Override
            public void runAtNextFrame(Runnable r) {
                scrollView.postOnAnimation(r);

            }

            @Override
            public void removeCallback(Runnable r) {
                scrollView.removeCallbacks(r);
            }
        };
        DragScrollListener listener = new DragScrollListener(
                context,
                dragHandler,
                scrollView::getHeight,
                () -> {
                    return scrollView.canScrollVertically(-1);
                },
                () -> {
                    return scrollView.canScrollVertically(1);
                },
                actionDelegate);
        return listener;
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        boolean handled = false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                mDragHappening = true;
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                mDragHappening = false;
                break;
            case DragEvent.ACTION_DRAG_ENTERED:
                handled = insideDragZone();
                break;
            case DragEvent.ACTION_DRAG_LOCATION:
                handled = handleLocationEvent(v, event.getX(), event.getY());
                break;
            default:
                break;
        }

        if (!handled) {
            handled = mDragHandler.onDrag(v, event);
        }

        return handled;
    }

    private boolean handleLocationEvent(View v, float x, float y) {
        mCurrentPosition = new Point(Math.round(v.getX() + x), Math.round(v.getY() + y));
        if (insideDragZone()) {
            mDragScroller.run();
            return true;
        }
        return false;
    }

    private boolean insideDragZone() {
        if (mCurrentPosition == null) {
            return false;
        }

        boolean shouldScrollUp = mCurrentPosition.y < mAutoScrollEdgeHeight
                && mCanScrollUp.getAsBoolean();
        boolean shouldScrollDown = mCurrentPosition.y > mHeight.getAsInt() - mAutoScrollEdgeHeight
                && mCanScrollDown.getAsBoolean();
        return shouldScrollUp || shouldScrollDown;
    }
}