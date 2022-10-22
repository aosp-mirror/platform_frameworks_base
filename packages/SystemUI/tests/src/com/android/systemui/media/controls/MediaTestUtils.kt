package com.android.systemui.media.controls

import com.android.internal.logging.InstanceId
import com.android.systemui.media.controls.models.player.MediaData

class MediaTestUtils {
    companion object {
        val emptyMediaData = MediaData(
            userId = 0,
            initialized = true,
            app = null,
            appIcon = null,
            artist = null,
            song = null,
            artwork = null,
            actions = emptyList(),
            actionsToShowInCompact = emptyList(),
            packageName = "",
            token = null,
            clickIntent = null,
            device = null,
            active = true,
            resumeAction = null,
            isPlaying = false,
            instanceId = InstanceId.fakeInstanceId(-1),
            appUid = -1)
    }
}
