This is a test app for the USB accessory APIs.  It consists of two parts:

AccessoryChat - A Java app with a chat-like UI that sends and receives strings
                via the UsbAccessory class.

accessorychat - A C command-line program that communicates with AccessoryChat.
                This program behaves as if it were a USB accessory.
                It builds both for the host (Linux PC) and as an android
                command line program, which will work if run as root on an
                android device with USB host support
