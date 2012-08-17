This library (com.android.location.provider.jar) is a shared java library
containing classes required by unbundled location providers.

--- Rules of this library ---
o This library is effectively a PUBLIC API for unbundled location providers
  that may be distributed outside the system image. So it MUST BE API STABLE.
  You can add but not remove. The rules are the same as for the
  public platform SDK API.
o This library can see and instantiate internal platform classes (such as
  ProviderRequest.java), but it must not expose them in any public method
  (or by extending them via inheritance). This would break clients of the
  library because they cannot see the internal platform classes.

This library is distributed in the system image, and loaded as
a shared library. So you can change the implementation, but not
the interface. In this way it is like framework.jar.

--- Why does this library exists? ---

Unbundled location providers (such as the NetworkLocationProvider)
can not use internal platform classes.

So ideally all of these classes would be part of the public platform SDK API,
but that doesn't seem like a great idea when only applications with a special
signature can implement this API.

The compromise is this library.

It wraps internal platform classes (like ProviderRequest) with a stable
API that does not leak the internal classes.
