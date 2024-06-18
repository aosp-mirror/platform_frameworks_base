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
import android.media.projection.ReviewGrantedConsentResult;
import android.os.IBinder;
import android.view.ContentRecordingSession;

/** {@hide} */
interface IMediaProjectionManager {
    /**
     * Intent extra indicating if user must review access to the consent token already granted.
     */
    const String EXTRA_USER_REVIEW_GRANTED_CONSENT = "extra_media_projection_user_consent_required";

    /**
     * Intent extra indicating the package attempting to re-use granted consent.
     */
    const String EXTRA_PACKAGE_REUSING_GRANTED_CONSENT =
            "extra_media_projection_package_reusing_consent";

    /**
     * Returns whether a combination of process UID and package has the projection permission.
     *
     * @param processUid the process UID as returned by {@link android.os.Process.myUid()}.
     */
    @UnsupportedAppUsage
    boolean hasProjectionPermission(int processUid, String packageName);

    /**
     * Returns a new {@link IMediaProjection} instance associated with the given package.
     *
     * @param processUid the process UID as returned by {@link android.os.Process.myUid()}.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    IMediaProjection createProjection(int processUid, String packageName, int type,
            boolean permanentGrant);

    /**
     * Returns the current {@link IMediaProjection} instance associated with the given
     * package and process UID, or {@code null} if it is not possible to re-use the current
     * projection.
     *
     * <p>Should only be invoked when the user has reviewed consent for a re-used projection token.
     * Requires that there is a prior session waiting for the user to review consent, and the given
     * package details match those on the current projection.
     *
     * @see {@link #isCurrentProjection}
     *
     * @param processUid the process UID as returned by {@link android.os.Process.myUid()}.
     */
    @EnforcePermission("android.Manifest.permission.MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    IMediaProjection getProjection(int processUid, String packageName);

    /**
     * Returns {@code true} if the given {@link IMediaProjection} corresponds to the current
     * projection, or {@code false} otherwise.
     */
    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
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
    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void requestConsentForInvalidProjection(in IMediaProjection projection);

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    MediaProjectionInfo getActiveProjectionInfo();

    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void stopActiveProjection();

    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void notifyActiveProjectionCapturedContentResized(int width, int height);

    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
                + ".permission.MANAGE_MEDIA_PROJECTION)")
    void notifyActiveProjectionCapturedContentVisibilityChanged(boolean isVisible);

    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
                + ".permission.MANAGE_MEDIA_PROJECTION)")
    MediaProjectionInfo addCallback(IMediaProjectionWatcherCallback callback);

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
     * @throws SecurityException If the provided projection is not current.
     */
  @EnforcePermission("MANAGE_MEDIA_PROJECTION")
  @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    boolean setContentRecordingSession(in ContentRecordingSession incomingSession,
            in IMediaProjection projection);

    /**
     * Sets the result of the user reviewing the recording permission, when the host app is re-using
     * the consent token.
     *
     * <p>Ignores the provided result if the given projection is not the current projection.
     *
     * <p>Based on the given result:
     * <ul>
     *   <li>If UNKNOWN or RECORD_CANCEL, then tear down the recording.</li>
     *   <li>If RECORD_CONTENT_DISPLAY, then record the default display.</li>
     *   <li>If RECORD_CONTENT_TASK, record the task indicated by
     *     {@link IMediaProjection#getLaunchCookie}.</li>
     * </ul>
     * @param projection The projection associated with the consent result. Must be the current
     * projection instance, unless the given result is RECORD_CANCEL.
     */
    @EnforcePermission("android.Manifest.permission.MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void setUserReviewGrantedConsentResult(ReviewGrantedConsentResult consentResult,
            in @nullable IMediaProjection projection);

    /**
     * Notifies system server that the permission request was initiated.
     *
     * <p>Only used for emitting atoms.
     *
     * @param hostProcessUid        The uid of the process requesting consent to capture, may be an
     *                              app or SystemUI.
     * @param sessionCreationSource Only set if the state is MEDIA_PROJECTION_STATE_INITIATED.
     *                              Indicates the entry point for requesting the permission. Must be
     *                              a valid state defined
     *                              in the SessionCreationSource enum.
     */
    @EnforcePermission("android.Manifest.permission.MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    oneway void notifyPermissionRequestInitiated(int hostProcessUid, int sessionCreationSource);

    /**
     * Notifies system server that the permission request was displayed.
     *
     * <p>Only used for emitting atoms.
     *
     * @param hostProcessUid The uid of the process requesting consent to capture, may be an app or
     *                       SystemUI.
     */
    @EnforcePermission("android.Manifest.permission.MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    oneway void notifyPermissionRequestDisplayed(int hostProcessUid);

    /**
     * Notifies system server that the permission request was cancelled.
     *
     * <p>Only used for emitting atoms.
     *
     * @param hostProcessUid The uid of the process requesting consent to capture, may be an app or
     *                       SystemUI.
     */
    @EnforcePermission("android.Manifest.permission.MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    oneway void notifyPermissionRequestCancelled(int hostProcessUid);

    /**
     * Notifies system server that the app selector was displayed.
     *
     * <p>Only used for emitting atoms.
     *
     * @param hostProcessUid The uid of the process requesting consent to capture, may be an app or
     *                       SystemUI.
     */
    @EnforcePermission("android.Manifest.permission.MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    oneway void notifyAppSelectorDisplayed(int hostProcessUid);

    @EnforcePermission("MANAGE_MEDIA_PROJECTION")
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void notifyWindowingModeChanged(int contentToRecord, int targetProcessUid, int windowingMode);
}
