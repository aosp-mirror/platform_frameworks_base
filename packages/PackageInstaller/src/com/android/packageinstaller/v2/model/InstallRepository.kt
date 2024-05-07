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

package com.android.packageinstaller.v2.model

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionInfo
import android.content.pm.PackageInstaller.SessionParams
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserManager
import android.text.TextUtils
import android.util.EventLog
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.packageinstaller.R
import com.android.packageinstaller.common.EventResultPersister
import com.android.packageinstaller.common.EventResultPersister.OutOfIdsException
import com.android.packageinstaller.common.InstallEventReceiver
import com.android.packageinstaller.v2.model.InstallAborted.Companion.ABORT_REASON_DONE
import com.android.packageinstaller.v2.model.InstallAborted.Companion.ABORT_REASON_INTERNAL_ERROR
import com.android.packageinstaller.v2.model.InstallAborted.Companion.ABORT_REASON_POLICY
import com.android.packageinstaller.v2.model.InstallAborted.Companion.DLG_NONE
import com.android.packageinstaller.v2.model.InstallAborted.Companion.DLG_PACKAGE_ERROR
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_ANONYMOUS_SOURCE
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_INSTALL_CONFIRMATION
import com.android.packageinstaller.v2.model.InstallUserActionRequired.Companion.USER_ACTION_REASON_UNKNOWN_SOURCE
import com.android.packageinstaller.v2.model.PackageUtil.canPackageQuery
import com.android.packageinstaller.v2.model.PackageUtil.generateStubPackageInfo
import com.android.packageinstaller.v2.model.PackageUtil.getAppSnippet
import com.android.packageinstaller.v2.model.PackageUtil.getPackageInfo
import com.android.packageinstaller.v2.model.PackageUtil.getPackageNameForUid
import com.android.packageinstaller.v2.model.PackageUtil.isCallerSessionOwner
import com.android.packageinstaller.v2.model.PackageUtil.isInstallPermissionGrantedOrRequested
import com.android.packageinstaller.v2.model.PackageUtil.isPermissionGranted
import com.android.packageinstaller.v2.model.PackageUtil.localLogv
import java.io.File
import java.io.IOException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class InstallRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val packageInstaller: PackageInstaller = packageManager.packageInstaller
    private val userManager: UserManager? = context.getSystemService(UserManager::class.java)
    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)
    private val appOpsManager: AppOpsManager? = context.getSystemService(AppOpsManager::class.java)
    private var isSessionInstall = false
    private var isTrustedSource = false
    private val _stagingResult = MutableLiveData<InstallStage>()
    val stagingResult: LiveData<InstallStage>
        get() = _stagingResult
    private val _installResult = MutableLiveData<InstallStage>()
    val installResult: LiveData<InstallStage>
        get() = _installResult

    /**
     * Session ID for a session created when caller uses PackageInstaller APIs
     */
    private var sessionId = SessionInfo.INVALID_ID

    /**
     * Session ID for a session created by this app
     */
    var stagedSessionId = SessionInfo.INVALID_ID
        private set
    private var callingUid = Process.INVALID_UID
    private var originatingUid = Process.INVALID_UID
    private var callingPackage: String? = null
    private var sessionStager: SessionStager? = null
    private lateinit var intent: Intent
    private lateinit var appOpRequestInfo: AppOpRequestInfo
    private lateinit var appSnippet: PackageUtil.AppSnippet

    /**
     * PackageInfo of the app being installed on device.
     */
    private var newPackageInfo: PackageInfo? = null

    /**
     * Extracts information from the incoming install intent, checks caller's permission to install
     * packages, verifies that the caller is the install session owner (in case of a session based
     * install) and checks if the current user has restrictions set that prevent app installation,
     *
     * @param intent the incoming [Intent] object for installing a package
     * @param callerInfo [CallerInfo] that holds the callingUid and callingPackageName
     * @return
     *  * [InstallAborted] if there are errors while performing the checks
     *  * [InstallStaging] after successfully performing the checks
     */
    fun performPreInstallChecks(intent: Intent, callerInfo: CallerInfo): InstallStage {
        this.intent = intent

        var callingAttributionTag: String? = null

        isSessionInstall =
            PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL == intent.action
                || PackageInstaller.ACTION_CONFIRM_INSTALL == intent.action

        sessionId = if (isSessionInstall)
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, SessionInfo.INVALID_ID)
        else SessionInfo.INVALID_ID

        stagedSessionId = intent.getIntExtra(EXTRA_STAGED_SESSION_ID, SessionInfo.INVALID_ID)

        callingPackage = callerInfo.packageName

        if (sessionId != SessionInfo.INVALID_ID) {
            val sessionInfo: SessionInfo? = packageInstaller.getSessionInfo(sessionId)
            callingPackage = sessionInfo?.getInstallerPackageName()
            callingAttributionTag = sessionInfo?.getInstallerAttributionTag()
        }

        // Uid of the source package, coming from ActivityManager
        callingUid = callerInfo.uid
        if (callingUid == Process.INVALID_UID) {
            Log.e(LOG_TAG, "Could not determine the launching uid.")
        }
        val sourceInfo: ApplicationInfo? = getSourceInfo(callingPackage)
        // Uid of the source package, with a preference to uid from ApplicationInfo
        originatingUid = sourceInfo?.uid ?: callingUid
        appOpRequestInfo = AppOpRequestInfo(
            getPackageNameForUid(context, originatingUid, callingPackage),
            originatingUid, callingAttributionTag
        )

        if(localLogv) {
            Log.i(LOG_TAG, "Intent: $intent\n" +
                "sessionId: $sessionId\n" +
                "staged sessionId: $stagedSessionId\n" +
                "calling package: $callingPackage\n" +
                "callingUid: $callingUid\n" +
                "originatingUid: $originatingUid")
        }

        if (callingUid == Process.INVALID_UID && sourceInfo == null) {
            // Caller's identity could not be determined. Abort the install
            Log.e(LOG_TAG, "Cannot determine caller since UID is invalid and sourceInfo is null")
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }

        if ((sessionId != SessionInfo.INVALID_ID
                && !isCallerSessionOwner(packageInstaller, originatingUid, sessionId))
            || (stagedSessionId != SessionInfo.INVALID_ID
                && !isCallerSessionOwner(packageInstaller, Process.myUid(), stagedSessionId))
        ) {
            Log.e(LOG_TAG, "UID is not the owner of the session:\n" +
                "CallingUid: $originatingUid | SessionId: $sessionId\n" +
                "My UID: ${Process.myUid()} | StagedSessionId: $stagedSessionId")
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }

        isTrustedSource = isInstallRequestFromTrustedSource(sourceInfo, this.intent, originatingUid)
        if (!isInstallPermissionGrantedOrRequested(
                context, callingUid, originatingUid, isTrustedSource
            )
        ) {
            Log.e(LOG_TAG, "UID $originatingUid needs to declare " +
                Manifest.permission.REQUEST_INSTALL_PACKAGES
            )
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }

        val restriction = getDevicePolicyRestrictions()
        if (restriction != null) {
            val adminSupportDetailsIntent =
                devicePolicyManager!!.createAdminSupportIntent(restriction)
            Log.e(LOG_TAG, "$restriction set in place. Cannot install." )
            return InstallAborted(
                ABORT_REASON_POLICY, message = restriction, resultIntent = adminSupportDetailsIntent
            )
        }

        maybeRemoveInvalidInstallerPackageName(callerInfo)

        return InstallStaging()
    }

    /**
     * @return the ApplicationInfo for the installation source (the calling package), if available
     */
    private fun getSourceInfo(callingPackage: String?): ApplicationInfo? {
        return try {
            callingPackage?.let { packageManager.getApplicationInfo(it, 0) }
        } catch (ignored: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun isInstallRequestFromTrustedSource(
        sourceInfo: ApplicationInfo?,
        intent: Intent,
        originatingUid: Int,
    ): Boolean {
        val isNotUnknownSource = intent.getBooleanExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)
        return (sourceInfo != null && sourceInfo.isPrivilegedApp
            && (isNotUnknownSource
            || isPermissionGranted(context, Manifest.permission.INSTALL_PACKAGES, originatingUid)))
    }

    private fun getDevicePolicyRestrictions(): String? {
        val restrictions = arrayOf(
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
        )
        for (restriction in restrictions) {
            if (!userManager!!.hasUserRestrictionForUser(restriction, Process.myUserHandle())) {
                continue
            }
            return restriction
        }
        return null
    }

    private fun maybeRemoveInvalidInstallerPackageName(callerInfo: CallerInfo) {
        val installerPackageNameFromIntent =
            intent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME) ?: return

        if (!TextUtils.equals(installerPackageNameFromIntent, callerInfo.packageName)
            && callerInfo.packageName != null
            && isPermissionGranted(
                packageManager, Manifest.permission.INSTALL_PACKAGES, callerInfo.packageName
            )
        ) {
            Log.e(
                LOG_TAG, "The given installer package name $installerPackageNameFromIntent"
                    + " is invalid. Remove it."
            )
            EventLog.writeEvent(
                0x534e4554, "236687884", callerInfo.uid,
                "Invalid EXTRA_INSTALLER_PACKAGE_NAME"
            )
            intent.removeExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun stageForInstall() {
        val uri = intent.data
        if (stagedSessionId != SessionInfo.INVALID_ID
            || isSessionInstall
            || (uri != null && SCHEME_PACKAGE == uri.scheme)
        ) {
            // For a session based install or installing with a package:// URI, there is no file
            // for us to stage.
            _stagingResult.value = InstallReady()
            return
        }
        if (uri != null
            && ContentResolver.SCHEME_CONTENT == uri.scheme
            && canPackageQuery(context, callingUid, uri)
        ) {
            if (stagedSessionId > 0) {
                val info: SessionInfo? = packageInstaller.getSessionInfo(stagedSessionId)
                if (info == null || !info.isActive || info.resolvedBaseApkPath == null) {
                    Log.w(LOG_TAG, "Session $stagedSessionId in funky state; ignoring")
                    if (info != null) {
                        cleanupStagingSession()
                    }
                    stagedSessionId = 0
                }
            }

            // Session does not exist, or became invalid.
            if (stagedSessionId <= 0) {
                // Create session here to be able to show error.
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r").use { afd ->
                        val pfd: ParcelFileDescriptor? = afd?.parcelFileDescriptor
                        val params: SessionParams =
                            createSessionParams(originatingUid, intent, pfd, uri.toString())
                        stagedSessionId = packageInstaller.createSession(params)
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Failed to create a staging session", e)
                    _stagingResult.value = InstallAborted(
                        ABORT_REASON_INTERNAL_ERROR,
                        resultIntent = Intent().putExtra(
                            Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_FAILED_INVALID_APK
                        ),
                        activityResultCode = Activity.RESULT_FIRST_USER,
                        errorDialogType =  if (e is IOException) DLG_PACKAGE_ERROR else DLG_NONE
                    )
                    return
                }
            }

            sessionStager = SessionStager(context, uri, stagedSessionId)
            GlobalScope.launch(Dispatchers.Main) {
                val wasFileStaged = sessionStager!!.execute()

                if (wasFileStaged) {
                    _stagingResult.value = InstallReady()
                } else {
                    cleanupStagingSession()
                    Log.e(LOG_TAG, "Could not stage APK.")
                    _stagingResult.value = InstallAborted(
                        ABORT_REASON_INTERNAL_ERROR,
                        resultIntent = Intent().putExtra(
                            Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_FAILED_INVALID_APK
                        ),
                        activityResultCode = Activity.RESULT_FIRST_USER
                    )
                }
            }
        } else {
            Log.e(LOG_TAG, "Invalid URI: ${if (uri == null) "null" else uri.scheme}")
            _stagingResult.value = InstallAborted(
                ABORT_REASON_INTERNAL_ERROR,
                resultIntent = Intent().putExtra(
                    Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_FAILED_INVALID_URI
                ),
                activityResultCode = Activity.RESULT_FIRST_USER
            )
        }
    }

    private fun cleanupStagingSession() {
        if (stagedSessionId > 0) {
            try {
                packageInstaller.abandonSession(stagedSessionId)
            } catch (ignored: SecurityException) {
            }
            stagedSessionId = 0
        }
    }

    private fun createSessionParams(
        originatingUid: Int,
        intent: Intent,
        pfd: ParcelFileDescriptor?,
        debugPathName: String,
    ): SessionParams {
        val params = SessionParams(SessionParams.MODE_FULL_INSTALL)
        val referrerUri = intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java)
        params.setPackageSource(
            if (referrerUri != null)
                PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
            else PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
        )
        params.setInstallAsInstantApp(false)
        params.setReferrerUri(referrerUri)
        params.setOriginatingUri(
            intent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI, Uri::class.java)
        )
        params.setOriginatingUid(originatingUid)
        params.setInstallerPackageName(intent.getStringExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME))
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        // Disable full screen intent usage by for sideloads.
        params.setPermissionState(
            Manifest.permission.USE_FULL_SCREEN_INTENT, SessionParams.PERMISSION_STATE_DENIED
        )
        if (pfd != null) {
            try {
                val installInfo = packageInstaller.readInstallInfo(pfd, debugPathName, 0)
                params.setAppPackageName(installInfo.packageName)
                params.setInstallLocation(installInfo.installLocation)
                params.setSize(installInfo.calculateInstalledSize(params, pfd))
            } catch (e: PackageInstaller.PackageParsingException) {
                Log.e(LOG_TAG, "Cannot parse package $debugPathName. Assuming defaults.", e)
                params.setSize(pfd.statSize)
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Cannot calculate installed size $debugPathName. " +
                    "Try only apk size.", e
                )
            }
        } else {
            Log.e(LOG_TAG, "Cannot parse package $debugPathName. Assuming defaults.")
        }
        return params
    }

    /**
     * Processes Install session, file:// or package:// URI to generate data pertaining to user
     * confirmation for an install. This method also checks if the source app has the AppOp granted
     * to install unknown apps. If an AppOp is to be requested, cache the user action prompt data to
     * be reused once appOp has been granted
     *
     * @return
     *  * [InstallAborted]
     *      *  If install session is invalid (not sealed or resolvedBaseApk path is invalid)
     *      *  Source app doesn't have visibility to target app
     *      *  The APK is invalid
     *      *  URI is invalid
     *      *  Can't get ApplicationInfo for source app, to request AppOp
     *
     *  *  [InstallUserActionRequired]
     *      * If AppOP is granted and user action is required to proceed with install
     *      * If AppOp grant is to be requested from the user
     */
    fun requestUserConfirmation(): InstallStage {
        return if (isTrustedSource) {
            if (localLogv) {
                Log.i(LOG_TAG, "Install allowed")
            }
            // Returns InstallUserActionRequired stage if install details could be successfully
            // computed, else it returns InstallAborted.
            generateConfirmationSnippet()
        } else {
            val unknownSourceStage = handleUnknownSources(appOpRequestInfo)
            if (unknownSourceStage.stageCode == InstallStage.STAGE_READY) {
                // Source app already has appOp granted.
                generateConfirmationSnippet()
            } else {
                unknownSourceStage
            }
        }
    }

    private fun generateConfirmationSnippet(): InstallStage {
        val packageSource: Any?
        val pendingUserActionReason: Int

        if (PackageInstaller.ACTION_CONFIRM_INSTALL == intent.action) {
            val info = packageInstaller.getSessionInfo(sessionId)
            val resolvedPath = info?.resolvedBaseApkPath
            if (info == null || !info.isSealed || resolvedPath == null) {
                Log.e(LOG_TAG, "Session $sessionId in funky state; ignoring")
                return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
            packageSource = Uri.fromFile(File(resolvedPath))
            // TODO: Not sure where is this used yet. PIA.java passes it to
            //  InstallInstalling if not null
            // mOriginatingURI = null;
            // mReferrerURI = null;
            pendingUserActionReason = info.getPendingUserActionReason()
        } else if (PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL == intent.action) {
            val info = packageInstaller.getSessionInfo(sessionId)
            if (info == null || !info.isPreApprovalRequested) {
                Log.e(LOG_TAG, "Session $sessionId in funky state; ignoring")
                return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
            packageSource = info
            // mOriginatingURI = null;
            // mReferrerURI = null;
            pendingUserActionReason = info.getPendingUserActionReason()
        } else {
            // Two possible origins:
            // 1. Installation with SCHEME_PACKAGE.
            // 2. Installation with "file://" for session created by this app
            packageSource =
                if (intent.data?.scheme == SCHEME_PACKAGE) {
                    intent.data
                } else {
                    val stagedSessionInfo = packageInstaller.getSessionInfo(stagedSessionId)
                    Uri.fromFile(File(stagedSessionInfo?.resolvedBaseApkPath!!))
                }
            // mOriginatingURI = mIntent.getParcelableExtra(Intent.EXTRA_ORIGINATING_URI);
            // mReferrerURI = mIntent.getParcelableExtra(Intent.EXTRA_REFERRER);
            pendingUserActionReason = PackageInstaller.REASON_CONFIRM_PACKAGE_CHANGE
        }

        // if there's nothing to do, quietly slip into the ether
        if (packageSource == null) {
            Log.e(LOG_TAG, "Unspecified source")
            return InstallAborted(
                ABORT_REASON_INTERNAL_ERROR,
                resultIntent = Intent().putExtra(
                    Intent.EXTRA_INSTALL_RESULT,
                    PackageManager.INSTALL_FAILED_INVALID_URI
                ),
                activityResultCode = Activity.RESULT_FIRST_USER
            )
        }
        return processAppSnippet(packageSource, pendingUserActionReason)
    }

    /**
     * Parse the Uri (post-commit install session) or use the SessionInfo (pre-commit install
     * session) to set up the installer for this install.
     *
     * @param source The source of package URI or SessionInfo
     * @return
     *  * [InstallUserActionRequired] if source could be processed
     *  * [InstallAborted] if source is invalid or there was an error is processing a source
     */
    private fun processAppSnippet(source: Any, userActionReason: Int): InstallStage {
        return when (source) {
            is Uri -> processPackageUri(source, userActionReason)
            is SessionInfo -> processSessionInfo(source, userActionReason)
            else -> InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
    }

    /**
     * Parse the Uri and set up the installer for this package.
     *
     * @param packageUri The URI to parse
     * @return
     *  * [InstallUserActionRequired] if source could be processed
     *  * [InstallAborted] if source is invalid or there was an error is processing a source
     */
    private fun processPackageUri(packageUri: Uri, userActionReason: Int): InstallStage {
        val scheme = packageUri.scheme
        val packageName = packageUri.schemeSpecificPart
        if (scheme == null) {
            return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
        }
        if (localLogv) {
            Log.i(LOG_TAG, "processPackageUri(): uri = $packageUri, scheme = $scheme")
        }
        when (scheme) {
            SCHEME_PACKAGE -> {
                for (handle in userManager!!.getUserHandles(true)) {
                    val pmForUser = context.createContextAsUser(handle, 0).packageManager
                    try {
                        if (pmForUser.canPackageQuery(callingPackage!!, packageName)) {
                            newPackageInfo = pmForUser.getPackageInfo(
                                packageName,
                                PackageManager.GET_PERMISSIONS
                                    or PackageManager.MATCH_UNINSTALLED_PACKAGES
                            )
                        }
                    } catch (ignored: PackageManager.NameNotFoundException) {
                    }
                }
                if (newPackageInfo == null) {
                    Log.e(
                        LOG_TAG, "Requested package " + packageUri.schemeSpecificPart
                            + " not available. Discontinuing installation"
                    )
                    return InstallAborted(
                        ABORT_REASON_INTERNAL_ERROR,
                        errorDialogType = DLG_PACKAGE_ERROR,
                        resultIntent = Intent().putExtra(
                            Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_FAILED_INVALID_APK
                        ),
                        activityResultCode = Activity.RESULT_FIRST_USER
                    )
                }
                appSnippet = getAppSnippet(context, newPackageInfo!!)
                if (localLogv) {
                    Log.i(LOG_TAG, "Created snippet for " + appSnippet.label)
                }
            }

            ContentResolver.SCHEME_FILE -> {
                val sourceFile = packageUri.path?.let { File(it) }
                newPackageInfo = sourceFile?.let {
                    getPackageInfo(context, it, PackageManager.GET_PERMISSIONS)
                }

                // Check for parse errors
                if (newPackageInfo == null) {
                    Log.w(
                        LOG_TAG, "Parse error when parsing manifest. " +
                            "Discontinuing installation"
                    )
                    return InstallAborted(
                        ABORT_REASON_INTERNAL_ERROR,
                        errorDialogType = DLG_PACKAGE_ERROR,
                        resultIntent = Intent().putExtra(
                            Intent.EXTRA_INSTALL_RESULT,
                            PackageManager.INSTALL_FAILED_INVALID_APK
                        ),
                        activityResultCode = Activity.RESULT_FIRST_USER
                    )
                }
                if (localLogv) {
                    Log.i(LOG_TAG, "Creating snippet for local file $sourceFile")
                }
                appSnippet = getAppSnippet(context, newPackageInfo!!, sourceFile!!)
            }

            else -> {
                Log.e(LOG_TAG, "Unexpected URI scheme $packageUri")
                return InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
        }
        return InstallUserActionRequired(
            USER_ACTION_REASON_INSTALL_CONFIRMATION, appSnippet, isAppUpdating(newPackageInfo!!),
            getUpdateMessage(newPackageInfo!!, userActionReason)
        )
    }

    /**
     * Use the SessionInfo and set up the installer for pre-commit install session.
     *
     * @param sessionInfo The SessionInfo to compose
     * @return [InstallUserActionRequired]
     */
    private fun processSessionInfo(sessionInfo: SessionInfo, userActionReason: Int): InstallStage {
        newPackageInfo = generateStubPackageInfo(sessionInfo.getAppPackageName())
        appSnippet = getAppSnippet(context, sessionInfo)

        return InstallUserActionRequired(
            USER_ACTION_REASON_INSTALL_CONFIRMATION, appSnippet, isAppUpdating(newPackageInfo!!),
            getUpdateMessage(newPackageInfo!!, userActionReason)

        )
    }

    private fun getUpdateMessage(pkgInfo: PackageInfo, userActionReason: Int): String? {
        if (isAppUpdating(pkgInfo)) {
            val existingUpdateOwnerLabel = getExistingUpdateOwnerLabel(pkgInfo)
            val requestedUpdateOwnerLabel = getApplicationLabel(callingPackage)
            if (!TextUtils.isEmpty(existingUpdateOwnerLabel)
                && userActionReason == PackageInstaller.REASON_REMIND_OWNERSHIP
            ) {
                return context.getString(
                    R.string.install_confirm_question_update_owner_reminder,
                    requestedUpdateOwnerLabel, existingUpdateOwnerLabel
                )
            }
        }
        return null
    }

    private fun getExistingUpdateOwnerLabel(pkgInfo: PackageInfo): CharSequence? {
        return try {
            val packageName = pkgInfo.packageName
            val sourceInfo = packageManager.getInstallSourceInfo(packageName)
            val existingUpdateOwner = sourceInfo.updateOwnerPackageName
            getApplicationLabel(existingUpdateOwner)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun getApplicationLabel(packageName: String?): CharSequence? {
        return try {
            val appInfo = packageName?.let {
                packageManager.getApplicationInfo(
                    it, PackageManager.ApplicationInfoFlags.of(0)
                )
            }
            appInfo?.let { packageManager.getApplicationLabel(it) }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun isAppUpdating(newPkgInfo: PackageInfo): Boolean {
        var pkgName = newPkgInfo.packageName
        // Check if there is already a package on the device with this name
        // but it has been renamed to something else.
        val oldName = packageManager.canonicalToCurrentPackageNames(arrayOf(pkgName))
        if (oldName != null && oldName.isNotEmpty() && oldName[0] != null) {
            pkgName = oldName[0]
            newPkgInfo.packageName = pkgName
            newPkgInfo.applicationInfo?.packageName = pkgName
        }

        // Check if package is already installed. display confirmation dialog if replacing pkg
        try {
            // This is a little convoluted because we want to get all uninstalled
            // apps, but this may include apps with just data, and if it is just
            // data we still want to count it as "installed".
            val appInfo = packageManager.getApplicationInfo(
                pkgName, PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
            if (appInfo.flags and ApplicationInfo.FLAG_INSTALLED == 0) {
                return false
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return true
    }

    /**
     * Once the user returns from Settings related to installing from unknown sources, reattempt
     * the installation if the source app is granted permission to install other apps. Abort the
     * installation if the source app is still not granted installing permission.
     *
     * @return
     * * [InstallUserActionRequired] containing data required to ask user confirmation
     * to proceed with the install.
     * * [InstallAborted] if there was an error while recomputing, or the source still
     * doesn't have install permission.
     */
    fun reattemptInstall(): InstallStage {
        val unknownSourceStage = handleUnknownSources(appOpRequestInfo)
        return when (unknownSourceStage.stageCode) {
            InstallStage.STAGE_READY -> {
                // Source app now has appOp granted.
                generateConfirmationSnippet()
            }

            InstallStage.STAGE_ABORTED -> {
                // There was some error in determining the AppOp code for the source app.
                // Abort installation
                unknownSourceStage
            }

            else -> {
                // AppOpsManager again returned a MODE_ERRORED or MODE_DEFAULT op code. This was
                // unexpected while reattempting the install. Let's abort it.
                Log.e(LOG_TAG, "AppOp still not granted.")
                InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
        }
    }

    private fun handleUnknownSources(requestInfo: AppOpRequestInfo): InstallStage {
        if (requestInfo.callingPackage == null) {
            Log.i(LOG_TAG, "No source found for package " + newPackageInfo?.packageName)
            return InstallUserActionRequired(USER_ACTION_REASON_ANONYMOUS_SOURCE)
        }
        // Shouldn't use static constant directly, see b/65534401.
        val appOpStr = AppOpsManager.permissionToOp(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        val appOpMode = appOpsManager!!.noteOpNoThrow(
            appOpStr!!, requestInfo.originatingUid, requestInfo.callingPackage,
            requestInfo.attributionTag, "Started package installation activity"
        )
        if (localLogv) {
            Log.i(LOG_TAG, "handleUnknownSources(): appMode=$appOpMode")
        }

        return when (appOpMode) {
            AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_ERRORED -> {
                if (appOpMode == AppOpsManager.MODE_DEFAULT) {
                    appOpsManager.setMode(
                        appOpStr, requestInfo.originatingUid, requestInfo.callingPackage,
                        AppOpsManager.MODE_ERRORED
                    )
                }
                try {
                    val sourceInfo =
                        packageManager.getApplicationInfo(requestInfo.callingPackage, 0)
                    val sourceAppSnippet = getAppSnippet(context, sourceInfo)
                    InstallUserActionRequired(
                        USER_ACTION_REASON_UNKNOWN_SOURCE, appSnippet = sourceAppSnippet,
                        dialogMessage = requestInfo.callingPackage
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(LOG_TAG, "Did not find appInfo for " + requestInfo.callingPackage)
                    InstallAborted(ABORT_REASON_INTERNAL_ERROR)
                }
            }

            AppOpsManager.MODE_ALLOWED -> InstallReady()

            else -> {
                Log.e(
                    LOG_TAG, "Invalid app op mode $appOpMode for " +
                        "OP_REQUEST_INSTALL_PACKAGES found for uid $requestInfo.originatingUid"
                )
                InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            }
        }
    }

    /**
     * Kick off the installation. Register a broadcast listener to get the result of the
     * installation and commit the staged session here. If the installation was session based,
     * signal the PackageInstaller that the user has granted permission to proceed with the install
     */
    fun initiateInstall() {
        if (sessionId > 0) {
            packageInstaller.setPermissionsResult(sessionId, true)
            if (localLogv) {
                Log.i(LOG_TAG, "Install permission granted for session $sessionId")
            }
            _installResult.value = InstallAborted(
                ABORT_REASON_DONE, activityResultCode = Activity.RESULT_OK
            )
            return
        }
        val uri = intent.data
        if (SCHEME_PACKAGE == uri?.scheme) {
            try {
                packageManager.installExistingPackage(
                    newPackageInfo!!.packageName, PackageManager.INSTALL_REASON_USER
                )
                setStageBasedOnResult(PackageInstaller.STATUS_SUCCESS, -1, null)
            } catch (e: PackageManager.NameNotFoundException) {
                setStageBasedOnResult(
                    PackageInstaller.STATUS_FAILURE, PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                    null)
            }
            return
        }
        if (stagedSessionId <= 0) {
            // How did we even land here?
            Log.e(LOG_TAG, "Invalid local session and caller initiated session")
            _installResult.value = InstallAborted(ABORT_REASON_INTERNAL_ERROR)
            return
        }
        val installId: Int
        try {
            _installResult.value = InstallInstalling(appSnippet)
            installId = InstallEventReceiver.addObserver(
                context, EventResultPersister.GENERATE_NEW_ID
            ) { statusCode: Int, legacyStatus: Int, message: String?, serviceId: Int ->
                setStageBasedOnResult(statusCode, legacyStatus, message)
            }
        } catch (e: OutOfIdsException) {
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE, PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null)
            return
        }
        val broadcastIntent = Intent(BROADCAST_ACTION)
        broadcastIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        broadcastIntent.setPackage(context.packageName)
        broadcastIntent.putExtra(EventResultPersister.EXTRA_ID, installId)
        val pendingIntent = PendingIntent.getBroadcast(
            context, installId, broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            val session = packageInstaller.openSession(stagedSessionId)
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Session $stagedSessionId could not be opened.", e)
            packageInstaller.abandonSession(stagedSessionId)
            setStageBasedOnResult(
                PackageInstaller.STATUS_FAILURE, PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null)
        }
    }

    private fun setStageBasedOnResult(
        statusCode: Int,
        legacyStatus: Int,
        message: String?,
    ) {
        if (localLogv) {
            Log.i(LOG_TAG, "Status code: $statusCode\n" +
                "legacy status: $legacyStatus\n" +
                "message: $message")
        }
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            val shouldReturnResult = intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false)
            val resultIntent = if (shouldReturnResult) {
                Intent().putExtra(Intent.EXTRA_INSTALL_RESULT, PackageManager.INSTALL_SUCCEEDED)
            } else {
                val intent = packageManager.getLaunchIntentForPackage(newPackageInfo!!.packageName)
                if (isLauncherActivityEnabled(intent)) intent else null
            }
            _installResult.setValue(InstallSuccess(appSnippet, shouldReturnResult, resultIntent))
        } else {
            if (statusCode != PackageInstaller.STATUS_FAILURE_ABORTED) {
                _installResult.setValue(InstallFailed(appSnippet, statusCode, legacyStatus, message))
            } else {
                _installResult.setValue(InstallAborted(ABORT_REASON_INTERNAL_ERROR))
            }

        }
    }

    private fun isLauncherActivityEnabled(intent: Intent?): Boolean {
        if (intent == null || intent.component == null) {
            return false
        }
        return (intent.component?.let { packageManager.getComponentEnabledSetting(it) }
            != COMPONENT_ENABLED_STATE_DISABLED)
    }

    /**
     * Cleanup the staged session. Also signal the packageinstaller that an install session is to
     * be aborted
     */
    fun cleanupInstall() {
        if (sessionId > 0) {
            packageInstaller.setPermissionsResult(sessionId, false)
        } else if (stagedSessionId > 0) {
            cleanupStagingSession()
        }
    }

    /**
     * When the identity of the install source could not be determined, user can skip checking the
     * source and directly proceed with the install.
     */
    fun forcedSkipSourceCheck(): InstallStage {
        return generateConfirmationSnippet()
    }

    val stagingProgress: LiveData<Int>
        get() = sessionStager?.progress ?: MutableLiveData(0)

    companion object {
        const val EXTRA_STAGED_SESSION_ID = "com.android.packageinstaller.extra.STAGED_SESSION_ID"
        const val SCHEME_PACKAGE = "package"
        const val BROADCAST_ACTION = "com.android.packageinstaller.ACTION_INSTALL_COMMIT"
        private val LOG_TAG = InstallRepository::class.java.simpleName
    }

    data class CallerInfo(val packageName: String?, val uid: Int)
    data class AppOpRequestInfo(
        val callingPackage: String?,
        val originatingUid: Int,
        val attributionTag: String?,
    )
}
