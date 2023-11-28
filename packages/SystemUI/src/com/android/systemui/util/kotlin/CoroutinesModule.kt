package com.android.systemui.util.kotlin

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.Tracing
import com.android.systemui.Flags.coroutineTracing
import com.android.app.tracing.TraceUtils.Companion.coroutineTracingIsEnabled
import com.android.app.tracing.TraceContextElement
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
    @Background
    fun bgApplicationScope(
            @Application applicationScope: CoroutineScope,
            @Background coroutineContext: CoroutineContext,
    ): CoroutineScope = applicationScope.plus(coroutineContext)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Tracing
    @SysUISingleton
    fun tracingCoroutineContext(): CoroutineContext {
        return if (coroutineTracing()) {
            coroutineTracingIsEnabled = true
            TraceContextElement()
        } else EmptyCoroutineContext
    }
}
