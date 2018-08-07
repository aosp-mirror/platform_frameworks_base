The usbdescriptors_ files contain raw USB descriptors from the Google
USB-C to 3.5mm adapter, with different loads connected to the 3.5mm
jack.

usbdescriptors_nothing.bin:
 - The descriptors when the jack is disconnected.

usbdescriptors_headphones.bin:
 - The descriptors when the jack is connected to 32-ohm headphones,
   no microphone.
   The relevant output terminal is:
        bDescriptorSubtype      3 (OUTPUT_TERMINAL)
        bTerminalID            15
        wTerminalType      0x0302 Headphones
   
usbdescriptors_lineout.bin:
 - The descriptors when the jack is connected to a PC line-in jack.
   The relevant output terminal is:
        bDescriptorSubtype      3 (OUTPUT_TERMINAL)
        bTerminalID            15
        wTerminalType      0x0603 Line Connector

usbdescriptors_headset.bin:
 - The descriptors when a headset with microphone and low-impedance
   headphones are connected.
   The relevant input terminal is:
        bDescriptorSubtype      2 (INPUT_TERMINAL)
        bTerminalID             1
        wTerminalType      0x0201 Microphone
   The relevant output terminal is:
        bDescriptorSubtype      3 (OUTPUT_TERMINAL)
        bTerminalID            15
        wTerminalType      0x0302 Headphones


