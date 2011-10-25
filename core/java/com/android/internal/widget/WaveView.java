/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.widget;

import java.util.ArrayList;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;

/**
 * A special widget containing a center and outer ring. Moving the center ring to the outer ring
 * causes an event that can be caught by implementing OnTriggerListener.
 */
public class WaveView extends View implements ValueAnimator.AnimatorUpdateListener {
    private static final String TAG = "WaveView";
    private static final boolean DBG = false;
    private static final int WAVE_COUNT = 20; // default wave count
    private static final long VIBRATE_SHORT = 20;  // msec
    private static final long VIBRATE_LONG = 20;  // msec

    // Lock state machine states
    private static final int STATE_RESET_LOCK = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_START_ATTEMPT = 2;
    private static final int STATE_ATTEMPTING = 3;
    private static final int STATE_UNLOCK_ATTEMPT = 4;
    private static final int STATE_UNLOCK_SUCCESS = 5;

    // Animation properties.
    private static final long DURATION = 300; // duration of transitional animations
    private static final long FINAL_DURATION = 200; // duration of final animations when unlocking
    private static final long RING_DELAY = 1300; // when to start fading animated rings
    private static final long FINAL_DELAY = 200; // delay for unlock success animation
    private static final long SHORT_DELAY = 100; // for starting one animation after another.
    private static final long WAVE_DURATION = 2000; // amount of time for way to expand/decay
    private static final long RESET_TIMEOUT = 3000; // elapsed time of inactivity before we reset
    private static final long DELAY_INCREMENT = 15; // increment per wave while tracking motion
    private static final long DELAY_INCREMENT2 = 12; // increment per wave while not tracking
    private static final long WAVE_DELAY = WAVE_DURATION / WAVE_COUNT; // initial propagation delay

    /**
     * The scale by which to multiply the unlock handle width to compute the radius
     * in which it can be grabbed when accessibility is disabled.
     */
    private static final float GRAB_HANDLE_RADIUS_SCALE_ACCESSIBILITY_DISABLED = 0.5f;

    /**
     * The scale by which to multiply the unlock handle width to compute the radius
     * in which it can be grabbed when accessibility is enabled (more generous).
     */
    private static final float GRAB_HANDLE_RADIUS_SCALE_ACCESSIBILITY_ENABLED = 1.0f;

    private Vibrator mVibrator;
    private OnTriggerListener mOnTriggerListener;
    private ArrayList<DrawableHolder> mDrawables = new ArrayList<DrawableHolder>(3);
    private ArrayList<DrawableHolder> mLightWaves = new ArrayList<DrawableHolder>(WAVE_COUNT);
    private boolean mFingerDown = false;
    private float mRingRadius = 182.0f; // Radius of bitmap ring. Used to snap halo to it
    private int mSnapRadius = 136; // minimum threshold for drag unlock
    private int mWaveCount = WAVE_COUNT;  // number of waves
    private long mWaveTimerDelay = WAVE_DELAY;
    private int mCurrentWave = 0;
    private float mLockCenterX; // center of widget as dictated by widget size
    private float mLockCenterY;
    private float mMouseX; // current mouse position as of last touch event
    private float mMouseY;
    private DrawableHolder mUnlockRing;
    private DrawableHolder mUnlockDefault;
    private DrawableHolder mUnlockHalo;
    private int mLockState = STATE_RESET_LOCK;
    private int mGrabbedState = OnTriggerListener.NO_HANDLE;
    private boolean mWavesRunning;
    private boolean mFinishWaves;

    public WaveView(Context context) {
        this(context, null);
    }

    public WaveView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.WaveView);
        // mOrientation = a.getInt(R.styleable.WaveView_orientation, HORIZONTAL);
        // a.recycle();

        initDrawables();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mLockCenterX = 0.5f * w;
        mLockCenterY = 0.5f * h;
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    protected int getSuggestedMinimumWidth() {
        // View should be large enough to contain the unlock ring + halo
        return mUnlockRing.getWidth() + mUnlockHalo.getWidth();
    }

    @Override
    protected int getSuggestedMinimumHeight() {
        // View should be large enough to contain the unlock ring + halo
        return mUnlockRing.getHeight() + mUnlockHalo.getHeight();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSpecSize =  MeasureSpec.getSize(widthMeasureSpec);
        int heightSpecSize =  MeasureSpec.getSize(heightMeasureSpec);
        int width;
        int height;

        if (widthSpecMode == MeasureSpec.AT_MOST) {
            width = Math.min(widthSpecSize, getSuggestedMinimumWidth());
        } else if (widthSpecMode == MeasureSpec.EXACTLY) {
            width = widthSpecSize;
        } else {
            width = getSuggestedMinimumWidth();
        }

        if (heightSpecMode == MeasureSpec.AT_MOST) {
            height = Math.min(heightSpecSize, getSuggestedMinimumWidth());
        } else if (heightSpecMode == MeasureSpec.EXACTLY) {
            height = heightSpecSize;
        } else {
            height = getSuggestedMinimumHeight();
        }

        setMeasuredDimension(width, height);
    }

    private void initDrawables() {
        mUnlockRing = new DrawableHolder(createDrawable(R.drawable.unlock_ring));
        mUnlockRing.setX(mLockCenterX);
        mUnlockRing.setY(mLockCenterY);
        mUnlockRing.setScaleX(0.1f);
        mUnlockRing.setScaleY(0.1f);
        mUnlockRing.setAlpha(0.0f);
        mDrawables.add(mUnlockRing);

        mUnlockDefault = new DrawableHolder(createDrawable(R.drawable.unlock_default));
        mUnlockDefault.setX(mLockCenterX);
        mUnlockDefault.setY(mLockCenterY);
        mUnlockDefault.setScaleX(0.1f);
        mUnlockDefault.setScaleY(0.1f);
        mUnlockDefault.setAlpha(0.0f);
        mDrawables.add(mUnlockDefault);

        mUnlockHalo = new DrawableHolder(createDrawable(R.drawable.unlock_halo));
        mUnlockHalo.setX(mLockCenterX);
        mUnlockHalo.setY(mLockCenterY);
        mUnlockHalo.setScaleX(0.1f);
        mUnlockHalo.setScaleY(0.1f);
        mUnlockHalo.setAlpha(0.0f);
        mDrawables.add(mUnlockHalo);

        BitmapDrawable wave = createDrawable(R.drawable.unlock_wave);
        for (int i = 0; i < mWaveCount; i++) {
            DrawableHolder holder = new DrawableHolder(wave);
            mLightWaves.add(holder);
            holder.setAlpha(0.0f);
        }
    }

    private void waveUpdateFrame(float mouseX, float mouseY, boolean fingerDown) {
        double distX = mouseX - mLockCenterX;
        double distY = mouseY - mLockCenterY;
        int dragDistance = (int) Math.ceil(Math.hypot(distX, distY));
        double touchA = Math.atan2(distX, distY);
        float ringX = (float) (mLockCenterX + mRingRadius * Math.sin(touchA));
        float ringY = (float) (mLockCenterY + mRingRadius * Math.cos(touchA));

        switch (mLockState) {
            case STATE_RESET_LOCK:
                if (DBG) Log.v(TAG, "State RESET_LOCK");
                mWaveTimerDelay = WAVE_DELAY;
                for (int i = 0; i < mLightWaves.size(); i++) {
                    DrawableHolder holder = mLightWaves.get(i);
                    holder.addAnimTo(300, 0, "alpha", 0.0f, false);
                }
                for (int i = 0; i < mLightWaves.size(); i++) {
                    mLightWaves.get(i).startAnimations(this);
                }

                mUnlockRing.addAnimTo(DURATION, 0, "x", mLockCenterX, true);
                mUnlockRing.addAnimTo(DURATION, 0, "y", mLockCenterY, true);
                mUnlockRing.addAnimTo(DURATION, 0, "scaleX", 0.1f, true);
                mUnlockRing.addAnimTo(DURATION, 0, "scaleY", 0.1f, true);
                mUnlockRing.addAnimTo(DURATION, 0, "alpha", 0.0f, true);

                mUnlockDefault.removeAnimationFor("x");
                mUnlockDefault.removeAnimationFor("y");
                mUnlockDefault.removeAnimationFor("scaleX");
                mUnlockDefault.removeAnimationFor("scaleY");
                mUnlockDefault.removeAnimationFor("alpha");
                mUnlockDefault.setX(mLockCenterX);
                mUnlockDefault.setY(mLockCenterY);
                mUnlockDefault.setScaleX(0.1f);
                mUnlockDefault.setScaleY(0.1f);
                mUnlockDefault.setAlpha(0.0f);
                mUnlockDefault.addAnimTo(DURATION, SHORT_DELAY, "scaleX", 1.0f, true);
                mUnlockDefault.addAnimTo(DURATION, SHORT_DELAY, "scaleY", 1.0f, true);
                mUnlockDefault.addAnimTo(DURATION, SHORT_DELAY, "alpha", 1.0f, true);

                mUnlockHalo.removeAnimationFor("x");
                mUnlockHalo.removeAnimationFor("y");
                mUnlockHalo.removeAnimationFor("scaleX");
                mUnlockHalo.removeAnimationFor("scaleY");
                mUnlockHalo.removeAnimationFor("alpha");
                mUnlockHalo.setX(mLockCenterX);
                mUnlockHalo.setY(mLockCenterY);
                mUnlockHalo.setScaleX(0.1f);
                mUnlockHalo.setScaleY(0.1f);
                mUnlockHalo.setAlpha(0.0f);
                mUnlockHalo.addAnimTo(DURATION, SHORT_DELAY, "x", mLockCenterX, true);
                mUnlockHalo.addAnimTo(DURATION, SHORT_DELAY, "y", mLockCenterY, true);
                mUnlockHalo.addAnimTo(DURATION, SHORT_DELAY, "scaleX", 1.0f, true);
                mUnlockHalo.addAnimTo(DURATION, SHORT_DELAY, "scaleY", 1.0f, true);
                mUnlockHalo.addAnimTo(DURATION, SHORT_DELAY, "alpha", 1.0f, true);

                removeCallbacks(mLockTimerActions);

                mLockState = STATE_READY;
                break;

            case STATE_READY:
                if (DBG) Log.v(TAG, "State READY");
                mWaveTimerDelay = WAVE_DELAY;
                break;

            case STATE_START_ATTEMPT:
                if (DBG) Log.v(TAG, "State START_ATTEMPT");
                mUnlockDefault.removeAnimationFor("x");
                mUnlockDefault.removeAnimationFor("y");
                mUnlockDefault.removeAnimationFor("scaleX");
                mUnlockDefault.removeAnimationFor("scaleY");
                mUnlockDefault.removeAnimationFor("alpha");
                mUnlockDefault.setX(mLockCenterX + 182);
                mUnlockDefault.setY(mLockCenterY);
                mUnlockDefault.setScaleX(0.1f);
                mUnlockDefault.setScaleY(0.1f);
                mUnlockDefault.setAlpha(0.0f);

                mUnlockDefault.addAnimTo(DURATION, SHORT_DELAY, "scaleX", 1.0f, false);
                mUnlockDefault.addAnimTo(DURATION, SHORT_DELAY, "scaleY", 1.0f, false);
                mUnlockDefault.addAnimTo(DURATION, SHORT_DELAY, "alpha", 1.0f, false);

                mUnlockRing.addAnimTo(DURATION, 0, "scaleX", 1.0f, true);
                mUnlockRing.addAnimTo(DURATION, 0, "scaleY", 1.0f, true);
                mUnlockRing.addAnimTo(DURATION, 0, "alpha", 1.0f, true);

                mLockState = STATE_ATTEMPTING;
                break;

            case STATE_ATTEMPTING:
                if (DBG) Log.v(TAG, "State ATTEMPTING (fingerDown = " + fingerDown + ")");
                if (dragDistance > mSnapRadius) {
                    mFinishWaves = true; // don't start any more waves.
                    if (fingerDown) {
                        mUnlockHalo.addAnimTo(0, 0, "x", ringX, true);
                        mUnlockHalo.addAnimTo(0, 0, "y", ringY, true);
                        mUnlockHalo.addAnimTo(0, 0, "scaleX", 1.0f, true);
                        mUnlockHalo.addAnimTo(0, 0, "scaleY", 1.0f, true);
                        mUnlockHalo.addAnimTo(0, 0, "alpha", 1.0f, true);
                    }  else {
                        if (DBG) Log.v(TAG, "up detected, moving to STATE_UNLOCK_ATTEMPT");
                        mLockState = STATE_UNLOCK_ATTEMPT;
                    }
                } else {
                    // If waves have stopped, we need to kick them off again...
                    if (!mWavesRunning) {
                        mWavesRunning = true;
                        mFinishWaves = false;
                        // mWaveTimerDelay = WAVE_DELAY;
                        postDelayed(mAddWaveAction, mWaveTimerDelay);
                    }
                    mUnlockHalo.addAnimTo(0, 0, "x", mouseX, true);
                    mUnlockHalo.addAnimTo(0, 0, "y", mouseY, true);
                    mUnlockHalo.addAnimTo(0, 0, "scaleX", 1.0f, true);
                    mUnlockHalo.addAnimTo(0, 0, "scaleY", 1.0f, true);
                    mUnlockHalo.addAnimTo(0, 0, "alpha", 1.0f, true);
                }
                break;

            case STATE_UNLOCK_ATTEMPT:
                if (DBG) Log.v(TAG, "State UNLOCK_ATTEMPT");
                if (dragDistance > mSnapRadius) {
                    for (int n = 0; n < mLightWaves.size(); n++) {
                        DrawableHolder wave = mLightWaves.get(n);
                        long delay = 1000L*(6 + n - mCurrentWave)/10L;
                        wave.addAnimTo(FINAL_DURATION, delay, "x", ringX, true);
                        wave.addAnimTo(FINAL_DURATION, delay, "y", ringY, true);
                        wave.addAnimTo(FINAL_DURATION, delay, "scaleX", 0.1f, true);
                        wave.addAnimTo(FINAL_DURATION, delay, "scaleY", 0.1f, true);
                        wave.addAnimTo(FINAL_DURATION, delay, "alpha", 0.0f, true);
                    }
                    for (int i = 0; i < mLightWaves.size(); i++) {
                        mLightWaves.get(i).startAnimations(this);
                    }

                    mUnlockRing.addAnimTo(FINAL_DURATION, 0, "x", ringX, false);
                    mUnlockRing.addAnimTo(FINAL_DURATION, 0, "y", ringY, false);
                    mUnlockRing.addAnimTo(FINAL_DURATION, 0, "scaleX", 0.1f, false);
                    mUnlockRing.addAnimTo(FINAL_DURATION, 0, "scaleY", 0.1f, false);
                    mUnlockRing.addAnimTo(FINAL_DURATION, 0, "alpha", 0.0f, false);

                    mUnlockRing.addAnimTo(FINAL_DURATION, FINAL_DELAY, "alpha", 0.0f, false);

                    mUnlockDefault.removeAnimationFor("x");
                    mUnlockDefault.removeAnimationFor("y");
                    mUnlockDefault.removeAnimationFor("scaleX");
                    mUnlockDefault.removeAnimationFor("scaleY");
                    mUnlockDefault.removeAnimationFor("alpha");
                    mUnlockDefault.setX(ringX);
                    mUnlockDefault.setY(ringY);
                    mUnlockDefault.setScaleX(0.1f);
                    mUnlockDefault.setScaleY(0.1f);
                    mUnlockDefault.setAlpha(0.0f);

                    mUnlockDefault.addAnimTo(FINAL_DURATION, 0, "x", ringX, true);
                    mUnlockDefault.addAnimTo(FINAL_DURATION, 0, "y", ringY, true);
                    mUnlockDefault.addAnimTo(FINAL_DURATION, 0, "scaleX", 1.0f, true);
                    mUnlockDefault.addAnimTo(FINAL_DURATION, 0, "scaleY", 1.0f, true);
                    mUnlockDefault.addAnimTo(FINAL_DURATION, 0, "alpha", 1.0f, true);

                    mUnlockDefault.addAnimTo(FINAL_DURATION, FINAL_DELAY, "scaleX", 3.0f, false);
                    mUnlockDefault.addAnimTo(FINAL_DURATION, FINAL_DELAY, "scaleY", 3.0f, false);
                    mUnlockDefault.addAnimTo(FINAL_DURATION, FINAL_DELAY, "alpha", 0.0f, false);

                    mUnlockHalo.addAnimTo(FINAL_DURATION, 0, "x", ringX, false);
                    mUnlockHalo.addAnimTo(FINAL_DURATION, 0, "y", ringY, false);

                    mUnlockHalo.addAnimTo(FINAL_DURATION, FINAL_DELAY, "scaleX", 3.0f, false);
                    mUnlockHalo.addAnimTo(FINAL_DURATION, FINAL_DELAY, "scaleY", 3.0f, false);
                    mUnlockHalo.addAnimTo(FINAL_DURATION, FINAL_DELAY, "alpha", 0.0f, false);

                    removeCallbacks(mLockTimerActions);

                    postDelayed(mLockTimerActions, RESET_TIMEOUT);

                    dispatchTriggerEvent(OnTriggerListener.CENTER_HANDLE);
                    mLockState = STATE_UNLOCK_SUCCESS;
                } else {
                    mLockState = STATE_RESET_LOCK;
                }
                break;

            case STATE_UNLOCK_SUCCESS:
                if (DBG) Log.v(TAG, "State UNLOCK_SUCCESS");
                removeCallbacks(mAddWaveAction);
                break;

            default:
                if (DBG) Log.v(TAG, "Unknown state " + mLockState);
                break;
        }
        mUnlockDefault.startAnimations(this);
        mUnlockHalo.startAnimations(this);
        mUnlockRing.startAnimations(this);
    }

    BitmapDrawable createDrawable(int resId) {
        Resources res = getResources();
        Bitmap bitmap = BitmapFactory.decodeResource(res, resId);
        return new BitmapDrawable(res, bitmap);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        waveUpdateFrame(mMouseX, mMouseY, mFingerDown);
        for (int i = 0; i < mDrawables.size(); ++i) {
            mDrawables.get(i).draw(canvas);
        }
        for (int i = 0; i < mLightWaves.size(); ++i) {
            mLightWaves.get(i).draw(canvas);
        }
    }

    private final Runnable mLockTimerActions = new Runnable() {
        public void run() {
            if (DBG) Log.v(TAG, "LockTimerActions");
            // reset lock after inactivity
            if (mLockState == STATE_ATTEMPTING) {
                if (DBG) Log.v(TAG, "Timer resets to STATE_RESET_LOCK");
                mLockState = STATE_RESET_LOCK;
            }
            // for prototype, reset after successful unlock
            if (mLockState == STATE_UNLOCK_SUCCESS) {
                if (DBG) Log.v(TAG, "Timer resets to STATE_RESET_LOCK after success");
                mLockState = STATE_RESET_LOCK;
            }
            invalidate();
        }
    };

    private final Runnable mAddWaveAction = new Runnable() {
        public void run() {
            double distX = mMouseX - mLockCenterX;
            double distY = mMouseY - mLockCenterY;
            int dragDistance = (int) Math.ceil(Math.hypot(distX, distY));
            if (mLockState == STATE_ATTEMPTING && dragDistance < mSnapRadius
                    && mWaveTimerDelay >= WAVE_DELAY) {
                mWaveTimerDelay = Math.min(WAVE_DURATION, mWaveTimerDelay + DELAY_INCREMENT);

                DrawableHolder wave = mLightWaves.get(mCurrentWave);
                wave.setAlpha(0.0f);
                wave.setScaleX(0.2f);
                wave.setScaleY(0.2f);
                wave.setX(mMouseX);
                wave.setY(mMouseY);

                wave.addAnimTo(WAVE_DURATION, 0, "x", mLockCenterX, true);
                wave.addAnimTo(WAVE_DURATION, 0, "y", mLockCenterY, true);
                wave.addAnimTo(WAVE_DURATION*2/3, 0, "alpha", 1.0f, true);
                wave.addAnimTo(WAVE_DURATION, 0, "scaleX", 1.0f, true);
                wave.addAnimTo(WAVE_DURATION, 0, "scaleY", 1.0f, true);

                wave.addAnimTo(1000, RING_DELAY, "alpha", 0.0f, false);
                wave.startAnimations(WaveView.this);

                mCurrentWave = (mCurrentWave+1) % mWaveCount;
                if (DBG) Log.v(TAG, "WaveTimerDelay: start new wave in " + mWaveTimerDelay);
            } else {
                mWaveTimerDelay += DELAY_INCREMENT2;
            }
            if (mFinishWaves) {
                // sentinel used to restart the waves after they've stopped
                mWavesRunning = false;
            } else {
                postDelayed(mAddWaveAction, mWaveTimerDelay);
            }
        }
    };

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (AccessibilityManager.getInstance(mContext).isTouchExplorationEnabled()) {
            final int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    event.setAction(MotionEvent.ACTION_DOWN);
                    break;
                case MotionEvent.ACTION_HOVER_MOVE:
                    event.setAction(MotionEvent.ACTION_MOVE);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    event.setAction(MotionEvent.ACTION_UP);
                    break;
            }
            onTouchEvent(event);
            event.setAction(action);
        }
        return super.onHoverEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();
        mMouseX = event.getX();
        mMouseY = event.getY();
        boolean handled = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(mLockTimerActions);
                mFingerDown = true;
                tryTransitionToStartAttemptState(event);
                handled = true;
                break;

            case MotionEvent.ACTION_MOVE:
                tryTransitionToStartAttemptState(event);
                handled = true;
                break;

            case MotionEvent.ACTION_UP:
                if (DBG) Log.v(TAG, "ACTION_UP");
                mFingerDown = false;
                postDelayed(mLockTimerActions, RESET_TIMEOUT);
                setGrabbedState(OnTriggerListener.NO_HANDLE);
                // Normally the state machine is driven by user interaction causing redraws.
                // However, when there's no more user interaction and no running animations,
                // the state machine stops advancing because onDraw() never gets called.
                // The following ensures we advance to the next state in this case,
                // either STATE_UNLOCK_ATTEMPT or STATE_RESET_LOCK.
                waveUpdateFrame(mMouseX, mMouseY, mFingerDown);
                handled = true;
                break;

            case MotionEvent.ACTION_CANCEL:
                mFingerDown = false;
                handled = true;
                break;
        }
        invalidate();
        return handled ? true : super.onTouchEvent(event);
    }

    /**
     * Tries to transition to start attempt state.
     *
     * @param event A motion event.
     */
    private void tryTransitionToStartAttemptState(MotionEvent event) {
        final float dx = event.getX() - mUnlockHalo.getX();
        final float dy = event.getY() - mUnlockHalo.getY();
        float dist = (float) Math.hypot(dx, dy);
        if (dist <= getScaledGrabHandleRadius()) {
            setGrabbedState(OnTriggerListener.CENTER_HANDLE);
            if (mLockState == STATE_READY) {
                mLockState = STATE_START_ATTEMPT;
                if (AccessibilityManager.getInstance(mContext).isEnabled()) {
                    announceUnlockHandle();
                }
            }
        }
    }

    /**
     * @return The radius in which the handle is grabbed scaled based on
     *     whether accessibility is enabled.
     */
    private float getScaledGrabHandleRadius() {
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            return GRAB_HANDLE_RADIUS_SCALE_ACCESSIBILITY_ENABLED * mUnlockHalo.getWidth();
        } else {
            return GRAB_HANDLE_RADIUS_SCALE_ACCESSIBILITY_DISABLED * mUnlockHalo.getWidth();
        }
    }

    /**
     * Announces the unlock handle if accessibility is enabled.
     */
    private void announceUnlockHandle() {
        setContentDescription(mContext.getString(R.string.description_target_unlock_tablet));
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        setContentDescription(null);
    }

    /**
     * Triggers haptic feedback.
     */
    private synchronized void vibrate(long duration) {
        if (mVibrator == null) {
            mVibrator = (android.os.Vibrator)
                    getContext().getSystemService(Context.VIBRATOR_SERVICE);
        }
        mVibrator.vibrate(duration);
    }

    /**
     * Registers a callback to be invoked when the user triggers an event.
     *
     * @param listener the OnDialTriggerListener to attach to this view
     */
    public void setOnTriggerListener(OnTriggerListener listener) {
        mOnTriggerListener = listener;
    }

    /**
     * Dispatches a trigger event to listener. Ignored if a listener is not set.
     * @param whichHandle the handle that triggered the event.
     */
    private void dispatchTriggerEvent(int whichHandle) {
        vibrate(VIBRATE_LONG);
        if (mOnTriggerListener != null) {
            mOnTriggerListener.onTrigger(this, whichHandle);
        }
    }

    /**
     * Sets the current grabbed state, and dispatches a grabbed state change
     * event to our listener.
     */
    private void setGrabbedState(int newState) {
        if (newState != mGrabbedState) {
            mGrabbedState = newState;
            if (mOnTriggerListener != null) {
                mOnTriggerListener.onGrabbedStateChange(this, mGrabbedState);
            }
        }
    }

    public interface OnTriggerListener {
        /**
         * Sent when the user releases the handle.
         */
        public static final int NO_HANDLE = 0;

        /**
         * Sent when the user grabs the center handle
         */
        public static final int CENTER_HANDLE = 10;

        /**
         * Called when the user drags the center ring beyond a threshold.
         */
        void onTrigger(View v, int whichHandle);

        /**
         * Called when the "grabbed state" changes (i.e. when the user either grabs or releases
         * one of the handles.)
         *
         * @param v the view that was triggered
         * @param grabbedState the new state: {@link #NO_HANDLE}, {@link #CENTER_HANDLE},
         */
        void onGrabbedStateChange(View v, int grabbedState);
    }

    public void onAnimationUpdate(ValueAnimator animation) {
        invalidate();
    }

    public void reset() {
        if (DBG) Log.v(TAG, "reset() : resets state to STATE_RESET_LOCK");
        mLockState = STATE_RESET_LOCK;
        invalidate();
    }
}
