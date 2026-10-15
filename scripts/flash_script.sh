#MAGISK
############################################
# Magisk Flash Script (updater-script)
############################################

##############
# Preparation
##############

# Default permissions
umask 022

OUTFD=$2
COMMONDIR=$INSTALLER/assets

if [ ! -f $COMMONDIR/util_functions.sh ]; then
  echo "! Unable to extract zip file!"
  exit 1
fi

# Load utility functions
. $COMMONDIR/util_functions.sh

setup_flashable

############
# Detection
############

if echo $MAGISK_VER | grep -q '\.'; then
  PRETTY_VER=$MAGISK_VER
else
  PRETTY_VER="$MAGISK_VER($MAGISK_VER_CODE)"
fi
print_title "Magisk $PRETTY_VER Installer"

INSTALL_STAGE=detection
is_mounted /data || mount /data || is_mounted /cache || mount /cache
mount_partitions
check_data
RECOVERYINSTALL=false
CLEANINSTALL=false
if ! $BOOTMODE; then
  RECOVERYINSTALL=true
  if [ "$DATA_DE" = true ]; then
    CLEANINSTALL=true
    mount_recovery_magisk_storage || abort "! Cannot access Magisk preinit storage"
    check_recovery_magisk_cleanup || abort "! Cannot safely remove old Magisk data"
  else
    ui_print "! /data/adb unavailable: continuing boot-image installation"
    ui_print "! Old modules and settings cannot be removed; clean reset skipped"
  fi
  # Ignore saved flags from the previous installation; detect this device anew.
  unset KEEPVERITY KEEPFORCEENCRYPT RECOVERYMODE VENDORBOOT
  get_flags clean
else
  get_flags
fi
find_boot_image
$BOOTMODE || recovery_debug_state

[ -z $BOOTIMAGE ] && abort "! Unable to detect target image"
ui_print "- Target image: $BOOTIMAGE"

# Detect version and architecture
api_level_arch_detect

[ $API -lt 23 ] && abort "! Magisk only support Android 6.0 and above"

ui_print "- Device platform: $ABI"

BINDIR=$INSTALLER/lib/$ABI
cd $BINDIR
for file in lib*.so; do mv "$file" "${file:3:${#file}-6}"; done
cd /
cp -af $INSTALLER/lib/$ABI32/libmagisk.so $BINDIR/magisk32 2>/dev/null

# Check if system root is installed and remove
$BOOTMODE || remove_system_su

##############
# Environment
##############

INSTALL_STAGE=extract
ui_print "- Constructing environment"

# Recovery prepares the image in temporary storage before deleting old data.
TARGETBIN=$MAGISKBIN
if $RECOVERYINSTALL; then
  $CLEANINSTALL && ui_print "! Clean installation deletes all Magisk modules, settings and startup scripts"
  MAGISKBIN=$TMPDIR/magisk-clean
fi

# Copy required files
rm -rf "$MAGISKBIN" || abort "! Cannot clear installation directory"
mkdir -p "$MAGISKBIN" || abort "! Cannot create installation directory"
cp -af "$BINDIR/." "$COMMONDIR/." "$BBBIN" "$MAGISKBIN" || abort "! Cannot extract installation files"

# Remove files only used by the Magisk app
rm -f $MAGISKBIN/bootctl $MAGISKBIN/main.jar \
  $MAGISKBIN/module_installer.sh $MAGISKBIN/uninstaller.sh

chmod -R 755 "$MAGISKBIN" || abort "! Cannot set installation permissions"

##################
# Image Patching
##################

if $RECOVERYINSTALL; then
  INSTALL_STAGE=patch-and-verify
  prepare_magisk_image
  cd / || abort "! Cannot leave temporary installation directory"
  if $CLEANINSTALL; then
    INSTALL_STAGE=clean-old-data
    clean_recovery_magisk || abort "! Old Magisk data could not be fully removed"
  fi
  INSTALL_STAGE=store-environment
  PERSIST_ENV=true
  if [ "$DATA" != true ] && ! recovery_has_persistent_cache; then
    PERSIST_ENV=false
  fi
  if $PERSIST_ENV && rm -rf "$TARGETBIN" && mkdir -p "$TARGETBIN" && cp -af "$MAGISKBIN/." "$TARGETBIN/"; then
    MAGISKBIN=$TARGETBIN
    chmod -R 755 "$MAGISKBIN" || abort "! Cannot set fresh installation permissions"
    ui_print "- Installation files stored in $MAGISKBIN"
  elif $CLEANINSTALL; then
    abort "! Cannot copy fresh Magisk files"
  else
    ui_print "! No writable installation storage; flashing from recovery RAM"
    ui_print "! Magisk environment may need repair after boot; root is not guaranteed"
  fi
  INSTALL_STAGE=flash-and-readback
  flash_magisk_image
else
  install_magisk
fi

INSTALL_STAGE=addon-d
# addon.d
if [ -d /system/addon.d ]; then
  ui_print "- Adding addon.d survival script"
  blockdev --setrw /dev/block/mapper/system$SLOT 2>/dev/null
  mount -o rw,remount /system || mount -o rw,remount /
  ADDOND=/system/addon.d/99-magisk.sh
  cp -af $COMMONDIR/addon.d.sh $ADDOND
  chmod 755 $ADDOND
fi

INSTALL_STAGE=finish
# Cleanups
$BOOTMODE || recovery_cleanup
rm -rf $TMPDIR

ui_print "- Done"
exit 0
