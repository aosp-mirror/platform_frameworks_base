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

package com.android.systemui

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.kairos.BuildScope
import com.android.systemui.kairos.Events
import com.android.systemui.kairos.EventsLoop
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.Incremental
import com.android.systemui.kairos.IncrementalLoop
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.State
import com.android.systemui.kairos.StateLoop
import com.android.systemui.kairos.launchKairosNetwork
import com.android.systemui.kairos.launchScope
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.Multibinds
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A Kairos-powered class that needs late-initialization within a Kairos [BuildScope].
 *
 * If your class is a [SysUISingleton], you can leverage Dagger to automatically initialize your
 * instance after SystemUI has initialized:
 * ```kotlin
 * class MyClass : KairosActivatable { ... }
 *
 * @dagger.Module
 * interface MyModule {
 *     @Binds
 *     @IntoSet
 *     fun bindKairosActivatable(impl: MyClass): KairosActivatable
 * }
 * ```
 *
 * Alternatively, you can utilize Dagger's [dagger.assisted.AssistedInject]:
 * ```kotlin
 * class MyClass @AssistedInject constructor(...) : KairosActivatable {
 *     @AssistedFactory
 *     interface Factory {
 *         fun create(...): MyClass
 *     }
 * }
 *
 * // When you need an instance:
 *
 * class OtherClass @Inject constructor(
 *     private val myClassFactory: MyClass.Factory,
 * ) {
 *     fun BuildScope.foo() {
 *         val myClass = activated { myClassFactory.create() }
 *         ...
 *     }
 * }
 * ```
 *
 * @see activated
 */
@ExperimentalKairosApi
fun interface KairosActivatable {
    /** Initializes any Kairos fields that require a [BuildScope] in order to be constructed. */
    fun BuildScope.activate()
}

/** Constructs [KairosActivatable] instances. */
@ExperimentalKairosApi
fun interface KairosActivatableFactory<T : KairosActivatable> {
    fun BuildScope.create(): T
}

/** Instantiates, [activates][KairosActivatable.activate], and returns a [KairosActivatable]. */
@ExperimentalKairosApi
fun <T : KairosActivatable> BuildScope.activated(factory: KairosActivatableFactory<T>): T =
    factory.run { create() }.apply { activate() }

/**
 * Utilities for defining [State] and [Events] from a constructor without a provided [BuildScope].
 * These instances are not active until the builder is [activated][activate]; while you can
 * immediately use them with other Kairos APIs, the Kairos transaction will be suspended until
 * initialization is complete.
 *
 * ```kotlin
 * class MyRepository(private val dataSource: DataSource) : KairosBuilder by kairosBuilder() {
 *   val dataSourceEvent = buildEvents<SomeData> {
 *       // inside this lambda, we have access to a BuildScope, which can be used to create
 *       // new inputs to the Kairos network
 *       dataSource.someDataFlow.toEvents()
 *   }
 * }
 * ```
 */
@ExperimentalKairosApi
interface KairosBuilder : KairosActivatable {
    /**
     * Returns a forward-reference to a [State] that will be instantiated when this builder is
     * [activated][activate].
     */
    fun <R> buildState(block: BuildScope.() -> State<R>): State<R>

    /**
     * Returns a forward-reference to an [Events] that will be instantiated when this builder is
     * [activated][activate].
     */
    fun <R> buildEvents(block: BuildScope.() -> Events<R>): Events<R>

    fun <K, V> buildIncremental(block: BuildScope.() -> Incremental<K, V>): Incremental<K, V>

    /** Defers [block] until this builder is [activated][activate]. */
    fun onActivated(block: BuildScope.() -> Unit)
}

/** Returns an [KairosBuilder] that can only be [activated][KairosActivatable.activate] once. */
@ExperimentalKairosApi fun kairosBuilder(): KairosBuilder = KairosBuilderImpl()

@OptIn(ExperimentalKairosApi::class)
private class KairosBuilderImpl @Inject constructor() : KairosBuilder {

    // TODO: atomic?
    // TODO: are two lists really necessary?
    private var _builds: MutableList<KairosActivatable>? = mutableListOf()
    private var _startables: MutableList<KairosActivatable>? = mutableListOf()

    private val startables
        get() = checkNotNull(_startables) { "Kairos network has already been initialized" }

    private val builds
        get() = checkNotNull(_builds) { "Kairos network has already been initialized" }

    override fun <R> buildState(block: BuildScope.() -> State<R>): State<R> =
        StateLoop<R>().apply { builds.add { loopback = block() } }

    override fun <R> buildEvents(block: BuildScope.() -> Events<R>): Events<R> =
        EventsLoop<R>().apply { builds.add { loopback = block() } }

    override fun <K, V> buildIncremental(
        block: BuildScope.() -> Incremental<K, V>
    ): Incremental<K, V> = IncrementalLoop<K, V>().apply { builds.add { loopback = block() } }

    override fun onActivated(block: BuildScope.() -> Unit) {
        startables.add { block() }
    }

    override fun BuildScope.activate() {
        builds.forEach { it.run { activate() } }
        _builds = null
        deferredBuildScopeAction {
            startables.forEach { it.run { activate() } }
            _startables = null
        }
    }
}

/** Initializes [KairosActivatables][KairosActivatable] after SystemUI is initialized. */
@SysUISingleton
@ExperimentalKairosApi
class KairosCoreStartable
@Inject
constructor(
    @Application private val appScope: CoroutineScope,
    private val kairosNetwork: KairosNetwork,
    private val activatables: dagger.Lazy<Set<@JvmSuppressWildcards KairosActivatable>>,
) : CoreStartable {
    override fun start() {
        appScope.launch {
            kairosNetwork.activateSpec {
                for (activatable in activatables.get()) {
                    launchScope { activatable.run { activate() } }
                }
            }
        }
    }
}

@Module
@ExperimentalKairosApi
interface KairosCoreStartableModule {
    @Binds
    @IntoMap
    @ClassKey(KairosCoreStartable::class)
    fun bindCoreStartable(impl: KairosCoreStartable): CoreStartable

    @Multibinds fun kairosActivatables(): Set<@JvmSuppressWildcards KairosActivatable>

    companion object {
        @Provides
        @SysUISingleton
        fun provideKairosNetwork(@Application scope: CoroutineScope): KairosNetwork =
            scope.launchKairosNetwork()
    }
}
