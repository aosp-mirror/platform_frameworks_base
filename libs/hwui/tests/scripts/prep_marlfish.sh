#marlfish is marlin & sailfish  (☞ﾟヮﾟ)☞

cpubase=/sys/devices/system/cpu

adb root
adb wait-for-device
adb shell stop thermal-engine
adb shell stop perfd

# silver cores
#307200 384000 460800 537600 614400 691200 768000 844800 902400 979200
#1056000 1132800 1209600 1286400 1363200 1440000 1516800 1593600
# gold cores
#307200 384000 460800 537600 614400 691200 748800 825600 902400 979200
#1056000 1132800 1209600 1286400 1363200 1440000 1516800 1593600 1670400
#1747200 1824000 1900800 1977600 2054400 2150400

S=979200
cpu=0
# Changing governor and frequency in one core will be automatically applied
# to other cores in the cluster
while [ $((cpu < 3)) -eq 1 ]; do
    adb shell "echo userspace > $cpubase/cpu2/cpufreq/scaling_governor"
    echo "Setting cpu ${cpu} & $(($cpu + 1)) cluster to $S hz"
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_max_freq"
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_min_freq"
    cpu=$(($cpu + 2))
done

echo "setting GPU bus and idle timer"
adb shell "echo 0 > /sys/class/kgsl/kgsl-3d0/bus_split"
adb shell "echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on"
adb shell "echo 10000 > /sys/class/kgsl/kgsl-3d0/idle_timer"

#0 762 1144 1525 2288 3143 4173 5195 5859 7759 9887 11863 13763
adb shell "echo 13763 > /sys/class/devfreq/soc:qcom,gpubw/min_freq" &> /dev/null

#133000000 214000000 315000000 401800000 510000000 560000000 624000000
echo "performance mode, 315 MHz"
adb shell "echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor"
adb shell "echo 315000000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
adb shell "echo 315000000 > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq"

adb shell "echo 4 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel"
adb shell "echo 4 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel"
