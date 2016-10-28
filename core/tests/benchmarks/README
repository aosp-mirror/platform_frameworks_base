
These benchmarks use the Caliper benchmark framework, and can be
run on a remote device using Vogar:

http://code.google.com/p/caliper/
http://code.google.com/p/vogar/

-------------------------

Quick Command Line Reference:

# Build vogar and dependencies.
$> mmma -j32 external/vogar

# First make sure art has permissions to dalvik-cache, otherwise it will run slower with interpreter.
$> adb root

# Run vogar in benchmark mode, telling it to use app_process (not dalvikvm which is default)
# Otherwise you will likely crash with UnsatisfiedLinkError despite having correct JNI code.

$> vogar --mode app_process --benchmark path/to/Benchmark.java

# Sometimes your benchmarks might time out, if so increase the timeout:
# (--timeout goes to vogar, and --time-limit goes to caliper)
$> vogar --timeout 1000 --mode app_process --benchmark path/to/Benchmark -- --time-limit 9999s
