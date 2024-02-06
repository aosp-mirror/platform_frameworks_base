/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import androidx.core.app.AppComponentFactory
import com.android.systemui.dagger.ContextComponentHelper
import com.android.systemui.dagger.SysUIComponent
import com.android.tools.r8.keepanno.annotations.KeepTarget
import com.android.tools.r8.keepanno.annotations.UsesReflection
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import javax.inject.Inject

/**
 * Implementation of AppComponentFactory that injects into constructors.
 *
 * This class sets up dependency injection when creating our application.
 *
 * Activities, Services, and BroadcastReceivers support dependency injection into
 * their constructors.
 *
 * ContentProviders support injection into member variables - _not_ constructors.
 */
abstract class SystemUIAppComponentFactoryBase : AppComponentFactory() {
    companion object {
        private const val TAG = "AppComponentFactory"
        // Must be static due to http://b/141008541.
        var systemUIInitializer: SystemUIInitializer? = null
    }

    @set:Inject
    lateinit var componentHelper: ContextComponentHelper

    /**
     * Returns a new [SystemUIInitializer].
     *
     * The returned implementation should be specific to your build.
     */
    protected abstract fun createSystemUIInitializer(context: Context): SystemUIInitializer

    private fun createSystemUIInitializerInternal(context: Context): SystemUIInitializer {
        return systemUIInitializer ?: run {
            val initializer = createSystemUIInitializer(context.applicationContext)
            try {
                initializer.init(false)
            } catch (exception: ExecutionException) {
                throw RuntimeException("Failed to initialize SysUI", exception)
            } catch (exception: InterruptedException) {
                throw RuntimeException("Failed to initialize SysUI", exception)
            }
            initializer.sysUIComponent.inject(
                this@SystemUIAppComponentFactoryBase
            )

            systemUIInitializer = initializer
            return initializer
        }
    }

    override fun instantiateApplicationCompat(cl: ClassLoader, className: String): Application {
        val app = super.instantiateApplicationCompat(cl, className)
        if (app !is ContextInitializer) {
            throw RuntimeException("App must implement ContextInitializer")
        } else {
            app.setContextAvailableCallback { context ->
                createSystemUIInitializerInternal(context)
            }
        }

        return app
    }

    @UsesReflection(
            KeepTarget(instanceOfClassConstant = SysUIComponent::class, methodName = "inject"))
    override fun instantiateProviderCompat(cl: ClassLoader, className: String): ContentProvider {
        val contentProvider = super.instantiateProviderCompat(cl, className)
        if (contentProvider is ContextInitializer) {
            contentProvider.setContextAvailableCallback { context ->
                val initializer = createSystemUIInitializerInternal(context)
                val rootComponent = initializer.sysUIComponent
                try {
                    val injectMethod = rootComponent.javaClass
                        .getMethod("inject", contentProvider.javaClass)
                    injectMethod.invoke(rootComponent, contentProvider)
                } catch (e: NoSuchMethodException) {
                    throw RuntimeException("No injector for class: " + contentProvider.javaClass, e)
                } catch (e: IllegalAccessException) {
                    throw RuntimeException("Injector inaccessible for class: " +
                            contentProvider.javaClass, e)
                } catch (e: InvocationTargetException) {
                    throw RuntimeException("Error while injecting: " + contentProvider.javaClass, e)
                }
                initializer
            }
        }
        return contentProvider
    }

    override fun instantiateActivityCompat(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): Activity {
        if (!this::componentHelper.isInitialized) {
            // This shouldn't happen, but is seen on occasion.
            // Bug filed against framework to take a look: http://b/141008541
            systemUIInitializer?.sysUIComponent?.inject(this@SystemUIAppComponentFactoryBase)
        }
        return componentHelper.resolveActivity(className)
            ?: super.instantiateActivityCompat(cl, className, intent)
    }

    override fun instantiateServiceCompat(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): Service {
        if (!this::componentHelper.isInitialized) {
            // This shouldn't happen, but does when a device is freshly formatted.
            // Bug filed against framework to take a look: http://b/141008541
            systemUIInitializer?.sysUIComponent?.inject(this@SystemUIAppComponentFactoryBase)
        }
        return componentHelper.resolveService(className)
            ?: super.instantiateServiceCompat(cl, className, intent)
    }

    override fun instantiateReceiverCompat(
        cl: ClassLoader,
        className: String,
        intent: Intent?
    ): BroadcastReceiver {
        if (!this::componentHelper.isInitialized) {
            // This shouldn't happen, but does when a device is freshly formatted.
            // Bug filed against framework to take a look: http://b/141008541
            systemUIInitializer?.sysUIComponent?.inject(this@SystemUIAppComponentFactoryBase)
        }
        return componentHelper.resolveBroadcastReceiver(className)
            ?: super.instantiateReceiverCompat(cl, className, intent)
    }

    /**
     * An Interface for classes that can be notified when an Application Context becomes available.
     *
     * An instance of this will be passed to implementers of [ContextInitializer].
     */
    fun interface ContextAvailableCallback {
        /** Notifies when the Application Context is available.  */
        fun onContextAvailable(context: Context): SystemUIInitializer
    }

    /**
     * Interface for classes that can be constructed by the system before a context is available.
     *
     * This is intended for [Application] and [ContentProvider] implementations that
     * either may not have a Context until some point after construction or are themselves
     * a [Context].
     *
     * Implementers will be passed a [ContextAvailableCallback] that they should call as soon
     * as an Application Context is ready.
     */
    interface ContextInitializer {
        /**
         * Called to supply the [ContextAvailableCallback] that should be called when an
         * Application [Context] is available.
         */
        fun setContextAvailableCallback(callback: ContextAvailableCallback)
    }
}
