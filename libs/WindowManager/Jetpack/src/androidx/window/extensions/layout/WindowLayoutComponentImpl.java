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
import static androidx.window.util.ExtensionHelper.isZero;
import static androidx.window.util.ExtensionHelper.rotateRectToDisplayRotation;
import static androidx.window.util.ExtensionHelper.transformToWindowSpaceRect;

import android.app.Activity;
import android.app.ActivityClient;
import android.app.Application;
import android.app.WindowConfiguration;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.WindowManager;
import android.window.TaskFragmentOrganizer;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiContext;
import androidx.window.common.CommonFoldingFeature;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.EmptyLifecycleCallbacksAdapter;
import androidx.window.extensions.core.util.function.Consumer;
import androidx.window.util.DataProducer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Map<Context, Consumer<WindowLayoutInfo>> mWindowLayoutChangeListeners =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final DataProducer<List<CommonFoldingFeature>> mFoldingFeatureProducer;

    @GuardedBy("mLock")
    private final List<CommonFoldingFeature> mLastReportedFoldingFeatures = new ArrayList<>();

    @GuardedBy("mLock")
    private final Map<IBinder, ConfigurationChangeListener> mConfigurationChangeListeners =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final Map<java.util.function.Consumer<WindowLayoutInfo>, Consumer<WindowLayoutInfo>>
            mJavaToExtConsumers = new ArrayMap<>();

    private final TaskFragmentOrganizer mTaskFragmentOrganizer;

    public WindowLayoutComponentImpl(@NonNull Context context,
            @NonNull TaskFragmentOrganizer taskFragmentOrganizer,
            @NonNull DeviceStateManagerFoldingFeatureProducer foldingFeatureProducer) {
        ((Application) context.getApplicationContext())
                .registerActivityLifecycleCallbacks(new NotifyOnConfigurationChanged());
        mFoldingFeatureProducer = foldingFeatureProducer;
        mFoldingFeatureProducer.addDataChangedCallback(this::onDisplayFeaturesChanged);
        mTaskFragmentOrganizer = taskFragmentOrganizer;
    }

    /** Registers to listen to {@link CommonFoldingFeature} changes */
    public void addFoldingStateChangedCallback(
            java.util.function.Consumer<List<CommonFoldingFeature>> consumer) {
        synchronized (mLock) {
            mFoldingFeatureProducer.addDataChangedCallback(consumer);
        }
    }

    /**
     * Adds a listener interested in receiving updates to {@link WindowLayoutInfo}
     *
     * @param activity hosting a {@link android.view.Window}
     * @param consumer interested in receiving updates to {@link WindowLayoutInfo}
     */
    @Override
    public void addWindowLayoutInfoListener(@NonNull Activity activity,
            @NonNull java.util.function.Consumer<WindowLayoutInfo> consumer) {
        final Consumer<WindowLayoutInfo> extConsumer = consumer::accept;
        synchronized (mLock) {
            mJavaToExtConsumers.put(consumer, extConsumer);
        }
        addWindowLayoutInfoListener(activity, extConsumer);
    }

    @Override
    public void addWindowLayoutInfoListener(@NonNull @UiContext Context context,
            @NonNull java.util.function.Consumer<WindowLayoutInfo> consumer) {
        final Consumer<WindowLayoutInfo> extConsumer = consumer::accept;
        synchronized (mLock) {
            mJavaToExtConsumers.put(consumer, extConsumer);
        }
        addWindowLayoutInfoListener(context, extConsumer);
    }

    /**
     * Similar to {@link #addWindowLayoutInfoListener(Activity, java.util.function.Consumer)}, but
     * takes a UI Context as a parameter.
     *
     * Jetpack {@link androidx.window.layout.ExtensionWindowLayoutInfoBackend} makes sure all
     * consumers related to the same {@link Context} gets updated {@link WindowLayoutInfo}
     * together. However only the first registered consumer of a {@link Context} will actually
     * invoke {@link #addWindowLayoutInfoListener(Context, Consumer)}.
     * Here we enforce that {@link #addWindowLayoutInfoListener(Context, Consumer)} can only be
     * called once for each {@link Context}.
     */
    @Override
    public void addWindowLayoutInfoListener(@NonNull @UiContext Context context,
            @NonNull Consumer<WindowLayoutInfo> consumer) {
        synchronized (mLock) {
            if (mWindowLayoutChangeListeners.containsKey(context)
                    // In theory this method can be called on the same consumer with different
                    // context.
                    || mWindowLayoutChangeListeners.containsValue(consumer)) {
                return;
            }
            if (!context.isUiContext()) {
                throw new IllegalArgumentException("Context must be a UI Context, which should be"
                        + " an Activity, WindowContext or InputMethodService");
            }
            mFoldingFeatureProducer.getData((features) -> {
                WindowLayoutInfo newWindowLayout = getWindowLayoutInfo(context, features);
                consumer.accept(newWindowLayout);
            });
            mWindowLayoutChangeListeners.put(context, consumer);

            final IBinder windowContextToken = context.getWindowContextToken();
            if (windowContextToken != null) {
                // We register component callbacks for window contexts. For activity contexts, they
                // will receive callbacks from NotifyOnConfigurationChanged instead.
                final ConfigurationChangeListener listener =
                        new ConfigurationChangeListener(windowContextToken);
                context.registerComponentCallbacks(listener);
                mConfigurationChangeListeners.put(windowContextToken, listener);
            }
        }
    }

    @Override
    public void removeWindowLayoutInfoListener(
            @NonNull java.util.function.Consumer<WindowLayoutInfo> consumer) {
        final Consumer<WindowLayoutInfo> extConsumer;
        synchronized (mLock) {
            extConsumer = mJavaToExtConsumers.remove(consumer);
        }
        if (extConsumer != null) {
            removeWindowLayoutInfoListener(extConsumer);
        }
    }

    /**
     * Removes a listener no longer interested in receiving updates.
     *
     * @param consumer no longer interested in receiving updates to {@link WindowLayoutInfo}
     */
    @Override
    public void removeWindowLayoutInfoListener(@NonNull Consumer<WindowLayoutInfo> consumer) {
        synchronized (mLock) {
            for (Context context : mWindowLayoutChangeListeners.keySet()) {
                if (!mWindowLayoutChangeListeners.get(context).equals(consumer)) {
                    continue;
                }
                final IBinder token = context.getWindowContextToken();
                if (token != null) {
                    context.unregisterComponentCallbacks(mConfigurationChangeListeners.get(token));
                    mConfigurationChangeListeners.remove(token);
                }
                break;
            }
            mWindowLayoutChangeListeners.values().remove(consumer);
        }
    }

    @GuardedBy("mLock")
    @NonNull
    private Set<Context> getContextsListeningForLayoutChanges() {
        return mWindowLayoutChangeListeners.keySet();
    }

    @GuardedBy("mLock")
    private boolean isListeningForLayoutChanges(IBinder token) {
        for (Context context : getContextsListeningForLayoutChanges()) {
            if (token.equals(Context.getToken(context))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A convenience method to translate from the common feature state to the extensions feature
     * state.  More specifically, translates from {@link CommonFoldingFeature.State} to
     * {@link FoldingFeature#STATE_FLAT} or {@link FoldingFeature#STATE_HALF_OPENED}. If it is not
     * possible to translate, then we will return a {@code null} value.
     *
     * @param state if it matches a value in {@link CommonFoldingFeature.State}, {@code null}
     *              otherwise. @return a {@link FoldingFeature#STATE_FLAT} or
     *              {@link FoldingFeature#STATE_HALF_OPENED} if the given state matches a value in
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

    private void onDisplayFeaturesChanged(List<CommonFoldingFeature> storedFeatures) {
        synchronized (mLock) {
            mLastReportedFoldingFeatures.clear();
            mLastReportedFoldingFeatures.addAll(storedFeatures);
            for (Context context : getContextsListeningForLayoutChanges()) {
                // Get the WindowLayoutInfo from the activity and pass the value to the
                // layoutConsumer.
                Consumer<WindowLayoutInfo> layoutConsumer = mWindowLayoutChangeListeners.get(
                        context);
                WindowLayoutInfo newWindowLayout = getWindowLayoutInfo(context, storedFeatures);
                layoutConsumer.accept(newWindowLayout);
            }
        }
    }

    /**
     * Translates the {@link DisplayFeature} into a {@link WindowLayoutInfo} when a
     * valid state is found.
     *
     * @param context a proxy for the {@link android.view.Window} that contains the
     *                {@link DisplayFeature}.
     */
    private WindowLayoutInfo getWindowLayoutInfo(@NonNull @UiContext Context context,
            List<CommonFoldingFeature> storedFeatures) {
        List<DisplayFeature> displayFeatureList = getDisplayFeatures(context, storedFeatures);
        return new WindowLayoutInfo(displayFeatureList);
    }

    /**
     * Gets the current {@link WindowLayoutInfo} computed with passed {@link WindowConfiguration}.
     *
     * @return current {@link WindowLayoutInfo} on the default display. Returns
     * empty {@link WindowLayoutInfo} on secondary displays.
     */
    @NonNull
    public WindowLayoutInfo getCurrentWindowLayoutInfo(int displayId,
            @NonNull WindowConfiguration windowConfiguration) {
        synchronized (mLock) {
            return getWindowLayoutInfo(displayId, windowConfiguration,
                    mLastReportedFoldingFeatures);
        }
    }

    /** @see #getWindowLayoutInfo(Context, List) */
    private WindowLayoutInfo getWindowLayoutInfo(int displayId,
            @NonNull WindowConfiguration windowConfiguration,
            List<CommonFoldingFeature> storedFeatures) {
        List<DisplayFeature> displayFeatureList = getDisplayFeatures(displayId, windowConfiguration,
                storedFeatures);
        return new WindowLayoutInfo(displayFeatureList);
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
     * @param context a proxy for the {@link android.view.Window} that contains the
     * {@link DisplayFeature}.
     * @return a {@link List}  of {@link DisplayFeature}s that are within the
     * {@link android.view.Window} of the {@link Activity}
     */
    private List<DisplayFeature> getDisplayFeatures(
            @NonNull @UiContext Context context, List<CommonFoldingFeature> storedFeatures) {
        if (!shouldReportDisplayFeatures(context)) {
            return Collections.emptyList();
        }
        return getDisplayFeatures(context.getDisplayId(),
                context.getResources().getConfiguration().windowConfiguration,
                storedFeatures);
    }

    /** @see #getDisplayFeatures(Context, List) */
    private List<DisplayFeature> getDisplayFeatures(int displayId,
            @NonNull WindowConfiguration windowConfiguration,
            List<CommonFoldingFeature> storedFeatures) {
        List<DisplayFeature> features = new ArrayList<>();
        if (displayId != DEFAULT_DISPLAY) {
            return features;
        }

        for (CommonFoldingFeature baseFeature : storedFeatures) {
            Integer state = convertToExtensionState(baseFeature.getState());
            if (state == null) {
                continue;
            }
            Rect featureRect = baseFeature.getRect();
            rotateRectToDisplayRotation(displayId, featureRect);
            transformToWindowSpaceRect(windowConfiguration, featureRect);

            if (!isZero(featureRect)) {
                // TODO(b/228641877): Remove guarding when fixed.
                features.add(new FoldingFeature(featureRect, baseFeature.getType(), state));
            }
        }
        return features;
    }

    /**
     * Calculates if the display features should be reported for the UI Context. The calculation
     * uses the task information because that is accurate for Activities in ActivityEmbedding mode.
     * TODO(b/238948678): Support reporting display features in all windowing modes.
     *
     * @return true if the display features should be reported for the UI Context, false otherwise.
     */
    private boolean shouldReportDisplayFeatures(@NonNull @UiContext Context context) {
        int displayId = context.getDisplay().getDisplayId();
        if (displayId != DEFAULT_DISPLAY) {
            // Display features are not supported on secondary displays.
            return false;
        }
        final int windowingMode;
        IBinder activityToken = context.getActivityToken();
        if (activityToken != null) {
            final Configuration taskConfig = ActivityClient.getInstance().getTaskConfiguration(
                    activityToken);
            if (taskConfig == null) {
                // If we cannot determine the task configuration for any reason, it is likely that
                // we won't be able to determine its position correctly as well. DisplayFeatures'
                // bounds in this case can't be computed correctly, so we should skip.
                return false;
            }
            final Rect taskBounds = taskConfig.windowConfiguration.getBounds();
            final WindowManager windowManager = Objects.requireNonNull(
                    context.getSystemService(WindowManager.class));
            final Rect currentBounds = windowManager.getCurrentWindowMetrics().getBounds();
            final Rect maxBounds = windowManager.getMaximumWindowMetrics().getBounds();
            boolean isTaskExpanded = maxBounds.equals(taskBounds);
            boolean isActivityExpanded = maxBounds.equals(currentBounds);
            /*
             * We need to proxy being in full screen because when a user enters PiP and exits PiP
             * the task windowingMode will report multi-window/pinned until the transition is
             * finished in WM Shell.
             * maxBounds == taskWindowBounds is a proxy check to verify the window is full screen
             * For tasks that are letterboxed, we use currentBounds == maxBounds to filter these
             * out.
             */
            // TODO(b/262900133) remove currentBounds check when letterboxed apps report bounds.
            // currently we don't want to report to letterboxed apps since they do not update the
            // window bounds when the Activity is moved.  An inaccurate fold will be reported so
            // we skip.
            return isTaskExpanded && (isActivityExpanded
                    || mTaskFragmentOrganizer.isActivityEmbedded(activityToken));
        } else {
            // TODO(b/242674941): use task windowing mode for window context that associates with
            //  activity.
            windowingMode = context.getResources().getConfiguration().windowConfiguration
                    .getWindowingMode();
        }
        // It is recommended not to report any display features in multi-window mode, since it
        // won't be possible to synchronize the display feature positions with window movement.
        return !WindowConfiguration.inMultiWindowMode(windowingMode);
    }

    @GuardedBy("mLock")
    private void onDisplayFeaturesChangedIfListening(@NonNull IBinder token) {
        if (isListeningForLayoutChanges(token)) {
            mFoldingFeatureProducer.getData(
                    WindowLayoutComponentImpl.this::onDisplayFeaturesChanged);
        }
    }

    private final class NotifyOnConfigurationChanged extends EmptyLifecycleCallbacksAdapter {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            super.onActivityCreated(activity, savedInstanceState);
            synchronized (mLock) {
                onDisplayFeaturesChangedIfListening(activity.getActivityToken());
            }
        }

        @Override
        public void onActivityConfigurationChanged(Activity activity) {
            super.onActivityConfigurationChanged(activity);
            synchronized (mLock) {
                onDisplayFeaturesChangedIfListening(activity.getActivityToken());
            }
        }
    }

    private final class ConfigurationChangeListener implements ComponentCallbacks {
        final IBinder mToken;

        ConfigurationChangeListener(IBinder token) {
            mToken = token;
        }

        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            synchronized (mLock) {
                onDisplayFeaturesChangedIfListening(mToken);
            }
        }

        @Override
        public void onLowMemory() {
        }
    }
}
