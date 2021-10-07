#!/system/bin/sh

# Preload the native portion libuinputcommand_jni.so to bypass the dependency
# checks in the Java classloader, which prohibit dependencies that aren't
# listed in system/core/rootdir/etc/public.libraries.android.txt.
export LD_PRELOAD=libuinputcommand_jni.so

export CLASSPATH=/system/framework/uinput.jar
exec app_process /system/bin com.android.commands.uinput.Uinput "$@"
