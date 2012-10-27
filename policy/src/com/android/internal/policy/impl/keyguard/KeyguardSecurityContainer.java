package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class KeyguardSecurityContainer extends FrameLayout {

    public KeyguardSecurityContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardSecurityContainer(Context context) {
        this(null, null, 0);
    }

    public KeyguardSecurityContainer(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }
}
