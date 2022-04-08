This Android service measures CPU usage of a program and an underlying system service it relies on.
An example of this would be an android app XYZ communicates to some other device via Bluetooth. The
SvcMonitor service can monitor the CPU usage of XYZ and com.android.bluetooth.

Usage:

To start the service:
$ adb shell am startservice -a start \
-e java XYZ -e hal com.android.bluetooth \
com.google.android.experimental.svcmonitor/.SvcMonitor

To stop the service:
$ adb shell am startservice -a stop \
com.google.android.experimental.svcmonitor/.SvcMonitor

To stop the service config:
$ adb shell am startservice -a change \
-e java NewName -e hal NewService \
com.google.android.experimental.svcmonitor/.SvcMonitor

To monitor the data:
$ adb logcat | grep XYZ

Options:
-e java NameOfProgram: any running process’s name.
-e hal NameOfSysService: name of the system service the previous process relies on.
--ei period: period between each measurement (frequency). Unit: ms, Default:1000, Min: 100
