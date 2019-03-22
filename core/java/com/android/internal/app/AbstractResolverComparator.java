package com.android.internal.app;

import android.content.ComponentName;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import java.util.Comparator;
import java.util.List;

/**
 * Used to sort resolved activities in {@link ResolverListController}.
 */
abstract class AbstractResolverComparator implements Comparator<ResolvedComponentInfo> {

    protected AfterCompute mAfterCompute;

    /**
     * Callback to be called when {@link #compute(List)} finishes. This signals to stop waiting.
     */
    public interface AfterCompute {

        public void afterCompute();
    }

    public void setCallBack(AfterCompute afterCompute) {
        mAfterCompute = afterCompute;
    }

    /**
     * Computes features for each target. This will be called before calls to {@link
     * #getScore(ComponentName)} or {@link #compare(Object, Object)}, in order to prepare the
     * comparator for those calls. Note that {@link #getScore(ComponentName)} uses {@link
     * ComponentName}, so the implementation will have to be prepared to identify a {@link
     * ResolvedComponentInfo} by {@link ComponentName}.
     */
    public abstract void compute(List<ResolvedComponentInfo> targets);

    /**
     * Returns the score that was calculated for the corresponding {@link ResolvedComponentInfo}
     * when {@link #compute(List)} was called before this.
     */
    public abstract float getScore(ComponentName name);

    /**
     * Reports to UsageStats what was chosen.
     */
    // TODO(b/129014961) Move implemetation here and make final.
    public abstract void updateChooserCounts(String packageName, int userId, String action);

    /**
     * Updates the model used to rank the componentNames.
     *
     * <p>Default implementation does nothing, as we could have simple model that does not train
     * online.
     *
     * @param componentName the component that the user clicked
     */
    public void updateModel(ComponentName componentName) {
    }

    /**
     * Called when the {@link ResolverActivity} is destroyed.
     */
    public abstract void destroy();
}
