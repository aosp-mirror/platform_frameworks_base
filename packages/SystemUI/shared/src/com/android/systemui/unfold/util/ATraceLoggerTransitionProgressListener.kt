package com.android.systemui.unfold.util

import android.os.Trace
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import javax.inject.Inject
import javax.inject.Qualifier

/**
 * Listener that logs start and end of the fold-unfold transition.
 *
 * [tracePrefix] arg helps in differentiating those. Currently, this is expected to be logged twice
 * for each fold/unfold: in (1) systemui and (2) launcher process.
 */
class ATraceLoggerTransitionProgressListener
@Inject
internal constructor(@UnfoldTransitionATracePrefix tracePrefix: String) :
    TransitionProgressListener {

    private val traceName = "$tracePrefix#$UNFOLD_TRANSITION_TRACE_NAME"

    override fun onTransitionStarted() {
        Trace.beginAsyncSection(traceName, /* cookie= */ 0)
    }

    override fun onTransitionFinished() {
        Trace.endAsyncSection(traceName, /* cookie= */ 0)
    }

    override fun onTransitionProgress(progress: Float) {
        Trace.setCounter(traceName, (progress * 100).toLong())
    }
}

private const val UNFOLD_TRANSITION_TRACE_NAME = "FoldUnfoldTransitionInProgress"

@Qualifier annotation class UnfoldTransitionATracePrefix
