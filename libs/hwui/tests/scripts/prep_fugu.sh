#!/bin/bash

cpubase=/sys/devices/system/cpu
gov=cpufreq/scaling_governor

adb root
adb wait-for-device
thermal=$(adb shell "getprop persist.service.thermal")
echo "thermal status: $thermal"
if [ $thermal -eq 1 ]
then
    echo "Trying to setprop persist.service.thermal 0 and reboot"
    adb shell "setprop persist.service.thermal 0"
    adb reboot
    adb wait-for-device
    thermal=$(adb shell "getprop persist.service.thermal")
    if [ $thermal -eq 1 ]
    then
        echo "thermal property is still 1. Abort."
        exit -1
    fi
    echo "Successfully setup persist.service.thermal to 0"
fi

adb shell stop perfprod

# cores
# 1833000 1750000 1666000 1583000 1500000 1416000 1333000 1250000
# 1166000 1083000 1000000 916000 833000 750000 666000 583000 500000

cpu=0
S=1166000
while [ $((cpu < 3)) -eq 1 ]; do
    echo "Setting cpu ${cpu} & $(($cpu + 1)) cluster to $S hz"
    # cpu0/online doesn't exist, because you can't turned it off, so ignore results of this command
    adb shell "echo 1 > $cpubase/cpu${cpu}/online" &> /dev/null
    adb shell "echo userspace > $cpubase/cpu${cpu}/$gov"
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_max_freq"
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_min_freq"
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_setspeed"
    cpu=$(($cpu + 2))
done

#/sys/class/devfreq/dfrgx/available_frequencies is empty, so set to min
echo "performance mode, 457 MHz"
adb shell "echo performance > /sys/class/devfreq/dfrgx/governor"
adb shell "echo 457000 > /sys/class/devfreq/dfrgx/min_freq"
adb shell "echo 457000 > /sys/class/devfreq/dfrgx/max_freq"
