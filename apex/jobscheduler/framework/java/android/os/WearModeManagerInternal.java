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

package android.os;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.StringDef;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Mode Manager local service interface.
 * Example usage: LocalServices.get(WearModeManagerInternal.class).
 *
 * TODO(b/288115060): consolidate with {@link com.android.server.policy.WearModeServiceInternal}
 *
 * @hide
 */
public interface WearModeManagerInternal {

    /**
     * Mode manager quick doze request identifier.
     *
     * <p>Unique identifier that can be used as identifier parameter in
     * registerInternalStateObserver
     * to listen to changes in quick doze request state from mode manager.
     *
     * TODO(b/288276510): convert to int constant
     */
    String QUICK_DOZE_REQUEST_IDENTIFIER = "quick_doze_request";

    /**
     * Mode manager off body state identifier.
     *
     * <p>Unique identifier that can be used as identifier parameter in
     * registerInternalStateObserver
     * to listen to changes in quick doze request state from mode manager.
     *
     * TODO(b/288276510): convert to int constant
     */
    String OFFBODY_STATE_ID = "off_body";

    /**
     * StringDef for Mode manager identifiers.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
            QUICK_DOZE_REQUEST_IDENTIFIER,
            OFFBODY_STATE_ID
    })
    @Target(ElementType.TYPE_USE)
    @interface Identifier {
    }

    /**
     * Method to register a callback in Mode manager.
     *
     * <p>Callback is executed when there is a change of active state for the
     * provided identifier.
     *
     * <p>Mode manager has active states and configured states where active state is the state of a
     * mode/feature as reflected on the device,
     * configured state refers to the configured value of the state of the mode / feature.
     * For e.g.: Quick doze might be configured to be disabled by default but in certain modes, it
     * can be overridden to be enabled. At that point active=enabled, configured=disabled.
     *
     * <p>
     *
     * @param identifier Observer listens for changes to this {@link Identifier}
     * @param executor   Executor used to execute the callback.
     * @param callback   Boolean consumer callback.
     */
    <T> void addActiveStateChangeListener(@NonNull @Identifier String identifier,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<T> callback);
}
