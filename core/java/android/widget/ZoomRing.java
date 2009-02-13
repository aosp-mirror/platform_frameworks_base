package android.widget;

import com.android.internal.R;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RotateDrawable;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * @hide
 */
public class ZoomRing extends View {

    // TODO: move to ViewConfiguration?
    static final int DOUBLE_TAP_DISMISS_TIMEOUT = ViewConfiguration.getJumpTapTimeout();
    // TODO: get from theme
    private static final int DISABLED_ALPHA = 160;

    private static final String TAG = "ZoomRing";

    // TODO: Temporary until the trail is done
    private static final boolean DRAW_TRAIL = false;

    // TODO: xml
    private static final int THUMB_DISTANCE = 63;

    /** To avoid floating point calculations, we multiply radians by this value. */
    public static final int RADIAN_INT_MULTIPLIER = 100000000;
    /** PI using our multiplier. */
    public static final int PI_INT_MULTIPLIED = (int) (Math.PI * RADIAN_INT_MULTIPLIER);
    /** PI/2 using our multiplier. */
    private static final int HALF_PI_INT_MULTIPLIED = PI_INT_MULTIPLIED / 2;

    private int mZeroAngle = HALF_PI_INT_MULTIPLIED * 3;

    private static final int THUMB_GRAB_SLOP = PI_INT_MULTIPLIED / 4;

    /** The cached X of our center. */
    private int mCenterX;
    /** The cached Y of our center. */
    private int mCenterY;

    /** The angle of the thumb (in int radians) */
    private int mThumbAngle;
    private boolean mIsThumbAngleValid;
    private int mThumbHalfWidth;
    private int mThumbHalfHeight;

    /** The inner radius of the track. */
    private int mBoundInnerRadiusSquared = 0;
    /** The outer radius of the track. */
    private int mBoundOuterRadiusSquared = Integer.MAX_VALUE;

    private int mPreviousWidgetDragX;
    private int mPreviousWidgetDragY;

    private boolean mDrawThumb = true;
    private Drawable mThumbDrawable;

    private static final int MODE_IDLE = 0;
    private static final int MODE_DRAG_THUMB = 1;
    /**
     * User has his finger down, but we are waiting for him to pass the touch
     * slop before going into the #MODE_MOVE_ZOOM_RING. This is a good time to
     * show the movable hint.
     */
    private static final int MODE_WAITING_FOR_MOVE_ZOOM_RING = 4;
    private static final int MODE_MOVE_ZOOM_RING = 2;
    private static final int MODE_TAP_DRAG = 3;
    private int mMode;

    private long mPreviousDownTime;
    private int mPreviousDownX;
    private int mPreviousDownY;

    private Disabler mDisabler = new Disabler();

    private OnZoomRingCallback mCallback;
    private int mPreviousCallbackAngle;
    private int mCallbackThreshold = Integer.MAX_VALUE;

    private boolean mResetThumbAutomatically = true;
    private int mThumbDragStartAngle;
    private final int mTouchSlop;
    private Drawable mTrail;
    private double mAcculumalatedTrailAngle;

    public ZoomRing(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        mTouchSlop = viewConfiguration.getScaledTouchSlop();

        // TODO get drawables from style instead
        Resources res = context.getResources();
        mThumbDrawable = res.getDrawable(R.drawable.zoom_ring_thumb);
        if (DRAW_TRAIL) {
            mTrail = res.getDrawable(R.drawable.zoom_ring_trail).mutate();
        }

        // TODO: add padding to drawable
        setBackgroundResource(R.drawable.zoom_ring_track);
        // TODO get from style
        setBounds(30, Integer.MAX_VALUE);

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
    public void setBounds(int innerRadius, int outerRadius) {
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

    public void setThumbAngle(int angle) {
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

    public void resetThumbAngle(int angle) {
        mPreviousCallbackAngle = angle;
        setThumbAngle(angle);
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
        mIsThumbAngleValid = false;
    }

    public void setTapDragMode(boolean tapDragMode, int x, int y) {
        resetState();
        mMode = tapDragMode ? MODE_TAP_DRAG : MODE_IDLE;
        mIsThumbAngleValid = false;

        if (tapDragMode && mCallback != null) {
            onThumbDragStarted(getAngle(x - mCenterX, y - mCenterY));
        }
    }

    public boolean handleTouch(int action, long time, int x, int y, int rawX, int rawY) {
        switch (action) {

            case MotionEvent.ACTION_DOWN:
                if (mPreviousDownTime + DOUBLE_TAP_DISMISS_TIMEOUT >= time) {
                    if (mCallback != null) {
                        mCallback.onZoomRingDismissed();
                    }
                } else {
                    mPreviousDownTime = time;
                    mPreviousDownX = x;
                    mPreviousDownY = y;
                }
                resetState();
                return true;

            case MotionEvent.ACTION_MOVE:
                // Fall through to code below switch
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mCallback != null) {
                    if (mMode == MODE_MOVE_ZOOM_RING || mMode == MODE_WAITING_FOR_MOVE_ZOOM_RING) {
                        mCallback.onZoomRingSetMovableHintVisible(false);
                        if (mMode == MODE_MOVE_ZOOM_RING) {
                            mCallback.onZoomRingMovingStopped();
                        }
                    } else if (mMode == MODE_DRAG_THUMB || mMode == MODE_TAP_DRAG) {
                        onThumbDragStopped(getAngle(x - mCenterX, y - mCenterY));
                    }
                }
                mDisabler.setEnabling(true);
                return true;

            default:
                return false;
        }

        // local{X,Y} will be where the center of the widget is (0,0)
        int localX = x - mCenterX;
        int localY = y - mCenterY;
        boolean isTouchingThumb = true;
        boolean isInBounds = true;
        int touchAngle = getAngle(localX, localY);

        int radiusSquared = localX * localX + localY * localY;
        if (radiusSquared < mBoundInnerRadiusSquared ||
                radiusSquared > mBoundOuterRadiusSquared) {
            // Out-of-bounds
            isTouchingThumb = false;
            isInBounds = false;
        }

        int deltaThumbAndTouch = getDelta(touchAngle, mThumbAngle);
        int absoluteDeltaThumbAndTouch = deltaThumbAndTouch >= 0 ?
                deltaThumbAndTouch : -deltaThumbAndTouch;
        if (isTouchingThumb &&
                absoluteDeltaThumbAndTouch > THUMB_GRAB_SLOP) {
            // Didn't grab close enough to the thumb
            isTouchingThumb = false;
        }

        if (mMode == MODE_IDLE) {
            if (isTouchingThumb) {
                mMode = MODE_DRAG_THUMB;
            } else {
                mMode = MODE_WAITING_FOR_MOVE_ZOOM_RING;
            }

            if (mCallback != null) {
                if (mMode == MODE_DRAG_THUMB) {
                    onThumbDragStarted(touchAngle);
                } else if (mMode == MODE_WAITING_FOR_MOVE_ZOOM_RING) {
                    mCallback.onZoomRingSetMovableHintVisible(true);
                }
            }

        } else if (mMode == MODE_WAITING_FOR_MOVE_ZOOM_RING) {
            if (Math.abs(x - mPreviousDownX) > mTouchSlop ||
                    Math.abs(y - mPreviousDownY) > mTouchSlop) {
                /* Make sure the user has moved the slop amount before going into that mode. */
                mMode = MODE_MOVE_ZOOM_RING;

                if (mCallback != null) {
                    mCallback.onZoomRingMovingStarted();
                }
            }
        }

        // Purposefully not an "else if"
        if (mMode == MODE_DRAG_THUMB || mMode == MODE_TAP_DRAG) {
            if (isInBounds) {
                onThumbDragged(touchAngle, mIsThumbAngleValid ? deltaThumbAndTouch : 0);
            } else {
                mIsThumbAngleValid = false;
            }
        } else if (mMode == MODE_MOVE_ZOOM_RING) {
            onZoomRingMoved(rawX, rawY);
        }

        return true;
    }

    private int getDelta(int angle1, int angle2) {
        int delta = angle1 - angle2;

        // Assume this is a result of crossing over the discontinuous 0 -> 2pi
        if (delta > PI_INT_MULTIPLIED || delta < -PI_INT_MULTIPLIED) {
            // Bring both the radians and previous angle onto a continuous range
            if (angle1 < HALF_PI_INT_MULTIPLIED) {
                // Same as deltaRadians = (radians + 2PI) - previousAngle
                delta += PI_INT_MULTIPLIED * 2;
            } else if (angle2 < HALF_PI_INT_MULTIPLIED) {
                // Same as deltaRadians = radians - (previousAngle + 2PI)
                delta -= PI_INT_MULTIPLIED * 2;
            }
        }

        return delta;
    }

    private void onThumbDragStarted(int startAngle) {
        mThumbDragStartAngle = startAngle;
        mCallback.onZoomRingThumbDraggingStarted(startAngle);
    }

    private void onThumbDragged(int touchAngle, int deltaAngle) {
        mAcculumalatedTrailAngle += Math.toDegrees(deltaAngle / (double) RADIAN_INT_MULTIPLIER);
        int totalDeltaAngle = getDelta(touchAngle, mPreviousCallbackAngle);
        if (totalDeltaAngle > mCallbackThreshold
                || totalDeltaAngle < -mCallbackThreshold) {
            if (mCallback != null) {
                boolean canStillZoom = mCallback.onZoomRingThumbDragged(
                        totalDeltaAngle / mCallbackThreshold,
                        mThumbDragStartAngle, touchAngle);
                mDisabler.setEnabling(canStillZoom);

                if (canStillZoom) {
                    // TODO: we're trying the haptics to see how it goes with
                    // users, so we're ignoring the settings (for now)
                    performHapticFeedback(HapticFeedbackConstants.ZOOM_RING_TICK,
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING |
                            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                }
            }

            // Get the closest tick and lock on there
            mPreviousCallbackAngle = getClosestTickAngle(touchAngle);
        }

        setThumbAngle(touchAngle);
        mIsThumbAngleValid = true;
    }

    private int getClosestTickAngle(int angle) {
        int smallerAngleDistance = angle % mCallbackThreshold;
        int smallerAngle = angle - smallerAngleDistance;
        if (smallerAngleDistance < mCallbackThreshold / 2) {
            // Closer to the smaller angle
            return smallerAngle;
        } else {
            // Closer to the bigger angle (premodding)
            return (smallerAngle + mCallbackThreshold) % (PI_INT_MULTIPLIED * 2);
        }
    }

    private void onThumbDragStopped(int stopAngle) {
        mCallback.onZoomRingThumbDraggingStopped(stopAngle);
    }

    private void onZoomRingMoved(int x, int y) {
        if (mPreviousWidgetDragX != Integer.MIN_VALUE) {
            int deltaX = x - mPreviousWidgetDragX;
            int deltaY = y - mPreviousWidgetDragY;

            if (mCallback != null) {
                mCallback.onZoomRingMoved(deltaX, deltaY);
            }
        }

        mPreviousWidgetDragX = x;
        mPreviousWidgetDragY = y;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        if (!hasWindowFocus && mCallback != null) {
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

        if (mDrawThumb) {
            if (DRAW_TRAIL) {
                mTrail.draw(canvas);
            }
            mThumbDrawable.draw(canvas);
        }
    }

    private class Disabler implements Runnable {
        private static final int DELAY = 15;
        private static final float ENABLE_RATE = 1.05f;
        private static final float DISABLE_RATE = 0.95f;
        
        private int mAlpha = 255;
        private boolean mEnabling;
        
        public int getAlpha() {
            return mAlpha;
        }

        public void setEnabling(boolean enabling) {
            if ((enabling && mAlpha != 255) || (!enabling && mAlpha != DISABLED_ALPHA)) {
                mEnabling = enabling;
                post(this);
            }
        }
        
        public void run() {
            mAlpha *= mEnabling ? ENABLE_RATE : DISABLE_RATE;
            if (mAlpha < DISABLED_ALPHA) {
                mAlpha = DISABLED_ALPHA;
            } else if (mAlpha > 255) {
                mAlpha = 255;
            } else {
                // Still more to go
                postDelayed(this, DELAY);
            }
            
            getBackground().setAlpha(mAlpha);
            invalidate();
        }
    }
    
    public interface OnZoomRingCallback {
        void onZoomRingSetMovableHintVisible(boolean visible);
        
        void onZoomRingMovingStarted();
        boolean onZoomRingMoved(int deltaX, int deltaY);
        void onZoomRingMovingStopped();
        
        void onZoomRingThumbDraggingStarted(int startAngle);
        boolean onZoomRingThumbDragged(int numLevels, int startAngle, int curAngle);
        void onZoomRingThumbDraggingStopped(int endAngle);
        
        void onZoomRingDismissed();
    }

}
