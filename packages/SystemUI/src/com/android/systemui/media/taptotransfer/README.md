# Media Tap-To-Transfer

## Overview
This package (and child packages) include code for the media tap-to-transfer feature, which
allows users to easily transfer playing media between devices.

In media transfer, there are two devices: the **sender** and the **receiver**. The sender device
will start and stop media casts to the receiver device. On both devices, System UI will display a
chip informing the user about the media cast occurring.

**Important**: System UI is **not responsible** for performing the media transfer. System UI
**only** displays an informational chip; external clients are responsible for performing the media
transfer and informing System UI about the transfer status.

## Information flow
External clients notify System UI about the transfer status by calling `@SystemApi`s in
`StatusBarManager`. For the sender device, use the `updateMediaTapToTransferSenderDisplay` API; for
the receiver, use the `updateMediaTapToTransferReceiverDisplay` API. The APIs eventually flow into
SystemUI's `CommandQueue`, which then notifies callbacks about the new state.
`MediaTttChipControllerSender` implements the sender callback, and `MediaTttChipControllerReceiver`
implements the receiver callback. These controllers will then show or hide the tap-to-transfer chip
(depending on what information was sent in the API).

## Architecture
This package is structured so that the sender code is in the `sender` package, the receiver code is
in the `receiver` package, and code that's shared between them is in the `common` package.

* The `ChipStateSender` and `ChipStateReceiver` classes are enums that describe all the possible
  transfer states (transfer started, transfer succeeded, etc.) and include relevant parameters for
  each state.
* The `ChipSenderInfo` and `ChipReceiverInfo` classes are simple data classes that contain all the
  information needed to display a chip. They include the transfer state, information about the media
  being transferred, etc.
* The `MediaTttChipControllerSender` and `MediaTttChipControllerReceiver` classes are responsible
  for showing or hiding the chip and updating the chip view based on information from the
  `ChipInfo`. `MediaTttChipControllerCommon` has all the common logic for adding and removing the
  view to the window and also includes any display logic that can be shared between the sender and
  receiver. The sender and receiver controller subclasses have the display logic that's specific to
  just the sender or just the receiver.

## Testing
If you want to test out the tap-to-transfer chip without using the `@SystemApi`s, you can use adb
commands instead. Refer to `MediaTttCommandLineHelper` for information about adb commands.
