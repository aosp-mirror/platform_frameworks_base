/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.telephony.DomainSelectionService.SelectionAttributes;

/**
 * Implemented as part of the {@link DomainSelectionService} to implement domain selection
 * for a specific use case.
 * @hide
 */
public interface DomainSelector {
    /**
     * Cancel an ongoing selection operation. It is up to the DomainSelectionService
     * to clean up all ongoing operations with the framework.
     */
    void cancelSelection();

    /**
     * Reselect a domain due to the call not setting up properly.
     *
     * @param attr attributes required to select the domain.
     */
    void reselectDomain(@NonNull SelectionAttributes attr);

    /**
     * Finish the selection procedure and clean everything up.
     */
    void finishSelection();
}
