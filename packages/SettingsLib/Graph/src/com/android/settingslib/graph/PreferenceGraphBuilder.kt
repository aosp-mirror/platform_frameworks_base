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

@file:Suppress("DEPRECATION")

package com.android.settingslib.graph

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceActivity
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.android.settingslib.graph.proto.PreferenceGraphProto
import com.android.settingslib.graph.proto.PreferenceGroupProto
import com.android.settingslib.graph.proto.PreferenceProto
import com.android.settingslib.graph.proto.PreferenceProto.ActionTarget
import com.android.settingslib.graph.proto.PreferenceScreenProto
import com.android.settingslib.graph.proto.TextProto
import com.android.settingslib.metadata.BooleanValue
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceAvailabilityProvider
import com.android.settingslib.metadata.PreferenceHierarchy
import com.android.settingslib.metadata.PreferenceHierarchyNode
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceRestrictionProvider
import com.android.settingslib.metadata.PreferenceScreenBindingKeyProvider
import com.android.settingslib.metadata.PreferenceScreenMetadata
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.android.settingslib.metadata.PreferenceSummaryProvider
import com.android.settingslib.metadata.PreferenceTitleProvider
import com.android.settingslib.preference.PreferenceScreenFactory
import com.android.settingslib.preference.PreferenceScreenProvider
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PreferenceGraphBuilder"

/**
 * Builder of preference graph.
 *
 * Only activity in current application is supported. To create preference graph across
 * applications, use [crawlPreferenceGraph].
 */
class PreferenceGraphBuilder
private constructor(private val context: Context, private val request: GetPreferenceGraphRequest) {
    private val preferenceScreenFactory by lazy {
        PreferenceScreenFactory(context.ofLocale(request.locale))
    }
    private val builder by lazy { PreferenceGraphProto.newBuilder() }
    private val visitedScreens = mutableSetOf<String>().apply { addAll(request.visitedScreens) }
    private val includeValue = request.includeValue

    private suspend fun init() {
        for (activityClass in request.activityClasses) {
            add(activityClass)
        }
    }

    fun build() = builder.build()

    /** Adds an activity to the graph. */
    suspend fun <T> add(activityClass: Class<T>) where T : Activity, T : PreferenceScreenProvider =
        addPreferenceScreenProvider(activityClass)

    /**
     * Adds an activity to the graph.
     *
     * Reflection is used to create the instance. To avoid security vulnerability, the code ensures
     * given [activityClassName] must be declared as an <activity> entry in AndroidManifest.xml.
     */
    suspend fun add(activityClassName: String) {
        try {
            val intent = Intent()
            intent.setClassName(context, activityClassName)
            if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ==
                null) {
                Log.e(TAG, "$activityClassName is not activity")
                return
            }
            val activityClass = context.classLoader.loadClass(activityClassName)
            if (addPreferenceScreenKeyProvider(activityClass)) return
            if (PreferenceScreenProvider::class.java.isAssignableFrom(activityClass)) {
                addPreferenceScreenProvider(activityClass)
            } else {
                Log.w(TAG, "$activityClass does not implement PreferenceScreenProvider")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fail to add $activityClassName", e)
        }
    }

    private suspend fun addPreferenceScreenKeyProvider(activityClass: Class<*>): Boolean {
        if (!PreferenceScreenBindingKeyProvider::class.java.isAssignableFrom(activityClass)) {
            return false
        }
        val key = getPreferenceScreenKey { activityClass.newInstance() } ?: return false
        if (addPreferenceScreenFromRegistry(key, activityClass)) {
            builder.addRoots(key)
            return true
        }
        return false
    }

    private suspend fun getPreferenceScreenKey(newInstance: () -> Any): String? =
        withContext(Dispatchers.Main) {
            try {
                val instance = newInstance()
                if (instance is PreferenceScreenBindingKeyProvider) {
                    return@withContext instance.getPreferenceScreenBindingKey(context)
                } else {
                    Log.w(TAG, "$instance is not PreferenceScreenKeyProvider")
                }
            } catch (e: Exception) {
                Log.e(TAG, "getPreferenceScreenKey failed", e)
            }
            null
        }

    private suspend fun addPreferenceScreenFromRegistry(
        key: String,
        activityClass: Class<*>,
    ): Boolean {
        val metadata = PreferenceScreenRegistry[key] ?: return false
        if (!metadata.hasCompleteHierarchy()) return false
        return addPreferenceScreenMetadata(metadata, activityClass)
    }

    private suspend fun addPreferenceScreenMetadata(
        metadata: PreferenceScreenMetadata,
        activityClass: Class<*>,
    ): Boolean =
        addPreferenceScreen(metadata.key, activityClass) {
            preferenceScreenProto {
                completeHierarchy = true
                root = metadata.getPreferenceHierarchy(context).toProto(activityClass, true)
            }
        }

    private suspend fun addPreferenceScreenProvider(activityClass: Class<*>) {
        Log.d(TAG, "add $activityClass")
        createPreferenceScreen { activityClass.newInstance() }
            ?.let {
                addPreferenceScreen(Intent(context, activityClass), activityClass, it)
                builder.addRoots(it.key)
            }
    }

    /**
     * Creates [PreferenceScreen].
     *
     * Androidx Activity/Fragment instance must be created in main thread, otherwise an exception is
     * raised.
     */
    private suspend fun createPreferenceScreen(newInstance: () -> Any): PreferenceScreen? =
        withContext(Dispatchers.Main) {
            try {
                val instance = newInstance()
                Log.d(TAG, "createPreferenceScreen $instance")
                if (instance is PreferenceScreenProvider) {
                    return@withContext instance.createPreferenceScreen(preferenceScreenFactory)
                } else {
                    Log.w(TAG, "$instance is not PreferenceScreenProvider")
                }
            } catch (e: Exception) {
                Log.e(TAG, "createPreferenceScreen failed", e)
            }
            return@withContext null
        }

    private suspend fun addPreferenceScreen(
        intent: Intent,
        activityClass: Class<*>,
        preferenceScreen: PreferenceScreen?,
    ) {
        val key = preferenceScreen?.key
        if (key.isNullOrEmpty()) {
            Log.e(TAG, "$activityClass \"$preferenceScreen\" has no key")
            return
        }
        @Suppress("CheckReturnValue")
        addPreferenceScreen(key, activityClass) { preferenceScreen.toProto(intent, activityClass) }
    }

    private suspend fun addPreferenceScreen(
        key: String,
        activityClass: Class<*>,
        preferenceScreenProvider: suspend () -> PreferenceScreenProto,
    ): Boolean {
        if (!visitedScreens.add(key)) {
            Log.w(TAG, "$activityClass $key visited")
            return false
        }
        val activityClassName = activityClass.name
        val associatedKey = builder.getActivityScreensOrDefault(activityClassName, null)
        if (associatedKey == null) {
            builder.putActivityScreens(activityClassName, key)
        } else if (associatedKey != key) {
            Log.w(TAG, "Dup $activityClassName association, old: $associatedKey, new: $key")
        }
        builder.putScreens(key, preferenceScreenProvider())
        return true
    }

    private suspend fun PreferenceScreen.toProto(
        intent: Intent,
        activityClass: Class<*>,
    ): PreferenceScreenProto = preferenceScreenProto {
        this.intent = intent.toProto()
        root = (this@toProto as PreferenceGroup).toProto(activityClass)
    }

    private suspend fun PreferenceGroup.toProto(activityClass: Class<*>): PreferenceGroupProto =
        preferenceGroupProto {
            preference = (this@toProto as Preference).toProto(activityClass)
            for (index in 0 until preferenceCount) {
                val child = getPreference(index)
                addPreferences(
                    preferenceOrGroupProto {
                        if (child is PreferenceGroup) {
                            group = child.toProto(activityClass)
                        } else {
                            preference = child.toProto(activityClass)
                        }
                    })
            }
        }

    private suspend fun Preference.toProto(activityClass: Class<*>): PreferenceProto =
        preferenceProto {
            this@toProto.key?.let { key = it }
            this@toProto.title?.let { title = textProto { string = it.toString() } }
            this@toProto.summary?.let { summary = textProto { string = it.toString() } }
            val preferenceExtras = peekExtras()
            preferenceExtras?.let { extras = it.toProto() }
            enabled = isEnabled
            available = isVisible
            persistent = isPersistent
            if (includeValue && isPersistent && this@toProto is TwoStatePreference) {
                value = preferenceValueProto { booleanValue = this@toProto.isChecked }
            }
            this@toProto.fragment.toActionTarget(activityClass, preferenceExtras)?.let {
                actionTarget = it
                return@preferenceProto
            }
            this@toProto.intent?.let { actionTarget = it.toActionTarget() }
        }

    private suspend fun PreferenceHierarchy.toProto(
        activityClass: Class<*>,
        isRoot: Boolean,
    ): PreferenceGroupProto = preferenceGroupProto {
        preference = toProto(this@toProto, activityClass, isRoot)
        forEachAsync {
            addPreferences(
                preferenceOrGroupProto {
                    if (it is PreferenceHierarchy) {
                        group = it.toProto(activityClass, false)
                    } else {
                        preference = toProto(it, activityClass, false)
                    }
                })
        }
    }

    private suspend fun toProto(
        node: PreferenceHierarchyNode,
        activityClass: Class<*>,
        isRoot: Boolean,
    ) = preferenceProto {
        val metadata = node.metadata
        key = metadata.key
        metadata.getTitleTextProto(isRoot)?.let { title = it }
        if (metadata.summary != 0) {
            summary = textProto { resourceId = metadata.summary }
        } else {
            (metadata as? PreferenceSummaryProvider)?.getSummary(context)?.let {
                summary = textProto { string = it.toString() }
            }
        }
        if (metadata.icon != 0) icon = metadata.icon
        if (metadata.keywords != 0) keywords = metadata.keywords
        val preferenceExtras = metadata.extras(context)
        preferenceExtras?.let { extras = it.toProto() }
        indexable = metadata.isIndexable(context)
        enabled = metadata.isEnabled(context)
        if (metadata is PreferenceAvailabilityProvider) {
            available = metadata.isAvailable(context)
        }
        if (metadata is PreferenceRestrictionProvider) {
            restricted = metadata.isRestricted(context)
        }
        persistent = metadata.isPersistent(context)
        if (includeValue &&
            persistent &&
            metadata is BooleanValue &&
            metadata is PersistentPreference<*>) {
            metadata.storage(context).getValue(metadata.key, Boolean::class.javaObjectType)?.let {
                value = preferenceValueProto { booleanValue = it }
            }
        }
        if (metadata is PreferenceScreenMetadata) {
            if (metadata.hasCompleteHierarchy()) {
                @Suppress("CheckReturnValue") addPreferenceScreenMetadata(metadata, activityClass)
            } else {
                metadata.fragmentClass()?.toActionTarget(activityClass, preferenceExtras)?.let {
                    actionTarget = it
                }
            }
        }
        metadata.intent(context)?.let { actionTarget = it.toActionTarget() }
    }

    private fun PreferenceMetadata.getTitleTextProto(isRoot: Boolean): TextProto? {
        if (isRoot && this is PreferenceScreenMetadata) {
            val titleRes = screenTitle
            if (titleRes != 0) {
                return textProto { resourceId = titleRes }
            } else {
                getScreenTitle(context)?.let {
                    return textProto { string = it.toString() }
                }
            }
        } else {
            val titleRes = title
            if (titleRes != 0) {
                return textProto { resourceId = titleRes }
            }
        }
        return (this as? PreferenceTitleProvider)?.getTitle(context)?.let {
            textProto { string = it.toString() }
        }
    }

    private suspend fun String?.toActionTarget(
        activityClass: Class<*>,
        extras: Bundle?,
    ): ActionTarget? {
        if (this.isNullOrEmpty()) return null
        try {
            val fragmentClass = context.classLoader.loadClass(this)
            if (Fragment::class.java.isAssignableFrom(fragmentClass)) {
                @Suppress("UNCHECKED_CAST")
                return (fragmentClass as Class<out Fragment>).toActionTarget(activityClass, extras)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot loadClass $this", e)
        }
        return null
    }

    private suspend fun Class<out Fragment>.toActionTarget(
        activityClass: Class<*>,
        extras: Bundle?,
    ): ActionTarget {
        val startIntent = Intent(context, activityClass)
        startIntent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, name)
        extras?.let { startIntent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT_ARGUMENTS, it) }
        if (!PreferenceScreenProvider::class.java.isAssignableFrom(this) &&
            !PreferenceScreenBindingKeyProvider::class.java.isAssignableFrom(this)) {
            return actionTargetProto { intent = startIntent.toProto() }
        }
        val fragment =
            withContext(Dispatchers.Main) {
                return@withContext try {
                    newInstance().apply { arguments = extras }
                } catch (e: Exception) {
                    Log.e(TAG, "Fail to instantiate fragment ${this@toActionTarget}", e)
                    null
                }
            }
        if (fragment is PreferenceScreenBindingKeyProvider) {
            val screenKey = fragment.getPreferenceScreenBindingKey(context)
            if (screenKey != null && addPreferenceScreenFromRegistry(screenKey, activityClass)) {
                return actionTargetProto { key = screenKey }
            }
        }
        if (fragment is PreferenceScreenProvider) {
            val screen = fragment.createPreferenceScreen(preferenceScreenFactory)
            if (screen != null) {
                addPreferenceScreen(startIntent, activityClass, screen)
                return actionTargetProto { key = screen.key }
            }
        }
        return actionTargetProto { intent = startIntent.toProto() }
    }

    private suspend fun Intent.toActionTarget(): ActionTarget {
        if (component?.packageName == "") {
            setClassName(context, component!!.className)
        }
        resolveActivity(context.packageManager)?.let {
            if (it.packageName == context.packageName) {
                add(it.className)
            }
        }
        return actionTargetProto { intent = toProto() }
    }

    companion object {
        suspend fun of(context: Context, request: GetPreferenceGraphRequest) =
            PreferenceGraphBuilder(context, request).also { it.init() }
    }
}

@SuppressLint("AppBundleLocaleChanges")
internal fun Context.ofLocale(locale: Locale?): Context {
    if (locale == null) return this
    val baseConfig: Configuration = resources.configuration
    val baseLocale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            baseConfig.locales[0]
        } else {
            baseConfig.locale
        }
    if (locale == baseLocale) {
        return this
    }
    val newConfig = Configuration(baseConfig)
    newConfig.setLocale(locale)
    return createConfigurationContext(newConfig)
}
