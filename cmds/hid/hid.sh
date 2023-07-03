#!/system/bin/sh

# Preload the native portion libhidcommand_jni.so to bypass the dependency
# checks in the Java classloader, which prohibit dependencies that aren't
# listed in system/core/rootdir/etc/public.libraries.android.txt.
export LD_PRELOAD=libhidcommand_jni.so

export CLASSPATH=/system/framework/hid.jar
exec app_process /system/bin com.android.commands.hid.Hid "$@"
