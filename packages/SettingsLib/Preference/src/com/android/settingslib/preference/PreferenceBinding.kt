/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.preference

import android.content.Context
import androidx.preference.DialogPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import com.android.settingslib.metadata.DiscreteIntValue
import com.android.settingslib.metadata.DiscreteValue
import com.android.settingslib.metadata.PreferenceAvailabilityProvider
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceScreenMetadata
import com.android.settingslib.metadata.RangeValue

/** Binding of preference widget and preference metadata. */
interface PreferenceBinding {

    /**
     * Provides a new [Preference] widget instance.
     *
     * By default, it returns a new [Preference] object. Subclass could override this method to
     * provide customized widget and do **one-off** initialization (e.g.
     * [Preference.setOnPreferenceClickListener]). To update widget everytime when state is changed,
     * override the [bind] method.
     *
     * Notes:
     * - DO NOT set any properties defined in [PreferenceMetadata]. For example,
     *   title/summary/icon/extras/isEnabled/isVisible/isPersistent/dependency. These properties
     *   will be reset by [bind].
     * - Override [bind] if needed to provide more information for customized widget.
     */
    fun createWidget(context: Context): Preference = Preference(context)

    /**
     * Binds preference widget with given metadata.
     *
     * Whenever metadata state is changed, this callback is invoked to update widget. By default,
     * the common states like title, summary, enabled, etc. are already applied. Subclass should
     * override this method to bind more data (e.g. read preference value from storage and apply it
     * to widget).
     *
     * @param preference preference widget created by [createWidget]
     * @param metadata metadata to apply
     */
    fun bind(preference: Preference, metadata: PreferenceMetadata) {
        metadata.apply {
            preference.key = key
            if (icon != 0) {
                preference.setIcon(icon)
            } else {
                preference.icon = null
            }
            val context = preference.context
            preference.peekExtras()?.clear()
            extras(context)?.let { preference.extras.putAll(it) }
            preference.title = getPreferenceTitle(context)
            preference.summary = getPreferenceSummary(context)
            preference.isEnabled = isEnabled(context)
            preference.isVisible =
                (this as? PreferenceAvailabilityProvider)?.isAvailable(context) != false
            preference.isPersistent = isPersistent(context)
            metadata.order(context)?.let { preference.order = it }
            // PreferenceRegistry will notify dependency change, so we do not need to set
            // dependency here. This simplifies dependency management and avoid the
            // IllegalStateException when call Preference.setDependency
            preference.dependency = null
            if (preference !is PreferenceScreen) { // avoid recursive loop when build graph
                preference.fragment = (this as? PreferenceScreenCreator)?.fragmentClass()?.name
                preference.intent = intent(context)
            }
            if (preference is DialogPreference) {
                preference.dialogTitle = preference.title
            }
            if (preference is ListPreference && this is DiscreteValue<*>) {
                preference.setEntries(valuesDescription)
                if (this is DiscreteIntValue) {
                    val intValues = context.resources.getIntArray(values)
                    preference.entryValues = Array(intValues.size) { intValues[it].toString() }
                } else {
                    preference.setEntryValues(values)
                }
            } else if (preference is SeekBarPreference && this is RangeValue) {
                preference.min = minValue
                preference.max = maxValue
                preference.seekBarIncrement = incrementStep
            }
        }
    }
}

/** Abstract preference screen to provide preference hierarchy and binding factory. */
interface PreferenceScreenCreator : PreferenceScreenMetadata, PreferenceScreenProvider {

    /** Returns if the flag (e.g. for rollout) is enabled on current screen. */
    fun isFlagEnabled(context: Context): Boolean = true

    val preferenceBindingFactory: PreferenceBindingFactory
        get() = DefaultPreferenceBindingFactory

    override fun createPreferenceScreen(factory: PreferenceScreenFactory) =
        factory.getOrCreatePreferenceScreen().apply {
            inflatePreferenceHierarchy(preferenceBindingFactory, getPreferenceHierarchy(context))
        }
}
