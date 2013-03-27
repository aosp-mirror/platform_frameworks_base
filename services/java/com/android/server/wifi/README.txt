WifiService: Implements the IWifiManager 3rd party API. The API and the device state information (screen on/off, battery state, sleep policy) go as input into the WifiController which tracks high level states as to whether STA or AP mode is operational and controls the WifiStateMachine to handle bringup and shut down.

WifiController: Acts as a controller to the WifiStateMachine based on various inputs (API and device state). Runs on the same thread created in WifiService.

WifiSettingsStore: Tracks the various settings (wifi toggle, airplane toggle, tethering toggle, scan mode toggle) and provides API to figure if wifi should be turned on or off.

WifiTrafficPoller: Polls traffic on wifi and notifies apps listening on it.

WifiNotificationController: Controls whether the open network notification is displayed or not based on the scan results.

WifiStateMachine: Tracks the various states on STA and AP connectivity and handles bring up and shut down.


Feature description:

Scan-only mode with Wi-Fi turned off:
 - Setup wizard opts user into allowing scanning for improved location. We show no further dialogs in setup wizard since the user has just opted into the feature. This is the reason WifiService listens to DEVICE_PROVISIONED setting.
 - Once the user has his device provisioned, turning off Wi-Fi from settings or from a third party app will show up a dialog reminding the user that scan mode will be on even though Wi-Fi is being turned off. The user has the choice to turn this notification off.
 - In the scan mode, the device continues to allow scanning from any app with Wi-Fi turned off. This is done by disabling all networks and allowing only scans to be passed.
