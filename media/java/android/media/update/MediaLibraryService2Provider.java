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

import android.media.MediaSession2.ControllerInfo;
import android.os.Bundle;

/**
 * @hide
 */
public interface MediaLibraryService2Provider extends MediaSessionService2Provider {
    // Nothing new for now

    interface MediaLibrarySessionProvider extends MediaSession2Provider {
        void notifyChildrenChanged_impl(ControllerInfo controller, String parentId,
                int itemCount, Bundle extras);
        void notifyChildrenChanged_impl(String parentId, int itemCount, Bundle extras);
        void notifySearchResultChanged_impl(ControllerInfo controller, String query, int itemCount,
                Bundle extras);
    }

    interface LibraryRootProvider {
        String getRootId_impl();
        Bundle getExtras_impl();
    }
}
