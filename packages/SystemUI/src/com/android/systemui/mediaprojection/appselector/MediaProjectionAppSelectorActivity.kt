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
package com.android.systemui.mediaprojection.appselector

import android.app.ActivityOptions
import android.app.ActivityOptions.LaunchCookie
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.media.projection.IMediaProjection
import android.media.projection.IMediaProjectionManager.EXTRA_USER_REVIEW_GRANTED_CONSENT
import android.media.projection.MediaProjectionManager.EXTRA_MEDIA_PROJECTION
import android.media.projection.ReviewGrantedConsentResult.RECORD_CANCEL
import android.media.projection.ReviewGrantedConsentResult.RECORD_CONTENT_TASK
import android.os.Bundle
import android.os.ResultReceiver
import android.os.UserHandle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.app.AbstractMultiProfilePagerAdapter.EmptyStateProvider
import com.android.internal.app.AbstractMultiProfilePagerAdapter.MyUserIdProvider
import com.android.internal.app.ChooserActivity
import com.android.internal.app.ResolverListController
import com.android.internal.app.chooser.NotSelectableTargetInfo
import com.android.internal.app.chooser.TargetInfo
import com.android.internal.widget.RecyclerView
import com.android.internal.widget.RecyclerViewAccessibilityDelegate
import com.android.internal.widget.ResolverDrawerLayout
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.mediaprojection.MediaProjectionServiceHelper
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.view.MediaProjectionRecentsViewController
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.AsyncActivityLauncher
import java.lang.IllegalArgumentException
import javax.inject.Inject

class MediaProjectionAppSelectorActivity(
    private val componentFactory: MediaProjectionAppSelectorComponent.Factory,
    private val activityLauncher: AsyncActivityLauncher,
    /** This is used to override the dependency in a screenshot test */
    @VisibleForTesting
    private val listControllerFactory: ((userHandle: UserHandle) -> ResolverListController)?
) :
    ChooserActivity(),
    MediaProjectionAppSelectorView,
    MediaProjectionAppSelectorResultHandler,
    LifecycleOwner {

    @Inject
    constructor(
        componentFactory: MediaProjectionAppSelectorComponent.Factory,
        activityLauncher: AsyncActivityLauncher
    ) : this(componentFactory, activityLauncher, listControllerFactory = null)

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle = lifecycleRegistry
    private lateinit var configurationController: ConfigurationController
    private lateinit var controller: MediaProjectionAppSelectorController
    private lateinit var recentsViewController: MediaProjectionRecentsViewController
    private lateinit var component: MediaProjectionAppSelectorComponent
    // Indicate if we are under the media projection security flow
    // i.e. when a host app reuses consent token, review the permission and update it to the service
    private var reviewGrantedConsentRequired = false
    // If an app is selected, set to true so that we don't send RECORD_CANCEL in onDestroy
    private var taskSelected = false

    override fun getLayoutResource() = R.layout.media_projection_app_selector

    public override fun onCreate(savedInstanceState: Bundle?) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        component =
            componentFactory.create(
                hostUserHandle = hostUserHandle,
                hostUid = hostUid,
                callingPackage = callingPackage,
                view = this,
                resultHandler = this,
                isFirstStart = savedInstanceState == null
            )
        component.lifecycleObservers.forEach { lifecycle.addObserver(it) }

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

        reviewGrantedConsentRequired =
            intent.getBooleanExtra(EXTRA_USER_REVIEW_GRANTED_CONSENT, false)

        super.onCreate(savedInstanceState)
        controller.init()
        setIcon()
        // we override AppList's AccessibilityDelegate set in ResolverActivity.onCreate because in
        // our case this delegate must extend RecyclerViewAccessibilityDelegate, otherwise
        // RecyclerView scrolling is broken
        setAppListAccessibilityDelegate()
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurationController.onConfigurationChanged(newConfig)
    }

    override fun appliedThemeResId(): Int = R.style.Theme_SystemUI_MediaProjectionAppSelector

    override fun createBlockerEmptyStateProvider(): EmptyStateProvider =
        component.emptyStateProvider

    override fun createListController(userHandle: UserHandle): ResolverListController =
        listControllerFactory?.invoke(userHandle) ?: super.createListController(userHandle)

    override fun startSelected(which: Int, always: Boolean, filtered: Boolean) {
        val currentListAdapter = mChooserMultiProfilePagerAdapter.activeListAdapter
        val targetInfo = currentListAdapter.targetInfoForPosition(which, filtered) ?: return
        if (targetInfo is NotSelectableTargetInfo) return

        val intent = createIntent(targetInfo)

        val launchCookie = LaunchCookie("media_projection_launch_token")
        val activityOptions = ActivityOptions.makeBasic()
        activityOptions.setLaunchCookie(launchCookie)

        val userHandle = mMultiProfilePagerAdapter.activeListAdapter.userHandle

        // Launch activity asynchronously and wait for the result, launching of an activity
        // is typically very fast, so we don't show any loaders.
        // We wait for the activity to be launched to make sure that the window of the activity
        // is created and ready to be captured.
        val activityStarted =
            activityLauncher.startActivityAsUser(intent, userHandle, activityOptions.toBundle()) {
                returnSelectedApp(launchCookie, taskId = -1)
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
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        component.lifecycleObservers.forEach { lifecycle.removeObserver(it) }
        // onDestroy is also called when an app is selected, in that case we only want to send
        // RECORD_CONTENT_TASK but not RECORD_CANCEL
        if (!taskSelected) {
            // TODO(b/272010156): Return result to PermissionActivity and update service there
            MediaProjectionServiceHelper.setReviewedConsentIfNeeded(
                RECORD_CANCEL,
                reviewGrantedConsentRequired,
                /* projection= */ null
            )
            if (isFinishing) {
                // Only log dismissed when actually finishing, and not when changing configuration.
                controller.onSelectorDismissed()
            }
        }
        activityLauncher.destroy()
        controller.destroy()
        super.onDestroy()
    }

    override fun onActivityStarted(cti: TargetInfo) {
        // do nothing
    }

    override fun bind(recentTasks: List<RecentTask>) {
        recentsViewController.bind(recentTasks)
        if (!hasWorkProfile()) {
            // Make sure to refresh the adapter, to show/hide the recents view depending on whether
            // there are recents or not.
            mMultiProfilePagerAdapter.personalListAdapter.notifyDataSetChanged()
        }
    }

    override fun returnSelectedApp(launchCookie: LaunchCookie, taskId: Int) {
        taskSelected = true
        if (intent.hasExtra(EXTRA_CAPTURE_REGION_RESULT_RECEIVER)) {
            // The client requested to return the result in the result receiver instead of
            // activity result, let's send the media projection to the result receiver
            val resultReceiver =
                intent.getParcelableExtra(
                    EXTRA_CAPTURE_REGION_RESULT_RECEIVER,
                    ResultReceiver::class.java
                ) as ResultReceiver
            val captureRegion = MediaProjectionCaptureTarget(launchCookie, taskId)
            val data = Bundle().apply { putParcelable(KEY_CAPTURE_TARGET, captureRegion) }
            resultReceiver.send(RESULT_OK, data)
            // TODO(b/279175710): Ensure consent result is always set here. Skipping this for now
            //  in ScreenMediaRecorder, since we know the permission grant (projection) is never
            //  reused in that scenario.
        } else {
            // TODO(b/272010156): Return result to PermissionActivity and update service there
            // Return the media projection instance as activity result
            val mediaProjectionBinder = intent.getIBinderExtra(EXTRA_MEDIA_PROJECTION)
            val projection = IMediaProjection.Stub.asInterface(mediaProjectionBinder)

            projection.setLaunchCookie(launchCookie)
            projection.setTaskId(taskId)

            val intent = Intent()
            intent.putExtra(EXTRA_MEDIA_PROJECTION, projection.asBinder())
            setResult(RESULT_OK, intent)
            setForceSendResultForMediaProjection()
            MediaProjectionServiceHelper.setReviewedConsentIfNeeded(
                RECORD_CONTENT_TASK,
                reviewGrantedConsentRequired,
                projection
            )
        }

        finish()
    }

    override fun shouldGetOnlyDefaultActivities() = false

    override fun shouldShowContentPreview() =
        if (hasWorkProfile()) {
            // When the user has a work profile, we can always set this to true, and the layout is
            // adjusted automatically, and hide the recents view.
            true
        } else {
            // When there is no work profile, we should only show the content preview if there are
            // recents, otherwise the collapsed app selector will look empty.
            recentsViewController.hasRecentTasks
        }

    override fun shouldShowStickyContentPreviewWhenEmpty() = shouldShowContentPreview()

    override fun shouldShowServiceTargets() = false

    private fun hasWorkProfile() = mMultiProfilePagerAdapter.count > 1

    override fun createMyUserIdProvider(): MyUserIdProvider =
        object : MyUserIdProvider() {
            override fun getMyUserId(): Int = component.hostUserHandle.identifier
        }

    override fun createContentPreviewView(parent: ViewGroup): ViewGroup =
        recentsViewController.createView(parent)

    /** Set up intent for the [ChooserActivity] */
    private fun Intent.configureChooserIntent(
        resources: Resources,
        hostUserHandle: UserHandle,
        personalProfileUserHandle: UserHandle,
    ) {
        // Specify the query intent to show icons for all apps on the chooser screen
        val queryIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        putExtra(Intent.EXTRA_INTENT, queryIntent)

        // Update the title of the chooser
        putExtra(Intent.EXTRA_TITLE, resources.getString(titleResId))

        // Select host app's profile tab by default
        val selectedProfile =
            if (hostUserHandle == personalProfileUserHandle) {
                PROFILE_PERSONAL
            } else {
                PROFILE_WORK
            }
        putExtra(EXTRA_SELECTED_PROFILE, selectedProfile)
    }

    private val hostUserHandle: UserHandle
        get() {
            val extras =
                intent.extras
                    ?: error("MediaProjectionAppSelectorActivity should be launched with extras")
            return extras.getParcelable(EXTRA_HOST_APP_USER_HANDLE)
                ?: error(
                    "MediaProjectionAppSelectorActivity should be provided with " +
                        "$EXTRA_HOST_APP_USER_HANDLE extra"
                )
        }

    private val hostUid: Int
        get() {
            if (!intent.hasExtra(EXTRA_HOST_APP_UID)) {
                error(
                    "MediaProjectionAppSelectorActivity should be provided with " +
                        "$EXTRA_HOST_APP_UID extra"
                )
            }
            return intent.getIntExtra(EXTRA_HOST_APP_UID, /* defaultValue= */ -1)
        }

    /**
     * The type of screen sharing being performed. Used to show the right text and icon in the
     * activity.
     */
    private val screenShareType: ScreenShareType?
        get() {
            if (!intent.hasExtra(EXTRA_SCREEN_SHARE_TYPE)) {
                return null
            } else {
                val type = intent.getStringExtra(EXTRA_SCREEN_SHARE_TYPE) ?: return null
                return try {
                    enumValueOf<ScreenShareType>(type)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
        }

    @get:StringRes
    private val titleResId: Int
        get() =
            when (screenShareType) {
                ScreenShareType.ShareToApp ->
                    R.string.media_projection_entry_share_app_selector_title
                ScreenShareType.SystemCast ->
                    R.string.media_projection_entry_cast_app_selector_title
                ScreenShareType.ScreenRecord -> R.string.screenrecord_app_selector_title
                null -> R.string.screen_share_generic_app_selector_title
            }

    @get:DrawableRes
    private val iconResId: Int
        get() =
            when (screenShareType) {
                ScreenShareType.ShareToApp -> R.drawable.ic_present_to_all
                ScreenShareType.SystemCast -> R.drawable.ic_cast_connected
                ScreenShareType.ScreenRecord -> R.drawable.ic_screenrecord
                null -> R.drawable.ic_present_to_all
            }

    @get:ColorRes
    private val iconTintResId: Int?
        get() =
            when (screenShareType) {
                ScreenShareType.ScreenRecord -> R.color.screenrecord_icon_color
                else -> null
            }

    companion object {
        const val TAG = "MediaProjectionAppSelectorActivity"

        /**
         * When EXTRA_CAPTURE_REGION_RESULT_RECEIVER is passed as intent extra the activity will
         * send the [CaptureRegion] to the result receiver instead of returning media projection
         * instance through activity result.
         */
        const val EXTRA_CAPTURE_REGION_RESULT_RECEIVER = "capture_region_result_receiver"

        /**
         * User on the device that launched the media projection flow. (Primary, Secondary, Guest,
         * Work, etc)
         */
        const val EXTRA_HOST_APP_USER_HANDLE = "launched_from_user_handle"
        /**
         * The kernel user-ID that has been assigned to the app that originally launched the media
         * projection flow.
         */
        const val EXTRA_HOST_APP_UID = "launched_from_host_uid"
        const val KEY_CAPTURE_TARGET = "capture_region"

        /**
         * The type of screen sharing being performed.
         *
         * The value set for this extra should match the name of a [ScreenShareType].
         */
        const val EXTRA_SCREEN_SHARE_TYPE = "screen_share_type"
    }

    private fun setIcon() {
        val iconView = findViewById<ImageView>(R.id.media_projection_app_selector_icon) ?: return
        iconView.setImageResource(iconResId)
        iconTintResId?.let { iconView.setColorFilter(this.resources.getColor(it, this.theme)) }
    }

    private fun setAppListAccessibilityDelegate() {
        val rdl = requireViewById<ResolverDrawerLayout>(com.android.internal.R.id.contentPanel)
        for (i in 0 until mMultiProfilePagerAdapter.count) {
            val list =
                mMultiProfilePagerAdapter
                    .getItem(i)
                    .rootView
                    .findViewById<View>(com.android.internal.R.id.resolver_list)
            if (list == null || list !is RecyclerView) {
                Log.wtf(TAG, "MediaProjection only supports RecyclerView")
            } else {
                list.accessibilityDelegate = RecyclerViewExpandingAccessibilityDelegate(rdl, list)
            }
        }
    }

    /**
     * An a11y delegate propagating all a11y events to [AppListAccessibilityDelegate] so that it can
     * expand drawer when needed. It needs to extend [RecyclerViewAccessibilityDelegate] because
     * that superclass handles RecyclerView scrolling while using a11y services.
     */
    private class RecyclerViewExpandingAccessibilityDelegate(
        rdl: ResolverDrawerLayout,
        view: RecyclerView
    ) : RecyclerViewAccessibilityDelegate(view) {

        private val delegate = AppListAccessibilityDelegate(rdl)

        override fun onRequestSendAccessibilityEvent(
            host: ViewGroup,
            child: View,
            event: AccessibilityEvent
        ): Boolean {
            super.onRequestSendAccessibilityEvent(host, child, event)
            return delegate.onRequestSendAccessibilityEvent(host, child, event)
        }
    }

    /** Enum describing what type of app screen sharing is being performed. */
    enum class ScreenShareType {
        /** The selected app will be cast to another device. */
        SystemCast,
        /** The selected app will be shared to another app on the device. */
        ShareToApp,
        /** The selected app will be recorded. */
        ScreenRecord,
    }
}
