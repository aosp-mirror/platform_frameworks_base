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

package com.android.systemui.statusbar.phone

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.ActivityTaskManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.RemoteAnimationAdapter
import android.view.View
import android.view.WindowManager
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.Flags
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DelegateTransitionAnimatorController
import com.android.systemui.assist.AssistManager
import com.android.systemui.camera.CameraIntents
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.kotlin.getOrNull
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Encapsulates the activity logic for activity starter when the SceneContainerFlag is enabled.
 *
 * TODO: b/308819693
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class ActivityStarterInternalImpl
@Inject
constructor(
    private val statusBarKeyguardViewManagerLazy: Lazy<StatusBarKeyguardViewManager>,
    private val keyguardInteractor: KeyguardInteractor,
    private val centralSurfacesOptLazy: Lazy<Optional<CentralSurfaces>>,
    private val context: Context,
    @Main private val resources: Resources,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val activityTransitionAnimator: ActivityTransitionAnimator,
    @DisplayId private val displayId: Int,
    private val deviceProvisioningInteractor: DeviceProvisioningInteractor,
    private val activityIntentHelper: ActivityIntentHelper,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val assistManagerLazy: Lazy<AssistManager>,
    @Main private val mainExecutor: DelayableExecutor,
    private val shadeControllerLazy: Lazy<ShadeController>,
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val statusBarWindowController: StatusBarWindowController,
    private val keyguardViewMediatorLazy: Lazy<KeyguardViewMediator>,
    private val shadeAnimationInteractor: ShadeAnimationInteractor,
    private val notifShadeWindowControllerLazy: Lazy<NotificationShadeWindowController>,
    private val commandQueue: CommandQueue,
    private val lockScreenUserManager: NotificationLockscreenUserManager,
) : ActivityStarterInternal {
    private val centralSurfaces: CentralSurfaces?
        get() = centralSurfacesOptLazy.get().getOrNull()

    override fun startPendingIntentDismissingKeyguard(
        intent: PendingIntent,
        dismissShade: Boolean,
        intentSentUiThreadCallback: Runnable?,
        associatedView: View?,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreen: Boolean,
        skipLockscreenChecks: Boolean,
        fillInIntent: Intent?,
        extraOptions: Bundle?,
        customMessage: String?,
    ) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return
        val animationController =
            if (associatedView is ExpandableNotificationRow) {
                centralSurfaces?.getAnimatorControllerFromNotification(associatedView)
            } else animationController

        val willLaunchResolverActivity =
            intent.isActivity &&
                activityIntentHelper.wouldPendingLaunchResolverActivity(
                    intent,
                    lockScreenUserManager.currentUserId,
                )

        val actuallyShowOverLockscreen =
            showOverLockscreen &&
                intent.isActivity &&
                (skipLockscreenChecks ||
                    activityIntentHelper.wouldPendingShowOverLockscreen(
                        intent,
                        lockScreenUserManager.currentUserId
                    ))

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
                dismissShade = dismissShade,
                isLaunchForActivity = intent.isActivity,
            )
        val controller =
            if (actuallyShowOverLockscreen) {
                wrapAnimationControllerForLockscreen(dismissShade, statusBarController)
            } else {
                statusBarController
            }

        // If we animate, don't collapse the shade and defer the keyguard dismiss (in case we
        // run the animation on the keyguard). The animation will take care of (instantly)
        // collapsing the shade and hiding the keyguard once it is done.
        val collapse = dismissShade && !animate
        val runnable = Runnable {
            try {
                activityTransitionAnimator.startPendingIntentWithAnimation(
                    controller,
                    animate,
                    intent.creatorPackage,
                    actuallyShowOverLockscreen,
                    object : ActivityTransitionAnimator.PendingIntentStarter {
                        override fun startPendingIntent(
                            animationAdapter: RemoteAnimationAdapter?
                        ): Int {
                            val options =
                                ActivityOptions(
                                    CentralSurfaces.getActivityOptions(displayId, animationAdapter)
                                        .apply { extraOptions?.let { putAll(it) } }
                                )
                            // TODO b/221255671: restrict this to only be set for notifications
                            options.isEligibleForLegacyPermissionPrompt = true
                            options.setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            )
                            return intent.sendAndReturnResult(
                                context,
                                0,
                                fillInIntent,
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
                // This activity could have started while the device is dreaming, in which case
                // the dream would occlude the activity. In order to show the newly started
                // activity, we wake from the dream.
                centralSurfaces?.awakenDreams()
            }
            intentSentUiThreadCallback?.let { mainExecutor.execute(it) }
        }

        if (!actuallyShowOverLockscreen) {
            mainExecutor.execute {
                executeRunnableDismissingKeyguard(
                    runnable = runnable,
                    afterKeyguardGone = willLaunchResolverActivity,
                    dismissShade = collapse,
                    willAnimateOnKeyguard = animate,
                    customMessage = customMessage,
                )
            }
        } else {
            mainExecutor.execute(runnable)
        }
    }

    override fun startActivityDismissingKeyguard(
        intent: Intent,
        dismissShade: Boolean,
        onlyProvisioned: Boolean,
        callback: ActivityStarter.Callback?,
        flags: Int,
        animationController: ActivityTransitionAnimator.Controller?,
        customMessage: String?,
        disallowEnterPictureInPictureWhileLaunching: Boolean,
        userHandle: UserHandle?
    ) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return
        val userHandle: UserHandle = userHandle ?: getActivityUserHandle(intent)

        if (onlyProvisioned && !deviceProvisioningInteractor.isDeviceProvisioned()) return

        val willLaunchResolverActivity: Boolean =
            activityIntentHelper.wouldLaunchResolverActivity(
                intent,
                selectedUserInteractor.getSelectedUserId(),
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
            intent.flags =
                if (intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0) {
                    Intent.FLAG_ACTIVITY_NEW_TASK
                } else {
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            intent.addFlags(flags)
            val result = intArrayOf(ActivityManager.START_CANCELED)
            activityTransitionAnimator.startIntentWithAnimation(
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
                if (CameraIntents.isInsecureCameraIntent(intent)) {
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
        executeRunnableDismissingKeyguard(
            runnable,
            cancelRunnable,
            dismissShadeDirectly,
            willLaunchResolverActivity,
            deferred = !isKeyguardOccluded(),
            animate,
            customMessage,
        )
    }

    override fun startActivity(
        intent: Intent,
        dismissShade: Boolean,
        animationController: ActivityTransitionAnimator.Controller?,
        showOverLockscreenWhenLocked: Boolean,
        userHandle: UserHandle?
    ) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return
        val userHandle = userHandle ?: getActivityUserHandle(intent)
        // Make sure that we dismiss the keyguard if it is directly dismissible or when we don't
        // want to show the activity above it.
        if (deviceEntryInteractor.isUnlocked.value || !showOverLockscreenWhenLocked) {
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
                    isActivityIntent = true,
                    showOverLockscreen = showOverLockscreenWhenLocked
                )

        var controller: ActivityTransitionAnimator.Controller? = null
        if (animate) {
            // Wrap the animation controller to dismiss the shade and set
            // mIsLaunchingActivityOverLockscreen during the animation.
            val delegate =
                wrapAnimationControllerForShadeOrStatusBar(
                    animationController = animationController,
                    dismissShade = dismissShade,
                    isLaunchForActivity = true,
                )
            controller = wrapAnimationControllerForLockscreen(dismissShade, delegate)
        } else if (dismissShade) {
            // The animation will take care of dismissing the shade at the end of the animation.
            // If we don't animate, collapse it directly.
            shadeControllerLazy.get().cancelExpansionAndCollapseShade()
        }

        // We should exit the dream to prevent the activity from starting below the
        // dream.
        if (keyguardInteractor.isDreaming.value) {
            centralSurfaces?.awakenDreams()
        }

        activityTransitionAnimator.startIntentWithAnimation(
            controller,
            animate,
            intent.getPackage(),
            showOverLockscreenWhenLocked
        ) { adapter: RemoteAnimationAdapter? ->
            TaskStackBuilder.create(context)
                .addNextIntent(intent)
                .startActivities(CentralSurfaces.getActivityOptions(displayId, adapter), userHandle)
        }
    }

    override fun dismissKeyguardThenExecute(
        action: ActivityStarter.OnDismissAction,
        cancel: Runnable?,
        afterKeyguardGone: Boolean,
        customMessage: String?
    ) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return
        Log.i(TAG, "Invoking dismissKeyguardThenExecute, afterKeyguardGone: $afterKeyguardGone")

        // TODO b/308819693: startWakeAndUnlock animation when pulsing

        if (isKeyguardShowing()) {
            statusBarKeyguardViewManagerLazy
                .get()
                .dismissWithAction(action, cancel, afterKeyguardGone, customMessage)
        } else {
            // If the keyguard isn't showing but the device is dreaming, we should exit the
            // dream.
            if (keyguardInteractor.isDreaming.value) {
                centralSurfaces?.awakenDreams()
            }
            action.onDismiss()
        }
    }

    override fun executeRunnableDismissingKeyguard(
        runnable: Runnable?,
        cancelAction: Runnable?,
        dismissShade: Boolean,
        afterKeyguardGone: Boolean,
        deferred: Boolean,
        willAnimateOnKeyguard: Boolean,
        customMessage: String?
    ) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return
        val onDismissAction: ActivityStarter.OnDismissAction =
            object : ActivityStarter.OnDismissAction {
                override fun onDismiss(): Boolean {
                    if (runnable != null) {
                        if (isKeyguardOccluded()) {
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
                    if (Flags.communalHub() && communalSceneInteractor.isIdleOnCommunal.value) {
                        // Override to false when launching activity over the hub that requires auth
                        return false
                    }
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

    override fun shouldAnimateLaunch(isActivityIntent: Boolean): Boolean {
        return shouldAnimateLaunch(isActivityIntent, false)
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
        // TODO(b/294418322): always support launch animations when occluded.
        val ignoreOcclusion = showOverLockscreen && Flags.mediaLockscreenLaunchAnimation()
        if (isKeyguardOccluded() && !ignoreOcclusion) {
            return false
        }

        // Always animate if we are not showing the keyguard or if we animate over the lockscreen
        // (without unlocking it).
        if (showOverLockscreen || !isKeyguardShowing()) {
            return true
        }

        // We don't animate non-activity launches as they can break the animation.
        // TODO(b/184121838): Support non activity launches on the lockscreen.
        return isActivityIntent
    }

    /** Retrieves the current user handle to start the Activity. */
    private fun getActivityUserHandle(intent: Intent): UserHandle {
        val packages: Array<String> = resources.getStringArray(R.array.system_ui_packages)
        for (pkg in packages) {
            val componentName = intent.component ?: break
            if (pkg == componentName.packageName) {
                return UserHandle(UserHandle.myUserId())
            }
        }
        return UserHandle(selectedUserInteractor.getSelectedUserId())
    }

    private fun isKeyguardShowing(): Boolean {
        return !deviceEntryInteractor.isDeviceEntered.value
    }

    private fun isKeyguardOccluded(): Boolean {
        return keyguardTransitionInteractor.getCurrentState() == KeyguardState.OCCLUDED
    }

    /**
     * Return a [ActivityTransitionAnimator.Controller] wrapping `animationController` so that:
     * - if it launches in the notification shade window and `dismissShade` is true, then the shade
     *   will be instantly dismissed at the end of the animation.
     * - if it launches in status bar window, it will make the status bar window match the device
     *   size during the animation (that way, the animation won't be clipped by the status bar
     *   size).
     *
     * @param animationController the controller that is wrapped and will drive the main animation.
     * @param dismissShade whether the notification shade will be dismissed at the end of the
     *   animation. This is ignored if `animationController` is not animating in the shade window.
     * @param isLaunchForActivity whether the launch is for an activity.
     */
    private fun wrapAnimationControllerForShadeOrStatusBar(
        animationController: ActivityTransitionAnimator.Controller?,
        dismissShade: Boolean,
        isLaunchForActivity: Boolean,
    ): ActivityTransitionAnimator.Controller? {
        if (animationController == null) {
            return null
        }
        val rootView = animationController.transitionContainer.rootView
        val controllerFromStatusBar: Optional<ActivityTransitionAnimator.Controller> =
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
                return StatusBarTransitionAnimatorController(
                    animationController,
                    shadeAnimationInteractor,
                    shadeControllerLazy.get(),
                    notifShadeWindowControllerLazy.get(),
                    commandQueue,
                    displayId,
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
        dismissShade: Boolean,
        animationController: ActivityTransitionAnimator.Controller?
    ): ActivityTransitionAnimator.Controller? {
        return animationController?.let {
            object : DelegateTransitionAnimatorController(it) {
                override fun onIntentStarted(willAnimate: Boolean) {
                    delegate.onIntentStarted(willAnimate)
                    if (willAnimate) {
                        centralSurfaces?.setIsLaunchingActivityOverLockscreen(true, dismissShade)
                    }
                }

                override fun onTransitionAnimationStart(isExpandingFullyAbove: Boolean) {
                    super.onTransitionAnimationStart(isExpandingFullyAbove)
                    if (Flags.communalHub()) {
                        communalSceneInteractor.snapToScene(
                            newScene = CommunalScenes.Blank,
                            loggingReason = "ActivityStarterInternalImpl",
                            delayMillis = ActivityTransitionAnimator.TIMINGS.totalDuration
                        )
                    }
                }

                override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
                    // Set mIsLaunchingActivityOverLockscreen to false before actually
                    // finishing the animation so that we can assume that
                    // mIsLaunchingActivityOverLockscreen being true means that we will
                    // collapse the shade (or at least run the post collapse runnables)
                    // later on.
                    centralSurfaces?.setIsLaunchingActivityOverLockscreen(false, false)
                    delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
                }

                override fun onTransitionAnimationCancelled(newKeyguardOccludedState: Boolean?) {
                    // Set mIsLaunchingActivityOverLockscreen to false before actually
                    // finishing the animation so that we can assume that
                    // mIsLaunchingActivityOverLockscreen being true means that we will
                    // collapse the shade (or at least run the // post collapse
                    // runnables) later on.
                    centralSurfaces?.setIsLaunchingActivityOverLockscreen(false, false)
                    delegate.onTransitionAnimationCancelled(newKeyguardOccludedState)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ActivityStarterInternalImpl"
    }
}
