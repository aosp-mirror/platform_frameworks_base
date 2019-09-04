# Kotlin in SystemUI

Queue "it's happening" gif.

Kotlin is probably going to be a bit of a wild west for a while, but please
try to follow these guidelines as much as possible.

 - No semi-colons: they are optional, we probably don't want them in the
   future, so let's just not add them.
 - No DSLs: sysui is complicated enough as is, let's not add more layers at
   the moment.
 - Only use extension functions for keeping complex code locality: Don't use
   extension functions to add methods to android classes that you always wished
   were there, instead add them directly to the class and save us the extension.
 - inline, reified, and de-compisition can all be great things: just make sure
   you know what they do and why you are using them.

# Recommended reading

 - [Kotlin](https://kotlinlang.org/)
 - [AndroidX-KTX](https://www.youtube.com/watch?v=st1XVfkDWqk)
 - [Performance and Kotlin tricks](https://www.youtube.com/watch?v=6P20npkvcb8)
