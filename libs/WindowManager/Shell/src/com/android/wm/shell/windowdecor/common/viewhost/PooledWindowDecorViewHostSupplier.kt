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
package com.android.wm.shell.windowdecor.common.viewhost

import android.content.Context
import android.os.Trace
import android.util.Pools
import android.view.Display
import android.view.SurfaceControl
import com.android.wm.shell.shared.annotations.ShellMainThread
import kotlinx.coroutines.CoroutineScope

/**
 * A [WindowDecorViewHostSupplier] backed by a pool to allow recycling view hosts which may be
 * expensive to recreate for each new or updated window decoration.
 *
 * Callers can obtain a [WindowDecorViewHost] using [acquire], which will return a pooled
 * object if available, or create a new instance and return it if needed. When finished using a
 * [WindowDecorViewHost], it must be released using [release] to allow it to be sent back
 * into the pool and reused later on.
 */
class PooledWindowDecorViewHostSupplier(
    @ShellMainThread private val mainScope: CoroutineScope,
    maxPoolSize: Int,
) : WindowDecorViewHostSupplier<WindowDecorViewHost> {

    private val pool: Pools.Pool<WindowDecorViewHost> = Pools.SynchronizedPool(maxPoolSize)
    private var nextDecorViewHostId = 0

    override fun acquire(context: Context, display: Display): WindowDecorViewHost {
        val pooledViewHost = pool.acquire()
        if (pooledViewHost != null) {
            return pooledViewHost
        }
        Trace.beginSection("PooledWindowDecorViewHostSupplier#acquire-newInstance")
        val newDecorViewHost = newInstance(context, display)
        Trace.endSection()
        return newDecorViewHost
    }

    override fun release(viewHost: WindowDecorViewHost, t: SurfaceControl.Transaction) {
        val pooled = pool.release(viewHost)
        if (!pooled) {
            viewHost.release(t)
        }
    }

    private fun newInstance(context: Context, display: Display): ReusableWindowDecorViewHost {
        // Use a reusable window decor view host, as it allows swapping the entire view hierarchy.
        return ReusableWindowDecorViewHost(
            context = context,
            mainScope = mainScope,
            display = display,
            id = nextDecorViewHostId++
        )
    }
}
