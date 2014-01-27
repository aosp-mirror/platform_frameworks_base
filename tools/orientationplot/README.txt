This directory contains a simple python script for visualizing
the behavior of the WindowOrientationListener.


PREREQUISITES
-------------

1. Python 2.6
2. numpy
3. matplotlib


USAGE
-----

The tool works by scaping the debug log output from WindowOrientationListener
for interesting data and then plotting it.

1. Plug in the device.  Ensure that it is the only device plugged in
   since this script is of very little brain and will get confused otherwise.

2. Enable the Window Orientation Listener debugging data log.
   adb shell setprop debug.orientation.log true
   adb shell stop
   adb shell start

3. Run "orientationplot.py".


WHAT IT ALL MEANS
-----------------

The tool displays several time series graphs that plot the output of the
WindowOrientationListener.  Here you can see the raw accelerometer data,
filtered accelerometer data, measured tilt and orientation angle, confidence
intervals for the proposed orientation and accelerometer latency.

Things to look for:

1. Ensure the filtering is not too aggressive.  If the filter cut-off frequency is
   less than about 1Hz, then the filtered accelorometer data becomes too smooth
   and the latency for orientation detection goes up.  One way to observe this
   is by holding the device vertically in one orientation then sharply turning
   it 90 degrees to a different orientation.  Compared the rapid changes in the
   raw accelerometer data with the smoothed out filtered data.  If the filtering
   is too aggressive, the filter response may lag by hundreds of milliseconds.

2. Ensure that there is an appropriate gap between adjacent orientation angles
   for hysteresis.  Try holding the device in one orientation and slowly turning
   it 90 degrees.  Note that the confidence intervals will all drop to 0 at some
   point in between the two orientations; that is the gap.  The gap should be
   observed between all adjacent pairs of orientations when turning the device
   in either direction.

   Next try holding the device in one orientation and rapidly turning it end
   over end to a midpoint about 45 degrees between two opposing orientations.
   There should be no gap observed initially.  The algorithm should pick one
   of the orientations and settle into it (since it is obviously quite
   different from the original orientation of the device).  However, once it
   settles, the confidence values should start trending to 0 again because
   the measured orientation angle is now within the gap between the new
   orientation and the adjacent orientation.

   In other words, the hysteresis gap applies only when the measured orientation
   angle (say, 45 degrees) is between the current orientation's ideal angle
   (say, 0 degrees) and an adjacent orientation's ideal angle (say, 90 degrees).

3. Accelerometer jitter.  The accelerometer latency graph displays the interval
   between sensor events as reported by the SensorEvent.timestamp field.  It
   should be a fairly constant 60ms.  If the latency jumps around wildly or
   greatly exceeds 60ms then there is a problem with the accelerometer or the
   sensor manager.

4. The orientation angle is not measured when the tilt is too close to 90 or -90
   degrees (refer to MAX_TILT constant).  Consequently, you should expect there
   to be no data.  Likewise, all dependent calculations are suppressed in this case
   so there will be no orientation proposal either.

5. Each orientation has its own bound on allowable tilt angles.  It's a good idea to
   verify that these limits are being enforced by gradually varying the tilt of
   the device until it is inside/outside the limit for each orientation.

6. Orientation changes should be significantly harder when the device is held
   overhead.  People reading on tablets in bed often have their head turned
   a little to the side, or they hold the device loosely so its orientation
   can be a bit unusual.  The tilt is a good indicator of whether the device is
   overhead.
