/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.internal.app;

import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Used to sort resolved activities in {@link ResolverListController}.
 *
 * @hide
 */
public abstract class AbstractResolverComparator implements Comparator<ResolvedComponentInfo> {

    private static final int NUM_OF_TOP_ANNOTATIONS_TO_USE = 3;
    private static final boolean DEBUG = true;
    private static final String TAG = "AbstractResolverComp";

    protected AfterCompute mAfterCompute;
    protected final PackageManager mPm;
    protected final UsageStatsManager mUsm;
    protected String[] mAnnotations;
    protected String mContentType;

    // True if the current share is a link.
    private final boolean mHttp;

    // message types
    static final int RANKER_SERVICE_RESULT = 0;
    static final int RANKER_RESULT_TIMEOUT = 1;

    // timeout for establishing connections with a ResolverRankerService, collecting features and
    // predicting ranking scores.
    private static final int WATCHDOG_TIMEOUT_MILLIS = 500;

    private final Comparator<ResolveInfo> mAzComparator;

    protected final Handler mHandler = new Handler(Looper.getMainLooper()) {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case RANKER_SERVICE_RESULT:
                    if (DEBUG) {
                        Log.d(TAG, "RANKER_SERVICE_RESULT");
                    }
                    if (mHandler.hasMessages(RANKER_RESULT_TIMEOUT)) {
                        handleResultMessage(msg);
                        mHandler.removeMessages(RANKER_RESULT_TIMEOUT);
                        afterCompute();
                    }
                    break;

                case RANKER_RESULT_TIMEOUT:
                    if (DEBUG) {
                        Log.d(TAG, "RANKER_RESULT_TIMEOUT; unbinding services");
                    }
                    mHandler.removeMessages(RANKER_SERVICE_RESULT);
                    afterCompute();
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    };

    public AbstractResolverComparator(Context context, Intent intent) {
        String scheme = intent.getScheme();
        mHttp = "http".equals(scheme) || "https".equals(scheme);
        mContentType = intent.getType();
        getContentAnnotations(intent);
        mPm = context.getPackageManager();
        mUsm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        mAzComparator = new AzInfoComparator(context);
    }

    // get annotations of content from intent.
    private void getContentAnnotations(Intent intent) {
        ArrayList<String> annotations = intent.getStringArrayListExtra(
                Intent.EXTRA_CONTENT_ANNOTATIONS);
        if (annotations != null) {
            int size = annotations.size();
            if (size > NUM_OF_TOP_ANNOTATIONS_TO_USE) {
                size = NUM_OF_TOP_ANNOTATIONS_TO_USE;
            }
            mAnnotations = new String[size];
            for (int i = 0; i < size; i++) {
                mAnnotations[i] = annotations.get(i);
            }
        }
    }

    /**
     * Callback to be called when {@link #compute(List)} finishes. This signals to stop waiting.
     */
    interface AfterCompute {

        void afterCompute();
    }

    void setCallBack(AfterCompute afterCompute) {
        mAfterCompute = afterCompute;
    }

    protected final void afterCompute() {
        final AfterCompute afterCompute = mAfterCompute;
        if (afterCompute != null) {
            afterCompute.afterCompute();
        }
    }

    @Override
    public final int compare(ResolvedComponentInfo lhsp, ResolvedComponentInfo rhsp) {
        final ResolveInfo lhs = lhsp.getResolveInfoAt(0);
        final ResolveInfo rhs = rhsp.getResolveInfoAt(0);

        // We want to put the one targeted to another user at the end of the dialog.
        if (lhs.targetUserId != UserHandle.USER_CURRENT) {
            return rhs.targetUserId != UserHandle.USER_CURRENT ? 0 : 1;
        }
        if (rhs.targetUserId != UserHandle.USER_CURRENT) {
            return -1;
        }

        if (mHttp) {
            final boolean lhsSpecific = ResolverActivity.isSpecificUriMatch(lhs.match);
            final boolean rhsSpecific = ResolverActivity.isSpecificUriMatch(rhs.match);
            if (lhsSpecific != rhsSpecific) {
                return lhsSpecific ? -1 : 1;
            }
        }

        final boolean lPinned = lhsp.isPinned();
        final boolean rPinned = rhsp.isPinned();

        // Pinned items always receive priority.
        if (lPinned && !rPinned) {
            return -1;
        } else if (!lPinned && rPinned) {
            return 1;
        } else if (lPinned && rPinned) {
            // If both items are pinned, resolve the tie alphabetically.
            return mAzComparator.compare(lhsp.getResolveInfoAt(0), rhsp.getResolveInfoAt(0));
        }

        return compare(lhs, rhs);
    }

    /**
     * Delegated to when used as a {@link Comparator<ResolvedComponentInfo>} if there is not a
     * special case. The {@link ResolveInfo ResolveInfos} are the first {@link ResolveInfo} in
     * {@link ResolvedComponentInfo#getResolveInfoAt(int)} from the parameters of {@link
     * #compare(ResolvedComponentInfo, ResolvedComponentInfo)}
     */
    abstract int compare(ResolveInfo lhs, ResolveInfo rhs);

    /**
     * Computes features for each target. This will be called before calls to {@link
     * #getScore(ComponentName)} or {@link #compare(Object, Object)}, in order to prepare the
     * comparator for those calls. Note that {@link #getScore(ComponentName)} uses {@link
     * ComponentName}, so the implementation will have to be prepared to identify a {@link
     * ResolvedComponentInfo} by {@link ComponentName}. {@link #beforeCompute()} will be called
     * before doing any computing.
     */
    final void compute(List<ResolvedComponentInfo> targets) {
        beforeCompute();
        doCompute(targets);
    }

    /** Implementation of compute called after {@link #beforeCompute()}. */
    abstract void doCompute(List<ResolvedComponentInfo> targets);

    /**
     * Returns the score that was calculated for the corresponding {@link ResolvedComponentInfo}
     * when {@link #compute(List)} was called before this.
     */
    abstract float getScore(ComponentName name);

    /**
     * Returns the list of top K component names which have highest
     * {@link #getScore(ComponentName)}
     */
    abstract List<ComponentName> getTopComponentNames(int topK);

    /** Handles result message sent to mHandler. */
    abstract void handleResultMessage(Message message);

    /**
     * Reports to UsageStats what was chosen.
     */
    final void updateChooserCounts(String packageName, int userId, String action) {
        if (mUsm != null) {
            mUsm.reportChooserSelection(packageName, userId, mContentType, mAnnotations, action);
        }
    }

    /**
     * Updates the model used to rank the componentNames.
     *
     * <p>Default implementation does nothing, as we could have simple model that does not train
     * online.
     *
     * @param componentName the component that the user clicked
     */
    void updateModel(ComponentName componentName) {
    }

    /** Called before {@link #doCompute(List)}. Sets up 500ms timeout. */
    void beforeCompute() {
        if (DEBUG) Log.d(TAG, "Setting watchdog timer for " + WATCHDOG_TIMEOUT_MILLIS + "ms");
        if (mHandler == null) {
            Log.d(TAG, "Error: Handler is Null; Needs to be initialized.");
            return;
        }
        mHandler.sendEmptyMessageDelayed(RANKER_RESULT_TIMEOUT, WATCHDOG_TIMEOUT_MILLIS);
    }

    /**
     * Called when the {@link ResolverActivity} is destroyed. This calls {@link #afterCompute()}. If
     * this call needs to happen at a different time during destroy, the method should be
     * overridden.
     */
    void destroy() {
        mHandler.removeMessages(RANKER_SERVICE_RESULT);
        mHandler.removeMessages(RANKER_RESULT_TIMEOUT);
        afterCompute();
        mAfterCompute = null;
    }

    /**
     * Sort intents alphabetically based on package name.
     */
    class AzInfoComparator implements Comparator<ResolveInfo> {
        Collator mCollator;
        AzInfoComparator(Context context) {
            mCollator = Collator.getInstance(context.getResources().getConfiguration().locale);
        }

        @Override
        public int compare(ResolveInfo lhsp, ResolveInfo rhsp) {
            if (lhsp == null) {
                return -1;
            } else if (rhsp == null) {
                return 1;
            }
            return mCollator.compare(lhsp.activityInfo.packageName, rhsp.activityInfo.packageName);
        }
    }

}
