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
package com.android.systemui.recents.tv.views;

import android.animation.Animator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.recents.tv.animations.DismissAnimationsHolder;
import com.android.systemui.recents.tv.animations.RecentsRowFocusAnimationHolder;
import com.android.systemui.recents.tv.animations.ViewFocusAnimator;
import com.android.systemui.recents.model.Task;

public class TaskCardView extends LinearLayout {

    private ImageView mThumbnailView;
    private TextView mTitleTextView;
    private ImageView mBadgeView;
    private Task mTask;
    private boolean mDismissState;

    private ViewFocusAnimator mViewFocusAnimator;
    private DismissAnimationsHolder mDismissAnimationsHolder;
    private RecentsRowFocusAnimationHolder mRecentsRowFocusAnimationHolder;

    public TaskCardView(Context context) {
        this(context, null);
    }

    public TaskCardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mViewFocusAnimator = new ViewFocusAnimator(this);
        mDismissState = false;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mThumbnailView = (ImageView) findViewById(R.id.card_view_thumbnail);
        mTitleTextView = (TextView) findViewById(R.id.card_title_text);
        mBadgeView = (ImageView) findViewById(R.id.card_extra_badge);
        mDismissAnimationsHolder = new DismissAnimationsHolder(this);
        View title = findViewById(R.id.card_info_field);
        mRecentsRowFocusAnimationHolder = new RecentsRowFocusAnimationHolder(this, title);
    }

    public void init(Task task) {
        mTask = task;
        mThumbnailView.setImageBitmap(task.thumbnail);
        mTitleTextView.setText(task.title);
        mBadgeView.setImageDrawable(task.icon);
    }

    public Task getTask() {
        return mTask;
    }

    @Override
    public void getFocusedRect(Rect r) {
        mThumbnailView.getFocusedRect(r);
    }

    public Rect getFocusedThumbnailRect() {
        Rect r = new Rect();
        mThumbnailView.getGlobalVisibleRect(r);
        return r;
    }

    public static Rect getStartingCardThumbnailRect(Context context) {
        Resources res = context.getResources();

        TypedValue out = new TypedValue();
        res.getValue(R.integer.selected_scale, out, true);
        float scale = out.getFloat();

        int width = res.getDimensionPixelOffset(R.dimen.recents_tv_card_width);
        int widthDelta = (int) (width * scale - width);
        int height = res.getDimensionPixelOffset(R.dimen.recents_tv_screenshot_height);
        int heightDelta = (int) (height * scale - height);
        int topMargin = res.getDimensionPixelOffset(R.dimen.recents_tv_gird_row_top_margin);

        int headerHeight = res.getDimensionPixelOffset(R.dimen.recents_tv_card_extra_badge_size) +
                res.getDimensionPixelOffset(R.dimen.recents_tv_icon_padding_bottom);
        int headerHeightDelta = (int) (headerHeight * scale - headerHeight);

        int dismissAreaHeight =
                res.getDimensionPixelOffset(R.dimen.recents_tv_dismiss_icon_top_margin) +
                res.getDimensionPixelOffset(R.dimen.recents_tv_dismiss_icon_bottom_margin) +
                res.getDimensionPixelOffset(R.dimen.recents_tv_dismiss_icon_size) +
                res.getDimensionPixelOffset(R.dimen.recents_tv_dismiss_text_size);

        int dismissAreaHeightDelta = (int) (dismissAreaHeight * scale - dismissAreaHeight);

        int totalHeightDelta = heightDelta + headerHeightDelta + dismissAreaHeightDelta;

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;

        return new Rect(screenWidth / 2 - width / 2 - widthDelta / 2,
                topMargin - totalHeightDelta / 2 + (int) (headerHeight * scale),
                screenWidth / 2 + width / 2 + widthDelta / 2,
                topMargin - totalHeightDelta / 2 + (int) (headerHeight * scale) +
                        (int) (height * scale));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN : {
                if (!isInDismissState()) {
                    setDismissState(true);
                    return true;
                }
                break;
            }
            case KeyEvent.KEYCODE_DPAD_UP : {
                if (isInDismissState()) {
                    setDismissState(false);
                    return true;
                }
                break;
            }

            //Eat right and left key presses when we are in dismiss state
            case KeyEvent.KEYCODE_DPAD_LEFT : {
                if (isInDismissState()) {
                    return true;
                }
                break;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT : {
                if (isInDismissState()) {
                    return true;
                }
                break;
            }
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setDismissState(boolean dismissState) {
        if (mDismissState != dismissState) {
            mDismissState = dismissState;
            if (dismissState) {
                mDismissAnimationsHolder.startEnterAnimation();
            } else {
                mDismissAnimationsHolder.startExitAnimation();
            }
        }
    }

    public boolean isInDismissState() {
        return mDismissState;
    }

    public void startDismissTaskAnimation(Animator.AnimatorListener listener) {
        mDismissAnimationsHolder.startDismissAnimation(listener);
    }

    public RecentsRowFocusAnimationHolder getRecentsRowFocusAnimationHolder() {
        return mRecentsRowFocusAnimationHolder;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setDismissState(false);
    }
}
