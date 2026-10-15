#!/sbin/sh

# Capture bootstrap failures before the bundled BusyBox is available.
BOOTSTRAPLOG=/tmp/magisk-recovery.log
printf '%s\n' '=== Recovery bootstrap ===' > "$BOOTSTRAPLOG"
bootstrap_fail() {
  printf '! Bootstrap failed: %s\n' "$1" >> "$BOOTSTRAPLOG"
  cat "$BOOTSTRAPLOG" >&2
  exit 1
}
TMPDIR=/dev/tmp
rm -rf "$TMPDIR" >> "$BOOTSTRAPLOG" 2>&1 || bootstrap_fail "clear work directory"
mkdir -p "$TMPDIR" >> "$BOOTSTRAPLOG" 2>&1 || bootstrap_fail "create work directory"

export BBBIN=$TMPDIR/busybox
for arch in "x86_64" "x86" "arm64-v8a" "armeabi-v7a"; do
  unzip -o "$3" "lib/$arch/libbusybox.so" -d $TMPDIR >> "$BOOTSTRAPLOG" 2>&1
  libpath="$TMPDIR/lib/$arch/libbusybox.so"
  chmod 755 "$libpath" >> "$BOOTSTRAPLOG" 2>&1
  printf '[DEBUG] Trying BusyBox ABI=%s\n' "$arch" >> "$BOOTSTRAPLOG"
  if [ -x "$libpath" ] && "$libpath" true >> "$BOOTSTRAPLOG" 2>&1; then
    mv -f "$libpath" "$BBBIN" >> "$BOOTSTRAPLOG" 2>&1
    break
  fi
done
if [ ! -x "$BBBIN" ]; then
  cat "$BOOTSTRAPLOG" >&2
  echo "! Cannot run bundled BusyBox; see $BOOTSTRAPLOG" >&2
  exit 1
fi
$BBBIN rm -rf "$TMPDIR/lib" >> "$BOOTSTRAPLOG" 2>&1 || bootstrap_fail "clear ABI probes"

export INSTALLER=$TMPDIR/install
export ASH_STANDALONE=1
LOGDIR=$($BBBIN mktemp -d /tmp/magisk-recovery.XXXXXX 2>> "$BOOTSTRAPLOG") || bootstrap_fail "create log directory"
export MAGISK_RECOVERY_BUSYBOX=$LOGDIR/busybox
export MAGISK_RECOVERY_LOG=$LOGDIR/recovery.log
$BBBIN cp "$BBBIN" "$MAGISK_RECOVERY_BUSYBOX" >> "$BOOTSTRAPLOG" 2>&1 || bootstrap_fail "preserve BusyBox"
$BBBIN cp "$BOOTSTRAPLOG" "$MAGISK_RECOVERY_LOG" 2>> "$BOOTSTRAPLOG" || bootstrap_fail "initialize transcript"
if ! $BBBIN unzip -o "$3" assets/recovery_log.sh -d "$LOGDIR" >> "$MAGISK_RECOVERY_LOG" 2>&1; then
  $BBBIN cp "$MAGISK_RECOVERY_LOG" "$BOOTSTRAPLOG"
  $BBBIN cat "$BOOTSTRAPLOG" >&2
  exit 1
fi
exec "$MAGISK_RECOVERY_BUSYBOX" sh "$LOGDIR/assets/recovery_log.sh" "$@"
