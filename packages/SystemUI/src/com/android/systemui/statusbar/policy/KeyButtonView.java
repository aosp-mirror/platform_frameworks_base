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

package com.android.systemui.statusbar.policy;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RemoteViews.RemoteView;

import com.android.systemui.R;

public class KeyButtonView extends ImageView {
    private static final String TAG = "StatusBar.KeyButtonView";

    IWindowManager mWindowManager;
    long mDownTime;
    boolean mSending;
    int mCode;
    int mRepeat;
    int mTouchSlop;
    Drawable mGlowBG;
    float mGlowAlpha = 0f, mGlowScale = 1f, mDrawingAlpha = 1f;

    Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (isPressed()) {

                if (mCode != 0) {
                    mRepeat++;
                    sendEvent(KeyEvent.ACTION_DOWN,
                            KeyEvent.FLAG_FROM_SYSTEM
                            | KeyEvent.FLAG_VIRTUAL_HARD_KEY
                            | KeyEvent.FLAG_LONG_PRESS);

                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                } else {
                    // Just an old-fashioned ImageView
                    performLongClick();
                }
            }
        }
    };

    public KeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyButtonView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.KeyButtonView,
                defStyle, 0);

        mCode = a.getInteger(R.styleable.KeyButtonView_keyCode, 0);

        mGlowBG = a.getDrawable(R.styleable.KeyButtonView_glowBackground);
        if (mGlowBG != null) {
            mDrawingAlpha = 0.5f;
        }
        
        a.recycle();

        mWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));

        setClickable(true);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mGlowBG != null) {
            canvas.save();
            final int w = getWidth();
            final int h = getHeight();
            canvas.scale(mGlowScale, mGlowScale, w*0.5f, h*0.5f);
            mGlowBG.setBounds(0, 0, w, h);
            mGlowBG.setAlpha((int)(mGlowAlpha * 255));
            mGlowBG.draw(canvas);
            canvas.restore();

            canvas.saveLayerAlpha(null, (int)(mDrawingAlpha * 255), Canvas.ALL_SAVE_FLAG);
        }
        super.onDraw(canvas);
        if (mGlowBG != null) {
            canvas.restore();
        }
    }

    public float getDrawingAlpha() {
        if (mGlowBG == null) return 0;
        return mDrawingAlpha;
    }

    public void setDrawingAlpha(float x) {
        if (mGlowBG == null) return;
        mDrawingAlpha = x;
        invalidate();
    }

    public float getGlowAlpha() {
        if (mGlowBG == null) return 0;
        return mGlowAlpha;
    }

    public void setGlowAlpha(float x) {
        if (mGlowBG == null) return;
        mGlowAlpha = x;
        invalidate();
    }

    public float getGlowScale() {
        if (mGlowBG == null) return 0;
        return mGlowScale;
    }

    public void setGlowScale(float x) {
        if (mGlowBG == null) return;
        mGlowScale = x;
        final float w = getWidth();
        final float h = getHeight();
        if (x < 1.0f) {
            invalidate();
        } else {
            final float rx = (w * (x - 1.0f)) / 2.0f;
            final float ry = (h * (x - 1.0f)) / 2.0f;
            com.android.systemui.SwipeHelper.invalidateGlobalRegion(
                    this,
                    new RectF(getLeft() - rx,
                              getTop() - ry,
                              getRight() + rx,
                              getBottom() + ry));
        }
    }

    public void setPressed(boolean pressed) {
        if (mGlowBG != null) {
            if (pressed != isPressed()) {
                AnimatorSet as = new AnimatorSet();
                if (pressed) {
                    if (mGlowScale < 1.7f) mGlowScale = 1.7f;
                    if (mGlowAlpha < 0.5f) mGlowAlpha = 0.5f;
                    setDrawingAlpha(1f);
                    as.playTogether(
                        ObjectAnimator.ofFloat(this, "glowAlpha", 1f),
                        ObjectAnimator.ofFloat(this, "glowScale", 1.8f)
                    );
                    as.setDuration(50);
                } else {
                    as.playTogether(
                        ObjectAnimator.ofFloat(this, "glowAlpha", 0f),
                        ObjectAnimator.ofFloat(this, "glowScale", 1f),
                        ObjectAnimator.ofFloat(this, "drawingAlpha", 0.5f)
                    );
                    as.setDuration(500);
                }
                as.start();
            }
        }
        super.setPressed(pressed);
    }

    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        int x, y;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                //Slog.d("KeyButtonView", "press");
                mDownTime = SystemClock.uptimeMillis();
                mRepeat = 0;
                mSending = true;
                sendEvent(KeyEvent.ACTION_DOWN,
                        KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, mDownTime);
                setPressed(true);
                removeCallbacks(mCheckLongPress);
                postDelayed(mCheckLongPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSending) {
                    x = (int)ev.getX();
                    y = (int)ev.getY();
                    setPressed(x >= -mTouchSlop
                            && x < getWidth() + mTouchSlop
                            && y >= -mTouchSlop
                            && y < getHeight() + mTouchSlop);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (mSending) {
                    mSending = false;
                    sendEvent(KeyEvent.ACTION_UP,
                            KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY
                                | KeyEvent.FLAG_CANCELED);
                    removeCallbacks(mCheckLongPress);
                }
                break;
            case MotionEvent.ACTION_UP:
                final boolean doIt = isPressed();
                setPressed(false);
                if (mSending) {
                    mSending = false;
                    final int flags = KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
                    removeCallbacks(mCheckLongPress);

                    if (mCode != 0) {
                        if (doIt) {
                            sendEvent(KeyEvent.ACTION_UP, flags);
                            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                            playSoundEffect(SoundEffectConstants.CLICK);
                        } else {
                            sendEvent(KeyEvent.ACTION_UP, flags | KeyEvent.FLAG_CANCELED);
                        }
                    } else {
                        // no key code, just a regular ImageView
                        if (doIt) performClick();
                    }
                }
                break;
        }

        return true;
    }

    void sendEvent(int action, int flags) {
        sendEvent(action, flags, SystemClock.uptimeMillis());
    }

    void sendEvent(int action, int flags, long when) {
        final KeyEvent ev = new KeyEvent(mDownTime, when, action, mCode, mRepeat,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD);
        try {
            //Slog.d(TAG, "injecting event " + ev);
            mWindowManager.injectInputEventNoWait(ev);
        } catch (RemoteException ex) {
            // System process is dead
        }
    }
}


