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
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.tv.animations.DismissAnimationsHolder;
import com.android.systemui.recents.tv.animations.RecentsRowFocusAnimationHolder;
import com.android.systemui.recents.tv.animations.ViewFocusAnimator;
import com.android.systemui.recents.model.Task;

public class TaskCardView extends LinearLayout {

    private static final String TAG = "TaskCardView";
    private View mThumbnailView;
    private View mDismissIconView;
    private TextView mTitleTextView;
    private ImageView mBadgeView;
    private Task mTask;
    private boolean mDismissState;
    private int mCornerRadius;

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
        mDismissState = false;
        Configuration config = getResources().getConfiguration();
        setLayoutDirection(config.getLayoutDirection());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mThumbnailView = findViewById(R.id.card_view_thumbnail);
        mTitleTextView = (TextView) findViewById(R.id.card_title_text);
        mBadgeView = (ImageView) findViewById(R.id.card_extra_badge);
        mDismissIconView = findViewById(R.id.dismiss_icon);
        mDismissAnimationsHolder = new DismissAnimationsHolder(this);
        View title = findViewById(R.id.card_info_field);
        mCornerRadius = getResources().getDimensionPixelSize(
                R.dimen.recents_task_view_rounded_corners_radius);
        mRecentsRowFocusAnimationHolder = new RecentsRowFocusAnimationHolder(this, title);
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.isTouchExplorationEnabled()) {
            mDismissIconView.setFocusable(true);
            mDismissIconView.setFocusableInTouchMode(true);
            mDismissIconView.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        setDismissState(true);
                    } else {
                        setDismissState(false);
                    }
                }
            });
        } else {
            mDismissIconView.setFocusable(false);
            mDismissIconView.setFocusableInTouchMode(false);
        }
        mViewFocusAnimator = new ViewFocusAnimator(this);
    }

    public void init(Task task) {
        mTask = task;
        mTitleTextView.setText(task.title);
        mBadgeView.setImageDrawable(task.icon);
        setThumbnailView();
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

    public static Rect getStartingCardThumbnailRect(
            Context context, boolean hasFocus, int numberOfTasks) {
        if(numberOfTasks > 1) {
            return getStartingCardThumbnailRectForStartPosition(context, hasFocus);
        } else {
            return getStartingCardThumbnailRectForFocusedPosition(context, hasFocus);
        }
    }

    private static Rect getStartingCardThumbnailRectForStartPosition(
            Context context, boolean hasFocus) {
        Resources res = context.getResources();

        int width = res.getDimensionPixelOffset(R.dimen.recents_tv_card_width);
        int totalSpacing = res.getDimensionPixelOffset(R.dimen.recents_tv_gird_card_spacing) * 2;
        if (hasFocus) {
            totalSpacing += res.getDimensionPixelOffset(R.dimen.recents_tv_gird_focused_card_delta);
        }
        int height = res.getDimensionPixelOffset(R.dimen.recents_tv_screenshot_height);
        int topMargin = res.getDimensionPixelOffset(R.dimen.recents_tv_gird_row_top_margin);
        int headerHeight = res.getDimensionPixelOffset(R.dimen.recents_tv_card_extra_badge_size) +
                res.getDimensionPixelOffset(R.dimen.recents_tv_icon_padding_bottom);

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenWidth = size.x;

        return new Rect(screenWidth / 2 + width / 2 + totalSpacing,
                topMargin + headerHeight,
                screenWidth / 2 + width / 2 + totalSpacing + width,
                topMargin + headerHeight + height);
    }

    private static Rect getStartingCardThumbnailRectForFocusedPosition(
            Context context, boolean hasFocus) {
        Resources res = context.getResources();

        TypedValue out = new TypedValue();
        res.getValue(R.integer.selected_scale, out, true);
        float scale = hasFocus ? out.getFloat() : 1;

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
        mDismissState = false;
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

    public void reset() {
        mDismissState = false;
        mRecentsRowFocusAnimationHolder.reset();
        mDismissAnimationsHolder.reset();
    }

    private void setThumbnailView() {
        ImageView screenshotView = (ImageView) findViewById(R.id.card_view_banner_icon);
        PackageManager pm = getContext().getPackageManager();
        if (mTask.thumbnail != null) {
            setAsScreenShotView(mTask.thumbnail, screenshotView);
        } else {
            try {
                Drawable banner = null;
                if (mTask.key != null) {
                    banner = pm.getActivityBanner(mTask.key.baseIntent);
                }
                if (banner != null) {
                    setAsBannerView(banner, screenshotView);
                } else {
                    setAsIconView(mTask.icon, screenshotView);
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Package not found : " + e);
                setAsIconView(mTask.icon, screenshotView);
            }
        }
    }

    private void setAsScreenShotView(Bitmap screenshot, ImageView screenshotView) {
        LayoutParams lp = (LayoutParams) screenshotView.getLayoutParams();
        lp.width = LayoutParams.MATCH_PARENT;
        lp.height = LayoutParams.MATCH_PARENT;

        screenshotView.setLayoutParams(lp);
        screenshotView.setClipToOutline(true);
        screenshotView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
        screenshotView.setImageBitmap(screenshot);
    }

    private void setAsBannerView(Drawable banner, ImageView bannerView) {
        LayoutParams lp = (LayoutParams) bannerView.getLayoutParams();
        lp.width = getResources()
                .getDimensionPixelSize(R.dimen.recents_tv_banner_width);
        lp.height = getResources()
                .getDimensionPixelSize(R.dimen.recents_tv_banner_height);
        bannerView.setLayoutParams(lp);
        bannerView.setImageDrawable(banner);
    }

    private void setAsIconView(Drawable icon, ImageView iconView) {
        LayoutParams lp = (LayoutParams) iconView.getLayoutParams();
        lp.width = getResources()
                .getDimensionPixelSize(R.dimen.recents_tv_fallback_icon_width);
        lp.height = getResources()
                .getDimensionPixelSize(R.dimen.recents_tv_fallback_icon_height);

        iconView.setLayoutParams(lp);
        iconView.setImageDrawable(icon);
    }

    public View getThumbnailView() {
        return mThumbnailView;
    }

    public View getDismissIconView() {
        return mDismissIconView;
    }

    public static int getNumberOfVisibleTasks(Context context) {
        Resources res = context.getResources();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        int screenWidth = size.x;
        int cardWidth = res.getDimensionPixelSize(R.dimen.recents_tv_card_width);
        int spacing = res.getDimensionPixelSize(R.dimen.recents_tv_gird_card_spacing);
        return (int) (1.0 + Math.ceil(screenWidth / (cardWidth + spacing * 2.0)));
    }
}
