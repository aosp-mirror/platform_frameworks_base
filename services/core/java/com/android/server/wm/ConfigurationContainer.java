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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.app.WindowConfiguration.windowingModeToString;
import static android.app.WindowConfigurationProto.WINDOWING_MODE;
import static android.content.ConfigurationProto.WINDOW_CONFIGURATION;

import static com.android.server.wm.ConfigurationContainerProto.FULL_CONFIGURATION;
import static com.android.server.wm.ConfigurationContainerProto.MERGED_OVERRIDE_CONFIGURATION;
import static com.android.server.wm.ConfigurationContainerProto.OVERRIDE_CONFIGURATION;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.LocaleList;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Contains common logic for classes that have override configurations and are organized in a
 * hierarchy.
 */
public abstract class ConfigurationContainer<E extends ConfigurationContainer> {
    /**
     * {@link #Rect} returned from {@link #getRequestedOverrideBounds()} to prevent original value
     * from being set directly.
     */
    private Rect mReturnBounds = new Rect();

    /**
     * Contains requested override configuration settings applied to this configuration container.
     */
    private Configuration mRequestedOverrideConfiguration = new Configuration();

    /**
     * Contains the requested override configuration with parent and policy constraints applied.
     * This is the set of overrides that gets applied to the full and merged configurations.
     */
    private Configuration mResolvedOverrideConfiguration = new Configuration();

    /** True if mRequestedOverrideConfiguration is not empty */
    private boolean mHasOverrideConfiguration;

    /**
     * Contains full configuration applied to this configuration container. Corresponds to full
     * parent's config with applied {@link #mResolvedOverrideConfiguration}.
     */
    private Configuration mFullConfiguration = new Configuration();

    /**
     * Contains merged override configuration settings from the top of the hierarchy down to this
     * particular instance. It is different from {@link #mFullConfiguration} because it starts from
     * topmost container's override config instead of global config.
     */
    private Configuration mMergedOverrideConfiguration = new Configuration();

    private ArrayList<ConfigurationContainerListener> mChangeListeners = new ArrayList<>();

    // TODO: Can't have ag/2592611 soon enough!
    private final Configuration mRequestsTmpConfig = new Configuration();
    private final Configuration mResolvedTmpConfig = new Configuration();

    // Used for setting bounds
    private final Rect mTmpRect = new Rect();

    static final int BOUNDS_CHANGE_NONE = 0;

    /**
     * Return value from {@link #setBounds(Rect)} indicating the position of the override bounds
     * changed.
     */
    static final int BOUNDS_CHANGE_POSITION = 1;

    /**
     * Return value from {@link #setBounds(Rect)} indicating the size of the override bounds
     * changed.
     */
    static final int BOUNDS_CHANGE_SIZE = 1 << 1;

    /**
     * Returns full configuration applied to this configuration container.
     * This method should be used for getting settings applied in each particular level of the
     * hierarchy.
     */
    @NonNull
    public Configuration getConfiguration() {
        return mFullConfiguration;
    }

    /**
     * Notify that parent config changed and we need to update full configuration.
     * @see #mFullConfiguration
     */
    public void onConfigurationChanged(Configuration newParentConfig) {
        mResolvedTmpConfig.setTo(mResolvedOverrideConfiguration);
        resolveOverrideConfiguration(newParentConfig);
        mFullConfiguration.setTo(newParentConfig);
        // Do not inherit always-on-top property from parent, otherwise the always-on-top
        // property is propagated to all children. In that case, newly added child is
        // always being positioned at bottom (behind the always-on-top siblings).
        mFullConfiguration.windowConfiguration.unsetAlwaysOnTop();
        mFullConfiguration.updateFrom(mResolvedOverrideConfiguration);
        onMergedOverrideConfigurationChanged();
        if (!mResolvedTmpConfig.equals(mResolvedOverrideConfiguration)) {
            // This depends on the assumption that change-listeners don't do
            // their own override resolution. This way, dependent hierarchies
            // can stay properly synced-up with a primary hierarchy's constraints.
            // Since the hierarchies will be merged, this whole thing will go away
            // before the assumption will be broken.
            // Inform listeners of the change.
            for (int i = mChangeListeners.size() - 1; i >= 0; --i) {
                mChangeListeners.get(i).onRequestedOverrideConfigurationChanged(
                        mResolvedOverrideConfiguration);
            }
        }
        for (int i = mChangeListeners.size() - 1; i >= 0; --i) {
            mChangeListeners.get(i).onMergedOverrideConfigurationChanged(
                    mMergedOverrideConfiguration);
        }
        for (int i = getChildCount() - 1; i >= 0; --i) {
            dispatchConfigurationToChild(getChildAt(i), mFullConfiguration);
        }
    }

    /**
     * Dispatches the configuration to child when {@link #onConfigurationChanged(Configuration)} is
     * called. This allows the derived classes to override how to dispatch the configuration.
     */
    void dispatchConfigurationToChild(E child, Configuration config) {
        child.onConfigurationChanged(config);
    }

    /**
     * Resolves the current requested override configuration into
     * {@link #mResolvedOverrideConfiguration}
     *
     * @param newParentConfig The new parent configuration to resolve overrides against.
     */
    void resolveOverrideConfiguration(Configuration newParentConfig) {
        mResolvedOverrideConfiguration.setTo(mRequestedOverrideConfiguration);
    }

    /** Returns {@code true} if requested override override configuration is not empty. */
    boolean hasRequestedOverrideConfiguration() {
        return mHasOverrideConfiguration;
    }

    /** Returns requested override configuration applied to this configuration container. */
    @NonNull
    public Configuration getRequestedOverrideConfiguration() {
        return mRequestedOverrideConfiguration;
    }

    /** Returns the resolved override configuration. */
    @NonNull
    Configuration getResolvedOverrideConfiguration() {
        return mResolvedOverrideConfiguration;
    }

    /**
     * Update override configuration and recalculate full config.
     * @see #mRequestedOverrideConfiguration
     * @see #mFullConfiguration
     */
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        updateRequestedOverrideConfiguration(overrideConfiguration);
        // Update full configuration of this container and all its children.
        final ConfigurationContainer parent = getParent();
        onConfigurationChanged(parent != null ? parent.getConfiguration() : Configuration.EMPTY);
    }

    /** Updates override configuration without recalculate full config. */
    void updateRequestedOverrideConfiguration(Configuration overrideConfiguration) {
        // Pre-compute this here, so we don't need to go through the entire Configuration when
        // writing to proto (which has significant cost if we write a lot of empty configurations).
        mHasOverrideConfiguration = !Configuration.EMPTY.equals(overrideConfiguration);
        mRequestedOverrideConfiguration.setTo(overrideConfiguration);
        final Rect newBounds = mRequestedOverrideConfiguration.windowConfiguration.getBounds();
        if (mHasOverrideConfiguration && providesMaxBounds()
                && diffRequestedOverrideMaxBounds(newBounds) != BOUNDS_CHANGE_NONE) {
            mRequestedOverrideConfiguration.windowConfiguration.setMaxBounds(newBounds);
        }
    }

    /**
     * Get merged override configuration from the top of the hierarchy down to this particular
     * instance. This should be reported to client as override config.
     */
    @NonNull
    public Configuration getMergedOverrideConfiguration() {
        return mMergedOverrideConfiguration;
    }

    /**
     * Update merged override configuration based on corresponding parent's config. If there is no
     * parent, merged override configuration will set equal to current override config. This
     * doesn't cascade on its own since it's called by {@link #onConfigurationChanged}.
     * @see #mMergedOverrideConfiguration
     */
    void onMergedOverrideConfigurationChanged() {
        final ConfigurationContainer parent = getParent();
        if (parent != null) {
            mMergedOverrideConfiguration.setTo(parent.getMergedOverrideConfiguration());
            // Do not inherit always-on-top property from parent, otherwise the always-on-top
            // property is propagated to all children. In that case, newly added child is
            // always being positioned at bottom (behind the always-on-top siblings).
            mMergedOverrideConfiguration.windowConfiguration.unsetAlwaysOnTop();
            mMergedOverrideConfiguration.updateFrom(mResolvedOverrideConfiguration);
        } else {
            mMergedOverrideConfiguration.setTo(mResolvedOverrideConfiguration);
        }
    }

    /**
     * Indicates whether this container chooses not to override any bounds from its parent, either
     * because it doesn't request to override them or the request is dropped during configuration
     * resolution. In this case, it will inherit the bounds of the first ancestor which specifies a
     * bounds subject to policy constraints.
     *
     * @return {@code true} if this container level uses bounds from parent level. {@code false}
     *         otherwise.
     */
    public boolean matchParentBounds() {
        return getResolvedOverrideBounds().isEmpty();
    }

    /**
     * Returns whether the bounds specified are considered the same as the existing requested
     * override bounds. This is either when the two bounds are equal or the requested override
     * bounds are empty and the specified bounds is null.
     *
     * @return {@code true} if the bounds are equivalent, {@code false} otherwise
     */
    public boolean equivalentRequestedOverrideBounds(Rect bounds) {
        return equivalentBounds(getRequestedOverrideBounds(),  bounds);
    }

    /** Similar to {@link #equivalentRequestedOverrideBounds(Rect)}, but compares max bounds. */
    public boolean equivalentRequestedOverrideMaxBounds(Rect bounds) {
        return equivalentBounds(getRequestedOverrideMaxBounds(),  bounds);
    }

    /**
     * Returns whether the two bounds are equal to each other or are a combination of null or empty.
     */
    public static boolean equivalentBounds(Rect bounds, Rect other) {
        return bounds == other
                || (bounds != null && (bounds.equals(other) || (bounds.isEmpty() && other == null)))
                || (other != null && other.isEmpty() && bounds == null);
    }

    /**
     * Returns the effective bounds of this container, inheriting the first non-empty bounds set in
     * its ancestral hierarchy, including itself.
     */
    public Rect getBounds() {
        mReturnBounds.set(getConfiguration().windowConfiguration.getBounds());
        return mReturnBounds;
    }

    public void getBounds(Rect outBounds) {
        outBounds.set(getBounds());
    }

    /** Similar to {@link #getBounds()}, but reports the max bounds. */
    public Rect getMaxBounds() {
        mReturnBounds.set(getConfiguration().windowConfiguration.getMaxBounds());
        return mReturnBounds;
    }

    /**
     * Sets {@code out} to the top-left corner of the bounds as returned by {@link #getBounds()}.
     */
    public void getPosition(Point out) {
        Rect bounds = getBounds();
        out.set(bounds.left, bounds.top);
    }

    Rect getResolvedOverrideBounds() {
        mReturnBounds.set(getResolvedOverrideConfiguration().windowConfiguration.getBounds());
        return mReturnBounds;
    }

    /**
     * Returns the bounds requested on this container. These may not be the actual bounds the
     * container ends up with due to policy constraints. The {@link Rect} handed back is
     * shared for all calls to this method and should not be modified.
     */
    public Rect getRequestedOverrideBounds() {
        mReturnBounds.set(getRequestedOverrideConfiguration().windowConfiguration.getBounds());

        return mReturnBounds;
    }

    /** Similar to {@link #getRequestedOverrideBounds()}, but returns the max bounds. */
    public Rect getRequestedOverrideMaxBounds() {
        mReturnBounds.set(getRequestedOverrideConfiguration().windowConfiguration.getMaxBounds());

        return mReturnBounds;
    }

    /**
     * Returns {@code true} if the {@link WindowConfiguration} in the requested override
     * {@link Configuration} specifies bounds.
     */
    public boolean hasOverrideBounds() {
        return !getRequestedOverrideBounds().isEmpty();
    }

    /**
     * Sets the passed in {@link Rect} to the current bounds.
     * @see #getRequestedOverrideBounds()
     */
    public void getRequestedOverrideBounds(Rect outBounds) {
        outBounds.set(getRequestedOverrideBounds());
    }

    /**
     * Sets the bounds at the current hierarchy level, overriding any bounds set on an ancestor.
     * This value will be reported when {@link #getBounds()} and
     * {@link #getRequestedOverrideBounds()}. If
     * an empty {@link Rect} or null is specified, this container will be considered to match its
     * parent bounds {@see #matchParentBounds} and will inherit bounds from its parent.
     *
     * @param bounds The bounds defining the container size.
     *
     * @return a bitmask representing the types of changes made to the bounds.
     */
    public int setBounds(Rect bounds) {
        int boundsChange = diffRequestedOverrideBounds(bounds);
        final boolean overrideMaxBounds = providesMaxBounds()
                && diffRequestedOverrideMaxBounds(bounds) != BOUNDS_CHANGE_NONE;

        if (boundsChange == BOUNDS_CHANGE_NONE && !overrideMaxBounds) {
            return boundsChange;
        }

        mRequestsTmpConfig.setTo(getRequestedOverrideConfiguration());
        mRequestsTmpConfig.windowConfiguration.setBounds(bounds);
        onRequestedOverrideConfigurationChanged(mRequestsTmpConfig);

        return boundsChange;
    }

    public int setBounds(int left, int top, int right, int bottom) {
        mTmpRect.set(left, top, right, bottom);
        return setBounds(mTmpRect);
    }

    /**
     * Returns {@code true} if this {@link ConfigurationContainer} provides the maximum bounds to
     * its child {@link ConfigurationContainer}s. Returns {@code false}, otherwise.
     * <p>
     * The maximum bounds is how large a window can be expanded.
     * </p>
     */
    protected boolean providesMaxBounds() {
        return false;
    }

    int diffRequestedOverrideMaxBounds(Rect bounds) {
        if (equivalentRequestedOverrideMaxBounds(bounds)) {
            return BOUNDS_CHANGE_NONE;
        }

        int boundsChange = BOUNDS_CHANGE_NONE;

        final Rect existingBounds = getRequestedOverrideMaxBounds();

        if (bounds == null || existingBounds.left != bounds.left
                || existingBounds.top != bounds.top) {
            boundsChange |= BOUNDS_CHANGE_POSITION;
        }

        if (bounds == null || existingBounds.width() != bounds.width()
                || existingBounds.height() != bounds.height()) {
            boundsChange |= BOUNDS_CHANGE_SIZE;
        }

        return boundsChange;
    }

    int diffRequestedOverrideBounds(Rect bounds) {
        if (equivalentRequestedOverrideBounds(bounds)) {
            return BOUNDS_CHANGE_NONE;
        }

        int boundsChange = BOUNDS_CHANGE_NONE;

        final Rect existingBounds = getRequestedOverrideBounds();

        if (bounds == null || existingBounds.left != bounds.left
                || existingBounds.top != bounds.top) {
            boundsChange |= BOUNDS_CHANGE_POSITION;
        }

        if (bounds == null || existingBounds.width() != bounds.width()
                || existingBounds.height() != bounds.height()) {
            boundsChange |= BOUNDS_CHANGE_SIZE;
        }

        return boundsChange;
    }

    public WindowConfiguration getWindowConfiguration() {
        return mFullConfiguration.windowConfiguration;
    }

    /** Returns the windowing mode the configuration container is currently in. */
    public int getWindowingMode() {
        return mFullConfiguration.windowConfiguration.getWindowingMode();
    }

    /** Returns the windowing mode override that is requested by this container. */
    public int getRequestedOverrideWindowingMode() {
        return mRequestedOverrideConfiguration.windowConfiguration.getWindowingMode();
    }

    /** Sets the requested windowing mode override for the configuration container. */
    public void setWindowingMode(/*@WindowConfiguration.WindowingMode*/ int windowingMode) {
        mRequestsTmpConfig.setTo(getRequestedOverrideConfiguration());
        mRequestsTmpConfig.windowConfiguration.setWindowingMode(windowingMode);
        onRequestedOverrideConfigurationChanged(mRequestsTmpConfig);
    }

    /** Sets the always on top flag for this configuration container.
     *  When you call this function, make sure that the following functions are called as well to
     *  keep proper z-order.
     *  - {@link TaskDisplayArea#positionChildAt(int POSITION_TOP, Task, boolean)};
     * */
    public void setAlwaysOnTop(boolean alwaysOnTop) {
        mRequestsTmpConfig.setTo(getRequestedOverrideConfiguration());
        mRequestsTmpConfig.windowConfiguration.setAlwaysOnTop(alwaysOnTop);
        onRequestedOverrideConfigurationChanged(mRequestsTmpConfig);
    }

    /**
     * Returns true if this container is currently in multi-window mode. I.e. sharing the screen
     * with another activity.
     */
    public boolean inMultiWindowMode() {
        /*@WindowConfiguration.WindowingMode*/ int windowingMode =
                mFullConfiguration.windowConfiguration.getWindowingMode();
        return WindowConfiguration.inMultiWindowMode(windowingMode);
    }

    public boolean inPinnedWindowingMode() {
        return mFullConfiguration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }

    public boolean inFreeformWindowingMode() {
        return mFullConfiguration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_FREEFORM;
    }

    /** Returns the activity type associated with the configuration container. */
    /*@WindowConfiguration.ActivityType*/
    public int getActivityType() {
        return mFullConfiguration.windowConfiguration.getActivityType();
    }

    /** Sets the activity type to associate with the configuration container. */
    public void setActivityType(/*@WindowConfiguration.ActivityType*/ int activityType) {
        int currentActivityType = getActivityType();
        if (currentActivityType == activityType) {
            return;
        }
        if (currentActivityType != ACTIVITY_TYPE_UNDEFINED) {
            throw new IllegalStateException("Can't change activity type once set: " + this
                    + " activityType=" + activityTypeToString(activityType));
        }
        mRequestsTmpConfig.setTo(getRequestedOverrideConfiguration());
        mRequestsTmpConfig.windowConfiguration.setActivityType(activityType);
        onRequestedOverrideConfigurationChanged(mRequestsTmpConfig);
    }

    public boolean isActivityTypeHome() {
        return getActivityType() == ACTIVITY_TYPE_HOME;
    }

    public boolean isActivityTypeRecents() {
        return getActivityType() == ACTIVITY_TYPE_RECENTS;
    }

    final boolean isActivityTypeHomeOrRecents() {
        final int activityType = getActivityType();
        return activityType == ACTIVITY_TYPE_HOME || activityType == ACTIVITY_TYPE_RECENTS;
    }

    public boolean isActivityTypeAssistant() {
        return getActivityType() == ACTIVITY_TYPE_ASSISTANT;
    }

    /**
     * Applies app-specific nightMode and {@link LocaleList} on requested configuration.
     * @return true if any of the requested configuration has been updated.
     */
    public boolean applyAppSpecificConfig(Integer nightMode, LocaleList locales,
            @Configuration.GrammaticalGender Integer gender) {
        mRequestsTmpConfig.setTo(getRequestedOverrideConfiguration());
        boolean newNightModeSet = (nightMode != null) && setOverrideNightMode(mRequestsTmpConfig,
                nightMode);
        boolean newLocalesSet = (locales != null) && setOverrideLocales(mRequestsTmpConfig,
                locales);
        boolean newGenderSet = (gender != null) && setOverrideGender(mRequestsTmpConfig,
                gender);
        if (newNightModeSet || newLocalesSet || newGenderSet) {
            onRequestedOverrideConfigurationChanged(mRequestsTmpConfig);
        }
        return newNightModeSet || newLocalesSet || newGenderSet;
    }

    /**
     * Overrides the night mode applied to this ConfigurationContainer.
     * @return true if the nightMode has been changed.
     */
    private boolean setOverrideNightMode(Configuration requestsTmpConfig, int nightMode) {
        final int currentUiMode = mRequestedOverrideConfiguration.uiMode;
        final int currentNightMode = currentUiMode & Configuration.UI_MODE_NIGHT_MASK;
        final int validNightMode = nightMode & Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == validNightMode) {
            return false;
        }
        requestsTmpConfig.uiMode = validNightMode
                | (currentUiMode & ~Configuration.UI_MODE_NIGHT_MASK);
        return true;
    }

    /**
     * Overrides the locales applied to this ConfigurationContainer.
     * @return true if the LocaleList has been changed.
     */
    private boolean setOverrideLocales(Configuration requestsTmpConfig,
            @NonNull LocaleList overrideLocales) {
        if (mRequestedOverrideConfiguration.getLocales().equals(overrideLocales)) {
            return false;
        }
        requestsTmpConfig.setLocales(overrideLocales);
        requestsTmpConfig.userSetLocale = true;
        return true;
    }

    /**
     * Overrides the gender to this ConfigurationContainer.
     *
     * @return true if the grammatical gender has been changed.
     */
    private boolean setOverrideGender(Configuration requestsTmpConfig,
            @Configuration.GrammaticalGender int gender) {
        if (mRequestedOverrideConfiguration.getGrammaticalGender() == gender) {
            return false;
        } else {
            requestsTmpConfig.setGrammaticalGender(gender);
            return true;
        }
    }

    public boolean isActivityTypeDream() {
        return getActivityType() == ACTIVITY_TYPE_DREAM;
    }

    public boolean isActivityTypeStandard() {
        return getActivityType() == ACTIVITY_TYPE_STANDARD;
    }

    public boolean isActivityTypeStandardOrUndefined() {
        /*@WindowConfiguration.ActivityType*/ final int activityType = getActivityType();
        return activityType == ACTIVITY_TYPE_STANDARD || activityType == ACTIVITY_TYPE_UNDEFINED;
    }

    public static boolean isCompatibleActivityType(int currentType, int otherType) {
        if (currentType == otherType) {
            return true;
        }
        if (currentType == ACTIVITY_TYPE_ASSISTANT) {
            // Assistant activities are only compatible with themselves...
            return false;
        }
        // Otherwise we are compatible if us or other is not currently defined.
        return currentType == ACTIVITY_TYPE_UNDEFINED || otherType == ACTIVITY_TYPE_UNDEFINED;
    }

    /**
     * Returns true if this container is compatible with the input windowing mode and activity type.
     * The container is compatible:
     * - If {@param activityType} and {@param windowingMode} match this container activity type and
     * windowing mode.
     * - If {@param activityType} is {@link WindowConfiguration#ACTIVITY_TYPE_UNDEFINED} or
     * {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD} and this containers activity type is also
     * standard or undefined and its windowing mode matches {@param windowingMode}.
     * - If {@param activityType} isn't {@link WindowConfiguration#ACTIVITY_TYPE_UNDEFINED} or
     * {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD} or this containers activity type isn't
     * also standard or undefined and its activity type matches {@param activityType} regardless of
     * if {@param windowingMode} matches the containers windowing mode.
     */
    public boolean isCompatible(int windowingMode, int activityType) {
        final int thisActivityType = getActivityType();
        final int thisWindowingMode = getWindowingMode();
        final boolean sameActivityType = thisActivityType == activityType;
        final boolean sameWindowingMode = thisWindowingMode == windowingMode;

        if (sameActivityType && sameWindowingMode) {
            return true;
        }

        if ((activityType != ACTIVITY_TYPE_UNDEFINED && activityType != ACTIVITY_TYPE_STANDARD)
                || !isActivityTypeStandardOrUndefined()) {
            // Only activity type need to match for non-standard activity types that are defined.
            return sameActivityType;
        }

        // Otherwise we are compatible if the windowing mode is the same.
        return sameWindowingMode;
    }

    void registerConfigurationChangeListener(ConfigurationContainerListener listener) {
        registerConfigurationChangeListener(listener, true /* shouldDispatchConfig */);
    }

    void registerConfigurationChangeListener(ConfigurationContainerListener listener,
            boolean shouldDispatchConfig) {
        if (mChangeListeners.contains(listener)) {
            return;
        }
        mChangeListeners.add(listener);
        if (shouldDispatchConfig) {
            listener.onRequestedOverrideConfigurationChanged(mResolvedOverrideConfiguration);
            listener.onMergedOverrideConfigurationChanged(mMergedOverrideConfiguration);
        }
    }

    void unregisterConfigurationChangeListener(ConfigurationContainerListener listener) {
        mChangeListeners.remove(listener);
    }

    @VisibleForTesting
    boolean containsListener(ConfigurationContainerListener listener) {
        return mChangeListeners.contains(listener);
    }

    /**
     * Must be called when new parent for the container was set.
     */
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        // Removing parent usually means that we've detached this entity to destroy it or to attach
        // to another parent. In both cases we don't need to update the configuration now.
        if (newParent != null) {
            // Update full configuration of this container and all its children.
            onConfigurationChanged(newParent.mFullConfiguration);
        }
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at
     * {@link com.android.server.wm.ConfigurationContainerProto}.
     *
     * @param proto    Stream to write the ConfigurationContainer object to.
     * @param fieldId  Field Id of the ConfigurationContainer as defined in the parent
     *                 message.
     * @param logLevel Determines the amount of data to be written to the Protobuf.
     * @hide
     */
    @CallSuper
    protected void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        final long token = proto.start(fieldId);

        if (logLevel == WindowTraceLogLevel.ALL || mHasOverrideConfiguration) {
            mRequestedOverrideConfiguration.dumpDebug(proto, OVERRIDE_CONFIGURATION,
                    logLevel == WindowTraceLogLevel.CRITICAL);
        }

        // Unless trace level is set to `WindowTraceLogLevel.ALL` don't dump anything that isn't
        // required to mitigate performance overhead
        if (logLevel == WindowTraceLogLevel.ALL) {
            mFullConfiguration.dumpDebug(proto, FULL_CONFIGURATION, false /* critical */);
            mMergedOverrideConfiguration.dumpDebug(proto, MERGED_OVERRIDE_CONFIGURATION,
                    false /* critical */);
        }

        if (logLevel == WindowTraceLogLevel.TRIM) {
            // Required for Fass to automatically detect pip transitions in Winscope traces
            dumpDebugWindowingMode(proto);
        }

        proto.end(token);
    }

    private void dumpDebugWindowingMode(ProtoOutputStream proto) {
        final long fullConfigToken = proto.start(FULL_CONFIGURATION);
        final long windowConfigToken = proto.start(WINDOW_CONFIGURATION);

        int windowingMode = mFullConfiguration.windowConfiguration.getWindowingMode();
        proto.write(WINDOWING_MODE, windowingMode);

        proto.end(windowConfigToken);
        proto.end(fullConfigToken);
    }

    /**
     * Dumps the names of this container children in the input print writer indenting each
     * level with the input prefix.
     */
    public void dumpChildrenNames(PrintWriter pw, String prefix) {
        dumpChildrenNames(pw, prefix, true /* isLastChild */);
    }

    /**
     * Dumps the names of this container children in the input print writer indenting each
     * level with the input prefix.
     */
    public void dumpChildrenNames(PrintWriter pw, String prefix, boolean isLastChild) {
        int curWinMode = getWindowingMode();
        String winMode = windowingModeToString(curWinMode);
        if (curWinMode != WINDOWING_MODE_UNDEFINED &&
                curWinMode != WINDOWING_MODE_FULLSCREEN) {
            winMode = winMode.toUpperCase();
        }
        int requestedWinMode = getRequestedOverrideWindowingMode();
        String overrideWinMode = windowingModeToString(requestedWinMode);
        if (requestedWinMode != WINDOWING_MODE_UNDEFINED &&
                requestedWinMode != WINDOWING_MODE_FULLSCREEN) {
            overrideWinMode = overrideWinMode.toUpperCase();
        }
        String actType = activityTypeToString(getActivityType());
        if (getActivityType() != ACTIVITY_TYPE_UNDEFINED
                && getActivityType() != ACTIVITY_TYPE_STANDARD) {
            actType = actType.toUpperCase();
        }
        pw.print(prefix + (isLastChild ? "└─ " : "├─ "));
        pw.println(getName()
                + " type=" + actType
                + " mode=" + winMode
                + " override-mode=" + overrideWinMode
                + " requested-bounds=" + getRequestedOverrideBounds().toShortString()
                + " bounds=" + getBounds().toShortString());

        String childPrefix = prefix + (isLastChild ? "   " : "│  ");
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final E cc = getChildAt(i);
            cc.dumpChildrenNames(pw, childPrefix, i == 0 /* isLastChild */);
        }
    }

    String getName() {
        return toString();
    }

    public boolean isAlwaysOnTop() {
        return mFullConfiguration.windowConfiguration.isAlwaysOnTop();
    }

    boolean hasChild() {
        return getChildCount() > 0;
    }

    abstract protected int getChildCount();

    abstract protected E getChildAt(int index);

    abstract protected ConfigurationContainer getParent();

}
