/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.ASSIST_CONTEXT_FULL;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_NONE;

import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_RECEIVER_EXTRAS;

import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.IActivityTaskManager;
import android.app.IAssistDataReceiver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.IWindowManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to asynchronously fetch the assist data and screenshot from the current running
 * activities. It manages received data and calls back to the owner when the owner is ready to
 * receive the data itself.
 */
public class AssistDataRequester extends IAssistDataReceiver.Stub {

    public static final String KEY_RECEIVER_EXTRA_COUNT = "count";
    public static final String KEY_RECEIVER_EXTRA_INDEX = "index";

    private IWindowManager mWindowManager;
    @VisibleForTesting
    public IActivityTaskManager mActivityTaskManager;
    private Context mContext;
    private AppOpsManager mAppOpsManager;

    private AssistDataRequesterCallbacks mCallbacks;
    private Object mCallbacksLock;

    private int mRequestStructureAppOps;
    private int mRequestScreenshotAppOps;
    private boolean mCanceled;
    private int mPendingDataCount;
    private int mPendingScreenshotCount;
    private final ArrayList<Bundle> mAssistData = new ArrayList<>();
    private final ArrayList<Bitmap> mAssistScreenshot = new ArrayList<>();


    /**
     * Interface to handle the events from the fetcher.
     */
    public interface AssistDataRequesterCallbacks {
        /**
         * @return whether the currently received assist data can be handled by the callbacks.
         */
        @GuardedBy("mCallbacksLock")
        boolean canHandleReceivedAssistDataLocked();

        /**
         * Called when we receive asynchronous assist data. This call is only made if the
         * {@param fetchData} argument to requestAssistData() is true, and if the current activity
         * allows assist data to be fetched.  In addition, the callback will be made with the
         * {@param mCallbacksLock} held, and only if {@link #canHandleReceivedAssistDataLocked()}
         * is true.
         */
        @GuardedBy("mCallbacksLock")
        default void onAssistDataReceivedLocked(Bundle data, int activityIndex, int activityCount) {
            // Do nothing
        }

        /**
         * Called when we receive asynchronous assist screenshot. This call is only made if
         * {@param fetchScreenshot} argument to requestAssistData() is true, and if the current
         * activity allows assist data to be fetched.  In addition, the callback will be made with
         * the {@param mCallbacksLock} held, and only if
         * {@link #canHandleReceivedAssistDataLocked()} is true.
         */
        @GuardedBy("mCallbacksLock")
        default void onAssistScreenshotReceivedLocked(Bitmap screenshot) {
            // Do nothing
        }

        /**
         * Called when there is no more pending assist data or screenshots for the last request.
         * If the request was canceled, then this callback will not be made. In addition, the
         * callback will be made with the {@param mCallbacksLock} held, and only if
         * {@link #canHandleReceivedAssistDataLocked()} is true.
         */
        @GuardedBy("mCallbacksLock")
        default void onAssistRequestCompleted() {
            // Do nothing
        }
    }

    /**
     * @param callbacks The callbacks to handle the asynchronous reply with the assist data.
     * @param callbacksLock The lock for the requester to hold when calling any of the
     *                     {@param callbacks}. The owner should also take care in locking
     *                     appropriately when calling into this requester.
     * @param requestStructureAppOps The app ops to check before requesting the assist structure
     * @param requestScreenshotAppOps The app ops to check before requesting the assist screenshot.
     *                                This can be {@link AppOpsManager#OP_NONE} to indicate that
     *                                screenshots should never be fetched.
     */
    public AssistDataRequester(Context context,
            IWindowManager windowManager, AppOpsManager appOpsManager,
            AssistDataRequesterCallbacks callbacks, Object callbacksLock,
            int requestStructureAppOps, int requestScreenshotAppOps) {
        mCallbacks = callbacks;
        mCallbacksLock = callbacksLock;
        mWindowManager = windowManager;
        mActivityTaskManager = ActivityTaskManager.getService();
        mContext = context;
        mAppOpsManager = appOpsManager;
        mRequestStructureAppOps = requestStructureAppOps;
        mRequestScreenshotAppOps = requestScreenshotAppOps;
    }

    /**
     * Request that autofill data be loaded asynchronously. The resulting data will be provided
     * through the {@link AssistDataRequesterCallbacks}.
     *
     * See {@link #requestData(List, boolean, boolean, boolean, boolean, boolean, int, String,
     * boolean)}.
     */
    public void requestAutofillData(List<IBinder> activityTokens, int callingUid,
            String callingPackage) {
        requestData(activityTokens, true /* requestAutofillData */,
                true /* fetchData */, false /* fetchScreenshot */,
                true /* allowFetchData */, false /* allowFetchScreenshot */,
                false /* ignoreTopActivityCheck */, callingUid, callingPackage);
    }

    /**
     * Request that assist data be loaded asynchronously. The resulting data will be provided
     * through the {@link AssistDataRequesterCallbacks}.
     *
     * See {@link #requestData(List, boolean, boolean, boolean, boolean, boolean, int, String,
     * boolean)}.
     */
    public void requestAssistData(List<IBinder> activityTokens, final boolean fetchData,
            final boolean fetchScreenshot, boolean allowFetchData, boolean allowFetchScreenshot,
            int callingUid, String callingPackage) {
        requestAssistData(activityTokens, fetchData, fetchScreenshot, allowFetchData,
                allowFetchScreenshot, false /* ignoreTopActivityCheck */, callingUid,
                callingPackage);
    }

    /**
     * Request that assist data be loaded asynchronously. The resulting data will be provided
     * through the {@link AssistDataRequesterCallbacks}.
     *
     * See {@link #requestData(List, boolean, boolean, boolean, boolean, boolean, int, String,
     * boolean)}.
     */
    public void requestAssistData(List<IBinder> activityTokens, final boolean fetchData,
            final boolean fetchScreenshot, boolean allowFetchData, boolean allowFetchScreenshot,
            boolean ignoreTopActivityCheck, int callingUid, String callingPackage) {
        requestData(activityTokens, false /* requestAutofillData */, fetchData, fetchScreenshot,
                allowFetchData, allowFetchScreenshot, ignoreTopActivityCheck, callingUid,
                callingPackage);
    }

    /**
     * Request that assist data be loaded asynchronously. The resulting data will be provided
     * through the {@link AssistDataRequesterCallbacks}.
     *
     * @param activityTokens the list of visible activities
     * @param requestAutofillData if true, will fetch the autofill data, otherwise, will fetch the
     *     assist context data
     * @param fetchData whether or not to fetch the assist data, only applies if the caller is
     *     allowed to fetch the assist data, and the current activity allows assist data to be
     *     fetched from it
     * @param fetchScreenshot whether or not to fetch the screenshot, only applies if fetchData is
     *     true, the caller is allowed to fetch the assist data, and the current activity allows
     *     assist data to be fetched from it
     * @param allowFetchData to be joined with other checks, determines whether or not the requester
     *     is allowed to fetch the assist data
     * @param allowFetchScreenshot to be joined with other checks, determines whether or not the
     *     requester is allowed to fetch the assist screenshot
     * @param ignoreTopActivityCheck overrides the check for whether the activity is in focus when
     *     making the request. Used when passing an activity from Recents.
     */
    private void requestData(List<IBinder> activityTokens, final boolean requestAutofillData,
            final boolean fetchData, final boolean fetchScreenshot, boolean allowFetchData,
            boolean allowFetchScreenshot, boolean ignoreTopActivityCheck, int callingUid,
            String callingPackage) {
        // TODO(b/34090158): Known issue, if the assist data is not allowed on the current activity,
        //                   then no assist data is requested for any of the other activities

        // Early exit if there are no activity to fetch for
        if (activityTokens.isEmpty()) {
            // No activities, just dispatch request-complete
            tryDispatchRequestComplete();
            return;
        }

        // Ensure that the current activity supports assist data
        boolean isAssistDataAllowed = false;
        try {
            isAssistDataAllowed = mActivityTaskManager.isAssistDataAllowedOnCurrentActivity();
        } catch (RemoteException e) {
            // Should never happen
        }
        allowFetchData &= isAssistDataAllowed;
        allowFetchScreenshot &= fetchData && isAssistDataAllowed
                && (mRequestScreenshotAppOps != OP_NONE);

        mCanceled = false;
        mPendingDataCount = 0;
        mPendingScreenshotCount = 0;
        mAssistData.clear();
        mAssistScreenshot.clear();

        if (fetchData) {
            if (mAppOpsManager.checkOpNoThrow(mRequestStructureAppOps, callingUid, callingPackage)
                    == MODE_ALLOWED && allowFetchData) {
                final int numActivities = activityTokens.size();
                for (int i = 0; i < numActivities; i++) {
                    IBinder topActivity = activityTokens.get(i);
                    try {
                        MetricsLogger.count(mContext, "assist_with_context", 1);
                        Bundle receiverExtras = new Bundle();
                        receiverExtras.putInt(KEY_RECEIVER_EXTRA_INDEX, i);
                        receiverExtras.putInt(KEY_RECEIVER_EXTRA_COUNT, numActivities);
                        boolean result = requestAutofillData
                                ? mActivityTaskManager.requestAutofillData(this,
                                        receiverExtras, topActivity, 0 /* flags */)
                                : mActivityTaskManager.requestAssistContextExtras(
                                        ASSIST_CONTEXT_FULL, this, receiverExtras, topActivity,
                                        /* checkActivityIsTop= */ (i == 0)
                                        && !ignoreTopActivityCheck, /* newSessionId= */ i == 0);
                        if (result) {
                            mPendingDataCount++;
                        } else if (i == 0) {
                            // Wasn't allowed... given that, let's not do the screenshot either.
                            if (mCallbacks.canHandleReceivedAssistDataLocked()) {
                                dispatchAssistDataReceived(null);
                            } else {
                                mAssistData.add(null);
                            }
                            allowFetchScreenshot = false;
                            break;
                        }
                    } catch (RemoteException e) {
                        // Can't happen
                    }
                }
            } else {
                // Wasn't allowed... given that, let's not do the screenshot either.
                if (mCallbacks.canHandleReceivedAssistDataLocked()) {
                    dispatchAssistDataReceived(null);
                } else {
                    mAssistData.add(null);
                }
                allowFetchScreenshot = false;
            }
        }

        if (fetchScreenshot) {
            if (mAppOpsManager.checkOpNoThrow(mRequestScreenshotAppOps, callingUid, callingPackage)
                    == MODE_ALLOWED && allowFetchScreenshot) {
                try {
                    MetricsLogger.count(mContext, "assist_with_screen", 1);
                    mPendingScreenshotCount++;
                    mWindowManager.requestAssistScreenshot(this);
                } catch (RemoteException e) {
                    // Can't happen
                }
            } else {
                if (mCallbacks.canHandleReceivedAssistDataLocked()) {
                    dispatchAssistScreenshotReceived(null);
                } else {
                    mAssistScreenshot.add(null);
                }
            }
        }
        // For the cases where we dispatch null data/screenshot due to permissions, just dispatch
        // request-complete after those are made
        tryDispatchRequestComplete();
    }

    /**
     * This call should only be made when the callbacks are capable of handling the received assist
     * data. The owner is also responsible for locking before calling this method.
     */
    public void processPendingAssistData() {
        flushPendingAssistData();
        tryDispatchRequestComplete();
    }

    private void flushPendingAssistData() {
        final int dataCount = mAssistData.size();
        for (int i = 0; i < dataCount; i++) {
            dispatchAssistDataReceived(mAssistData.get(i));
        }
        mAssistData.clear();
        final int screenshotsCount = mAssistScreenshot.size();
        for (int i = 0; i < screenshotsCount; i++) {
            dispatchAssistScreenshotReceived(mAssistScreenshot.get(i));
        }
        mAssistScreenshot.clear();
    }

    public int getPendingDataCount() {
        return mPendingDataCount;
    }

    public int getPendingScreenshotCount() {
        return mPendingScreenshotCount;
    }

    /**
     * Cancels the current request for the assist data.
     */
    public void cancel() {
        // Reset the pending data count, if we receive new assist data after this point, it will
        // be ignored
        mCanceled = true;
        mPendingDataCount = 0;
        mPendingScreenshotCount = 0;
        mAssistData.clear();
        mAssistScreenshot.clear();
    }

    @Override
    public void onHandleAssistData(Bundle data) {
        synchronized (mCallbacksLock) {
            if (mCanceled) {
                return;
            }
            mPendingDataCount--;

            if (mCallbacks.canHandleReceivedAssistDataLocked()) {
                // Process any pending data and dispatch the new data as well
                flushPendingAssistData();
                dispatchAssistDataReceived(data);
                tryDispatchRequestComplete();
            } else {
                // Queue up the data for processing later
                mAssistData.add(data);
            }
        }
    }

    @Override
    public void onHandleAssistScreenshot(Bitmap screenshot) {
        synchronized (mCallbacksLock) {
            if (mCanceled) {
                return;
            }
            mPendingScreenshotCount--;

            if (mCallbacks.canHandleReceivedAssistDataLocked()) {
                // Process any pending data and dispatch the new data as well
                flushPendingAssistData();
                dispatchAssistScreenshotReceived(screenshot);
                tryDispatchRequestComplete();
            } else {
                // Queue up the data for processing later
                mAssistScreenshot.add(screenshot);
            }
        }
    }

    private void dispatchAssistDataReceived(Bundle data) {
        int activityIndex = 0;
        int activityCount = 0;
        final Bundle receiverExtras = data != null
                ? data.getBundle(ASSIST_KEY_RECEIVER_EXTRAS) : null;
        if (receiverExtras != null) {
            activityIndex = receiverExtras.getInt(KEY_RECEIVER_EXTRA_INDEX);
            activityCount = receiverExtras.getInt(KEY_RECEIVER_EXTRA_COUNT);
        }
        mCallbacks.onAssistDataReceivedLocked(data, activityIndex, activityCount);
    }

    private void dispatchAssistScreenshotReceived(Bitmap screenshot) {
        mCallbacks.onAssistScreenshotReceivedLocked(screenshot);
    }

    private void tryDispatchRequestComplete() {
        if (mPendingDataCount == 0 && mPendingScreenshotCount == 0 &&
                mAssistData.isEmpty() && mAssistScreenshot.isEmpty()) {
            mCallbacks.onAssistRequestCompleted();
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mPendingDataCount="); pw.println(mPendingDataCount);
        pw.print(prefix); pw.print("mAssistData="); pw.println(mAssistData);
        pw.print(prefix); pw.print("mPendingScreenshotCount="); pw.println(mPendingScreenshotCount);
        pw.print(prefix); pw.print("mAssistScreenshot="); pw.println(mAssistScreenshot);
    }
}