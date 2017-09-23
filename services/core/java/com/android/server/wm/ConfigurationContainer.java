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
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.activityTypeToString;
import static com.android.server.wm.proto.ConfigurationContainerProto.FULL_CONFIGURATION;
import static com.android.server.wm.proto.ConfigurationContainerProto.MERGED_OVERRIDE_CONFIGURATION;
import static com.android.server.wm.proto.ConfigurationContainerProto.OVERRIDE_CONFIGURATION;

import android.annotation.CallSuper;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.util.proto.ProtoOutputStream;

import java.util.ArrayList;

/**
 * Contains common logic for classes that have override configurations and are organized in a
 * hierarchy.
 */
public abstract class ConfigurationContainer<E extends ConfigurationContainer> {

    /** Contains override configuration settings applied to this configuration container. */
    private Configuration mOverrideConfiguration = new Configuration();

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
        mOverrideConfiguration.setTo(overrideConfiguration);
        // Update full configuration of this container and all its children.
        final ConfigurationContainer parent = getParent();
        onConfigurationChanged(parent != null ? parent.getConfiguration() : Configuration.EMPTY);
        // Update merged override config of this container and all its children.
        onMergedOverrideConfigurationChanged();

        // Inform listeners of the change.
        for (int i = mChangeListeners.size() - 1; i >=0; --i) {
            mChangeListeners.get(i).onOverrideConfigurationChanged(overrideConfiguration);
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

    /**
     * Returns true if this container can be put in either
     * {@link WindowConfiguration#WINDOWING_MODE_SPLIT_SCREEN_PRIMARY} or
     * {@link WindowConfiguration##WINDOWING_MODE_SPLIT_SCREEN_SECONDARY} windowing modes based on
     * its current state.
     */
    public boolean supportSplitScreenWindowingMode() {
        return mFullConfiguration.windowConfiguration.supportSplitScreenWindowingMode();
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
     * {@link com.android.server.wm.proto.ConfigurationContainerProto}.
     *
     * @param protoOutputStream Stream to write the ConfigurationContainer object to.
     * @param fieldId           Field Id of the ConfigurationContainer as defined in the parent
     *                          message.
     * @hide
     */
    @CallSuper
    public void writeToProto(ProtoOutputStream protoOutputStream, long fieldId) {
        final long token = protoOutputStream.start(fieldId);
        mOverrideConfiguration.writeToProto(protoOutputStream, OVERRIDE_CONFIGURATION);
        mFullConfiguration.writeToProto(protoOutputStream, FULL_CONFIGURATION);
        mMergedOverrideConfiguration.writeToProto(protoOutputStream, MERGED_OVERRIDE_CONFIGURATION);
        protoOutputStream.end(token);
    }

    abstract protected int getChildCount();

    abstract protected E getChildAt(int index);

    abstract protected ConfigurationContainer getParent();
}
