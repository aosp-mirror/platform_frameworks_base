/*
 * Copyright 2015 The Android Open Source Project
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

package android.media;

/**
 * This exception is thrown when the MediaDrm instance has become unusable
 * due to a restart of the mediaserver process.  To continue, the app must
 * release the MediaDrm object, then create and initialize a new one.
 */
public class MediaDrmResetException extends IllegalStateException {
    public MediaDrmResetException(String detailMessage) {
        super(detailMessage);
    }
}
