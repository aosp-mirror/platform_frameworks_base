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
import static android.view.Display.INVALID_DISPLAY;

import static androidx.window.common.ExtensionHelper.isZero;
import static androidx.window.common.ExtensionHelper.rotateRectToDisplayRotation;
import static androidx.window.common.ExtensionHelper.transformToWindowSpaceRect;
import static androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_FLAT;
import static androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_HALF_OPENED;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.Application;
import android.app.WindowConfiguration;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Bundle;
import android.os.IBinder;
import android.os.StrictMode;
import android.util.ArrayMap;
import android.util.Log;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiContext;
import androidx.annotation.VisibleForTesting;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.EmptyLifecycleCallbacksAdapter;
import androidx.window.common.collections.ListUtil;
import androidx.window.common.layout.CommonFoldingFeature;
import androidx.window.extensions.core.util.function.Consumer;
import androidx.window.extensions.util.DeduplicateConsumer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reference implementation of androidx.window.extensions.layout OEM interface for use with
 * WindowManager Jetpack.
 */
public class WindowLayoutComponentImpl implements WindowLayoutComponent {
    private static final String TAG = WindowLayoutComponentImpl.class.getSimpleName();

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Map<Context, DeduplicateConsumer<WindowLayoutInfo>> mWindowLayoutChangeListeners =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final DeviceStateManagerFoldingFeatureProducer mFoldingFeatureProducer;

    /**
     * The last reported folding features from the device. This is initialized in the constructor
     * because the data change callback added to {@link #mFoldingFeatureProducer} is immediately
     * called. This is due to current device state from the device state manager already being
     * available in the {@link DeviceStateManagerFoldingFeatureProducer}.
     */
    @GuardedBy("mLock")
    private final List<CommonFoldingFeature> mLastReportedFoldingFeatures = new ArrayList<>();

    @GuardedBy("mLock")
    private final Map<IBinder, ConfigurationChangeListener> mConfigurationChangeListeners =
            new ArrayMap<>();

    @GuardedBy("mLock")
    private final Map<java.util.function.Consumer<WindowLayoutInfo>, Consumer<WindowLayoutInfo>>
            mJavaToExtConsumers = new ArrayMap<>();

    private final RawConfigurationChangedListener mRawConfigurationChangedListener =
            new RawConfigurationChangedListener();

    private final SupportedWindowFeatures mSupportedWindowFeatures;

    private final DisplayStateProvider mDisplayStateProvider;

    public WindowLayoutComponentImpl(@NonNull Context context,
            @NonNull DeviceStateManagerFoldingFeatureProducer foldingFeatureProducer) {
        this(context, foldingFeatureProducer, new DisplayStateProvider() {
            @Override
            public int getDisplayRotation(@NonNull WindowConfiguration windowConfiguration) {
                return windowConfiguration.getDisplayRotation();
            }

            @NonNull
            @Override
            public DisplayInfo getDisplayInfo(int displayId) {
                return DisplayManagerGlobal.getInstance().getDisplayInfo(displayId);
            }
        });
    }

    @VisibleForTesting
    WindowLayoutComponentImpl(@NonNull Context context,
            @NonNull DeviceStateManagerFoldingFeatureProducer foldingFeatureProducer,
            @NonNull DisplayStateProvider displayStateProvider) {
        mDisplayStateProvider = displayStateProvider;
        ((Application) context.getApplicationContext())
                .registerActivityLifecycleCallbacks(new NotifyOnConfigurationChanged());
        mFoldingFeatureProducer = foldingFeatureProducer;
        mFoldingFeatureProducer.addDataChangedCallback(this::onDisplayFeaturesChanged);
        final List<DisplayFoldFeature> displayFoldFeatures = ListUtil.map(
                mFoldingFeatureProducer.getDisplayFeatures(), DisplayFoldFeatureUtil::translate);
        mSupportedWindowFeatures = new SupportedWindowFeatures.Builder(displayFoldFeatures).build();
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
            updateListenerRegistrations();
        }
        addWindowLayoutInfoListener(activity, extConsumer);
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
                    || containsConsumer(consumer)) {
                return;
            }
            assertUiContext(context);
            Log.d(TAG, "Register WindowLayoutInfoListener on "
                    + dumpAllBaseContextToString(context));
            mFoldingFeatureProducer.getData((features) -> {
                WindowLayoutInfo newWindowLayout = getWindowLayoutInfo(context, features);
                consumer.accept(newWindowLayout);
            });
            mWindowLayoutChangeListeners.put(context, new DeduplicateConsumer<>(consumer));

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

    @NonNull
    private String dumpAllBaseContextToString(@NonNull Context context) {
        final StringBuilder builder = new StringBuilder("Context=" + context);
        while ((context instanceof ContextWrapper wrapper) && wrapper.getBaseContext() != null) {
            context = wrapper.getBaseContext();
            builder.append(", of which baseContext=").append(context);
        }
        return builder.toString();
    }

    @Override
    public void removeWindowLayoutInfoListener(
            @NonNull java.util.function.Consumer<WindowLayoutInfo> consumer) {
        final Consumer<WindowLayoutInfo> extConsumer;
        synchronized (mLock) {
            extConsumer = mJavaToExtConsumers.remove(consumer);
            updateListenerRegistrations();
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
            DeduplicateConsumer<WindowLayoutInfo> consumerToRemove = null;
            for (Context context : mWindowLayoutChangeListeners.keySet()) {
                final DeduplicateConsumer<WindowLayoutInfo> deduplicateConsumer =
                        mWindowLayoutChangeListeners.get(context);
                if (!deduplicateConsumer.matchesConsumer(consumer)) {
                    continue;
                }
                final IBinder token = context.getWindowContextToken();
                consumerToRemove = deduplicateConsumer;
                if (token != null) {
                    context.unregisterComponentCallbacks(mConfigurationChangeListeners.get(token));
                    mConfigurationChangeListeners.remove(token);
                }
                break;
            }
            if (consumerToRemove != null) {
                mWindowLayoutChangeListeners.values().remove(consumerToRemove);
            }
        }
    }

    @GuardedBy("mLock")
    private boolean containsConsumer(@NonNull Consumer<WindowLayoutInfo> consumer) {
        for (DeduplicateConsumer<WindowLayoutInfo> c : mWindowLayoutChangeListeners.values()) {
            if (c.matchesConsumer(consumer)) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private void updateListenerRegistrations() {
        ActivityThread currentThread = ActivityThread.currentActivityThread();
        if (mJavaToExtConsumers.isEmpty()) {
            currentThread.removeConfigurationChangedListener(mRawConfigurationChangedListener);
        } else {
            currentThread.addConfigurationChangedListener(Runnable::run,
                    mRawConfigurationChangedListener);
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

    @VisibleForTesting
    void onDisplayFeaturesChanged(List<CommonFoldingFeature> storedFeatures) {
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
    @NonNull
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

    @Override
    @NonNull
    public WindowLayoutInfo getCurrentWindowLayoutInfo(@NonNull @UiContext Context context) {
        assertUiContext(context);
        synchronized (mLock) {
            return getWindowLayoutInfo(context, mLastReportedFoldingFeatures);
        }
    }

    /**
     * Returns the {@link SupportedWindowFeatures} for the device. This list does not change over
     * time.
     */
    @NonNull
    public SupportedWindowFeatures getSupportedWindowFeatures() {
        return mSupportedWindowFeatures;
    }

    private void assertUiContext(@NonNull Context context) {
        final IllegalArgumentException exception = new IllegalArgumentException(
                "Context must be a UI Context with display association, which should be "
                        + "an Activity, WindowContext or InputMethodService");
        if (!context.isUiContext()) {
            throw exception;
        }
        if (context.getAssociatedDisplayId() == INVALID_DISPLAY) {
            // This is to identify if #isUiContext of a non-UI Context is overridden.
            // #isUiContext is more likely to be overridden than #getAssociatedDisplayId
            // since #isUiContext is a public API.
            StrictMode.onIncorrectContextUsed("The given context is a UI context, "
                    + "but it is not associated with any display. "
                    + "This context may not receive WindowLayoutInfo updates and "
                    + "may get an empty WindowLayoutInfo return value. "
                    + dumpAllBaseContextToString(context), exception);
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
     *                {@link DisplayFeature}.
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

        // We will transform the feature bounds to the Activity window, so using the rotation
        // from the same source (WindowConfiguration) to make sure they are synchronized.
        final int rotation = mDisplayStateProvider.getDisplayRotation(windowConfiguration);
        final DisplayInfo displayInfo = mDisplayStateProvider.getDisplayInfo(displayId);

        for (CommonFoldingFeature baseFeature : storedFeatures) {
            Integer state = convertToExtensionState(baseFeature.getState());
            if (state == null) {
                continue;
            }
            final Rect featureRect = baseFeature.getRect();
            rotateRectToDisplayRotation(displayInfo, rotation, featureRect);
            transformToWindowSpaceRect(windowConfiguration, featureRect);

            if (isZero(featureRect)) {
                // TODO(b/228641877): Remove guarding when fixed.
                continue;
            }
            if (featureRect.left != 0 && featureRect.top != 0) {
                Log.wtf(TAG, "Bounding rectangle must start at the top or "
                        + "left of the window. BaseFeatureRect: " + baseFeature.getRect()
                        + ", FeatureRect: " + featureRect
                        + ", WindowConfiguration: " + windowConfiguration);
                continue;

            }
            if (featureRect.left == 0
                    && featureRect.width() != windowConfiguration.getBounds().width()) {
                Log.w(TAG, "Horizontal FoldingFeature must have full width."
                        + " BaseFeatureRect: " + baseFeature.getRect()
                        + ", FeatureRect: " + featureRect
                        + ", WindowConfiguration: " + windowConfiguration);
                continue;
            }
            if (featureRect.top == 0
                    && featureRect.height() != windowConfiguration.getBounds().height()) {
                Log.w(TAG, "Vertical FoldingFeature must have full height."
                        + " BaseFeatureRect: " + baseFeature.getRect()
                        + ", FeatureRect: " + featureRect
                        + ", WindowConfiguration: " + windowConfiguration);
                continue;
            }
            features.add(new FoldingFeature(featureRect, baseFeature.getType(), state));
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
        int displayId = context.getAssociatedDisplayId();
        if (!context.isUiContext() || displayId == INVALID_DISPLAY) {
            // This could happen if a caller sets MutableContextWrapper's base Context to a non-UI
            // Context.
            StrictMode.onIncorrectContextUsed("Context is not a UI Context anymore."
                    + " Was the base context changed? It's suggested to unregister"
                    + " the windowLayoutInfo callback before changing the base Context."
                    + " UI Contexts are Activity, InputMethodService or context created"
                    + " with createWindowContext. " + dumpAllBaseContextToString(context),
                    new UnsupportedOperationException("Context is not a UI Context anymore."
                            + " Was the base context changed?"));
        }
        if (displayId != DEFAULT_DISPLAY) {
            // Display features are not supported on secondary displays.
            return false;
        }

        // We do not report folding features for Activities in PiP because the bounds are
        // not updated fast enough and the window is too small for the UI to adapt.
        return context.getResources().getConfiguration().windowConfiguration
                .getWindowingMode() != WindowConfiguration.WINDOWING_MODE_PINNED;
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

    private final class RawConfigurationChangedListener implements
            java.util.function.Consumer<IBinder> {
        @Override
        public void accept(IBinder activityToken) {
            synchronized (mLock) {
                onDisplayFeaturesChangedIfListening(activityToken);
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

    @VisibleForTesting
    interface DisplayStateProvider {
        @Surface.Rotation
        int getDisplayRotation(@NonNull WindowConfiguration windowConfiguration);

        @NonNull
        DisplayInfo getDisplayInfo(int displayId);
    }
}
