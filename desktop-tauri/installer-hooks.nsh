; SPDX-FileCopyrightText: 2026 Brian Pilnick
; SPDX-License-Identifier: GPL-3.0-or-later
; Additional WebView2 permission: see LICENSING.md at the repository root.

; The application retains its original startup-registration name for compatibility.
; Tauri's default uninstaller removes only an entry matching the new product name.
!macro NSIS_HOOK_POSTUNINSTALL
  ${If} $UpdateMode <> 1
    ReadRegStr $R0 HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "Caltrain Wi-Fi"
    ${If} $R0 == "$INSTDIR\${MAINBINARYNAME}.exe --autostart"
    ${OrIf} $R0 == "$\"$INSTDIR\${MAINBINARYNAME}.exe$\" --autostart"
      DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "Caltrain Wi-Fi"
    ${EndIf}
  ${EndIf}
!macroend
