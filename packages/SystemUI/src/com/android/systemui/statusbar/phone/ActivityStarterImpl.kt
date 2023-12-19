/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.RemoteAnimationAdapter
import android.view.View
import android.view.WindowManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.ActivityLaunchAnimator.PendingIntentStarter
import com.android.systemui.animation.DelegateLaunchAnimatorController
import com.android.systemui.assist.AssistManager
import com.android.systemui.camera.CameraIntents.Companion.isInsecureCameraIntent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.kotlin.getOrNull
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject

/** Handles start activity logic in SystemUI. */
@SysUISingleton
class ActivityStarterImpl
@Inject
constructor(
    private val centralSurfacesOptLazy: Lazy<Optional<CentralSurfaces>>,
    private val assistManagerLazy: Lazy<AssistManager>,
    private val dozeServiceHostLazy: Lazy<DozeServiceHost>,
    private val biometricUnlockControllerLazy: Lazy<BiometricUnlockController>,
    private val keyguardViewMediatorLazy: Lazy<KeyguardViewMediator>,
    private val shadeControllerLazy: Lazy<ShadeController>,
    private val shadeViewControllerLazy: Lazy<ShadeViewController>,
    private val shadeAnimationInteractor: ShadeAnimationInteractor,
    private val statusBarKeyguardViewManagerLazy: Lazy<StatusBarKeyguardViewManager>,
    private val notifShadeWindowControllerLazy: Lazy<NotificationShadeWindowController>,
    private val activityLaunchAnimator: ActivityLaunchAnimator,
    private val context: Context,
    @DisplayId private val displayId: Int,
    private val lockScreenUserManager: NotificationLockscreenUserManager,
    private val statusBarWindowController: StatusBarWindowController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val keyguardStateController: KeyguardStateController,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val userTracker: UserTracker,
    private val activityIntentHelper: ActivityIntentHelper,
    @Main private val mainExecutor: DelayableExecutor,
) : ActivityStarter {
    companion object {
        const val TAG = "ActivityStarterImpl"
    }

    private val centralSurfaces: CentralSurfaces?
        get() = centralSurfacesOptLazy.get().getOrNull()

    private val activityStarterInternal = ActivityStarterInternal()

    override fun startPendingIntentDismissingKeyguard(intent: PendingIntent) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(intent = intent)
    }

    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
        )
    }

    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
        associatedView: View?,
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            associatedView = associatedView,
        )
    }

    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
        animationController: ActivityLaunchAnimator.Controller?,
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            animationController = animationController,
        )
    }

    override fun startPendingIntentMaybeDismissingKeyguard(
        intent: PendingIntent,
        intentSentUiThreadCallback: Runnable?,
        animationController: ActivityLaunchAnimator.Controller?
    ) {
        activityStarterInternal.startPendingIntentDismissingKeyguard(
            intent = intent,
            intentSentUiThreadCallback = intentSentUiThreadCallback,
            animationController = animationController,
            showOverLockscreen = true,
        )
    }

    /**
     * TODO(b/279084380): Change callers to just call startActivityDismissingKeyguard and deprecate
     *   this.
     */
    override fun startActivity(intent: Intent, dismissShade: Boolean) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            dismissShade = dismissShade,
        )
    }

    /**
     * TODO(b/279084380): Change callers to just call startActivityDismissingKeyguard and deprecate
     *   this.
     */
    override fun startActivity(intent: Intent, onlyProvisioned: Boolean, dismissShade: Boolean) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            onlyProvisioned = onlyProvisioned,
            dismissShade = dismissShade,
        )
    }

    /**
     * TODO(b/279084380): Change callers to just call startActivityDismissingKeyguard and deprecate
     *   this.
     */
    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        callback: ActivityStarter.Callback?,
    ) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            dismissShade = dismissShade,
            callback = callback,
        )
    }

    /**
     * TODO(b/279084380): Change callers to just call startActivityDismissingKeyguard and deprecate
     *   this.
     */
    override fun startActivity(
        intent: Intent,
        onlyProvisioned: Boolean,
        dismissShade: Boolean,
        flags: Int,
    ) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            onlyProvisioned = onlyProvisioned,
            dismissShade = dismissShade,
            flags = flags,
        )
    }

    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        animationController: ActivityLaunchAnimator.Controller?,
        showOverLockscreenWhenLocked: Boolean,
    ) {
        activityStarterInternal.startActivity(
            intent = intent,
            dismissShade = dismissShade,
            animationController = animationController,
            showOverLockscreenWhenLocked = showOverLockscreenWhenLocked,
        )
    }
    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        animationController: ActivityLaunchAnimator.Controller?,
        showOverLockscreenWhenLocked: Boolean,
        userHandle: UserHandle?,
    ) {
        activityStarterInternal.startActivity(
            intent = intent,
            dismissShade = dismissShade,
            animationController = animationController,
            showOverLockscreenWhenLocked = showOverLockscreenWhenLocked,
            userHandle = userHandle,
        )
    }

    override fun postStartActivityDismissingKeyguard(intent: PendingIntent) {
        postOnUiThread {
            activityStarterInternal.startPendingIntentDismissingKeyguard(
                intent = intent,
            )
        }
    }

    override fun postStartActivityDismissingKeyguard(
        intent: PendingIntent,
        animationController: ActivityLaunchAnimator.Controller?
    ) {
        postOnUiThread {
            activityStarterInternal.startPendingIntentDismissingKeyguard(
                intent = intent,
                animationController = animationController,
            )
        }
    }

    override fun postStartActivityDismissingKeyguard(intent: Intent, delay: Int) {
        postOnUiThread(delay) {
            activityStarterInternal.startActivityDismissingKeyguard(
                intent = intent,
                onlyProvisioned = true,
                dismissShade = true,
            )
        }
    }

    override fun postStartActivityDismissingKeyguard(
        intent: Intent,
        delay: Int,
        animationController: ActivityLaunchAnimator.Controller?,
    ) {
        postOnUiThread(delay) {
            activityStarterInternal.startActivityDismissingKeyguard(
                intent = intent,
                onlyProvisioned = true,
                dismissShade = true,
                animationController = animationController,
            )
        }
    }

    override fun postStartActivityDismissingKeyguard(
        intent: Intent,
        delay: Int,
        animationController: ActivityLaunchAnimator.Controller?,
        customMessage: String?,
    ) {
        postOnUiThread(delay) {
            activityStarterInternal.startActivityDismissingKeyguard(
                intent = intent,
                onlyProvisioned = true,
                dismissShade = true,
                animationController = animationController,
                customMessage = customMessage,
            )
        }
    }

    override fun dismissKeyguardThenExecute(
        action: OnDismissAction,
        cancel: Runnable?,
        afterKeyguardGone: Boolean,
    ) {
        activityStarterInternal.dismissKeyguardThenExecute(
            action = action,
            cancel = cancel,
            afterKeyguardGone = afterKeyguardGone,
        )
    }

    override fun dismissKeyguardThenExecute(
        action: OnDismissAction,
        cancel: Runnable?,
        afterKeyguardGone: Boolean,
        customMessage: String?,
    ) {
        activityStarterInternal.dismissKeyguardThenExecute(
            action = action,
            cancel = cancel,
            afterKeyguardGone = afterKeyguardGone,
            customMessage = customMessage,
        )
    }

    override fun startActivityDismissingKeyguard(
        intent: Intent,
        onlyProvisioned: Boolean,
        dismissShade: Boolean,
    ) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            onlyProvisioned = onlyProvisioned,
            dismissShade = dismissShade,
        )
    }

    override fun startActivityDismissingKeyguard(
        intent: Intent,
        onlyProvisioned: Boolean,
        dismissShade: Boolean,
        disallowEnterPictureInPictureWhileLaunching: Boolean,
        callback: ActivityStarter.Callback?,
        flags: Int,
        animationController: ActivityLaunchAnimator.Controller?,
        userHandle: UserHandle?,
    ) {
        activityStarterInternal.startActivityDismissingKeyguard(
            intent = intent,
            onlyProvisioned = onlyProvisioned,
            dismissShade = dismissShade,
            disallowEnterPictureInPictureWhileLaunching =
                disallowEnterPictureInPictureWhileLaunching,
            callback = callback,
            flags = flags,
            animationController = animationController,
            userHandle = userHandle,
        )
    }

    override fun executeRunnableDismissingKeyguard(
        runnable: Runnable?,
        cancelAction: Runnable?,
        dismissShade: Boolean,
        afterKeyguardGone: Boolean,
        deferred: Boolean,
    ) {
        activityStarterInternal.executeRunnableDismissingKeyguard(
            runnable = runnable,
            cancelAction = cancelAction,
            dismissShade = dismissShade,
            afterKeyguardGone = afterKeyguardGone,
            deferred = deferred,
        )
    }

    override fun postQSRunnableDismissingKeyguard(runnable: Runnable?) {
        postOnUiThread {
            statusBarStateController.setLeaveOpenOnKeyguardHide(true)
            activityStarterInternal.executeRunnableDismissingKeyguard(
                runnable = { runnable?.let { postOnUiThread(runnable = it) } },
            )
        }
    }

    private fun postOnUiThread(delay: Int = 0, runnable: Runnable) {
        mainExecutor.executeDelayed(runnable, delay.toLong())
    }

    /**
     * Whether we should animate an activity launch.
     *
     * Note: This method must be called *before* dismissing the keyguard.
     */
    private fun shouldAnimateLaunch(
        isActivityIntent: Boolean,
        showOverLockscreen: Boolean,
    ): Boolean {
        // TODO(b/294418322): Support launch animations when occluded.
        if (keyguardStateController.isOccluded) {
            return false
        }

        // Always animate if we are not showing the keyguard or if we animate over the lockscreen
        // (without unlocking it).
        if (showOverLockscreen || !keyguardStateController.isShowing) {
            return true
        }

        // We don't animate non-activity launches as they can break the animation.
        // TODO(b/184121838): Support non activity launches on the lockscreen.
        return isActivityIntent
    }

    override fun shouldAnimateLaunch(isActivityIntent: Boolean): Boolean {
        return shouldAnimateLaunch(isActivityIntent, false)
    }

    /**
     * Encapsulates the activity logic for activity starter.
     *
     * Logic is duplicated in {@link CentralSurfacesImpl}
     */
    private inner class ActivityStarterInternal {
        /** Starts an activity after dismissing keyguard. */
        fun startActivityDismissingKeyguard(
            intent: Intent,
            onlyProvisioned: Boolean = false,
            dismissShade: Boolean = false,
            disallowEnterPictureInPictureWhileLaunching: Boolean = false,
            callback: ActivityStarter.Callback? = null,
            flags: Int = 0,
            animationController: ActivityLaunchAnimator.Controller? = null,
            userHandle: UserHandle? = null,
            customMessage: String? = null,
        ) {
            val userHandle: UserHandle = userHandle ?: getActivityUserHandle(intent)

            if (onlyProvisioned && !deviceProvisionedController.isDeviceProvisioned) return

            val willLaunchResolverActivity: Boolean =
                activityIntentHelper.wouldLaunchResolverActivity(
                    intent,
                    lockScreenUserManager.currentUserId
                )

            val animate =
                animationController != null &&
                    !willLaunchResolverActivity &&
                    shouldAnimateLaunch(isActivityIntent = true)
            val animController =
                wrapAnimationControllerForShadeOrStatusBar(
                    animationController = animationController,
                    dismissShade = dismissShade,
                    isLaunchForActivity = true,
                )

            // If we animate, we will dismiss the shade only once the animation is done. This is
            // taken care of by the StatusBarLaunchAnimationController.
            val dismissShadeDirectly = dismissShade && animController == null

            val runnable = Runnable {
                assistManagerLazy.get().hideAssist()
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                intent.addFlags(flags)
                val result = intArrayOf(ActivityManager.START_CANCELED)
                activityLaunchAnimator.startIntentWithAnimation(
                    animController,
                    animate,
                    intent.getPackage()
                ) { adapter: RemoteAnimationAdapter? ->
                    val options =
                        ActivityOptions(CentralSurfaces.getActivityOptions(displayId, adapter))

                    // We know that the intent of the caller is to dismiss the keyguard and
                    // this runnable is called right after the keyguard is solved, so we tell
                    // WM that we should dismiss it to avoid flickers when opening an activity
                    // that can also be shown over the keyguard.
                    options.setDismissKeyguardIfInsecure()
                    options.setDisallowEnterPictureInPictureWhileLaunching(
                        disallowEnterPictureInPictureWhileLaunching
                    )
                    if (isInsecureCameraIntent(intent)) {
                        // Normally an activity will set it's requested rotation
                        // animation on its window. However when launching an activity
                        // causes the orientation to change this is too late. In these cases
                        // the default animation is used. This doesn't look good for
                        // the camera (as it rotates the camera contents out of sync
                        // with physical reality). So, we ask the WindowManager to
                        // force the cross fade animation if an orientation change
                        // happens to occur during the launch.
                        options.rotationAnimationHint =
                            WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
                    }
                    if (Settings.Panel.ACTION_VOLUME == intent.action) {
                        // Settings Panel is implemented as activity(not a dialog), so
                        // underlying app is paused and may enter picture-in-picture mode
                        // as a result.
                        // So we need to disable picture-in-picture mode here
                        // if it is volume panel.
                        options.setDisallowEnterPictureInPictureWhileLaunching(true)
                    }
                    try {
                        result[0] =
                            ActivityTaskManager.getService()
                                .startActivityAsUser(
                                    null,
                                    context.basePackageName,
                                    context.attributionTag,
                                    intent,
                                    intent.resolveTypeIfNeeded(context.contentResolver),
                                    null,
                                    null,
                                    0,
                                    Intent.FLAG_ACTIVITY_NEW_TASK,
                                    null,
                                    options.toBundle(),
                                    userHandle.identifier,
                                )
                    } catch (e: RemoteException) {
                        Log.w(TAG, "Unable to start activity", e)
                    }
                    result[0]
                }
                callback?.onActivityStarted(result[0])
            }
            val cancelRunnable = Runnable {
                callback?.onActivityStarted(ActivityManager.START_CANCELED)
            }
            // Do not deferKeyguard when occluded because, when keyguard is occluded,
            // we do not launch the activity until keyguard is done.
            val occluded = (keyguardStateController.isShowing && keyguardStateController.isOccluded)
            val deferred = !occluded
            executeRunnableDismissingKeyguard(
                runnable,
                cancelRunnable,
                dismissShadeDirectly,
                willLaunchResolverActivity,
                deferred,
                animate,
                customMessage,
            )
        }

        /**
         * Starts a pending intent after dismissing keyguard.
         *
         * This can be called in a background thread (to prevent calls in [ActivityIntentHelper] in
         * the main thread).
         */
        fun startPendingIntentDismissingKeyguard(
            intent: PendingIntent,
            intentSentUiThreadCallback: Runnable? = null,
            associatedView: View? = null,
            animationController: ActivityLaunchAnimator.Controller? = null,
            showOverLockscreen: Boolean = false,
        ) {
            val animationController =
                if (associatedView is ExpandableNotificationRow) {
                    centralSurfaces?.getAnimatorControllerFromNotification(associatedView)
                } else animationController

            val willLaunchResolverActivity =
                (intent.isActivity &&
                    activityIntentHelper.wouldPendingLaunchResolverActivity(
                        intent,
                        lockScreenUserManager.currentUserId,
                    ))

            val actuallyShowOverLockscreen =
                showOverLockscreen &&
                    intent.isActivity &&
                    activityIntentHelper.wouldPendingShowOverLockscreen(
                        intent,
                        lockScreenUserManager.currentUserId
                    )

            val animate =
                !willLaunchResolverActivity &&
                    animationController != null &&
                    shouldAnimateLaunch(intent.isActivity, actuallyShowOverLockscreen)

            // We wrap animationCallback with a StatusBarLaunchAnimatorController so
            // that the shade is collapsed after the animation (or when it is cancelled,
            // aborted, etc).
            val statusBarController =
                wrapAnimationControllerForShadeOrStatusBar(
                    animationController = animationController,
                    dismissShade = true,
                    isLaunchForActivity = intent.isActivity,
                )
            val controller =
                if (actuallyShowOverLockscreen) {
                    wrapAnimationControllerForLockscreen(statusBarController)
                } else {
                    statusBarController
                }

            // If we animate, don't collapse the shade and defer the keyguard dismiss (in case we
            // run the animation on the keyguard). The animation will take care of (instantly)
            // collapsing the shade and hiding the keyguard once it is done.
            val collapse = !animate
            val runnable = Runnable {
                try {
                    activityLaunchAnimator.startPendingIntentWithAnimation(
                        controller,
                        animate,
                        intent.creatorPackage,
                        actuallyShowOverLockscreen,
                        object : PendingIntentStarter {
                            override fun startPendingIntent(
                                animationAdapter: RemoteAnimationAdapter?
                            ): Int {
                                val options =
                                    ActivityOptions(
                                        CentralSurfaces.getActivityOptions(
                                            displayId,
                                            animationAdapter
                                        )
                                    )
                                // TODO b/221255671: restrict this to only be set for
                                // notifications
                                options.isEligibleForLegacyPermissionPrompt = true
                                options.setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                                )
                                return intent.sendAndReturnResult(
                                    null,
                                    0,
                                    null,
                                    null,
                                    null,
                                    null,
                                    options.toBundle()
                                )
                            }
                        },
                    )
                } catch (e: PendingIntent.CanceledException) {
                    // the stack trace isn't very helpful here.
                    // Just log the exception message.
                    Log.w(TAG, "Sending intent failed: $e")
                    if (!collapse) {
                        // executeRunnableDismissingKeyguard did not collapse for us already.
                        shadeControllerLazy.get().collapseOnMainThread()
                    }
                    // TODO: Dismiss Keyguard.
                }
                if (intent.isActivity) {
                    assistManagerLazy.get().hideAssist()
                }
                intentSentUiThreadCallback?.let { postOnUiThread(runnable = it) }
            }

            if (!actuallyShowOverLockscreen) {
                postOnUiThread(delay = 0) {
                    executeRunnableDismissingKeyguard(
                        runnable = runnable,
                        afterKeyguardGone = willLaunchResolverActivity,
                        dismissShade = collapse,
                        willAnimateOnKeyguard = animate,
                    )
                }
            } else {
                postOnUiThread(delay = 0, runnable)
            }
        }

        /** Starts an Activity. */
        fun startActivity(
            intent: Intent,
            dismissShade: Boolean = false,
            animationController: ActivityLaunchAnimator.Controller? = null,
            showOverLockscreenWhenLocked: Boolean = false,
            userHandle: UserHandle? = null,
        ) {
            val userHandle = userHandle ?: getActivityUserHandle(intent)
            // Make sure that we dismiss the keyguard if it is directly dismissible or when we don't
            // want to show the activity above it.
            if (keyguardStateController.isUnlocked || !showOverLockscreenWhenLocked) {
                startActivityDismissingKeyguard(
                    intent = intent,
                    onlyProvisioned = false,
                    dismissShade = dismissShade,
                    disallowEnterPictureInPictureWhileLaunching = false,
                    callback = null,
                    flags = 0,
                    animationController = animationController,
                    userHandle = userHandle,
                )
                return
            }

            val animate =
                animationController != null &&
                    shouldAnimateLaunch(
                        /* isActivityIntent= */ true,
                        showOverLockscreenWhenLocked
                    ) == true

            var controller: ActivityLaunchAnimator.Controller? = null
            if (animate) {
                // Wrap the animation controller to dismiss the shade and set
                // mIsLaunchingActivityOverLockscreen during the animation.
                val delegate =
                    wrapAnimationControllerForShadeOrStatusBar(
                        animationController = animationController,
                        dismissShade = dismissShade,
                        isLaunchForActivity = true,
                    )
                controller = wrapAnimationControllerForLockscreen(delegate)
            } else if (dismissShade) {
                // The animation will take care of dismissing the shade at the end of the animation.
                // If we don't animate, collapse it directly.
                shadeControllerLazy.get().cancelExpansionAndCollapseShade()
            }

            // We should exit the dream to prevent the activity from starting below the
            // dream.
            if (keyguardUpdateMonitor.isDreaming) {
                centralSurfaces?.awakenDreams()
            }

            activityLaunchAnimator.startIntentWithAnimation(
                controller,
                animate,
                intent.getPackage(),
                showOverLockscreenWhenLocked
            ) { adapter: RemoteAnimationAdapter? ->
                TaskStackBuilder.create(context)
                    .addNextIntent(intent)
                    .startActivities(
                        CentralSurfaces.getActivityOptions(displayId, adapter),
                        userHandle
                    )
            }
        }

        /** Executes an action after dismissing keyguard. */
        fun dismissKeyguardThenExecute(
            action: OnDismissAction,
            cancel: Runnable? = null,
            afterKeyguardGone: Boolean = false,
            customMessage: String? = null,
        ) {
            if (
                !action.willRunAnimationOnKeyguard() &&
                    wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_ASLEEP &&
                    keyguardStateController.canDismissLockScreen() &&
                    !statusBarStateController.leaveOpenOnKeyguardHide() &&
                    dozeServiceHostLazy.get().isPulsing
            ) {
                // Reuse the biometric wake-and-unlock transition if we dismiss keyguard from a
                // pulse.
                // TODO: Factor this transition out of BiometricUnlockController.
                biometricUnlockControllerLazy
                    .get()
                    .startWakeAndUnlock(BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING)
            }
            if (keyguardStateController.isShowing) {
                statusBarKeyguardViewManagerLazy
                    .get()
                    .dismissWithAction(action, cancel, afterKeyguardGone, customMessage)
            } else {
                // If the keyguard isn't showing but the device is dreaming, we should exit the
                // dream.
                if (keyguardUpdateMonitor.isDreaming) {
                    centralSurfaces?.awakenDreams()
                }
                action.onDismiss()
            }
        }

        /** Executes an action after dismissing keyguard. */
        fun executeRunnableDismissingKeyguard(
            runnable: Runnable? = null,
            cancelAction: Runnable? = null,
            dismissShade: Boolean = false,
            afterKeyguardGone: Boolean = false,
            deferred: Boolean = false,
            willAnimateOnKeyguard: Boolean = false,
            customMessage: String? = null,
        ) {
            val onDismissAction: OnDismissAction =
                object : OnDismissAction {
                    override fun onDismiss(): Boolean {
                        if (runnable != null) {
                            if (
                                keyguardStateController.isShowing &&
                                    keyguardStateController.isOccluded
                            ) {
                                statusBarKeyguardViewManagerLazy
                                    .get()
                                    .addAfterKeyguardGoneRunnable(runnable)
                            } else {
                                mainExecutor.execute(runnable)
                            }
                        }
                        if (dismissShade) {
                            shadeControllerLazy.get().collapseShadeForActivityStart()
                        }
                        return deferred
                    }

                    override fun willRunAnimationOnKeyguard(): Boolean {
                        return willAnimateOnKeyguard
                    }
                }
            dismissKeyguardThenExecute(
                onDismissAction,
                cancelAction,
                afterKeyguardGone,
                customMessage,
            )
        }

        /**
         * Return a [ActivityLaunchAnimator.Controller] wrapping `animationController` so that:
         * - if it launches in the notification shade window and `dismissShade` is true, then the
         *   shade will be instantly dismissed at the end of the animation.
         * - if it launches in status bar window, it will make the status bar window match the
         *   device size during the animation (that way, the animation won't be clipped by the
         *   status bar size).
         *
         * @param animationController the controller that is wrapped and will drive the main
         *   animation.
         * @param dismissShade whether the notification shade will be dismissed at the end of the
         *   animation. This is ignored if `animationController` is not animating in the shade
         *   window.
         * @param isLaunchForActivity whether the launch is for an activity.
         */
        private fun wrapAnimationControllerForShadeOrStatusBar(
            animationController: ActivityLaunchAnimator.Controller?,
            dismissShade: Boolean,
            isLaunchForActivity: Boolean,
        ): ActivityLaunchAnimator.Controller? {
            if (animationController == null) {
                return null
            }
            val rootView = animationController.launchContainer.rootView
            val controllerFromStatusBar: Optional<ActivityLaunchAnimator.Controller> =
                statusBarWindowController.wrapAnimationControllerIfInStatusBar(
                    rootView,
                    animationController
                )
            if (controllerFromStatusBar.isPresent) {
                return controllerFromStatusBar.get()
            }

            centralSurfaces?.let {
                // If the view is not in the status bar, then we are animating a view in the shade.
                // We have to make sure that we collapse it when the animation ends or is cancelled.
                if (dismissShade) {
                    return StatusBarLaunchAnimatorController(
                        animationController,
                        shadeViewControllerLazy.get(),
                        shadeAnimationInteractor,
                        shadeControllerLazy.get(),
                        notifShadeWindowControllerLazy.get(),
                        isLaunchForActivity
                    )
                }
            }

            return animationController
        }

        /**
         * Wraps an animation controller so that if an activity would be launched on top of the
         * lockscreen, the correct flags are set for it to be occluded.
         */
        private fun wrapAnimationControllerForLockscreen(
            animationController: ActivityLaunchAnimator.Controller?
        ): ActivityLaunchAnimator.Controller? {
            return animationController?.let {
                object : DelegateLaunchAnimatorController(it) {
                    override fun onIntentStarted(willAnimate: Boolean) {
                        delegate.onIntentStarted(willAnimate)
                        if (willAnimate) {
                            centralSurfaces?.setIsLaunchingActivityOverLockscreen(true)
                        }
                    }

                    override fun onLaunchAnimationStart(isExpandingFullyAbove: Boolean) {
                        super.onLaunchAnimationStart(isExpandingFullyAbove)

                        // Double check that the keyguard is still showing and not going
                        // away, but if so set the keyguard occluded. Typically, WM will let
                        // KeyguardViewMediator know directly, but we're overriding that to
                        // play the custom launch animation, so we need to take care of that
                        // here. The unocclude animation is not overridden, so WM will call
                        // KeyguardViewMediator's unocclude animation runner when the
                        // activity is exited.
                        if (
                            keyguardStateController.isShowing &&
                                !keyguardStateController.isKeyguardGoingAway
                        ) {
                            Log.d(TAG, "Setting occluded = true in #startActivity.")
                            keyguardViewMediatorLazy
                                .get()
                                .setOccluded(true /* isOccluded */, true /* animate */)
                        }
                    }

                    override fun onLaunchAnimationEnd(isExpandingFullyAbove: Boolean) {
                        // Set mIsLaunchingActivityOverLockscreen to false before actually
                        // finishing the animation so that we can assume that
                        // mIsLaunchingActivityOverLockscreen being true means that we will
                        // collapse the shade (or at least run the post collapse runnables)
                        // later on.
                        centralSurfaces?.setIsLaunchingActivityOverLockscreen(false)
                        delegate.onLaunchAnimationEnd(isExpandingFullyAbove)
                    }

                    override fun onLaunchAnimationCancelled(newKeyguardOccludedState: Boolean?) {
                        if (newKeyguardOccludedState != null) {
                            keyguardViewMediatorLazy
                                .get()
                                .setOccluded(newKeyguardOccludedState, false /* animate */)
                        }

                        // Set mIsLaunchingActivityOverLockscreen to false before actually
                        // finishing the animation so that we can assume that
                        // mIsLaunchingActivityOverLockscreen being true means that we will
                        // collapse the shade (or at least run the // post collapse
                        // runnables) later on.
                        centralSurfaces?.setIsLaunchingActivityOverLockscreen(false)
                        delegate.onLaunchAnimationCancelled(newKeyguardOccludedState)
                    }
                }
            }
        }

        /** Retrieves the current user handle to start the Activity. */
        private fun getActivityUserHandle(intent: Intent): UserHandle {
            val packages: Array<String> =
                context.resources.getStringArray(R.array.system_ui_packages)
            for (pkg in packages) {
                val componentName = intent.component ?: break
                if (pkg == componentName.packageName) {
                    return UserHandle(UserHandle.myUserId())
                }
            }
            return userTracker.userHandle
        }
    }
}
