/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.support.v4.util.ArrayMap;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.RecyclerView;
import android.util.TypedValue;

import com.android.documentsui.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Performs change animations on Items in DirectoryFragment's RecyclerView.  This class overrides
 * the way selection animations are normally performed - instead of cross fading the old Item with a
 * new Item, this class manually animates a background color change.  This enables selected Items to
 * correctly maintain focus.
 */
class DirectoryItemAnimator extends DefaultItemAnimator {
    private final List<ColorAnimation> mPendingAnimations = new ArrayList<>();
    private final Map<RecyclerView.ViewHolder, ColorAnimation> mRunningAnimations =
            new ArrayMap<>();
    private final Integer mDefaultColor;
    private final Integer mSelectedColor;

    public DirectoryItemAnimator(Context context) {
        mDefaultColor = context.getResources().getColor(R.color.item_doc_background);
        mSelectedColor = context.getResources().getColor(R.color.item_doc_background_selected);
    }

    @Override
    public void runPendingAnimations() {
        super.runPendingAnimations();
        for (ColorAnimation anim: mPendingAnimations) {
            anim.start();
            mRunningAnimations.put(anim.viewHolder, anim);
        }
        mPendingAnimations.clear();
    }

    @Override
    public void endAnimation(RecyclerView.ViewHolder vh) {
        super.endAnimation(vh);

        for (int i = mPendingAnimations.size() - 1; i >= 0; --i) {
            ColorAnimation anim = mPendingAnimations.get(i);
            if (anim.viewHolder == vh) {
                mPendingAnimations.remove(i);
                anim.end();
            }
        }

        ColorAnimation anim = mRunningAnimations.get(vh);
        if (anim != null) {
            anim.cancel();
        }
    }

    @Override
    public ItemHolderInfo recordPreLayoutInformation(
        RecyclerView.State state,
        RecyclerView.ViewHolder viewHolder,
        @AdapterChanges int changeFlags,
        List<Object> payloads) {
        ItemInfo info = (ItemInfo) super.recordPreLayoutInformation(state,
                viewHolder, changeFlags, payloads);
        info.isActivated = viewHolder.itemView.isActivated();
        return info;
    }


    @Override
    public ItemHolderInfo recordPostLayoutInformation(
        RecyclerView.State state, RecyclerView.ViewHolder viewHolder) {
        ItemInfo info = (ItemInfo) super.recordPostLayoutInformation(state,
                viewHolder);
        info.isActivated = viewHolder.itemView.isActivated();
        return info;
    }

    @Override
    public boolean animateChange(final RecyclerView.ViewHolder oldHolder,
            RecyclerView.ViewHolder newHolder, ItemHolderInfo preInfo,
            ItemHolderInfo postInfo) {
        if (oldHolder != newHolder) {
            return super.animateChange(oldHolder, newHolder, preInfo, postInfo);
        }

        ItemInfo pre = (ItemInfo)preInfo;
        ItemInfo post = (ItemInfo)postInfo;

        if (pre.isActivated == post.isActivated) {
            dispatchAnimationFinished(oldHolder);
            return false;
        } else {
            Integer startColor = pre.isActivated ? mSelectedColor : mDefaultColor;
            Integer endColor = post.isActivated ? mSelectedColor : mDefaultColor;
            oldHolder.itemView.setBackgroundColor(startColor);
            mPendingAnimations.add(new ColorAnimation(oldHolder, startColor, endColor));
        }
        return true;
    }

    @Override
    public ItemHolderInfo obtainHolderInfo() {
        return new ItemInfo();
    }

    @Override
    public boolean canReuseUpdatedViewHolder(RecyclerView.ViewHolder vh) {
        return true;
    }

    class ItemInfo extends DefaultItemAnimator.ItemHolderInfo {
        boolean isActivated;
    };

    /**
     * Animates changes in background color.
     */
    class ColorAnimation
            implements ValueAnimator.AnimatorUpdateListener, Animator.AnimatorListener {
        ValueAnimator mValueAnimator;
        final RecyclerView.ViewHolder viewHolder;
        int mEndColor;

        public ColorAnimation(RecyclerView.ViewHolder vh, int startColor, int endColor)
        {
            viewHolder = vh;
            mValueAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, endColor);
            mValueAnimator.addUpdateListener(this);
            mValueAnimator.addListener(this);

            mEndColor = endColor;
        }

        public void start() {
            mValueAnimator.start();
        }

        public void cancel() {
            mValueAnimator.cancel();
        }

        public void end() {
            mValueAnimator.end();
        }

        @Override
        public void onAnimationUpdate(ValueAnimator animator) {
            viewHolder.itemView.setBackgroundColor((Integer)animator.getAnimatedValue());
        }

        @Override
        public void onAnimationEnd(Animator animator) {
            viewHolder.itemView.setBackgroundColor(mEndColor);
            mRunningAnimations.remove(viewHolder);
            dispatchAnimationFinished(viewHolder);
        }

        @Override
        public void onAnimationStart(Animator animation) {
            dispatchAnimationStarted(viewHolder);
        }

        @Override
        public void onAnimationCancel(Animator animation) {}

        @Override
        public void onAnimationRepeat(Animator animation) {}
    };
};
