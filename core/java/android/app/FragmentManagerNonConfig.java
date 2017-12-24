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
 * limitations under the License.
 */


package android.app;

import java.util.List;

/**
 * FragmentManagerNonConfig stores the retained instance fragments across
 * activity recreation events.
 *
 * <p>Apps should treat objects of this type as opaque, returned by
 * and passed to the state save and restore process for fragments in
 * {@link FragmentController#retainNonConfig()} and
 * {@link FragmentController#restoreAllState(Parcelable, FragmentManagerNonConfig)}.</p>
 *
 * @deprecated Use the <a href="{@docRoot}tools/extras/support-library.html">Support Library</a>
 *      {@link android.support.v4.app.FragmentManagerNonConfig}
 */
@Deprecated
public class FragmentManagerNonConfig {
    private final List<Fragment> mFragments;
    private final List<FragmentManagerNonConfig> mChildNonConfigs;

    FragmentManagerNonConfig(List<Fragment> fragments,
            List<FragmentManagerNonConfig> childNonConfigs) {
        mFragments = fragments;
        mChildNonConfigs = childNonConfigs;
    }

    /**
     * @return the retained instance fragments returned by a FragmentManager
     */
    List<Fragment> getFragments() {
        return mFragments;
    }

    /**
     * @return the FragmentManagerNonConfigs from any applicable fragment's child FragmentManager
     */
    List<FragmentManagerNonConfig> getChildNonConfigs() {
        return mChildNonConfigs;
    }
}
