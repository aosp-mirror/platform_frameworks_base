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
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import static android.view.animation.AnimationUtils.currentAnimationTimeMillis;
import com.android.internal.R;


/**
 * Custom view that presents up to two items that are selectable by rotating a semi-circle from
 * left to right, or right to left.  Used by incoming call screen, and the lock screen when no
 * security pattern is set.
 */
public class RotarySelector extends View {
    private static final String LOG_TAG = "RotarySelector";
    private static final boolean DBG = false;

    // Listener for onDialTrigger() callbacks.
    private OnDialTriggerListener mOnDialTriggerListener;

    private float mDensity;

    // UI elements
    private Drawable mBackground;
    private Drawable mDimple;

    private Drawable mLeftHandleIcon;
    private Drawable mRightHandleIcon;

    private Drawable mArrowShortLeftAndRight;
    private Drawable mArrowLongLeft;  // Long arrow starting on the left, pointing clockwise
    private Drawable mArrowLongRight;  // Long arrow starting on the right, pointing CCW

    // positions of the left and right handle
    private int mLeftHandleX;
    private int mRightHandleX;

    // current offset of user's dragging
    private int mTouchDragOffset = 0;

    // state of the animation used to bring the handle back to its start position when
    // the user lets go before triggering an action
    private boolean mAnimating = false;
    private long mAnimationEndTime;
    private int mAnimatingDelta;
    private AccelerateInterpolator mInterpolator;

    /**
     * True after triggering an action if the user of {@link OnDialTriggerListener} wants to
     * freeze the UI (until they transition to another screen).
     */
    private boolean mFrozen = false;

    /**
     * If the user is currently dragging something.
     */
    private int mGrabbedState = NOTHING_GRABBED;
    private static final int NOTHING_GRABBED = 0;
    private static final int LEFT_HANDLE_GRABBED = 1;
    private static final int RIGHT_HANDLE_GRABBED = 2;

    /**
     * Whether the user has triggered something (e.g dragging the left handle all the way over to
     * the right).
     */
    private boolean mTriggered = false;

    // Vibration (haptic feedback)
    private Vibrator mVibrator;
    private static final long VIBRATE_SHORT = 60;  // msec
    private static final long VIBRATE_LONG = 100;  // msec

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
    private static final int EDGE_TRIGGER_DIP = 65;

    /**
     * Dimensions of arc in background drawable.
     */
    static final int OUTER_ROTARY_RADIUS_DIP = 390;
    static final int ROTARY_STROKE_WIDTH_DIP = 83;
    private static final int ANIMATION_DURATION_MILLIS = 300;

    private static final boolean DRAW_CENTER_DIMPLE = false;
    private int mEdgeTriggerThresh;

    public RotarySelector(Context context) {
        this(context, null);
    }

    /**
     * Constructor used when this widget is created from a layout file.
     */
    public RotarySelector(Context context, AttributeSet attrs) {
        super(context, attrs);
        if (DBG) log("IncomingCallDialWidget constructor...");

        Resources r = getResources();
        mDensity = r.getDisplayMetrics().density;
        if (DBG) log("- Density: " + mDensity);

        // Assets (all are BitmapDrawables).
        mBackground = r.getDrawable(R.drawable.jog_dial_bg_cropped);
        mDimple = r.getDrawable(R.drawable.jog_dial_dimple);

        mArrowLongLeft = r.getDrawable(R.drawable.jog_dial_arrow_long_left_green);
        mArrowLongRight = r.getDrawable(R.drawable.jog_dial_arrow_long_right_red);
        mArrowShortLeftAndRight = r.getDrawable(R.drawable.jog_dial_arrow_short_left_and_right);

        // Arrows:
        // All arrow assets are the same size (they're the full width of
        // the screen) regardless of which arrows are actually visible.
        int arrowW = mArrowShortLeftAndRight.getIntrinsicWidth();
        int arrowH = mArrowShortLeftAndRight.getIntrinsicHeight();
        mArrowShortLeftAndRight.setBounds(0, 0, arrowW, arrowH);
        mArrowLongLeft.setBounds(0, 0, arrowW, arrowH);
        mArrowLongRight.setBounds(0, 0, arrowW, arrowH);

        mInterpolator = new AccelerateInterpolator();

        mEdgeTriggerThresh = (int) (mDensity * EDGE_TRIGGER_DIP);
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
        Drawable d = null;
        if (resId != 0) {
            d = getResources().getDrawable(resId);
        }
        setLeftHandleDrawable(d);
    }

    /**
     * Sets the left handle icon to a given Drawable.
     *
     * @param d the Drawable to use as the icon, or null to remove the icon.
     */
    public void setLeftHandleDrawable(Drawable d) {
        mLeftHandleIcon = d;
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
        Drawable d = null;
        if (resId != 0) {
            d = getResources().getDrawable(resId);
        }
        setRightHandleDrawable(d);
    }

    /**
     * Sets the right handle icon to a given Drawable.
     *
     * @param d the Drawable to use as the icon, or null to remove the icon.
     */
    public void setRightHandleDrawable(Drawable d) {
        mRightHandleIcon = d;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);  // screen width

        final int arrowH = mArrowShortLeftAndRight.getIntrinsicHeight();
        final int backgroundH = mBackground.getIntrinsicHeight();

        // by making the height less than arrow + bg, arrow and bg will be scrunched together,
        // overlaying somewhat (though on transparent portions of the drawable).
        // this works because the arrows are drawn from the top, and the rotary bg is drawn
        // from the bottom.
        final int arrowScrunch = (int) (ARROW_SCRUNCH_DIP * mDensity);
        setMeasuredDimension(width, backgroundH + arrowH - arrowScrunch);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        mLeftHandleX = (int) (EDGE_PADDING_DIP * mDensity) + mDimple.getIntrinsicWidth() / 2;
        mRightHandleX =
                getWidth() - (int) (EDGE_PADDING_DIP * mDensity) - mDimple.getIntrinsicWidth() / 2;
    }

//    private Paint mPaint = new Paint();

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (DBG) {
            log(String.format("onDraw: mAnimating=%s, mTouchDragOffset=%d, mGrabbedState=%d," +
                    "mFrozen=%s",
                    mAnimating, mTouchDragOffset, mGrabbedState, mFrozen));
        }

        final int height = getHeight();

        // update animating state before we draw anything
        if (mAnimating && !mFrozen) {
            long millisLeft = mAnimationEndTime - currentAnimationTimeMillis();
            if (DBG) log("millisleft for animating: " + millisLeft);
            if (millisLeft <= 0) {
                reset();
            } else {
                float interpolation = mInterpolator.getInterpolation(
                        (float) millisLeft / ANIMATION_DURATION_MILLIS);
                mTouchDragOffset = (int) (mAnimatingDelta * interpolation);
            }
        }

        // Background:
        final int backgroundW = mBackground.getIntrinsicWidth();
        final int backgroundH = mBackground.getIntrinsicHeight();
        final int backgroundY = height - backgroundH;
        if (DBG) log("- Background INTRINSIC: " + backgroundW + " x " + backgroundH);
        mBackground.setBounds(0, backgroundY,
                              backgroundW, backgroundY + backgroundH);
        if (DBG) log("  Background BOUNDS: " + mBackground.getBounds());
        mBackground.draw(canvas);


        // Draw the correct arrow(s) depending on the current state:
        Drawable currentArrow;
        switch (mGrabbedState) {
            case NOTHING_GRABBED:
                currentArrow  = null; //mArrowShortLeftAndRight;
                break;
            case LEFT_HANDLE_GRABBED:
                currentArrow = mArrowLongLeft;
                break;
            case RIGHT_HANDLE_GRABBED:
                currentArrow = mArrowLongRight;
                break;
            default:
                throw new IllegalStateException("invalid mGrabbedState: " + mGrabbedState);
        }
        if (currentArrow != null) currentArrow.draw(canvas);

        // debug: draw circle that should match the outer arc (good sanity check)
//        mPaint.setColor(Color.RED);
//        mPaint.setStyle(Paint.Style.STROKE);
//        float or = OUTER_ROTARY_RADIUS_DIP * mDensity;
//        canvas.drawCircle(getWidth() / 2, or + mBackground.getBounds().top, or, mPaint);

        final int outerRadius = (int) (mDensity * OUTER_ROTARY_RADIUS_DIP);
        final int innerRadius =
                (int) ((OUTER_ROTARY_RADIUS_DIP - ROTARY_STROKE_WIDTH_DIP) * mDensity);
        final int bgTop = mBackground.getBounds().top;
        {
            final int xOffset = mLeftHandleX + mTouchDragOffset;
            final int drawableY = getYOnArc(
                    mBackground,
                    innerRadius,
                    outerRadius,
                    xOffset);

            drawCentered(mDimple, canvas, xOffset, drawableY + bgTop);
            if (mGrabbedState != RIGHT_HANDLE_GRABBED) {
                drawCentered(mLeftHandleIcon, canvas, xOffset, drawableY + bgTop);
            }
        }

        if (DRAW_CENTER_DIMPLE) {
            final int xOffset = getWidth() / 2 + mTouchDragOffset;
            final int drawableY = getYOnArc(
                    mBackground,
                    innerRadius,
                    outerRadius,
                    xOffset);

            drawCentered(mDimple, canvas, xOffset, drawableY + bgTop);
        }

        {
            final int xOffset = mRightHandleX + mTouchDragOffset;
            final int drawableY = getYOnArc(
                    mBackground,
                    innerRadius,
                    outerRadius,
                    xOffset);

            drawCentered(mDimple, canvas, xOffset, drawableY + bgTop);
            if (mGrabbedState != LEFT_HANDLE_GRABBED) {
                drawCentered(mRightHandleIcon, canvas, xOffset, drawableY + bgTop);
            }
        }

        if (mAnimating) invalidate();
    }

    /**
     * Assuming drawable is a bounding box around a piece of an arc drawn by two concentric circles
     * (as the background drawable for the rotary widget is), and given an x coordinate along the
     * drawable, return the y coordinate of a point on the arc that is between the two concentric
     * circles.  The resulting y combined with the incoming x is a point along the circle in
     * between the two concentric circles.
     *
     * @param drawable The drawable.
     * @param innerRadius The radius of the circle that intersects the drawable at the bottom two
     *        corders of the drawable (top two corners in terms of drawing coordinates).
     * @param outerRadius The radius of the circle who's top most point is the top center of the
     *        drawable (bottom center in terms of drawing coordinates).
     * @param x The distance along the x axis of the desired point.
     * @return The y coordinate, in drawing coordinates, that will place (x, y) along the circle
     *        in between the two concentric circles.
     */
    private int getYOnArc(Drawable drawable, int innerRadius, int outerRadius, int x) {

        // the hypotenuse
        final int halfWidth = (outerRadius - innerRadius) / 2;
        final int middleRadius = innerRadius + halfWidth;

        // the bottom leg of the triangle
        final int triangleBottom = (drawable.getIntrinsicWidth() / 2) - x;

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
        if (mAnimating || mFrozen) {
            return true;
        }

        final int eventX = (int) event.getX();
        final int hitWindow = mDimple.getIntrinsicWidth();

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
                    mTouchDragOffset = eventX - mLeftHandleX;
                    mGrabbedState = LEFT_HANDLE_GRABBED;
                    invalidate();
                    vibrate(VIBRATE_SHORT);
                } else if (eventX > mRightHandleX - hitWindow) {
                    mTouchDragOffset = eventX - mRightHandleX;
                    mGrabbedState = RIGHT_HANDLE_GRABBED;
                    invalidate();
                    vibrate(VIBRATE_SHORT);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (DBG) log("touch-move");
                if (mGrabbedState == LEFT_HANDLE_GRABBED) {
                    mTouchDragOffset = eventX - mLeftHandleX;
                    invalidate();
                    if (eventX >= getRight() - mEdgeTriggerThresh && !mTriggered) {
                        mTriggered = true;
                        mFrozen = dispatchTriggerEvent(OnDialTriggerListener.LEFT_HANDLE);
                    }
                } else if (mGrabbedState == RIGHT_HANDLE_GRABBED) {
                    mTouchDragOffset = eventX - mRightHandleX;
                    invalidate();
                    if (eventX <= mEdgeTriggerThresh && !mTriggered) {
                        mTriggered = true;
                        mFrozen = dispatchTriggerEvent(OnDialTriggerListener.RIGHT_HANDLE);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (DBG) log("touch-up");
                // handle animating back to start if they didn't trigger
                if (mGrabbedState == LEFT_HANDLE_GRABBED
                        && Math.abs(eventX - mLeftHandleX) > 5) {
                    mAnimating = true;
                    mAnimationEndTime = currentAnimationTimeMillis() + ANIMATION_DURATION_MILLIS;
                    mAnimatingDelta = eventX - mLeftHandleX;
                } else if (mGrabbedState == RIGHT_HANDLE_GRABBED
                        && Math.abs(eventX - mRightHandleX) > 5) {
                    mAnimating = true;
                    mAnimationEndTime = currentAnimationTimeMillis() + ANIMATION_DURATION_MILLIS;
                    mAnimatingDelta = eventX - mRightHandleX;
                }

                mTouchDragOffset = 0;
                mGrabbedState = NOTHING_GRABBED;
                invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (DBG) log("touch-cancel");
                reset();
                invalidate();
                break;
        }
        return true;
    }

    private void reset() {
        mAnimating = false;
        mTouchDragOffset = 0;
        mGrabbedState = NOTHING_GRABBED;
        mTriggered = false;
    }

    /**
     * Triggers haptic feedback.
     */
    private synchronized void vibrate(long duration) {
        if (mVibrator == null) {
            mVibrator = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        }
        mVibrator.vibrate(duration);
    }

    /**
     * Sets the bounds of the specified Drawable so that it's centered
     * on the point (x,y), then draws it onto the specified canvas.
     * TODO: is there already a utility method somewhere for this?
     */
    private static void drawCentered(Drawable d, Canvas c, int x, int y) {
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();

        // if (DBG) log("--> drawCentered: " + x + " , " + y + "; intrinsic " + w + " x " + h);
        d.setBounds(x - (w / 2), y - (h / 2),
                    x + (w / 2), y + (h / 2));
        d.draw(c);
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
    private boolean dispatchTriggerEvent(int whichHandle) {
        vibrate(VIBRATE_LONG);
        if (mOnDialTriggerListener != null) {
            return mOnDialTriggerListener.onDialTrigger(this, whichHandle);
        }
        return false;
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
         * @hide
         * The center handle is currently unused.
         */
        public static final int CENTER_HANDLE = 3;

        /**
         * Called when the dial is triggered.
         *
         * @param v The view that was triggered
         * @param whichHandle  Which "dial handle" the user grabbed,
         *        either {@link #LEFT_HANDLE}, {@link #RIGHT_HANDLE}, or
         *        {@link #CENTER_HANDLE}.
         * @return Whether the widget should freeze (e.g when the action goes to another screen,
         *         you want the UI to stay put until the transition occurs).
         */
        boolean onDialTrigger(View v, int whichHandle);
    }


    // Debugging / testing code

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
