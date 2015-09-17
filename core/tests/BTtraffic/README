This is a tool to generate classic Bluetooth traffic with specified period and package size.
Together with the SvcMonitor, which will be called automatically in this android service, can be
used to measure the CPU usage from the Java layer Bluetooth code and the underlying system service
com.android.bluetooth.

1. Server (Listener) - Client (Sender) model. Both run as an Android service.
2. No pairing needed. Communicate via unsecured RFcomm. Client establishes the connection by
providing the MAC addr of the server.
3. Bluetooth has to be turned on on both side.
4. Client can configure the traffic by specifying the transfer period and package size.
5. A separate monitor process will be automatically forked and will be reading from /proc file
system to calculate the cpu usage. The measurement is updated once per second.
6. The monitor process (com.google.android.experimental.svcmonitor/.ScvMonitor) can be run as an
independent service to measure cpu usage on any similarly configured tests (e.g. wifi, BLE). Refer
to SvcMonitor's README for usage and details.

Usage:
To instal the test:
On both the server and client device, install the 2 apk:
$ adb install $OUT/system/app/bttraffic/bttraffic.apk
$ adb install $OUT/system/app/svcmonitor/svcmonitor.apk

To start the service on the SERVER side:
$ adb shell am startservice -a start --ez ack true \
com.google.android.experimental.bttraffic/.BTtraffic

To start the service on the CLIENT side:
$ adb shell am startservice -a start \
-e addr "F8:A9:D0:A8:74:8E" --ei size 1000 --ei period 15 \
com.google.android.experimental.bttraffic/.BTtraffic

To stop the test:
On either the server or client:
$ adb shell am startservice -a stop \
com.google.android.experimental.bttraffic/.BTtraffic

To look at the data:
$ adb logcat | grep bttraffic

Options:
-e addr: MAC addr of the server, in uppercase letter.
--ei size: package size, unit: byte; default: 1024, MAX: 20MB
--ei period: system sleep time between sending each package, unit: ms, default: 5000
                  ** if -1 is provided, client will only send the package once.
--ez ack: whether acknowledge is required (true/false)
