package android.widget;

import com.android.internal.R;

import android.content.Context;
import android.content.res.Resources;
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
 * @hide
 */
public class ZoomRing extends View {

    // TODO: move to ViewConfiguration?
    static final int DOUBLE_TAP_DISMISS_TIMEOUT = ViewConfiguration.getDoubleTapTimeout();
    // TODO: get from theme
    private static final String TAG = "ZoomRing";

    // TODO: Temporary until the trail is done
    private static final boolean DRAW_TRAIL = false;

    // TODO: xml
    private static final int THUMB_DISTANCE = 63;

    /** To avoid floating point calculations, we multiply radians by this value. */
    public static final int RADIAN_INT_MULTIPLIER = 10000;
    public static final int RADIAN_INT_ERROR = 100;
    /** PI using our multiplier. */
    public static final int PI_INT_MULTIPLIED = (int) (Math.PI * RADIAN_INT_MULTIPLIER);
    public static final int TWO_PI_INT_MULTIPLIED = PI_INT_MULTIPLIED * 2;
    /** PI/2 using our multiplier. */
    private static final int HALF_PI_INT_MULTIPLIED = PI_INT_MULTIPLIED / 2;

    private int mZeroAngle = HALF_PI_INT_MULTIPLIED * 3;
    
    private static final int THUMB_GRAB_SLOP = PI_INT_MULTIPLIED / 8;
    private static final int THUMB_DRAG_SLOP = PI_INT_MULTIPLIED / 12;

    /**
     * Includes error because we compare this to the result of
     * getDelta(getClosestTickeAngle(..), oldAngle) which ends up having some
     * rounding error.
     */ 
    private static final int MAX_ABS_JUMP_DELTA_ANGLE = (2 * PI_INT_MULTIPLIED / 3) +
            RADIAN_INT_ERROR; 

    /** The cached X of our center. */
    private int mCenterX;
    /** The cached Y of our center. */
    private int mCenterY;

    /** The angle of the thumb (in int radians) */
    private int mThumbAngle;
    private int mThumbHalfWidth;
    private int mThumbHalfHeight;
    
    private int mThumbCwBound = Integer.MIN_VALUE;
    private int mThumbCcwBound = Integer.MIN_VALUE;
    private boolean mEnforceMaxAbsJump = true;
    
    /** The inner radius of the track. */
    private int mBoundInnerRadiusSquared = 0;
    /** The outer radius of the track. */
    private int mBoundOuterRadiusSquared = Integer.MAX_VALUE;

    private int mPreviousWidgetDragX;
    private int mPreviousWidgetDragY;

    private boolean mDrawThumb = true;
    private Drawable mThumbDrawable;
    
    /** Shown beneath the thumb if we can still zoom in. */
    private Drawable mThumbPlusArrowDrawable;
    /** Shown beneath the thumb if we can still zoom out. */
    private Drawable mThumbMinusArrowDrawable;
    private static final int THUMB_ARROW_PLUS = 1 << 0;
    private static final int THUMB_ARROW_MINUS = 1 << 1;
    /** Bitwise-OR of {@link #THUMB_ARROW_MINUS} and {@link #THUMB_ARROW_PLUS} */
    private int mThumbArrowsToDraw;
    private static final int THUMB_ARROWS_FADE_DURATION = 300;
    private long mThumbArrowsFadeStartTime;
    private int mThumbArrowsAlpha = 255;
    
    private static final int MODE_IDLE = 0;

    /**
     * User has his finger down somewhere on the ring (besides the thumb) and we
     * are waiting for him to move the slop amount before considering him in the
     * drag thumb state.
     */
    private static final int MODE_WAITING_FOR_DRAG_THUMB = 5;
    private static final int MODE_DRAG_THUMB = 1;
    /**
     * User has his finger down, but we are waiting for him to pass the touch
     * slop before going into the #MODE_MOVE_ZOOM_RING. This is a good time to
     * show the movable hint.
     */
    private static final int MODE_WAITING_FOR_MOVE_ZOOM_RING = 4;
    private static final int MODE_MOVE_ZOOM_RING = 2;
    private static final int MODE_TAP_DRAG = 3;
    /** Ignore the touch interaction.  Reset to MODE_IDLE after up/cancel. */
    private static final int MODE_IGNORE_UNTIL_UP = 6;
    private int mMode;

    private long mPreviousUpTime;
    private int mPreviousDownX;
    private int mPreviousDownY;

    private int mWaitingForDragThumbDownAngle;
    
    private OnZoomRingCallback mCallback;
    private int mPreviousCallbackAngle;
    private int mCallbackThreshold = Integer.MAX_VALUE;

    private boolean mResetThumbAutomatically = true;
    private int mThumbDragStartAngle;

    private final int mTouchSlop;

    private Drawable mTrail;
    private double mAcculumalatedTrailAngle;
    
    private Scroller mThumbScroller;

    private static final int MSG_THUMB_SCROLLER_TICK = 1;
    private static final int MSG_THUMB_ARROWS_FADE_TICK = 2;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_THUMB_SCROLLER_TICK:
                    onThumbScrollerTick();
                    break;
                    
                case MSG_THUMB_ARROWS_FADE_TICK:
                    onThumbArrowsFadeTick();
                    break;
            }
        }
    };
    
    public ZoomRing(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        mTouchSlop = viewConfiguration.getScaledTouchSlop();

        // TODO get drawables from style instead
        Resources res = context.getResources();
        mThumbDrawable = res.getDrawable(R.drawable.zoom_ring_thumb);
        mThumbPlusArrowDrawable = res.getDrawable(R.drawable.zoom_ring_thumb_plus_arrow_rotatable).
                mutate();
        mThumbMinusArrowDrawable = res.getDrawable(R.drawable.zoom_ring_thumb_minus_arrow_rotatable).
                mutate();
        if (DRAW_TRAIL) {
            mTrail = res.getDrawable(R.drawable.zoom_ring_trail).mutate();
        }

        // TODO: add padding to drawable
        setBackgroundResource(R.drawable.zoom_ring_track);
        // TODO get from style
        setRingBounds(43, Integer.MAX_VALUE);

        mThumbHalfHeight = mThumbDrawable.getIntrinsicHeight() / 2;
        mThumbHalfWidth = mThumbDrawable.getIntrinsicWidth() / 2;

        mCallbackThreshold = PI_INT_MULTIPLIED / 6;
    }

    public ZoomRing(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomRing(Context context) {
        this(context, null);
    }

    public void setCallback(OnZoomRingCallback callback) {
        mCallback = callback;
    }

    // TODO: rename
    public void setCallbackThreshold(int callbackThreshold) {
        mCallbackThreshold = callbackThreshold;
    }

    // TODO: from XML too
    public void setRingBounds(int innerRadius, int outerRadius) {
        mBoundInnerRadiusSquared = innerRadius * innerRadius;
        if (mBoundInnerRadiusSquared < innerRadius) {
            // Prevent overflow
            mBoundInnerRadiusSquared = Integer.MAX_VALUE;
        }

        mBoundOuterRadiusSquared = outerRadius * outerRadius;
        if (mBoundOuterRadiusSquared < outerRadius) {
            // Prevent overflow
            mBoundOuterRadiusSquared = Integer.MAX_VALUE;
        }
    }

    public void setThumbClockwiseBound(int angle) {
        if (angle < 0) {
            mThumbCwBound = Integer.MIN_VALUE;
        } else {
            mThumbCwBound = getClosestTickAngle(angle);
        }
        setEnforceMaxAbsJump();
    }
    
    public void setThumbCounterclockwiseBound(int angle) {
        if (angle < 0) {
            mThumbCcwBound = Integer.MIN_VALUE;
        } else {
            mThumbCcwBound = getClosestTickAngle(angle);
        }
        setEnforceMaxAbsJump();
    }
    
    private void setEnforceMaxAbsJump() {
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
        mPreviousCallbackAngle = getClosestTickAngle(angle);
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
                THUMB_DISTANCE) + mCenterX;
        int thumbCenterY = (int) (Math.sin(1f * unoffsetAngle / RADIAN_INT_MULTIPLIER) *
                THUMB_DISTANCE) * -1 + mCenterY;

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
        // Counter clockwise if the new angle is more the current angle
        boolean counterClockwise = deltaAngle > 0;
        
        if (deltaAngle > PI_INT_MULTIPLIED || deltaAngle < -PI_INT_MULTIPLIED) {
            // It's quicker to go the other direction
            counterClockwise = !counterClockwise;
        }
        
        setThumbAngleAnimated(angle, duration, counterClockwise);
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
        onThumbScrollerTick();
    }
    
    private int getAnimationDuration(int deltaAngle) {
        if (deltaAngle < 0) deltaAngle *= -1;
        return 300 + deltaAngle * 300 / RADIAN_INT_MULTIPLIER; 
    }
    
    private void onThumbScrollerTick() {
        if (!mThumbScroller.computeScrollOffset()) return;
        setThumbAngleInt(getThumbScrollerAngle());        
        mHandler.sendEmptyMessage(MSG_THUMB_SCROLLER_TICK);
    }

    private int getThumbScrollerAngle() {
        return mThumbScroller.getCurrX() % TWO_PI_INT_MULTIPLIED;
    }
    
    public void resetThumbAngle(int angle) {
        mPreviousCallbackAngle = angle;
        setThumbAngleInt(angle);
    }

    public void resetThumbAngle() {
        if (mResetThumbAutomatically) {
            resetThumbAngle(0);
        }
    }
    
    public void setResetThumbAutomatically(boolean resetThumbAutomatically) {
        mResetThumbAutomatically = resetThumbAutomatically;
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
        
        mThumbPlusArrowDrawable.setBounds(0, 0, right - left, bottom - top);
        mThumbMinusArrowDrawable.setBounds(0, 0, right - left, bottom - top);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return handleTouch(event.getAction(), event.getEventTime(),
                (int) event.getX(), (int) event.getY(), (int) event.getRawX(),
                (int) event.getRawY());
    }

    private void resetState() {
        mMode = MODE_IDLE;
        mPreviousWidgetDragX = mPreviousWidgetDragY = Integer.MIN_VALUE;
        mAcculumalatedTrailAngle = 0.0;
    }

    public void setTapDragMode(boolean tapDragMode, int x, int y) {
        resetState();
        mMode = tapDragMode ? MODE_TAP_DRAG : MODE_IDLE;

        if (tapDragMode) {
            onThumbDragStarted(getAngle(x - mCenterX, y - mCenterY));
        }
    }

    public boolean handleTouch(int action, long time, int x, int y, int rawX, int rawY) {
        switch (action) {

            case MotionEvent.ACTION_DOWN:
                if (time - mPreviousUpTime <= DOUBLE_TAP_DISMISS_TIMEOUT) {
                    mCallback.onZoomRingDismissed(true);
                    onTouchUp(time);
                    
                    // Dismissing, so halt here
                    return true;
                }

                mCallback.onUserInteractionStarted();
                mPreviousDownX = x;
                mPreviousDownY = y;
                resetState();
                // Fall through to code below switch (since the down is used for
                // jumping to the touched tick)
                break;

            case MotionEvent.ACTION_MOVE:
                if (mMode == MODE_IGNORE_UNTIL_UP) return true;
                
                // Fall through to code below switch
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                onTouchUp(time);
                return true;

            default:
                return false;
        }

        // local{X,Y} will be where the center of the widget is (0,0)
        int localX = x - mCenterX;
        int localY = y - mCenterY;
        boolean isTouchingThumb = true;
        boolean isInRingBounds = true;
        
        int touchAngle = getAngle(localX, localY);
        int radiusSquared = localX * localX + localY * localY;
        if (radiusSquared < mBoundInnerRadiusSquared ||
                radiusSquared > mBoundOuterRadiusSquared) {
            // Out-of-bounds
            isTouchingThumb = false;
            isInRingBounds = false;
        }

        int deltaThumbAndTouch = getDelta(mThumbAngle, touchAngle);
        int absoluteDeltaThumbAndTouch = deltaThumbAndTouch >= 0 ?
                deltaThumbAndTouch : -deltaThumbAndTouch;
        if (isTouchingThumb &&
                absoluteDeltaThumbAndTouch > THUMB_GRAB_SLOP) {
            // Didn't grab close enough to the thumb
            isTouchingThumb = false;
        }

        if (mMode == MODE_IDLE) {
            if (isTouchingThumb) {
                // They grabbed the thumb
                mMode = MODE_DRAG_THUMB;
                onThumbDragStarted(touchAngle);
                
            } else if (isInRingBounds) {
                // They tapped somewhere else on the ring
                int tickAngle = getClosestTickAngle(touchAngle);

                int deltaThumbAndTick = getDelta(mThumbAngle, tickAngle);
                int boundAngle = getBoundIfExceeds(mThumbAngle, deltaThumbAndTick);

                if (mEnforceMaxAbsJump) {
                    // Enforcing the max jump
                    if (deltaThumbAndTick > MAX_ABS_JUMP_DELTA_ANGLE ||
                            deltaThumbAndTick < -MAX_ABS_JUMP_DELTA_ANGLE) {
                        // Trying to jump too far, ignore this touch interaction                    
                        mMode = MODE_IGNORE_UNTIL_UP;
                        return true;
                    }

                    // Make sure we only let them jump within bounds
                    if (boundAngle != Integer.MIN_VALUE) {
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
                            // Not allowed to be here, it is between two bounds
                            mMode = MODE_IGNORE_UNTIL_UP;
                            return true;
                        }
                    }
                }

                mMode = MODE_WAITING_FOR_DRAG_THUMB;
                mWaitingForDragThumbDownAngle = touchAngle;
                boolean ccw = deltaThumbAndTick > 0;
                setThumbAngleAnimated(tickAngle, 0, ccw);
                
                // Our thumb scrolling animation takes us from mThumbAngle to tickAngle
                onThumbDragStarted(mThumbAngle);
                onThumbDragged(tickAngle, true, ccw);
                
            } else {
                // They tapped somewhere else
                mMode = MODE_WAITING_FOR_MOVE_ZOOM_RING;
                mCallback.onZoomRingSetMovableHintVisible(true);
            }

        } else if (mMode == MODE_WAITING_FOR_DRAG_THUMB) {
            int deltaDownAngle = getDelta(mWaitingForDragThumbDownAngle, touchAngle);
            if ((deltaDownAngle < -THUMB_DRAG_SLOP || deltaDownAngle > THUMB_DRAG_SLOP) &&
                    isDeltaInBounds(mWaitingForDragThumbDownAngle, deltaDownAngle)) {
                mMode = MODE_DRAG_THUMB;
            }

        } else if (mMode == MODE_WAITING_FOR_MOVE_ZOOM_RING) {
            if (Math.abs(x - mPreviousDownX) > mTouchSlop ||
                    Math.abs(y - mPreviousDownY) > mTouchSlop) {
                /* Make sure the user has moved the slop amount before going into that mode. */
                mMode = MODE_MOVE_ZOOM_RING;
                mCallback.onZoomRingMovingStarted();
            }
        }

        // Purposefully not an "else if"
        if (mMode == MODE_DRAG_THUMB || mMode == MODE_TAP_DRAG) {
            if (isInRingBounds) {
                onThumbDragged(touchAngle, false, false);
            }
        } else if (mMode == MODE_MOVE_ZOOM_RING) {
            onZoomRingMoved(rawX, rawY);
        }

        return true;
    }
    
    private void onTouchUp(long time) {
        if (mMode == MODE_MOVE_ZOOM_RING || mMode == MODE_WAITING_FOR_MOVE_ZOOM_RING) {
            mCallback.onZoomRingSetMovableHintVisible(false);
            if (mMode == MODE_MOVE_ZOOM_RING) {
                mCallback.onZoomRingMovingStopped();
            }
        } else if (mMode == MODE_DRAG_THUMB || mMode == MODE_TAP_DRAG ||
                mMode == MODE_WAITING_FOR_DRAG_THUMB) {
            onThumbDragStopped();
            
            if (mMode == MODE_DRAG_THUMB) {
                // Animate back to a tick
                setThumbAngleAnimated(mPreviousCallbackAngle, 0);
            }
        }

        mPreviousUpTime = time;
        mCallback.onUserInteractionStopped();
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
        totalDeltaAngle = getDelta(mPreviousCallbackAngle, touchAngle, useDirection, ccw);
        int fuzzyCallbackThreshold = (int) (mCallbackThreshold * 0.65f);
        if (totalDeltaAngle >= fuzzyCallbackThreshold
                || totalDeltaAngle <= -fuzzyCallbackThreshold) {

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
                if (deltaCcwBoundAndTouch >= mCallbackThreshold / 2) {
                    // The touch has past a bound
                    int deltaPreviousCbAndTouch = getDelta(mPreviousCallbackAngle,
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
                if (deltaCwBoundAndTouch <= -mCallbackThreshold / 2) {
                    int deltaPreviousCbAndTouch = getDelta(mPreviousCallbackAngle,
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
                totalDeltaAngle = getDelta(mPreviousCallbackAngle, touchAngle, useDirection, ccw);
                animateThumbToNewAngle = true;
                mMode = MODE_IGNORE_UNTIL_UP;
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
            int deltaLevels = Math.round((float) totalDeltaAngle / mCallbackThreshold); 
            if (deltaLevels != 0) {
                boolean canStillZoom = mCallback.onZoomRingThumbDragged(
                        deltaLevels, mThumbDragStartAngle, touchAngle);
                
                // TODO: we're trying the haptics to see how it goes with
                // users, so we're ignoring the settings (for now)
                performHapticFeedback(HapticFeedbackConstants.ZOOM_RING_TICK,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING |
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                
                // Set the callback angle to the actual angle based on how many delta levels we gave
                mPreviousCallbackAngle = getValidAngle(
                        mPreviousCallbackAngle + (deltaLevels * mCallbackThreshold));
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
        int smallerAngleDistance = angle % mCallbackThreshold;
        int smallerAngle = angle - smallerAngleDistance;
        if (smallerAngleDistance < mCallbackThreshold / 2) {
            // Closer to the smaller angle
            return smallerAngle;
        } else {
            // Closer to the bigger angle (premodding)
            return (smallerAngle + mCallbackThreshold) % TWO_PI_INT_MULTIPLIED; 
        }
    }

    private void onThumbDragStopped() {
        mCallback.onZoomRingThumbDraggingStopped();
    }

    private void onZoomRingMoved(int x, int y) {
        if (mPreviousWidgetDragX != Integer.MIN_VALUE) {
            int deltaX = x - mPreviousWidgetDragX;
            int deltaY = y - mPreviousWidgetDragY;

            mCallback.onZoomRingMoved(deltaX, deltaY);
        }

        mPreviousWidgetDragX = x;
        mPreviousWidgetDragY = y;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (!hasWindowFocus) {
            mCallback.onZoomRingDismissed(true);
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

        if (mDrawThumb) {
            if (DRAW_TRAIL) {
                mTrail.draw(canvas);
            }
            if ((mThumbArrowsToDraw & THUMB_ARROW_PLUS) != 0) {
                mThumbPlusArrowDrawable.draw(canvas);
            }
            if ((mThumbArrowsToDraw & THUMB_ARROW_MINUS) != 0) {
                mThumbMinusArrowDrawable.draw(canvas);
            }
            mThumbDrawable.draw(canvas);
        }
    }
    
    private void setThumbArrowsAngle(int angle) {
        int level = -angle * 10000 / ZoomRing.TWO_PI_INT_MULTIPLIED;
        mThumbPlusArrowDrawable.setLevel(level);
        mThumbMinusArrowDrawable.setLevel(level);
    }
    
    public void setThumbArrowsVisible(boolean visible) {
        if (visible) {
            mThumbArrowsAlpha = 255;
            int callbackAngle = mPreviousCallbackAngle;
            if (callbackAngle < mThumbCwBound - RADIAN_INT_ERROR ||
                    callbackAngle > mThumbCwBound + RADIAN_INT_ERROR) {
                mThumbPlusArrowDrawable.setAlpha(255);
                mThumbArrowsToDraw |= THUMB_ARROW_PLUS;                
            } else {
                mThumbArrowsToDraw &= ~THUMB_ARROW_PLUS;
            }
            if (callbackAngle < mThumbCcwBound - RADIAN_INT_ERROR ||
                    callbackAngle > mThumbCcwBound + RADIAN_INT_ERROR) {
                mThumbMinusArrowDrawable.setAlpha(255);
                mThumbArrowsToDraw |= THUMB_ARROW_MINUS;
            } else {
                mThumbArrowsToDraw &= ~THUMB_ARROW_MINUS;
            }
            invalidate();
        } else if (mThumbArrowsAlpha == 255) {
            // Only start fade if we're fully visible (otherwise another fade is happening already)
            mThumbArrowsFadeStartTime = SystemClock.elapsedRealtime();
            onThumbArrowsFadeTick();
        }
    }
    
    private void onThumbArrowsFadeTick() {
        if (mThumbArrowsAlpha <= 0) {
            mThumbArrowsToDraw = 0;
            return;
        }
        
        mThumbArrowsAlpha = (int)
                (255 - (255 * (SystemClock.elapsedRealtime() - mThumbArrowsFadeStartTime)
                        / THUMB_ARROWS_FADE_DURATION));
        if (mThumbArrowsAlpha < 0) mThumbArrowsAlpha = 0;
        if ((mThumbArrowsToDraw & THUMB_ARROW_PLUS) != 0) {
            mThumbPlusArrowDrawable.setAlpha(mThumbArrowsAlpha);
            invalidateDrawable(mThumbPlusArrowDrawable);
        }
        if ((mThumbArrowsToDraw & THUMB_ARROW_MINUS) != 0) {
            mThumbMinusArrowDrawable.setAlpha(mThumbArrowsAlpha);
            invalidateDrawable(mThumbMinusArrowDrawable);
        }
            
        if (!mHandler.hasMessages(MSG_THUMB_ARROWS_FADE_TICK)) {
            mHandler.sendEmptyMessage(MSG_THUMB_ARROWS_FADE_TICK);
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
        boolean onZoomRingMoved(int deltaX, int deltaY);
        void onZoomRingMovingStopped();
        
        void onZoomRingThumbDraggingStarted();
        boolean onZoomRingThumbDragged(int numLevels, int startAngle, int curAngle);
        void onZoomRingThumbDraggingStopped();
        
        void onZoomRingDismissed(boolean dismissImmediately);
        
        void onUserInteractionStarted();
        void onUserInteractionStopped();
    }

    private static void printAngle(String angleName, int angle) {
        Log.d(TAG, angleName + ": " + (long) angle * 180 / PI_INT_MULTIPLIED);
    }
}
