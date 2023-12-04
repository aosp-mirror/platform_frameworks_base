/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.chooser;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Static helper methods that privileged clients can use to initiate Share sessions with extra
 * customization options that aren't usually available in the stock "Resolver/Chooser" flows.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_SUPPORT_NFC_RESOLVER)
@SystemApi
public class CustomChoosers {
    /**
     * Intent action to start a Share session with additional customization options. Clients should
     * use the helper methods in this class to configure their customized share intents, and should
     * avoid using this action to construct their own intents directly.
     */
    private static final String ACTION_SHOW_CUSTOMIZED_RESOLVER =
            "android.service.chooser.action.SHOW_CUSTOMIZED_RESOLVER";

    /**
     * "Extras" key for an ArrayList of {@link ResolveInfo} records which are to be shown as the
     * targets in the customized share session.
     *
     * @hide
     */
    public static final String EXTRA_RESOLVE_INFOS = "android.service.chooser.extra.RESOLVE_INFOS";

    /**
     * Build an {@link Intent} to dispatch a "Chooser flow" that picks a target resolution for the
     * specified {@code target} intent, styling the Chooser UI according to the specified
     * customization parameters.
     *
     * @param target The ambiguous intent that should be resolved to a specific target selected
     * via the Chooser flow.
     * @param title An optional "headline" string to display at the top of the Chooser UI, or null
     * to use the system default.
     * @param resolutionList Explicit resolution info for targets that should be shown in the
     * dispatched Share UI.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_NFC_RESOLVER)
    @SystemApi
    @NonNull
    public static Intent createNfcResolverIntent(
            @NonNull Intent target,
            @Nullable CharSequence title,
            @NonNull List<ResolveInfo> resolutionList) {
        Intent resolverIntent = new Intent(ACTION_SHOW_CUSTOMIZED_RESOLVER);
        resolverIntent.putExtra(Intent.EXTRA_INTENT, target);
        resolverIntent.putExtra(Intent.EXTRA_TITLE, title);
        resolverIntent.putParcelableArrayListExtra(
                EXTRA_RESOLVE_INFOS, new ArrayList<>(resolutionList));
        return resolverIntent;
    }

    private CustomChoosers() {}
}
