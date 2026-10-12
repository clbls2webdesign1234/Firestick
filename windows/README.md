# Disc Shelf for Windows

Plays DVD and Blu-ray discs stored as `.iso` files or as copied disc folders
(`VIDEO_TS` or `BDMV`) on a Windows 10 or 11 PC, with the disc's menus, a choice of
soundtrack, and chapter skipping. It uses the same VLC engine as the Fire TV app.
Nothing needs installing: the engine and Java come inside the folder.

## Install

1. Download `DiscShelf-Windows.zip` from
   `https://github.com/clbls2webdesign1234/Firestick/releases/download/windows/DiscShelf-Windows.zip`
2. Right-click it, choose **Extract All**, and pick a place such as `C:\Programs`.
3. Open the `DiscShelf` folder and run **DiscShelf.exe**. If Windows SmartScreen
   warns about an unknown app, choose **More info**, then **Run anyway**.
   You can right-click DiscShelf.exe and choose **Pin to Start**.

## First run

1. Press **Add folder…** and choose where your discs are: a drive, a USB disk, or a
   network share. For a share, type its address into the folder box, for example
   `\\192.168.1.4\Movies`.
2. Disc Shelf finds every `.iso` file and every disc folder in it and the folders below.
3. Double-click a disc, or select it and press Enter.

**Network shares need Windows to know the login.** Open the share once in File Explorer,
enter the user name and password, and tick **Remember my credentials**. Unlike on the
Fire TV, disc folders on a share should play with their menus too.

## While a disc plays

| Key | Does |
|---|---|
| Space | Pause and resume |
| Left / Right | Back 10 s / forward 30 s. In a disc menu, move left and right |
| Up / Down / Enter | Move and choose in a disc menu |
| Page Up / Page Down | Previous / next chapter |
| M | Disc menu |
| A / S | Soundtrack / subtitles list |
| C / T | Chapters / titles list |
| F or F11, double-click | Full screen |
| Esc | Leave full screen, then stop |
| Right-click | All of the above as a menu |

The control bar has the same choices as buttons. In full screen it hides itself;
move the mouse to bring it back.

## Sound

Under **Settings**, Sound output:

| Mode | Use it when |
|---|---|
| Bitstream over HDMI | The PC is connected to an AV receiver by HDMI. DTS, Dolby Digital, Dolby TrueHD and DTS-HD Master Audio go to the receiver untouched, and it shows the format. |
| Bitstream over optical/S/PDIF | An optical cable to the receiver. Dolby Digital and core DTS only; that cable cannot carry the lossless formats. |
| Decoded by the PC | Speakers on the PC itself, or a receiver without the decoder. Set the channel layout (5.1, 7.1) in Windows Sound settings, Speaker setup. |

For HDMI bitstream, choose the receiver as the output device in Windows Sound settings.
A new setting applies from the next disc you start.

"7.2" is a speaker layout, not a disc format: discs carry up to 7.1, and the receiver
makes the second subwoofer feed.

## Limits worth knowing

- **Copy-protected Blu-ray images will not play.** The engine has no AACS support.
  Images made without protection play, and DVD images normally play as they are.
- **Blu-ray discs with Java menus** (most studio releases) start on the main title
  instead of the menu. Titles, chapters, soundtracks and subtitles are all available
  from the control bar.
- Picture is shown at the disc's own resolution (720×480/576 for DVD, 1920×1080 for
  Blu-ray), scaled to fill your screen.
- This app has been compiled, but it **has not been run on a Windows PC yet**.
  Expect rough edges, and say what you find.

## Where things are kept

Your sources, the disc list and resume points are in `%APPDATA%\DiscShelf\library.json`.
Settings are in the registry under the current user.

Disc Shelf for Windows uses vlcj (GPL 3) and libVLC (LGPL 2.1).
