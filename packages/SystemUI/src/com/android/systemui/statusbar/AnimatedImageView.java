/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.View;
import android.widget.ImageView;
import android.widget.RemoteViews.RemoteView;

@RemoteView
public class AnimatedImageView extends ImageView {
    AnimationDrawable mAnim;
    boolean mAttached;

    public AnimatedImageView(Context context) {
        super(context);
    }

    public AnimatedImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void updateAnim() {
        Drawable drawable = getDrawable();
        if (mAttached && mAnim != null) {
            mAnim.stop();
        }
        if (drawable instanceof AnimationDrawable) {
            mAnim = (AnimationDrawable)drawable;
            if (isShown()) {
                mAnim.start();
            }
        } else {
            mAnim = null;
        }
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateAnim();
    }

    @Override
    @android.view.RemotableViewMethod
    public void setImageResource(int resid) {
        super.setImageResource(resid);
        updateAnim();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        updateAnim();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAnim != null) {
            mAnim.stop();
        }
        mAttached = false;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int vis) {
        super.onVisibilityChanged(changedView, vis);
        if (mAnim != null) {
            if (isShown()) {
                mAnim.start();
            } else {
                mAnim.stop();
            }
        }
    }
}

