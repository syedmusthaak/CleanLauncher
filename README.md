# Clean Launcher (Android TV)
Build: open in Android Studio -> Run, or `gradle assembleDebug`, then `adb install app-debug.apk`.
Set as default home (if the TV doesn't prompt):
  adb shell cmd package set-home-activity com.cleanlauncher/.MainActivity
Remove bloatware (reversible):
  adb shell pm uninstall -k --user 0 <package>
Boot input: highlight an HDMI/AV tile, hold OK to toggle auto-start. 5-second cancel countdown on boot.
