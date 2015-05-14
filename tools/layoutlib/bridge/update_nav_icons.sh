#!/bin/sh

# copies the navigation bar icons from system ui code to layoutlib.
# to run, simply execute the script. (if not using bash, cd to the dir
# containing this script and then run by ./update_nav_icons.sh)

# Try to get the location of this script.
if [ -n $BASH ]; then
  # see http://stackoverflow.com/a/246128/1546000
  MY_LOCATION=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
  cd $MY_LOCATION
else
  # Let's assume script was run from the same dir.
  MY_LOCATION=$(pwd)
fi

# Check mac or linux to get sed argument to enable extended regex.
case $(uname -s) in
  Darwin)
    EXT_REGEX="-E"
    ;;
  *)
    EXT_REGEX="-r"
    ;;
esac


FB="frameworks/base"
# frameworks/base relative to current location
FB=$(echo $MY_LOCATION | sed $EXT_REGEX -e "s,.*$FB[^/]*/,," -e "s,[^/]+,..,g")
CURRENT_API=21  # update only if icons change from this api version.
DENSITIES="ldpi mdpi hdpi xhdpi xxhdpi"
ICONS="ic_sysbar_back.png ic_sysbar_home.png ic_sysbar_recent.png"
BARS="./resources/bars/"

for icon in $ICONS
do
  for density in $DENSITIES
  do
    destination="$BARS/v$CURRENT_API/$density/"
    mkdir -p "$destination"  # create if not present.
    cp -v "$FB/packages/SystemUI/res/drawable-$density/$icon" "$destination"
  done

  for density in $DENSITIES
  do
    destination="$BARS/v$CURRENT_API/ldrtl-$density/"
    mkdir -p "$destination"
    cp -v "$FB/packages/SystemUI/res/drawable-ldrtl-$density/$icon" "$destination"
    done
done
