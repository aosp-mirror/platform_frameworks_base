This folder contains sample files for each of the 4 XML Wi-Fi config store files in Android 11 AOSP.
OEMs can use these files as reference for converting their previous customized
formats into the AOSP format. The conversion logic needs to be written in
WifiMigration.java class, i.e each OEM needs to modify
WifiMigration.convertAndRetrieveSharedConfigStoreFile() and the
WifiMigration.convertAndRetrieveUserConfigStoreFile() methods.

The 4 files are:

Shared files
============
1) WifiConfigStore.xml - General storage for shared configurations. Includes
user's saved Wi-Fi networks.
AOSP Path in Android 10: /data/misc/wifi/WifiConfigStore.xml
AOSP Path in Android 11: /data/misc/apexdata/com.android/wifi/WifiConfigStore.xml
Sample File (in this folder): Shared_WifiConfigStore.xml

2) WifiConfigStoreSoftAp.xml - Storage for user's softap/tethering configuration.
AOSP Path in Android 10: /data/misc/wifi/softap.conf.
Note: Was key/value format in Android 10. Conversion to XML done in SoftApConfToXmlMigrationUtil.java.
AOSP Path in Android 11: /data/misc/apexdata/com.android/wifi/WifiConfigStore.xml
Sample File (in this folder): Shared_WifiConfigStoreSoftAp.xml

User specific files
==================
3) WifiConfigStore.xml - General storage for user specific configurations. Includes
user's saved passpoint networks, Wi-Fi network request approvals, etc.
AOSP Path in Android 10: /data/misc_ce/<userId>/wifi/WifiConfigStore.xml
AOSP Path in Android 11: /data/misc_ce/<userId>/apexdata/com.android/wifi/WifiConfigStore.xml
Sample File (in this folder): User_WifiConfigStore.xml

4) WifiConfigStoreNetworkSuggestions.xml - Storage for app installed network suggestions.
AOSP Path in Android 10: /data/misc_ce/<userId>/wifi/WifiConfigStoreNetworkSuggestions.xml
AOSP Path in Android 11: /data/misc_ce/<userId>/apexdata/com.android/wifi/WifiConfigStoreNetworkSuggestions.xml
Sample File (in this folder): User_WifiConfigStoreNetworkSuggestions.xml
