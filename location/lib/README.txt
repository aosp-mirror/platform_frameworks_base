This library (com.android.location.provider.jar) is a shared java library
containing classes required by unbundled providers. The library was created
as a way of exposing API classes outside of the public API before SystemApi
was possible. Now that SystemApi exists, no new classes should ever be added
to this library, and all classes in this library should eventually be
deprecated and new SystemApi replacements offered.

Whether or not classes in this library can ever be removed must be answered on
a case by case basis. Most of the classes are usually referenced by Google Play
services (in which case references can be removed from that code base), but
these classes may also be referenced by OEM code, which must be considered
before any removal.
