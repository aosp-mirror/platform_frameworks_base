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

function testGetRequestedCapability() {
    let capability = SliceStoreWebInterface.getRequestedCapability();
    document.getElementById("requested_capability").innerHTML =
            "Premium capability requested: " + capability;
}

function testNotifyPurchaseSuccessful(duration_ms_long = 0) {
    SliceStoreWebInterface.notifyPurchaseSuccessful(duration);
    document.getElementById("purchase_successful").innerHTML =
            "Notified purchase success for duration: " + duration;
}

function testNotifyPurchaseFailed() {
    SliceStoreWebInterface.notifyPurchaseFailed();
    document.getElementById("purchase_failed").innerHTML =
            "Notified purchase failed.";
}
