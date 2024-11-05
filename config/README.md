# Configuration files for ART compiling the framework

* dirty-image-objects: List of objects in the boot image which are known to
  become dirty. This helps binning objects in the image file.
* preloaded-classes: classes that will be allocated in the boot image, and
  initialized by the zygote.
* preloaded-classes-denylist: Classes that should not be initialized in the
  zygote, as they have app-specific behavior.
