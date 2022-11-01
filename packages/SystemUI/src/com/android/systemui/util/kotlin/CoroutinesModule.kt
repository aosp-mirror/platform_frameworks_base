package com.android.systemui.util.kotlin

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Providers for various coroutines-related constructs. */
@Module
object CoroutinesModule {
    @Provides
    @SysUISingleton
    @Application
    fun applicationScope(
        @Main dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(dispatcher)

    @Provides
    @SysUISingleton
    @Main
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

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
    fun bgDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
