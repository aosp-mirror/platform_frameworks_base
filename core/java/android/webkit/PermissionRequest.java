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
 * This class defines a permission request and is used when web content
 * requests access to protected resources. The permission request related events
 * are delivered via {@link WebChromeClient#onPermissionRequest} and
 * {@link WebChromeClient#onPermissionRequestCanceled}.
 *
 * Either {@link #grant(String[]) grant()} or {@link #deny()} must be called in UI
 * thread to respond to the request.
 */
public abstract class PermissionRequest {
    /**
     * Resource belongs to video capture device, like camera.
     */
    public final static String RESOURCE_VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE";
    /**
     * Resource belongs to audio capture device, like microphone.
     */
    public final static String RESOURCE_AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE";
    /**
     * Resource belongs to protected media identifier.
     * After the user grants this resource, the origin can use EME APIs to generate the license
     * requests.
     */
    public final static String RESOURCE_PROTECTED_MEDIA_ID =
            "android.webkit.resource.PROTECTED_MEDIA_ID";

    /**
     * Call this method to get the origin of the web page which is trying to access
     * the restricted resources.
     *
     * @return the origin of web content which attempt to access the restricted
     *         resources.
     */
    public abstract Uri getOrigin();

    /**
     * Call this method to get the resources the web page is trying to access.
     *
     * @return the array of resources the web content wants to access.
     */
    public abstract String[] getResources();

    /**
     * Call this method to grant origin the permission to access the given resources.
     * The granted permission is only valid for this WebView.
     *
     * @param resources the resources granted to be accessed by origin, to grant
     *        request, the requested resources returned by {@link #getResources()}
     *        must be equals or a subset of granted resources.
     *        This parameter is designed to avoid granting permission by accident
     *        especially when new resources are requested by web content.
     */
    public abstract void grant(String[] resources);

    /**
     * Call this method to deny the request.
     */
    public abstract void deny();
}
