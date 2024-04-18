package com.android.settingslib.graph

import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen

/** Manager to create and initialize preference screen. */
class PreferenceScreenManager(private val preferenceManager: PreferenceManager) {
    private val context = preferenceManager.context
    // the map will preserve order
    private val updaters = mutableMapOf<String, PreferenceUpdater>()
    private val screenUpdaters = mutableListOf<PreferenceScreenUpdater>()

    /** Creates an empty [PreferenceScreen]. */
    fun createPreferenceScreen(): PreferenceScreen =
        preferenceManager.createPreferenceScreen(context)

    /** Creates [PreferenceScreen] from resource. */
    fun createPreferenceScreen(@XmlRes xmlRes: Int): PreferenceScreen =
        preferenceManager.inflateFromResource(context, xmlRes, null)

    /** Adds updater for given preference. */
    fun addPreferenceUpdater(@StringRes key: Int, updater: PreferenceUpdater) =
        addPreferenceUpdater(context.getString(key), updater)

    /** Adds updater for given preference. */
    fun addPreferenceUpdater(
        key: String,
        updater: PreferenceUpdater,
    ): PreferenceScreenManager {
        updaters.put(key, updater)?.let { if (it != updater) throw IllegalArgumentException() }
        return this
    }

    /** Adds updater for preference screen. */
    fun addPreferenceScreenUpdater(updater: PreferenceScreenUpdater): PreferenceScreenManager {
        screenUpdaters.add(updater)
        return this
    }

    /** Adds a list of updaters for preference screen. */
    fun addPreferenceScreenUpdater(
        vararg updaters: PreferenceScreenUpdater,
    ): PreferenceScreenManager {
        screenUpdaters.addAll(updaters)
        return this
    }

    /** Updates preference screen with registered updaters. */
    fun updatePreferenceScreen(preferenceScreen: PreferenceScreen) {
        for ((key, updater) in updaters) {
            preferenceScreen.findPreference<Preference>(key)?.let { updater.updatePreference(it) }
        }
        for (updater in screenUpdaters) {
            updater.updatePreferenceScreen(preferenceScreen)
        }
    }
}

/** Updater of [Preference]. */
interface PreferenceUpdater {
    fun updatePreference(preference: Preference)
}

/** Updater of [PreferenceScreen]. */
interface PreferenceScreenUpdater {
    fun updatePreferenceScreen(preferenceScreen: PreferenceScreen)
}
