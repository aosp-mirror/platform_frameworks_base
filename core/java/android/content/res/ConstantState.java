/*
* Copyright (C) 2014 The Android Open Source Project
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
package android.content.res;

/**
 * A cache class that can provide new instances of a particular resource which may change
 * depending on the current {@link Resources.Theme} or {@link Configuration}.
 * <p>
 * A constant state should be able to return a bitmask of changing configurations, which
 * identifies the type of configuration changes that may invalidate this resource. These
 * configuration changes can be obtained from {@link android.util.TypedValue}. Entities such as
 * {@link android.animation.Animator} also provide a changing configuration method to include
 * their dependencies (e.g. An AnimatorSet's changing configuration is the union of the
 * changing configurations of each Animator in the set)
 * @hide
 */
abstract public class ConstantState<T> {

    /**
     * Return a bit mask of configuration changes that will impact
     * this resource (and thus require completely reloading it).
     */
    abstract public int getChangingConfigurations();

    /**
     * Create a new instance without supplying resources the caller
     * is running in.
     */
    public abstract T newInstance();

    /**
     * Create a new instance from its constant state.  This
     * must be implemented for resources that change based on the target
     * density of their caller (that is depending on whether it is
     * in compatibility mode).
     */
    public T newInstance(Resources res) {
        return newInstance();
    }

    /**
     * Create a new instance from its constant state.  This must be
     * implemented for resources that can have a theme applied.
     */
    public T newInstance(Resources res, Resources.Theme theme) {
        return newInstance(res);
    }
}
