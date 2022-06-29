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

package com.android.internal.statusbar;

/**
 * An interface that will be invoked if the user chooses to undo a transfer.
 */
interface IUndoMediaTransferCallback {

    /**
     * Invoked to notify callers that the user has chosen to undo the media transfer that just
     * occurred.
     *
     * Implementors of this method are repsonsible for actually undoing the transfer.
     */
    oneway void onUndoTriggered();
}
