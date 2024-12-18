package com.android.systemui.mediaprojection.appselector

import android.app.ActivityOptions.LaunchCookie

/**
 * Interface that allows to continue the media projection flow and return the selected app
 * result to the original caller.
 */
interface MediaProjectionAppSelectorResultHandler {
    /**
     * Return selected app to the original caller of the media projection app picker.
     * @param launchCookie launch cookie of the launched activity of the target app, always set
     *     regardless of launching a new task or a recent task
     * @param taskId id of the launched task of the target app, only set to a positive int when
     *     launching a recent task, otherwise set to -1 by default
     */
    fun returnSelectedApp(launchCookie: LaunchCookie, taskId: Int)
}
