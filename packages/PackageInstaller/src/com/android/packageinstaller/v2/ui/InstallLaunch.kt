/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.ui

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_FIRST_USER
import android.app.Activity.RESULT_OK
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.Window
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.InstallAborted
import com.android.packageinstaller.v2.model.InstallFailed
import com.android.packageinstaller.v2.model.InstallInstalling
import com.android.packageinstaller.v2.model.InstallRepository
import com.android.packageinstaller.v2.model.InstallStage
import com.android.packageinstaller.v2.model.InstallSuccess
import com.android.packageinstaller.v2.model.InstallUserActionRequired
import com.android.packageinstaller.v2.model.PackageUtil.localLogv
import com.android.packageinstaller.v2.ui.fragments.AnonymousSourceFragment
import com.android.packageinstaller.v2.ui.fragments.ExternalSourcesBlockedFragment
import com.android.packageinstaller.v2.ui.fragments.InstallConfirmationFragment
import com.android.packageinstaller.v2.ui.fragments.InstallFailedFragment
import com.android.packageinstaller.v2.ui.fragments.InstallInstallingFragment
import com.android.packageinstaller.v2.ui.fragments.InstallStagingFragment
import com.android.packageinstaller.v2.ui.fragments.InstallSuccessFragment
import com.android.packageinstaller.v2.ui.fragments.ParseErrorFragment
import com.android.packageinstaller.v2.ui.fragments.SimpleErrorFragment
import com.android.packageinstaller.v2.viewmodel.InstallViewModel
import com.android.packageinstaller.v2.viewmodel.InstallViewModelFactory

class InstallLaunch : FragmentActivity(), InstallActionListener {

    companion object {
        @JvmField val EXTRA_CALLING_PKG_UID =
            InstallLaunch::class.java.packageName + ".callingPkgUid"
        @JvmField val EXTRA_CALLING_PKG_NAME =
            InstallLaunch::class.java.packageName + ".callingPkgName"
        private val LOG_TAG = InstallLaunch::class.java.simpleName
        private const val TAG_DIALOG = "dialog"
    }

    /**
     * A collection of unknown sources listeners that are actively listening for app ops mode
     * changes
     */
    private val activeUnknownSourcesListeners: MutableList<UnknownSourcesListener> = ArrayList(1)
    private var installViewModel: InstallViewModel? = null
    private var installRepository: InstallRepository? = null
    private var fragmentManager: FragmentManager? = null
    private var appOpsManager: AppOpsManager? = null
    private lateinit var unknownAppsIntentLauncher: ActivityResultLauncher<Intent>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        fragmentManager = supportFragmentManager
        appOpsManager = getSystemService(AppOpsManager::class.java)
        installRepository = InstallRepository(applicationContext)
        installViewModel = ViewModelProvider(
            this, InstallViewModelFactory(this.application, installRepository!!)
        )[InstallViewModel::class.java]

        val intent = intent
        val info = InstallRepository.CallerInfo(
            intent.getStringExtra(EXTRA_CALLING_PKG_NAME),
            intent.getIntExtra(EXTRA_CALLING_PKG_UID, Process.INVALID_UID)
        )
        installViewModel!!.preprocessIntent(intent, info)
        installViewModel!!.currentInstallStage.observe(this) { installStage: InstallStage ->
            onInstallStageChange(installStage)
        }

        // Used to launch intent for Settings, to manage "install unknown apps" permission
        unknownAppsIntentLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // Reattempt installation on coming back from Settings, after toggling
                // "install unknown apps" permission
                installViewModel!!.reattemptInstall()
            }
    }

    /**
     * Main controller of the UI. This method shows relevant dialogs based on the install stage
     */
    private fun onInstallStageChange(installStage: InstallStage) {
        when (installStage.stageCode) {
            InstallStage.STAGE_STAGING -> {
                val stagingDialog = InstallStagingFragment()
                showDialogInner(stagingDialog)
                installViewModel!!.stagingProgress.observe(this) { progress: Int ->
                    stagingDialog.setProgress(progress)
                }
            }

            InstallStage.STAGE_ABORTED -> {
                val aborted = installStage as InstallAborted
                when (aborted.abortReason) {
                    InstallAborted.ABORT_REASON_DONE,
                    InstallAborted.ABORT_REASON_INTERNAL_ERROR -> {
                        if (aborted.errorDialogType == InstallAborted.DLG_PACKAGE_ERROR) {
                            val parseErrorDialog = ParseErrorFragment(aborted)
                            showDialogInner(parseErrorDialog)
                        } else {
                            setResult(aborted.activityResultCode, aborted.resultIntent, true)
                        }
                    }

                    InstallAborted.ABORT_REASON_POLICY -> showPolicyRestrictionDialog(aborted)
                    else -> setResult(RESULT_CANCELED, null, true)
                }
            }

            InstallStage.STAGE_USER_ACTION_REQUIRED -> {
                val uar = installStage as InstallUserActionRequired
                when (uar.actionReason) {
                    InstallUserActionRequired.USER_ACTION_REASON_INSTALL_CONFIRMATION -> {
                        val actionDialog = InstallConfirmationFragment(uar)
                        showDialogInner(actionDialog)
                    }

                    InstallUserActionRequired.USER_ACTION_REASON_UNKNOWN_SOURCE -> {
                        val externalSourceDialog = ExternalSourcesBlockedFragment(uar)
                        showDialogInner(externalSourceDialog)
                    }

                    InstallUserActionRequired.USER_ACTION_REASON_ANONYMOUS_SOURCE -> {
                        val anonymousSourceDialog = AnonymousSourceFragment()
                        showDialogInner(anonymousSourceDialog)
                    }
                }
            }

            InstallStage.STAGE_INSTALLING -> {
                val installing = installStage as InstallInstalling
                val installingDialog = InstallInstallingFragment(installing)
                showDialogInner(installingDialog)
            }

            InstallStage.STAGE_SUCCESS -> {
                val success = installStage as InstallSuccess
                if (success.shouldReturnResult) {
                    val successIntent = success.resultIntent
                    setResult(RESULT_OK, successIntent, true)
                } else {
                    val successDialog = InstallSuccessFragment(success)
                    showDialogInner(successDialog)
                }
            }

            InstallStage.STAGE_FAILED -> {
                val failed = installStage as InstallFailed
                if (failed.shouldReturnResult) {
                    val failureIntent = failed.resultIntent
                    setResult(RESULT_FIRST_USER, failureIntent, true)
                } else {
                    val failureDialog = InstallFailedFragment(failed)
                    showDialogInner(failureDialog)
                }
            }

            else -> {
                Log.d(LOG_TAG, "Unimplemented stage: " + installStage.stageCode)
                showDialogInner(null)
            }
        }
    }

    private fun showPolicyRestrictionDialog(aborted: InstallAborted) {
        val restriction = aborted.message
        val adminSupportIntent = aborted.resultIntent
        var shouldFinish: Boolean = false

        // If the given restriction is set by an admin, display information about the
        // admin enforcing the restriction for the affected user. If not enforced by the admin,
        // show the system dialog.
        if (adminSupportIntent != null) {
            if (localLogv) {
                Log.i(LOG_TAG, "Restriction set by admin, starting $adminSupportIntent")
            }
            startActivity(adminSupportIntent)
            // Finish the package installer app since the next dialog will not be shown by this app
            shouldFinish = true
        } else {
            if (localLogv) {
                Log.i(LOG_TAG, "Restriction set by system: $restriction")
            }
            val blockedByPolicyDialog = createDevicePolicyRestrictionDialog(restriction)
            // Don't finish the package installer app since the next dialog
            // will be shown by this app
            shouldFinish = blockedByPolicyDialog == null
            showDialogInner(blockedByPolicyDialog)
        }
        setResult(RESULT_CANCELED, null, shouldFinish)
    }

    /**
     * Create a new dialog based on the install restriction enforced.
     *
     * @param restriction The restriction to create the dialog for
     * @return The dialog
     */
    private fun createDevicePolicyRestrictionDialog(restriction: String?): DialogFragment? {
        if (localLogv) {
            Log.i(LOG_TAG, "createDialog($restriction)")
        }
        return when (restriction) {
            UserManager.DISALLOW_INSTALL_APPS ->
                SimpleErrorFragment(R.string.install_apps_user_restriction_dlg_text)

            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY ->
                SimpleErrorFragment(R.string.unknown_apps_user_restriction_dlg_text)

            else -> null
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
        if (resultCode != RESULT_OK) {
            // Let callers know that the install was cancelled
            installViewModel!!.cleanupInstall()
        }
        if (shouldFinish) {
            finish()
        }
    }

    override fun onPositiveResponse(reasonCode: Int) {
        if (localLogv) {
            Log.d(LOG_TAG, "Positive button clicked. ReasonCode: $reasonCode")
        }
        when (reasonCode) {
            InstallUserActionRequired.USER_ACTION_REASON_ANONYMOUS_SOURCE ->
                installViewModel!!.forcedSkipSourceCheck()

            InstallUserActionRequired.USER_ACTION_REASON_INSTALL_CONFIRMATION ->
                installViewModel!!.initiateInstall()
        }
    }

    override fun onNegativeResponse(stageCode: Int) {
        if (localLogv) {
            Log.d(LOG_TAG, "Negative button clicked. StageCode: $stageCode")
        }
        if (stageCode == InstallStage.STAGE_USER_ACTION_REQUIRED) {
            installViewModel!!.cleanupInstall()
        }
        setResult(RESULT_CANCELED, null, true)
    }

    override fun onNegativeResponse(resultCode: Int, data: Intent?) {
        if (localLogv) {
            Log.d(LOG_TAG, "Negative button clicked. resultCode: $resultCode; Intent: $data")
        }
        setResult(resultCode, data, true)
    }

    override fun sendUnknownAppsIntent(sourcePackageName: String) {
        if (localLogv) {
            Log.d(LOG_TAG, "Launching unknown-apps settings intent for $sourcePackageName")
        }
        val settingsIntent = Intent()
        settingsIntent.setAction(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        val packageUri = Uri.parse("package:$sourcePackageName")
        settingsIntent.setData(packageUri)
        settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        try {
            registerAppOpChangeListener(
                UnknownSourcesListener(sourcePackageName), sourcePackageName
            )
            unknownAppsIntentLauncher.launch(settingsIntent)
        } catch (exc: ActivityNotFoundException) {
            Log.e(
                LOG_TAG, "Settings activity not found for action: "
                    + Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            )
        }
    }

    override fun openInstalledApp(intent: Intent?) {
        if (localLogv) {
            Log.d(LOG_TAG, "Opening $intent")
        }
        setResult(RESULT_OK, intent, true)
        if (intent != null && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            startActivity(intent)
        }
    }

    private fun registerAppOpChangeListener(listener: UnknownSourcesListener, packageName: String) {
        appOpsManager!!.startWatchingMode(
            AppOpsManager.OPSTR_REQUEST_INSTALL_PACKAGES,
            packageName,
            listener
        )
        activeUnknownSourcesListeners.add(listener)
    }

    private fun unregisterAppOpChangeListener(listener: UnknownSourcesListener) {
        activeUnknownSourcesListeners.remove(listener)
        appOpsManager!!.stopWatchingMode(listener)
    }

    override fun onDestroy() {
        super.onDestroy()
        while (activeUnknownSourcesListeners.isNotEmpty()) {
            unregisterAppOpChangeListener(activeUnknownSourcesListeners[0])
        }
    }

    private inner class UnknownSourcesListener(private val mOriginatingPackage: String) :
        AppOpsManager.OnOpChangedListener {
        override fun onOpChanged(op: String, packageName: String) {
            if (mOriginatingPackage != packageName) {
                return
            }
            unregisterAppOpChangeListener(this)
            activeUnknownSourcesListeners.remove(this)
            if (isDestroyed) {
                return
            }
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed) {
                    // Relaunch Pia to continue installation.
                    startActivity(
                        intent.putExtra(
                            InstallRepository.EXTRA_STAGED_SESSION_ID,
                            installViewModel!!.stagedSessionId
                        )
                    )

                    // If the userId of the root of activity stack is different from current userId,
                    // starting Pia again lead to duplicate instances of the app in the stack.
                    // As such, finish the old instance. Old Pia is finished even if the userId of
                    // the root is the same, since there is no way to determine the difference in
                    // userIds.
                    finish()
                }
            }, 500)
        }
    }
}
