/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os;

import android.content.Intent;

/**
 * The exception that is thrown when an application exposes a {@code file://}
 * {@link android.net.Uri} to another app.
 * <p>
 * This exposure is discouraged since the receiving app may not have access to
 * the shared path. For example, the receiving app may not have requested the
 * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} runtime permission,
 * or the platform may be sharing the {@link android.net.Uri} across user
 * profile boundaries.
 * <p>
 * Instead, apps should use {@code content://} Uris so the platform can extend
 * temporary permission for the receiving app to access the resource.
 * <p>
 * This is only thrown for applications targeting {@link Build.VERSION_CODES#N}
 * or higher. Applications targeting earlier SDK versions are allowed to share
 * {@code file://} {@link android.net.Uri}, but it's strongly discouraged.
 *
 * @see androidx.core.content.FileProvider
 * @see Intent#FLAG_GRANT_READ_URI_PERMISSION
 */
public class FileUriExposedException extends RuntimeException {
    public FileUriExposedException(String message) {
        super(message);
    }
}
