package com.android.systemui.util

import android.app.backup.BackupManager
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Wrapper around [BackupManager] useful for testing. */
@SysUISingleton
class BackupManagerProxy @Inject constructor() {

    /** Wrapped version of [BackupManager.dataChanged] */
    fun dataChanged(packageName: String) = BackupManager.dataChanged(packageName)

    /** Wrapped version of [BackupManager.dataChangedForUser] */
    fun dataChangedForUser(userId: Int, packageName: String) =
        BackupManager.dataChangedForUser(userId, packageName)
}
