package com.android.wm.shell.desktopmode

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.RectEvaluator
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.ActivityOptions.SourceInfo
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
import android.app.PendingIntent.FLAG_MUTABLE
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.content.Intent
import android.content.Intent.FILL_IN_COMPONENT
import android.graphics.Rect
import android.os.IBinder
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CLOSE
import android.window.TransitionInfo
import android.window.TransitionInfo.Change
import android.window.TransitionRequestInfo
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.protolog.ShellProtoLogGroup
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.transition.Transitions.TRANSIT_DESKTOP_MODE_CANCEL_DRAG_TO_DESKTOP
import com.android.wm.shell.transition.Transitions.TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP
import com.android.wm.shell.transition.Transitions.TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP
import com.android.wm.shell.transition.Transitions.TransitionHandler
import com.android.wm.shell.util.KtProtoLog
import com.android.wm.shell.util.TransitionUtil
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import com.android.wm.shell.windowdecor.MoveToDesktopAnimator
import com.android.wm.shell.windowdecor.MoveToDesktopAnimator.Companion.DRAG_FREEFORM_SCALE
import java.util.function.Supplier

/**
 * Handles the transition to enter desktop from fullscreen by dragging on the handle bar. It also
 * handles the cancellation case where the task is dragged back to the status bar area in the same
 * gesture.
 */
class DragToDesktopTransitionHandler(
        private val context: Context,
        private val transitions: Transitions,
        private val taskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
        private val transactionSupplier: Supplier<SurfaceControl.Transaction>
) : TransitionHandler {

    constructor(
            context: Context,
            transitions: Transitions,
            rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    ) : this(
            context,
            transitions,
            rootTaskDisplayAreaOrganizer,
            Supplier { SurfaceControl.Transaction() }
    )

    private val rectEvaluator = RectEvaluator(Rect())
    private val launchHomeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)

    private var dragToDesktopStateListener: DragToDesktopStateListener? = null
    private var splitScreenController: SplitScreenController? = null
    private var transitionState: TransitionState? = null

    /** Whether a drag-to-desktop transition is in progress. */
    val inProgress: Boolean
        get() = transitionState != null

    /** Sets a listener to receive callback about events during the transition animation. */
    fun setDragToDesktopStateListener(listener: DragToDesktopStateListener) {
        dragToDesktopStateListener = listener
    }

    /** Setter needed to avoid cyclic dependency. */
    fun setSplitScreenController(controller: SplitScreenController) {
        splitScreenController = controller
    }

    /**
     * Starts a transition that performs a transient launch of Home so that Home is brought to the
     * front while still keeping the currently focused task that is being dragged resumed. This
     * allows the animation handler to reorder the task to the front and to scale it with the
     * gesture into the desktop area with the Home and wallpaper behind it.
     *
     * Note that the transition handler for this transition doesn't call the finish callback until
     * after one of the "end" or "cancel" transitions is merged into this transition.
     */
    fun startDragToDesktopTransition(
            taskId: Int,
            dragToDesktopAnimator: MoveToDesktopAnimator,
            windowDecoration: DesktopModeWindowDecoration
    ) {
        if (inProgress) {
            KtProtoLog.v(
                    ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                    "DragToDesktop: Drag to desktop transition already in progress."
            )
            return
        }

        val options = ActivityOptions.makeBasic().apply {
            setTransientLaunch()
            setSourceInfo(SourceInfo.TYPE_DESKTOP_ANIMATION, SystemClock.uptimeMillis())
            pendingIntentCreatorBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val pendingIntent = PendingIntent.getActivity(
                context,
                0 /* requestCode */,
                launchHomeIntent,
                FLAG_MUTABLE or FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT or FILL_IN_COMPONENT,
                options.toBundle()
        )
        val wct = WindowContainerTransaction()
        wct.sendPendingIntent(pendingIntent, launchHomeIntent, options.toBundle())
        val startTransitionToken = transitions
                .startTransition(TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP, wct, this)

        transitionState = if (isSplitTask(taskId)) {
            TransitionState.FromSplit(
                    draggedTaskId = taskId,
                    dragAnimator = dragToDesktopAnimator,
                    windowDecoration = windowDecoration,
                    startTransitionToken = startTransitionToken
            )
        } else {
            TransitionState.FromFullscreen(
                    draggedTaskId = taskId,
                    dragAnimator = dragToDesktopAnimator,
                    windowDecoration = windowDecoration,
                    startTransitionToken = startTransitionToken
            )
        }
    }

    /**
     * Starts a transition that "finishes" the drag to desktop gesture. This transition is intended
     * to merge into the "start" transition and is the one that actually applies the bounds and
     * windowing mode changes to the dragged task. This is called when the dragged task is released
     * inside the desktop drop zone.
     */
    fun finishDragToDesktopTransition(wct: WindowContainerTransaction) {
        if (!inProgress) {
            // Don't attempt to finish a drag to desktop transition since there is no transition in
            // progress which means that the drag to desktop transition was never successfully
            // started.
            return
        }
        if (requireTransitionState().startAborted) {
            // Don't attempt to complete the drag-to-desktop since the start transition didn't
            // succeed as expected. Just reset the state as if nothing happened.
            clearState()
            return
        }
        transitions.startTransition(TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP, wct, this)
    }

    /**
     * Starts a transition that "cancels" the drag to desktop gesture. This transition is intended
     * to merge into the "start" transition and it restores the transient state that was used to
     * launch the Home task over the dragged task. This is called when the dragged task is released
     * outside the desktop drop zone and is instead dropped back into the status bar region that
     * means the user wants to remain in their current windowing mode.
     */
    fun cancelDragToDesktopTransition() {
        if (!inProgress) {
            // Don't attempt to cancel a drag to desktop transition since there is no transition in
            // progress which means that the drag to desktop transition was never successfully
            // started.
            return
        }
        val state = requireTransitionState()
        if (state.startAborted) {
            // Don't attempt to cancel the drag-to-desktop since the start transition didn't
            // succeed as expected. Just reset the state as if nothing happened.
            clearState()
            return
        }
        state.cancelled = true
        if (state.draggedTaskChange != null) {
            // Regular case, transient launch of Home happened as is waiting for the cancel
            // transient to start and merge. Animate the cancellation (scale back to original
            // bounds) first before actually starting the cancel transition so that the wallpaper
            // is visible behind the animating task.
            startCancelAnimation()
        } else {
            // There's no dragged task, this can happen when the "cancel" happened too quickly
            // before the "start" transition is even ready (like on a fling gesture). The
            // "shrink" animation didn't even start, so there's no need to animate the "cancel".
            // We also don't want to start the cancel transition yet since we don't have
            // enough info to restore the order. We'll check for the cancelled state flag when
            // the "start" animation is ready and cancel from #startAnimation instead.
        }
    }

    override fun startAnimation(
            transition: IBinder,
            info: TransitionInfo,
            startTransaction: SurfaceControl.Transaction,
            finishTransaction: SurfaceControl.Transaction,
            finishCallback: Transitions.TransitionFinishCallback
    ): Boolean {
        val state = requireTransitionState()

        val isStartDragToDesktop = info.type == TRANSIT_DESKTOP_MODE_START_DRAG_TO_DESKTOP &&
                transition == state.startTransitionToken
        if (!isStartDragToDesktop) {
            return false
        }

        // Layering: non-wallpaper, non-home tasks excluding the dragged task go at the bottom,
        // then Home on top of that, wallpaper on top of that and finally the dragged task on top
        // of everything.
        val appLayers = info.changes.size
        val homeLayers = info.changes.size * 2
        val wallpaperLayers = info.changes.size * 3
        val dragLayer = wallpaperLayers
        val leafTaskFilter = TransitionUtil.LeafTaskFilter()
        info.changes.withIndex().forEach { (i, change) ->
            if (TransitionUtil.isWallpaper(change)) {
                val layer = wallpaperLayers - i
                startTransaction.apply {
                    setLayer(change.leash, layer)
                    show(change.leash)
                }
            } else if (isHomeChange(change)) {
                state.homeToken = change.container
                val layer = homeLayers - i
                startTransaction.apply {
                    setLayer(change.leash, layer)
                    show(change.leash)
                }
            } else if (TransitionInfo.isIndependent(change, info)) {
                // Root.
                when (state) {
                    is TransitionState.FromSplit -> {
                        state.splitRootChange = change
                        val layer = if (!state.cancelled) {
                            // Normal case, split root goes to the bottom behind everything else.
                            appLayers - i
                        } else {
                            // Cancel-early case, pretend nothing happened so split root stays top.
                            dragLayer
                        }
                        startTransaction.apply {
                            setLayer(change.leash, layer)
                            show(change.leash)
                        }
                    }
                    is TransitionState.FromFullscreen -> {
                        if (change.taskInfo?.taskId == state.draggedTaskId) {
                            state.draggedTaskChange = change
                            val bounds = change.endAbsBounds
                            startTransaction.apply {
                                setLayer(change.leash, dragLayer)
                                setWindowCrop(change.leash, bounds.width(), bounds.height())
                                show(change.leash)
                            }
                        } else {
                            throw IllegalStateException("Expected root to be dragged task")
                        }
                    }
                }
            } else if (leafTaskFilter.test(change)) {
                // When dragging one of the split tasks, the dragged leaf needs to be re-parented
                // so that it can be layered separately from the rest of the split root/stages.
                // The split root including the other split side was layered behind the wallpaper
                // and home while the dragged split needs to be layered in front of them.
                // Do not do this in the cancel-early case though, since in that case nothing should
                // happen on screen so the layering will remain the same as if no transition
                // occurred.
                if (change.taskInfo?.taskId == state.draggedTaskId && !state.cancelled) {
                    state.draggedTaskChange = change
                    taskDisplayAreaOrganizer.reparentToDisplayArea(
                            change.endDisplayId, change.leash, startTransaction)
                    val bounds = change.endAbsBounds
                    startTransaction.apply {
                        setLayer(change.leash, dragLayer)
                        setWindowCrop(change.leash, bounds.width(), bounds.height())
                        show(change.leash)
                    }
                }
            }
        }
        state.startTransitionFinishCb = finishCallback
        state.startTransitionFinishTransaction = finishTransaction
        startTransaction.apply()

        if (!state.cancelled) {
            // Normal case, start animation to scale down the dragged task. It'll also be moved to
            // follow the finger and when released we'll start the next phase/transition.
            state.dragAnimator.startAnimation()
        } else {
            // Cancel-early case, the state was flagged was cancelled already, which means the
            // gesture ended in the cancel region. This can happen even before the start transition
            // is ready/animate here when cancelling quickly like with a fling. There's no point
            // in starting the scale down animation that we would scale up anyway, so just jump
            // directly into starting the cancel transition to restore WM order. Surfaces should
            // not move as if no transition happened.
            startCancelDragToDesktopTransition()
        }
        return true
    }

    override fun mergeAnimation(
            transition: IBinder,
            info: TransitionInfo,
            t: SurfaceControl.Transaction,
            mergeTarget: IBinder,
            finishCallback: Transitions.TransitionFinishCallback
    ) {
        val state = requireTransitionState()
        val isCancelTransition = info.type == TRANSIT_DESKTOP_MODE_CANCEL_DRAG_TO_DESKTOP &&
                transition == state.cancelTransitionToken &&
                mergeTarget == state.startTransitionToken
        val isEndTransition = info.type == TRANSIT_DESKTOP_MODE_END_DRAG_TO_DESKTOP &&
                mergeTarget == state.startTransitionToken

        val startTransactionFinishT = state.startTransitionFinishTransaction
                ?: error("Start transition expected to be waiting for merge but wasn't")
        val startTransitionFinishCb = state.startTransitionFinishCb
                ?: error("Start transition expected to be waiting for merge but wasn't")
        if (isEndTransition) {
            info.changes.withIndex().forEach { (i, change) ->
                if (change.mode == TRANSIT_CLOSE) {
                    t.hide(change.leash)
                    startTransactionFinishT.hide(change.leash)
                } else if (change.taskInfo?.taskId == state.draggedTaskId) {
                    t.show(change.leash)
                    startTransactionFinishT.show(change.leash)
                    state.draggedTaskChange = change
                } else if (change.taskInfo?.windowingMode == WINDOWING_MODE_FREEFORM) {
                    // Other freeform tasks that are being restored go behind the dragged task.
                    val draggedTaskLeash = state.draggedTaskChange?.leash
                            ?: error("Expected dragged leash to be non-null")
                    t.setRelativeLayer(change.leash, draggedTaskLeash, -i)
                    startTransactionFinishT.setRelativeLayer(change.leash, draggedTaskLeash, -i)
                }
            }

            val draggedTaskChange = state.draggedTaskChange
                    ?: throw IllegalStateException("Expected non-null change of dragged task")
            val draggedTaskLeash = draggedTaskChange.leash
            val startBounds = draggedTaskChange.startAbsBounds
            val endBounds = draggedTaskChange.endAbsBounds

            // TODO(b/301106941): Instead of forcing-finishing the animation that scales the
            //  surface down and then starting another that scales it back up to the final size,
            //  blend the two animations.
            state.dragAnimator.endAnimator()
            // Using [DRAG_FREEFORM_SCALE] to calculate animated width/height is possible because
            // it is known that the animation scale is finished because the animation was
            // force-ended above. This won't be true when the two animations are blended.
            val animStartWidth = (startBounds.width() * DRAG_FREEFORM_SCALE).toInt()
            val animStartHeight = (startBounds.height() * DRAG_FREEFORM_SCALE).toInt()
            // Using end bounds here to find the left/top also assumes the center animation has
            // finished and the surface is placed exactly in the center of the screen which matches
            // the end/default bounds of the now freeform task.
            val animStartLeft = endBounds.centerX() - (animStartWidth / 2)
            val animStartTop = endBounds.centerY() - (animStartHeight / 2)
            val animStartBounds = Rect(
                    animStartLeft,
                    animStartTop,
                    animStartLeft + animStartWidth,
                    animStartTop + animStartHeight
            )


            dragToDesktopStateListener?.onCommitToDesktopAnimationStart(t)
            t.apply {
                setScale(draggedTaskLeash, 1f, 1f)
                setPosition(
                        draggedTaskLeash,
                        animStartBounds.left.toFloat(),
                        animStartBounds.top.toFloat()
                )
                setWindowCrop(
                        draggedTaskLeash,
                        animStartBounds.width(),
                        animStartBounds.height()
                )
            }
            // Accept the merge by applying the merging transaction (applied by #showResizeVeil)
            // and finish callback. Show the veil and position the task at the first frame before
            // starting the final animation.
            state.windowDecoration.showResizeVeil(t, animStartBounds)
            finishCallback.onTransitionFinished(null /* wct */)

            // Because the task surface was scaled down during the drag, we must use the animated
            // bounds instead of the [startAbsBounds].
            val tx: SurfaceControl.Transaction = transactionSupplier.get()
            ValueAnimator.ofObject(rectEvaluator, animStartBounds, endBounds)
                    .setDuration(DRAG_TO_DESKTOP_FINISH_ANIM_DURATION_MS)
                    .apply {
                        addUpdateListener { animator ->
                            val animBounds = animator.animatedValue as Rect
                            tx.apply {
                                setScale(draggedTaskLeash, 1f, 1f)
                                 setPosition(
                                         draggedTaskLeash,
                                         animBounds.left.toFloat(),
                                         animBounds.top.toFloat()
                                 )
                                setWindowCrop(
                                        draggedTaskLeash,
                                        animBounds.width(),
                                        animBounds.height()
                                )
                            }
                            state.windowDecoration.updateResizeVeil(tx, animBounds)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                state.windowDecoration.hideResizeVeil()
                                startTransitionFinishCb.onTransitionFinished(null /* null */)
                                clearState()
                            }
                        })
                        start()
                    }
        } else if (isCancelTransition) {
            info.changes.forEach { change ->
                t.show(change.leash)
                startTransactionFinishT.show(change.leash)
            }
            t.apply()
            finishCallback.onTransitionFinished(null /* wct */)
            startTransitionFinishCb.onTransitionFinished(null /* wct */)
            clearState()
        }
    }

    override fun handleRequest(
            transition: IBinder,
            request: TransitionRequestInfo
    ): WindowContainerTransaction? {
        // Only handle transitions started from shell.
        return null
    }

    override fun onTransitionConsumed(
        transition: IBinder,
        aborted: Boolean,
        finishTransaction: SurfaceControl.Transaction?
    ) {
        val state = transitionState ?: return
        if (aborted && state.startTransitionToken == transition) {
            KtProtoLog.v(
                ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE,
                "DragToDesktop: onTransitionConsumed() start transition aborted"
            )
            state.startAborted = true
        }
    }

    private fun isHomeChange(change: Change): Boolean {
        return change.taskInfo?.activityType == ACTIVITY_TYPE_HOME
    }

    private fun startCancelAnimation() {
        val state = requireTransitionState()
        val dragToDesktopAnimator = state.dragAnimator

        val draggedTaskChange = state.draggedTaskChange
                ?: throw IllegalStateException("Expected non-null task change")
        val sc = draggedTaskChange.leash
        // TODO(b/301106941): Don't end the animation and start one to scale it back, merge them
        //  instead.
        // End the animation that shrinks the window when task is first dragged from fullscreen
        dragToDesktopAnimator.endAnimator()
        // Then animate the scaled window back to its original bounds.
        val x: Float = dragToDesktopAnimator.position.x
        val y: Float = dragToDesktopAnimator.position.y
        val targetX = draggedTaskChange.endAbsBounds.left
        val targetY = draggedTaskChange.endAbsBounds.top
        val dx = targetX - x
        val dy = targetY - y
        val tx: SurfaceControl.Transaction = transactionSupplier.get()
        ValueAnimator.ofFloat(DRAG_FREEFORM_SCALE, 1f)
                .setDuration(DRAG_TO_DESKTOP_FINISH_ANIM_DURATION_MS)
                .apply {
                    addUpdateListener { animator ->
                        val scale = animator.animatedValue as Float
                        val fraction = animator.animatedFraction
                        val animX = x + (dx * fraction)
                        val animY = y + (dy * fraction)
                        tx.apply {
                            setPosition(sc, animX, animY)
                            setScale(sc, scale, scale)
                            show(sc)
                            apply()
                        }
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            dragToDesktopStateListener?.onCancelToDesktopAnimationEnd(tx)
                            // Start the cancel transition to restore order.
                            startCancelDragToDesktopTransition()
                        }
                    })
                    start()
                }
    }

    private fun startCancelDragToDesktopTransition() {
        val state = requireTransitionState()
        val wct = WindowContainerTransaction()
        when (state) {
            is TransitionState.FromFullscreen -> {
                val wc = state.draggedTaskChange?.container
                        ?: error("Dragged task should be non-null before cancelling")
                wct.reorder(wc, true /* toTop */)
            }
            is TransitionState.FromSplit -> {
                val wc = state.splitRootChange?.container
                        ?: error("Split root should be non-null before cancelling")
                wct.reorder(wc, true /* toTop */)
            }
        }
        val homeWc = state.homeToken ?: error("Home task should be non-null before cancelling")
        wct.restoreTransientOrder(homeWc)

        state.cancelTransitionToken = transitions.startTransition(
                TRANSIT_DESKTOP_MODE_CANCEL_DRAG_TO_DESKTOP, wct, this)
    }

    private fun clearState() {
        transitionState = null
    }

    private fun isSplitTask(taskId: Int): Boolean {
        return splitScreenController?.isTaskInSplitScreen(taskId) ?: false
    }

    private fun requireTransitionState(): TransitionState {
        return transitionState ?: error("Expected non-null transition state")
    }

    interface DragToDesktopStateListener {
        fun onCommitToDesktopAnimationStart(tx: SurfaceControl.Transaction)
        fun onCancelToDesktopAnimationEnd(tx: SurfaceControl.Transaction)
    }

    sealed class TransitionState {
        abstract val draggedTaskId: Int
        abstract val dragAnimator: MoveToDesktopAnimator
        abstract val windowDecoration: DesktopModeWindowDecoration
        abstract val startTransitionToken: IBinder
        abstract var startTransitionFinishCb: Transitions.TransitionFinishCallback?
        abstract var startTransitionFinishTransaction: SurfaceControl.Transaction?
        abstract var cancelTransitionToken: IBinder?
        abstract var homeToken: WindowContainerToken?
        abstract var draggedTaskChange: Change?
        abstract var cancelled: Boolean
        abstract var startAborted: Boolean

        data class FromFullscreen(
                override val draggedTaskId: Int,
                override val dragAnimator: MoveToDesktopAnimator,
                override val windowDecoration: DesktopModeWindowDecoration,
                override val startTransitionToken: IBinder,
                override var startTransitionFinishCb: Transitions.TransitionFinishCallback? = null,
                override var startTransitionFinishTransaction: SurfaceControl.Transaction? = null,
                override var cancelTransitionToken: IBinder? = null,
                override var homeToken: WindowContainerToken? = null,
                override var draggedTaskChange: Change? = null,
                override var cancelled: Boolean = false,
                override var startAborted: Boolean = false,
        ) : TransitionState()
        data class FromSplit(
                override val draggedTaskId: Int,
                override val dragAnimator: MoveToDesktopAnimator,
                override val windowDecoration: DesktopModeWindowDecoration,
                override val startTransitionToken: IBinder,
                override var startTransitionFinishCb: Transitions.TransitionFinishCallback? = null,
                override var startTransitionFinishTransaction: SurfaceControl.Transaction? = null,
                override var cancelTransitionToken: IBinder? = null,
                override var homeToken: WindowContainerToken? = null,
                override var draggedTaskChange: Change? = null,
                override var cancelled: Boolean = false,
                override var startAborted: Boolean = false,
                var splitRootChange: Change? = null,
        ) : TransitionState()
    }

    companion object {
        /** The duration of the animation to commit or cancel the drag-to-desktop gesture. */
        private const val DRAG_TO_DESKTOP_FINISH_ANIM_DURATION_MS = 336L
    }
}
