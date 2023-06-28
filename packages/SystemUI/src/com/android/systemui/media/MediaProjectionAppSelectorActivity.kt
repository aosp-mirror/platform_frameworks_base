/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.media

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.media.projection.IMediaProjection
import android.media.projection.MediaProjectionManager.EXTRA_MEDIA_PROJECTION
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.os.UserHandle
import android.view.ViewGroup
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.app.AbstractMultiProfilePagerAdapter.EmptyStateProvider
import com.android.internal.app.AbstractMultiProfilePagerAdapter.MyUserIdProvider
import com.android.internal.app.ChooserActivity
import com.android.internal.app.ResolverListController
import com.android.internal.app.chooser.NotSelectableTargetInfo
import com.android.internal.app.chooser.TargetInfo
import com.android.systemui.R
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorComponent
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorController
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorResultHandler
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorView
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.view.MediaProjectionRecentsViewController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.AsyncActivityLauncher
import javax.inject.Inject

class MediaProjectionAppSelectorActivity(
    private val componentFactory: MediaProjectionAppSelectorComponent.Factory,
    private val activityLauncher: AsyncActivityLauncher,
    private val featureFlags: FeatureFlags,
    /** This is used to override the dependency in a screenshot test */
    @VisibleForTesting
    private val listControllerFactory: ((userHandle: UserHandle) -> ResolverListController)?
) : ChooserActivity(), MediaProjectionAppSelectorView, MediaProjectionAppSelectorResultHandler {

    @Inject
    constructor(
        componentFactory: MediaProjectionAppSelectorComponent.Factory,
        activityLauncher: AsyncActivityLauncher,
        featureFlags: FeatureFlags
    ) : this(componentFactory, activityLauncher, featureFlags, listControllerFactory = null)

    private lateinit var configurationController: ConfigurationController
    private lateinit var controller: MediaProjectionAppSelectorController
    private lateinit var recentsViewController: MediaProjectionRecentsViewController
    private lateinit var component: MediaProjectionAppSelectorComponent

    override fun getLayoutResource() = R.layout.media_projection_app_selector

    public override fun onCreate(bundle: Bundle?) {
        component = componentFactory.create(activity = this, view = this, resultHandler = this)

        // Create a separate configuration controller for this activity as the configuration
        // might be different from the global one
        configurationController = component.configurationController
        controller = component.controller
        recentsViewController = component.recentsViewController

        intent.configureChooserIntent(
            resources,
            component.hostUserHandle,
            component.personalProfileUserHandle
        )

        super.onCreate(bundle)
        controller.init()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurationController.onConfigurationChanged(newConfig)
    }

    override fun appliedThemeResId(): Int = R.style.Theme_SystemUI_MediaProjectionAppSelector

    override fun createBlockerEmptyStateProvider(): EmptyStateProvider =
        if (featureFlags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES)) {
            component.emptyStateProvider
        } else {
            super.createBlockerEmptyStateProvider()
        }

    override fun createListController(userHandle: UserHandle): ResolverListController =
        listControllerFactory?.invoke(userHandle) ?: super.createListController(userHandle)

    override fun startSelected(which: Int, always: Boolean, filtered: Boolean) {
        val currentListAdapter = mChooserMultiProfilePagerAdapter.activeListAdapter
        val targetInfo = currentListAdapter.targetInfoForPosition(which, filtered) ?: return
        if (targetInfo is NotSelectableTargetInfo) return

        val intent = createIntent(targetInfo)

        val launchToken: IBinder = Binder("media_projection_launch_token")
        val activityOptions = ActivityOptions.makeBasic()
        activityOptions.launchCookie = launchToken

        val userHandle = mMultiProfilePagerAdapter.activeListAdapter.userHandle

        // Launch activity asynchronously and wait for the result, launching of an activity
        // is typically very fast, so we don't show any loaders.
        // We wait for the activity to be launched to make sure that the window of the activity
        // is created and ready to be captured.
        val activityStarted =
            activityLauncher.startActivityAsUser(intent, userHandle, activityOptions.toBundle()) {
                returnSelectedApp(launchToken)
            }

        // Rely on the ActivityManager to pop up a dialog regarding app suspension
        // and return false if suspended
        if (!targetInfo.isSuspended && activityStarted) {
            // TODO(b/222078415) track activity launch
        }
    }

    private fun createIntent(target: TargetInfo): Intent {
        val intent = Intent(target.resolvedIntent)

        // Launch the app in a new task, so it won't be in the host's app task
        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK

        // Remove activity forward result flag as this activity will
        // return the media projection session
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT.inv()

        return intent
    }

    override fun onDestroy() {
        activityLauncher.destroy()
        controller.destroy()
        super.onDestroy()
    }

    override fun onActivityStarted(cti: TargetInfo) {
        // do nothing
    }

    override fun bind(recentTasks: List<RecentTask>) {
        recentsViewController.bind(recentTasks)
    }

    override fun returnSelectedApp(launchCookie: IBinder) {
        if (intent.hasExtra(EXTRA_CAPTURE_REGION_RESULT_RECEIVER)) {
            // The client requested to return the result in the result receiver instead of
            // activity result, let's send the media projection to the result receiver
            val resultReceiver =
                intent.getParcelableExtra(
                    EXTRA_CAPTURE_REGION_RESULT_RECEIVER,
                    ResultReceiver::class.java
                ) as ResultReceiver
            val captureRegion = MediaProjectionCaptureTarget(launchCookie)
            val data = Bundle().apply { putParcelable(KEY_CAPTURE_TARGET, captureRegion) }
            resultReceiver.send(RESULT_OK, data)
        } else {
            // Return the media projection instance as activity result
            val mediaProjectionBinder = intent.getIBinderExtra(EXTRA_MEDIA_PROJECTION)
            val projection = IMediaProjection.Stub.asInterface(mediaProjectionBinder)

            projection.launchCookie = launchCookie

            val intent = Intent()
            intent.putExtra(EXTRA_MEDIA_PROJECTION, projection.asBinder())
            setResult(RESULT_OK, intent)
            setForceSendResultForMediaProjection()
        }

        finish()
    }

    override fun shouldGetOnlyDefaultActivities() = false

    override fun shouldShowContentPreview() = true

    override fun shouldShowContentPreviewWhenEmpty(): Boolean = true

    override fun createMyUserIdProvider(): MyUserIdProvider =
        object : MyUserIdProvider() {
            override fun getMyUserId(): Int = component.hostUserHandle.identifier
        }

    override fun createContentPreviewView(parent: ViewGroup): ViewGroup =
        recentsViewController.createView(parent)

    companion object {
        /**
         * When EXTRA_CAPTURE_REGION_RESULT_RECEIVER is passed as intent extra the activity will
         * send the [CaptureRegion] to the result receiver instead of returning media projection
         * instance through activity result.
         */
        const val EXTRA_CAPTURE_REGION_RESULT_RECEIVER = "capture_region_result_receiver"

        /** UID of the app that originally launched the media projection flow (host app user) */
        const val EXTRA_HOST_APP_USER_HANDLE = "launched_from_user_handle"
        const val KEY_CAPTURE_TARGET = "capture_region"

        /** Set up intent for the [ChooserActivity] */
        private fun Intent.configureChooserIntent(
            resources: Resources,
            hostUserHandle: UserHandle,
            personalProfileUserHandle: UserHandle
        ) {
            // Specify the query intent to show icons for all apps on the chooser screen
            val queryIntent =
                Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            putExtra(Intent.EXTRA_INTENT, queryIntent)

            // Update the title of the chooser
            val title = resources.getString(R.string.media_projection_permission_app_selector_title)
            putExtra(Intent.EXTRA_TITLE, title)

            // Select host app's profile tab by default
            val selectedProfile =
                if (hostUserHandle == personalProfileUserHandle) {
                    PROFILE_PERSONAL
                } else {
                    PROFILE_WORK
                }
            putExtra(EXTRA_SELECTED_PROFILE, selectedProfile)
        }
    }
}
