#!/usr/bin/env python2.6
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
# Plots debug log output from VelocityTracker.
# Enable DEBUG_VELOCITY to print the output.
#
# This code supports side-by-side comparison of two algorithms.
# The old algorithm should be modified to emit debug log messages containing
# the word "OLD".
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
    self.fig.suptitle('Velocity Tracker', fontsize=12)
    self.fig.set_dpi(96)
    self.fig.set_size_inches(16, 12, forward=True)

    self.velocity_x = self._make_timeseries()
    self.velocity_y = self._make_timeseries()
    self.velocity_magnitude = self._make_timeseries()
    self.velocity_axes = self._add_timeseries_axes(
        1, 'Velocity', 'px/s', [-5000, 5000],
        yticks=range(-5000, 5000, 1000))
    self.velocity_line_x = self._add_timeseries_line(
        self.velocity_axes, 'vx', 'red')
    self.velocity_line_y = self._add_timeseries_line(
        self.velocity_axes, 'vy', 'green')
    self.velocity_line_magnitude = self._add_timeseries_line(
        self.velocity_axes, 'magnitude', 'blue')
    self._add_timeseries_legend(self.velocity_axes)

    shared_axis = self.velocity_axes

    self.old_velocity_x = self._make_timeseries()
    self.old_velocity_y = self._make_timeseries()
    self.old_velocity_magnitude = self._make_timeseries()
    self.old_velocity_axes = self._add_timeseries_axes(
        2, 'Old Algorithm Velocity', 'px/s', [-5000, 5000],
        sharex=shared_axis,
        yticks=range(-5000, 5000, 1000))
    self.old_velocity_line_x = self._add_timeseries_line(
        self.old_velocity_axes, 'vx', 'red')
    self.old_velocity_line_y = self._add_timeseries_line(
        self.old_velocity_axes, 'vy', 'green')
    self.old_velocity_line_magnitude = self._add_timeseries_line(
        self.old_velocity_axes, 'magnitude', 'blue')
    self._add_timeseries_legend(self.old_velocity_axes)

    self.timer = self.fig.canvas.new_timer(interval=100)
    self.timer.add_callback(lambda: self.update())
    self.timer.start()

    self.timebase = None
    self._reset_parse_state()

  # Initialize a time series.
  def _make_timeseries(self):
    return [[], []]

  # Add a subplot to the figure for a time series.
  def _add_timeseries_axes(self, index, title, ylabel, ylim, yticks, sharex=None):
    num_graphs = 2
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
    self.parse_velocity_x = None
    self.parse_velocity_y = None
    self.parse_velocity_magnitude = None
    self.parse_old_velocity_x = None
    self.parse_old_velocity_y = None
    self.parse_old_velocity_magnitude = None

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

      if line.find(': position') != -1:
        self.parse_velocity_x = self._get_following_number(line, 'vx=')
        self.parse_velocity_y = self._get_following_number(line, 'vy=')
        self.parse_velocity_magnitude = self._get_following_number(line, 'speed=')
        self._append(self.velocity_x, timeindex, self.parse_velocity_x)
        self._append(self.velocity_y, timeindex, self.parse_velocity_y)
        self._append(self.velocity_magnitude, timeindex, self.parse_velocity_magnitude)

      if line.find(': OLD') != -1:
        self.parse_old_velocity_x = self._get_following_number(line, 'vx=')
        self.parse_old_velocity_y = self._get_following_number(line, 'vy=')
        self.parse_old_velocity_magnitude = self._get_following_number(line, 'speed=')
        self._append(self.old_velocity_x, timeindex, self.parse_old_velocity_x)
        self._append(self.old_velocity_y, timeindex, self.parse_old_velocity_y)
        self._append(self.old_velocity_magnitude, timeindex, self.parse_old_velocity_magnitude)

    # Scroll the plots.
    if timeindex > timespan:
      bottom = int(timeindex) - timespan + scrolljump
      self.timebase += timedelta(seconds=bottom)
      self._scroll(self.velocity_x, bottom)
      self._scroll(self.velocity_y, bottom)
      self._scroll(self.velocity_magnitude, bottom)
      self._scroll(self.old_velocity_x, bottom)
      self._scroll(self.old_velocity_y, bottom)
      self._scroll(self.old_velocity_magnitude, bottom)

    # Redraw the plots.
    self.velocity_line_x.set_data(self.velocity_x)
    self.velocity_line_y.set_data(self.velocity_y)
    self.velocity_line_magnitude.set_data(self.velocity_magnitude)
    self.old_velocity_line_x.set_data(self.old_velocity_x)
    self.old_velocity_line_y.set_data(self.old_velocity_y)
    self.old_velocity_line_magnitude.set_data(self.old_velocity_magnitude)

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

  # Add a value to a time series.
  def _append(self, timeseries, timeindex, number):
    timeseries[0].append(timeindex)
    timeseries[1].append(number)

  # Parse the logcat timestamp.
  # Timestamp has the form '01-21 20:42:42.930'
  def _parse_timestamp(self, line):
    return datetime.strptime(line[0:18], '%m-%d %H:%M:%S.%f')

# Notice
print "Velocity Tracker plotting tool"
print "-----------------------------------------\n"
print "Please enable debug logging and recompile the code."

# Start adb.
print "Starting adb logcat.\n"

adb = subprocess.Popen(['adb', 'logcat', '-s', '-v', 'time', 'Input:*', 'VelocityTracker:*'],
    stdout=subprocess.PIPE)
adbout = NonBlockingStream(adb.stdout)

# Prepare plotter.
plotter = Plotter(adbout)
plotter.update()

# Main loop.
plot.show()
