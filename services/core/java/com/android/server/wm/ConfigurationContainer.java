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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.app.WindowConfiguration.windowingModeToString;
import static com.android.server.wm.ConfigurationContainerProto.FULL_CONFIGURATION;
import static com.android.server.wm.ConfigurationContainerProto.MERGED_OVERRIDE_CONFIGURATION;
import static com.android.server.wm.ConfigurationContainerProto.OVERRIDE_CONFIGURATION;

import android.annotation.CallSuper;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Contains common logic for classes that have override configurations and are organized in a
 * hierarchy.
 */
public abstract class ConfigurationContainer<E extends ConfigurationContainer> {
    /**
     * {@link #Rect} returned from {@link #getOverrideBounds()} to prevent original value from being
     * set directly.
     */
    private Rect mReturnBounds = new Rect();

    /** Contains override configuration settings applied to this configuration container. */
    private Configuration mOverrideConfiguration = new Configuration();

    /** True if mOverrideConfiguration is not empty */
    private boolean mHasOverrideConfiguration;

    /**
     * Contains full configuration applied to this configuration container. Corresponds to full
     * parent's config with applied {@link #mOverrideConfiguration}.
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
    private final Configuration mTmpConfig = new Configuration();

    // Used for setting bounds
    private final Rect mTmpRect = new Rect();

    static final int BOUNDS_CHANGE_NONE = 0;
    // Return value from {@link setBounds} indicating the position of the override bounds changed.
    static final int BOUNDS_CHANGE_POSITION = 1;
    // Return value from {@link setBounds} indicating the size of the override bounds changed.
    static final int BOUNDS_CHANGE_SIZE = 1 << 1;


    /**
     * Returns full configuration applied to this configuration container.
     * This method should be used for getting settings applied in each particular level of the
     * hierarchy.
     */
    public Configuration getConfiguration() {
        return mFullConfiguration;
    }

    /**
     * Notify that parent config changed and we need to update full configuration.
     * @see #mFullConfiguration
     */
    public void onConfigurationChanged(Configuration newParentConfig) {
        mFullConfiguration.setTo(newParentConfig);
        mFullConfiguration.updateFrom(mOverrideConfiguration);
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ConfigurationContainer child = getChildAt(i);
            child.onConfigurationChanged(mFullConfiguration);
        }
    }

    /** Returns override configuration applied to this configuration container. */
    public Configuration getOverrideConfiguration() {
        return mOverrideConfiguration;
    }

    /**
     * Update override configuration and recalculate full config.
     * @see #mOverrideConfiguration
     * @see #mFullConfiguration
     */
    public void onOverrideConfigurationChanged(Configuration overrideConfiguration) {
        // Pre-compute this here, so we don't need to go through the entire Configuration when
        // writing to proto (which has significant cost if we write a lot of empty configurations).
        mHasOverrideConfiguration = !Configuration.EMPTY.equals(overrideConfiguration);
        mOverrideConfiguration.setTo(overrideConfiguration);
        // Update full configuration of this container and all its children.
        final ConfigurationContainer parent = getParent();
        onConfigurationChanged(parent != null ? parent.getConfiguration() : Configuration.EMPTY);
        // Update merged override config of this container and all its children.
        onMergedOverrideConfigurationChanged();

        // Use the updated override configuration to notify listeners.
        mTmpConfig.setTo(mOverrideConfiguration);
        // Inform listeners of the change.
        for (int i = mChangeListeners.size() - 1; i >=0; --i) {
            mChangeListeners.get(i).onOverrideConfigurationChanged(mTmpConfig);
        }
    }

    /**
     * Get merged override configuration from the top of the hierarchy down to this particular
     * instance. This should be reported to client as override config.
     */
    public Configuration getMergedOverrideConfiguration() {
        return mMergedOverrideConfiguration;
    }

    /**
     * Update merged override configuration based on corresponding parent's config and notify all
     * its children. If there is no parent, merged override configuration will set equal to current
     * override config.
     * @see #mMergedOverrideConfiguration
     */
    void onMergedOverrideConfigurationChanged() {
        final ConfigurationContainer parent = getParent();
        if (parent != null) {
            mMergedOverrideConfiguration.setTo(parent.getMergedOverrideConfiguration());
            mMergedOverrideConfiguration.updateFrom(mOverrideConfiguration);
        } else {
            mMergedOverrideConfiguration.setTo(mOverrideConfiguration);
        }
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ConfigurationContainer child = getChildAt(i);
            child.onMergedOverrideConfigurationChanged();
        }
    }

    /**
     * Indicates whether this container has not specified any bounds different from its parent. In
     * this case, it will inherit the bounds of the first ancestor which specifies a bounds.
     * @return {@code true} if no explicit bounds have been set at this container level.
     *         {@code false} otherwise.
     */
    public boolean matchParentBounds() {
        return getOverrideBounds().isEmpty();
    }

    /**
     * Returns whether the bounds specified is considered the same as the existing override bounds.
     * This is either when the two bounds are equal or the override bounds is empty and the
     * specified bounds is null.
     *
     * @return {@code true} if the bounds are equivalent, {@code false} otherwise
     */
    public boolean equivalentOverrideBounds(Rect bounds) {
        return equivalentBounds(getOverrideBounds(),  bounds);
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
     * @return
     */
    public Rect getBounds() {
        mReturnBounds.set(getConfiguration().windowConfiguration.getBounds());
        return mReturnBounds;
    }

    public void getBounds(Rect outBounds) {
        outBounds.set(getBounds());
    }

    /**
     * Returns the current bounds explicitly set on this container. The {@link Rect} handed back is
     * shared for all calls to this method and should not be modified.
     */
    public Rect getOverrideBounds() {
        mReturnBounds.set(getOverrideConfiguration().windowConfiguration.getBounds());

        return mReturnBounds;
    }

    /**
     * Returns {@code true} if the {@link WindowConfiguration} in the override
     * {@link Configuration} specifies bounds.
     */
    public boolean hasOverrideBounds() {
        return !getOverrideBounds().isEmpty();
    }

    /**
     * Sets the passed in {@link Rect} to the current bounds.
     * @see {@link #getOverrideBounds()}.
     */
    public void getOverrideBounds(Rect outBounds) {
        outBounds.set(getOverrideBounds());
    }

    /**
     * Sets the bounds at the current hierarchy level, overriding any bounds set on an ancestor.
     * This value will be reported when {@link #getBounds()} and {@link #getOverrideBounds()}. If
     * an empty {@link Rect} or null is specified, this container will be considered to match its
     * parent bounds {@see #matchParentBounds} and will inherit bounds from its parent.
     * @param bounds The bounds defining the container size.
     * @return a bitmask representing the types of changes made to the bounds.
     */
    public int setBounds(Rect bounds) {
        int boundsChange = diffOverrideBounds(bounds);

        if (boundsChange == BOUNDS_CHANGE_NONE) {
            return boundsChange;
        }


        mTmpConfig.setTo(getOverrideConfiguration());
        mTmpConfig.windowConfiguration.setBounds(bounds);
        onOverrideConfigurationChanged(mTmpConfig);

        return boundsChange;
    }

    public int setBounds(int left, int top, int right, int bottom) {
        mTmpRect.set(left, top, right, bottom);
        return setBounds(mTmpRect);
    }

    int diffOverrideBounds(Rect bounds) {
        if (equivalentOverrideBounds(bounds)) {
            return BOUNDS_CHANGE_NONE;
        }

        int boundsChange = BOUNDS_CHANGE_NONE;

        final Rect existingBounds = getOverrideBounds();

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

    /** Sets the windowing mode for the configuration container. */
    public void setWindowingMode(/*@WindowConfiguration.WindowingMode*/ int windowingMode) {
        mTmpConfig.setTo(getOverrideConfiguration());
        mTmpConfig.windowConfiguration.setWindowingMode(windowingMode);
        onOverrideConfigurationChanged(mTmpConfig);
    }

    /**
     * Returns true if this container is currently in multi-window mode. I.e. sharing the screen
     * with another activity.
     */
    public boolean inMultiWindowMode() {
        /*@WindowConfiguration.WindowingMode*/ int windowingMode =
                mFullConfiguration.windowConfiguration.getWindowingMode();
        return windowingMode != WINDOWING_MODE_FULLSCREEN
                && windowingMode != WINDOWING_MODE_UNDEFINED;
    }

    /** Returns true if this container is currently in split-screen windowing mode. */
    public boolean inSplitScreenWindowingMode() {
        /*@WindowConfiguration.WindowingMode*/ int windowingMode =
                mFullConfiguration.windowConfiguration.getWindowingMode();

        return windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
    }

    /** Returns true if this container is currently in split-screen secondary windowing mode. */
    public boolean inSplitScreenSecondaryWindowingMode() {
        /*@WindowConfiguration.WindowingMode*/ int windowingMode =
                mFullConfiguration.windowConfiguration.getWindowingMode();

        return windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
    }

    public boolean inSplitScreenPrimaryWindowingMode() {
        return mFullConfiguration.windowConfiguration.getWindowingMode()
                == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
    }

    /**
     * Returns true if this container can be put in either
     * {@link WindowConfiguration#WINDOWING_MODE_SPLIT_SCREEN_PRIMARY} or
     * {@link WindowConfiguration##WINDOWING_MODE_SPLIT_SCREEN_SECONDARY} windowing modes based on
     * its current state.
     */
    public boolean supportsSplitScreenWindowingMode() {
        return mFullConfiguration.windowConfiguration.supportSplitScreenWindowingMode();
    }

    public boolean inPinnedWindowingMode() {
        return mFullConfiguration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }

    public boolean inFreeformWindowingMode() {
        return mFullConfiguration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_FREEFORM;
    }

    /** Returns the activity type associated with the the configuration container. */
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
        mTmpConfig.setTo(getOverrideConfiguration());
        mTmpConfig.windowConfiguration.setActivityType(activityType);
        onOverrideConfigurationChanged(mTmpConfig);
    }

    public boolean isActivityTypeHome() {
        return getActivityType() == ACTIVITY_TYPE_HOME;
    }

    public boolean isActivityTypeRecents() {
        return getActivityType() == ACTIVITY_TYPE_RECENTS;
    }

    public boolean isActivityTypeAssistant() {
        return getActivityType() == ACTIVITY_TYPE_ASSISTANT;
    }

    public boolean isActivityTypeStandard() {
        return getActivityType() == ACTIVITY_TYPE_STANDARD;
    }

    public boolean isActivityTypeStandardOrUndefined() {
        /*@WindowConfiguration.ActivityType*/ final int activityType = getActivityType();
        return activityType == ACTIVITY_TYPE_STANDARD || activityType == ACTIVITY_TYPE_UNDEFINED;
    }

    public boolean hasCompatibleActivityType(ConfigurationContainer other) {
        /*@WindowConfiguration.ActivityType*/ int thisType = getActivityType();
        /*@WindowConfiguration.ActivityType*/ int otherType = other.getActivityType();

        if (thisType == otherType) {
            return true;
        }
        if (thisType == ACTIVITY_TYPE_ASSISTANT) {
            // Assistant activities are only compatible with themselves...
            return false;
        }
        // Otherwise we are compatible if us or other is not currently defined.
        return thisType == ACTIVITY_TYPE_UNDEFINED || otherType == ACTIVITY_TYPE_UNDEFINED;
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

    public void registerConfigurationChangeListener(ConfigurationContainerListener listener) {
        if (mChangeListeners.contains(listener)) {
            return;
        }
        mChangeListeners.add(listener);
        listener.onOverrideConfigurationChanged(mOverrideConfiguration);
    }

    public void unregisterConfigurationChangeListener(ConfigurationContainerListener listener) {
        mChangeListeners.remove(listener);
    }

    /**
     * Must be called when new parent for the container was set.
     */
    protected void onParentChanged() {
        final ConfigurationContainer parent = getParent();
        // Removing parent usually means that we've detached this entity to destroy it or to attach
        // to another parent. In both cases we don't need to update the configuration now.
        if (parent != null) {
            // Update full configuration of this container and all its children.
            onConfigurationChanged(parent.mFullConfiguration);
            // Update merged override configuration of this container and all its children.
            onMergedOverrideConfigurationChanged();
        }
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at
     * {@link com.android.server.wm.ConfigurationContainerProto}.
     *
     * @param proto    Stream to write the ConfigurationContainer object to.
     * @param fieldId  Field Id of the ConfigurationContainer as defined in the parent
     *                 message.
     * @param trim     If true, reduce amount of data written.
     * @hide
     */
    @CallSuper
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        final long token = proto.start(fieldId);
        if (!trim || mHasOverrideConfiguration) {
            mOverrideConfiguration.writeToProto(proto, OVERRIDE_CONFIGURATION);
        }
        if (!trim) {
            mFullConfiguration.writeToProto(proto, FULL_CONFIGURATION);
            mMergedOverrideConfiguration.writeToProto(proto, MERGED_OVERRIDE_CONFIGURATION);
        }
        proto.end(token);
    }

    /**
     * Dumps the names of this container children in the input print writer indenting each
     * level with the input prefix.
     */
    public void dumpChildrenNames(PrintWriter pw, String prefix) {
        final String childPrefix = prefix + " ";
        pw.println(getName()
                + " type=" + activityTypeToString(getActivityType())
                + " mode=" + windowingModeToString(getWindowingMode()));
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final E cc = getChildAt(i);
            pw.print(childPrefix + "#" + i + " ");
            cc.dumpChildrenNames(pw, childPrefix);
        }
    }

    String getName() {
        return toString();
    }

    boolean isAlwaysOnTop() {
        return mFullConfiguration.windowConfiguration.isAlwaysOnTop();
    }

    abstract protected int getChildCount();

    abstract protected E getChildAt(int index);

    abstract protected ConfigurationContainer getParent();
}
