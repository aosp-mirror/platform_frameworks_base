/*
** Copyright 2021, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.view;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.InsetsState;
import android.view.ISurfaceControlViewHostParent;
import android.window.ISurfaceSyncGroup;

/**
 * API from content embedder back to embedded content in SurfaceControlViewHost
 * {@hide}
 */
interface ISurfaceControlViewHost {
    /**
     * TODO (b/263273252): Investigate the need for these to be blocking calls or add additional
     * APIs that are blocking
     */
    oneway void onConfigurationChanged(in Configuration newConfig);
    oneway void onDispatchDetachedFromWindow();
    oneway void onInsetsChanged(in InsetsState state, in Rect insetFrame);
    ISurfaceSyncGroup getSurfaceSyncGroup();
    /**
     * Attaches the parent interface so the embedded content can communicate back to the parent.
     * If null is passed in, it will remove the parent interface and no more updates will be sent.
     */
    oneway void attachParentInterface(in @nullable ISurfaceControlViewHostParent parentInterface);
}
