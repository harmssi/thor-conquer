#!/bin/bash
# Copy the game data from your own C&C Remastered Collection (Steam install on the device, e.g. via
# DroiDeck) into Thor Conquer's data folder over adb. The Steam folder is only read, never modified.
#
#   thor/copy-data.sh            # Red Alert + Tiberian Dawn
#   thor/copy-data.sh ra|td      # one game only
#   STEAM_DIR=/path/to/CnCRemastered thor/copy-data.sh
#
# Resulting layout (disc-specific files in sub folders, like the engine's local "CD" folders):
#   redalert/       REDALERT.MIX EXPAND.MIX EXPAND2.MIX HIRES1.MIX LORES1.MIX
#   redalert/allied/MAIN.MIX          (RED_ALERT/CD1 - contains the Allied and the Soviet campaign)
#   redalert/counterstrike/MAIN.MIX   (RED_ALERT/COUNTERSTRIKE)
#   redalert/aftermath/MAIN.MIX       (RED_ALERT/AFTERMATH)
#   tiberiandawn/   CONQUER.MIX CCLOCAL.MIX ... (TIBERIAN_DAWN/CD1, shared files)
#   tiberiandawn/gdi/GENERAL.MIX MOVIES.MIX   (CD1)
#   tiberiandawn/nod/GENERAL.MIX MOVIES.MIX   (CD2)
set -euo pipefail
: # adb uses ANDROID_SERIAL if set (several devices)
STEAMAPPS=/storage/7BD6-7817/Android/data/com.droiddeck.launcher/files/steam/steamapps
STEAM_DIR=${STEAM_DIR:-$STEAMAPPS/common/CnCRemastered}
DEST=/sdcard/Android/data/de.harmssi.thorconquer/files
WHAT=${1:-all}

SRC=$STEAM_DIR/Data/CNCDATA
if ! adb shell "[ -d $SRC ]"; then
    echo "Not found: $SRC"
    echo "(Download not finished yet? Do not copy from steamapps/downloading while Steam is still writing.)"
    exit 1
fi

copy() { # source_dir target_dir file...
    local from=$1 to=$2
    shift 2
    adb shell "mkdir -p $to"
    for f in "$@"; do
        local size
        size=$(adb shell "stat -c %s $from/$f 2>/dev/null || echo -1" | tr -d '\r')
        if [ "$size" -le 0 ]; then
            echo "  MISSING/EMPTY: $from/$f"
            continue
        fi
        local have
        have=$(adb shell "stat -c %s $to/$f 2>/dev/null || echo -1" | tr -d '\r')
        if [ "$have" = "$size" ]; then
            echo "  $f (already there)"
            continue
        fi
        echo "  $f ($size bytes)"
        adb shell "cp $from/$f $to/$f && chmod 666 $to/$f"
    done
}

if [ "$WHAT" = all ] || [ "$WHAT" = ra ]; then
    R=$SRC/RED_ALERT
    echo "Red Alert -> $DEST/redalert"
    copy $R/CD1 $DEST/redalert REDALERT.MIX EXPAND.MIX EXPAND2.MIX HIRES1.MIX LORES1.MIX
    copy $R/CD1 $DEST/redalert/allied MAIN.MIX
    copy $R/COUNTERSTRIKE $DEST/redalert/counterstrike MAIN.MIX
    copy $R/AFTERMATH $DEST/redalert/aftermath MAIN.MIX
fi
if [ "$WHAT" = all ] || [ "$WHAT" = td ]; then
    T=$SRC/TIBERIAN_DAWN
    echo "Tiberian Dawn -> $DEST/tiberiandawn"
    copy $T/CD1 $DEST/tiberiandawn CONQUER.MIX CCLOCAL.MIX LOCAL.MIX TEMPERAT.MIX DESERT.MIX WINTER.MIX \
        SOUNDS.MIX SPEECH.MIX SCORES.MIX TRANSIT.MIX UPDATE.MIX UPDATEC.MIX UPDATA.MIX TEMPICNH.MIX \
        DESEICNH.MIX WINTICNH.MIX ZOUNDS.MIX SC-000.MIX SC-001.MIX
    copy $T/CD1 $DEST/tiberiandawn/gdi GENERAL.MIX MOVIES.MIX
    copy $T/CD2 $DEST/tiberiandawn/nod GENERAL.MIX MOVIES.MIX
fi
echo "Done."
