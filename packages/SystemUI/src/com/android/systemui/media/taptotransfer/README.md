# Media Tap-To-Transfer

This package (and child packages) include code for the media tap-to-transfer feature, which
allows users to easily transfer playing media between devices.

In media transfer, there are two devices: the *sender* and the *receiver*. The sender device will
start and stop media casts to the receiver device. On both devices, System UI will display a chip
informing the user about the media cast occurring.

This package is structured so that the sender code is in the sender package, the receiver code is
in the receiver package, and code that's shared between them is in the common package.
