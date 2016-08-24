/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.annotation.IntDef;
import android.app.FragmentTransaction;
import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.android.documentsui.R;
import com.android.documentsui.Shared;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class exists solely to support animated transition of our directory fragment.
 * The structure of this class is tightly coupled with the static animations defined in
 * res/animator, specifically the "position" property referenced by
 * res/animator/dir_{enter,leave}.xml.
 */
public class AnimationView extends LinearLayout {

    @IntDef(flag = true, value = {
            ANIM_NONE,
            ANIM_SIDE,
            ANIM_LEAVE,
            ANIM_ENTER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AnimationType {}
    public static final int ANIM_NONE = 1;
    public static final int ANIM_SIDE = 2;
    public static final int ANIM_LEAVE = 3;
    public static final int ANIM_ENTER = 4;

    private float mPosition = 0f;

    // The distance the animation will cover...currently matches the height of the
    // content area.
    private int mSpan;

    public AnimationView(Context context) {
        super(context);
    }

    public AnimationView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mSpan = h;
        setPosition(mPosition);
    }

    public float getPosition() {
        return mPosition;
    }

    public void setPosition(float position) {
        mPosition = position;
        // Warning! If we ever decide to switch this to setX (slide left/right)
        // please remember to add RLT variations of the animations under res/animator-ldrtl.
        setY((mSpan > 0) ? (mPosition * mSpan) : 0);

        if (mPosition != 0) {
            setTranslationZ(getResources().getDimensionPixelSize(R.dimen.dir_elevation));
        } else {
            setTranslationZ(0);
        }
    }

    /**
     * Configures custom animations on the transaction according to the specified
     * @AnimationType.
     */
    static void setupAnimations(
            FragmentTransaction ft, @AnimationType int anim, Bundle args) {
        switch (anim) {
            case AnimationView.ANIM_SIDE:
                args.putBoolean(Shared.EXTRA_IGNORE_STATE, true);
                break;
            case AnimationView.ANIM_ENTER:
                // TODO: Document which behavior is being tailored
                //     by passing this bit. Remove if possible.
                args.putBoolean(Shared.EXTRA_IGNORE_STATE, true);
                ft.setCustomAnimations(R.animator.dir_enter, R.animator.fade_out);
                break;
            case AnimationView.ANIM_LEAVE:
                ft.setCustomAnimations(R.animator.fade_in, R.animator.dir_leave);
                break;
        }
    }
}
