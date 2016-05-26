#buller is bullhead & angler  (☞ﾟヮﾟ)☞

nr=$(adb shell cat /proc/cpuinfo | grep processor | wc -l)
cpubase=/sys/devices/system/cpu
gov=cpufreq/scaling_governor

adb root
adb wait-for-device

adb shell stop
adb shell stop thermal-engine
adb shell stop perfd

for pid in $( adb shell ps | awk '{ if ( $9 == "surfaceflinger" ) { print $2 } }' ); do
    adb shell kill $pid
done
adb shell setprop debug.egl.traceGpuCompletion 1
adb shell daemonize surfaceflinger
sleep 3
adb shell setprop service.bootanim.exit 1

# LITTLE cores
# 384000 460800 600000 672000 787200 864000 960000 1248000 1440000
# BIG cores
# 384000 480000 633600 768000 864000 960000 1248000 1344000 1440000
# 1536000 1632000 1689600 1824000

cpu=0
S=960000
while [ $((cpu < 4)) -eq 1 ]; do
    echo "Setting cpu $cpu to $S hz";
    adb shell "echo 1 > $cpubase/cpu${cpu}/online";
    adb shell "echo userspace > $cpubase/cpu${cpu}/$gov";
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_max_freq";
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_min_freq";
    adb shell "echo $S > $cpubase/cpu${cpu}/cpufreq/scaling_setspeed";
    cpu=$(($cpu + 1));
done

while [ $((cpu < $nr)) -eq 1 ]; do
  echo "disable cpu $cpu";
  adb shell "echo 0 > $cpubase/cpu${cpu}/online";
  cpu=$(($cpu + 1));
done

echo "setting GPU bus and idle timer"
adb shell "echo 0 > /sys/class/kgsl/kgsl-3d0/bus_split"
adb shell "echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on"
adb shell "echo 10000 > /sys/class/kgsl/kgsl-3d0/idle_timer"

#0 762 1144 1525 2288 3509 4173 5271 5928 7904 9887 11863
adb shell "echo 11863 > /sys/class/devfreq/qcom,gpubw.70/min_freq";

#600000000 510000000 450000000 390000000 305000000 180000000
echo "performance mode, 305 MHz"
adb shell "echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor";
adb shell "echo 305000000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq";
adb shell "echo 305000000 > /sys/class/kgsl/kgsl-3d0/devfreq/max_freq";

echo "adb shell \"cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq\""
adb shell "cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq";
