WifiService: Implements the IWifiManager 3rd party API. The API and the device state information (screen on/off, battery state, sleep policy) go as input into the WifiController which tracks high level states as to whether STA or AP mode is operational and controls the WifiStateMachine to handle bringup and shut down.

WifiController: Acts as a controller to the WifiStateMachine based on various inputs (API and device state). Runs on the same thread created in WifiService.

WifiSettingsStore: Tracks the various settings (wifi toggle, airplane toggle, tethering toggle, scan mode toggle) and provides API to figure if wifi should be turned on or off.

WifiTrafficPoller: Polls traffic on wifi and notifies apps listening on it.

WifiNotificationController: Controls whether the open network notification is displayed or not based on the scan results.

WifiStateMachine: Tracks the various states on STA and AP connectivity and handles bring up and shut down.

