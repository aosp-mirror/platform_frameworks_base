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

package android.inputmethodservice.navigationbar;

import static android.view.Display.INVALID_DISPLAY;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageView;

import com.android.internal.annotations.VisibleForTesting;

/**
 * @hide
 */
public class KeyButtonView extends ImageView implements ButtonInterface {
    private static final String TAG = KeyButtonView.class.getSimpleName();

    private final boolean mPlaySounds;
    private long mDownTime;
    private boolean mTracking;
    private int mCode;
    private int mTouchDownX;
    private int mTouchDownY;
    private AudioManager mAudioManager;
    private boolean mGestureAborted;
    @VisibleForTesting boolean mLongClicked;
    private OnClickListener mOnClickListener;
    private final KeyButtonRipple mRipple;
    private final Paint mOvalBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float mDarkIntensity;
    private boolean mHasOvalBg = false;

    private final Runnable mCheckLongPress = new Runnable() {
        public void run() {
            if (isPressed()) {
                // Log.d("KeyButtonView", "longpressed: " + this);
                if (isLongClickable()) {
                    // Just an old-fashioned ImageView
                    performLongClick();
                    mLongClicked = true;
                } else {
                    if (mCode != KEYCODE_UNKNOWN) {
                        sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    }
                    mLongClicked = true;
                }
            }
        }
    };

    public KeyButtonView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // TODO(b/215443343): Figure out better place to set this.
        switch (getId()) {
            case com.android.internal.R.id.input_method_nav_back:
                mCode = KEYCODE_BACK;
                break;
            default:
                mCode = KEYCODE_UNKNOWN;
                break;
        }

        mPlaySounds = true;

        setClickable(true);
        mAudioManager = context.getSystemService(AudioManager.class);

        mRipple = new KeyButtonRipple(context, this,
                com.android.internal.R.dimen.input_method_nav_key_button_ripple_max_width);
        setBackground(mRipple);
        setWillNotDraw(false);
        forceHasOverlappingRendering(false);
    }

    @Override
    public boolean isClickable() {
        return mCode != KEYCODE_UNKNOWN || super.isClickable();
    }

    public void setCode(int code) {
        mCode = code;
    }

    @Override
    public void setOnClickListener(OnClickListener onClickListener) {
        super.setOnClickListener(onClickListener);
        mOnClickListener = onClickListener;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mCode != KEYCODE_UNKNOWN) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_CLICK, null));
            if (isLongClickable()) {
                info.addAction(
                        new AccessibilityNodeInfo.AccessibilityAction(ACTION_LONG_CLICK, null));
            }
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) {
            jumpDrawablesToCurrentState();
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (action == ACTION_CLICK && mCode != KEYCODE_UNKNOWN) {
            sendEvent(KeyEvent.ACTION_DOWN, 0, SystemClock.uptimeMillis());
            sendEvent(KeyEvent.ACTION_UP, mTracking ? KeyEvent.FLAG_TRACKING : 0);
            mTracking = false;
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
            playSoundEffect(SoundEffectConstants.CLICK);
            return true;
        } else if (action == ACTION_LONG_CLICK && mCode != KEYCODE_UNKNOWN) {
            sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
            sendEvent(KeyEvent.ACTION_UP, mTracking ? KeyEvent.FLAG_TRACKING : 0);
            mTracking = false;
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
            return true;
        }
        return super.performAccessibilityActionInternal(action, arguments);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final boolean showSwipeUI = false; // mOverviewProxyService.shouldShowSwipeUpUI();
        final int action = ev.getAction();
        int x, y;
        if (action == MotionEvent.ACTION_DOWN) {
            mGestureAborted = false;
        }
        if (mGestureAborted) {
            setPressed(false);
            return false;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownTime = SystemClock.uptimeMillis();
                mLongClicked = false;
                setPressed(true);

                // Use raw X and Y to detect gestures in case a parent changes the x and y values
                mTouchDownX = (int) ev.getRawX();
                mTouchDownY = (int) ev.getRawY();
                if (mCode != KEYCODE_UNKNOWN) {
                    sendEvent(KeyEvent.ACTION_DOWN, 0, mDownTime);
                } else {
                    // Provide the same haptic feedback that the system offers for virtual keys.
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                if (!showSwipeUI) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                }
                removeCallbacks(mCheckLongPress);
                postDelayed(mCheckLongPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                x = (int) ev.getRawX();
                y = (int) ev.getRawY();

                float slop = getQuickStepTouchSlopPx(getContext());
                if (Math.abs(x - mTouchDownX) > slop || Math.abs(y - mTouchDownY) > slop) {
                    // When quick step is enabled, prevent animating the ripple triggered by
                    // setPressed and decide to run it on touch up
                    setPressed(false);
                    removeCallbacks(mCheckLongPress);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                if (mCode != KEYCODE_UNKNOWN) {
                    sendEvent(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED);
                }
                removeCallbacks(mCheckLongPress);
                break;
            case MotionEvent.ACTION_UP:
                final boolean doIt = isPressed() && !mLongClicked;
                setPressed(false);
                final boolean doHapticFeedback = (SystemClock.uptimeMillis() - mDownTime) > 150;
                if (showSwipeUI) {
                    if (doIt) {
                        // Apply haptic feedback on touch up since there is none on touch down
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        playSoundEffect(SoundEffectConstants.CLICK);
                    }
                } else if (doHapticFeedback && !mLongClicked) {
                    // Always send a release ourselves because it doesn't seem to be sent elsewhere
                    // and it feels weird to sometimes get a release haptic and other times not.
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                }
                if (mCode != KEYCODE_UNKNOWN) {
                    if (doIt) {
                        sendEvent(KeyEvent.ACTION_UP, mTracking ? KeyEvent.FLAG_TRACKING : 0);
                        mTracking = false;
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                    } else {
                        sendEvent(KeyEvent.ACTION_UP, KeyEvent.FLAG_CANCELED);
                    }
                } else {
                    // no key code, just a regular ImageView
                    if (doIt && mOnClickListener != null) {
                        mOnClickListener.onClick(this);
                        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
                    }
                }
                removeCallbacks(mCheckLongPress);
                break;
        }

        return true;
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);

        if (drawable == null) {
            return;
        }
        KeyButtonDrawable keyButtonDrawable = (KeyButtonDrawable) drawable;
        keyButtonDrawable.setDarkIntensity(mDarkIntensity);
        mHasOvalBg = keyButtonDrawable.hasOvalBg();
        if (mHasOvalBg) {
            mOvalBgPaint.setColor(keyButtonDrawable.getDrawableBackgroundColor());
        }
        mRipple.setType(keyButtonDrawable.hasOvalBg() ? KeyButtonRipple.Type.OVAL
                : KeyButtonRipple.Type.ROUNDED_RECT);
    }

    @Override
    public void playSoundEffect(int soundConstant) {
        if (!mPlaySounds) return;
        mAudioManager.playSoundEffect(soundConstant);
    }

    private void sendEvent(int action, int flags) {
        sendEvent(action, flags, SystemClock.uptimeMillis());
    }

    private void sendEvent(int action, int flags, long when) {
        if (mCode == KeyEvent.KEYCODE_BACK && flags != KeyEvent.FLAG_LONG_PRESS) {
            if (action == MotionEvent.ACTION_UP) {
                // TODO(b/215443343): Implement notifyBackAction();
            }
        }

        // TODO(b/215443343): Consolidate this logic to somewhere else.
        if (mContext instanceof InputMethodService) {
            final int repeatCount = (flags & KeyEvent.FLAG_LONG_PRESS) != 0 ? 1 : 0;
            final KeyEvent ev = new KeyEvent(mDownTime, when, action, mCode, repeatCount,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    flags | KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            int displayId = INVALID_DISPLAY;

            // Make KeyEvent work on multi-display environment
            if (getDisplay() != null) {
                displayId = getDisplay().getDisplayId();
            }
            if (displayId != INVALID_DISPLAY) {
                ev.setDisplayId(displayId);
            }
            final InputMethodService ims = (InputMethodService) mContext;
            final boolean handled;
            switch (action) {
                case KeyEvent.ACTION_DOWN:
                    handled = ims.onKeyDown(ev.getKeyCode(), ev);
                    mTracking = handled && ev.getRepeatCount() == 0
                            && (ev.getFlags() & KeyEvent.FLAG_START_TRACKING) != 0;
                    break;
                case KeyEvent.ACTION_UP:
                    handled = ims.onKeyUp(ev.getKeyCode(), ev);
                    break;
                default:
                    handled = false;
                    break;
            }
            if (!handled) {
                final InputConnection ic = ims.getCurrentInputConnection();
                if (ic != null) {
                    ic.sendKeyEvent(ev);
                }
            }
        }
    }

    @Override
    public void setDarkIntensity(float darkIntensity) {
        mDarkIntensity = darkIntensity;

        Drawable drawable = getDrawable();
        if (drawable != null) {
            ((KeyButtonDrawable) drawable).setDarkIntensity(darkIntensity);
            // Since we reuse the same drawable for multiple views, we need to invalidate the view
            // manually.
            invalidate();
        }
        mRipple.setDarkIntensity(darkIntensity);
    }

    @Override
    public void setDelayTouchFeedback(boolean shouldDelay) {
        mRipple.setDelayTouchFeedback(shouldDelay);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mHasOvalBg) {
            int d = Math.min(getWidth(), getHeight());
            canvas.drawOval(0, 0, d, d, mOvalBgPaint);
        }
        super.draw(canvas);
    }

    /**
     * Ratio of quickstep touch slop (when system takes over the touch) to view touch slop
     */
    public static final float QUICKSTEP_TOUCH_SLOP_RATIO = 3;

    /**
     * Touch slop for quickstep gesture
     */
    private static float getQuickStepTouchSlopPx(Context context) {
        return QUICKSTEP_TOUCH_SLOP_RATIO * ViewConfiguration.get(context).getScaledTouchSlop();
    }
}
