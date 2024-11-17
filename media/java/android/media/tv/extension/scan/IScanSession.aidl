/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.scan;

import android.os.Bundle;

/**
 * @hide
 */
interface IScanSession {
    // Start a service scan.
    int startScan(int broadcastType, String countryCode, String operator, in int[] frequency,
        String scanType, String languageCode);
    // Reset the scan information held in TIS.
    int resetScan();
    // Cancel scan.
    int cancelScan();

    // Get available interface for created ScanExtension interface.
    String[] getAvailableExtensionInterfaceNames();
    // Get extension interface for Scan.
    IBinder getExtensionInterface(String name);

    // Clear the results of the service scan from the service database.
    int clearServiceList(in Bundle optionalClearParams);
    // Store the results of the service scan from the service database.
    int storeServiceList();
    // Get a service information specified by the service information ID.
    Bundle getServiceInfo(String serviceInfoId, in String[] keys);
    // Get a service information ID list.
    String[] getServiceInfoIdList();
    // Get a list of service info by the filter.
    Bundle getServiceInfoList(in Bundle filterInfo, in String[] keys);
    // Update the service information.
    int updateServiceInfo(in Bundle serviceInfo);
    // Updates the service information for the specified service information ID in array list.
    int updateServiceInfoByList(in Bundle[] serviceInfo);

    /* DVBI specific functions */
    // Get all of the serviceLists, parsed from Local TV storage, Broadcast, USB file discovery.
    Bundle getServiceLists();
    // Users choose one serviceList from the serviceLists, and install the services.
    int setServiceList(int serviceListRecId);
    // Get all of the packageData, parsed from the selected serviceList XML.
    Bundle getPackageData();
    // Choose the package using package id and install the corresponding services.
    int setPackage(String packageId);
    // Get all of the countryRegionData, parsed from the selected serviceList XML.
    Bundle getCountryRegionData();
    // Choose the countryRegion using countryRegion id, and install the corresponding services.
    int setCountryRegion(String regionId);
    // Get all of the regionData, parsed from the selected serviceList XML.
    Bundle getRegionData();
    // Choose the region using the regionData id, and install the corresponding services.
    int setRegion(String regionId);

    // Get unique session token for the scan.
    String getSessionToken();
    // Release scan resource, the register listener will be released.
    int release();
}
