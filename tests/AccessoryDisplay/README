This directory contains sample code to test the use of virtual
displays created over an Android Open Accessories Protocol link.

--- DESCRIPTION ---

There are two applications with two distinct roles: a sink
and a source.

1. Sink Application

The role of the sink is to emulate an external display that happens
to be connected using the USB accessory protocol.  Think of it as
a monitor or video dock that the user will want to plug a phone into.

The sink application uses the UsbDevice APIs to receive connections
from the source device over USB.  The sink acts as a USB host
in this arrangement and will provide power to the source.

The sink application decodes encoded video from the source and
displays it in a SurfaceView.  The sink also injects passes touch
events to the source over USB HID.

2. Source Application

The role of the source is to present some content onto an external
display that happens to be attached over USB.  This is the typical
role that a phone or tablet might have when the user is trying to
play content to an external monitor.

The source application uses the UsbAccessory APIs to connect
to the sink device over USB.  The source acts as a USB peripheral
in this arrangement and will receive power from the sink.

The source application uses the DisplayManager APIs to create
a private virtual display which passes the framebuffer through
an encoder and streams the output to the sink over USB.  Then
the application opens a Presentation on the new virtual display
and shows a silly cube animation.

--- USAGE ---

These applications should be installed on two separate Android
devices which are then connected using a USB OTG cable.
Remember that the sink device is functioning as the USB host
so the USB OTG cable should be plugged directly into it.

When connected, the applications should automatically launch
on each device.  The source will then begin to project display
contents to the sink.

