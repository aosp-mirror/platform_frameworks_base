/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.provider;

import android.content.ContentResolver;
import android.content.Intent;

/**
 * These are standard columns for openable URIs. Providers that serve openable
 * URIs <em>must</em> support at least these columns when queried.
 * <p>
 * To find the content type of a URI, use
 * {@link ContentResolver#getType(android.net.Uri)}.
 *
 * @see Intent#CATEGORY_OPENABLE
 */
public interface OpenableColumns {

    /**
     * The human-friendly name of file. If this is not provided then the name should default to the
     * the last segment of the file's URI.
     */
    public static final String DISPLAY_NAME = "_display_name";

    /**
     * The number of bytes in the file identified by the openable URI. Null if unknown.
     */
    public static final String SIZE = "_size";
}
