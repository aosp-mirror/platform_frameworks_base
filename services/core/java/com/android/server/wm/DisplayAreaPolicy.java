/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import android.content.res.Resources;
import android.text.TextUtils;

/**
 * Policy that manages DisplayAreas.
 */
public abstract class DisplayAreaPolicy {
    protected final WindowManagerService mWmService;
    protected final DisplayContent mContent;

    /**
     * The root DisplayArea. Attach all DisplayAreas to this area (directly or indirectly).
     */
    protected final DisplayArea.Root mRoot;

    /**
     * The IME container. The IME's windows are automatically added to this container.
     */
    protected final DisplayArea<? extends WindowContainer> mImeContainer;

    /**
     * The Tasks container. Tasks etc. are automatically added to this container.
     */
    protected final DisplayArea<? extends ActivityStack> mTaskContainers;

    /**
     * Construct a new {@link DisplayAreaPolicy}
     *
     * @param wmService the window manager service instance
     * @param content the display content for which the policy applies
     * @param root the root display area under which the policy operates
     * @param imeContainer the ime container that the policy must attach
     * @param taskContainers the task container that the policy must attach
     *
     * @see #attachDisplayAreas()
     */
    protected DisplayAreaPolicy(WindowManagerService wmService,
            DisplayContent content, DisplayArea.Root root,
            DisplayArea<? extends WindowContainer> imeContainer,
            DisplayArea<? extends ActivityStack> taskContainers) {
        mWmService = wmService;
        mContent = content;
        mRoot = root;
        mImeContainer = imeContainer;
        mTaskContainers = taskContainers;
    }

    /**
     * Called to ask the policy to set up the DisplayArea hierarchy. At a minimum this must:
     *
     * - attach mImeContainer to mRoot (or one of its descendants)
     * - attach mTaskStacks to mRoot (or one of its descendants)
     *
     * Additionally, this is the right place to set up any other DisplayAreas as desired.
     */
    public abstract void attachDisplayAreas();

    /**
     * Called to ask the policy to attach the given WindowToken to the DisplayArea hierarchy.
     *
     * This must attach the token to mRoot (or one of its descendants).
     */
    public abstract void addWindow(WindowToken token);

    /** Provider for platform-default display area policy. */
    static final class DefaultProvider implements DisplayAreaPolicy.Provider {
        @Override
        public DisplayAreaPolicy instantiate(WindowManagerService wmService,
                DisplayContent content, DisplayArea.Root root,
                DisplayArea<? extends WindowContainer> imeContainer,
                TaskContainers taskContainers) {
            return new DisplayAreaPolicyBuilder()
                    .build(wmService, content, root, imeContainer, taskContainers);
        }
    }

    /**
     * Provider for {@link DisplayAreaPolicy} instances.
     *
     * By implementing this interface and overriding the
     * {@code config_deviceSpecificDisplayAreaPolicyProvider}, a device-specific implementations
     * of {@link DisplayAreaPolicy} can be supplied.
     */
    public interface Provider {
        /**
         * Instantiate a new DisplayAreaPolicy.
         *
         * @see DisplayAreaPolicy#DisplayAreaPolicy
         */
        DisplayAreaPolicy instantiate(WindowManagerService wmService,
                DisplayContent content, DisplayArea.Root root,
                DisplayArea<? extends WindowContainer> imeContainer,
                TaskContainers taskContainers);

        /**
         * Instantiate the device-specific {@link Provider}.
         */
        static Provider fromResources(Resources res) {
            String name = res.getString(
                    com.android.internal.R.string.config_deviceSpecificDisplayAreaPolicyProvider);
            if (TextUtils.isEmpty(name)) {
                return new DisplayAreaPolicy.DefaultProvider();
            }
            try {
                return (Provider) Class.forName(name).newInstance();
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new IllegalStateException("Couldn't instantiate class " + name
                        + " for config_deviceSpecificDisplayAreaPolicyProvider:"
                        + " make sure it has a public zero-argument constructor"
                        + " and implements DisplayAreaPolicy.Provider", e);
            }
        }
    }
}
