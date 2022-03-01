/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.os.IBinder;
import android.os.InputConstants;
import android.os.Looper;
import android.os.Process;
import android.util.Slog;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Creates a InputWindowHandle that catches all touches that would otherwise pass through an
 * Activity.
 */
class ActivityRecordInputSink {

    /**
     * Feature flag for making Activities consume all touches within their task bounds.
     */
    @ChangeId
    static final long ENABLE_TOUCH_OPAQUE_ACTIVITIES = 194480991L;

    private static final String TAG = "ActivityRecordInputSink";
    private static final int NUMBER_OF_TOUCHES_TO_DISABLE = 3;
    private static final long TOAST_COOL_DOWN_MILLIS = 3000L;

    private final ActivityRecord mActivityRecord;
    private final boolean mIsCompatEnabled;
    private final String mName;

    // Hold on to InputEventReceiver to prevent it from getting GCd.
    private InputEventReceiver mInputEventReceiver;
    private InputWindowHandleWrapper mInputWindowHandleWrapper;
    private SurfaceControl mSurfaceControl;
    private int mRapidTouchCount = 0;
    private IBinder mToken;
    private boolean mDisabled = false;

    ActivityRecordInputSink(ActivityRecord activityRecord) {
        mActivityRecord = activityRecord;
        mIsCompatEnabled = CompatChanges.isChangeEnabled(ENABLE_TOUCH_OPAQUE_ACTIVITIES,
                mActivityRecord.getUid());
        mName = Integer.toHexString(System.identityHashCode(this)) + " ActivityRecordInputSink "
                + mActivityRecord.mActivityComponent.getShortClassName();
    }

    public void applyChangesToSurfaceIfChanged(SurfaceControl.Transaction transaction) {
        InputWindowHandleWrapper inputWindowHandleWrapper = getInputWindowHandleWrapper();
        if (mSurfaceControl == null) {
            mSurfaceControl = createSurface(transaction);
        }
        if (inputWindowHandleWrapper.isChanged()) {
            inputWindowHandleWrapper.applyChangesToSurface(transaction, mSurfaceControl);
        }
    }

    private SurfaceControl createSurface(SurfaceControl.Transaction t) {
        SurfaceControl surfaceControl = mActivityRecord.makeChildSurface(null)
                .setName(mName)
                .setHidden(false)
                .setCallsite("ActivityRecordInputSink.createSurface")
                .build();
        // Put layer below all siblings (and the parent surface too)
        t.setLayer(surfaceControl, Integer.MIN_VALUE);
        return surfaceControl;
    }

    private InputWindowHandleWrapper getInputWindowHandleWrapper() {
        if (mInputWindowHandleWrapper == null) {
            mInputWindowHandleWrapper = new InputWindowHandleWrapper(createInputWindowHandle());
            InputChannel inputChannel =
                    mActivityRecord.mWmService.mInputManager.createInputChannel(mName);
            mToken = inputChannel.getToken();
            mInputEventReceiver = createInputEventReceiver(inputChannel);
        }
        if (mDisabled || !mIsCompatEnabled || mActivityRecord.isInTransition()) {
            // TODO(b/208662670): Investigate if we can have feature active during animations.
            mInputWindowHandleWrapper.setToken(null);
        } else {
            mInputWindowHandleWrapper.setToken(mToken);
        }
        return mInputWindowHandleWrapper;
    }

    private InputWindowHandle createInputWindowHandle() {
        InputWindowHandle inputWindowHandle = new InputWindowHandle(null,
                mActivityRecord.getDisplayId());
        inputWindowHandle.replaceTouchableRegionWithCrop = true;
        inputWindowHandle.name = mName;
        inputWindowHandle.ownerUid = Process.myUid();
        inputWindowHandle.ownerPid = Process.myPid();
        inputWindowHandle.layoutParamsFlags =
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
        inputWindowHandle.dispatchingTimeoutMillis =
                InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        return inputWindowHandle;
    }

    private InputEventReceiver createInputEventReceiver(InputChannel inputChannel) {
        return new SinkInputEventReceiver(inputChannel,
                mActivityRecord.mAtmService.mUiHandler.getLooper());
    }

    private void showAsToastAndLog(String message) {
        Toast.makeText(mActivityRecord.mAtmService.mUiContext, message,
                Toast.LENGTH_LONG).show();
        Slog.wtf(TAG, message + " " + mActivityRecord.mActivityComponent);
    }

    private class SinkInputEventReceiver extends InputEventReceiver {
        private long mLastToast = 0;

        SinkInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        public void onInputEvent(InputEvent event) {
            if (!(event instanceof MotionEvent)) {
                Slog.wtf(TAG,
                        "Received InputEvent that was not a MotionEvent");
                finishInputEvent(event, true);
                return;
            }
            MotionEvent motionEvent = (MotionEvent) event;
            if (motionEvent.getAction() != MotionEvent.ACTION_DOWN) {
                finishInputEvent(event, true);
                return;
            }

            if (event.getEventTime() - mLastToast > TOAST_COOL_DOWN_MILLIS) {
                String message = "go/activity-touch-opaque - "
                        + mActivityRecord.mActivityComponent.getPackageName()
                        + " blocked the touch!";
                showAsToastAndLog(message);
                mLastToast = event.getEventTime();
                mRapidTouchCount = 1;
            } else if (++mRapidTouchCount >= NUMBER_OF_TOUCHES_TO_DISABLE && !mDisabled) {
                // Disable touch blocking until Activity Record is recreated.
                String message = "Disabled go/activity-touch-opaque - "
                        + mActivityRecord.mActivityComponent.getPackageName();
                showAsToastAndLog(message);
                mDisabled = true;
            }
            finishInputEvent(event, true);
        }
    }

}
