#!/bin/bash
set -e
# Make sure that entries are not added for packages that are already fully handled using
# annotations.
LOCAL_DIR="$( dirname ${BASH_SOURCE} )"
# Each team should add a <team>_PACKAGES and <team>_EMAIL with the list of packages and
# the team email to use in the event of this detecting an entry in a <team> package. Also
# add <team> to the TEAMS list. 
LIBCORE_PACKAGES="\
  android.icu \
  android.system \
  com.android.bouncycastle \
  com.android.conscrypt \
  com.android.i18n.phonenumbers \
  com.android.okhttp \
  com.sun \
  dalvik \
  java \
  javax \
  libcore \
  org.apache.harmony \
  org.json \
  org.w3c.dom \
  org.xml.sax \
  sun \
  "
LIBCORE_EMAIL=libcore-team@android.com

# List of teams.
TEAMS=LIBCORE

# Generate the list of packages and convert to a regular expression.
PACKAGES=$(for t in $TEAMS; do echo $(eval echo \${${t}_PACKAGES}); done)
RE=$(echo ${PACKAGES} | sed "s/ /|/g")
git show --name-only --pretty=format: $1 | grep "config/hiddenapi-.*txt" | while read file; do
    ENTRIES=$(grep -E "^L(${RE})/" <(git show $1:$file))
    if [[ -n "${ENTRIES}" ]]; then
      echo -e "\e[1m\e[31m$file $1 contains the following entries\e[0m"
      echo -e "\e[1m\e[31mfor packages that are handled using UnsupportedAppUsage. Please remove\e[0m"
      echo -e "\e[1m\e[31mthese entries and add annotations instead.\e[0m"
      # Partition the entries by team and provide contact details to aid in fixing the issue.
      for t in ${TEAMS}
      do
        PACKAGES=$(eval echo \${${t}_PACKAGES})
        RE=$(echo ${PACKAGES} | sed "s/ /|/g")
        TEAM_ENTRIES=$(grep -E "^L(${RE})/" <(echo "${ENTRIES}"))
        if [[ -n "${TEAM_ENTRIES}" ]]; then
          EMAIL=$(eval echo \${${t}_EMAIL})
          echo -e "\e[33mContact ${EMAIL} or compat- for help with the following:\e[0m"
          for i in ${ENTRIES}
          do
            echo -e "\e[33m  ${i}\e[0m"
          done
        fi
      done
      exit 1
    fi
done
