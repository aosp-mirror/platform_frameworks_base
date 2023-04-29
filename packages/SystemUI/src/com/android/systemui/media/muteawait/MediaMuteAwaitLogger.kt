package com.android.systemui.media.muteawait

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.MediaMuteAwaitLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import javax.inject.Inject

/** Log messages for [MediaMuteAwaitConnectionManager]. */
@SysUISingleton
class MediaMuteAwaitLogger @Inject constructor(
    @MediaMuteAwaitLog private val buffer: LogBuffer
) {
    /** Logs that a muted device has been newly added. */
    fun logMutedDeviceAdded(deviceAddress: String, deviceName: String, hasMediaUsage: Boolean) =
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = deviceAddress
                str2 = deviceName
                bool1 = hasMediaUsage
            },
            {
                "Muted device added: address=$str1 name=$str2 hasMediaUsage=$bool1"
            }
        )

    /** Logs that a muted device has been removed. */
    fun logMutedDeviceRemoved(
        deviceAddress: String,
        deviceName: String,
        hasMediaUsage: Boolean,
        isMostRecentDevice: Boolean
    ) = buffer.log(
        TAG,
        LogLevel.DEBUG,
        {
            str1 = deviceAddress
            str2 = deviceName
            bool1 = hasMediaUsage
            bool2 = isMostRecentDevice
        },
        {
            "Muted device removed: " +
                    "address=$str1 name=$str2 hasMediaUsage=$bool1 isMostRecentDevice=$bool2"
        }
    )
}

private const val TAG = "MediaMuteAwait"
