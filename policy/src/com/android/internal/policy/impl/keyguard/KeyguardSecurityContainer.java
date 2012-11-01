package com.android.internal.policy.impl.keyguard;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.android.internal.R;

public class KeyguardSecurityContainer extends FrameLayout {

    private float mBackgroundAlpha;
    private Drawable mBackgroundDrawable;

    public KeyguardSecurityContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardSecurityContainer(Context context) {
        this(null, null, 0);
    }

    public KeyguardSecurityContainer(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mBackgroundDrawable = context.getResources().getDrawable(R.drawable.kg_bouncer_bg_white);
    }

    public void setBackgroundAlpha(float alpha) {
        if (Float.compare(mBackgroundAlpha, alpha) != 0) {
            mBackgroundAlpha = alpha;
            invalidate();
        }
    }

    public float getBackgroundAlpha() {
        return mBackgroundAlpha;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mBackgroundAlpha > 0.0f && mBackgroundDrawable != null) {
            Drawable bg = mBackgroundDrawable;
            bg.setAlpha((int) (mBackgroundAlpha * 255));
            bg.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
            bg.draw(canvas);
        }
        super.dispatchDraw(canvas);
    }

    public void showBouncer(int duration) {
        SecurityMessageDisplay message = new KeyguardMessageArea.Helper(this);
        message.showBouncer(duration);
        Animator anim = ObjectAnimator.ofFloat(this, "BackgroundAlpha", 1f);
        anim.setDuration(duration);
        anim.start();
    }

    public void hideBouncer(int duration) {
        SecurityMessageDisplay message = new KeyguardMessageArea.Helper(this);
        message.hideBouncer(duration);
        Animator anim = ObjectAnimator.ofFloat(this, "BackgroundAlpha", 0f);
        anim.setDuration(duration);
        anim.start();
    }
}
