# Configuration files for ART compiling the framework

* boot-image-profile.txt: A list of methods from the boot classpath to be compiled by dex2oat.
  The order in the file is not relevant.
* boot-profile.txt: An ordered list of methods from the boot classpath to be compiled by
  the JIT in the order provided in the file. Used by JIT zygote, when on-device
  signing failed.
* dirty-image-objects: List of objects in the boot image which are known to
  become dirty. This helps binning objects in the image file.
* preloaded-classes: classes that will be allocated in the boot image, and
  initialized by the zygote.
* preloaded-classes-denylist: Classes that should not be initialized in the
  zygote, as they have app-specific behavior.
