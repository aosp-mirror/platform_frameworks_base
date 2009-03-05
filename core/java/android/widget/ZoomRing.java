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

package android.widget;

import com.android.internal.R;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RotateDrawable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * A view that has a draggable thumb on a circle.
 * 
 * @hide
 */
public class ZoomRing extends View {
    private static final String TAG = "ZoomRing";

    // TODO: Temporary until the trail is done
    private static final boolean DRAW_TRAIL = false;

    /**
     * To avoid floating point calculations and int round-offs, we multiply
     * radians by this value.
     */
    public static final int RADIAN_INT_MULTIPLIER = 10000;
    /** The allowable margin of error when comparing two angles. */
    public static final int RADIAN_INT_ERROR = 100;
    public static final int PI_INT_MULTIPLIED = (int) (Math.PI * RADIAN_INT_MULTIPLIER);
    public static final int TWO_PI_INT_MULTIPLIED = PI_INT_MULTIPLIED * 2;
    private static final int HALF_PI_INT_MULTIPLIED = PI_INT_MULTIPLIED / 2;

    private static final int DOUBLE_TAP_DISMISS_TIMEOUT = ViewConfiguration.getDoubleTapTimeout();
    private final int mTouchSlop;

    /** The slop when the user is grabbing the thumb. */
    private static final int THUMB_GRAB_SLOP = PI_INT_MULTIPLIED / 8;
    /** The slop until a user starts dragging the thumb. */
    private static final int THUMB_DRAG_SLOP = PI_INT_MULTIPLIED / 12;

    /** The distance (in px) from the center of the ring to the center of the thumb. */ 
    private int mThumbDistance;

    /** The angle on a unit circle that is considered to be the zoom ring's 0 degree. */
    private int mZeroAngle = HALF_PI_INT_MULTIPLIED * 3;

    /**
     * The maximum delta angle that the thumb can move. The primary use is to
     * ensure that when a user taps on the ring, the movement to reach that
     * target angle is not ambiguous (for example, if the thumb is at 0 and he
     * taps 180, should the thumb go clockwise or counterclockwise?
     * <p>
     * Includes error because we compare this to the result of
     * getDelta(getClosestTickeAngle(..), oldAngle) which ends up having some
     * rounding error.
     */ 
    private static final int MAX_ABS_JUMP_DELTA_ANGLE = (2 * PI_INT_MULTIPLIED / 3) +
            RADIAN_INT_ERROR; 

    /** The cached X of the zoom ring's center (in zoom ring coordinates). */
    private int mCenterX;
    /** The cached Y of the zoom ring's center (in zoom ring coordinates). */
    private int mCenterY;

    /** The angle of the thumb (in int radians) */
    private int mThumbAngle;
    /** The cached width/2 of the zoom ring. */
    private int mThumbHalfWidth;
    /** The cached height/2 of the zoom ring. */
    private int mThumbHalfHeight;
    
    /**
     * The bound for the thumb's movement when it is being dragged clockwise.
     * Can be Integer.MIN_VALUE if there is no bound in this direction.
     */
    private int mThumbCwBound = Integer.MIN_VALUE;
    /**
     * The bound for the thumb's movement when it is being dragged
     * counterclockwise. Can be Integer.MIN_VALUE if there is no bound in this
     * direction.
     */
    private int mThumbCcwBound = Integer.MIN_VALUE;
    
    /**
     * Whether to enforce the maximum absolute jump delta. See
     * {@link #MAX_ABS_JUMP_DELTA_ANGLE}.
     */
    private boolean mEnforceMaxAbsJump = true;
    
    /** The inner radius of the track. */
    private int mTrackInnerRadius;
    /** Cached square of the inner radius of the track. */
    private int mTrackInnerRadiusSquared;
    /** The outer radius of the track. */
    private int mTrackOuterRadius;
    /** Cached square of the outer radius of the track. */
    private int mTrackOuterRadiusSquared;

    /** The raw X of where the widget previously was located. */
    private int mPreviousWidgetDragX;
    /** The raw Y of where the widget previously was located. */
    private int mPreviousWidgetDragY;

    /** Whether the thumb should be visible. */
    private boolean mThumbVisible = true;
    
    /** The drawable for the thumb. */
    private Drawable mThumbDrawable;
    
    /** Shown beneath the thumb if we can still zoom in. */
    private Drawable mZoomInArrowDrawable;
    /** Shown beneath the thumb if we can still zoom out. */
    private Drawable mZoomOutArrowDrawable;
    
    /** @see #mThumbArrowsToDraw */
    private static final int THUMB_ARROW_PLUS = 1 << 0;
    /** @see #mThumbArrowsToDraw */
    private static final int THUMB_ARROW_MINUS = 1 << 1;
    /** Bitwise-OR of {@link #THUMB_ARROW_MINUS} and {@link #THUMB_ARROW_PLUS} */
    private int mThumbArrowsToDraw;
    
    /** The duration for the thumb arrows fading out */
    private static final int THUMB_ARROWS_FADE_DURATION = 300;
    /** The time when the fade out started. */
    private long mThumbArrowsFadeStartTime;
    /** The current alpha for the thumb arrows. */
    private int mThumbArrowsAlpha = 255;

    /** The distance from the center to the zoom arrow hints (usually plus and minus). */
    private int mZoomArrowHintDistance;
    /** The offset angle from the thumb angle to draw the zoom arrow hints. */
    private int mZoomArrowHintOffsetAngle = TWO_PI_INT_MULTIPLIED / 11;
    /** Drawn (without rotation) on top of the arrow. */
    private Drawable mZoomInArrowHintDrawable;
    /** Drawn (without rotation) on top of the arrow. */
    private Drawable mZoomOutArrowHintDrawable;
    
    /** Zoom ring is just chillin' */
    private static final int MODE_IDLE = 0;
    /**
     * User has his finger down somewhere on the ring (besides the thumb) and we
     * are waiting for him to move the slop amount before considering him in the
     * drag thumb state.
     */
    private static final int MODE_WAITING_FOR_DRAG_THUMB_AFTER_JUMP = 5;
    /** User is dragging the thumb. */
    private static final int MODE_DRAG_THUMB = 1;
    /**
     * User has his finger down, but we are waiting for him to pass the touch
     * slop before going into the #MODE_MOVE_ZOOM_RING. This is a good time to
     * show the movable hint.
     */
    private static final int MODE_WAITING_FOR_MOVE_ZOOM_RING = 4;
    /** User is moving the zoom ring. */
    private static final int MODE_MOVE_ZOOM_RING = 2;
    /** User is dragging the thumb via tap-drag. */
    private static final int MODE_TAP_DRAG = 3;
    /** Ignore the touch interaction until the user touches the thumb again. */
    private static final int MODE_IGNORE_UNTIL_TOUCHES_THUMB = 6;
    /** The current mode of interaction. */
    private int mMode;
    /** Records the last mode the user was in. */
    private int mPreviousMode;

    /** The previous time of the up-touch on the center. */
    private long mPreviousCenterUpTime;
    /** The previous X of down-touch. */
    private int mPreviousDownX;
    /** The previous Y of down-touch. */
    private int mPreviousDownY;

    /** The angle where the user first grabbed the thumb. */
    private int mInitialGrabThumbAngle;
    
    /** The callback. */
    private OnZoomRingCallback mCallback;
    /** The tick angle that we previously called back with. */
    private int mPreviousCallbackTickAngle;
    /** The delta angle between ticks.  A tick is a callback point. */
    private int mTickDelta = Integer.MAX_VALUE;
    /** If the user drags to within __% of a tick, snap to that tick. */
    private int mFuzzyTickDelta = Integer.MAX_VALUE;
    
    /** The angle where the thumb is officially starting to be dragged. */
    private int mThumbDragStartAngle;

    /** The drawable for the zoom trail. */
    private Drawable mTrail;
    /** The accumulated angle for the trail. */
    private double mAcculumalatedTrailAngle;
    
    /** The animation-step tracker for scrolling the thumb to a particular position. */
    private Scroller mThumbScroller;

    /** Whether to ever vibrate when passing a tick. */
    private boolean mVibration = true;
    
    /** The drawable used to hint that this can pan its owner. */
    private Drawable mPanningArrowsDrawable;
    
    private static final int MSG_THUMB_SCROLLER_STEP = 1;
    private static final int MSG_THUMB_ARROWS_FADE_STEP = 2;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_THUMB_SCROLLER_STEP:
                    onThumbScrollerStep();
                    break;
                    
                case MSG_THUMB_ARROWS_FADE_STEP:
                    onThumbArrowsFadeStep();
                    break;
            }
        }
    };
    
    public ZoomRing(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        mTouchSlop = viewConfiguration.getScaledTouchSlop();

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ZoomRing, defStyle, 0);
        mThumbDistance = (int) a.getDimension(R.styleable.ZoomRing_thumbDistance, 0);
        setTrackRadii(
                (int) a.getDimension(R.styleable.ZoomRing_trackInnerRadius, 0),
                (int) a.getDimension(R.styleable.ZoomRing_trackOuterRadius, Integer.MAX_VALUE));
        mThumbDrawable = a.getDrawable(R.styleable.ZoomRing_thumbDrawable);
        mZoomInArrowDrawable = a.getDrawable(R.styleable.ZoomRing_zoomInArrowDrawable);
        mZoomOutArrowDrawable = a.getDrawable(R.styleable.ZoomRing_zoomOutArrowDrawable);
        mZoomInArrowHintDrawable = a.getDrawable(R.styleable.ZoomRing_zoomInArrowHintDrawable);
        mZoomOutArrowHintDrawable = a.getDrawable(R.styleable.ZoomRing_zoomOutArrowHintDrawable);
        mZoomArrowHintDistance =
                (int) a.getDimension(R.styleable.ZoomRing_zoomArrowHintDistance, 0);
        mZoomArrowHintOffsetAngle =
                (int) (a.getInteger(R.styleable.ZoomRing_zoomArrowHintOffsetAngle, 0)
                        * TWO_PI_INT_MULTIPLIED / 360);
        mPanningArrowsDrawable = a.getDrawable(R.styleable.ZoomRing_panningArrowsDrawable);
        a.recycle();
        
        Resources res = context.getResources();
        if (DRAW_TRAIL) {
            // TODO get drawables from style instead
            mTrail = res.getDrawable(R.drawable.zoom_ring_trail).mutate();
        }

        mThumbHalfHeight = mThumbDrawable.getIntrinsicHeight() / 2;
        mThumbHalfWidth = mThumbDrawable.getIntrinsicWidth() / 2;

        setTickDelta(PI_INT_MULTIPLIED / 6);
    }

    public ZoomRing(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.zoomRingStyle);
    }

    public ZoomRing(Context context) {
        this(context, null);
    }

    public void setTrackDrawable(Drawable drawable) {
        setBackgroundDrawable(drawable);
    }
    
    public void setCallback(OnZoomRingCallback callback) {
        mCallback = callback;
    }

    /**
     * Sets the distance between ticks.  This will be used as a callback threshold.
     * 
     * @param angle The angle between ticks.
     */
    public void setTickDelta(int angle) {
        mTickDelta = angle;
        mFuzzyTickDelta = (int) (angle * 0.65f);
    }

    public void setVibration(boolean vibration) {
        mVibration = vibration;
    }
    
    public void setThumbVisible(boolean thumbVisible) {
        if (mThumbVisible != thumbVisible) {
            mThumbVisible = thumbVisible;
            invalidate();
        }
    }
    
    public Drawable getPanningArrowsDrawable() {
        return mPanningArrowsDrawable;
    }
    
    public void setTrackRadii(int innerRadius, int outerRadius) {
        mTrackInnerRadius = innerRadius;
        mTrackOuterRadius = outerRadius;
        
        mTrackInnerRadiusSquared = innerRadius * innerRadius;
        if (mTrackInnerRadiusSquared < innerRadius) {
            // Prevent overflow
            mTrackInnerRadiusSquared = Integer.MAX_VALUE;
        }

        mTrackOuterRadiusSquared = outerRadius * outerRadius;
        if (mTrackOuterRadiusSquared < outerRadius) {
            // Prevent overflow
            mTrackOuterRadiusSquared = Integer.MAX_VALUE;
        }
    }

    public int getTrackInnerRadius() {
        return mTrackInnerRadius;
    }
    
    public int getTrackOuterRadius() {
        return mTrackOuterRadius;
    }
    
    public void setThumbClockwiseBound(int angle) {
        if (angle < 0) {
            mThumbCwBound = Integer.MIN_VALUE;
        } else {
            mThumbCwBound = getClosestTickAngle(angle);
        }
        updateEnforceMaxAbsJump();
    }
    
    public void setThumbCounterclockwiseBound(int angle) {
        if (angle < 0) {
            mThumbCcwBound = Integer.MIN_VALUE;
        } else {
            mThumbCcwBound = getClosestTickAngle(angle);
        }
        updateEnforceMaxAbsJump();
    }
    
    private void updateEnforceMaxAbsJump() {
        // If there are bounds in both direction, there is no reason to restrict
        // the amount that a user can absolute jump to
        mEnforceMaxAbsJump =
                mThumbCcwBound == Integer.MIN_VALUE || mThumbCwBound == Integer.MIN_VALUE;
    }
    
    public int getThumbAngle() {
        return mThumbAngle;
    }
    
    public void setThumbAngle(int angle) {
        angle = getValidAngle(angle);
        mPreviousCallbackTickAngle = getClosestTickAngle(angle);
        setThumbAngleAuto(angle, false, false);
    }
    
    /**
     * Sets the thumb angle. If already animating, will continue the animation,
     * otherwise it will do a direct jump.
     * 
     * @param angle
     * @param useDirection Whether to use the ccw parameter
     * @param ccw Whether going counterclockwise (only used if useDirection is true)
     */
    private void setThumbAngleAuto(int angle, boolean useDirection, boolean ccw) {
        if (mThumbScroller == null
                || mThumbScroller.isFinished()
                || Math.abs(getDelta(angle, getThumbScrollerAngle())) < THUMB_GRAB_SLOP) {
            setThumbAngleInt(angle);
        } else {
            if (useDirection) {
                setThumbAngleAnimated(angle, 0, ccw);
            } else {
                setThumbAngleAnimated(angle, 0);
            }
        }
    }
    
    private void setThumbAngleInt(int angle) {
        mThumbAngle = angle;
        int unoffsetAngle = angle + mZeroAngle;
        int thumbCenterX = (int) (Math.cos(1f * unoffsetAngle / RADIAN_INT_MULTIPLIER) *
                mThumbDistance) + mCenterX;
        int thumbCenterY = (int) (Math.sin(1f * unoffsetAngle / RADIAN_INT_MULTIPLIER) *
                mThumbDistance) * -1 + mCenterY;

        mThumbDrawable.setBounds(thumbCenterX - mThumbHalfWidth,
                thumbCenterY - mThumbHalfHeight,
                thumbCenterX + mThumbHalfWidth,
                thumbCenterY + mThumbHalfHeight);

        if (mThumbArrowsToDraw > 0) {
            setThumbArrowsAngle(angle);
        }
        
        if (DRAW_TRAIL) {
            double degrees;
            degrees = Math.min(359.0, Math.abs(mAcculumalatedTrailAngle));
            int level = (int) (10000.0 * degrees / 360.0);

            mTrail.setLevel((int) (10000.0 *
                    (-Math.toDegrees(angle / (double) RADIAN_INT_MULTIPLIER) -
                            degrees + 90) / 360.0));
            ((RotateDrawable) mTrail).getDrawable().setLevel(level);
        }

        invalidate();
    }
    
    /**
     * 
     * @param angle
     * @param duration The animation duration, or 0 for the default duration.
     */
    public void setThumbAngleAnimated(int angle, int duration) {
        // The angle when going from the current angle to the new angle
        int deltaAngle = getDelta(mThumbAngle, angle);
        setThumbAngleAnimated(angle, duration, deltaAngle > 0);
    }
    
    public void setThumbAngleAnimated(int angle, int duration, boolean counterClockwise) {
        if (mThumbScroller == null) {
            mThumbScroller = new Scroller(mContext);
        }
        
        int startAngle = mThumbAngle;
        int endAngle = getValidAngle(angle);
        int deltaAngle = getDelta(startAngle, endAngle, counterClockwise);
        if (startAngle + deltaAngle < 0) {
            // Keep our angles positive
            startAngle += TWO_PI_INT_MULTIPLIED;
        }
        
        if (!mThumbScroller.isFinished()) {
            duration = mThumbScroller.getDuration() - mThumbScroller.timePassed();
        } else if (duration == 0) {
            duration = getAnimationDuration(deltaAngle);
        }
        mThumbScroller.startScroll(startAngle, 0, deltaAngle, 0, duration);
        onThumbScrollerStep();
    }
    
    private int getAnimationDuration(int deltaAngle) {
        if (deltaAngle < 0) deltaAngle *= -1;
        return 300 + deltaAngle * 300 / RADIAN_INT_MULTIPLIER; 
    }
    
    private void onThumbScrollerStep() {
        if (!mThumbScroller.computeScrollOffset()) return;
        setThumbAngleInt(getThumbScrollerAngle());        
        mHandler.sendEmptyMessage(MSG_THUMB_SCROLLER_STEP);
    }

    private int getThumbScrollerAngle() {
        return mThumbScroller.getCurrX() % TWO_PI_INT_MULTIPLIED;
    }
    
    public void resetThumbAngle() {
        mPreviousCallbackTickAngle = 0;
        setThumbAngleInt(0);
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right,
            int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Cache the center point
        mCenterX = (right - left) / 2;
        mCenterY = (bottom - top) / 2;

        // Done here since we now have center, which is needed to calculate some
        // aux info for thumb angle
        if (mThumbAngle == Integer.MIN_VALUE) {
            resetThumbAngle();
        }

        if (DRAW_TRAIL) {
            mTrail.setBounds(0, 0, right - left, bottom - top);
        }
        
        // These drawables are the same size as the track
        mZoomInArrowDrawable.setBounds(0, 0, right - left, bottom - top);
        mZoomOutArrowDrawable.setBounds(0, 0, right - left, bottom - top);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handleTouch(event.getAction(), event.getEventTime(),
                (int) event.getX(), (int) event.getY(), (int) event.getRawX(),
                (int) event.getRawY());
    }

    private void resetToIdle() {
        setMode(MODE_IDLE);
        mPreviousWidgetDragX = mPreviousWidgetDragY = Integer.MIN_VALUE;
        mAcculumalatedTrailAngle = 0.0;
    }

    public void setTapDragMode(boolean tapDragMode, int x, int y) {
        resetToIdle();
        if (tapDragMode) {
            setMode(MODE_TAP_DRAG);
            mCallback.onUserInteractionStarted();
            onThumbDragStarted(getAngle(x - mCenterX, y - mCenterY));
        } else {
            onTouchUp(SystemClock.elapsedRealtime(), true);
        }
    }

    public boolean handleTouch(int action, long time, int x, int y, int rawX, int rawY) {
        // local{X,Y} will be where the center of the widget is (0,0)
        int localX = x - mCenterX;
        int localY = y - mCenterY;

        /*
         * If we are not drawing the thumb, there is no way for the user to be
         * touching the thumb. Also, if this is the case, assume they are not
         * touching the ring (so the user cannot absolute set the thumb, and
         * there will be a larger touch region for going into the move-ring
         * mode).
         */
        boolean isTouchingThumb = mThumbVisible;
        boolean isTouchingRing = mThumbVisible;
        
        int touchAngle = getAngle(localX, localY);
        
        int radiusSquared = localX * localX + localY * localY;
        if (radiusSquared < mTrackInnerRadiusSquared ||
                radiusSquared > mTrackOuterRadiusSquared) {
            // Out-of-bounds
            isTouchingThumb = false;
            isTouchingRing = false;
        }

        if (isTouchingThumb) {
            int deltaThumbAndTouch = getDelta(mThumbAngle, touchAngle);
            int absoluteDeltaThumbAndTouch = deltaThumbAndTouch >= 0 ?
                    deltaThumbAndTouch : -deltaThumbAndTouch;
            if (absoluteDeltaThumbAndTouch > THUMB_GRAB_SLOP) {
                // Didn't grab close enough to the thumb
                isTouchingThumb = false;
            }
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (!isTouchingRing &&
                        (time - mPreviousCenterUpTime <= DOUBLE_TAP_DISMISS_TIMEOUT)) {
                    // Make sure the double-tap is in the center of the widget (and not on the ring)
                    mCallback.onZoomRingDismissed();
                    onTouchUp(time, isTouchingRing);
                    
                    // Dismissing, so halt here
                    return true;
                }

                resetToIdle();
                mCallback.onUserInteractionStarted();
                mPreviousDownX = x;
                mPreviousDownY = y;
                // Fall through to code below switch (since the down is used for
                // jumping to the touched tick)
                break;

            case MotionEvent.ACTION_MOVE:
                // Fall through to code below switch
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                onTouchUp(time, isTouchingRing);
                return true;

            default:
                return false;
        }

        if (mMode == MODE_IDLE) {
            if (isTouchingThumb) {
                // They grabbed the thumb
                setMode(MODE_DRAG_THUMB);
                onThumbDragStarted(touchAngle);
                
            } else if (isTouchingRing) {
                // They tapped somewhere else on the ring
                int tickAngle = getClosestTickAngle(touchAngle);
                int deltaThumbAndTick = getDelta(mThumbAngle, tickAngle);
                int boundAngle = getBoundIfExceeds(mThumbAngle, deltaThumbAndTick);

                if (mEnforceMaxAbsJump) {
                    // Enforcing the max jump
                    if (deltaThumbAndTick > MAX_ABS_JUMP_DELTA_ANGLE ||
                            deltaThumbAndTick < -MAX_ABS_JUMP_DELTA_ANGLE) {
                        // Trying to jump too far, ignore this touch interaction                    
                        setMode(MODE_IGNORE_UNTIL_TOUCHES_THUMB);
                        return true;
                    }

                    if (boundAngle != Integer.MIN_VALUE) {
                        // Cap the user's jump to the bound
                        tickAngle = boundAngle;
                    }
                } else {
                    // Not enforcing the max jump, but we have to make sure
                    // we're getting to the tapped angle by going through the
                    // in-bounds region
                    if (boundAngle != Integer.MIN_VALUE) {
                        // Going this direction hits a bound, let's go the opposite direction
                        boolean oldDirectionIsCcw = deltaThumbAndTick > 0;
                        deltaThumbAndTick = getDelta(mThumbAngle, tickAngle, !oldDirectionIsCcw);
                        boundAngle = getBoundIfExceeds(mThumbAngle, deltaThumbAndTick);
                        if (boundAngle != Integer.MIN_VALUE) {
                            // Cannot get to the tapped location because it is out-of-bounds
                            setMode(MODE_IGNORE_UNTIL_TOUCHES_THUMB);
                            return true;
                        }
                    }
                }

                setMode(MODE_WAITING_FOR_DRAG_THUMB_AFTER_JUMP);
                mInitialGrabThumbAngle = touchAngle;
                boolean ccw = deltaThumbAndTick > 0;
                setThumbAngleAnimated(tickAngle, 0, ccw);
                
                /*
                 * Our thumb scrolling animation takes us from mThumbAngle to
                 * tickAngle, so manifest that as the user dragging the thumb
                 * there.
                 */
                onThumbDragStarted(mThumbAngle);
                // We know which direction we want to go
                onThumbDragged(tickAngle, true, ccw);
                
            } else {
                // They tapped somewhere else on the widget
                setMode(MODE_WAITING_FOR_MOVE_ZOOM_RING);
                mCallback.onZoomRingSetMovableHintVisible(true);
            }

        } else if (mMode == MODE_WAITING_FOR_DRAG_THUMB_AFTER_JUMP) {
            int deltaDownAngle = getDelta(mInitialGrabThumbAngle, touchAngle);
            if ((deltaDownAngle < -THUMB_DRAG_SLOP || deltaDownAngle > THUMB_DRAG_SLOP) &&
                    isDeltaInBounds(mInitialGrabThumbAngle, deltaDownAngle)) {
                setMode(MODE_DRAG_THUMB);
                
                // No need to call onThumbDragStarted, since that was done when they tapped-to-jump
            }

        } else if (mMode == MODE_WAITING_FOR_MOVE_ZOOM_RING) {
            if (Math.abs(x - mPreviousDownX) > mTouchSlop ||
                    Math.abs(y - mPreviousDownY) > mTouchSlop) {
                /* Make sure the user has moved the slop amount before going into that mode. */
                setMode(MODE_MOVE_ZOOM_RING);
                mCallback.onZoomRingMovingStarted();
                // Move the zoom ring so it is under the finger where the user first touched
                mCallback.onZoomRingMoved(x - mPreviousDownX, y - mPreviousDownY, rawX, rawY);
            }
        } else if (mMode == MODE_IGNORE_UNTIL_TOUCHES_THUMB) {
            if (isTouchingThumb) {
                // The user is back on the thumb, let's go back to the previous mode
                setMode(mPreviousMode);
            }
        }

        // Purposefully not an "else if"
        if (mMode == MODE_DRAG_THUMB || mMode == MODE_TAP_DRAG) {
            if (isTouchingRing) {
                onThumbDragged(touchAngle, false, false);
            }
        } else if (mMode == MODE_MOVE_ZOOM_RING) {
            onZoomRingMoved(rawX, rawY);
        }

        return true;
    }
    
    private void onTouchUp(long time, boolean isTouchingRing) {
        int mode = mMode;
        if (mode == MODE_IGNORE_UNTIL_TOUCHES_THUMB) {
            // For cleaning up, pretend like the user was still in the previous mode
            mode = mPreviousMode;
        }
        
        if (mode == MODE_MOVE_ZOOM_RING || mode == MODE_WAITING_FOR_MOVE_ZOOM_RING) {
            mCallback.onZoomRingSetMovableHintVisible(false);
            if (mode == MODE_MOVE_ZOOM_RING) {
                mCallback.onZoomRingMovingStopped();
            }
        } else if (mode == MODE_DRAG_THUMB || mode == MODE_TAP_DRAG ||
                mode == MODE_WAITING_FOR_DRAG_THUMB_AFTER_JUMP) {
            onThumbDragStopped();
            
            if (mode == MODE_DRAG_THUMB || mode == MODE_TAP_DRAG) {
                // Animate back to a tick
                setThumbAngleAnimated(mPreviousCallbackTickAngle, 0);
            }
        }
        mCallback.onUserInteractionStopped();
        
        if (!isTouchingRing) {
            mPreviousCenterUpTime = time;
        }
    }
    
    private void setMode(int mode) {
        if (mode != mMode) {
            mPreviousMode = mMode;
            mMode = mode;
        }
    }

    private boolean isDeltaInBounds(int startAngle, int deltaAngle) {
        return getBoundIfExceeds(startAngle, deltaAngle) == Integer.MIN_VALUE;
    }
    
    private int getBoundIfExceeds(int startAngle, int deltaAngle) {
        if (deltaAngle > 0) {
            // Counterclockwise movement
            if (mThumbCcwBound != Integer.MIN_VALUE &&
                    getDelta(startAngle, mThumbCcwBound, true) < deltaAngle) {
                return mThumbCcwBound;
            }
        } else if (deltaAngle < 0) {
            // Clockwise movement, both of these will be negative
            int deltaThumbAndBound = getDelta(startAngle, mThumbCwBound, false);
            if (mThumbCwBound != Integer.MIN_VALUE &&
                    deltaThumbAndBound > deltaAngle) {
                // Tapped outside of the bound in that direction                    
                return mThumbCwBound;
            }
        }
        
        return Integer.MIN_VALUE;
    }

    private int getDelta(int startAngle, int endAngle, boolean useDirection, boolean ccw) {
        return useDirection ? getDelta(startAngle, endAngle, ccw) : getDelta(startAngle, endAngle);
    }
    
    /**
     * Gets the smallest delta between two angles, and infers the direction
     * based on the shortest path between the two angles. If going from
     * startAngle to endAngle is counterclockwise, the result will be positive.
     * If it is clockwise, the result will be negative.
     * 
     * @param startAngle The start angle.
     * @param endAngle The end angle.
     * @return The difference in angles.
     */
    private int getDelta(int startAngle, int endAngle) {
        int largerAngle, smallerAngle;
        if (endAngle > startAngle) {
            largerAngle = endAngle;
            smallerAngle = startAngle;
        } else {
            largerAngle = startAngle;
            smallerAngle = endAngle;
        }

        int delta = largerAngle - smallerAngle;
        if (delta <= PI_INT_MULTIPLIED) {
            // If going clockwise, negate the delta
            return startAngle == largerAngle ? -delta : delta; 
        } else {
            // The other direction is the delta we want (it includes the
            // discontinuous 0-2PI angle)
            delta = TWO_PI_INT_MULTIPLIED - delta;
            // If going clockwise, negate the delta
            return startAngle == smallerAngle ? -delta : delta; 
        }
    }

    /**
     * Gets the delta between two angles in the direction specified.
     * 
     * @param startAngle The start angle.
     * @param endAngle The end angle.
     * @param counterClockwise The direction to take when computing the delta.
     * @return The difference in angles in the given direction.
     */
    private int getDelta(int startAngle, int endAngle, boolean counterClockwise) {
        int delta = endAngle - startAngle;
        
        if (!counterClockwise && delta > 0) {
            // Crossed the discontinuous 0/2PI angle, take the leftover slice of
            // the pie and negate it
            return -TWO_PI_INT_MULTIPLIED + delta;
        } else if (counterClockwise && delta < 0) {
            // Crossed the discontinuous 0/2PI angle, take the leftover slice of
            // the pie (and ensure it is positive)
            return TWO_PI_INT_MULTIPLIED + delta;
        } else {
            return delta;
        }
    }
    
    private void onThumbDragStarted(int startAngle) {
        setThumbArrowsVisible(false);
        mThumbDragStartAngle = startAngle;
        mCallback.onZoomRingThumbDraggingStarted();
    }
    
    private void onThumbDragged(int touchAngle, boolean useDirection, boolean ccw) {
        boolean animateThumbToNewAngle = false;
        
        int totalDeltaAngle;
        totalDeltaAngle = getDelta(mPreviousCallbackTickAngle, touchAngle, useDirection, ccw);
        if (totalDeltaAngle >= mFuzzyTickDelta
                || totalDeltaAngle <= -mFuzzyTickDelta) {

            if (!useDirection) {
                // Set ccw to match the direction found by getDelta
                ccw = totalDeltaAngle > 0;
            }
            
            /*
             * When the user slides the thumb through the tick that corresponds
             * to a zoom bound, we don't want to abruptly stop there. Instead,
             * let the user slide it to the next tick, and then animate it back
             * to the original zoom bound tick. Because of this, we make sure
             * the delta from the bound is more than halfway to the next tick.
             * We make sure the bound is between the touch and the previous
             * callback to ensure we just passed the bound.
             */ 
            int oldTouchAngle = touchAngle;
            if (ccw && mThumbCcwBound != Integer.MIN_VALUE) {
                int deltaCcwBoundAndTouch =
                        getDelta(mThumbCcwBound, touchAngle, useDirection, true);
                if (deltaCcwBoundAndTouch >= mTickDelta / 2) {
                    // The touch has past a bound
                    int deltaPreviousCbAndTouch = getDelta(mPreviousCallbackTickAngle,
                            touchAngle, useDirection, true);
                    if (deltaPreviousCbAndTouch >= deltaCcwBoundAndTouch) {
                        // The bound is between the previous callback angle and the touch
                        touchAngle = mThumbCcwBound;
                        // We're moving the touch BACK to the bound, so opposite direction
                        ccw = false;
                    }
                }
            } else if (!ccw && mThumbCwBound != Integer.MIN_VALUE) {
                // See block above for general comments
                int deltaCwBoundAndTouch =
                        getDelta(mThumbCwBound, touchAngle, useDirection, false);
                if (deltaCwBoundAndTouch <= -mTickDelta / 2) {
                    int deltaPreviousCbAndTouch = getDelta(mPreviousCallbackTickAngle,
                            touchAngle, useDirection, false);
                    /*
                     * Both of these will be negative since we got delta in
                     * clockwise direction, and we want the magnitude of
                     * deltaPreviousCbAndTouch to be greater than the magnitude
                     * of deltaCwBoundAndTouch
                     */
                    if (deltaPreviousCbAndTouch <= deltaCwBoundAndTouch) {
                        touchAngle = mThumbCwBound;
                        ccw = true;
                    }
                }
            }
            if (touchAngle != oldTouchAngle) {
                // We bounded the touch angle
                totalDeltaAngle = getDelta(mPreviousCallbackTickAngle, touchAngle, useDirection, ccw);
                animateThumbToNewAngle = true;
                setMode(MODE_IGNORE_UNTIL_TOUCHES_THUMB);
            }
            
            
            // Prevent it from jumping too far
            if (mEnforceMaxAbsJump) {
                if (totalDeltaAngle <= -MAX_ABS_JUMP_DELTA_ANGLE) {
                    totalDeltaAngle = -MAX_ABS_JUMP_DELTA_ANGLE;
                    animateThumbToNewAngle = true;
                } else if (totalDeltaAngle >= MAX_ABS_JUMP_DELTA_ANGLE) {
                    totalDeltaAngle = MAX_ABS_JUMP_DELTA_ANGLE;
                    animateThumbToNewAngle = true;
                }
            }

            /*
             * We need to cover the edge case of a user grabbing the thumb,
             * going into the center of the widget, and then coming out from the
             * center to an angle that's slightly below the angle he's trying to
             * hit. If we do int division, we'll end up with one level lower
             * than the one he was going for.
             */
            int deltaLevels = Math.round((float) totalDeltaAngle / mTickDelta); 
            if (deltaLevels != 0) {
                boolean canStillZoom = mCallback.onZoomRingThumbDragged(
                        deltaLevels, mThumbDragStartAngle, touchAngle);
                
                if (mVibration) {
                    // TODO: we're trying the haptics to see how it goes with
                    // users, so we're ignoring the settings (for now)
                    performHapticFeedback(HapticFeedbackConstants.ZOOM_RING_TICK,
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING |
                            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                }
                
                // Set the callback angle to the actual angle based on how many delta levels we gave
                mPreviousCallbackTickAngle = getValidAngle(
                        mPreviousCallbackTickAngle + (deltaLevels * mTickDelta));
            }
        }

        if (DRAW_TRAIL) {
            int deltaAngle = getDelta(mThumbAngle, touchAngle, useDirection, ccw);
            mAcculumalatedTrailAngle += Math.toDegrees(deltaAngle / (double) RADIAN_INT_MULTIPLIER);
        }
            
        if (animateThumbToNewAngle) {
            if (useDirection) {
                setThumbAngleAnimated(touchAngle, 0, ccw);
            } else {
                setThumbAngleAnimated(touchAngle, 0);                
            }
        } else {
            setThumbAngleAuto(touchAngle, useDirection, ccw);
        }
    }
//    private void onThumbDragged(int touchAngle, boolean useDirection, boolean ccw) {
//        int deltaPrevCbAndTouch = getDelta(mPreviousCallbackAngle, touchAngle, useDirection, ccw);
//        
//        if (!useDirection) {
//            // Set ccw to match the direction found by getDelta
//            ccw = deltaPrevCbAndTouch > 0;
//            useDirection = true;            
//        }
//
//        boolean animateThumbToNewAngle = false;
//        boolean animationCcw = ccw;
//
//        if (deltaPrevCbAndTouch >= mFuzzyCallbackThreshold
//                || deltaPrevCbAndTouch <= -mFuzzyCallbackThreshold) {
//
//            /*
//             * When the user slides the thumb through the tick that corresponds
//             * to a zoom bound, we don't want to abruptly stop there. Instead,
//             * let the user slide it to the next tick, and then animate it back
//             * to the original zoom bound tick. Because of this, we make sure
//             * the delta from the bound is more than halfway to the next tick.
//             * We make sure the bound is between the touch and the previous
//             * callback to ensure we JUST passed the bound.
//             */ 
//            int oldTouchAngle = touchAngle;
//            if (ccw && mThumbCcwBound != Integer.MIN_VALUE) {
//                int deltaCcwBoundAndTouch =
//                        getDelta(mThumbCcwBound, touchAngle, true, ccw);
//                if (deltaCcwBoundAndTouch >= mCallbackThreshold / 2) {
//                    // The touch has past far enough from the bound
//                    int deltaPreviousCbAndTouch = getDelta(mPreviousCallbackAngle,
//                            touchAngle, true, ccw);
//                    if (deltaPreviousCbAndTouch >= deltaCcwBoundAndTouch) {
//                        // The bound is between the previous callback angle and the touch
//                        // Cap to the bound
//                        touchAngle = mThumbCcwBound;
//                        /*
//                         * We're moving the touch BACK to the bound, so animate
//                         * back in the opposite direction that passed the bound.
//                         */
//                        animationCcw = false;
//                    }
//                }
//            } else if (!ccw && mThumbCwBound != Integer.MIN_VALUE) {
//                // See block above for general comments
//                int deltaCwBoundAndTouch =
//                        getDelta(mThumbCwBound, touchAngle, true, ccw);
//                if (deltaCwBoundAndTouch <= -mCallbackThreshold / 2) {
//                    int deltaPreviousCbAndTouch = getDelta(mPreviousCallbackAngle,
//                            touchAngle, true, ccw);
//                    /*
//                     * Both of these will be negative since we got delta in
//                     * clockwise direction, and we want the magnitude of
//                     * deltaPreviousCbAndTouch to be greater than the magnitude
//                     * of deltaCwBoundAndTouch
//                     */
//                    if (deltaPreviousCbAndTouch <= deltaCwBoundAndTouch) {
//                        touchAngle = mThumbCwBound;
//                        animationCcw = true;
//                    }
//                }
//            }
//            if (touchAngle != oldTouchAngle) {
//                // We bounded the touch angle
//                deltaPrevCbAndTouch = getDelta(mPreviousCallbackAngle, touchAngle, true, ccw);
//                // Animate back to the bound
//                animateThumbToNewAngle = true;
//                // Disallow movement now
//                setMode(MODE_IGNORE_UNTIL_UP);
//            }
//            
//            
//            /*
//             * Prevent it from jumping too far (this could happen if the user
//             * goes through the center)
//             */
//
//            if (mEnforceMaxAbsJump) {
//                if (deltaPrevCbAndTouch <= -MAX_ABS_JUMP_DELTA_ANGLE) {
//                    deltaPrevCbAndTouch = -MAX_ABS_JUMP_DELTA_ANGLE;
//                    animateThumbToNewAngle = true;
//                } else if (deltaPrevCbAndTouch >= MAX_ABS_JUMP_DELTA_ANGLE) {
//                    deltaPrevCbAndTouch = MAX_ABS_JUMP_DELTA_ANGLE;
//                    animateThumbToNewAngle = true;
//                }
//            }
//
//            /*
//             * We need to cover the edge case of a user grabbing the thumb,
//             * going into the center of the widget, and then coming out from the
//             * center to an angle that's slightly below the angle he's trying to
//             * hit. If we do int division, we'll end up with one level lower
//             * than the one he was going for.
//             */
//            int deltaLevels = Math.round((float) deltaPrevCbAndTouch / mCallbackThreshold); 
//            if (deltaLevels != 0) {
//                boolean canStillZoom = mCallback.onZoomRingThumbDragged(
//                        deltaLevels, mThumbDragStartAngle, touchAngle);
//                
//                if (mVibration) {
//                    // TODO: we're trying the haptics to see how it goes with
//                    // users, so we're ignoring the settings (for now)
//                    performHapticFeedback(HapticFeedbackConstants.ZOOM_RING_TICK,
//                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING |
//                            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
//                
//                }
//                // Set the callback angle to the actual angle based on how many delta levels we gave
//                mPreviousCallbackAngle = getValidAngle(
//                        mPreviousCallbackAngle + (deltaLevels * mCallbackThreshold));
//            }
//        }
//
//        if (DRAW_TRAIL) {
//            int deltaAngle = getDelta(mThumbAngle, touchAngle, true, ccw);
//            mAcculumalatedTrailAngle += Math.toDegrees(deltaAngle / (double) RADIAN_INT_MULTIPLIER);
//        }
//            
//        if (animateThumbToNewAngle) {
//            setThumbAngleAnimated(touchAngle, 0, animationCcw);
//        } else {
//            /*
//             * Use regular ccw here because animationCcw will never have been
//             * changed if animateThumbToNewAngle is false
//             */
//            setThumbAngleAuto(touchAngle, true, ccw);
//        }
//    }
    
    private int getValidAngle(int invalidAngle) {
        if (invalidAngle < 0) {
            return (invalidAngle % TWO_PI_INT_MULTIPLIED) + TWO_PI_INT_MULTIPLIED;
        } else if (invalidAngle >= TWO_PI_INT_MULTIPLIED) {
            return invalidAngle % TWO_PI_INT_MULTIPLIED;
        } else {
            return invalidAngle;
        }
    }

    private int getClosestTickAngle(int angle) {
        int smallerAngleDistance = angle % mTickDelta;
        int smallerAngle = angle - smallerAngleDistance;
        if (smallerAngleDistance < mTickDelta / 2) {
            // Closer to the smaller angle
            return smallerAngle;
        } else {
            // Closer to the bigger angle (premodding)
            return (smallerAngle + mTickDelta) % TWO_PI_INT_MULTIPLIED; 
        }
    }

    private void onThumbDragStopped() {
        mCallback.onZoomRingThumbDraggingStopped();
    }

    private void onZoomRingMoved(int rawX, int rawY) {
        if (mPreviousWidgetDragX != Integer.MIN_VALUE) {
            int deltaX = rawX - mPreviousWidgetDragX;
            int deltaY = rawY - mPreviousWidgetDragY;

            mCallback.onZoomRingMoved(deltaX, deltaY, rawX, rawY);
        }

        mPreviousWidgetDragX = rawX;
        mPreviousWidgetDragY = rawY;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (!hasWindowFocus) {
            mCallback.onZoomRingDismissed();
        }
    }
    
    private int getAngle(int localX, int localY) {
        int radians = (int) (Math.atan2(localY, localX) * RADIAN_INT_MULTIPLIER);

        // Convert from [-pi,pi] to {0,2pi]
        if (radians < 0) {
            radians = -radians;
        } else if (radians > 0) {
            radians = 2 * PI_INT_MULTIPLIED - radians;
        } else {
            radians = 0;
        }

        radians = radians - mZeroAngle;
        return radians >= 0 ? radians : radians + 2 * PI_INT_MULTIPLIED;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mThumbVisible) {
            if (DRAW_TRAIL) {
                mTrail.draw(canvas);
            }
            if ((mThumbArrowsToDraw & THUMB_ARROW_PLUS) != 0) {
                mZoomInArrowDrawable.draw(canvas);
                mZoomInArrowHintDrawable.draw(canvas);
            }
            if ((mThumbArrowsToDraw & THUMB_ARROW_MINUS) != 0) {
                mZoomOutArrowDrawable.draw(canvas);
                mZoomOutArrowHintDrawable.draw(canvas);
            }
            mThumbDrawable.draw(canvas);
        }
    }
    
    private void setThumbArrowsAngle(int angle) {
        int level = -angle * 10000 / ZoomRing.TWO_PI_INT_MULTIPLIED;
        mZoomInArrowDrawable.setLevel(level);
        mZoomOutArrowDrawable.setLevel(level);
        
        // Assume it is a square
        int halfSideLength = mZoomInArrowHintDrawable.getIntrinsicHeight() / 2;
        int unoffsetAngle = angle + mZeroAngle;
        
        int plusCenterX = (int) (Math.cos(1f * (unoffsetAngle - mZoomArrowHintOffsetAngle)
                / RADIAN_INT_MULTIPLIER) * mZoomArrowHintDistance) + mCenterX;
        int plusCenterY = (int) (Math.sin(1f * (unoffsetAngle - mZoomArrowHintOffsetAngle)
                / RADIAN_INT_MULTIPLIER) * mZoomArrowHintDistance) * -1 + mCenterY;
        mZoomInArrowHintDrawable.setBounds(plusCenterX - halfSideLength,
                plusCenterY - halfSideLength,
                plusCenterX + halfSideLength,
                plusCenterY + halfSideLength);
        
        int minusCenterX = (int) (Math.cos(1f * (unoffsetAngle + mZoomArrowHintOffsetAngle)
                / RADIAN_INT_MULTIPLIER) * mZoomArrowHintDistance) + mCenterX;
        int minusCenterY = (int) (Math.sin(1f * (unoffsetAngle + mZoomArrowHintOffsetAngle)
                / RADIAN_INT_MULTIPLIER) * mZoomArrowHintDistance) * -1 + mCenterY;
        mZoomOutArrowHintDrawable.setBounds(minusCenterX - halfSideLength,
                minusCenterY - halfSideLength,
                minusCenterX + halfSideLength,
                minusCenterY + halfSideLength);
    }
    
    void setThumbArrowsVisible(boolean visible) {
        if (visible) {
            mThumbArrowsAlpha = 255;
            int callbackAngle = mPreviousCallbackTickAngle;
            if (callbackAngle < mThumbCwBound - RADIAN_INT_ERROR ||
                    callbackAngle > mThumbCwBound + RADIAN_INT_ERROR) {
                mZoomInArrowDrawable.setAlpha(255);
                mZoomInArrowHintDrawable.setAlpha(255);
                mThumbArrowsToDraw |= THUMB_ARROW_PLUS;                
            } else {
                mThumbArrowsToDraw &= ~THUMB_ARROW_PLUS;
            }
            if (callbackAngle < mThumbCcwBound - RADIAN_INT_ERROR ||
                    callbackAngle > mThumbCcwBound + RADIAN_INT_ERROR) {
                mZoomOutArrowDrawable.setAlpha(255);
                mZoomOutArrowHintDrawable.setAlpha(255);
                mThumbArrowsToDraw |= THUMB_ARROW_MINUS;
            } else {
                mThumbArrowsToDraw &= ~THUMB_ARROW_MINUS;
            }
            invalidate();
        } else if (mThumbArrowsAlpha == 255) {
            // Only start fade if we're fully visible (otherwise another fade is happening already)
            mThumbArrowsFadeStartTime = SystemClock.elapsedRealtime();
            onThumbArrowsFadeStep();
        }
    }
    
    private void onThumbArrowsFadeStep() {
        if (mThumbArrowsAlpha <= 0) {
            mThumbArrowsToDraw = 0;
            return;
        }
        
        mThumbArrowsAlpha = (int)
                (255 - (255 * (SystemClock.elapsedRealtime() - mThumbArrowsFadeStartTime)
                        / THUMB_ARROWS_FADE_DURATION));
        if (mThumbArrowsAlpha < 0) mThumbArrowsAlpha = 0;
        if ((mThumbArrowsToDraw & THUMB_ARROW_PLUS) != 0) {
            mZoomInArrowDrawable.setAlpha(mThumbArrowsAlpha);
            mZoomInArrowHintDrawable.setAlpha(mThumbArrowsAlpha);
            invalidateDrawable(mZoomInArrowHintDrawable);
            invalidateDrawable(mZoomInArrowDrawable);
        }
        if ((mThumbArrowsToDraw & THUMB_ARROW_MINUS) != 0) {
            mZoomOutArrowDrawable.setAlpha(mThumbArrowsAlpha);
            mZoomOutArrowHintDrawable.setAlpha(mThumbArrowsAlpha);
            invalidateDrawable(mZoomOutArrowHintDrawable);
            invalidateDrawable(mZoomOutArrowDrawable);
        }
            
        if (!mHandler.hasMessages(MSG_THUMB_ARROWS_FADE_STEP)) {
            mHandler.sendEmptyMessage(MSG_THUMB_ARROWS_FADE_STEP);
        }
    }
    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        
        setThumbArrowsAngle(mThumbAngle);
        setThumbArrowsVisible(true);
    }

    public interface OnZoomRingCallback {
        void onZoomRingSetMovableHintVisible(boolean visible);
        
        void onZoomRingMovingStarted();
        boolean onZoomRingMoved(int deltaX, int deltaY, int rawX, int rawY);
        void onZoomRingMovingStopped();
        
        void onZoomRingThumbDraggingStarted();
        boolean onZoomRingThumbDragged(int numLevels, int startAngle, int curAngle);
        void onZoomRingThumbDraggingStopped();
        
        void onZoomRingDismissed();
        
        void onUserInteractionStarted();
        void onUserInteractionStopped();
    }

    private static void printAngle(String angleName, int angle) {
        Log.d(TAG, angleName + ": " + (long) angle * 180 / PI_INT_MULTIPLIED);
    }
}
