This folder contains utils to properly perform timed suspend and wakeup.

AlarmService - a service that client can bind to and perform:
1) holding wakelock (singleton to this service)
2) setting alarm for a specified period and releasing the wakelock; service
   call will block until alarm has been triggered and the wakelock is held
3) releasing the wakelock

SleepHelper - a self instrumentation meant as a convenient way to trigger
the service functions from command line. Corresponding to service function
above, supported operations are:
1) holding wakelock
am instrument -w -e command prepare \
  com.android.testing.sleephelper/.SetAlarm

2) setting alarm and wait til triggered
am instrument -w -e command set_wait \
  -e param <time in ms> com.android.testing.sleephelper/.SetAlarm
Note: for the function to work properly, "-w" parameter is required

3) releasing wakelock
am instrument -w -e command done \
  com.android.testing.sleephelper/.SetAlarm
