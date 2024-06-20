/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.ui

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.android.packageinstaller.v2.model.PackageUtil.localLogv
import com.android.packageinstaller.v2.model.UninstallAborted
import com.android.packageinstaller.v2.model.UninstallFailed
import com.android.packageinstaller.v2.model.UninstallRepository
import com.android.packageinstaller.v2.model.UninstallStage
import com.android.packageinstaller.v2.model.UninstallSuccess
import com.android.packageinstaller.v2.model.UninstallUninstalling
import com.android.packageinstaller.v2.model.UninstallUserActionRequired
import com.android.packageinstaller.v2.ui.fragments.UninstallConfirmationFragment
import com.android.packageinstaller.v2.ui.fragments.UninstallErrorFragment
import com.android.packageinstaller.v2.ui.fragments.UninstallUninstallingFragment
import com.android.packageinstaller.v2.viewmodel.UninstallViewModel
import com.android.packageinstaller.v2.viewmodel.UninstallViewModelFactory

class UninstallLaunch : FragmentActivity(), UninstallActionListener {

    companion object {
        @JvmField val EXTRA_CALLING_PKG_UID =
            UninstallLaunch::class.java.packageName + ".callingPkgUid"
        @JvmField val EXTRA_CALLING_ACTIVITY_NAME =
            UninstallLaunch::class.java.packageName + ".callingActivityName"
        val LOG_TAG = UninstallLaunch::class.java.simpleName
        private const val TAG_DIALOG = "dialog"
    }

    private var uninstallViewModel: UninstallViewModel? = null
    private var uninstallRepository: UninstallRepository? = null
    private var fragmentManager: FragmentManager? = null
    private var notificationManager: NotificationManager? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addSystemFlags(WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)

        // Never restore any state, esp. never create any fragments. The data in the fragment might
        // be stale, if e.g. the app was uninstalled while the activity was destroyed.
        super.onCreate(null)
        fragmentManager = supportFragmentManager
        notificationManager = getSystemService(NotificationManager::class.java)

        uninstallRepository = UninstallRepository(applicationContext)
        uninstallViewModel = ViewModelProvider(
            this, UninstallViewModelFactory(this.application, uninstallRepository!!)
        ).get(UninstallViewModel::class.java)

        val intent = intent
        val callerInfo = UninstallRepository.CallerInfo(
            intent.getStringExtra(EXTRA_CALLING_ACTIVITY_NAME),
            intent.getIntExtra(EXTRA_CALLING_PKG_UID, Process.INVALID_UID)
        )
        uninstallViewModel!!.preprocessIntent(intent, callerInfo)
        uninstallViewModel!!.currentUninstallStage.observe(this) { uninstallStage: UninstallStage ->
            onUninstallStageChange(uninstallStage)
        }
    }

    /**
     * Main controller of the UI. This method shows relevant dialogs / fragments based on the
     * uninstall stage
     */
    private fun onUninstallStageChange(uninstallStage: UninstallStage) {
        when (uninstallStage.stageCode) {
            UninstallStage.STAGE_ABORTED -> {
                val aborted = uninstallStage as UninstallAborted
                if (aborted.abortReason == UninstallAborted.ABORT_REASON_APP_UNAVAILABLE ||
                    aborted.abortReason == UninstallAborted.ABORT_REASON_USER_NOT_ALLOWED
                ) {
                    val errorDialog = UninstallErrorFragment(aborted)
                    showDialogInner(errorDialog)
                } else {
                    setResult(aborted.activityResultCode, null, true)
                }
            }

            UninstallStage.STAGE_USER_ACTION_REQUIRED -> {
                val uar = uninstallStage as UninstallUserActionRequired
                val confirmationDialog = UninstallConfirmationFragment(uar)
                showDialogInner(confirmationDialog)
            }

            UninstallStage.STAGE_UNINSTALLING -> {
                // TODO: This shows a fragment whether or not user requests a result or not.
                //  Originally, if the user does not request a result, we used to show a notification.
                //  And a fragment if the user requests a result back. Should we consolidate and
                //  show a fragment always?
                val uninstalling = uninstallStage as UninstallUninstalling
                val uninstallingDialog = UninstallUninstallingFragment(uninstalling)
                showDialogInner(uninstallingDialog)
            }

            UninstallStage.STAGE_FAILED -> {
                val failed = uninstallStage as UninstallFailed
                if (!failed.returnResult) {
                    notificationManager!!.notify(
                        failed.uninstallNotificationId!!, failed.uninstallNotification
                    )
                }
                setResult(failed.activityResultCode, failed.resultIntent, true)
            }

            UninstallStage.STAGE_SUCCESS -> {
                val success = uninstallStage as UninstallSuccess
                if (success.message != null) {
                    Toast.makeText(this, success.message, Toast.LENGTH_LONG).show()
                }
                setResult(success.activityResultCode, success.resultIntent, true)
            }

            else -> {
                Log.e(LOG_TAG, "Invalid stage: " + uninstallStage.stageCode)
                showDialogInner(null)
            }
        }
    }

    /**
     * Replace any visible dialog by the dialog returned by InstallRepository
     *
     * @param newDialog The new dialog to display
     */
    private fun showDialogInner(newDialog: DialogFragment?) {
        val currentDialog = fragmentManager!!.findFragmentByTag(TAG_DIALOG) as DialogFragment?
        currentDialog?.dismissAllowingStateLoss()
        newDialog?.show(fragmentManager!!, TAG_DIALOG)
    }

    fun setResult(resultCode: Int, data: Intent?, shouldFinish: Boolean) {
        super.setResult(resultCode, data)
        if (shouldFinish) {
            finish()
        }
    }

    override fun onPositiveResponse(keepData: Boolean) {
        if (localLogv) {
            Log.d(LOG_TAG, "Staring uninstall")
        }
        uninstallViewModel!!.initiateUninstall(keepData)
    }

    override fun onNegativeResponse() {
        if (localLogv) {
            Log.d(LOG_TAG, "Cancelling uninstall")
        }
        uninstallViewModel!!.cancelUninstall()
        setResult(Activity.RESULT_FIRST_USER, null, true)
    }
}
