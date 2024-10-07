/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.camera

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.IActivityTaskManager
import android.app.admin.DevicePolicyManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.RemoteException
import android.util.Log
import android.view.WindowManager
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shared.system.ActivityManagerKt.isInForeground
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Helps with handling camera-related gestures (for example, double-tap the power button to launch
 * the camera).
 */
@SysUISingleton
class CameraGestureHelper
@Inject
constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val packageManager: PackageManager,
    private val activityManager: ActivityManager,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val activityTaskManager: IActivityTaskManager,
    private val cameraIntents: CameraIntentsWrapper,
    private val contentResolver: ContentResolver,
    @Main private val uiExecutor: Executor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val devicePolicyManager: DevicePolicyManager,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
) {
    /** Whether the camera application can be launched for the camera launch gesture. */
    fun canCameraGestureBeLaunched(statusBarState: Int): Boolean {
        if (!isCameraAllowedByAdmin()) {
            return false
        }

        val resolveInfo: ResolveInfo? =
            packageManager.resolveActivityAsUser(
                getStartCameraIntent(selectedUserInteractor.getSelectedUserId()),
                PackageManager.MATCH_DEFAULT_ONLY,
                selectedUserInteractor.getSelectedUserId()
            )
        val resolvedPackage = resolveInfo?.activityInfo?.packageName
        return (resolvedPackage != null &&
            (statusBarState != StatusBarState.SHADE ||
                !activityManager.isInForeground(resolvedPackage)))
    }

    /**
     * Launches the camera.
     *
     * @param source The source of the camera launch, to be passed to the camera app via [Intent]
     */
    fun launchCamera(source: Int) {
        val intent: Intent = getStartCameraIntent(selectedUserInteractor.getSelectedUserId())
        intent.putExtra(CameraIntents.EXTRA_LAUNCH_SOURCE, source)
        val wouldLaunchResolverActivity =
            activityIntentHelper.wouldLaunchResolverActivity(
                intent,
                selectedUserInteractor.getSelectedUserId()
            )
        if (CameraIntents.isSecureCameraIntent(intent) && !wouldLaunchResolverActivity) {
            uiExecutor.execute {
                // Normally an activity will set its requested rotation animation on its window.
                // However when launching an activity causes the orientation to change this is too
                // late. In these cases, the default animation is used. This doesn't look good for
                // the camera (as it rotates the camera contents out of sync with physical reality).
                // Therefore, we ask the WindowManager to force the cross-fade animation if an
                // orientation change happens to occur during the launch.
                val activityOptions = ActivityOptions.makeBasic()
                activityOptions.setDisallowEnterPictureInPictureWhileLaunching(true)
                activityOptions.rotationAnimationHint =
                    WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
                try {
                    activityTaskManager.startActivityAsUser(
                        null,
                        context.basePackageName,
                        context.attributionTag,
                        intent,
                        intent.resolveTypeIfNeeded(contentResolver),
                        null,
                        null,
                        0,
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                        null,
                        activityOptions.toBundle(),
                        selectedUserInteractor.getSelectedUserId(),
                    )
                } catch (e: RemoteException) {
                    Log.w("CameraGestureHelper", "Unable to start camera activity", e)
                }
            }
        } else {
            // We need to delay starting the activity because ResolverActivity finishes itself if
            // launched from behind the lock-screen.
            activityStarter.startActivity(intent, false /* dismissShade */)
        }

        // Call this to make sure the keyguard is ready to be dismissed once the next intent is
        // handled by the OS (in our case it is the activity we started right above)
        statusBarKeyguardViewManager.readyForKeyguardDone()
    }

    /**
     * Returns an [Intent] that can be used to start the camera app such that it occludes the
     * lock-screen, if needed.
     */
    private fun getStartCameraIntent(userId: Int): Intent {
        val isLockScreenDismissible = keyguardStateController.canDismissLockScreen()
        val isSecure = keyguardStateController.isMethodSecure
        return if (isSecure && !isLockScreenDismissible) {
            cameraIntents.getSecureCameraIntent(userId)
        } else {
            cameraIntents.getInsecureCameraIntent(userId)
        }
    }

    private fun isCameraAllowedByAdmin(): Boolean {
        if (devicePolicyManager.getCameraDisabled(null, lockscreenUserManager.getCurrentUserId())) {
            return false
        } else if (keyguardStateController.isShowing() && statusBarKeyguardViewManager.isSecure()) {
            // Check if the admin has disabled the camera specifically for the keyguard
            return (devicePolicyManager.getKeyguardDisabledFeatures(
                null,
                lockscreenUserManager.getCurrentUserId()
            ) and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) == 0
        }
        return true
    }
}
