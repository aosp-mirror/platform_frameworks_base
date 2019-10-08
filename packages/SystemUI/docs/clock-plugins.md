# Clock Plugins

## Introduction

The clock appearing on the lock screen and always on display (AOD) can be
customized via the ClockPlugin plugin interface.

## System Health

Clocks are high risk for battery consumption and screen burn-in because they
modify the UI of AOD.

To reduce battery consumption, it is recommended to
target a maximum on-pixel-ratio (OPR) of 5%. Clocks that are composed of
large blocks of color that cause the OPR to exceed 5% should be avoided.

To prevent screen burn-in, clocks should not be composed of large solid
blocks of color, and the clock should be moved around the screen to
distribute the on pixels across a large number of pixels. Software
burn-in testing is a good starting point to assess the pixel shifting
(clock movement) scheme and shape of the clock.

### Software Burn-In Test

The goal is to look for bright spots in the luminosity average over a period of
time. It is difficult to define a threshold where burn-in will occur. It is,
therefore, recommended to compare against an element on AOD that is known not
to cause problems.

For clock face that contain color, it is recommended to use an all white
version of the face. Since white has the highest luminosity, this version of
the clock face represents the worst case scenario.

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

Look for bright spots in the luminosity average. If bright spots are found,
action should be taken to change the shape of the clock face or increase the
amount of pixel shifting.
