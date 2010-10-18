#!/bin/bash
#
# Copyright (C) 2010 The Android Open Source Project
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at

#      http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# mkobb.sh - Creates OBB files on Linux machines

# Directory where we should temporarily mount the OBB loopback to copy files
MOUNTDIR=/tmp

# Presets. Changing these will probably break your OBB on the device
CRYPTO=twofish
FS=vfat
MKFS=mkfs.vfat
LOSETUP=losetup
BLOCK_SIZE=512
SLOP=512 # Amount of filesystem slop in ${BLOCK_SIZE} blocks

find_binaries() {
    MKFSBIN=`which ${MKFS}`
    LOSETUPBIN=`which ${LOSETUP}`
    MOUNTBIN=`which mount`
    UMOUNTBIN=`which umount`
    DDBIN=`which dd`
    RSYNCBIN=`which rsync`
    PBKDF2GEN=`which pbkdf2gen`
}

check_prereqs() {
    if [ "`uname -s`x" != "Linuxx" ]; then \
        echo "ERROR: This script only works on Linux!"
        exit 1
    fi

    if ! egrep -q "^cryptoloop " /proc/modules; then \
        echo "ERROR: Could not find cryptoloop in the kernel."
        echo "Perhaps you need to: modprobe cryptoloop"
        exit 1
    fi

    if ! egrep -q "name\s*:\s*${CRYPTO}$" /proc/crypto; then \
        echo "ERROR: Could not find crypto \`${CRYPTO}' in the kernel."
        echo "Perhaps you need to: modprobe ${CRYPTO}"
        exit 1
    fi

    if ! egrep -q "^\s*${FS}$" /proc/filesystems; then \
        echo "ERROR: Could not find filesystem \`${FS}' in the kernel."
        echo "Perhaps you need to: modprobe ${FS}"
        exit 1
    fi

    if [ "${MKFSBIN}x" = "x" ]; then \
        echo "ERROR: Could not find ${MKFS} in your path!"
        exit 1
    elif [ ! -x "${MKFSBIN}" ]; then \
        echo "ERROR: ${MKFSBIN} is not executable!"
        exit 1
    fi

    if [ "${LOSETUPBIN}x" = "x" ]; then \
        echo "ERROR: Could not find ${LOSETUP} in your path!"
        exit 1
    elif [ ! -x "${LOSETUPBIN}" ]; then \
        echo "ERROR: ${LOSETUPBIN} is not executable!"
        exit 1
    fi

    if [ "${PBKDF2GEN}x" = "x" ]; then \
        echo "ERROR: Could not find pbkdf2gen in your path!"
        exit 1
    fi
}

cleanup() {
    if [ "${loopdev}x" != "x" ]; then \
        ${LOSETUPBIN} -d ${loopdev}
    fi
}

hidden_prompt() {
    unset output
    prompt="$1"
    outvar="$2"
    while read -s -n 1 -p "$prompt" c; do \
        if [ "x$c" = "x" ]; then \
            break
        fi
        prompt='*'
        output="${output}${c}"
    done
    echo
    eval $outvar="$output"
    unset output
}

read_key() {
    hidden_prompt "        Encryption key: " key

    if [ "${key}x" = "x" ]; then \
        echo "ERROR: An empty key is not allowed!"
        exit 1
    fi

    hidden_prompt "Encryption key (again): " key2

    if [ "${key}x" != "${key2}x" ]; then \
        echo "ERROR: Encryption keys do not match!"
        exit 1
    fi
}

onexit() {
    if [ "x${temp_mount}" != "x" ]; then \
        ${UMOUNTBIN} ${temp_mount}
        rmdir ${temp_mount}
    fi
    if [ "x${loop_dev}" != "x" ]; then \
        if [ ${use_crypto} -eq 1 ]; then \
            dmsetup remove -f ${loop_dev}
            ${LOSETUPBIN} -d ${old_loop_dev}
        else \
            ${LOSETUPBIN} -d ${loop_dev}
        fi
    fi
    if [ "x${tempfile}" != "x" -a -f "${tempfile}" ]; then \
        rm -f ${tempfile}
    fi
    if [ "x${keyfile}" != "x" -a -f "${keyfile}" ]; then \
        rm -f ${keyfile}
    fi
    echo "Fatal error."
    exit 1
}

usage() {
    echo "mkobb.sh -- Create OBB files for use on Android"
    echo ""
    echo " -d <directory> Use <directory> as input for OBB files"
    echo " -k <key>       Use <key> to encrypt OBB file"
    echo " -K             Prompt for key to encrypt OBB file"
    echo " -o <filename>  Write OBB file out to <filename>"
    echo " -v             Verbose mode"
    echo " -h             Help; this usage screen"
}

find_binaries
check_prereqs

use_crypto=0

args=`getopt -o d:hk:Ko:v -- "$@"`
eval set -- "$args"

while true; do \
    case "$1" in
        -d) directory=$2; shift 2;;
        -h) usage; exit 1;;
        -k) key=$2; use_crypto=1; shift 2;;
        -K) prompt_key=1; use_crypto=1; shift;;
        -v) verbose=1; shift;;
        -o) filename=$2; shift 2;;
        --) shift; break;;
        *) echo "ERROR: Invalid argument in option parsing! Cannot recover. Ever."; exit 1;;
    esac
done

if [ "${directory}x" = "x" -o ! -d "${directory}" ]; then \
    echo "ERROR: Must specify valid input directory"
    echo ""
    usage
    exit 1;
fi

if [ "${filename}x" = "x" ]; then \
    echo "ERROR: Must specify filename"
    echo ""
    usage
    exit 1;
fi

if [ ${use_crypto} -eq 1 -a "${key}x" = "x" -a 0${prompt_key} -eq 0 ]; then \
    echo "ERROR: Crypto desired, but no key supplied or requested to prompt for."
    exit 1
fi

if [ 0${prompt_key} -eq 1 ]; then \
    read_key
fi

outdir=`dirname ${filename}`
if [ ! -d "${outdir}" ]; then \
    echo "ERROR: Output directory does not exist: ${outdir}"
    exit 1
fi

# Make sure we clean up any stuff we create from here on during error conditions
trap onexit ERR

tempfile=$(tempfile -d ${outdir}) || ( echo "ERROR: couldn't create temporary file in ${outdir}"; exit 1 )

block_count=`du -s --apparent-size --block-size=512 ${directory} | awk '{ print $1; }'`
if [ $? -ne 0 ]; then \
    echo "ERROR: Couldn't read size of input directory ${directory}"
    exit 1
fi

echo "Creating temporary file..."
${DDBIN} if=/dev/zero of=${tempfile} bs=${BLOCK_SIZE} count=$((${block_count} + ${SLOP})) > /dev/null 2>&1
if [ $? -ne 0 ]; then \
    echo "ERROR: creating temporary file: $?"
fi

loop_dev=$(${LOSETUPBIN} -f) || ( echo "ERROR: losetup wouldn't tell us the next unused device"; exit 1 )

${LOSETUPBIN} ${loop_dev} ${tempfile} || ( echo "ERROR: couldn't create loopback device"; exit 1 )

if [ ${use_crypto} -eq 1 ]; then \
    eval `${PBKDF2GEN} ${key}`
    unique_dm_name=`basename ${tempfile}`
    echo "0 `blockdev --getsize ${loop_dev}` crypt ${CRYPTO} ${key} 0 ${loop_dev} 0" | dmsetup create ${unique_dm_name}
    old_loop_dev=${loop_dev}
    loop_dev=/dev/mapper/${unique_dm_name}
fi

#
# Create the filesystem
#
echo ""
${MKFSBIN} -I ${loop_dev}
echo ""

#
# Make the temporary mount point and mount it
#
temp_mount="${MOUNTDIR}/${RANDOM}"
mkdir ${temp_mount}
${MOUNTBIN} -t ${FS} -o loop ${loop_dev} ${temp_mount}

#
# rsync the files!
#
echo "Copying files:"
${RSYNCBIN} -av --no-owner --no-group ${directory}/ ${temp_mount}/
echo ""

echo "Successfully created \`${filename}'"

if [ ${use_crypto} -eq 1 ]; then \
    echo "salt for use with obbtool is:"
    echo "${salt}"
fi

#
# Undo all the temporaries
#
umount ${temp_mount}
rmdir ${temp_mount}
if [ ${use_crypto} -eq 1 ]; then \
    dmsetup remove -f ${loop_dev}
    ${LOSETUPBIN} -d ${old_loop_dev}
else \
    ${LOSETUPBIN} -d ${loop_dev}
fi
mv ${tempfile} ${filename}

trap - ERR

exit 0
