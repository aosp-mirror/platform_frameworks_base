framework=/system/framework
bpath=$framework/core.jar:$framework/ext.jar:$framework/framework.jar:$framework/android.test.runner.jar
adb shell exec dalvikvm -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=3001 \
      -Xbootclasspath:$bpath -cp /data/app/android.core.apk \
      -Djava.io.tmpdir=/sdcard/tmp \
      com.android.internal.util.WithFramework junit.textui.TestRunner $*
