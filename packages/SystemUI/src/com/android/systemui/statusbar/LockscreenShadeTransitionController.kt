package com.android.systemui.statusbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.util.IndentingPrintWriter
import android.util.MathUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.FloatRange
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.ExpandHelper
import com.android.systemui.Gefingerpoken
import com.android.systemui.biometrics.UdfpsKeyguardViewControllerLegacy
import com.android.systemui.classifier.Classifier
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.domain.interactor.NaturalScrollingSettingObserver
import com.android.systemui.keyguard.shared.KeyguardShadeMigrationNssl
import com.android.systemui.media.controls.ui.MediaHierarchyManager
import com.android.systemui.navigationbar.gestural.Utilities.isTrackpadScroll
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.LSShadeTransitionLogger
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.wm.shell.animation.Interpolators
import java.io.PrintWriter
import javax.inject.Inject

private const val SPRING_BACK_ANIMATION_LENGTH_MS = 375L
private const val RUBBERBAND_FACTOR_STATIC = 0.15f
private const val RUBBERBAND_FACTOR_EXPANDABLE = 0.5f

/** A class that controls the lockscreen to shade transition */
@SysUISingleton
class LockscreenShadeTransitionController
@Inject
constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    private val logger: LSShadeTransitionLogger,
    private val keyguardBypassController: KeyguardBypassController,
    private val lockScreenUserManager: NotificationLockscreenUserManager,
    private val falsingCollector: FalsingCollector,
    private val ambientState: AmbientState,
    private val mediaHierarchyManager: MediaHierarchyManager,
    private val scrimTransitionController: LockscreenShadeScrimTransitionController,
    private val keyguardTransitionControllerFactory:
        LockscreenShadeKeyguardTransitionController.Factory,
    private val depthController: NotificationShadeDepthController,
    private val context: Context,
    private val splitShadeOverScrollerFactory: SplitShadeLockScreenOverScroller.Factory,
    private val singleShadeOverScrollerFactory: SingleShadeLockScreenOverScroller.Factory,
    private val activityStarter: ActivityStarter,
    wakefulnessLifecycle: WakefulnessLifecycle,
    configurationController: ConfigurationController,
    falsingManager: FalsingManager,
    dumpManager: DumpManager,
    qsTransitionControllerFactory: LockscreenShadeQsTransitionController.Factory,
    private val shadeRepository: ShadeRepository,
    private val shadeInteractor: ShadeInteractor,
    private val powerInteractor: PowerInteractor,
    private val splitShadeStateController: SplitShadeStateController,
    private val naturalScrollingSettingObserver: NaturalScrollingSettingObserver,
) : Dumpable {
    private var pulseHeight: Float = 0f

    @get:VisibleForTesting
    var fractionToShade: Float = 0f
        private set
    private var useSplitShade: Boolean = false
    private lateinit var nsslController: NotificationStackScrollLayoutController
    lateinit var shadeViewController: ShadeViewController
    lateinit var centralSurfaces: CentralSurfaces
    lateinit var qS: QS

    /** A handler that handles the next keyguard dismiss animation. */
    private var animationHandlerOnKeyguardDismiss: ((Long) -> Unit)? = null

    /** The entry that was just dragged down on. */
    private var draggedDownEntry: NotificationEntry? = null

    /** The current animator if any */
    @VisibleForTesting internal var dragDownAnimator: ValueAnimator? = null

    /** The current pulse height animator if any */
    @VisibleForTesting internal var pulseHeightAnimator: ValueAnimator? = null

    /** Distance that the full shade transition takes in order to complete. */
    private var fullTransitionDistance = 0

    /**
     * Distance that the full transition takes in order for us to fully transition to the shade by
     * tapping on a button, such as "expand".
     */
    private var fullTransitionDistanceByTap = 0

    /**
     * Distance that the full shade transition takes in order for the notification shelf to fully
     * expand.
     */
    private var notificationShelfTransitionDistance = 0

    /**
     * Distance that the full shade transition takes in order for depth of the wallpaper to fully
     * change.
     */
    private var depthControllerTransitionDistance = 0

    /**
     * Distance that the full shade transition takes in order for the UDFPS Keyguard View to fully
     * fade.
     */
    private var udfpsTransitionDistance = 0

    /**
     * Used for StatusBar to know that a transition is in progress. At the moment it only checks
     * whether the progress is > 0, therefore this value is not very important.
     */
    private var statusBarTransitionDistance = 0

    /**
     * Flag to make sure that the dragDownAmount is applied to the listeners even when in the locked
     * down shade.
     */
    private var forceApplyAmount = false

    /** A flag to suppress the default animation when unlocking in the locked down shade. */
    private var nextHideKeyguardNeedsNoAnimation = false

    /** Are we currently waking up to the shade locked */
    var isWakingToShadeLocked: Boolean = false
        private set

    /** The distance until we're showing the notifications when pulsing */
    val distanceUntilShowingPulsingNotifications
        get() = fullTransitionDistance

    /** The udfpsKeyguardViewController if it exists. */
    var mUdfpsKeyguardViewControllerLegacy: UdfpsKeyguardViewControllerLegacy? = null

    /** The touch helper responsible for the drag down animation. */
    val touchHelper =
        DragDownHelper(
            falsingManager,
            falsingCollector,
            this,
            naturalScrollingSettingObserver,
            shadeRepository,
            context
        )

    private val splitShadeOverScroller: SplitShadeLockScreenOverScroller by lazy {
        splitShadeOverScrollerFactory.create({ qS }, { nsslController })
    }

    private val phoneShadeOverScroller: SingleShadeLockScreenOverScroller by lazy {
        singleShadeOverScrollerFactory.create(nsslController)
    }

    private val keyguardTransitionController by lazy {
        keyguardTransitionControllerFactory.create(shadeViewController)
    }

    private val qsTransitionController = qsTransitionControllerFactory.create { qS }

    private val callbacks = mutableListOf<Callback>()

    /** See [LockscreenShadeQsTransitionController.qsTransitionFraction]. */
    @get:FloatRange(from = 0.0, to = 1.0)
    val qSDragProgress: Float
        get() = qsTransitionController.qsTransitionFraction

    /** See [LockscreenShadeQsTransitionController.qsSquishTransitionFraction]. */
    @get:FloatRange(from = 0.0, to = 1.0)
    val qsSquishTransitionFraction: Float
        get() = qsTransitionController.qsSquishTransitionFraction

    /**
     * [LockScreenShadeOverScroller] property that delegates to either
     * [SingleShadeLockScreenOverScroller] or [SplitShadeLockScreenOverScroller].
     *
     * There are currently two different implementations, as the over scroll behavior is different
     * on single shade and split shade.
     *
     * On single shade, only notifications are over scrolled, whereas on split shade, everything is
     * over scrolled.
     */
    private val shadeOverScroller: LockScreenShadeOverScroller
        get() = if (useSplitShade) splitShadeOverScroller else phoneShadeOverScroller

    init {
        updateResources()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                    touchHelper.updateResources(context)
                }
            }
        )
        dumpManager.registerDumpable(this)
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onExpandedChanged(isExpanded: Boolean) {
                    // safeguard: When the panel is fully collapsed, let's make sure to reset.
                    // See b/198098523
                    if (!isExpanded) {
                        if (dragDownAmount != 0f && dragDownAnimator?.isRunning != true) {
                            logger.logDragDownAmountResetWhenFullyCollapsed()
                            dragDownAmount = 0f
                        }
                        if (pulseHeight != 0f && pulseHeightAnimator?.isRunning != true) {
                            logger.logPulseHeightNotResetWhenFullyCollapsed()
                            setPulseHeight(0f, animate = false)
                        }
                    }
                }
            }
        )
        wakefulnessLifecycle.addObserver(
            object : WakefulnessLifecycle.Observer {
                override fun onPostFinishedWakingUp() {
                    // when finishing waking up, the UnlockedScreenOffAnimation has another attempt
                    // to reset keyguard. Let's do it in post
                    isWakingToShadeLocked = false
                }
            }
        )
    }

    private fun updateResources() {
        fullTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_full_transition_distance
            )
        fullTransitionDistanceByTap =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_transition_by_tap_distance
            )
        notificationShelfTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_notif_shelf_transition_distance
            )
        depthControllerTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_depth_controller_transition_distance
            )
        udfpsTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_udfps_keyguard_transition_distance
            )
        statusBarTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_status_bar_transition_distance
            )

        useSplitShade = splitShadeStateController.shouldUseSplitNotificationShade(context.resources)
    }

    fun setStackScroller(nsslController: NotificationStackScrollLayoutController) {
        this.nsslController = nsslController
        touchHelper.expandCallback = nsslController.expandHelperCallback
    }

    /** @return true if the interaction is accepted, false if it should be cancelled */
    internal fun canDragDown(): Boolean {
        return (statusBarStateController.state == StatusBarState.KEYGUARD ||
            nsslController.isInLockedDownShade()) && (qS.isFullyCollapsed || useSplitShade)
    }

    /** Called by the touch helper when when a gesture has completed all the way and released. */
    internal fun onDraggedDown(startingChild: View?, dragLengthY: Int) {
        if (canDragDown()) {
            val cancelRunnable = Runnable {
                logger.logGoingToLockedShadeAborted()
                setDragDownAmountAnimated(0f)
            }
            if (nsslController.isInLockedDownShade()) {
                logger.logDraggedDownLockDownShade(startingChild)
                statusBarStateController.setLeaveOpenOnKeyguardHide(true)
                activityStarter.dismissKeyguardThenExecute(
                    {
                        nextHideKeyguardNeedsNoAnimation = true
                        false
                    },
                    cancelRunnable,
                    /* afterKeyguardGone= */ false,
                )
            } else {
                logger.logDraggedDown(startingChild, dragLengthY)
                if (!ambientState.isDozing() || startingChild != null) {
                    // go to locked shade while animating the drag down amount from its current
                    // value
                    val animationHandler = { delay: Long ->
                        if (startingChild is ExpandableNotificationRow) {
                            startingChild.onExpandedByGesture(
                                true /* drag down is always an open */
                            )
                        }
                        shadeViewController.transitionToExpandedShade(delay)
                        callbacks.forEach {
                            it.setTransitionToFullShadeAmount(0f, /* animated= */ true, delay)
                        }

                        // Let's reset ourselves, ready for the next animation

                        // changing to shade locked will make isInLockDownShade true, so let's
                        // override that
                        forceApplyAmount = true
                        // Reset the behavior. At this point the animation is already started
                        logger.logDragDownAmountReset()
                        dragDownAmount = 0f
                        forceApplyAmount = false
                    }
                    goToLockedShadeInternal(startingChild, animationHandler, cancelRunnable)
                }
            }
        } else {
            logger.logUnSuccessfulDragDown(startingChild)
            setDragDownAmountAnimated(0f)
        }
    }

    /** Called by the touch helper when the drag down was aborted and should be reset. */
    internal fun onDragDownReset() {
        logger.logDragDownAborted()
        nsslController.setDimmed(
            /* dimmed= */ true,
            /* animate= */ true,
        )
        nsslController.resetScrollPosition()
        nsslController.resetCheckSnoozeLeavebehind()
        setDragDownAmountAnimated(0f)
    }

    /**
     * The user has dragged either above or below the threshold which changes the dimmed state.
     *
     * @param above whether they dragged above it
     */
    internal fun onCrossedThreshold(above: Boolean) {
        nsslController.setDimmed(
            /* dimmed= */ !above,
            /* animate= */ true,
        )
    }

    /** Called by the touch helper when the drag down was started */
    internal fun onDragDownStarted(startingChild: ExpandableView?) {
        logger.logDragDownStarted(startingChild)
        nsslController.cancelLongPress()
        nsslController.checkSnoozeLeavebehind()
        dragDownAnimator?.apply {
            if (isRunning) {
                logger.logAnimationCancelled(isPulse = false)
                cancel()
            }
        }
    }

    /** Do we need a falsing check currently? */
    internal val isFalsingCheckNeeded: Boolean
        get() = statusBarStateController.state == StatusBarState.KEYGUARD

    /**
     * Is dragging down enabled on a given view
     *
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

    /** @return if drag down is enabled anywhere, not just on selected views. */
    internal val isDragDownAnywhereEnabled: Boolean
        get() =
            (statusBarStateController.getState() == StatusBarState.KEYGUARD &&
                !keyguardBypassController.bypassEnabled &&
                (qS.isFullyCollapsed || useSplitShade))

    /** The amount in pixels that the user has dragged down. */
    internal var dragDownAmount = 0f
        set(value) {
            if (field != value || forceApplyAmount) {
                field = value
                if (!nsslController.isInLockedDownShade() || field == 0f || forceApplyAmount) {
                    fractionToShade =
                        MathUtils.saturate(dragDownAmount / notificationShelfTransitionDistance)
                    shadeRepository.setLockscreenShadeExpansion(fractionToShade)
                    nsslController.setTransitionToFullShadeAmount(fractionToShade)

                    qsTransitionController.dragDownAmount = value

                    callbacks.forEach {
                        it.setTransitionToFullShadeAmount(
                            field,
                            /* animate= */ false,
                            /* delay= */ 0,
                        )
                    }

                    mediaHierarchyManager.setTransitionToFullShadeAmount(field)
                    scrimTransitionController.dragDownAmount = value
                    transitionToShadeAmountCommon(field)
                    keyguardTransitionController.dragDownAmount = value
                    shadeOverScroller.expansionDragDownAmount = dragDownAmount
                }
            }
        }

    private fun transitionToShadeAmountCommon(dragDownAmount: Float) {
        if (depthControllerTransitionDistance == 0) { // split shade
            depthController.transitionToFullShadeProgress = 0f
        } else {
            val depthProgress =
                MathUtils.saturate(dragDownAmount / depthControllerTransitionDistance)
            depthController.transitionToFullShadeProgress = depthProgress
        }

        val udfpsProgress = MathUtils.saturate(dragDownAmount / udfpsTransitionDistance)
        shadeRepository.setUdfpsTransitionToFullShadeProgress(udfpsProgress)
        mUdfpsKeyguardViewControllerLegacy?.setTransitionToFullShadeProgress(udfpsProgress)

        val statusBarProgress = MathUtils.saturate(dragDownAmount / statusBarTransitionDistance)
        centralSurfaces.setTransitionToFullShadeProgress(statusBarProgress)
    }

    private fun setDragDownAmountAnimated(
        target: Float,
        delay: Long = 0,
        endlistener: (() -> Unit)? = null
    ) {
        logger.logDragDownAnimation(target)
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
            dragDownAnimator.addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        endlistener.invoke()
                    }
                }
            )
        }
        dragDownAnimator.start()
        this.dragDownAnimator = dragDownAnimator
    }

    /** Animate appear the drag down amount. */
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
        setDragDownAmountAnimated(fullTransitionDistanceByTap.toFloat(), delay = delay) {
            // End listener:
            // Reset
            logger.logDragDownAmountReset()
            dragDownAmount = 0f
            forceApplyAmount = false
        }
    }

    /**
     * Ask this controller to go to the locked shade, changing the state change and doing an
     * animation, where the qs appears from 0 from the top
     *
     * If secure with redaction: Show bouncer, go to unlocked shade. If secure without redaction or
     * no security: Go to [StatusBarState.SHADE_LOCKED].
     *
     * Split shade is special case and [needsQSAnimation] will be always overridden to true. That's
     * because handheld shade will automatically follow notifications animation, but that's not the
     * case for split shade.
     *
     * @param expandView The view to expand after going to the shade
     * @param needsQSAnimation if this needs the quick settings to slide in from the top or if
     *   that's already handled separately. This argument will be ignored on split shade as there QS
     *   animation can't be handled separately.
     */
    @JvmOverloads
    fun goToLockedShade(expandedView: View?, needsQSAnimation: Boolean = true) {
        val isKeyguard = statusBarStateController.state == StatusBarState.KEYGUARD
        logger.logTryGoToLockedShade(isKeyguard)
        if (isKeyguard) {
            val animationHandler: ((Long) -> Unit)?
            if (needsQSAnimation || useSplitShade) {
                // Let's use the default animation
                animationHandler = null
            } else {
                // Let's only animate notifications
                animationHandler = { delay: Long ->
                    shadeViewController.transitionToExpandedShade(delay)
                }
            }
            goToLockedShadeInternal(expandedView, animationHandler, cancelAction = null)
        }
    }

    /**
     * If secure with redaction: Show bouncer, go to unlocked shade.
     *
     * If secure without redaction or no security: Go to [StatusBarState.SHADE_LOCKED].
     *
     * @param expandView The view to expand after going to the shade.
     * @param animationHandler The handler which performs the go to full shade animation. If null,
     *   the default handler will do the animation, otherwise the caller is responsible for the
     *   animation. The input value is a Long for the delay for the animation.
     * @param cancelAction The runnable to invoke when the transition is aborted. This happens if
     *   the user goes to the bouncer and goes back.
     */
    private fun goToLockedShadeInternal(
        expandView: View?,
        animationHandler: ((Long) -> Unit)? = null,
        cancelAction: Runnable? = null
    ) {
        if (!shadeInteractor.isShadeEnabled.value) {
            cancelAction?.run()
            logger.logShadeDisabledOnGoToLockedShade()
            return
        }
        var userId: Int = lockScreenUserManager.getCurrentUserId()
        var entry: NotificationEntry? = null
        if (expandView is ExpandableNotificationRow) {
            entry = expandView.entry
            entry.setUserExpanded(
                /* userExpanded= */ true,
                /* allowChildExpansion= */ true,
            )
            // Indicate that the group expansion is changing at this time -- this way the group
            // and children backgrounds / divider animations will look correct.
            entry.setGroupExpansionChanging(true)
            userId = entry.sbn.userId
        }
        var fullShadeNeedsBouncer =
            (!lockScreenUserManager.shouldShowLockscreenNotifications() ||
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
                    notifyHeightChanged(
                        /* needsAnimation= */ false,
                    )
                    draggedDownEntry = null
                }
                cancelAction?.run()
            }
            logger.logShowBouncerOnGoToLockedShade()
            centralSurfaces.showBouncerWithDimissAndCancelIfKeyguard(onDismissAction, cancelHandler)
            draggedDownEntry = entry
        } else {
            logger.logGoingToLockedShade(animationHandler != null)
            if (statusBarStateController.isDozing) {
                // Make sure we don't go back to keyguard immediately again after waking up
                isWakingToShadeLocked = true
            }
            statusBarStateController.setState(StatusBarState.SHADE_LOCKED)
            // This call needs to be after updating the shade state since otherwise
            // the scrimstate resets too early
            if (animationHandler != null) {
                animationHandler.invoke(
                    /* delay= */ 0,
                )
            } else {
                performDefaultGoToFullShadeAnimation(0)
            }
        }
    }

    /**
     * Notify this handler that the keyguard was just dismissed and that a animation to the full
     * shade should happen.
     *
     * @param delay the delay to do the animation with
     * @param previousState which state were we in when we hid the keyguard?
     */
    fun onHideKeyguard(delay: Long, previousState: Int) {
        logger.logOnHideKeyguard()
        if (animationHandlerOnKeyguardDismiss != null) {
            animationHandlerOnKeyguardDismiss!!.invoke(delay)
            animationHandlerOnKeyguardDismiss = null
        } else {
            if (nextHideKeyguardNeedsNoAnimation) {
                nextHideKeyguardNeedsNoAnimation = false
            } else if (previousState != StatusBarState.SHADE_LOCKED) {
                // No animation necessary if we already were in the shade locked!
                performDefaultGoToFullShadeAnimation(delay)
            }
        }
        draggedDownEntry?.apply {
            setUserLocked(false)
            draggedDownEntry = null
        }
    }

    /**
     * Perform the default appear animation when going to the full shade. This is called when not
     * triggered by gestures, e.g. when clicking on the shelf or expand button.
     */
    private fun performDefaultGoToFullShadeAnimation(delay: Long) {
        logger.logDefaultGoToFullShadeAnimation(delay)
        shadeViewController.transitionToExpandedShade(delay)
        animateAppear(delay)
    }

    //
    // PULSE EXPANSION
    //

    /**
     * Set the height how tall notifications are pulsing. This is only set whenever we are expanding
     * from a pulse and determines how much the notifications are expanded.
     */
    fun setPulseHeight(height: Float, animate: Boolean = false) {
        if (animate) {
            val pulseHeightAnimator = ValueAnimator.ofFloat(pulseHeight, height)
            pulseHeightAnimator.interpolator = Interpolators.FAST_OUT_SLOW_IN
            pulseHeightAnimator.duration = SPRING_BACK_ANIMATION_LENGTH_MS
            pulseHeightAnimator.addUpdateListener { animation: ValueAnimator ->
                setPulseHeight(animation.animatedValue as Float)
            }
            pulseHeightAnimator.start()
            this.pulseHeightAnimator = pulseHeightAnimator
        } else {
            pulseHeight = height
            val overflow = nsslController.setPulseHeight(height)
            shadeViewController.setOverStretchAmount(overflow)
            val transitionHeight = if (keyguardBypassController.bypassEnabled) height else 0.0f
            transitionToShadeAmountCommon(transitionHeight)
        }
    }

    /**
     * Finish the pulse animation when the touch interaction finishes
     *
     * @param cancelled was the interaction cancelled and this is a reset?
     */
    fun finishPulseAnimation(cancelled: Boolean) {
        logger.logPulseExpansionFinished(cancelled)
        if (cancelled) {
            setPulseHeight(0f, animate = true)
        } else {
            callbacks.forEach { it.onPulseExpansionFinished() }
            setPulseHeight(0f, animate = false)
        }
    }

    /** Notify this class that a pulse expansion is starting */
    fun onPulseExpansionStarted() {
        logger.logPulseExpansionStarted()
        pulseHeightAnimator?.apply {
            if (isRunning) {
                logger.logAnimationCancelled(isPulse = true)
                cancel()
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").let {
            it.println("LSShadeTransitionController:")
            it.increaseIndent()
            it.println("pulseHeight: $pulseHeight")
            it.println("useSplitShade: $useSplitShade")
            it.println("dragDownAmount: $dragDownAmount")
            it.println("isDragDownAnywhereEnabled: $isDragDownAnywhereEnabled")
            it.println("isFalsingCheckNeeded: $isFalsingCheckNeeded")
            it.println("isWakingToShadeLocked: $isWakingToShadeLocked")
            it.println(
                "hasPendingHandlerOnKeyguardDismiss: " +
                    "${animationHandlerOnKeyguardDismiss != null}"
            )
        }
    }

    fun addCallback(callback: Callback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback)
        }
    }

    /** Callback for authentication events. */
    interface Callback {
        /** TODO: comment here */
        fun onPulseExpansionFinished() {}

        /**
         * Sets the amount of pixels we have currently dragged down if we're transitioning to the
         * full shade. 0.0f means we're not transitioning yet.
         */
        fun setTransitionToFullShadeAmount(pxAmount: Float, animate: Boolean, delay: Long) {}
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
    private val naturalScrollingSettingObserver: NaturalScrollingSettingObserver,
    private val shadeRepository: ShadeRepository,
    context: Context
) : Gefingerpoken {

    private var dragDownAmountOnStart = 0.0f
    lateinit var expandCallback: ExpandHelper.Callback

    private var minDragDistance = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchSlop = 0f
    private var slopMultiplier = 0f
    private var draggedFarEnough = false
    private var startingChild: ExpandableView? = null
    private var lastHeight = 0f
    private var isTrackpadReverseScroll = false
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
        minDragDistance =
            context.resources.getDimensionPixelSize(R.dimen.keyguard_drag_down_min_distance)
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
                isTrackpadReverseScroll =
                    !naturalScrollingSettingObserver.isNaturalScrollingEnabled &&
                        isTrackpadScroll(true, event)
            }
            MotionEvent.ACTION_MOVE -> {
                val h = (if (isTrackpadReverseScroll) -1 else 1) * (y - initialTouchY)
                // Adjust the touch slop if another gesture may be being performed.
                val touchSlop =
                    if (event.classification == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE) {
                        touchSlop * slopMultiplier
                    } else {
                        touchSlop
                    }
                if (h > touchSlop && h > Math.abs(x - initialTouchX)) {
                    isDraggingDown = true
                    captureStartingChild(initialTouchX, initialTouchY)
                    initialTouchY = y
                    initialTouchX = x
                    dragDownCallback.onDragDownStarted(startingChild)
                    dragDownAmountOnStart = dragDownCallback.dragDownAmount
                    val intercepted =
                        startingChild != null || dragDownCallback.isDragDownAnywhereEnabled
                    if (intercepted) {
                        shadeRepository.setLegacyLockscreenShadeTracking(true)
                    }
                    return intercepted
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
                lastHeight = (if (isTrackpadReverseScroll) -1 else 1) * (y - initialTouchY)
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
            MotionEvent.ACTION_UP ->
                if (
                    !falsingManager.isUnlockingDisabled &&
                        !isFalseTouch &&
                        dragDownCallback.canDragDown()
                ) {
                    val dragDown = (if (isTrackpadReverseScroll) -1 else 1) * (y - initialTouchY)
                    dragDownCallback.onDraggedDown(startingChild, dragDown.toInt())
                    if (startingChild != null) {
                        expandCallback.setUserLockedChild(startingChild, false)
                        startingChild = null
                    }
                    isDraggingDown = false
                    isTrackpadReverseScroll = false
                    shadeRepository.setLegacyLockscreenShadeTracking(false)
                    if (KeyguardShadeMigrationNssl.isEnabled) {
                        return true
                    }
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
        val rubberbandFactor =
            if (expandable) {
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

    @VisibleForTesting
    fun cancelChildExpansion(
        child: ExpandableView,
        animationDuration: Long = SPRING_BACK_ANIMATION_LENGTH_MS
    ) {
        if (child.actualHeight == child.collapsedHeight) {
            expandCallback.setUserLockedChild(child, false)
            return
        }
        val anim = ValueAnimator.ofInt(child.actualHeight, child.collapsedHeight)
        anim.interpolator = Interpolators.FAST_OUT_SLOW_IN
        anim.duration = animationDuration
        anim.addUpdateListener { animation: ValueAnimator ->
            // don't use reflection, because the `actualHeight` field may be obfuscated
            child.actualHeight = animation.animatedValue as Int
        }
        anim.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    expandCallback.setUserLockedChild(child, false)
                }
            }
        )
        anim.start()
    }

    private fun stopDragging() {
        if (startingChild != null) {
            cancelChildExpansion(startingChild!!)
            startingChild = null
        }
        isDraggingDown = false
        isTrackpadReverseScroll = false
        shadeRepository.setLegacyLockscreenShadeTracking(false)
        dragDownCallback.onDragDownReset()
    }

    private fun findView(x: Float, y: Float): ExpandableView? {
        return expandCallback.getChildAtRawPosition(x, y)
    }
}
