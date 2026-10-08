; BgRemover — small online installer (downloads the program during install)
#define AppVer GetEnv("APP_VERSION")
#define PayloadUrl GetEnv("PAYLOAD_URL")

[Setup]
AppId={{6E2B1C8A-4F7D-4B8E-9C61-B6A9D2E1F0A1}
AppName=SG search
AppVersion={#AppVer}
AppPublisher=SG search
DefaultDirName={autopf}\BgRemover
DefaultGroupName=SG search
DisableProgramGroupPage=yes
; وەک بەڕێوەبەر: وەشانی کۆن لە هەمان شوێن (Program Files) دەگۆڕدرێت و شۆرتکاتەکان نوێ دەبنەوە
PrivilegesRequired=admin
UsePreviousAppDir=yes
CloseApplications=force
RestartApplications=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=dist
OutputBaseFilename=BgRemover-Setup-Online-{#AppVer}
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\BgRemover.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "{tmp}\BgRemover.exe"; DestDir: "{app}"; Flags: external ignoreversion

[InstallDelete]
; شۆرتکاتی ناوە کۆنەکە (BgRemover) لادەبرێت — هی هەموو بەکارهێنەران و هی خۆت
Type: files; Name: "{commondesktop}\BgRemover.lnk"
Type: files; Name: "{userdesktop}\BgRemover.lnk"
Type: files; Name: "{commonprograms}\BgRemover\BgRemover.lnk"
Type: files; Name: "{userprograms}\BgRemover\BgRemover.lnk"
Type: files; Name: "{autodesktop}\BgRemover.lnk"
Type: files; Name: "{group}\BgRemover.lnk"
Type: files; Name: "{group}\Uninstall BgRemover.lnk"

[Icons]
Name: "{group}\SG search"; Filename: "{app}\BgRemover.exe"
Name: "{group}\Uninstall SG search"; Filename: "{uninstallexe}"
Name: "{autodesktop}\SG search"; Filename: "{app}\BgRemover.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\BgRemover.exe"; Description: "Launch SG search"; Flags: nowait postinstall skipifsilent

[Code]
var
  DownloadPage: TDownloadWizardPage;
  Downloaded: Boolean;

procedure RemoveOldUserInstall;
var
  s: String;
  rc: Integer;
begin
  { وەشانی کۆنی «تەنها بۆ خۆم» (LOCALAPPDATA) لادەبرێت بۆ ئەوەی تەنها یەک وەشان بمێنێتەوە }
  if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{6E2B1C8A-4F7D-4B8E-9C61-B6A9D2E1F0A1}_is1', 'UninstallString', s) then
    Exec(RemoveQuotes(s), '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART', '', SW_HIDE, ewWaitUntilTerminated, rc);
end;

procedure InitializeWizard;
begin
  DownloadPage := CreateDownloadPage('Downloading SG search', 'Downloading the program (about 110 MB)...', nil);
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = wpReady then begin
    DownloadPage.Clear;
    DownloadPage.Add('{#PayloadUrl}', 'BgRemover.exe', '');
    DownloadPage.Show;
    try
      try
        DownloadPage.Download;
        Downloaded := True;
      except
        if DownloadPage.AbortedByUser then
          Log('Aborted by user.')
        else
          SuppressibleMsgBox('Download failed (check the internet connection):'#13#10 + AddPeriod(GetExceptionMessage), mbCriticalError, MB_OK, IDOK);
        Result := False;
      end;
    finally
      DownloadPage.Hide;
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  RemoveOldUserInstall;
  Result := '';
  if not Downloaded then begin
    { silent install: no wizard pages }
    try
      DownloadTemporaryFile('{#PayloadUrl}', 'BgRemover.exe', '', nil);
      Downloaded := True;
    except
      Result := 'Download failed: ' + GetExceptionMessage;
    end;
  end;
end;
