There are two libraries defined in this directory:
First, com.android.media.remotedisplay.jar is a shared java library
containing classes required by unbundled remote display providers.
Second, com.android.media.remotedisplay.stubs.jar is a stub for the shared
library which provides build-time APIs to the unbundled clients.

At runtime, the shared library is added to the classloader of the app via the
<uses-library> tag. And since Java always tries to load a class from the
parent classloader, regardless of whether the stub library is linked to the
app statically or dynamically, the real classes are loaded from the shared
library.

--- Rules of this library ---
o The stub library is effectively a PUBLIC API for unbundled remote display providers
  that may be distributed outside the system image. So it MUST BE API STABLE.
  You can add but not remove. The rules are the same as for the
  public platform SDK API.
o This library can see and instantiate internal platform classes, but it must not
  expose them in any public method (or by extending them via inheritance). This would
  break clients of the library because they cannot see the internal platform classes.

This library is distributed in the system image, and loaded as
a shared library. So you can change the implementation, but not
the interface. In this way it is like framework.jar.

--- Why does this library exists? ---

Unbundled remote display providers (such as Cast) cannot use internal
platform classes.

This library will eventually be replaced when the media route provider
infrastructure that is currently defined in the support library is reintegrated
with the framework in a new API.  That API isn't ready yet so this
library is a compromise to make new capabilities available to the system
without exposing the full surface area of the support library media
route provider protocol.
