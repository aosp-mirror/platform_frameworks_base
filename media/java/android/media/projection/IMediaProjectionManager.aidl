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

import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.media.projection.IMediaProjectionWatcherCallback;
import android.media.projection.MediaProjectionInfo;
import android.os.IBinder;
import android.view.ContentRecordingSession;

/** {@hide} */
interface IMediaProjectionManager {
    @UnsupportedAppUsage
    boolean hasProjectionPermission(int uid, String packageName);

    /**
     * Returns a new {@link IMediaProjection} instance associated with the given package.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    IMediaProjection createProjection(int uid, String packageName, int type,
            boolean permanentGrant);

    /**
     * Returns {@code true} if the given {@link IMediaProjection} corresponds to the current
     * projection, or {@code false} otherwise.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    boolean isCurrentProjection(IMediaProjection projection);

    /**
     * Reshows the permisison dialog for the user to review consent they've already granted in
     * the given projection instance.
     *
     * <p>Preconditions:
     * <ul>
     *   <li>{@link IMediaProjection#isValid} returned false, rather than throwing an exception</li>
     *   <li>Given projection instance is the current projection instance.</li>
     * <ul>
     *
     * <p>Returns immediately but waits to start recording until user has reviewed their consent.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void requestConsentForInvalidProjection(IMediaProjection projection);

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    MediaProjectionInfo getActiveProjectionInfo();

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void stopActiveProjection();

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void notifyActiveProjectionCapturedContentResized(int width, int height);

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
                + ".permission.MANAGE_MEDIA_PROJECTION)")
    void notifyActiveProjectionCapturedContentVisibilityChanged(boolean isVisible);

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
                + ".permission.MANAGE_MEDIA_PROJECTION)")
    void addCallback(IMediaProjectionWatcherCallback callback);

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void removeCallback(IMediaProjectionWatcherCallback callback);

    /**
     * Returns {@code true} if it successfully updates the content recording session. Returns
     * {@code false} otherwise, and stops the current projection.
     *
     * <p>If a different session is already in progress, then the pre-existing session is stopped,
     * and the new incoming session takes over. Only updates the session if the given projection is
     * valid.
     *
     * @param incomingSession the nullable incoming content recording session
     * @param projection      the non-null projection the session describes
     */
  @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    boolean setContentRecordingSession(in ContentRecordingSession incomingSession,
            in IMediaProjection projection);
}
