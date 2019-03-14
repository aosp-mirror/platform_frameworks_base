#
# Copyright (C) 2018 The Android Open Source Project
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

#################################################################
###
###  DO NOT MODIFY THIS FILE
###  This is a copy of androidx's benchmark/lockClocks.sh
###  Make changes there instead then copy here!
###
#################################################################

# This script can be used to lock device clocks to stable levels for comparing
# different versions of software.  Since the clock levels are not necessarily
# indicative of real world behavior, this should **never** be used to compare
# performance between different device models.

# Fun notes for maintaining this file:
#      `expr` can deal with ints > INT32_MAX, but if compares cannot. This is why we use MHz.
#      `expr` can sometimes evaluate right-to-left. This is why we use parens.
#      Everything below the initial host-check isn't bash - Android uses mksh
#      mksh allows `\n` in an echo, bash doesn't
#      can't use `awk`

CPU_TARGET_FREQ_PERCENT=50
GPU_TARGET_FREQ_PERCENT=50

if [ "`command -v getprop`" == "" ]; then
    if [ -n "`command -v adb`" ]; then
        echo ""
        echo "Pushing $0 and running it on device..."
        dest=/data/local/tmp/`basename $0`
        adb push $0 ${dest}
        adb shell ${dest}
        adb shell rm ${dest}
        exit
    else
        echo "Could not find adb. Options are:"
        echo "  1. Ensure adb is on your \$PATH"
        echo "  2. Use './gradlew lockClocks'"
        echo "  3. Manually adb push this script to your device, and run it there"
        exit -1
    fi
fi

# require root
if [ "`id -u`" -ne "0" ]; then
    echo "Not running as root, cannot lock clocks, aborting"
    exit -1
fi

DEVICE=`getprop ro.product.device`
MODEL=`getprop ro.product.model`

# Find CPU max frequency, and lock big cores to an available frequency
# that's >= $CPU_TARGET_FREQ_PERCENT% of max. Disable other cores.
function_lock_cpu() {
    CPU_BASE=/sys/devices/system/cpu
    GOV=cpufreq/scaling_governor

    # Find max CPU freq, and associated list of available freqs
    cpuMaxFreq=0
    cpuAvailFreqCmpr=0
    cpuAvailFreq=0
    enableIndices=''
    disableIndices=''
    cpu=0
    while [ -f ${CPU_BASE}/cpu${cpu}/online ]; do
        # enable core, so we can find its frequencies
        echo 1 > ${CPU_BASE}/cpu${cpu}/online

        maxFreq=`cat ${CPU_BASE}/cpu$cpu/cpufreq/cpuinfo_max_freq`
        availFreq=`cat ${CPU_BASE}/cpu$cpu/cpufreq/scaling_available_frequencies`
        availFreqCmpr=${availFreq// /-}

        if [ ${maxFreq} -gt ${cpuMaxFreq} ]; then
            # new highest max freq, look for cpus with same max freq and same avail freq list
            cpuMaxFreq=${maxFreq}
            cpuAvailFreq=${availFreq}
            cpuAvailFreqCmpr=${availFreqCmpr}

            if [ -z ${disableIndices} ]; then
                disableIndices="$enableIndices"
            else
                disableIndices="$disableIndices $enableIndices"
            fi
            enableIndices=${cpu}
        elif [ ${maxFreq} == ${cpuMaxFreq} ] && [ ${availFreqCmpr} == ${cpuAvailFreqCmpr} ]; then
            enableIndices="$enableIndices $cpu"
        else
            disableIndices="$disableIndices $cpu"
        fi
        cpu=$(($cpu + 1))
    done

    # Chose a frequency to lock to that's >= $CPU_TARGET_FREQ_PERCENT% of max
    # (below, 100M = 1K for KHz->MHz * 100 for %)
    TARGET_FREQ_MHZ=`expr \( ${cpuMaxFreq} \* ${CPU_TARGET_FREQ_PERCENT} \) \/ 100000`
    chosenFreq=0
    for freq in ${cpuAvailFreq}; do
        freqMhz=`expr ${freq} \/ 1000`
        if [ ${freqMhz} -ge ${TARGET_FREQ_MHZ} ]; then
            chosenFreq=${freq}
            break
        fi
    done

    # enable 'big' CPUs
    for cpu in ${enableIndices}; do
        freq=${CPU_BASE}/cpu$cpu/cpufreq

        echo 1 > ${CPU_BASE}/cpu${cpu}/online
        echo userspace > ${CPU_BASE}/cpu${cpu}/${GOV}
        echo ${chosenFreq} > ${freq}/scaling_max_freq
        echo ${chosenFreq} > ${freq}/scaling_min_freq
        echo ${chosenFreq} > ${freq}/scaling_setspeed

        # validate setting the freq worked
        obsCur=`cat ${freq}/scaling_cur_freq`
        obsMin=`cat ${freq}/scaling_min_freq`
        obsMax=`cat ${freq}/scaling_max_freq`
        if [ obsCur -ne ${chosenFreq} ] || [ obsMin -ne ${chosenFreq} ] || [ obsMax -ne ${chosenFreq} ]; then
            echo "Failed to set CPU$cpu to $chosenFreq Hz! Aborting..."
            echo "scaling_cur_freq = $obsCur"
            echo "scaling_min_freq = $obsMin"
            echo "scaling_max_freq = $obsMax"
            exit -1
        fi
    done

    # disable other CPUs (Note: important to enable big cores first!)
    for cpu in ${disableIndices}; do
      echo 0 > ${CPU_BASE}/cpu${cpu}/online
    done

    echo "\nLocked CPUs ${enableIndices// /,} to $chosenFreq / $maxFreq KHz"
    echo "Disabled CPUs ${disableIndices// /,}"
}

# If we have a Qualcomm GPU, find its max frequency, and lock to
# an available frequency that's >= GPU_TARGET_FREQ_PERCENT% of max.
function_lock_gpu_kgsl() {
    if [ ! -d /sys/class/kgsl/kgsl-3d0/ ]; then
        # not kgsl, abort
        echo "\nCurrently don't support locking GPU clocks of $MODEL ($DEVICE)"
        return -1
    fi
    if [ ${DEVICE} == "walleye" ] || [ ${DEVICE} == "taimen" ]; then
        # Workaround crash
        echo "\nUnable to lock GPU clocks of $MODEL ($DEVICE)"
        return -1
    fi

    GPU_BASE=/sys/class/kgsl/kgsl-3d0

    gpuMaxFreq=0
    gpuAvailFreq=`cat $GPU_BASE/devfreq/available_frequencies`
    for freq in ${gpuAvailFreq}; do
        if [ ${freq} -gt ${gpuMaxFreq} ]; then
            gpuMaxFreq=${freq}
        fi
    done

    # (below, 100M = 1M for MHz * 100 for %)
    TARGET_FREQ_MHZ=`expr \( ${gpuMaxFreq} \* ${GPU_TARGET_FREQ_PERCENT} \) \/ 100000000`

    chosenFreq=${gpuMaxFreq}
    index=0
    chosenIndex=0
    for freq in ${gpuAvailFreq}; do
        freqMhz=`expr ${freq} \/ 1000000`
        if [ ${freqMhz} -ge ${TARGET_FREQ_MHZ} ] && [ ${chosenFreq} -ge ${freq} ]; then
            # note avail freq are generally in reverse order, so we don't break out of this loop
            chosenFreq=${freq}
            chosenIndex=${index}
        fi
        index=$(($index + 1))
    done
    lastIndex=$(($index - 1))

    firstFreq=`echo $gpuAvailFreq | cut -d" " -f1`

    if [ ${gpuMaxFreq} != ${firstFreq} ]; then
        # pwrlevel is index of desired freq among available frequencies, from highest to lowest.
        # If gpuAvailFreq appears to be in-order, reverse the index
        chosenIndex=$(($lastIndex - $chosenIndex))
    fi

    echo 0 > ${GPU_BASE}/bus_split
    echo 1 > ${GPU_BASE}/force_clk_on
    echo 10000 > ${GPU_BASE}/idle_timer

    echo performance > ${GPU_BASE}/devfreq/governor

    # NOTE: we store in min/max twice, because we don't know if we're increasing
    # or decreasing, and it's invalid to try and set min > max, or max < min
    echo ${chosenFreq} > ${GPU_BASE}/devfreq/min_freq
    echo ${chosenFreq} > ${GPU_BASE}/devfreq/max_freq
    echo ${chosenFreq} > ${GPU_BASE}/devfreq/min_freq
    echo ${chosenFreq} > ${GPU_BASE}/devfreq/max_freq
    echo ${chosenIndex} > ${GPU_BASE}/min_pwrlevel
    echo ${chosenIndex} > ${GPU_BASE}/max_pwrlevel
    echo ${chosenIndex} > ${GPU_BASE}/min_pwrlevel
    echo ${chosenIndex} > ${GPU_BASE}/max_pwrlevel

    obsCur=`cat ${GPU_BASE}/devfreq/cur_freq`
    obsMin=`cat ${GPU_BASE}/devfreq/min_freq`
    obsMax=`cat ${GPU_BASE}/devfreq/max_freq`
    if [ obsCur -ne ${chosenFreq} ] || [ obsMin -ne ${chosenFreq} ] || [ obsMax -ne ${chosenFreq} ]; then
        echo "Failed to set GPU to $chosenFreq Hz! Aborting..."
        echo "cur_freq = $obsCur"
        echo "min_freq = $obsMin"
        echo "max_freq = $obsMax"
        echo "index = $chosenIndex"
        exit -1
    fi
    echo "\nLocked GPU to $chosenFreq / $gpuMaxFreq Hz"
}

# kill processes that manage thermals / scaling
stop thermal-engine
stop perfd
stop vendor.thermal-engine
stop vendor.perfd

function_lock_cpu

function_lock_gpu_kgsl

# Memory bus - hardcoded per-device for now
if [ ${DEVICE} == "marlin" ] || [ ${DEVICE} == "sailfish" ]; then
    echo 13763 > /sys/class/devfreq/soc:qcom,gpubw/max_freq
else
    echo "\nUnable to lock memory bus of $MODEL ($DEVICE)."
fi

echo "\n$DEVICE clocks have been locked - to reset, reboot the device\n"