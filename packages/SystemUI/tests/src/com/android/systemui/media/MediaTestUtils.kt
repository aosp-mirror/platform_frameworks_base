package com.android.systemui.media

import com.android.internal.logging.InstanceId

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
            instanceId = InstanceId.fakeInstanceId(-1),
            appUid = -1)
    }
}