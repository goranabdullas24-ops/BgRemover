; BgRemover — small online installer (downloads the program during install)
#define AppVer GetEnv("APP_VERSION")
#define PayloadUrl GetEnv("PAYLOAD_URL")

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

[Icons]
Name: "{group}\BgRemover"; Filename: "{app}\BgRemover.exe"
Name: "{group}\Uninstall BgRemover"; Filename: "{uninstallexe}"
Name: "{autodesktop}\BgRemover"; Filename: "{app}\BgRemover.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\BgRemover.exe"; Description: "Launch BgRemover"; Flags: nowait postinstall skipifsilent

[Code]
var
  DownloadPage: TDownloadWizardPage;
  Downloaded: Boolean;

procedure InitializeWizard;
begin
  DownloadPage := CreateDownloadPage('Downloading BgRemover', 'Downloading the program (about 110 MB)...', nil);
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
