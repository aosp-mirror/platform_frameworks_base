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

package android.webkit;

import android.net.Uri;

/**
 * This class wraps a permission request, and is used to request permission for
 * the web content to access the resources.
 *
 * Either {@link #grant(long) grant()} or {@link #deny()} must be called to response the
 * request, otherwise, {@link WebChromeClient#onPermissionRequest(PermissionRequest)} will
 * not be invoked again if there is other permission request in this WebView.
 *
 * @hide
 */
public interface PermissionRequest {
    /**
     * Resource belongs to geolocation service.
     */
    public final static long RESOURCE_GEOLOCATION = 1 << 0;
    /**
     * Resource belongs to video capture device, like camera.
     */
    public final static long RESOURCE_VIDEO_CAPTURE = 1 << 1;
    /**
     * Resource belongs to audio capture device, like microphone.
     */
    public final static long RESOURCE_AUDIO_CAPTURE = 1 << 2;

    /**
     * @return the origin of web content which attempt to access the restricted
     *         resources.
     */
    public Uri getOrigin();

    /**
     * @return a bit mask of resources the web content wants to access.
     */
    public long getResources();

    /**
     * Call this method to grant origin the permission to access the given resources.
     * The granted permission is only valid for this WebView.
     *
     * @param resources the resources granted to be accessed by origin, to grant
     *        request, the requested resources returned by {@link #getResources()}
     *        must be equals or a subset of granted resources.
     *        This parameter is designed to avoid granting permission by accident
     *        especially when new resources are requested by web content.
     *        Calling grant(getResources()) has security issue, the new permission
     *        will be granted without being noticed.
     */
    public void grant(long resources);

    /**
     * Call this method to deny the request.
     */
    public void deny();
}
