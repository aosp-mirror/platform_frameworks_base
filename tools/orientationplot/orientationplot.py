#!/usr/bin/env python
#
# Copyright (C) 2011 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

#
# Plots debug log output from WindowOrientationListener.
# See README.txt for details.
#

import numpy as np
import matplotlib.pyplot as plot
import subprocess
import re
import fcntl
import os
import errno
import bisect
from datetime import datetime, timedelta

# Parameters.
timespan = 15 # seconds total span shown
scrolljump = 5 # seconds jump when scrolling
timeticks = 1 # seconds between each time tick

# Non-blocking stream wrapper.
class NonBlockingStream:
  def __init__(self, stream):
    fcntl.fcntl(stream, fcntl.F_SETFL, os.O_NONBLOCK)
    self.stream = stream
    self.buffer = ''
    self.pos = 0

  def readline(self):
    while True:
      index = self.buffer.find('\n', self.pos)
      if index != -1:
        result = self.buffer[self.pos:index]
        self.pos = index + 1
        return result

      self.buffer = self.buffer[self.pos:]
      self.pos = 0
      try:
        chunk = os.read(self.stream.fileno(), 4096)
      except OSError, e:
        if e.errno == errno.EAGAIN:
          return None
        raise e
      if len(chunk) == 0:
        if len(self.buffer) == 0:
          raise(EOFError)
        else:
          result = self.buffer
          self.buffer = ''
          self.pos = 0
          return result
      self.buffer += chunk

# Plotter
class Plotter:
  def __init__(self, adbout):
    self.adbout = adbout

    self.fig = plot.figure(1)
    self.fig.suptitle('Window Orientation Listener', fontsize=12)
    self.fig.set_dpi(96)
    self.fig.set_size_inches(16, 12, forward=True)

    self.raw_acceleration_x = self._make_timeseries()
    self.raw_acceleration_y = self._make_timeseries()
    self.raw_acceleration_z = self._make_timeseries()
    self.raw_acceleration_magnitude = self._make_timeseries()
    self.raw_acceleration_axes = self._add_timeseries_axes(
        1, 'Raw Acceleration', 'm/s^2', [-20, 20],
        yticks=range(-15, 16, 5))
    self.raw_acceleration_line_x = self._add_timeseries_line(
        self.raw_acceleration_axes, 'x', 'red')
    self.raw_acceleration_line_y = self._add_timeseries_line(
        self.raw_acceleration_axes, 'y', 'green')
    self.raw_acceleration_line_z = self._add_timeseries_line(
        self.raw_acceleration_axes, 'z', 'blue')
    self.raw_acceleration_line_magnitude = self._add_timeseries_line(
        self.raw_acceleration_axes, 'magnitude', 'orange', linewidth=2)
    self._add_timeseries_legend(self.raw_acceleration_axes)

    shared_axis = self.raw_acceleration_axes

    self.filtered_acceleration_x = self._make_timeseries()
    self.filtered_acceleration_y = self._make_timeseries()
    self.filtered_acceleration_z = self._make_timeseries()
    self.filtered_acceleration_magnitude = self._make_timeseries()
    self.filtered_acceleration_axes = self._add_timeseries_axes(
        2, 'Filtered Acceleration', 'm/s^2', [-20, 20],
        sharex=shared_axis,
        yticks=range(-15, 16, 5))
    self.filtered_acceleration_line_x = self._add_timeseries_line(
        self.filtered_acceleration_axes, 'x', 'red')
    self.filtered_acceleration_line_y = self._add_timeseries_line(
        self.filtered_acceleration_axes, 'y', 'green')
    self.filtered_acceleration_line_z = self._add_timeseries_line(
        self.filtered_acceleration_axes, 'z', 'blue')
    self.filtered_acceleration_line_magnitude = self._add_timeseries_line(
        self.filtered_acceleration_axes, 'magnitude', 'orange', linewidth=2)
    self._add_timeseries_legend(self.filtered_acceleration_axes)

    self.tilt_angle = self._make_timeseries()
    self.tilt_angle_axes = self._add_timeseries_axes(
        3, 'Tilt Angle', 'degrees', [-105, 105],
        sharex=shared_axis,
        yticks=range(-90, 91, 30))
    self.tilt_angle_line = self._add_timeseries_line(
        self.tilt_angle_axes, 'tilt', 'black')
    self._add_timeseries_legend(self.tilt_angle_axes)

    self.orientation_angle = self._make_timeseries()
    self.orientation_angle_axes = self._add_timeseries_axes(
        4, 'Orientation Angle', 'degrees', [-25, 375],
        sharex=shared_axis,
        yticks=range(0, 361, 45))
    self.orientation_angle_line = self._add_timeseries_line(
        self.orientation_angle_axes, 'orientation', 'black')
    self._add_timeseries_legend(self.orientation_angle_axes)

    self.current_rotation = self._make_timeseries()
    self.proposed_rotation = self._make_timeseries()
    self.predicted_rotation = self._make_timeseries()
    self.orientation_axes = self._add_timeseries_axes(
        5, 'Current / Proposed Orientation', 'rotation', [-1, 4],
        sharex=shared_axis,
        yticks=range(0, 4))
    self.current_rotation_line = self._add_timeseries_line(
        self.orientation_axes, 'current', 'black', linewidth=2)
    self.predicted_rotation_line = self._add_timeseries_line(
        self.orientation_axes, 'predicted', 'purple', linewidth=3)
    self.proposed_rotation_line = self._add_timeseries_line(
        self.orientation_axes, 'proposed', 'green', linewidth=3)
    self._add_timeseries_legend(self.orientation_axes)

    self.time_until_settled = self._make_timeseries()
    self.time_until_flat_delay_expired = self._make_timeseries()
    self.time_until_swing_delay_expired = self._make_timeseries()
    self.time_until_acceleration_delay_expired = self._make_timeseries()
    self.stability_axes = self._add_timeseries_axes(
        6, 'Proposal Stability', 'ms', [-10, 600],
        sharex=shared_axis,
        yticks=range(0, 600, 100))
    self.time_until_settled_line = self._add_timeseries_line(
        self.stability_axes, 'time until settled', 'black', linewidth=2)
    self.time_until_flat_delay_expired_line = self._add_timeseries_line(
        self.stability_axes, 'time until flat delay expired', 'green')
    self.time_until_swing_delay_expired_line = self._add_timeseries_line(
        self.stability_axes, 'time until swing delay expired', 'blue')
    self.time_until_acceleration_delay_expired_line = self._add_timeseries_line(
        self.stability_axes, 'time until acceleration delay expired', 'red')
    self._add_timeseries_legend(self.stability_axes)

    self.sample_latency = self._make_timeseries()
    self.sample_latency_axes = self._add_timeseries_axes(
        7, 'Accelerometer Sampling Latency', 'ms', [-10, 500],
        sharex=shared_axis,
        yticks=range(0, 500, 100))
    self.sample_latency_line = self._add_timeseries_line(
        self.sample_latency_axes, 'latency', 'black')
    self._add_timeseries_legend(self.sample_latency_axes)

    self.fig.canvas.mpl_connect('button_press_event', self._on_click)
    self.paused = False

    self.timer = self.fig.canvas.new_timer(interval=100)
    self.timer.add_callback(lambda: self.update())
    self.timer.start()

    self.timebase = None
    self._reset_parse_state()

  # Handle a click event to pause or restart the timer.
  def _on_click(self, ev):
    if not self.paused:
      self.paused = True
      self.timer.stop()
    else:
      self.paused = False
      self.timer.start()

  # Initialize a time series.
  def _make_timeseries(self):
    return [[], []]

  # Add a subplot to the figure for a time series.
  def _add_timeseries_axes(self, index, title, ylabel, ylim, yticks, sharex=None):
    num_graphs = 7
    height = 0.9 / num_graphs
    top = 0.95 - height * index
    axes = self.fig.add_axes([0.1, top, 0.8, height],
        xscale='linear',
        xlim=[0, timespan],
        ylabel=ylabel,
        yscale='linear',
        ylim=ylim,
        sharex=sharex)
    axes.text(0.02, 0.02, title, transform=axes.transAxes, fontsize=10, fontweight='bold')
    axes.set_xlabel('time (s)', fontsize=10, fontweight='bold')
    axes.set_ylabel(ylabel, fontsize=10, fontweight='bold')
    axes.set_xticks(range(0, timespan + 1, timeticks))
    axes.set_yticks(yticks)
    axes.grid(True)

    for label in axes.get_xticklabels():
      label.set_fontsize(9)
    for label in axes.get_yticklabels():
      label.set_fontsize(9)

    return axes

  # Add a line to the axes for a time series.
  def _add_timeseries_line(self, axes, label, color, linewidth=1):
    return axes.plot([], label=label, color=color, linewidth=linewidth)[0]

  # Add a legend to a time series.
  def _add_timeseries_legend(self, axes):
    axes.legend(
        loc='upper left',
        bbox_to_anchor=(1.01, 1),
        borderpad=0.1,
        borderaxespad=0.1,
        prop={'size': 10})

  # Resets the parse state.
  def _reset_parse_state(self):
    self.parse_raw_acceleration_x = None
    self.parse_raw_acceleration_y = None
    self.parse_raw_acceleration_z = None
    self.parse_raw_acceleration_magnitude = None
    self.parse_filtered_acceleration_x = None
    self.parse_filtered_acceleration_y = None
    self.parse_filtered_acceleration_z = None
    self.parse_filtered_acceleration_magnitude = None
    self.parse_tilt_angle = None
    self.parse_orientation_angle = None
    self.parse_current_rotation = None
    self.parse_proposed_rotation = None
    self.parse_predicted_rotation = None
    self.parse_time_until_settled = None
    self.parse_time_until_flat_delay_expired = None
    self.parse_time_until_swing_delay_expired = None
    self.parse_time_until_acceleration_delay_expired = None
    self.parse_sample_latency = None

  # Update samples.
  def update(self):
    timeindex = 0
    while True:
      try:
        line = self.adbout.readline()
      except EOFError:
        plot.close()
        return
      if line is None:
        break
      print line

      try:
        timestamp = self._parse_timestamp(line)
      except ValueError, e:
        continue
      if self.timebase is None:
        self.timebase = timestamp
      delta = timestamp - self.timebase
      timeindex = delta.seconds + delta.microseconds * 0.000001

      if line.find('Raw acceleration vector:') != -1:
        self.parse_raw_acceleration_x = self._get_following_number(line, 'x=')
        self.parse_raw_acceleration_y = self._get_following_number(line, 'y=')
        self.parse_raw_acceleration_z = self._get_following_number(line, 'z=')
        self.parse_raw_acceleration_magnitude = self._get_following_number(line, 'magnitude=')

      if line.find('Filtered acceleration vector:') != -1:
        self.parse_filtered_acceleration_x = self._get_following_number(line, 'x=')
        self.parse_filtered_acceleration_y = self._get_following_number(line, 'y=')
        self.parse_filtered_acceleration_z = self._get_following_number(line, 'z=')
        self.parse_filtered_acceleration_magnitude = self._get_following_number(line, 'magnitude=')

      if line.find('tiltAngle=') != -1:
        self.parse_tilt_angle = self._get_following_number(line, 'tiltAngle=')

      if line.find('orientationAngle=') != -1:
        self.parse_orientation_angle = self._get_following_number(line, 'orientationAngle=')

      if line.find('Result:') != -1:
        self.parse_current_rotation = self._get_following_number(line, 'currentRotation=')
        self.parse_proposed_rotation = self._get_following_number(line, 'proposedRotation=')
        self.parse_predicted_rotation = self._get_following_number(line, 'predictedRotation=')
        self.parse_sample_latency = self._get_following_number(line, 'timeDeltaMS=')
        self.parse_time_until_settled = self._get_following_number(line, 'timeUntilSettledMS=')
        self.parse_time_until_flat_delay_expired = self._get_following_number(line, 'timeUntilFlatDelayExpiredMS=')
        self.parse_time_until_swing_delay_expired = self._get_following_number(line, 'timeUntilSwingDelayExpiredMS=')
        self.parse_time_until_acceleration_delay_expired = self._get_following_number(line, 'timeUntilAccelerationDelayExpiredMS=')

        self._append(self.raw_acceleration_x, timeindex, self.parse_raw_acceleration_x)
        self._append(self.raw_acceleration_y, timeindex, self.parse_raw_acceleration_y)
        self._append(self.raw_acceleration_z, timeindex, self.parse_raw_acceleration_z)
        self._append(self.raw_acceleration_magnitude, timeindex, self.parse_raw_acceleration_magnitude)
        self._append(self.filtered_acceleration_x, timeindex, self.parse_filtered_acceleration_x)
        self._append(self.filtered_acceleration_y, timeindex, self.parse_filtered_acceleration_y)
        self._append(self.filtered_acceleration_z, timeindex, self.parse_filtered_acceleration_z)
        self._append(self.filtered_acceleration_magnitude, timeindex, self.parse_filtered_acceleration_magnitude)
        self._append(self.tilt_angle, timeindex, self.parse_tilt_angle)
        self._append(self.orientation_angle, timeindex, self.parse_orientation_angle)
        self._append(self.current_rotation, timeindex, self.parse_current_rotation)
        if self.parse_proposed_rotation >= 0:
          self._append(self.proposed_rotation, timeindex, self.parse_proposed_rotation)
        else:
          self._append(self.proposed_rotation, timeindex, None)
        if self.parse_predicted_rotation >= 0:
          self._append(self.predicted_rotation, timeindex, self.parse_predicted_rotation)
        else:
          self._append(self.predicted_rotation, timeindex, None)
        self._append(self.time_until_settled, timeindex, self.parse_time_until_settled)
        self._append(self.time_until_flat_delay_expired, timeindex, self.parse_time_until_flat_delay_expired)
        self._append(self.time_until_swing_delay_expired, timeindex, self.parse_time_until_swing_delay_expired)
        self._append(self.time_until_acceleration_delay_expired, timeindex, self.parse_time_until_acceleration_delay_expired)
        self._append(self.sample_latency, timeindex, self.parse_sample_latency)
        self._reset_parse_state()

    # Scroll the plots.
    if timeindex > timespan:
      bottom = int(timeindex) - timespan + scrolljump
      self.timebase += timedelta(seconds=bottom)
      self._scroll(self.raw_acceleration_x, bottom)
      self._scroll(self.raw_acceleration_y, bottom)
      self._scroll(self.raw_acceleration_z, bottom)
      self._scroll(self.raw_acceleration_magnitude, bottom)
      self._scroll(self.filtered_acceleration_x, bottom)
      self._scroll(self.filtered_acceleration_y, bottom)
      self._scroll(self.filtered_acceleration_z, bottom)
      self._scroll(self.filtered_acceleration_magnitude, bottom)
      self._scroll(self.tilt_angle, bottom)
      self._scroll(self.orientation_angle, bottom)
      self._scroll(self.current_rotation, bottom)
      self._scroll(self.proposed_rotation, bottom)
      self._scroll(self.predicted_rotation, bottom)
      self._scroll(self.time_until_settled, bottom)
      self._scroll(self.time_until_flat_delay_expired, bottom)
      self._scroll(self.time_until_swing_delay_expired, bottom)
      self._scroll(self.time_until_acceleration_delay_expired, bottom)
      self._scroll(self.sample_latency, bottom)

    # Redraw the plots.
    self.raw_acceleration_line_x.set_data(self.raw_acceleration_x)
    self.raw_acceleration_line_y.set_data(self.raw_acceleration_y)
    self.raw_acceleration_line_z.set_data(self.raw_acceleration_z)
    self.raw_acceleration_line_magnitude.set_data(self.raw_acceleration_magnitude)
    self.filtered_acceleration_line_x.set_data(self.filtered_acceleration_x)
    self.filtered_acceleration_line_y.set_data(self.filtered_acceleration_y)
    self.filtered_acceleration_line_z.set_data(self.filtered_acceleration_z)
    self.filtered_acceleration_line_magnitude.set_data(self.filtered_acceleration_magnitude)
    self.tilt_angle_line.set_data(self.tilt_angle)
    self.orientation_angle_line.set_data(self.orientation_angle)
    self.current_rotation_line.set_data(self.current_rotation)
    self.proposed_rotation_line.set_data(self.proposed_rotation)
    self.predicted_rotation_line.set_data(self.predicted_rotation)
    self.time_until_settled_line.set_data(self.time_until_settled)
    self.time_until_flat_delay_expired_line.set_data(self.time_until_flat_delay_expired)
    self.time_until_swing_delay_expired_line.set_data(self.time_until_swing_delay_expired)
    self.time_until_acceleration_delay_expired_line.set_data(self.time_until_acceleration_delay_expired)
    self.sample_latency_line.set_data(self.sample_latency)

    self.fig.canvas.draw_idle()

  # Scroll a time series.
  def _scroll(self, timeseries, bottom):
    bottom_index = bisect.bisect_left(timeseries[0], bottom)
    del timeseries[0][:bottom_index]
    del timeseries[1][:bottom_index]
    for i, timeindex in enumerate(timeseries[0]):
      timeseries[0][i] = timeindex - bottom

  # Extract a word following the specified prefix.
  def _get_following_word(self, line, prefix):
    prefix_index = line.find(prefix)
    if prefix_index == -1:
      return None
    start_index = prefix_index + len(prefix)
    delim_index = line.find(',', start_index)
    if delim_index == -1:
      return line[start_index:]
    else:
      return line[start_index:delim_index]

  # Extract a number following the specified prefix.
  def _get_following_number(self, line, prefix):
    word = self._get_following_word(line, prefix)
    if word is None:
      return None
    return float(word)

  # Extract an array of numbers following the specified prefix.
  def _get_following_array_of_numbers(self, line, prefix):
    prefix_index = line.find(prefix + '[')
    if prefix_index == -1:
      return None
    start_index = prefix_index + len(prefix) + 1
    delim_index = line.find(']', start_index)
    if delim_index == -1:
      return None

    result = []
    while start_index < delim_index:
      comma_index = line.find(', ', start_index, delim_index)
      if comma_index == -1:
        result.append(float(line[start_index:delim_index]))
        break;
      result.append(float(line[start_index:comma_index]))
      start_index = comma_index + 2
    return result

  # Add a value to a time series.
  def _append(self, timeseries, timeindex, number):
    timeseries[0].append(timeindex)
    timeseries[1].append(number)

  # Parse the logcat timestamp.
  # Timestamp has the form '01-21 20:42:42.930'
  def _parse_timestamp(self, line):
    return datetime.strptime(line[0:18], '%m-%d %H:%M:%S.%f')

# Notice
print "Window Orientation Listener plotting tool"
print "-----------------------------------------\n"
print "Please turn on the Window Orientation Listener logging.  See README.txt."

# Start adb.
print "Starting adb logcat.\n"

adb = subprocess.Popen(['adb', 'logcat', '-s', '-v', 'time', 'WindowOrientationListener:V'],
    stdout=subprocess.PIPE)
adbout = NonBlockingStream(adb.stdout)

# Prepare plotter.
plotter = Plotter(adbout)
plotter.update()

# Main loop.
plot.show()
