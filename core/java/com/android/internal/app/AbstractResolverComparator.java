package com.android.internal.app;

import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.util.Log;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Used to sort resolved activities in {@link ResolverListController}.
 */
abstract class AbstractResolverComparator implements Comparator<ResolvedComponentInfo> {

    private static final int NUM_OF_TOP_ANNOTATIONS_TO_USE = 3;

    protected AfterCompute mAfterCompute;
    protected final PackageManager mPm;
    protected final UsageStatsManager mUsm;
    protected String[] mAnnotations;
    protected String mContentType;

    // True if the current share is a link.
    private final boolean mHttp;
    // can be null if mHttp == false or current user has no default browser package
    private final String mDefaultBrowserPackageName;

    AbstractResolverComparator(Context context, Intent intent) {
        String scheme = intent.getScheme();
        mHttp = "http".equals(scheme) || "https".equals(scheme);
        mContentType = intent.getType();
        getContentAnnotations(intent);

        mPm = context.getPackageManager();
        mUsm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        mDefaultBrowserPackageName = mHttp
                ? mPm.getDefaultBrowserPackageNameAsUser(UserHandle.myUserId())
                : null;
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
            // Special case: we want filters that match URI paths/schemes to be
            // ordered before others.  This is for the case when opening URIs,
            // to make native apps go above browsers - except for 1 even more special case
            // which is the default browser, as we want that to go above them all.
            if (isDefaultBrowser(lhs)) {
                return -1;
            }

            if (isDefaultBrowser(rhs)) {
                return 1;
            }
            final boolean lhsSpecific = ResolverActivity.isSpecificUriMatch(lhs.match);
            final boolean rhsSpecific = ResolverActivity.isSpecificUriMatch(rhs.match);
            if (lhsSpecific != rhsSpecific) {
                return lhsSpecific ? -1 : 1;
            }
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
     * ResolvedComponentInfo} by {@link ComponentName}.
     */
    abstract void compute(List<ResolvedComponentInfo> targets);

    /**
     * Returns the score that was calculated for the corresponding {@link ResolvedComponentInfo}
     * when {@link #compute(List)} was called before this.
     */
    abstract float getScore(ComponentName name);

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

    /**
     * Called when the {@link ResolverActivity} is destroyed.
     */
    abstract void destroy();

    private boolean isDefaultBrowser(ResolveInfo ri) {
        // It makes sense to prefer the default browser
        // only if the targeted user is the current user
        if (ri.targetUserId != UserHandle.USER_CURRENT) {
            return false;
        }

        if (ri.activityInfo.packageName != null
                    && ri.activityInfo.packageName.equals(mDefaultBrowserPackageName)) {
            return true;
        }
        return false;
    }
}
