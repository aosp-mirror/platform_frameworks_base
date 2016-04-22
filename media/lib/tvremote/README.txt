This library (com.android.media.tv.remoteprovider.jar) is a shared java library
containing classes required by unbundled atv remote providers.

--- Rules of this library ---
o This library is effectively a System API for unbundled emote service provider
  that may be distributed outside the system image. So it MUST BE API STABLE.
  You can add but not remove. The rules are the same as for the
  public platform SDK API.
o This library can see and instantiate internal platform classes, but it must not
  expose them in any public method (or by extending them via inheritance). This would
  break clients of the library because they cannot see the internal platform classes.

This library is distributed in the system image, and loaded as
a shared library. So you can change the implementation, but not
the interface. In this way it is like framework.jar.

--- Why does this library exist? ---

Unbundled atv remote providers (such as Emote app) cannot use internal
platform classes.

This library will eventually be replaced when the inputmanager
infrastructure is ready with APIs allowing unbundled system apps to
inject events into uhid.
That API isn't ready yet so this library is a compromise to
make new capabilities available to the system.