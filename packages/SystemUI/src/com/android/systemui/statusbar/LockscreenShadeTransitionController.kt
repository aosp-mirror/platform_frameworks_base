package com.android.systemui.statusbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.MathUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.ExpandHelper
import com.android.systemui.Gefingerpoken
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.classifier.Classifier
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaHierarchyManager
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.LockscreenGestureLogger
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent
import com.android.systemui.statusbar.phone.NotificationPanelViewController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.StatusBar
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.Utils
import javax.inject.Inject

private const val SPRING_BACK_ANIMATION_LENGTH_MS = 375L
private const val RUBBERBAND_FACTOR_STATIC = 0.15f
private const val RUBBERBAND_FACTOR_EXPANDABLE = 0.5f

/**
 * A class that controls the lockscreen to shade transition
 */
@SysUISingleton
class LockscreenShadeTransitionController @Inject constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    private val lockscreenGestureLogger: LockscreenGestureLogger,
    private val keyguardBypassController: KeyguardBypassController,
    private val lockScreenUserManager: NotificationLockscreenUserManager,
    private val falsingCollector: FalsingCollector,
    private val ambientState: AmbientState,
    private val displayMetrics: DisplayMetrics,
    private val mediaHierarchyManager: MediaHierarchyManager,
    private val scrimController: ScrimController,
    private val featureFlags: FeatureFlags,
    private val context: Context,
    configurationController: ConfigurationController,
    falsingManager: FalsingManager
) {
    private var useSplitShade: Boolean = false
    private lateinit var nsslController: NotificationStackScrollLayoutController
    lateinit var notificationPanelController: NotificationPanelViewController
    lateinit var statusbar: StatusBar
    lateinit var qS: QS

    /**
     * A handler that handles the next keyguard dismiss animation.
     */
    private var animationHandlerOnKeyguardDismiss: ((Long) -> Unit)? = null

    /**
     * The entry that was just dragged down on.
     */
    private var draggedDownEntry: NotificationEntry? = null

    /**
     * The current animator if any
     */
    @VisibleForTesting
    internal var dragDownAnimator: ValueAnimator? = null

    /**
     * Distance that the full shade transition takes in order for scrim to fully transition to
     * the shade (in alpha)
     */
    private var scrimTransitionDistance = 0

    /**
     * Distance that the full transition takes in order for us to fully transition to the shade
     */
    private var fullTransitionDistance = 0

    /**
     * Flag to make sure that the dragDownAmount is applied to the listeners even when in the
     * locked down shade.
     */
    private var forceApplyAmount = false

    /**
     * A flag to suppress the default animation when unlocking in the locked down shade.
     */
    private var nextHideKeyguardNeedsNoAnimation = false

    /**
     * The touch helper responsible for the drag down animation.
     */
    val touchHelper = DragDownHelper(falsingManager, falsingCollector, this, context)

    init {
        updateResources()
        configurationController.addCallback(object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                updateResources()
                touchHelper.updateResources(context)
            }
        })
    }

    private fun updateResources() {
        scrimTransitionDistance = context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_scrim_transition_distance)
        fullTransitionDistance = context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_transition_distance)
        useSplitShade = Utils.shouldUseSplitNotificationShade(featureFlags, context.resources)
    }

    fun setStackScroller(nsslController: NotificationStackScrollLayoutController) {
        this.nsslController = nsslController
        touchHelper.host = nsslController.view
        touchHelper.expandCallback = nsslController.expandHelperCallback
    }

    /**
     * Initialize the shelf controller such that clicks on it will expand the shade
     */
    fun bindController(notificationShelfController: NotificationShelfController) {
        // Bind the click listener of the shelf to go to the full shade
        notificationShelfController.setOnClickListener {
            if (statusBarStateController.state == StatusBarState.KEYGUARD) {
                statusbar.wakeUpIfDozing(SystemClock.uptimeMillis(), it, "SHADE_CLICK")
                goToLockedShade(it)
            }
        }
    }

    /**
     * @return true if the interaction is accepted, false if it should be cancelled
     */
    internal fun canDragDown(): Boolean {
        return (statusBarStateController.state == StatusBarState.KEYGUARD ||
                nsslController.isInLockedDownShade()) &&
                qS.isFullyCollapsed
    }

    /**
     * Called by the touch helper when when a gesture has completed all the way and released.
     */
    internal fun onDraggedDown(startingChild: View?, dragLengthY: Int) {
        if (canDragDown()) {
            if (nsslController.isInLockedDownShade()) {
                statusBarStateController.setLeaveOpenOnKeyguardHide(true)
                statusbar.dismissKeyguardThenExecute(OnDismissAction {
                    nextHideKeyguardNeedsNoAnimation = true
                    false
                },
                        null /* cancelRunnable */, false /* afterKeyguardGone */)
            } else {
                lockscreenGestureLogger.write(
                        MetricsEvent.ACTION_LS_SHADE,
                        (dragLengthY / displayMetrics.density).toInt(),
                        0 /* velocityDp */)
                lockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_PULL_SHADE_OPEN)
                if (!ambientState.isDozing() || startingChild != null) {
                    // go to locked shade while animating the drag down amount from its current
                    // value
                    val animationHandler = { delay: Long ->
                        if (startingChild is ExpandableNotificationRow) {
                            startingChild.onExpandedByGesture(
                                    true /* drag down is always an open */)
                        }
                        notificationPanelController.animateToFullShade(delay)
                        notificationPanelController.setTransitionToFullShadeAmount(0f,
                                true /* animated */, delay)

                        // Let's reset ourselves, ready for the next animation

                        // changing to shade locked will make isInLockDownShade true, so let's
                        // override that
                        forceApplyAmount = true
                        // Reset the behavior. At this point the animation is already started
                        dragDownAmount = 0f
                        forceApplyAmount = false
                    }
                    val cancelRunnable = Runnable { setDragDownAmountAnimated(0f) }
                    goToLockedShadeInternal(startingChild, animationHandler, cancelRunnable)
                }
            }
        } else {
            setDragDownAmountAnimated(0f)
        }
    }

    /**
     * Called by the touch helper when the drag down was aborted and should be reset.
     */
    internal fun onDragDownReset() {
        nsslController.setDimmed(true /* dimmed */, true /* animated */)
        nsslController.resetScrollPosition()
        nsslController.resetCheckSnoozeLeavebehind()
        setDragDownAmountAnimated(0f)
    }

    /**
     * The user has dragged either above or below the threshold which changes the dimmed state.
     * @param above whether they dragged above it
     */
    internal fun onCrossedThreshold(above: Boolean) {
        nsslController.setDimmed(!above /* dimmed */, true /* animate */)
    }

    /**
     * Called by the touch helper when the drag down was started
     */
    internal fun onDragDownStarted() {
        nsslController.cancelLongPress()
        nsslController.checkSnoozeLeavebehind()
        dragDownAnimator?.cancel()
    }

    /**
     * Do we need a falsing check currently?
     */
    internal val isFalsingCheckNeeded: Boolean
        get() = statusBarStateController.state == StatusBarState.KEYGUARD

    /**
     * Is dragging down enabled on a given view
     * @param view The view to check or `null` to check if it's enabled at all
     */
    internal fun isDragDownEnabledForView(view: ExpandableView?): Boolean {
        if (isDragDownAnywhereEnabled) {
            return true
        }
        if (nsslController.isInLockedDownShade()) {
            if (view == null) {
                // Dragging down is allowed in general
                return true
            }
            if (view is ExpandableNotificationRow) {
                // Only drag down on sensitive views, otherwise the ExpandHelper will take this
                return view.entry.isSensitive
            }
        }
        return false
    }

    /**
     * @return if drag down is enabled anywhere, not just on selected views.
     */
    internal val isDragDownAnywhereEnabled: Boolean
        get() = (statusBarStateController.getState() == StatusBarState.KEYGUARD &&
                !keyguardBypassController.bypassEnabled &&
                qS.isFullyCollapsed)

    /**
     * The amount in pixels that the user has dragged down.
     */
    internal var dragDownAmount = 0f
        set(value) {
            if (field != value || forceApplyAmount) {
                field = value
                if (!nsslController.isInLockedDownShade() || forceApplyAmount) {
                    nsslController.setTransitionToFullShadeAmount(field)
                    notificationPanelController.setTransitionToFullShadeAmount(field,
                            false /* animate */, 0 /* delay */)
                    mediaHierarchyManager.setTransitionToFullShadeAmount(field)
                    val scrimProgress = MathUtils.saturate(field / scrimTransitionDistance)
                    scrimController.setTransitionToFullShadeProgress(scrimProgress)
                    // TODO: appear qs also in split shade
                    val qsAmount = if (useSplitShade) 0f else field
                    qS.setTransitionToFullShadeAmount(qsAmount, false /* animate */)
                }
            }
        }

    private fun setDragDownAmountAnimated(
        target: Float,
        delay: Long = 0,
        endlistener: (() -> Unit)? = null
    ) {
        val dragDownAnimator = ValueAnimator.ofFloat(dragDownAmount, target)
        dragDownAnimator.interpolator = Interpolators.FAST_OUT_SLOW_IN
        dragDownAnimator.duration = SPRING_BACK_ANIMATION_LENGTH_MS
        dragDownAnimator.addUpdateListener { animation: ValueAnimator ->
            dragDownAmount = animation.animatedValue as Float
        }
        if (delay > 0) {
            dragDownAnimator.startDelay = delay
        }
        if (endlistener != null) {
            dragDownAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    endlistener.invoke()
                }
            })
        }
        dragDownAnimator.start()
        this.dragDownAnimator = dragDownAnimator
    }

    /**
     * Animate appear the drag down amount.
     */
    private fun animateAppear(delay: Long = 0) {
        // changing to shade locked will make isInLockDownShade true, so let's override
        // that
        forceApplyAmount = true

        // we set the value initially to 1 pixel, since that will make sure we're
        // transitioning to the full shade. this is important to avoid flickering,
        // as the below animation only starts once the shade is unlocked, which can
        // be a couple of frames later. if we're setting it to 0, it will use the
        // default inset and therefore flicker
        dragDownAmount = 1f
        setDragDownAmountAnimated(fullTransitionDistance.toFloat(), delay = delay) {
            // End listener:
            // Reset
            dragDownAmount = 0f
            forceApplyAmount = false
        }
    }

    /**
     * Ask this controller to go to the locked shade, changing the state change and doing
     * an animation, where the qs appears from 0 from the top
     *
     * If secure with redaction: Show bouncer, go to unlocked shade.
     * If secure without redaction or no security: Go to [StatusBarState.SHADE_LOCKED].
     *
     * @param expandView The view to expand after going to the shade
     * @param needsQSAnimation if this needs the quick settings to slide in from the top or if
     *                         that's already handled separately
     */
    @JvmOverloads
    fun goToLockedShade(expandedView: View?, needsQSAnimation: Boolean = true) {
        if (statusBarStateController.state == StatusBarState.KEYGUARD) {
            val animationHandler: ((Long) -> Unit)?
            if (needsQSAnimation) {
                // Let's use the default animation
                animationHandler = null
            } else {
                // Let's only animate notifications
                animationHandler = { delay: Long ->
                    notificationPanelController.animateToFullShade(delay)
                }
            }
            goToLockedShadeInternal(expandedView, animationHandler,
                    cancelAction = null)
        }
    }

    /**
     * If secure with redaction: Show bouncer, go to unlocked shade.
     *
     * If secure without redaction or no security: Go to [StatusBarState.SHADE_LOCKED].
     *
     * @param expandView The view to expand after going to the shade.
     * @param animationHandler The handler which performs the go to full shade animation. If null,
     *                         the default handler will do the animation, otherwise the caller is
     *                         responsible for the animation. The input value is a Long for the
     *                         delay for the animation.
     * @param cancelAction The runnable to invoke when the transition is aborted. This happens if
     *                     the user goes to the bouncer and goes back.
     */
    private fun goToLockedShadeInternal(
        expandView: View?,
        animationHandler: ((Long) -> Unit)? = null,
        cancelAction: Runnable? = null
    ) {
        if (statusbar.isShadeDisabled) {
            cancelAction?.run()
            return
        }
        var userId: Int = lockScreenUserManager.getCurrentUserId()
        var entry: NotificationEntry? = null
        if (expandView is ExpandableNotificationRow) {
            entry = expandView.entry
            entry.setUserExpanded(true /* userExpanded */, true /* allowChildExpansion */)
            // Indicate that the group expansion is changing at this time -- this way the group
            // and children backgrounds / divider animations will look correct.
            entry.setGroupExpansionChanging(true)
            userId = entry.sbn.userId
        }
        var fullShadeNeedsBouncer = (!lockScreenUserManager.userAllowsPrivateNotificationsInPublic(
                lockScreenUserManager.getCurrentUserId()) ||
                !lockScreenUserManager.shouldShowLockscreenNotifications() ||
                falsingCollector.shouldEnforceBouncer())
        if (keyguardBypassController.bypassEnabled) {
            fullShadeNeedsBouncer = false
        }
        if (lockScreenUserManager.isLockscreenPublicMode(userId) && fullShadeNeedsBouncer) {
            statusBarStateController.setLeaveOpenOnKeyguardHide(true)
            var onDismissAction: OnDismissAction? = null
            if (animationHandler != null) {
                onDismissAction = OnDismissAction {
                    // We're waiting on keyguard to hide before triggering the action,
                    // as that will make the animation work properly
                    animationHandlerOnKeyguardDismiss = animationHandler
                    false
                }
            }
            val cancelHandler = Runnable {
                draggedDownEntry?.apply {
                    setUserLocked(false)
                    notifyHeightChanged(false /* needsAnimation */)
                    draggedDownEntry = null
                }
                cancelAction?.run()
            }
            statusbar.showBouncerWithDimissAndCancelIfKeyguard(onDismissAction, cancelHandler)
            draggedDownEntry = entry
        } else {
            statusBarStateController.setState(StatusBarState.SHADE_LOCKED)
            // This call needs to be after updating the shade state since otherwise
            // the scrimstate resets too early
            if (animationHandler != null) {
                animationHandler.invoke(0 /* delay */)
            } else {
                performDefaultGoToFullShadeAnimation(0)
            }
        }
    }

    /**
     * Notify this handler that the keyguard was just dismissed and that a animation to
     * the full shade should happen.
     */
    fun onHideKeyguard(delay: Long) {
        if (animationHandlerOnKeyguardDismiss != null) {
            animationHandlerOnKeyguardDismiss!!.invoke(delay)
            animationHandlerOnKeyguardDismiss = null
        } else {
            if (nextHideKeyguardNeedsNoAnimation) {
                nextHideKeyguardNeedsNoAnimation = false
            } else {
                performDefaultGoToFullShadeAnimation(delay)
            }
        }
        draggedDownEntry?.apply {
            setUserLocked(false)
            draggedDownEntry = null
        }
    }

    /**
     * Perform the default appear animation when going to the full shade. This is called when
     * not triggered by gestures, e.g. when clicking on the shelf or expand button.
     */
    private fun performDefaultGoToFullShadeAnimation(delay: Long) {
        notificationPanelController.animateToFullShade(delay)
        animateAppear(delay)
    }
}

/**
 * A utility class to enable the downward swipe on the lockscreen to go to the full shade and expand
 * the notification where the drag started.
 */
class DragDownHelper(
    private val falsingManager: FalsingManager,
    private val falsingCollector: FalsingCollector,
    private val dragDownCallback: LockscreenShadeTransitionController,
    context: Context
) : Gefingerpoken {

    private var dragDownAmountOnStart = 0.0f
    lateinit var expandCallback: ExpandHelper.Callback
    lateinit var host: View

    private var minDragDistance = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchSlop = 0f
    private var slopMultiplier = 0f
    private val temp2 = IntArray(2)
    private var draggedFarEnough = false
    private var startingChild: ExpandableView? = null
    private var lastHeight = 0f
    var isDraggingDown = false
        private set

    private val isFalseTouch: Boolean
        get() {
            return if (!dragDownCallback.isFalsingCheckNeeded) {
                false
            } else {
                falsingManager.isFalseTouch(Classifier.NOTIFICATION_DRAG_DOWN) || !draggedFarEnough
            }
        }

    val isDragDownEnabled: Boolean
        get() = dragDownCallback.isDragDownEnabledForView(null)

    init {
        updateResources(context)
    }

    fun updateResources(context: Context) {
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
                    dragDownCallback.onDragDownStarted()
                    dragDownAmountOnStart = dragDownCallback.dragDownAmount
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
                dragDownCallback.dragDownAmount = lastHeight + dragDownAmountOnStart
                if (startingChild != null) {
                    handleExpansion(lastHeight, startingChild!!)
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
            MotionEvent.ACTION_UP -> if (!falsingManager.isUnlockingDisabled && !isFalseTouch &&
                    dragDownCallback.canDragDown()) {
                dragDownCallback.onDraggedDown(startingChild, (y - initialTouchY).toInt())
                if (startingChild != null) {
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

    private fun cancelChildExpansion(child: ExpandableView) {
        if (child.actualHeight == child.collapsedHeight) {
            expandCallback.setUserLockedChild(child, false)
            return
        }
        val anim = ObjectAnimator.ofInt(child, "actualHeight",
                child.actualHeight, child.collapsedHeight)
        anim.interpolator = Interpolators.FAST_OUT_SLOW_IN
        anim.duration = SPRING_BACK_ANIMATION_LENGTH_MS
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                expandCallback.setUserLockedChild(child, false)
            }
        })
        anim.start()
    }

    private fun stopDragging() {
        falsingCollector.onNotificationStopDraggingDown()
        if (startingChild != null) {
            cancelChildExpansion(startingChild!!)
            startingChild = null
        }
        isDraggingDown = false
        dragDownCallback.onDragDownReset()
    }

    private fun findView(x: Float, y: Float): ExpandableView? {
        host.getLocationOnScreen(temp2)
        return expandCallback.getChildAtRawPosition(x + temp2[0], y + temp2[1])
    }
}