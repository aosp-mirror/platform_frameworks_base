package com.android.settingslib.graph

import android.content.Context
import androidx.preference.PreferenceScreen

/**
 * Interface to provide [PreferenceScreen].
 *
 * It is expected to be implemented by Activity/Fragment and the implementation needs to use
 * [Context] APIs (e.g. `getContext()`, `getActivity()`) with caution: preference screen creation
 * could happen in background service, where the Activity/Fragment lifecycle callbacks (`onCreate`,
 * `onDestroy`, etc.) are not invoked.
 */
interface PreferenceScreenProvider {

    /**
     * Creates [PreferenceScreen].
     *
     * Preference screen creation could happen in background service. The implementation MUST use
     * given [context] instead of APIs like `getContext()`, `getActivity()`, etc.
     */
    fun createPreferenceScreen(
        context: Context,
        preferenceScreenManager: PreferenceScreenManager,
    ): PreferenceScreen?
}
