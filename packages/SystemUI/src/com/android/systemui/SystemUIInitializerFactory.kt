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
package com.android.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.util.Assert

/**
 * Factory to reflectively lookup a [SystemUIInitializer] to start SystemUI with.
 */
@Deprecated("Provide your own {@link SystemUIAppComponentFactoryBase} that doesn't need this.")
object SystemUIInitializerFactory {
    private const val TAG = "SysUIInitializerFactory"
    @SuppressLint("StaticFieldLeak")
    private var initializer: SystemUIInitializer? = null

    /**
     * Instantiate a [SystemUIInitializer] reflectively.
     */
    @JvmStatic
    fun createWithContext(context: Context): SystemUIInitializer {
        return createFromConfig(context)
    }

    /**
     * Instantiate a [SystemUIInitializer] reflectively.
     */
    @JvmStatic
    private fun createFromConfig(context: Context): SystemUIInitializer {
        Assert.isMainThread()

        return createFromConfigNoAssert(context)
    }

    @JvmStatic
    @VisibleForTesting
    fun createFromConfigNoAssert(context: Context): SystemUIInitializer {

        return initializer ?: run {
            val className = context.getString(R.string.config_systemUIFactoryComponent)
            if (className.isEmpty()) {
                throw RuntimeException("No SystemUIFactory component configured")
            }
            try {
                val cls = context.classLoader.loadClass(className)
                val constructor = cls.getConstructor(Context::class.java)
                (constructor.newInstance(context) as SystemUIInitializer).apply {
                    initializer = this
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error creating SystemUIInitializer component: $className", t)
                throw t
            }
        }
    }
}
