/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.AudioAttributes;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import static android.view.animation.AnimationUtils.currentAnimationTimeMillis;

import com.android.internal.R;


/**
 * Custom view that presents up to two items that are selectable by rotating a semi-circle from
 * left to right, or right to left.  Used by incoming call screen, and the lock screen when no
 * security pattern is set.
 */
public class RotarySelector extends View {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    private static final String LOG_TAG = "RotarySelector";
    private static final boolean DBG = false;
    private static final boolean VISUAL_DEBUG = false;

    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build();

    // Listener for onDialTrigger() callbacks.
    private OnDialTriggerListener mOnDialTriggerListener;

    private float mDensity;

    // UI elements
    private Bitmap mBackground;
    private Bitmap mDimple;
    private Bitmap mDimpleDim;

    private Bitmap mLeftHandleIcon;
    private Bitmap mRightHandleIcon;

    private Bitmap mArrowShortLeftAndRight;
    private Bitmap mArrowLongLeft;  // Long arrow starting on the left, pointing clockwise
    private Bitmap mArrowLongRight;  // Long arrow starting on the right, pointing CCW

    // positions of the left and right handle
    private int mLeftHandleX;
    private int mRightHandleX;

    // current offset of rotary widget along the x axis
    private int mRotaryOffsetX = 0;

    // state of the animation used to bring the handle back to its start position when
    // the user lets go before triggering an action
    private boolean mAnimating = false;
    private long mAnimationStartTime;
    private long mAnimationDuration;
    private int mAnimatingDeltaXStart;   // the animation will interpolate from this delta to zero
    private int mAnimatingDeltaXEnd;

    private DecelerateInterpolator mInterpolator;

    private Paint mPaint = new Paint();

    // used to rotate the background and arrow assets depending on orientation
    final Matrix mBgMatrix = new Matrix();
    final Matrix mArrowMatrix = new Matrix();

    /**
     * If the user is currently dragging something.
     */
    private int mGrabbedState = NOTHING_GRABBED;
    public static final int NOTHING_GRABBED = 0;
    public static final int LEFT_HANDLE_GRABBED = 1;
    public static final int RIGHT_HANDLE_GRABBED = 2;

    /**
     * Whether the user has triggered something (e.g dragging the left handle all the way over to
     * the right).
     */
    private boolean mTriggered = false;

    // Vibration (haptic feedback)
    private Vibrator mVibrator;
    private static final long VIBRATE_SHORT = 20;  // msec
    private static final long VIBRATE_LONG = 20;  // msec

    /**
     * The drawable for the arrows need to be scrunched this many dips towards the rotary bg below
     * it.
     */
    private static final int ARROW_SCRUNCH_DIP = 6;

    /**
     * How far inset the left and right circles should be
     */
    private static final int EDGE_PADDING_DIP = 9;

    /**
     * How far from the edge of the screen the user must drag to trigger the event.
     */
    private static final int EDGE_TRIGGER_DIP = 100;

    /**
     * Dimensions of arc in background drawable.
     */
    static final int OUTER_ROTARY_RADIUS_DIP = 390;
    static final int ROTARY_STROKE_WIDTH_DIP = 83;
    static final int SNAP_BACK_ANIMATION_DURATION_MILLIS = 300;
    static final int SPIN_ANIMATION_DURATION_MILLIS = 800;

    private int mEdgeTriggerThresh;
    private int mDimpleWidth;
    private int mBackgroundWidth;
    private int mBackgroundHeight;
    private final int mOuterRadius;
    private final int mInnerRadius;
    private int mDimpleSpacing;

    private VelocityTracker mVelocityTracker;
    private int mMinimumVelocity;
    private int mMaximumVelocity;

    /**
     * The number of dimples we are flinging when we do the "spin" animation.  Used to know when to
     * wrap the icons back around so they "rotate back" onto the screen.
     * @see #updateAnimation()
     */
    private int mDimplesOfFling = 0;

    /**
     * Either {@link #HORIZONTAL} or {@link #VERTICAL}.
     */
    private int mOrientation;


    public RotarySelector(Context context) {
        this(context, null);
    }

    /**
     * Constructor used when this widget is created from a layout file.
     */
    public RotarySelector(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a =
            context.obtainStyledAttributes(attrs, R.styleable.RotarySelector);
        mOrientation = a.getInt(R.styleable.RotarySelector_orientation, HORIZONTAL);
        a.recycle();

        Resources r = getResources();
        mDensity = r.getDisplayMetrics().density;
        if (DBG) log("- Density: " + mDensity);

        // Assets (all are BitmapDrawables).
        mBackground = getBitmapFor(R.drawable.jog_dial_bg);
        mDimple = getBitmapFor(R.drawable.jog_dial_dimple);
        mDimpleDim = getBitmapFor(R.drawable.jog_dial_dimple_dim);

        mArrowLongLeft = getBitmapFor(R.drawable.jog_dial_arrow_long_left_green);
        mArrowLongRight = getBitmapFor(R.drawable.jog_dial_arrow_long_right_red);
        mArrowShortLeftAndRight = getBitmapFor(R.drawable.jog_dial_arrow_short_left_and_right);

        mInterpolator = new DecelerateInterpolator(1f);

        mEdgeTriggerThresh = (int) (mDensity * EDGE_TRIGGER_DIP);

        mDimpleWidth = mDimple.getWidth();

        mBackgroundWidth = mBackground.getWidth();
        mBackgroundHeight = mBackground.getHeight();
        mOuterRadius = (int) (mDensity * OUTER_ROTARY_RADIUS_DIP);
        mInnerRadius = (int) ((OUTER_ROTARY_RADIUS_DIP - ROTARY_STROKE_WIDTH_DIP) * mDensity);

        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity() * 2;
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    private Bitmap getBitmapFor(int resId) {
        return BitmapFactory.decodeResource(getContext().getResources(), resId);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        final int edgePadding = (int) (EDGE_PADDING_DIP * mDensity);
        mLeftHandleX = edgePadding + mDimpleWidth / 2;
        final int length = isHoriz() ? w : h;
        mRightHandleX = length - edgePadding - mDimpleWidth / 2;
        mDimpleSpacing = (length / 2) - mLeftHandleX;

        // bg matrix only needs to be calculated once
        mBgMatrix.setTranslate(0, 0);
        if (!isHoriz()) {
            // set up matrix for translating drawing of background and arrow assets
            final int left = w - mBackgroundHeight;
            mBgMatrix.preRotate(-90, 0, 0);
            mBgMatrix.postTranslate(left, h);

        } else {
            mBgMatrix.postTranslate(0, h - mBackgroundHeight);
        }
    }

    private boolean isHoriz() {
        return mOrientation == HORIZONTAL;
    }

    /**
     * Sets the left handle icon to a given resource.
     *
     * The resource should refer to a Drawable object, or use 0 to remove
     * the icon.
     *
     * @param resId the resource ID.
     */
    public void setLeftHandleResource(int resId) {
        if (resId != 0) {
            mLeftHandleIcon = getBitmapFor(resId);
        }
        invalidate();
    }

    /**
     * Sets the right handle icon to a given resource.
     *
     * The resource should refer to a Drawable object, or use 0 to remove
     * the icon.
     *
     * @param resId the resource ID.
     */
    public void setRightHandleResource(int resId) {
        if (resId != 0) {
            mRightHandleIcon = getBitmapFor(resId);
        }
        invalidate();
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int length = isHoriz() ?
                MeasureSpec.getSize(widthMeasureSpec) :
                MeasureSpec.getSize(heightMeasureSpec);
        final int arrowScrunch = (int) (ARROW_SCRUNCH_DIP * mDensity);
        final int arrowH = mArrowShortLeftAndRight.getHeight();

        // by making the height less than arrow + bg, arrow and bg will be scrunched together,
        // overlaying somewhat (though on transparent portions of the drawable).
        // this works because the arrows are drawn from the top, and the rotary bg is drawn
        // from the bottom.
        final int height = mBackgroundHeight + arrowH - arrowScrunch;

        if (isHoriz()) {
            setMeasuredDimension(length, height);
        } else {
            setMeasuredDimension(height, length);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int width = getWidth();

        if (VISUAL_DEBUG) {
            // draw bounding box around widget
            mPaint.setColor(0xffff0000);
            mPaint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(0, 0, width, getHeight(), mPaint);
        }

        final int height = getHeight();

        // update animating state before we draw anything
        if (mAnimating) {
            updateAnimation();
        }

        // Background:
        canvas.drawBitmap(mBackground, mBgMatrix, mPaint);

        // Draw the correct arrow(s) depending on the current state:
        mArrowMatrix.reset();
        switch (mGrabbedState) {
            case NOTHING_GRABBED:
                //mArrowShortLeftAndRight;
                break;
            case LEFT_HANDLE_GRABBED:
                mArrowMatrix.setTranslate(0, 0);
                if (!isHoriz()) {
                    mArrowMatrix.preRotate(-90, 0, 0);
                    mArrowMatrix.postTranslate(0, height);
                }
                canvas.drawBitmap(mArrowLongLeft, mArrowMatrix, mPaint);
                break;
            case RIGHT_HANDLE_GRABBED:
                mArrowMatrix.setTranslate(0, 0);
                if (!isHoriz()) {
                    mArrowMatrix.preRotate(-90, 0, 0);
                    // since bg width is > height of screen in landscape mode...
                    mArrowMatrix.postTranslate(0, height + (mBackgroundWidth - height));
                }
                canvas.drawBitmap(mArrowLongRight, mArrowMatrix, mPaint);
                break;
            default:
                throw new IllegalStateException("invalid mGrabbedState: " + mGrabbedState);
        }

        final int bgHeight = mBackgroundHeight;
        final int bgTop = isHoriz() ?
                height - bgHeight:
                width - bgHeight;

        if (VISUAL_DEBUG) {
            // draw circle bounding arc drawable: good sanity check we're doing the math correctly
            float or = OUTER_ROTARY_RADIUS_DIP * mDensity;
            final int vOffset = mBackgroundWidth - height;
            final int midX = isHoriz() ? width / 2 : mBackgroundWidth / 2 - vOffset;
            if (isHoriz()) {
                canvas.drawCircle(midX, or + bgTop, or, mPaint);
            } else {
                canvas.drawCircle(or + bgTop, midX, or, mPaint);
            }
        }

        // left dimple / icon
        {
            final int xOffset = mLeftHandleX + mRotaryOffsetX;
            final int drawableY = getYOnArc(
                    mBackgroundWidth,
                    mInnerRadius,
                    mOuterRadius,
                    xOffset);
            final int x = isHoriz() ? xOffset : drawableY + bgTop;
            final int y = isHoriz() ? drawableY + bgTop : height - xOffset;
            if (mGrabbedState != RIGHT_HANDLE_GRABBED) {
                drawCentered(mDimple, canvas, x, y);
                drawCentered(mLeftHandleIcon, canvas, x, y);
            } else {
                drawCentered(mDimpleDim, canvas, x, y);
            }
        }

        // center dimple
        {
            final int xOffset = isHoriz() ?
                    width / 2 + mRotaryOffsetX:
                    height / 2 + mRotaryOffsetX;
            final int drawableY = getYOnArc(
                    mBackgroundWidth,
                    mInnerRadius,
                    mOuterRadius,
                    xOffset);

            if (isHoriz()) {
                drawCentered(mDimpleDim, canvas, xOffset, drawableY + bgTop);
            } else {
                // vertical
                drawCentered(mDimpleDim, canvas, drawableY + bgTop, height - xOffset);
            }
        }

        // right dimple / icon
        {
            final int xOffset = mRightHandleX + mRotaryOffsetX;
            final int drawableY = getYOnArc(
                    mBackgroundWidth,
                    mInnerRadius,
                    mOuterRadius,
                    xOffset);

            final int x = isHoriz() ? xOffset : drawableY + bgTop;
            final int y = isHoriz() ? drawableY + bgTop : height - xOffset;
            if (mGrabbedState != LEFT_HANDLE_GRABBED) {
                drawCentered(mDimple, canvas, x, y);
                drawCentered(mRightHandleIcon, canvas, x, y);
            } else {
                drawCentered(mDimpleDim, canvas, x, y);
            }
        }

        // draw extra left hand dimples
        int dimpleLeft = mRotaryOffsetX + mLeftHandleX - mDimpleSpacing;
        final int halfdimple = mDimpleWidth / 2;
        while (dimpleLeft > -halfdimple) {
            final int drawableY = getYOnArc(
                    mBackgroundWidth,
                    mInnerRadius,
                    mOuterRadius,
                    dimpleLeft);

            if (isHoriz()) {
                drawCentered(mDimpleDim, canvas, dimpleLeft, drawableY + bgTop);
            } else {
                drawCentered(mDimpleDim, canvas, drawableY + bgTop, height - dimpleLeft);
            }
            dimpleLeft -= mDimpleSpacing;
        }

        // draw extra right hand dimples
        int dimpleRight = mRotaryOffsetX + mRightHandleX + mDimpleSpacing;
        final int rightThresh = mRight + halfdimple;
        while (dimpleRight < rightThresh) {
            final int drawableY = getYOnArc(
                    mBackgroundWidth,
                    mInnerRadius,
                    mOuterRadius,
                    dimpleRight);

            if (isHoriz()) {
                drawCentered(mDimpleDim, canvas, dimpleRight, drawableY + bgTop);
            } else {
                drawCentered(mDimpleDim, canvas, drawableY + bgTop, height - dimpleRight);
            }
            dimpleRight += mDimpleSpacing;
        }
    }

    /**
     * Assuming bitmap is a bounding box around a piece of an arc drawn by two concentric circles
     * (as the background drawable for the rotary widget is), and given an x coordinate along the
     * drawable, return the y coordinate of a point on the arc that is between the two concentric
     * circles.  The resulting y combined with the incoming x is a point along the circle in
     * between the two concentric circles.
     *
     * @param backgroundWidth The width of the asset (the bottom of the box surrounding the arc).
     * @param innerRadius The radius of the circle that intersects the drawable at the bottom two
     *        corders of the drawable (top two corners in terms of drawing coordinates).
     * @param outerRadius The radius of the circle who's top most point is the top center of the
     *        drawable (bottom center in terms of drawing coordinates).
     * @param x The distance along the x axis of the desired point.    @return The y coordinate, in drawing coordinates, that will place (x, y) along the circle
     *        in between the two concentric circles.
     */
    private int getYOnArc(int backgroundWidth, int innerRadius, int outerRadius, int x) {

        // the hypotenuse
        final int halfWidth = (outerRadius - innerRadius) / 2;
        final int middleRadius = innerRadius + halfWidth;

        // the bottom leg of the triangle
        final int triangleBottom = (backgroundWidth / 2) - x;

        // "Our offense is like the pythagorean theorem: There is no answer!" - Shaquille O'Neal
        final int triangleY =
                (int) Math.sqrt(middleRadius * middleRadius - triangleBottom * triangleBottom);

        // convert to drawing coordinates:
        // middleRadius - triangleY =
        //   the vertical distance from the outer edge of the circle to the desired point
        // from there we add the distance from the top of the drawable to the middle circle
        return middleRadius - triangleY + halfWidth;
    }

    /**
     * Handle touch screen events.
     *
     * @param event The motion event.
     * @return True if the event was handled, false otherwise.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mAnimating) {
            return true;
        }
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);

        final int height = getHeight();

        final int eventX = isHoriz() ?
                (int) event.getX():
                height - ((int) event.getY());
        final int hitWindow = mDimpleWidth;

        final int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (DBG) log("touch-down");
                mTriggered = false;
                if (mGrabbedState != NOTHING_GRABBED) {
                    reset();
                    invalidate();
                }
                if (eventX < mLeftHandleX + hitWindow) {
                    mRotaryOffsetX = eventX - mLeftHandleX;
                    setGrabbedState(LEFT_HANDLE_GRABBED);
                    invalidate();
                    vibrate(VIBRATE_SHORT);
                } else if (eventX > mRightHandleX - hitWindow) {
                    mRotaryOffsetX = eventX - mRightHandleX;
                    setGrabbedState(RIGHT_HANDLE_GRABBED);
                    invalidate();
                    vibrate(VIBRATE_SHORT);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (DBG) log("touch-move");
                if (mGrabbedState == LEFT_HANDLE_GRABBED) {
                    mRotaryOffsetX = eventX - mLeftHandleX;
                    invalidate();
                    final int rightThresh = isHoriz() ? getRight() : height;
                    if (eventX >= rightThresh - mEdgeTriggerThresh && !mTriggered) {
                        mTriggered = true;
                        dispatchTriggerEvent(OnDialTriggerListener.LEFT_HANDLE);
                        final VelocityTracker velocityTracker = mVelocityTracker;
                        velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                        final int rawVelocity = isHoriz() ?
                                (int) velocityTracker.getXVelocity():
                                -(int) velocityTracker.getYVelocity();
                        final int velocity = Math.max(mMinimumVelocity, rawVelocity);
                        mDimplesOfFling = Math.max(
                                8,
                                Math.abs(velocity / mDimpleSpacing));
                        startAnimationWithVelocity(
                                eventX - mLeftHandleX,
                                mDimplesOfFling * mDimpleSpacing,
                                velocity);
                    }
                } else if (mGrabbedState == RIGHT_HANDLE_GRABBED) {
                    mRotaryOffsetX = eventX - mRightHandleX;
                    invalidate();
                    if (eventX <= mEdgeTriggerThresh && !mTriggered) {
                        mTriggered = true;
                        dispatchTriggerEvent(OnDialTriggerListener.RIGHT_HANDLE);
                        final VelocityTracker velocityTracker = mVelocityTracker;
                        velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                        final int rawVelocity = isHoriz() ?
                                (int) velocityTracker.getXVelocity():
                                - (int) velocityTracker.getYVelocity();
                        final int velocity = Math.min(-mMinimumVelocity, rawVelocity);
                        mDimplesOfFling = Math.max(
                                8,
                                Math.abs(velocity / mDimpleSpacing));
                        startAnimationWithVelocity(
                                eventX - mRightHandleX,
                                -(mDimplesOfFling * mDimpleSpacing),
                                velocity);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (DBG) log("touch-up");
                // handle animating back to start if they didn't trigger
                if (mGrabbedState == LEFT_HANDLE_GRABBED
                        && Math.abs(eventX - mLeftHandleX) > 5) {
                    // set up "snap back" animation
                    startAnimation(eventX - mLeftHandleX, 0, SNAP_BACK_ANIMATION_DURATION_MILLIS);
                } else if (mGrabbedState == RIGHT_HANDLE_GRABBED
                        && Math.abs(eventX - mRightHandleX) > 5) {
                    // set up "snap back" animation
                    startAnimation(eventX - mRightHandleX, 0, SNAP_BACK_ANIMATION_DURATION_MILLIS);
                }
                mRotaryOffsetX = 0;
                setGrabbedState(NOTHING_GRABBED);
                invalidate();
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle(); // wishin' we had generational GC
                    mVelocityTracker = null;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (DBG) log("touch-cancel");
                reset();
                invalidate();
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
        return true;
    }

    private void startAnimation(int startX, int endX, int duration) {
        mAnimating = true;
        mAnimationStartTime = currentAnimationTimeMillis();
        mAnimationDuration = duration;
        mAnimatingDeltaXStart = startX;
        mAnimatingDeltaXEnd = endX;
        setGrabbedState(NOTHING_GRABBED);
        mDimplesOfFling = 0;
        invalidate();
    }

    private void startAnimationWithVelocity(int startX, int endX, int pixelsPerSecond) {
        mAnimating = true;
        mAnimationStartTime = currentAnimationTimeMillis();
        mAnimationDuration = 1000 * (endX - startX) / pixelsPerSecond;
        mAnimatingDeltaXStart = startX;
        mAnimatingDeltaXEnd = endX;
        setGrabbedState(NOTHING_GRABBED);
        invalidate();
    }

    private void updateAnimation() {
        final long millisSoFar = currentAnimationTimeMillis() - mAnimationStartTime;
        final long millisLeft = mAnimationDuration - millisSoFar;
        final int totalDeltaX = mAnimatingDeltaXStart - mAnimatingDeltaXEnd;
        final boolean goingRight = totalDeltaX < 0;
        if (DBG) log("millisleft for animating: " + millisLeft);
        if (millisLeft <= 0) {
            reset();
            return;
        }
        // from 0 to 1 as animation progresses
        float interpolation =
                mInterpolator.getInterpolation((float) millisSoFar / mAnimationDuration);
        final int dx = (int) (totalDeltaX * (1 - interpolation));
        mRotaryOffsetX = mAnimatingDeltaXEnd + dx;

        // once we have gone far enough to animate the current buttons off screen, we start
        // wrapping the offset back to the other side so that when the animation is finished,
        // the buttons will come back into their original places.
        if (mDimplesOfFling > 0) {
            if (!goingRight && mRotaryOffsetX < -3 * mDimpleSpacing) {
                // wrap around on fling left
                mRotaryOffsetX += mDimplesOfFling * mDimpleSpacing;
            } else if (goingRight && mRotaryOffsetX > 3 * mDimpleSpacing) {
                // wrap around on fling right
                mRotaryOffsetX -= mDimplesOfFling * mDimpleSpacing;
            }
        }
        invalidate();
    }

    private void reset() {
        mAnimating = false;
        mRotaryOffsetX = 0;
        mDimplesOfFling = 0;
        setGrabbedState(NOTHING_GRABBED);
        mTriggered = false;
    }

    /**
     * Triggers haptic feedback.
     */
    private synchronized void vibrate(long duration) {
        final boolean hapticEnabled = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
                UserHandle.USER_CURRENT) != 0;
        if (hapticEnabled) {
            if (mVibrator == null) {
                mVibrator = (android.os.Vibrator) getContext()
                        .getSystemService(Context.VIBRATOR_SERVICE);
            }
            mVibrator.vibrate(duration, VIBRATION_ATTRIBUTES);
        }
    }

    /**
     * Draw the bitmap so that it's centered
     * on the point (x,y), then draws it using specified canvas.
     * TODO: is there already a utility method somewhere for this?
     */
    private void drawCentered(Bitmap d, Canvas c, int x, int y) {
        int w = d.getWidth();
        int h = d.getHeight();

        c.drawBitmap(d, x - (w / 2), y - (h / 2), mPaint);
    }


    /**
     * Registers a callback to be invoked when the dial
     * is "triggered" by rotating it one way or the other.
     *
     * @param l the OnDialTriggerListener to attach to this view
     */
    public void setOnDialTriggerListener(OnDialTriggerListener l) {
        mOnDialTriggerListener = l;
    }

    /**
     * Dispatches a trigger event to our listener.
     */
    private void dispatchTriggerEvent(int whichHandle) {
        vibrate(VIBRATE_LONG);
        if (mOnDialTriggerListener != null) {
            mOnDialTriggerListener.onDialTrigger(this, whichHandle);
        }
    }

    /**
     * Sets the current grabbed state, and dispatches a grabbed state change
     * event to our listener.
     */
    private void setGrabbedState(int newState) {
        if (newState != mGrabbedState) {
            mGrabbedState = newState;
            if (mOnDialTriggerListener != null) {
                mOnDialTriggerListener.onGrabbedStateChange(this, mGrabbedState);
            }
        }
    }

    /**
     * Interface definition for a callback to be invoked when the dial
     * is "triggered" by rotating it one way or the other.
     */
    public interface OnDialTriggerListener {
        /**
         * The dial was triggered because the user grabbed the left handle,
         * and rotated the dial clockwise.
         */
        public static final int LEFT_HANDLE = 1;

        /**
         * The dial was triggered because the user grabbed the right handle,
         * and rotated the dial counterclockwise.
         */
        public static final int RIGHT_HANDLE = 2;

        /**
         * Called when the dial is triggered.
         *
         * @param v The view that was triggered
         * @param whichHandle  Which "dial handle" the user grabbed,
         *        either {@link #LEFT_HANDLE}, {@link #RIGHT_HANDLE}.
         */
        void onDialTrigger(View v, int whichHandle);

        /**
         * Called when the "grabbed state" changes (i.e. when
         * the user either grabs or releases one of the handles.)
         *
         * @param v the view that was triggered
         * @param grabbedState the new state: either {@link #NOTHING_GRABBED},
         * {@link #LEFT_HANDLE_GRABBED}, or {@link #RIGHT_HANDLE_GRABBED}.
         */
        void onGrabbedStateChange(View v, int grabbedState);
    }


    // Debugging / testing code

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
