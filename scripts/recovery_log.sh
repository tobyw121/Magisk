#!/system/bin/sh
# Runs with the private BusyBox outside TMPDIR, which the installer may delete.
BOX=$MAGISK_RECOVERY_BUSYBOX
WORK=${MAGISK_RECOVERY_LOG%/*}
ZIPFILE=$3

(
  echo "=== Magisk recovery installer ==="
  "$BOX" date -u '+UTC %Y-%m-%d %H:%M:%S'
  echo "[DEBUG] archive=$ZIPFILE work=$INSTALLER"
  "$BOX" mkdir -p "$INSTALLER"
  if "$BOX" unzip -o "$ZIPFILE" 'assets/*' 'lib/*' 'META-INF/com/google/*' \
      -x 'lib/*/libbusybox.so' -d "$INSTALLER"; then
    case "$ZIPFILE" in
      *uninstall*) SCRIPT=$INSTALLER/assets/uninstaller.sh ;;
      *) SCRIPT=$INSTALLER/META-INF/com/google/android/updater-script ;;
    esac
    "$BOX" sh "$SCRIPT" "$@"
    RESULT=$?
  else
    echo "! Archive extraction failed; installer not started"
    RESULT=1
  fi
  echo "[DEBUG] Installer exit status=$RESULT"
  echo "$RESULT" > "$WORK/result"
  exit "$RESULT"
) 2>&1 | "$BOX" tee -a "$MAGISK_RECOVERY_LOG"
TEE_RESULT=$?
RESULT=$("$BOX" cat "$WORK/result" 2>/dev/null)
[ -n "$RESULT" ] || RESULT=1
[ "$RESULT" != 0 ] || RESULT=$TEE_RESULT

# /tmp always retains the transcript for recovery/ADB pull. Durable copies are
# attempted only on real mounted filesystems, not recovery's empty RAM paths.
log_note() {
  printf '%s\n' "$1" | "$BOX" tee -a "$MAGISK_RECOVERY_LOG"
}
log_note "[DEBUG] Complete recovery transcript: /tmp/magisk-recovery.log"
SAVED_DESTS=
for PARTITION in data cache; do
  if "$BOX" awk -v path="/$PARTITION" '
    $2 == path && $3 != "tmpfs" && $3 != "rootfs" { found = 1 }
    END { exit !found }
  ' /proc/mounts; then
    case "$PARTITION" in
      data)
        DEST=/data/adb/magisk/recovery.log
        [ -d /data/adb/magisk ] && [ -w /data/adb/magisk ] || DEST=/data/magisk/recovery.log
        ;;
      cache) DEST=/cache/magisk-recovery.log ;;
    esac
    if [ -d "${DEST%/*}" ] && [ -w "${DEST%/*}" ]; then
      log_note "[DEBUG] Saving recovery transcript: $DEST"
      if "$BOX" cp -f "$MAGISK_RECOVERY_LOG" "$DEST"; then
        "$BOX" chmod 600 "$DEST"
        SAVED_DESTS="$SAVED_DESTS $DEST"
      else
        log_note "! Unable to save recovery transcript: $DEST"
      fi
    else
      log_note "! Recovery transcript destination unavailable: $DEST"
    fi
  else
    log_note "! /$PARTITION not mounted on persistent storage; transcript copy skipped"
  fi
done
# Recopy after all destination diagnostics so each saved transcript is complete.
for DEST in $SAVED_DESTS; do
  "$BOX" cp -f "$MAGISK_RECOVERY_LOG" "$DEST" || log_note "! Final transcript copy failed: $DEST"
done
"$BOX" cp -f "$MAGISK_RECOVERY_LOG" /tmp/magisk-recovery.log
"$BOX" sync
exit "$RESULT"
