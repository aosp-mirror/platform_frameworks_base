# Run this on the errors output by javac of missing resource symbols,
# to generate the set of <java-symbol> commands to have aapt generate
# the symbol for them.
#
# For example: make framework 2>&1 | sed -n -f MakeJavaSymbols.sed | sort -u

s|.*R.id.\([a-zA-Z0-9_]*\).*|  <java-symbol type="id" name="\1" />|gp
s|.*R.attr.\([a-zA-Z0-9_]*\).*|  <java-symbol type="attr" name="\1" />|gp
s|.*R.bool.\([a-zA-Z0-9_]*\).*|  <java-symbol type="bool" name="\1" />|gp
s|.*R.integer.\([a-zA-Z0-9_]*\).*|  <java-symbol type="integer" name="\1" />|gp
s|.*R.color.\([a-zA-Z0-9_]*\).*|  <java-symbol type="color" name="\1" />|gp
s|.*R.dimen.\([a-zA-Z0-9_]*\).*|  <java-symbol type="dimen" name="\1" />|gp
s|.*R.fraction.\([a-zA-Z0-9_]*\).*|  <java-symbol type="fraction" name="\1" />|gp
s|.*R.string.\([a-zA-Z0-9_]*\).*|  <java-symbol type="string" name="\1" />|gp
s|.*R.plurals.\([a-zA-Z0-9_]*\).*|  <java-symbol type="plurals" name="\1" />|gp
s|.*R.array.\([a-zA-Z0-9_]*\).*|  <java-symbol type="array" name="\1" />|gp
s|.*R.drawable.\([a-zA-Z0-9_]*\).*|  <java-symbol type="drawable" name="\1" />|gp
s|.*R.layout.\([a-zA-Z0-9_]*\).*|  <java-symbol type="layout" name="\1" />|gp
s|.*R.anim.\([a-zA-Z0-9_]*\).*|  <java-symbol type="anim" name="\1" />|gp
s|.*R.animator.\([a-zA-Z0-9_]*\).*|  <java-symbol type="animator" name="\1" />|gp
s|.*R.interpolator.\([a-zA-Z0-9_]*\).*|  <java-symbol type="interpolator" name="\1" />|gp
s|.*R.menu.\([a-zA-Z0-9_]*\).*|  <java-symbol type="menu" name="\1" />|gp
s|.*R.xml.\([a-zA-Z0-9_]*\).*|  <java-symbol type="xml" name="\1" />|gp
s|.*R.raw.\([a-zA-Z0-9_]*\).*|  <java-symbol type="raw" name="\1" />|gp
s|.*R.style.\([a-zA-Z0-9_]*\).*|  <java-symbol type="style" name="\1" />|gp
