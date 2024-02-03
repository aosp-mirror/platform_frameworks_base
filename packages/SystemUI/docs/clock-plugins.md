# Clock Plugins

The clock appearing on the lock screen and always on display (AOD) can be customized via the
ClockProviderPlugin plugin interface. The ClockPlugin interface has been removed.

## Lock screen integration
The lockscreen code has two main components, a [clock customization library](../customization), and
the SystemUI [lockscreen host code](../src/com/android/keyguard). The customization library contains
the default clock, and some support code for managing clocks and picking the correct one to render.
It is used by both SystemUI for rendering and ThemePicker for selecting clocks. The SystemUI host is
responsible for maintaining the view within the hierarchy and propagating events to the rendered
clock controller.

### Clock Library Code
[ClockProvider and ClockController](../plugin/src/com/android/systemui/plugins/ClockProviderPlugin.kt)
serve as the interface between the lockscreen (or other host application) and the clock that is
being rendered. Implementing these interfaces is the primary integration point for rendering clocks
in SystemUI. Many of the methods have an empty default implementation and are optional for
implementations if the related event is not interesting to your use case.

[DefaultClockProvider](../customization/src/com/android/systemui/shared/clocks/DefaultClockProvider.kt) and
[DefaultClockController](../customization/src/com/android/systemui/shared/clocks/DefaultClockController.kt)
implement these interfaces for the default lockscreen clock. They handle relevant events from the
lockscreen to update and control the small and large clock view as appropriate.
[AnimatableClockView](../customization/src/com/android/systemui/shared/clocks/AnimatableClockView.kt)
is the view that DefaultClockController uses to render both the small and large clock.
AnimatableClockView has moved location within the repo, but is largely unchanged from previous
versions of android.

The [ClockRegistry](../customization/src/com/android/systemui/shared/clocks/ClockRegistry.kt)
determines which clock should be shown, and handles creating them. It does this by maintaining a
list of [ClockProviders](../plugin/src/com/android/systemui/plugins/ClockProviderPlugin.kt) and
delegating work to them as appropriate. The DefaultClockProvider is compiled in so that it is
guaranteed to be available, and additional ClockProviders are loaded at runtime via
[PluginManager](../plugin_core/src/com/android/systemui/plugins/PluginManager.java).

[ClockPlugin](../plugin/src/com/android/systemui/plugins/ClockPlugin.java) is deprecated and no
longer used by keyguard to render clocks. The host code has been disabled but most of it is still
present in the source tree, although it will likely be removed in a later patch.

### Lockscreen Host
[ClockEventController](../src/com/android/keyguard/ClockEventController.kt) propagates events from
SystemUI event dispatchers to the clock controllers. It maintains a set of event listeners, but
otherwise attempts to do as little work as possible. It does maintain some state where necessary.

[KeyguardClockSwitchController](../src/com/android/keyguard/KeyguardClockSwitchController.java) is
the primary controller for the [KeyguardClockSwitch](../src/com/android/keyguard/KeyguardClockSwitch.java),
which serves as the view parent within SystemUI. Together they ensure the correct clock (either
large or small) is shown, handle animation between clock sizes, and control some sizing/layout
parameters for the clocks.

### Creating a custom clock
In order to create a custom clock, a partner must:
 - Write an implementation of ClockProviderPlugin and the subinterfaces relevant to your use-case.
 - Build this into a seperate plugin apk, and deploy that apk to the device.
    - Alternatively, it could be compiled directly into the customization lib like DefaultClockProvider.
 - PluginManager should automatically notify ClockRegistry of your plugin apk when it arrives on
      device. ClockRegistry will print info logs when it successfully loads a plugin.
 - Set the clock either in ThemePicker or through adb:
      `adb shell settings put secure lock_screen_custom_clock_face '''{\"clockId\":\"ID\"}'''`
 - SystemUI should immediately load and render the new clock if it is available.

### Picker integration
Picker logic for choosing between clocks is available to our partners as part of the ThemePicker.
The clock picking UI will be enabled by default if there is more than 1 clock provided, otherwise
it will be hidden from the UI.

## System Health

Clocks are high risk for battery consumption and screen burn-in because they modify the UI of AOD.

To reduce battery consumption, it is recommended to target a maximum on-pixel-ratio (OPR) of 10%.
Clocks that are composed of large blocks of color that cause the OPR to exceed 10% should be
avoided, but this target will differ depending on the device hardware.

To prevent screen burn-in, clocks should not be composed of large solid blocks of color, and the
clock should be moved around the screen to distribute the on pixels across a large number of pixels.
Software burn-in testing is a good starting point to assess the pixel shifting (clock movement)
scheme and shape of the clock. SystemUI currently treats all clocks the same in this regard using
[KeyguardClockPositionAlgorithm](../src/com/android/systemui/statusbar/phone/KeyguardClockPositionAlgorithm.java)

### Software Burn-In Test

The goal is to look for bright spots in the luminosity average over a period of time. It is
difficult to define a threshold where burn-in will occur. It is, therefore, recommended to compare
against an element on AOD that is known not to cause problems.

For clock face that contain color, it is recommended to use an all white version of the face. Since
white has the highest luminosity, this version of the clock face represents the worst case scenario.

To start, generate a sequence of screenshots for each minute over a 12 hr interval.

```
serial = '84TY004MS' # serial number for the device
count = 1
t = datetime.datetime(2019, 1, 1)
stop = t + datetime.timedelta(hours=12)
if not os.path.exists(OUTPUT_FOLDER):
  raise RuntimeError('output folder "%s" does not exist' % OUTPUT_FOLDER)
while t <= stop:
  os.system("adb -s %s shell 'date %s ; am broadcast -a android.intent.action.TIME_SET'" % (serial, t.strftime('%m%d%H%M%Y.%S')))
  os.system('adb -s %s shell screencap -p > %s/screencap_%06d.png' % (serial, OUTPUT_FOLDER, count))
  t += datetime.timedelta(minutes=1)
  count += 1
```

Average the luminosity of the screenshots.

```
#!python
import numpy
import scipy.ndimage
from imageio import imread, imwrite
import matplotlib.pylab as plt
import os
import os.path

def images(path):
  return [os.path.join(path, name) for name in os.listdir(path) if name.endswith('.png')]

def average(images):
  AVG = None
  for name in images:
    IM = scipy.ndimage.imread(name, mode='L')
    A = numpy.array(IM, dtype=numpy.double)
    if AVG is None:
      AVG = A
    else:
      AVG += A
  AVG /= len(images)
  return numpy.array(AVG, dtype=numpy.uint8)

def main(path):
  ims = images(path)
  if len(ims) == 0:
    raise ValueError("folder '%s' doesn't contain any png files" % path)
  AVG = average(ims)
  imwrite('average.png', AVG)
  plt.imshow(AVG)
  plt.show()

if __name__=='__main__':
  import sys
  main(sys.argv[1])
```

Look for bright spots in the luminosity average. If bright spots are found, action should be taken
to change the shape of the clock face or increase the amount of pixel shifting.
