package com.android.systemui.statusbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.android.systemui.ExpandHelper
import com.android.systemui.Gefingerpoken
import com.android.systemui.animation.Interpolators
import com.android.systemui.R
import com.android.systemui.classifier.Classifier
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.notification.row.ExpandableView

/**
 * A utility class to enable the downward swipe on the lockscreen to go to the full shade and expand
 * the notification where the drag started.
 */
class DragDownHelper(private val falsingManager: FalsingManager,
                     private val expandCallback: ExpandHelper.Callback,
                     private val dragDownCallback: DragDownCallback,
                     private val falsingCollector: FalsingCollector,
                     private val host: View,
                     context: Context): Gefingerpoken {

    private val minDragDistance: Int
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private val touchSlop: Float
    private val slopMultiplier: Float
    private val temp2 = IntArray(2)
    private var draggedFarEnough = false
    private var startingChild: ExpandableView? = null
    private var lastHeight = 0f
    var isDraggingDown = false
        private set

    init {
        minDragDistance = context.resources.getDimensionPixelSize(
                R.dimen.keyguard_drag_down_min_distance)
        val configuration = ViewConfiguration.get(context)
        touchSlop = configuration.scaledTouchSlop.toFloat()
        slopMultiplier = configuration.scaledAmbiguousGestureMultiplier
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggedFarEnough = false
                isDraggingDown = false
                startingChild = null
                initialTouchY = y
                initialTouchX = x
            }
            MotionEvent.ACTION_MOVE -> {
                val h = y - initialTouchY
                // Adjust the touch slop if another gesture may be being performed.
                val touchSlop = if (event.classification
                        == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE)
                    touchSlop * slopMultiplier
                else
                    touchSlop
                if (h > touchSlop && h > Math.abs(x - initialTouchX)) {
                    falsingCollector.onNotificationStartDraggingDown()
                    isDraggingDown = true
                    captureStartingChild(initialTouchX, initialTouchY)
                    initialTouchY = y
                    initialTouchX = x
                    dragDownCallback.onTouchSlopExceeded()
                    return startingChild != null || dragDownCallback.isDragDownAnywhereEnabled
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDraggingDown) {
            return false
        }
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastHeight = y - initialTouchY
                captureStartingChild(initialTouchX, initialTouchY)
                if (startingChild != null) {
                    handleExpansion(lastHeight, startingChild!!)
                } else {
                    dragDownCallback.setEmptyDragAmount(lastHeight)
                }
                if (lastHeight > minDragDistance) {
                    if (!draggedFarEnough) {
                        draggedFarEnough = true
                        dragDownCallback.onCrossedThreshold(true)
                    }
                } else {
                    if (draggedFarEnough) {
                        draggedFarEnough = false
                        dragDownCallback.onCrossedThreshold(false)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> if (!falsingManager.isUnlockingDisabled && !isFalseTouch
                    && dragDownCallback.canDragDown()) {
                dragDownCallback.onDraggedDown(startingChild, (y - initialTouchY).toInt())
                if (startingChild == null) {
                    cancelExpansion()
                } else {
                    expandCallback.setUserLockedChild(startingChild, false)
                    startingChild = null
                }
                isDraggingDown = false
            } else {
                stopDragging()
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                stopDragging()
                return false
            }
        }
        return false
    }

    private val isFalseTouch: Boolean
        get() {
            return if (!dragDownCallback.isFalsingCheckNeeded) {
                false
            } else {
                falsingManager.isFalseTouch(Classifier.NOTIFICATION_DRAG_DOWN) || !draggedFarEnough
            }
        }

    private fun captureStartingChild(x: Float, y: Float) {
        if (startingChild == null) {
            startingChild = findView(x, y)
            if (startingChild != null) {
                if (dragDownCallback.isDragDownEnabledForView(startingChild)) {
                    expandCallback.setUserLockedChild(startingChild, true)
                } else {
                    startingChild = null
                }
            }
        }
    }

    private fun handleExpansion(heightDelta: Float, child: ExpandableView) {
        var hDelta = heightDelta
        if (hDelta < 0) {
            hDelta = 0f
        }
        val expandable = child.isContentExpandable
        val rubberbandFactor = if (expandable) {
            RUBBERBAND_FACTOR_EXPANDABLE
        } else {
            RUBBERBAND_FACTOR_STATIC
        }
        var rubberband = hDelta * rubberbandFactor
        if (expandable && rubberband + child.collapsedHeight > child.maxContentHeight) {
            var overshoot = rubberband + child.collapsedHeight - child.maxContentHeight
            overshoot *= 1 - RUBBERBAND_FACTOR_STATIC
            rubberband -= overshoot
        }
        child.actualHeight = (child.collapsedHeight + rubberband).toInt()
    }

    private fun cancelExpansion(child: ExpandableView) {
        if (child.actualHeight == child.collapsedHeight) {
            expandCallback.setUserLockedChild(child, false)
            return
        }
        val anim = ObjectAnimator.ofInt(child, "actualHeight",
                child.actualHeight, child.collapsedHeight)
        anim.interpolator = Interpolators.FAST_OUT_SLOW_IN
        anim.duration = SPRING_BACK_ANIMATION_LENGTH_MS.toLong()
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                expandCallback.setUserLockedChild(child, false)
            }
        })
        anim.start()
    }

    private fun cancelExpansion() {
        val anim = ValueAnimator.ofFloat(lastHeight, 0f)
        anim.interpolator = Interpolators.FAST_OUT_SLOW_IN
        anim.duration = SPRING_BACK_ANIMATION_LENGTH_MS.toLong()
        anim.addUpdateListener { animation: ValueAnimator ->
            dragDownCallback.setEmptyDragAmount(animation.animatedValue as Float)
        }
        anim.start()
    }

    private fun stopDragging() {
        falsingCollector.onNotificationStopDraggingDown()
        if (startingChild != null) {
            cancelExpansion(startingChild!!)
            startingChild = null
        } else {
            cancelExpansion()
        }
        isDraggingDown = false
        dragDownCallback.onDragDownReset()
    }

    private fun findView(x: Float, y: Float): ExpandableView {
        host.getLocationOnScreen(temp2)
        return expandCallback.getChildAtRawPosition(x + temp2[0] , y + temp2[1])
    }

    val isDragDownEnabled: Boolean
        get() = dragDownCallback.isDragDownEnabledForView(null)

    interface DragDownCallback {

        /**
         * @return true if the interaction is accepted, false if it should be cancelled
         */
        fun canDragDown(): Boolean

        /**
         * Call when a view has been dragged.
         **/
        fun onDraggedDown(startingChild: View?, dragLengthY: Int)
        fun onDragDownReset()

        /**
         * The user has dragged either above or below the threshold
         * @param above whether they dragged above it
         */
        fun onCrossedThreshold(above: Boolean)
        fun onTouchSlopExceeded()
        fun setEmptyDragAmount(amount: Float)
        val isFalsingCheckNeeded: Boolean

        /**
         * Is dragging down enabled on a given view
         * @param view The view to check or `null` to check if it's enabled at all
         */
        fun isDragDownEnabledForView(view: ExpandableView?): Boolean

        /**
         * @return if drag down is enabled anywhere, not just on selected views.
         */
        val isDragDownAnywhereEnabled: Boolean
    }

    companion object {
        private const val RUBBERBAND_FACTOR_EXPANDABLE = 0.5f
        private const val RUBBERBAND_FACTOR_STATIC = 0.15f
        private const val SPRING_BACK_ANIMATION_LENGTH_MS = 375
    }
}