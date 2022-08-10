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

package androidx.window.extensions.layout;

import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.window.common.CommonFoldingFeature.COMMON_STATE_FLAT;
import static androidx.window.common.CommonFoldingFeature.COMMON_STATE_HALF_OPENED;
import static androidx.window.util.ExtensionHelper.rotateRectToDisplayRotation;
import static androidx.window.util.ExtensionHelper.transformToWindowSpaceRect;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.AppTask;
import android.app.Application;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.window.common.CommonFoldingFeature;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.EmptyLifecycleCallbacksAdapter;
import androidx.window.common.RawFoldingFeatureProducer;
import androidx.window.util.DataProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reference implementation of androidx.window.extensions.layout OEM interface for use with
 * WindowManager Jetpack.
 *
 * NOTE: This version is a work in progress and under active development. It MUST NOT be used in
 * production builds since the interface can still change before reaching stable version.
 * Please refer to {@link androidx.window.sidecar.SampleSidecarImpl} instead.
 */
public class WindowLayoutComponentImpl implements WindowLayoutComponent {
    private static final String TAG = "SampleExtension";

    private final Map<Activity, Consumer<WindowLayoutInfo>> mWindowLayoutChangeListeners =
            new ArrayMap<>();

    private final DataProducer<List<CommonFoldingFeature>> mFoldingFeatureProducer;

    public WindowLayoutComponentImpl(Context context) {
        ((Application) context.getApplicationContext())
                .registerActivityLifecycleCallbacks(new NotifyOnConfigurationChanged());
        RawFoldingFeatureProducer foldingFeatureProducer = new RawFoldingFeatureProducer(context);
        mFoldingFeatureProducer = new DeviceStateManagerFoldingFeatureProducer(context,
                foldingFeatureProducer);
        mFoldingFeatureProducer.addDataChangedCallback(this::onDisplayFeaturesChanged);
    }

    /**
     * Adds a listener interested in receiving updates to {@link WindowLayoutInfo}
     *
     * @param activity hosting a {@link android.view.Window}
     * @param consumer interested in receiving updates to {@link WindowLayoutInfo}
     */
    public void addWindowLayoutInfoListener(@NonNull Activity activity,
            @NonNull Consumer<WindowLayoutInfo> consumer) {
        mWindowLayoutChangeListeners.put(activity, consumer);
        onDisplayFeaturesChanged();
    }

    /**
     * Removes a listener no longer interested in receiving updates.
     *
     * @param consumer no longer interested in receiving updates to {@link WindowLayoutInfo}
     */
    public void removeWindowLayoutInfoListener(
            @NonNull Consumer<WindowLayoutInfo> consumer) {
        mWindowLayoutChangeListeners.values().remove(consumer);
        onDisplayFeaturesChanged();
    }

    void updateWindowLayout(@NonNull Activity activity,
            @NonNull WindowLayoutInfo newLayout) {
        Consumer<WindowLayoutInfo> consumer = mWindowLayoutChangeListeners.get(activity);
        if (consumer != null) {
            consumer.accept(newLayout);
        }
    }

    @NonNull
    Set<Activity> getActivitiesListeningForLayoutChanges() {
        return mWindowLayoutChangeListeners.keySet();
    }

    @NonNull
    private boolean isListeningForLayoutChanges(IBinder token) {
        for (Activity activity: getActivitiesListeningForLayoutChanges()) {
            if (token.equals(activity.getWindow().getAttributes().token)) {
                return true;
            }
        }
        return false;
    }

    protected boolean hasListeners() {
        return !mWindowLayoutChangeListeners.isEmpty();
    }

    /**
     * A convenience method to translate from the common feature state to the extensions feature
     * state.  More specifically, translates from {@link CommonFoldingFeature.State} to
     * {@link FoldingFeature.STATE_FLAT} or {@link FoldingFeature.STATE_HALF_OPENED}. If it is not
     * possible to translate, then we will return a {@code null} value.
     *
     * @param state if it matches a value in {@link CommonFoldingFeature.State}, {@code null}
     *              otherwise. @return a {@link FoldingFeature.STATE_FLAT} or
     *              {@link FoldingFeature.STATE_HALF_OPENED} if the given state matches a value in
     *              {@link CommonFoldingFeature.State} and {@code null} otherwise.
     */
    @Nullable
    private Integer convertToExtensionState(int state) {
        if (state == COMMON_STATE_FLAT) {
            return FoldingFeature.STATE_FLAT;
        } else if (state == COMMON_STATE_HALF_OPENED) {
            return FoldingFeature.STATE_HALF_OPENED;
        } else {
            return null;
        }
    }

    private void onDisplayFeaturesChanged() {
        for (Activity activity : getActivitiesListeningForLayoutChanges()) {
            WindowLayoutInfo newLayout = getWindowLayoutInfo(activity);
            updateWindowLayout(activity, newLayout);
        }
    }

    @NonNull
    private WindowLayoutInfo getWindowLayoutInfo(@NonNull Activity activity) {
        List<DisplayFeature> displayFeatures = getDisplayFeatures(activity);
        return new WindowLayoutInfo(displayFeatures);
    }

    /**
     * Translate from the {@link CommonFoldingFeature} to
     * {@link DisplayFeature} for a given {@link Activity}. If a
     * {@link CommonFoldingFeature} is not valid then it will be omitted.
     *
     * For a {@link FoldingFeature} the bounds are localized into the {@link Activity} window
     * coordinate space and the state is calculated from {@link CommonFoldingFeature#getState()}.
     * The state from {@link #mFoldingFeatureProducer} may not be valid since
     * {@link #mFoldingFeatureProducer} is a general state controller. If the state is not valid,
     * the {@link FoldingFeature} is omitted from the {@link List} of {@link DisplayFeature}. If the
     * bounds are not valid, constructing a {@link FoldingFeature} will throw an
     * {@link IllegalArgumentException} since this can cause negative UI effects down stream.
     *
     * @param activity a proxy for the {@link android.view.Window} that contains the
     * {@link DisplayFeature}.
     * @return a {@link List} of valid {@link DisplayFeature} that
     * are within the {@link android.view.Window} of the {@link Activity}
     */
    private List<DisplayFeature> getDisplayFeatures(@NonNull Activity activity) {
        List<DisplayFeature> features = new ArrayList<>();
        int displayId = activity.getDisplay().getDisplayId();
        if (displayId != DEFAULT_DISPLAY) {
            Log.w(TAG, "This sample doesn't support display features on secondary displays");
            return features;
        }

        if (isTaskInMultiWindowMode(activity)) {
            // It is recommended not to report any display features in multi-window mode, since it
            // won't be possible to synchronize the display feature positions with window movement.
            return features;
        }

        Optional<List<CommonFoldingFeature>> storedFeatures = mFoldingFeatureProducer.getData();
        if (storedFeatures.isPresent()) {
            for (CommonFoldingFeature baseFeature : storedFeatures.get()) {
                Integer state = convertToExtensionState(baseFeature.getState());
                if (state == null) {
                    continue;
                }
                Rect featureRect = baseFeature.getRect();
                rotateRectToDisplayRotation(displayId, featureRect);
                transformToWindowSpaceRect(activity, featureRect);

                if (!isRectZero(featureRect)) {
                    // TODO(b/228641877) Remove guarding if when fixed.
                    features.add(new FoldingFeature(featureRect, baseFeature.getType(), state));
                }
            }
        }
        return features;
    }

    /**
     * Checks whether the task associated with the activity is in multi-window. If task info is not
     * available it defaults to {@code true}.
     */
    private boolean isTaskInMultiWindowMode(@NonNull Activity activity) {
        final ActivityManager am = activity.getSystemService(ActivityManager.class);
        if (am == null) {
            return true;
        }

        final List<AppTask> appTasks = am.getAppTasks();
        final int taskId = activity.getTaskId();
        AppTask task = null;
        for (AppTask t : appTasks) {
            if (t.getTaskInfo().taskId == taskId) {
                task = t;
                break;
            }
        }
        if (task == null) {
            // The task might be removed on the server already.
            return true;
        }
        return WindowConfiguration.inMultiWindowMode(task.getTaskInfo().getWindowingMode());
    }

    /**
     * Returns {@link true} if a {@link Rect} has zero width and zero height,
     * {@code false} otherwise.
     */
    private boolean isRectZero(Rect rect) {
        return rect.width() == 0 && rect.height() == 0;
    }

    private final class NotifyOnConfigurationChanged extends EmptyLifecycleCallbacksAdapter {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            super.onActivityCreated(activity, savedInstanceState);
            onDisplayFeaturesChangedIfListening(activity);
        }

        @Override
        public void onActivityConfigurationChanged(Activity activity) {
            super.onActivityConfigurationChanged(activity);
            onDisplayFeaturesChangedIfListening(activity);
        }

        private void onDisplayFeaturesChangedIfListening(Activity activity) {
            IBinder token = activity.getWindow().getAttributes().token;
            if (token == null || isListeningForLayoutChanges(token)) {
                onDisplayFeaturesChanged();
            }
        }
    }
}
