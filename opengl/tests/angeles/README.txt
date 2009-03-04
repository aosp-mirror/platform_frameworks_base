------------------------------------------------------------------------
San Angeles Observation OpenGL ES version example
Copyright 2004-2005 Jetro Lauha
Web: http://iki.fi/jetro/
See file license.txt for licensing information.
------------------------------------------------------------------------

This is an OpenGL ES port of the small self-running demonstration
called "San Angeles Observation", which was first presented in the
Assembly'2004 event. It won the first place in the 4 KB intro
competition category.

The demonstration features a sightseeing of a futuristic city
having many different kind of buildings and items. Everything is
flat shaded with three different lights.

The original version was made for desktop with OpenGL. It was
naturally heavily size optimized in order to fit it in the size
limit. For this OpenGL ES version example much of the code is
cleaned up and the sound is removed. Also detail level is lowered,
although it still contains over 60000 faces.

The Win32 (2000/XP) binary package of original version is
available from this address: http://jet.ro/files/angeles.zip

First version of this OpenGL ES port was submitted to the Khronos
OpenGL ES Coding Challenge held in 2004-2005.

As a code example, this source shows the following:
  * How to create a minimal and portable ad hoc framework
    for small testing/demonstration programs. This framework
    compiles for both desktop and PocketPC Win32 environment,
    and a separate source is included for Linux with X11.
  * How to dynamically find and use the OpenGL ES DLL or
    shared object, so that the library is not needed at
    the compile/link stage.
  * How to use the basic features of OpenGL ES 1.0/1.1
    Common Lite, such as vertex arrays, color arrays and
    lighting.
  * How to create a self contained small demonstration
    application with objects generated using procedural
    algorithms.

As the original version was optimized for size instead of
performance, that holds true for this OpenGL ES version as
well. Thus the performance could be significantly increased,
for example by changing the code to use glDrawElements
instead of glDrawArrays. The code uses only OpenGL ES 1.0
Common Lite -level function calls without any extensions.

The reference OpenGL ES implementations used for this application:
  * Hybrid's OpenGL ES API Implementation (Gerbera) version 2.0.4
    Prebuilt Win32 PC executable: SanOGLES-Gerbera.exe
  * PowerVR MBX SDK, OpenGL ES Windows PC Emulation version 1.04.14.0170
    Prebuilt Win32 PC executable: SanOGLES-PVRSDK.exe

Note that DISABLE_IMPORTGL preprocessor macro can be used
to specify not to use dynamic runtime binding of the library.
You also need to define preprocessor macro PVRSDK to compile
the source with PowerVR OpenGL ES SDK.

The demo application is briefly tested with a few other OpenGL ES
implementations as well (e.g. Vincent, GLESonGL on Linux, Dell
Axim X50v). Most of these other implementations rendered the demo
erroneously in some aspect. This may indicate that the demo source
could still have some work to do with compatibility and correct
API usage, although the non-conforming implementations are most
probably unfinished as well.

Thanks and Acknowledgements:

* Toni Lönnberg (!Cube) created the music for original version, which
  is not featured in this OpenGL ES port.
* Sara Kapli (st Rana) for additional camera work.
* Paul Bourke for information about the supershapes.

------------------------------------------------------------------------
