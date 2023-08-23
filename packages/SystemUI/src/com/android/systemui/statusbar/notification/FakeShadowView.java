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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.AlphaOptimizedFrameLayout;

/**
 * A view used to cast a shadow of a certain size on another view
 */
public class FakeShadowView extends AlphaOptimizedFrameLayout {
    public static final float SHADOW_SIBLING_TRESHOLD = 0.1f;
    private final int mShadowMinHeight;

    private View mFakeShadow;
    private float mOutlineAlpha;

    public FakeShadowView(Context context) {
        this(context, null);
    }

    public FakeShadowView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FakeShadowView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public FakeShadowView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mFakeShadow = new View(context);
        mFakeShadow.setVisibility(INVISIBLE);
        mFakeShadow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (48 * getResources().getDisplayMetrics().density)));
        mFakeShadow.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRect(0, 0, getWidth(), mFakeShadow.getHeight());
                outline.setAlpha(mOutlineAlpha);
            }
        });
        addView(mFakeShadow);
        mShadowMinHeight = Math.max(1, context.getResources()
                .getDimensionPixelSize(R.dimen.notification_divider_height));
    }

    public void setFakeShadowTranslationZ(float fakeShadowTranslationZ, float outlineAlpha,
            int shadowYEnd, int outlineTranslation) {
        if (fakeShadowTranslationZ == 0.0f) {
            mFakeShadow.setVisibility(INVISIBLE);
        } else {
            mFakeShadow.setVisibility(VISIBLE);
            fakeShadowTranslationZ = Math.max(mShadowMinHeight, fakeShadowTranslationZ);
            mFakeShadow.setTranslationZ(fakeShadowTranslationZ);
            mFakeShadow.setTranslationX(outlineTranslation);
            mFakeShadow.setTranslationY(shadowYEnd - mFakeShadow.getHeight());
            if (outlineAlpha != mOutlineAlpha) {
                mOutlineAlpha = outlineAlpha;
                mFakeShadow.invalidateOutline();
            }
        }
    }
}
