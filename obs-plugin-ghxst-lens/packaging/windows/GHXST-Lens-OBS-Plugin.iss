; GHXST Lens OBS Plugin Installer
; Build with Inno Setup Compiler.

#define MyAppName "GHXST Lens OBS Plugin"
#define MyAppVersion "0.5.0-beta.1"
#define MyAppVersionNumeric "0.5.0.1"
#define MyAppPublisher "GHXST"
#define MyAppURL "https://ghx.st"
#define MyAppDllName "ghxst-lens.dll"

[Setup]
AppId={{B6CE93A4-936E-4E2D-9DD2-7BB93D0E0F88}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
VersionInfoVersion={#MyAppVersionNumeric}
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription=GHXST Lens OBS Plugin Beta Installer
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#MyAppVersionNumeric}
DefaultDirName={autopf}\obs-studio
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=GHXST-Lens-OBS-Plugin-0.5.0-beta.1-Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64
UninstallDisplayName={#MyAppName}
SetupIconFile=assets\ghxst-lens.ico
WizardImageFile=assets\wizard-side.bmp
WizardSmallImageFile=assets\wizard-small.bmp
SetupLogging=yes
DisableWelcomePage=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Messages]
WelcomeLabel1=Welcome to the GHXST Lens OBS Plugin Setup Wizard
WelcomeLabel2=This will install GHXST Lens Beta for OBS Studio.%n%nGHXST Lens turns your Android phone into a low-latency OBS camera source.

[Files]
Source: "..\..\build\RelWithDebInfo\ghxst-lens.dll"; DestDir: "{app}\obs-plugins\64bit"; Flags: ignoreversion
Source: "..\..\data\locale\*"; DestDir: "{app}\data\obs-plugins\ghxst-lens\locale"; Flags: ignoreversion recursesubdirs createallsubdirs

[Code]
function IsObsRunning(): Boolean;
var
  ResultCode: Integer;
  TempFile: String;
  Command: String;
  Output: AnsiString;
begin
  Result := False;
  TempFile := ExpandConstant('{tmp}\ghxst_lens_tasklist.txt');

  Command := '/C tasklist /FI "IMAGENAME eq obs64.exe" > "' + TempFile + '"';

  if Exec(ExpandConstant('{cmd}'), Command, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
  begin
    if LoadStringFromFile(TempFile, Output) then
    begin
      Result := Pos('obs64.exe', Lowercase(String(Output))) > 0;
    end;
  end;

  if FileExists(TempFile) then
    DeleteFile(TempFile);
end;

function InitializeSetup(): Boolean;
begin
  Result := True;

  if IsObsRunning() then
  begin
    MsgBox('OBS Studio is currently running. Please close OBS before installing GHXST Lens.', mbError, MB_OK);
    Result := False;
  end;
end;

[Run]
Filename: "{app}\bin\64bit\obs64.exe"; Description: "Launch OBS Studio"; Flags: nowait postinstall skipifsilent unchecked; Check: FileExists(ExpandConstant('{app}\bin\64bit\obs64.exe'))

[UninstallDelete]
Type: files; Name: "{app}\obs-plugins\64bit\ghxst-lens.dll"
Type: filesandordirs; Name: "{app}\data\obs-plugins\ghxst-lens"
