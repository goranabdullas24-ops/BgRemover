; BgRemover — Windows installer (Inno Setup)
#define AppVer GetEnv("APP_VERSION")

[Setup]
AppId={{6E2B1C8A-4F7D-4B8E-9C61-B6A9D2E1F0A1}
AppName=BgRemover
AppVersion={#AppVer}
AppPublisher=BgRemover
DefaultDirName={autopf}\BgRemover
DefaultGroupName=BgRemover
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=dist
OutputBaseFilename=BgRemover-Setup-{#AppVer}
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\BgRemover.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "dist\onedir\BgRemover\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion createallsubdirs

[Icons]
Name: "{group}\BgRemover"; Filename: "{app}\BgRemover.exe"
Name: "{group}\Uninstall BgRemover"; Filename: "{uninstallexe}"
Name: "{autodesktop}\BgRemover"; Filename: "{app}\BgRemover.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\BgRemover.exe"; Description: "Launch BgRemover"; Flags: nowait postinstall skipifsilent
