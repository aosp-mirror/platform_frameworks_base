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

package android.tests.enforcepermission;

interface IProtected {
    @EnforcePermission("INTERNET")
    void ProtectedByInternet();

    @EnforcePermission("VIBRATE")
    void ProtectedByVibrate();

    @EnforcePermission("INTERNET")
    void ProtectedByInternetAndVibrateImplicitly();

    @EnforcePermission("INTERNET")
    void ProtectedByInternetAndAccessNetworkStateImplicitly();

    @EnforcePermission("INTERNET")
    void ProtectedByInternetAndReadSyncSettingsImplicitly();

    @EnforcePermission("TURN_SCREEN_ON")
    void ProtectedByTurnScreenOn();

    @EnforcePermission("READ_CONTACTS")
    void ProtectedByReadContacts();

    @EnforcePermission("READ_CALENDAR")
    void ProtectedByReadCalendar();

    @EnforcePermission(allOf={"INTERNET", "VIBRATE"})
    void ProtectedByInternetAndVibrate();

    @EnforcePermission(allOf={"INTERNET", "READ_SYNC_SETTINGS"})
    void ProtectedByInternetAndReadSyncSettings();

    @EnforcePermission(anyOf={"ACCESS_WIFI_STATE", "VIBRATE"})
    void ProtectedByAccessWifiStateOrVibrate();

    @EnforcePermission(anyOf={"INTERNET", "VIBRATE"})
    void ProtectedByInternetOrVibrate();

    @RequiresNoPermission
    void NotProtected();

    @PermissionManuallyEnforced
    void ManuallyProtected();
}
