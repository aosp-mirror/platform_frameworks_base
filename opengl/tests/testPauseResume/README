Repro steps:

build, install and run the attached test program TestEgl.apk

The program does not draw anything to the screen, it just prints to the log, so use adb logcat to watch the output.

Expected behavior:

constantly increasing "step" count:


W/TestActivity( 1885): ****** step 235 resume
W/TestActivity( 1885): step 236 pause
W/TestActivity( 1885): ****** step 236 resume

and so on.

Actual behavior:

W/TestActivity( 1466): ****** step 25 resume
W/TestActivity( 1466): step 26 pause
W/TestActivity( 1466): ****** step 26 resume
W/dalvikvm( 1466): threadid=8: thread exiting with uncaught exception (group=0x4001d7f0)
E/AndroidRuntime( 1466): FATAL EXCEPTION: GLThread 9
E/AndroidRuntime( 1466): java.lang.RuntimeException: createContext failed: EGL_BAD_ALLOC
E/AndroidRuntime( 1466):        at android.opengl.GLSurfaceView$EglHelper.throwEglException(GLSurfaceView.java:1067)
E/AndroidRuntime( 1466):        at android.opengl.GLSurfaceView$EglHelper.throwEglException(GLSurfaceView.java:1059)
E/AndroidRuntime( 1466):        at android.opengl.GLSurfaceView$EglHelper.start(GLSurfaceView.java:925)
E/AndroidRuntime( 1466):        at android.opengl.GLSurfaceView$GLThread.guardedRun(GLSurfaceView.java:1236)
E/AndroidRuntime( 1466):        at android.opengl.GLSurfaceView$GLThread.run(GLSurfaceView.java:1106)
W/TestActivity( 1466): step 27 pause
W/TestActivity( 1466): ****** step 27 resume
W/TestActivity( 1466): step 28 pause
W/TestActivity( 1466): ****** step 28 resume


See http://b/issue?id=2550745 for further details.
