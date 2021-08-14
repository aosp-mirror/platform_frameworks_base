# Copyright (C) 2020 The Android Open Source Project
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
# Sony DS4 motion sensor configuration file.
#

# reporting mode 0 - continuous
sensor.accelerometer.reportingMode = 0
# The delay between sensor events corresponding to the lowest frequency in microsecond
sensor.accelerometer.maxDelay = 100000
# The minimum delay allowed between two events in microsecond
sensor.accelerometer.minDelay = 5000
# The power in mA used by this sensor while in use
sensor.accelerometer.power = 1.5

# reporting mode 0 - continuous
sensor.gyroscope.reportingMode = 0
# The delay between sensor events corresponding to the lowest frequency in microsecond
sensor.gyroscope.maxDelay = 100000
# The minimum delay allowed between two events in microsecond
sensor.gyroscope.minDelay = 5000
# The power in mA used by this sensor while in use
sensor.gyroscope.power = 0.8
