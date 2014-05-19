/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.widget.Button;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.Utilities;
import com.android.systemui.recents.model.Task;


/* The task info view */
class TaskInfoView extends FrameLayout {

    Button mAppInfoButton;

    // Circular clip animation
    boolean mCircularClipEnabled;
    Path mClipPath = new Path();
    float mClipRadius;
    float mMaxClipRadius;
    Point mClipOrigin = new Point();
    ObjectAnimator mCircularClipAnimator;

    public TaskInfoView(Context context) {
        this(context, null);
    }

    public TaskInfoView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskInfoView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskInfoView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        // Initialize the buttons on the info panel
        mAppInfoButton = (Button) findViewById(R.id.task_view_app_info_button);
    }

    /** Updates the positions of each of the items to fit in the rect specified */
    void updateContents(Rect visibleRect) {
        // Offset the app info button
        mAppInfoButton.setTranslationY(visibleRect.top +
                (visibleRect.height() - mAppInfoButton.getMeasuredHeight()) / 2);
    }

    /** Sets the circular clip radius on this panel */
    public void setCircularClipRadius(float r) {
        mClipRadius = r;
        invalidate();
    }

    /** Gets the circular clip radius on this panel */
    public float getCircularClipRadius() {
        return mClipRadius;
    }

    /** Animates the circular clip radius on the icon */
    void animateCircularClip(Point o, float fromRadius, float toRadius,
                             final Runnable postRunnable, boolean animateInContent) {
        if (mCircularClipAnimator != null) {
            mCircularClipAnimator.cancel();
        }

        // Calculate the max clip radius to each of the corners
        int w = getMeasuredWidth() - o.x;
        int h = getMeasuredHeight() - o.y;
        // origin to tl, tr, br, bl
        mMaxClipRadius = (int) Math.ceil(Math.sqrt(o.x * o.x + o.y * o.y));
        mMaxClipRadius = (int) Math.max(mMaxClipRadius, Math.ceil(Math.sqrt(w * w + o.y * o.y)));
        mMaxClipRadius = (int) Math.max(mMaxClipRadius, Math.ceil(Math.sqrt(w * w + h * h)));
        mMaxClipRadius = (int) Math.max(mMaxClipRadius, Math.ceil(Math.sqrt(o.x * o.x + h * h)));

        mClipOrigin.set(o.x, o.y);
        mClipRadius = fromRadius;
        int duration = Utilities.calculateTranslationAnimationDuration((int) mMaxClipRadius);
        mCircularClipAnimator = ObjectAnimator.ofFloat(this, "circularClipRadius", toRadius);
        mCircularClipAnimator.setDuration(duration);
        mCircularClipAnimator.setInterpolator(
                RecentsConfiguration.getInstance().defaultBezierInterpolator);
        mCircularClipAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mCircularClipEnabled = false;
                if (postRunnable != null) {
                    postRunnable.run();
                }
            }
        });
        mCircularClipAnimator.start();
        mCircularClipEnabled = true;

        if (animateInContent) {
            animateAppInfoButtonIn(duration);
        }
    }

    /** Cancels the circular clip animation. */
    void cancelCircularClipAnimation() {
        if (mCircularClipAnimator != null) {
            mCircularClipAnimator.cancel();
        }
    }

    void animateAppInfoButtonIn(int duration) {
        mAppInfoButton.setScaleX(0.75f);
        mAppInfoButton.setScaleY(0.75f);
        mAppInfoButton.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(duration)
                .setInterpolator(RecentsConfiguration.getInstance().defaultBezierInterpolator)
                .withLayer()
                .start();
    }

    /** Binds the info view to the task */
    void rebindToTask(Task t, boolean animate) {
        RecentsConfiguration configuration = RecentsConfiguration.getInstance();
        if (Constants.DebugFlags.App.EnableTaskBarThemeColors && t.colorPrimary != 0) {
            setBackgroundColor(t.colorPrimary);
            // Workaround: The button currently doesn't support setting a custom background tint
            // not defined in the theme.  Just lower the alpha on the button to make it blend more
            // into the background.
            if (mAppInfoButton.getBackground() instanceof RippleDrawable) {
                RippleDrawable d = (RippleDrawable) mAppInfoButton.getBackground();
                if (d != null) {
                    d.setAlpha(96);
                }
            }
        } else {
            setBackgroundColor(configuration.taskBarViewDefaultBackgroundColor);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        int saveCount = 0;
        if (mCircularClipEnabled) {
            saveCount = canvas.save(Canvas.CLIP_SAVE_FLAG);
            mClipPath.reset();
            mClipPath.addCircle(mClipOrigin.x, mClipOrigin.y, mClipRadius * mMaxClipRadius,
                    Path.Direction.CW);
            canvas.clipPath(mClipPath);
        }
        super.draw(canvas);
        if (mCircularClipEnabled) {
            canvas.restoreToCount(saveCount);
        }
    }
}
