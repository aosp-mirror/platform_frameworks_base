#!/bin/bash
set -e
# Make sure that entries are not added for packages that are already fully handled using
# annotations.
LOCAL_DIR="$( dirname ${BASH_SOURCE} )"
# Each team should add a <team>_PACKAGES and <team>_EMAIL with the list of packages and
# the team email to use in the event of this detecting an entry in a <team> package. Also
# add <team> to the TEAMS list. 
LIBCORE_PACKAGES="\
  android.system \
  android.test \
  com.android.bouncycastle \
  com.android.okhttp \
  com.sun \
  dalvik \
  java \
  javax \
  junit \
  libcore \
  org.apache.harmony \
  org.json \
  org.w3c.dom \
  org.xml.sax \
  org.xmlpull.v1 \
  sun \
  "
LIBCORE_EMAIL=libcore-team@android.com

I18N_PACKAGES="\
  android.icu \
  "

I18N_EMAIL=$LIBCORE_EMAIL

CONSCRYPT_PACKAGES="\
  com.android.org.conscrypt \
  "

CONSCRYPT_EMAIL=$LIBCORE_EMAIL

# List of teams.
TEAMS="LIBCORE I18N CONSCRYPT"

SHA=$1

# Generate the list of packages and convert to a regular expression.
PACKAGES=$(for t in $TEAMS; do echo $(eval echo \${${t}_PACKAGES}); done)
RE=$(echo ${PACKAGES} | sed "s/ /|/g")
EXIT_CODE=0
for file in $(git show --name-only --pretty=format: $SHA | grep "boot/hiddenapi/hiddenapi-.*txt"); do
    ENTRIES=$(grep -E "^\+L(${RE})/" <(git diff ${SHA}~1 ${SHA} $file) | sed "s|^\+||" || echo)
    if [[ -n "${ENTRIES}" ]]; then
      echo -e "\e[1m\e[31m$file $SHA contains the following entries\e[0m"
      echo -e "\e[1m\e[31mfor packages that are handled using UnsupportedAppUsage. Please remove\e[0m"
      echo -e "\e[1m\e[31mthese entries and add annotations instead.\e[0m"
      # Partition the entries by team and provide contact details to aid in fixing the issue.
      for t in ${TEAMS}
      do
        PACKAGES=$(eval echo \${${t}_PACKAGES})
        TEAM_RE=$(echo ${PACKAGES} | sed "s/ /|/g")
        TEAM_ENTRIES=$(grep -E "^L(${TEAM_RE})/" <(echo "${ENTRIES}") || echo)
        if [[ -n "${TEAM_ENTRIES}" ]]; then
          EMAIL=$(eval echo \${${t}_EMAIL})
          echo -e "\e[33mContact ${EMAIL} for help with the following:\e[0m"
          for i in ${TEAM_ENTRIES}
          do
            echo -e "\e[33m  ${i}\e[0m"
          done
        fi
      done
      EXIT_CODE=1
    fi
done
exit $EXIT_CODE
