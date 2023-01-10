/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.util

import android.content.SharedPreferences

/**
 * Fake [SharedPreferences] to use within tests
 *
 * This will act in the same way as a real one for a particular file, but will store all the
 * data in memory in the instance.
 *
 * [SharedPreferences.Editor.apply] and [SharedPreferences.Editor.commit] both act in the same way,
 * synchronously modifying the stored data. Listeners are dispatched in the same thread, also
 * synchronously.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> {
        return data
    }

    override fun getString(key: String, defValue: String?): String? {
        return data.getOrDefault(key, defValue) as? String?
    }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        return data.getOrDefault(key, defValues) as? MutableSet<String>?
    }

    override fun getInt(key: String, defValue: Int): Int {
        return data.getOrDefault(key, defValue) as Int
    }

    override fun getLong(key: String, defValue: Long): Long {
        return data.getOrDefault(key, defValue) as Long
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return data.getOrDefault(key, defValue) as Float
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return data.getOrDefault(key, defValue) as Boolean
    }

    override fun contains(key: String): Boolean {
        return key in data
    }

    override fun edit(): SharedPreferences.Editor {
        return Editor()
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.remove(listener)
    }

    private inner class Editor : SharedPreferences.Editor {

        private var clear = false
        private val changes = mutableMapOf<String, Any>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value != null) {
                changes[key] = value
            } else {
                removals.add(key)
            }
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            if (values != null) {
                changes[key] = values
            } else {
                removals.add(key)
            }
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            changes[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            changes[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            changes[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            changes[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            if (clear) {
                data.clear()
            }
            removals.forEach { data.remove(it) }
            data.putAll(changes)
            val keys = removals + data.keys
            if (clear || removals.isNotEmpty() || data.isNotEmpty()) {
                listeners.forEach { listener ->
                    if (clear) {
                        listener.onSharedPreferenceChanged(this@FakeSharedPreferences, null)
                    }
                    keys.forEach {
                        listener.onSharedPreferenceChanged(this@FakeSharedPreferences, it)
                    }
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
