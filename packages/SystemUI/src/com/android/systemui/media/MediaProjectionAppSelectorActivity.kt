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
import android.media.projection.IMediaProjection
import android.media.projection.MediaProjectionManager.EXTRA_MEDIA_PROJECTION
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.view.View
import com.android.internal.app.ChooserActivity
import com.android.internal.app.chooser.NotSelectableTargetInfo
import com.android.internal.app.chooser.TargetInfo
import com.android.systemui.util.AsyncActivityLauncher
import com.android.systemui.R;
import javax.inject.Inject

class MediaProjectionAppSelectorActivity @Inject constructor(
    private val activityLauncher: AsyncActivityLauncher
) : ChooserActivity() {

    public override fun onCreate(bundle: Bundle?) {
        val queryIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        intent.putExtra(Intent.EXTRA_INTENT, queryIntent)

        // TODO(b/235465652) Use resource lexeme
        intent.putExtra(Intent.EXTRA_TITLE, "Record or cast an app")

        super.onCreate(bundle)

        // TODO(b/235465652) we should update VisD of the title and add an icon
        findViewById<View>(R.id.title)?.visibility = View.VISIBLE
    }

    override fun appliedThemeResId(): Int =
        R.style.Theme_SystemUI_MediaProjectionAppSelector

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
        val activityStarted = activityLauncher
            .startActivityAsUser(intent, userHandle, activityOptions.toBundle()) {
                onTargetActivityLaunched(launchToken)
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
        super.onDestroy()
    }

    override fun onActivityStarted(cti: TargetInfo) {
        // do nothing
    }

    private fun onTargetActivityLaunched(launchToken: IBinder) {
        if (intent.hasExtra(EXTRA_CAPTURE_REGION_RESULT_RECEIVER)) {
            // The client requested to return the result in the result receiver instead of
            // activity result, let's send the media projection to the result receiver
            val resultReceiver = intent
                .getParcelableExtra(EXTRA_CAPTURE_REGION_RESULT_RECEIVER,
                    ResultReceiver::class.java) as ResultReceiver
            val captureRegion = MediaProjectionCaptureTarget(launchToken)
            val data = Bundle().apply {
                putParcelable(KEY_CAPTURE_TARGET, captureRegion)
            }
            resultReceiver.send(RESULT_OK, data)
        } else {
            // Return the media projection instance as activity result
            val mediaProjectionBinder = intent.getIBinderExtra(EXTRA_MEDIA_PROJECTION)
            val projection = IMediaProjection.Stub.asInterface(mediaProjectionBinder)

            projection.launchCookie = launchToken

            val intent = Intent()
            intent.putExtra(EXTRA_MEDIA_PROJECTION, projection.asBinder())
            setResult(RESULT_OK, intent)
            setForceSendResultForMediaProjection()
        }

        finish()
    }

    override fun shouldGetOnlyDefaultActivities() = false

    companion object {
        /**
         * When EXTRA_CAPTURE_REGION_RESULT_RECEIVER is passed as intent extra
         * the activity will send the [CaptureRegion] to the result receiver
         * instead of returning media projection instance through activity result.
         */
        const val EXTRA_CAPTURE_REGION_RESULT_RECEIVER = "capture_region_result_receiver"
        const val KEY_CAPTURE_TARGET = "capture_region"
    }
}
