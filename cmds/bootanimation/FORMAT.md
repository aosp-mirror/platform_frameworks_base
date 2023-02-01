# bootanimation format

## zipfile paths

The system selects a boot animation zipfile from the following locations, in order:

    /system/media/bootanimation-encrypted.zip (if getprop("vold.decrypt") = '1')
    /system/media/bootanimation.zip
    /oem/media/bootanimation.zip

## zipfile layout

The `bootanimation.zip` archive file includes:

    desc.txt - a text file
    part0  \
    part1   \  directories full of PNG frames
    ...     /
    partN  /

## desc.txt format

The first line defines the general parameters of the animation:

    WIDTH HEIGHT FPS [PROGRESS]

  * **WIDTH:** animation width (pixels)
  * **HEIGHT:** animation height (pixels)
  * **FPS:** frames per second, e.g. 60
  * **PROGRESS:** whether to show a progress percentage on the last part
      + The percentage will be displayed with an x-coordinate of 'c', and a
        y-coordinate set to 1/3 of the animation height.

Next, provide an optional line for dynamic coloring attributes, should dynamic coloring be used.
See the dyanmic coloring section for format details. Skip if you don't use dynamic coloring.

It is followed by a number of rows of the form:

    TYPE COUNT PAUSE PATH [FADE [#RGBHEX [CLOCK1 [CLOCK2]]]]

  * **TYPE:** a single char indicating what type of animation segment this is:
      + `p` -- this part will play unless interrupted by the end of the boot
      + `c` -- this part will play to completion, no matter what
      + `f` -- same as `p` but in addition the specified number of frames is being faded out while
        continue playing. Only the first interrupted `f` part is faded out, other subsequent `f`
        parts are skipped
  * **COUNT:** how many times to play the animation, or 0 to loop forever until boot is complete
  * **PAUSE:** number of FRAMES to delay after this part ends
  * **PATH:** directory in which to find the frames for this part (e.g. `part0`)
  * **FADE:** _(ONLY FOR `f` TYPE)_ number of frames to fade out when interrupted where `0` means
              _immediately_ which makes `f ... 0` behave like `p` and doesn't count it as a fading
              part
  * **RGBHEX:** _(OPTIONAL)_ a background color, specified as `#RRGGBB`
  * **CLOCK1, CLOCK2:** _(OPTIONAL)_ the coordinates at which to draw the current time (for watches):
      + If only `CLOCK1` is provided it is the y-coordinate of the clock and the x-coordinate
        defaults to `c`
      + If both `CLOCK1` and `CLOCK2` are provided then `CLOCK1` is the x-coordinate and `CLOCK2` is
        the y-coodinate
      + Values can be either a positive integer, a negative integer, or `c`
          - `c` -- will centre the text
          - `n` -- will position the text n pixels from the start; left edge for x-axis, bottom edge
            for y-axis
          - `-n` -- will position the text n pixels from the end; right edge for x-axis, top edge
            for y-axis
          - Examples:
              * `-24` or `c -24` will position the text 24 pixels from the top of the screen,
                centred horizontally
              * `16 c` will position the text 16 pixels from the left of the screen, centred
                vertically
              * `-32 32` will position the text such that the bottom right corner is 32 pixels above
                and 32 pixels left of the edges of the screen

There is also a special TYPE, `$SYSTEM`, that loads `/system/media/bootanimation.zip`
and plays that.

## clock_font.png

The file used to draw the time on top of the boot animation. The font format is as follows:
  * The file specifies glyphs for the ascii characters 32-127 (0x20-0x7F), both regular weight and
    bold weight.
  * The image is divided into a grid of characters
  * There are 16 columns and 6 rows
  * Each row is divided in half: regular weight glyphs on the top half, bold glyphs on the bottom
  * For a NxM image each character glyph will be N/16 pixels wide and M/(12*2) pixels high

## progress_font.png

The file used to draw the boot progress in percentage on top of the boot animation. The font format
follows the same specification as the one described for clock_font.png.

## loading and playing frames

Each part is scanned and loaded directly from the zip archive. Within a part directory, every file
(except `trim.txt` and `audio.wav`; see next sections) is expected to be a PNG file that represents
one frame in that part (at the specified resolution). For this reason it is important that frames be
named sequentially (e.g. `part000.png`, `part001.png`, ...) and added to the zip archive in that
order.

## trim.txt

To save on memory, textures may be trimmed by their background color.  trim.txt sequentially lists
the trim output for each frame in its directory, so the frames may be properly positioned.
Output should be of the form: `WxH+X+Y`. Example:

    713x165+388+914
    708x152+388+912
    707x139+388+911
    649x92+388+910

If the file is not present, each frame is assumed to be the same size as the animation.

## audio.wav

Each part may optionally play a `wav` sample when it starts. To enable this, add a file
with the name `audio.wav` in the part directory.

## exiting

The system will end the boot animation (first completing any incomplete or even entirely unplayed
parts that are of type `c`) when the system is finished booting. (This is accomplished by setting
the system property `service.bootanim.exit` to a nonzero string.)

## protips

### PNG compression

Use `zopflipng` if you have it, otherwise `pngcrush` will do. e.g.:

    for fn in *.png ; do
        zopflipng -m ${fn}s ${fn}s.new && mv -f ${fn}s.new ${fn}
        # or: pngcrush -q ....
    done

Some animations benefit from being reduced to 256 colors:

    pngquant --force --ext .png *.png
    # alternatively: mogrify -colors 256 anim-tmp/*/*.png

### creating the ZIP archive

    cd <path-to-pieces>
    zip -0qry -i \*.txt \*.png \*.wav @ ../bootanimation.zip *.txt part*

Note that the ZIP archive is not actually compressed! The PNG files are already as compressed
as they can reasonably get, and there is unlikely to be any redundancy between files.

### Dynamic coloring

Dynamic coloring is a render mode that draws the boot animation using a color transition.
In this mode, instead of directly rendering the PNG images, it treats the R, G, B, A channels
of input images as area masks of dynamic colors, which interpolates between start and end colors
based on animation progression.

To enable it, add the following line as the second line of desc.txt:

    dynamic_colors PATH #RGBHEX0 #RGBHEX1 #RGBHEX2 #RGBHEX3

  * **PATH:** file path of the part to apply dynamic color transition to.
    Any part before this part will be rendered in the start colors.
    Any part after will be rendered in the end colors.
  * **RGBHEX1:** the first start color (masked by the R channel), specified as `#RRGGBB`.
  * **RGBHEX2:** the second start color (masked by the G channel), specified as `#RRGGBB`.
  * **RGBHEX3:** the thrid start color (masked by the B channel), specified as `#RRGGBB`.
  * **RGBHEX4:** the forth start color (masked by the A channel), specified as `#RRGGBB`.

The end colors will be read from the following system properties:

  * persist.bootanim.color1
  * persist.bootanim.color2
  * persist.bootanim.color3
  * persist.bootanim.color4

When missing, the end colors will default to the start colors, effectively producing no color
transition.

Prepare your PNG images so that the R, G, B, A channels indicates the areas to draw color1,
color2, color3 and color4 respectively.
