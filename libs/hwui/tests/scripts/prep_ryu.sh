adb root
adb wait-for-device
adb shell stop thermal-engine
adb shell stop perfd

# 51000 102000 204000 306000 408000 510000 612000 714000 816000 918000
# 1020000 1122000 1224000 1326000 1428000 1530000 1632000 1734000 1836000 1912500
S=1326000
echo "set cpu to $S hz";
adb shell "echo userspace > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
adb shell "echo $S > /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq"
adb shell "echo $S > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq"

#01: core 76 MHz emc 408 MHz
#02: core 153 MHz emc 665 MHz
#03: core 230 MHz emc 800 MHz *
#04: core 307 MHz emc 1065 MHz
#05: core 384 MHz emc 1331 MHz
#06: core 460 MHz emc 1600 MHz
#07: core 537 MHz emc 1600 MHz
#08: core 614 MHz emc 1600 MHz
#09: core 691 MHz emc 1600 MHz
#0a: core 768 MHz emc 1600 MHz
#0b: core 844 MHz emc 1600 MHz
#0c: core 921 MHz emc 1600 MHz
#0d: core 998 MHz emc 1600 MHz
#AC: core 230 MHz emc 800 MHz a A d D

echo "set gpu to core 307 MHz emc 1065 MHz"
# it will lock gpu until you touch a screen
adb shell "echo 04 > /sys/devices/57000000.gpu/pstate"
