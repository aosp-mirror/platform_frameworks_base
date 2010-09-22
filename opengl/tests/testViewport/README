Repro steps:

build, install and run the attached test program TestViewport.apk

Run on Sapphire with Froyo.

The program clears the screen to blue, then draws a full screen white quad that
is alligned to the screen.
(Therefore the whole screen should appear to be white.)


Note that screen is all white.

Rotate screen 90 degrees.

Expected: screen is still all white.

Actual: screen is blue with offset white rectangle.

This bug only happens on Sapphire, it works correctly on Passion.

What happens:

I think the bug is that the gl.glViewport() call in onSurfaceChanged() is
being ignored by the OpenGL driver.

NOTE: If a gl.glViewport call is added at the beginning of the onDrawFrame()
call (which means it is called before every draw), the program runs correctly.
