/*
 * Copyright 2018 The Android Open Source Project
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

package android.media.update;

import android.media.DataSourceDesc;
import android.media.MediaItem2;
import android.media.MediaItem2.Builder;
import android.media.MediaMetadata2;
import android.os.Bundle;

/**
 * @hide
 */
public interface MediaItem2Provider {
    Bundle toBundle_impl();
    String toString_impl();
    int getFlags_impl();
    boolean isBrowsable_impl();
    boolean isPlayable_impl();
    void setMetadata_impl(MediaMetadata2 metadata);
    MediaMetadata2 getMetadata_impl();
    String getMediaId_impl();
    DataSourceDesc getDataSourceDesc_impl();
    boolean equals_impl(Object obj);

    interface BuilderProvider {
        Builder setMediaId_impl(String mediaId);
        Builder setMetadata_impl(MediaMetadata2 metadata);
        Builder setDataSourceDesc_impl(DataSourceDesc dataSourceDesc);
        MediaItem2 build_impl();
    }
}
