/*
 * Copyright 2020 The Android Open Source Project
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
package android.app.blob;

/** @hide */
public final class XmlTags {
    public static final String ATTR_VERSION = "v";

    public static final String TAG_SESSIONS = "ss";
    public static final String TAG_BLOBS = "bs";

    // For BlobStoreSession
    public static final String TAG_SESSION = "s";
    public static final String ATTR_ID = "id";
    public static final String ATTR_PACKAGE = "p";
    public static final String ATTR_UID = "u";
    public static final String ATTR_CREATION_TIME_MS = "crt";

    // For BlobMetadata
    public static final String TAG_BLOB = "b";
    public static final String ATTR_USER_ID = "us";

    // For BlobAccessMode
    public static final String TAG_ACCESS_MODE = "am";
    public static final String ATTR_TYPE = "t";
    public static final String TAG_ALLOWED_PACKAGE = "wl";
    public static final String ATTR_CERTIFICATE = "ct";

    // For BlobHandle
    public static final String TAG_BLOB_HANDLE = "bh";
    public static final String ATTR_ALGO = "al";
    public static final String ATTR_DIGEST = "dg";
    public static final String ATTR_LABEL = "lbl";
    public static final String ATTR_EXPIRY_TIME = "ex";
    public static final String ATTR_TAG = "tg";

    // For committer
    public static final String TAG_COMMITTER = "c";
    public static final String ATTR_COMMIT_TIME_MS = "cmt";

    // For leasee
    public static final String TAG_LEASEE = "l";
    public static final String ATTR_DESCRIPTION_RES_NAME = "rn";
    public static final String ATTR_DESCRIPTION = "d";
}
