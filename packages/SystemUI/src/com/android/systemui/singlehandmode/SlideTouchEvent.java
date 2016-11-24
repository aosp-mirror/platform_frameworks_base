package com.android.systemui.singlehandmode;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources.NotFoundException;
import android.graphics.Rect;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import com.android.systemui.R;

public class SlideTouchEvent {
    private static final String TAG = "SlideTouchEvent";
    private static final String LEFT = "left";
    private static final String RIGHT = "right";

    /**
     * The units you would like the velocity in. A value of 1 provides pixels per millisecond, 1000 provides pixels per second, etc.
     */
    private static final int UNITS = 1000;

    public static final float SCALE = (float) 3 / 4;

    public static final String KEY_SINGLE_HAND_SCREEN_ZOOM = "single_hand_screen_zoom";
    private boolean mIsSupport = true;
    private boolean mScreenZoomEnabled = true;
    private boolean mZoomGestureEnabled = false;

    private float[] mDownPoint = new float[2];
    private float mTriggerSingleHandMode;
    private float mVerticalProhibit;

    private int mMinimumFlingVelocity;
    private int mMaximumFlingVelocity;

    private VelocityTracker mVelocityTracker;
    private Handler mHandler = new Handler();
    private Context mContext;

    private boolean mFlag = false;

    public SlideTouchEvent(Context context) {
        mContext = context;
        init();
    }

    private void init() {
        if (null == mContext) {
            Log.e(TAG, "SlideTouchEvent init return...");
            return;
        }
        mIsSupport = isSupportSingleHand();
        if (!mIsSupport) return;

        final ViewConfiguration configuration = ViewConfiguration.get(mContext);
        mMinimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();

        mTriggerSingleHandMode = mContext.getResources().getDimension(R.dimen.navbar_single_hand_mode_horizontal_threshhold);
        mVerticalProhibit = mContext.getResources().getDimension(R.dimen.navbar_single_hand_mode_vertical_threshhold);
    }

    /**
     * handle MotionEvent, maybe trigger single hand mode
     * @param event MotionEvent
     */
    public void handleTouchEvent(MotionEvent event) {
        //Log.i(TAG, "handleTouchEvent:" + event);
        if (event == null) {
            return;
        }
        if (!mIsSupport) {
            return;
        }

        if (!mScreenZoomEnabled) {
            return;
        }

        if (mZoomGestureEnabled) {
            return;
        }

        if (null == mVelocityTracker) {
            mVelocityTracker = VelocityTracker.obtain();
        }

        mVelocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mFlag = true;
                mDownPoint[0] = event.getX();
                mDownPoint[1] = event.getY();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getActionIndex() == 0) {
                    mFlag = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (!mFlag) {
                    break;
                }
                mFlag = false;
                if (true) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(UNITS, mMaximumFlingVelocity);

                    final int pointerId = event.getPointerId(0);
                    final float velocityX = velocityTracker.getXVelocity(pointerId);

                    Log.i(TAG, "vel=" + Math.abs(velocityX) + ", MinimumFlingVelocity=" + mMinimumFlingVelocity);
                    if (Math.abs(velocityX) > mMinimumFlingVelocity) {

                        final int historySize = event.getHistorySize();

                        for (int i = 0; i < historySize + 1; i++) {

                            float x = i < historySize ? event.getHistoricalX(i) : event.getX();
                            float y = i < historySize ? event.getHistoricalY(i) : event.getY();
                            float distanceX = mDownPoint[0] - x;
                            float distanceY = mDownPoint[1] - y;
                            if (Math.abs(distanceY) > Math.abs(distanceX) || Math.abs(distanceY) > mVerticalProhibit) {
                                Log.i(TAG, "Sliding distanceY > distancex, " + distanceY + ", " + distanceX);
                                return;
                            }
                            if (Math.abs(distanceX) > mTriggerSingleHandMode) {
                                if (Configuration.ORIENTATION_PORTRAIT == mContext.getResources().getConfiguration().orientation) {
                                    startSingleHandMode(distanceX);
                                }
                            } else {
                                Log.i(TAG, "Sliding distance is too short, can not trigger the single hand mode");
                            }
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * start single hand mode.
     * @param distanceX Sliding X distance
     */
    private void startSingleHandMode(float distanceX) {
        String str = Settings.Global.getString(mContext.getContentResolver(), Settings.Global.SINGLE_HAND_MODE);
        Log.i("SingleHand", "start single hand mode str: " + str + " distanceX: " + distanceX);
        if (distanceX > 0 && TextUtils.isEmpty(str)) {
            Settings.Global.putString(mContext.getContentResolver(), Settings.Global.SINGLE_HAND_MODE, LEFT);
        }

        if (distanceX < 0 && TextUtils.isEmpty(str)) {
            Settings.Global.putString(mContext.getContentResolver(), Settings.Global.SINGLE_HAND_MODE, RIGHT);
        }

        if (distanceX < 0 && str != null && str.contains(LEFT)) {
            quitLSingleHandMode();
        }

        if (distanceX > 0 && str != null && str.contains(RIGHT)) {
            quitLSingleHandMode();
        }
    }

    public static final int STATE_MIDDLE = 0;
    public static final int STATE_LEFT = 1;
    public static final int STATE_RIGHT = 2;

    public static int getSingleHandState(Context context) {
        String str = Settings.Global.getString(context.getContentResolver(), Settings.Global.SINGLE_HAND_MODE);
        if (TextUtils.isEmpty(str)) {
            return STATE_MIDDLE;
        } else if (str.contains(LEFT)) {
            return STATE_LEFT;
        } else if (str.contains(RIGHT)) {
            return STATE_RIGHT;
        }
        return STATE_MIDDLE;
    }

    public static boolean isSingleHandMode(Context context) {
        return getSingleHandState(context) == STATE_MIDDLE ? false : true;
    }

    private void quitLSingleHandMode() {
        Log.i(TAG, "quitLSingleHandMode");
        Settings.Global.putString(mContext.getContentResolver(), Settings.Global.SINGLE_HAND_MODE, "");
    }

    private boolean isSupportSingleHand() {
        return true;
    }
}
