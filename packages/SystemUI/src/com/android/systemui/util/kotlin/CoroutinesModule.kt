package com.android.systemui.util.kotlin

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.Tracing
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.tracing.TraceUtils.Companion.coroutineTracingIsEnabled
import com.android.systemui.tracing.TraceContextElement
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Qualifier
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Key associated with a [Boolean] flag that enables or disables the coroutine tracing feature. */
@Qualifier
annotation class CoroutineTracingEnabledKey

/**
 * Same as [@Application], but does not make use of flags. This should only be used when early usage
 * of [@Application] would introduce a circular dependency on [FeatureFlagsClassic].
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class UnflaggedApplication

/**
 * Same as [@Background], but does not make use of flags. This should only be used when early usage
 * of [@Application] would introduce a circular dependency on [FeatureFlagsClassic].
 */
@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class UnflaggedBackground

/** Providers for various coroutines-related constructs. */
@Module
class CoroutinesModule {
    @Provides
    @SysUISingleton
    @Application
    fun applicationScope(
            @Main dispatcherContext: CoroutineContext,
    ): CoroutineScope = CoroutineScope(dispatcherContext)

    @Provides
    @SysUISingleton
    @UnflaggedApplication
    fun unflaggedApplicationScope(): CoroutineScope = CoroutineScope(Dispatchers.Main.immediate)

    @Provides
    @SysUISingleton
    @Main
    @Deprecated(
        "Use @Main CoroutineContext instead",
        ReplaceWith("mainCoroutineContext()", "kotlin.coroutines.CoroutineContext")
    )
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @SysUISingleton
    @Main
    fun mainCoroutineContext(@Tracing tracingCoroutineContext: CoroutineContext): CoroutineContext {
        return Dispatchers.Main.immediate + tracingCoroutineContext
    }

    /**
     * Provide a [CoroutineDispatcher] backed by a thread pool containing at most X threads, where
     * X is the number of CPU cores available.
     *
     * Because there are multiple threads at play, there is no serialization order guarantee. You
     * should use a [kotlinx.coroutines.channels.Channel] for serialization if necessary.
     *
     * @see Dispatchers.Default
     */
    @Provides
    @SysUISingleton
    @Background
    @Deprecated(
        "Use @Background CoroutineContext instead",
        ReplaceWith("bgCoroutineContext()", "kotlin.coroutines.CoroutineContext")
    )
    fun bgDispatcher(): CoroutineDispatcher = Dispatchers.IO


    @Provides
    @Background
    @SysUISingleton
    fun bgCoroutineContext(@Tracing tracingCoroutineContext: CoroutineContext): CoroutineContext {
        return Dispatchers.IO + tracingCoroutineContext
    }

    @Provides
    @UnflaggedBackground
    @SysUISingleton
    fun unflaggedBackgroundCoroutineContext(): CoroutineContext {
        return Dispatchers.IO
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Tracing
    @SysUISingleton
    fun tracingCoroutineContext(
        @CoroutineTracingEnabledKey enableTracing: Boolean
    ): CoroutineContext = if (enableTracing) TraceContextElement() else EmptyCoroutineContext

    companion object {
        @[Provides CoroutineTracingEnabledKey]
        fun provideIsCoroutineTracingEnabledKey(featureFlags: FeatureFlagsClassic): Boolean {
            return if (featureFlags.isEnabled(Flags.COROUTINE_TRACING)) {
                coroutineTracingIsEnabled = true
                true
            } else false
        }
    }
}
