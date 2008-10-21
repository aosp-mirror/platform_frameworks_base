/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.preference;

/**
 * Interface definition for a callback to be invoked when this
 * {@link Preference} changes with respect to enabling/disabling
 * dependents.
 */
interface OnDependencyChangeListener {
    /**
     * Called when this preference has changed in a way that dependents should
     * care to change their state.
     * 
     * @param disablesDependent Whether the dependent should be disabled.
     */
    void onDependencyChanged(Preference dependency, boolean disablesDependent);
}
