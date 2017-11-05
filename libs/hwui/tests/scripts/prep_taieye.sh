nr=$(adb shell cat /proc/cpuinfo | grep processor | wc -l)
cpubase=/sys/devices/system/cpu

adb root
adb wait-for-device
adb shell stop vendor.perfd
adb shell stop thermal-engine

S=1036800
cpu=0
# Changing governor and frequency in one core will be automatically applied
# to other cores in the cluster
while [ $((cpu < 4)) -eq 1 ]; do
    echo "Setting cpu ${cpu} to $S hz"
    adb shell "echo userspace > $cpubase/cpu${cpu}/cpufreq/scaling_governor"
    adb shell "echo 1 > $cpubase/cpu${cpu}/online"
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_max_freq"
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_min_freq"
    cpu=$(($cpu + 1))
done

while [ $((cpu < $nr)) -eq 1 ]; do
  echo "disable cpu $cpu"
  adb shell "echo 0 > $cpubase/cpu${cpu}/online"
  cpu=$(($cpu + 1))
done

echo "setting GPU bus and idle timer"
adb shell "echo 0 > /sys/class/kgsl/kgsl-3d0/bus_split"
adb shell "echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on"
adb shell "echo 10000 > /sys/class/kgsl/kgsl-3d0/idle_timer"

#0 762 1144 1525 2288 3143 4173 5195 5859 7759 9887 11863 13763
adb shell "echo 7759 > /sys/class/devfreq/soc\:qcom,gpubw/min_freq"
adb shell "echo 7759 > /sys/class/devfreq/soc\:qcom,gpubw/max_freq"
adb shell "echo 7759 > /sys/class/devfreq/soc\:qcom,cpubw/min_freq"
adb shell "echo 7759 > /sys/class/devfreq/soc\:qcom,cpubw/max_freq"
adb shell "echo 7759 > /sys/class/devfreq/soc\:qcom,mincpubw/min_freq"
adb shell "echo 7759 > /sys/class/devfreq/soc\:qcom,mincpubw/max_freq"
adb shell "echo 7759 > /sys/class/devfreq/soc\:qcom,memlat-cpu0/min_freq"
adb shell "echo 7759 > /sys/class/devfreq/soc\:qcom,memlat-cpu0/max_freq"

# 180000000 257000000 342000000 414000000 515000000 596000000 670000000 710000000
echo "performance mode, 342 MHz"
adb shell "echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor"
adb shell "echo 342000000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
adb shell "echo 342000000 > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq"

adb shell "echo 4 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel"
adb shell "echo 4 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel"
