# Disc Shelf

A Fire TV app that plays DVD and Blu-ray discs, stored as disc images (`.iso` files)
or as copied disc folders (`VIDEO_TS` or `BDMV`), from a NAS, a PC share, a web server
or a USB drive, with the disc's own menus, a choice of
soundtrack, and chapter skipping from the remote.

Playback uses libVLC, the engine behind VLC.

## What it does

- **Shelf.** Every `.iso` found in your sources, plus every disc folder on USB or local
  storage (a folder holding `VIDEO_TS` or `BDMV`), as a grid you move around with the remote.
  It remembers where you stopped each disc.
- **Disc menus.** DVD menus work with the arrows and Select, as on a DVD player.
  Blu-ray menus work on discs that use the simpler (HDMV) menu style.
- **Sound.** Pick any soundtrack on the disc. Dolby Digital and DTS can be handed to
  your receiver untouched (bitstream), decoded to multichannel PCM, or mixed to stereo.
- **Chapters and titles.** Skip with the remote, or jump to any chapter or title from a list.
- **Fire TV extras.** Alexa voice commands ("pause", "fast forward two minutes", "next"),
  TV refresh rate matched to the disc, and a banner on the Fire TV home screen.

## Limits worth knowing first

- **Copy-protected Blu-ray images will not play.** The engine has no AACS support.
  Images made without protection play; DVD images normally play as they are.
- **Blu-ray discs with Java menus** (most studio releases) start on the main title
  instead of the menu. Titles, chapters, audio and subtitles are still all available
  from the app's own panel (Menu button).
- **"7.2" is a speaker layout, not a disc format.** Discs carry up to 7.1. Your receiver
  creates the second subwoofer feed. Choose the 7.1 or 5.1 track and it does the rest.
- **DTS bitstream depends on the stick.** Amazon lists DTS passthrough for the
  Fire TV Stick 4K (2nd gen) and 4K Max (2nd gen). On models without it, the app decodes
  DTS and sends multichannel PCM instead.
- **Disc folders play from USB or local storage only.** The engine opens a copied
  `VIDEO_TS` or `BDMV` folder as a disc, menus included, only when the folder is on the
  stick or a drive plugged into it. On a network share, store discs as `.iso`; the scan
  reports any disc folders it skipped there.
- **USB drives formatted FAT32 cannot hold files over 4 GB,** which rules out most disc
  images. A network share is usually the practical source.
- This project was compiled and checked against the Android and libVLC programming
  interfaces, but it has **not been run on a Fire TV yet**. Expect to find rough edges.

## Getting it onto the stick

### 1. Let GitHub build the APK

1. Create a new repository on GitHub. Make it **public** if you want the simple
   install link in step 2.
2. Put this whole folder in it, including the hidden `.github` folder:

   ```
   git init
   git add .
   git commit -m "Disc Shelf"
   git branch -M main
   git remote add origin https://github.com/YOUR-NAME/YOUR-REPO.git
   git push -u origin main
   ```

3. Open the repository's **Actions** tab and wait for "Build APK" to finish
   (about five minutes). The APK then appears under **Releases** as `DiscShelf.apk`.

Every later push builds a new APK that installs over the old one and keeps your settings.

### 2. Install it

1. On the Fire TV: Settings, My Fire TV, Developer Options, and allow apps from
   unknown sources for the **Downloader** app (install Downloader from the Appstore first).
   If Developer Options is missing, open About and press Select on the device name
   seven times.
2. In Downloader, enter:

   ```
   https://github.com/YOUR-NAME/YOUR-REPO/releases/latest/download/DiscShelf.apk
   ```

3. Choose Install. Disc Shelf then appears with your other apps.

With a computer on the same network you can use `adb install -r DiscShelf.apk` instead.

## First run

1. Open **Sources** and add where your images live:
   - **Network share**: type the server and folder, for example `192.168.1.20/Films/Discs`,
     plus the user name and password if the share needs them.
   - **Web address**: a folder listing such as `http://192.168.1.20:8080/discs/`,
     or a list file (see below).
   - **USB drives** are searched automatically. Allow the storage permission when asked.
2. Go back. The shelf fills in. Press **Rescan** after adding files.
3. On the Fire TV itself, set Settings, Display & Sounds, Audio, Surround Sound to
   **Best Available** so Dolby and DTS can reach your receiver.

### List files

A plain text file, one disc per line. Any of these forms work, and can be mixed:

```
# lines starting with # are ignored
smb://nas/films/Alien.iso
Blade Runner (Final Cut) | smb://nas/films/BR_FINAL.iso
\\nas\films\Heat 1995.iso
box-sets/Jaws.iso
```

A line can also name a disc folder on USB storage, for example
`/storage/1234-ABCD/Discs/Alien` (the folder that holds `VIDEO_TS` or `BDMV`).

Paths without a server are taken relative to the list file's own location.
M3U playlists (`#EXTINF:-1,Title` followed by the address) are read too.

## Remote control

| Where | Button | Does |
|---|---|---|
| Disc menu | Arrows, Select | Move and choose |
| Film | Left / Right | Back 10 s / forward 30 s (hold to go faster) |
| Film | Select | Pause and show the control bar |
| Film | Rewind / Fast-forward | Previous / next chapter (can be changed to seek in Settings) |
| Anywhere | Play/Pause | Pause or resume |
| Anywhere | Menu (three lines) | Options panel: disc menu, audio, subtitles, chapters, titles, sound output, audio delay, picture size |
| Film | Back | Stop and return to the shelf |

On the shelf, the Menu button on a disc offers "play from the beginning" and
"forget where I stopped".

## Sound output modes

| Mode | Use it when |
|---|---|
| Bitstream, automatic | You have a receiver or soundbar. Sends whatever it reports it can decode. |
| Bitstream, forced | The receiver can decode a format but the Fire TV does not report it. Tick the formats in Settings. |
| Multichannel PCM | The receiver has no decoder for the track (or the stick cannot pass DTS). |
| Stereo | TV speakers. |

## If something goes wrong

- **Build fails on GitHub.** Open the failed run on the Actions tab; the error is near the
  end of the "Build the app" step. The most likely cause is a library version:
  the engine version is one line in `app/build.gradle` (`libvlc-all`).
- **A share shows no discs.** Check the address and login under Sources. Try the server's
  IP address rather than its name.
- **DVD shows comb-like lines on movement.** Settings, DVD picture processing, Software.
- **Stutter from a share.** Settings, Network buffer, raise it. Wired or 5 GHz Wi-Fi helps
  more than anything for Blu-ray images.
- **Soundtrack list shows the codec but no channel count.** Discs only reveal the channel
  layout of a track once it is playing.

## Project layout

```
app/src/main/java/com/discshelf/tv/
  MainActivity      the shelf
  PlayerActivity    playback, remote keys, options panel, sound output, Alexa controls
  Scanner           finds .iso files in each source
  ListFile          reads list files
  SourcesActivity   add and edit sources
  SettingsActivity  preferences
  Store, Prefs      saved data
.github/workflows/build.yml   the GitHub build
signing/discshelf.jks         fixed signing key so updates install over the top
```

The signing key is in the repository on purpose and is not a secret. Do not reuse it
for an app you publish.

libVLC is licensed under the LGPL 2.1.
