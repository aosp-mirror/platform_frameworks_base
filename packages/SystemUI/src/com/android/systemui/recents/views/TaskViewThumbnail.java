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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.model.Task;


/** The task thumbnail view */
public class TaskViewThumbnail extends FixedSizeImageView {

    RecentsConfiguration mConfig;

    // Task bar clipping
    Rect mClipRect = new Rect();

    public TaskViewThumbnail(Context context) {
        this(context, null);
    }

    public TaskViewThumbnail(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskViewThumbnail(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskViewThumbnail(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        setScaleType(ScaleType.FIT_XY);
    }

    @Override
    protected void onFinishInflate() {
        setAlpha(0.9f);
    }

    /** Updates the clip rect based on the given task bar. */
    void enableTaskBarClip(View taskBar) {
        int top = (int) Math.max(0, taskBar.getTranslationY() +
                taskBar.getMeasuredHeight() - 1);
        mClipRect.set(0, top, getMeasuredWidth(), getMeasuredHeight());
        setClipBounds(mClipRect);
    }

    /** Disables the task bar clipping. */
    void disableTaskBarClip() {
        mClipRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
        setClipBounds(mClipRect);
    }

    /** Binds the thumbnail view to the screenshot. */
    boolean bindToScreenshot(Bitmap ss) {
        if (ss != null) {
            setImageBitmap(ss);
            return true;
        }
        return false;
    }

    /** Unbinds the thumbnail view from the screenshot. */
    void unbindFromScreenshot() {
        setImageBitmap(null);
    }

    /** Binds the thumbnail view to the task */
    void rebindToTask(Task t) {
        if (t.thumbnail != null) {
            setImageBitmap(t.thumbnail);
        }
    }

    /** Unbinds the thumbnail view from the task */
    void unbindFromTask() {
        setImageDrawable(null);
    }

    /** Handles focus changes. */
    void onFocusChanged(boolean focused) {
        if (focused) {
            if (Float.compare(getAlpha(), 1f) != 0) {
                startFadeAnimation(1f, 0, 150, null);
            }
        } else {
            if (Float.compare(getAlpha(), mConfig.taskViewThumbnailAlpha) != 0) {
                startFadeAnimation(mConfig.taskViewThumbnailAlpha, 0, 150, null);
            }
        }
    }

    /** Prepares for the enter recents animation. */
    void prepareEnterRecentsAnimation(boolean isTaskViewLaunchTargetTask) {
        if (isTaskViewLaunchTargetTask) {
            setAlpha(1f);
        } else {
            setAlpha(mConfig.taskViewThumbnailAlpha);
        }
    }

    /** Animates this task thumbnail as it enters recents */
    void startEnterRecentsAnimation(int delay, Runnable postAnimRunnable) {
        startFadeAnimation(mConfig.taskViewThumbnailAlpha, delay,
                mConfig.taskBarEnterAnimDuration, postAnimRunnable);
    }

    /** Animates this task thumbnail as it exits recents */
    void startLaunchTaskAnimation(Runnable postAnimRunnable) {
        startFadeAnimation(1f, 0, mConfig.taskBarExitAnimDuration, postAnimRunnable);
    }

    /** Animates the thumbnail alpha. */
    void startFadeAnimation(float finalAlpha, int delay, int duration, Runnable postAnimRunnable) {
        if (postAnimRunnable != null) {
            animate().withEndAction(postAnimRunnable);
        }
        animate()
                .alpha(finalAlpha)
                .setStartDelay(delay)
                .setInterpolator(mConfig.fastOutSlowInInterpolator)
                .setDuration(duration)
                .withLayer()
                .start();
    }
}
