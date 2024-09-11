/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.projection;

import android.media.projection.IMediaProjectionCallback;
import android.os.IBinder;
import android.app.ActivityOptions.LaunchCookie;

/** {@hide} */
interface IMediaProjection {
    void start(IMediaProjectionCallback callback);
    void stop();

    boolean canProjectAudio();
    boolean canProjectVideo();
    boolean canProjectSecureVideo();

    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    int applyVirtualDisplayFlags(int flags);

    void registerCallback(IMediaProjectionCallback callback);

    void unregisterCallback(IMediaProjectionCallback callback);

    /**
     * Returns the {@link LaunchCookie} identifying the task to record. Will always be set
     * regardless of starting a new task or recent task
     */
    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    LaunchCookie getLaunchCookie();

    /**
     * Returns the taskId identifying the task to record. Will only be set in the case of
     * launching a recent task, otherwise set to -1.
     */
    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    int getTaskId();

    /**
     * Updates the {@link LaunchCookie} identifying the task to record.
     */
    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void setLaunchCookie(in LaunchCookie launchCookie);

    /**
     * Updates the taskId identifying the task to record.
     */
    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void setTaskId(in int taskId);


    /**
     * Returns {@code true} if this token is still valid. A token is valid as long as the token
     * hasn't timed out before it was used, and the token is only used once.
     *
     * <p>If the {@link IMediaProjection} is not valid, then either throws an exception if the
     * target SDK is at least {@code U}, or returns {@code false} for target SDK below {@code U}.
     *
     * @throws IllegalStateException If the caller's target SDK is at least {@code U} and the
     * projection is not valid.
     */
    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    boolean isValid();

    /**
     * Sets that {@link MediaProjection#createVirtualDisplay} has been invoked with this token (it
     * should only be called once).
     */
    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void notifyVirtualDisplayCreated(int displayId);
}
