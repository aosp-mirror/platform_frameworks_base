/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view.animation;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.util.AttributeSet;
import android.view.WindowInsets;

/**
 * An animation that controls the outset of an object.
 *
 * @hide
 */
public class ExtendAnimation extends Animation {
    protected Insets mFromInsets = Insets.NONE;
    protected Insets mToInsets = Insets.NONE;

    private int mFromLeftType = ABSOLUTE;
    private int mFromTopType = ABSOLUTE;
    private int mFromRightType = ABSOLUTE;
    private int mFromBottomType = ABSOLUTE;

    private int mToLeftType = ABSOLUTE;
    private int mToTopType = ABSOLUTE;
    private int mToRightType = ABSOLUTE;
    private int mToBottomType = ABSOLUTE;

    private float mFromLeftValue;
    private float mFromTopValue;
    private float mFromRightValue;
    private float mFromBottomValue;

    private float mToLeftValue;
    private float mToTopValue;
    private float mToRightValue;
    private float mToBottomValue;

    /**
     * Constructor used when an ExtendAnimation is loaded from a resource.
     *
     * @param context Application context to use
     * @param attrs Attribute set from which to read values
     */
    public ExtendAnimation(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.ExtendAnimation);

        Description d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ExtendAnimation_fromExtendLeft), context);
        mFromLeftType = d.type;
        mFromLeftValue = d.value;

        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ExtendAnimation_fromExtendTop), context);
        mFromTopType = d.type;
        mFromTopValue = d.value;

        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ExtendAnimation_fromExtendRight), context);
        mFromRightType = d.type;
        mFromRightValue = d.value;

        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ExtendAnimation_fromExtendBottom), context);
        mFromBottomType = d.type;
        mFromBottomValue = d.value;


        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ExtendAnimation_toExtendLeft), context);
        mToLeftType = d.type;
        mToLeftValue = d.value;

        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ExtendAnimation_toExtendTop), context);
        mToTopType = d.type;
        mToTopValue = d.value;

        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ExtendAnimation_toExtendRight), context);
        mToRightType = d.type;
        mToRightValue = d.value;

        d = Description.parseValue(a.peekValue(
                com.android.internal.R.styleable.ExtendAnimation_toExtendBottom), context);
        mToBottomType = d.type;
        mToBottomValue = d.value;

        a.recycle();
    }

    /**
     * Constructor to use when building an ExtendAnimation from code
     *
     * @param fromInsets the insets to animate from
     * @param toInsets the insets to animate to
     */
    public ExtendAnimation(Insets fromInsets, Insets toInsets) {
        if (fromInsets == null || toInsets == null) {
            throw new RuntimeException("Expected non-null animation outsets");
        }
        mFromLeftValue = -fromInsets.left;
        mFromTopValue = -fromInsets.top;
        mFromRightValue = -fromInsets.right;
        mFromBottomValue = -fromInsets.bottom;

        mToLeftValue = -toInsets.left;
        mToTopValue = -toInsets.top;
        mToRightValue = -toInsets.right;
        mToBottomValue = -toInsets.bottom;
    }

    /**
     * Constructor to use when building an ExtendAnimation from code
     */
    public ExtendAnimation(int fromL, int fromT, int fromR, int fromB,
            int toL, int toT, int toR, int toB) {
        this(Insets.of(-fromL, -fromT, -fromR, -fromB), Insets.of(-toL, -toT, -toR, -toB));
    }

    @Override
    protected void applyTransformation(float it, Transformation tr) {
        int l = mFromInsets.left + (int) ((mToInsets.left - mFromInsets.left) * it);
        int t = mFromInsets.top + (int) ((mToInsets.top - mFromInsets.top) * it);
        int r = mFromInsets.right + (int) ((mToInsets.right - mFromInsets.right) * it);
        int b = mFromInsets.bottom + (int) ((mToInsets.bottom - mFromInsets.bottom) * it);
        tr.setInsets(l, t, r, b);
    }

    @Override
    public boolean willChangeTransformationMatrix() {
        return false;
    }

    /** @hide */
    @Override
    @WindowInsets.Side.InsetsSide
    public int getExtensionEdges() {
        return (mFromInsets.left < 0 || mToInsets.left < 0 ?  WindowInsets.Side.LEFT : 0)
            | (mFromInsets.right < 0 || mToInsets.right < 0 ?  WindowInsets.Side.RIGHT : 0)
            | (mFromInsets.top < 0 || mToInsets.top < 0 ?  WindowInsets.Side.TOP : 0)
            | (mFromInsets.bottom < 0 || mToInsets.bottom < 0 ? WindowInsets.Side.BOTTOM : 0);
    }

    @Override
    public void initialize(int width, int height, int parentWidth, int parentHeight) {
        super.initialize(width, height, parentWidth, parentHeight);
        // We remove any negative extension (i.e. positive insets) and set those to 0
        mFromInsets = Insets.min(Insets.of(
                    -(int) resolveSize(mFromLeftType, mFromLeftValue, width, parentWidth),
                    -(int) resolveSize(mFromTopType, mFromTopValue, height, parentHeight),
                    -(int) resolveSize(mFromRightType, mFromRightValue, width, parentWidth),
                    -(int) resolveSize(mFromBottomType, mFromBottomValue, height, parentHeight)
                ), Insets.NONE);
        mToInsets = Insets.min(Insets.of(
                    -(int) resolveSize(mToLeftType, mToLeftValue, width, parentWidth),
                    -(int) resolveSize(mToTopType, mToTopValue, height, parentHeight),
                    -(int) resolveSize(mToRightType, mToRightValue, width, parentWidth),
                    -(int) resolveSize(mToBottomType, mToBottomValue, height, parentHeight)
                ), Insets.NONE);
    }
}
