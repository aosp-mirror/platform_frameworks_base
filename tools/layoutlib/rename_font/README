This tool is used to rename the PS name encoded inside the ttf font that we ship
with the SDK. There is bug in Java that returns incorrect results for
java.awt.Font#layoutGlyphVector() if two fonts with same name but differnt
versions are loaded. As a workaround, we rename all the fonts that we ship with
the SDK by appending the font version to its name.


The build_font.py copies all files from input_dir to output_dir while renaming
the font files (*.ttf) in the process.
