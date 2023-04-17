package com.android.systemui.shared.condition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Converts a boolean flow to a [Condition] object which can be used with a [Monitor] */
@JvmOverloads
fun Flow<Boolean>.toCondition(scope: CoroutineScope, initialValue: Boolean? = null): Condition {
    return object : Condition(initialValue, false) {
        var job: Job? = null

        override fun start() {
            job = scope.launch { collect { updateCondition(it) } }
        }

        override fun stop() {
            job?.cancel()
            job = null
        }
    }
}
