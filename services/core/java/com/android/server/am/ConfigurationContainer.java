/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.am;

import android.content.res.Configuration;

/**
 * Contains common logic for classes that have override configurations and are organized in a
 * hierarchy.
 */
// TODO(b/36505427): Move to wm package and have WindowContainer use this instead of having its own
// implementation for merging configuration.
abstract class ConfigurationContainer<E extends ConfigurationContainer> {

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

    /**
     * Returns full configuration applied to this configuration container.
     * This method should be used for getting settings applied in each particular level of the
     * hierarchy.
     */
    Configuration getConfiguration() {
        return mFullConfiguration;
    }

    /**
     * Notify that parent config changed and we need to update full configuration.
     * @see #mFullConfiguration
     */
    void onConfigurationChanged(Configuration newParentConfig) {
        mFullConfiguration.setTo(newParentConfig);
        mFullConfiguration.updateFrom(mOverrideConfiguration);
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ConfigurationContainer child = getChildAt(i);
            child.onConfigurationChanged(mFullConfiguration);
        }
    }

    /** Returns override configuration applied to this configuration container. */
    Configuration getOverrideConfiguration() {
        return mOverrideConfiguration;
    }

    /**
     * Update override configuration and recalculate full config.
     * @see #mOverrideConfiguration
     * @see #mFullConfiguration
     */
    void onOverrideConfigurationChanged(Configuration overrideConfiguration) {
        mOverrideConfiguration.setTo(overrideConfiguration);
        // Update full configuration of this container and all its children.
        final ConfigurationContainer parent = getParent();
        onConfigurationChanged(parent != null ? parent.getConfiguration() : Configuration.EMPTY);
        // Update merged override config of this container and all its children.
        onMergedOverrideConfigurationChanged();
    }

    /**
     * Get merged override configuration from the top of the hierarchy down to this particular
     * instance. This should be reported to client as override config.
     */
    Configuration getMergedOverrideConfiguration() {
        return mMergedOverrideConfiguration;
    }

    /**
     * Update merged override configuration based on corresponding parent's config and notify all
     * its children. If there is no parent, merged override configuration will set equal to current
     * override config.
     * @see #mMergedOverrideConfiguration
     */
    private void onMergedOverrideConfigurationChanged() {
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
     * Must be called when new parent for the container was set.
     */
    void onParentChanged() {
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

    abstract protected int getChildCount();

    abstract protected E getChildAt(int index);

    abstract protected ConfigurationContainer getParent();
}
