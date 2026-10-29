# Screen Speaker

A small macOS app that reads text aloud: English with `ollama run phonon`, Chinese with the
macOS `say` command.

Copy Screen lets you drag over any part of the screen (a browser page, a PDF, another app's
window), recognizes the text in the screenshot with Apple's Vision framework and adds it to the end
of the text pane. The same capture is one key away in any app: ⇧⌘2 (next to the macOS screenshot
keys) brings up the crosshair and appends the recognized text without taking you away from what you
were reading. The pane is a normal editor: paste or type text, fix misread words, select a part.
Speak reads the selection, or the whole pane when nothing is selected, in sentence-sized chunks so
audio starts after the first chunk instead of after the whole text. Save WAV runs the same
synthesis but writes the speech to a file instead of playing it.

## Requirements

- JDK 17 or newer to build (Gradle picks JDK 21 via the toolchain setting in `build.gradle.kts`).
- For English, [Ollama](https://ollama.com). If `ollama` is missing, the first English Speak offers
  to install it with `curl -fsSL https://ollama.com/install.sh | sh`. The `phonon` model is pulled
  on first use.
- For Chinese, nothing extra: `say` and its Mandarin voice Tingting come with macOS.
- macOS with the Xcode Command Line Tools (`xcode-select --install`). The build compiles two tiny
  Swift helpers (`src/main/swift/ocr.swift` for text recognition, `hotkey.swift` for the system-wide
  shortcut) with `swiftc` and bundles them into the jar. On other platforms the build still produces
  the jar, but Copy Screen reports that the helper is missing and there is no shortcut.

## Build

```
./gradlew build      # build/libs/screen-speaker.jar, runs the tests
./gradlew run        # Screen Speaker from sources
./gradlew appImage   # build/app/Screen Speaker.app (macOS bundle with its own runtime)
```

## Run

```
java -jar build/libs/screen-speaker.jar
```

Or double-click `Screen Speaker.app`. The app finds `ollama` via `OLLAMA_BIN`, then `PATH`, then
`/usr/local/bin`, `/opt/homebrew/bin` and `/Applications/Ollama.app`. Set `-Dspeech.model=<name>`
to use a model other than `phonon`. The system-wide shortcut is picked in Speech > Copy Screen
Shortcut; `-Dscreen.hotkey=<keys>` overrides that choice for one run (`alt+cmd+r`, `ctrl+f5`, `⌃⌥⌘R`:
a letter, digit, `space` or `f1`..`f12` with any of `cmd`, `shift`, `alt`, `ctrl`), and
`-Dscreen.hotkey=none` runs without one.

## Using it

The window stays on top and parks itself in the lower right corner. The top row sets the language,
voice and speed; the row below it holds the actions.

| Action | How |
|---|---|
| Language | English or Chinese. English is read by ollama `phonon`, Chinese by the macOS `say` command. The voice list changes with the language, and each language remembers the voice you last picked |
| Copy Screen | Press Copy Screen (Cmd-R). The window hides and the crosshair appears. Drag a rectangle over the text, or press Space and click a window. Esc cancels. The recognized text is added to the end of the pane as a new paragraph. Nothing is spoken yet, and playback that is already running keeps going |
| ⇧⌘2 anywhere | Press Shift-Cmd-2 in any app, without switching to Screen Speaker: the same capture starts, and the text lands at the end of the pane while the app you were in stays in front, so several captures can be collected while reading. Speech > Copy Screen Shortcut changes the key: ⇧⌘2, ⌥⌘R, ⌃⌥⌘R, ⌃⌥Space, Other… for any combination, or None. The change applies at once and is remembered. If a key cannot be registered (another app owns it, or the helper is missing) the status bar says so and Copy Screen still works from the window |
| Speak | Pick a voice and press Speak (Shift-Cmd-R). Reads the selected part of the pane, or all of it when nothing is selected. Fix a misread word in the pane and press Speak to hear it corrected |
| Speed | Pick 0.75x to 3x next to the voice. A change applies at once, even in the middle of a sentence, and the pitch stays the same: the audio is time-stretched, not played faster |
| Pause / Resume | Pause (Cmd-P) holds playback where it is, even in the middle of a sentence. The same button, now labelled Resume, continues from that point |
| Edit | The pane is a normal text area: type into it, Cmd-V pastes text from any app, Cmd-C copies the recognized text out, Cmd-A selects all |
| Stop | Stop button or Cmd-. ; ends playback (paused or not) or a save for good and kills ollama or say (or the installer) if it is still running |
| Save WAV | Save WAV… (Shift-Cmd-S) asks for a file name, suggesting the first sentence of the text, then synthesizes the selected part of the pane (or all of it) with the chosen language, voice and speed and writes one WAV file (16-bit mono 24 kHz) instead of playing it. The status bar counts the parts and ends with the file's path and length. Nothing plays meanwhile; Stop abandons the save without writing a file |

**Voices.** The English list is every voice `ollama show phonon` names. The Chinese list is every
voice `say -v '?'` reports with a `zh` locale: Tingting (Mandarin, China mainland) first, then the
others installed on this Mac, for example Meijia (Taiwan) and Sinji (Cantonese, Hong Kong). More
voices can be added in System Settings > Accessibility > Spoken Content > System Voice > Manage
Voices. Both boxes accept a typed name too.

**Screen Recording permission.** macOS lets `screencapture` see other apps' windows only after the
responsible app has Screen Recording permission: Terminal or your IDE when you use `./gradlew run`,
or `Screen Speaker.app` when you use the bundle. macOS asks the first time; if you declined, turn it
on in System Settings > Privacy & Security > Screen Recording. Without it the screenshot shows only
the desktop and Screen Speaker reports "No text found".

**Known limit.** Text is ordered top to bottom, left to right. Multi-column layouts (a sidebar next
to an article) interleave; drag a tighter rectangle around the column you want.

## How it works

`ollama run phonon --voice <v> "<text>"` writes a 16-bit mono 24 kHz WAV to stdout when stdout
is not a terminal. `OllamaSpeech` captures those bytes and plays them with `javax.sound.sampled`.
For Chinese, `SaySpeech` runs `say -v Tingting -o <tmp>.wav --file-format=WAVE
--data-format=LEI16@24000 -f -` with the text on stdin, so `say` writes the same kind of WAV
instead of speaking, and the rest of the app does not care which engine made the bytes. A voice
that `say -v '?'` does not list is reported as an error rather than passed on, because `say` would
silently fall back to the system voice, usually an English one.

`TextChunker` splits the text at sentence boundaries, English (`.!?`) and Chinese (`。！？`), into
chunks of about 280 characters for English and 100 for Chinese (a Chinese character is roughly a
syllable, so both are about twenty seconds of speech). Between two Chinese characters a line break
is a wrap rather than a word gap and is dropped; a long Chinese sentence without spaces is cut
after a clause mark (`，、；：`). `SpeechPipeline` synthesizes the next chunk while the current
one plays. The first English run after a while is slow because Ollama loads the model; `say` needs
about two seconds per chunk. Speed is applied at playback: `TimeStretch` shortens or lengthens the
PCM with WSOLA (overlap-add of windows picked by cross-correlation), so the voice keeps its pitch.
A speed change or a resume reopens the clip from the point reached, so both take effect
immediately.

Save WAV feeds the same pipeline a `WavFile` in place of the player: it collects each chunk's PCM,
checks that every chunk has the format of the first, and when the pipeline is done stretches the
whole thing to the selected speed with `TimeStretch` and writes it with `AudioSystem.write`. The
file is written only at the end, so a stopped or failed save leaves nothing behind.

The app icon (a screen with lines of text and a speaker on a blue square) is drawn by
`AppIcon.java` with Java2D; `./gradlew appImage` renders it at every size macOS wants, packs the
set into `Screen Speaker.icns` with `iconutil`, and hands that to jpackage. Run from sources, the
app puts the same drawing in the Dock itself.

Copy Screen runs `screencapture -i -x` for the screenshot and then the bundled `ocr` helper, which
uses `VNRecognizeTextRequest` (accurate mode, language correction) and prints the lines in reading
order with blank lines where a vertical gap looks like a paragraph break. The helper is unpacked
from the jar into `~/Library/Caches/dev.zhihua.speech-editor/` on first use; set
`-Dscreen.ocr=<path>` to use a different binary.

The system-wide shortcut comes from a second bundled Swift helper, `hotkey`, because Java cannot
register one itself. It calls Carbon's `RegisterEventHotKey`, which works from any process and needs
no Accessibility permission, prints `ready`, then `pressed` for every hit wherever it happens, and
exits when its stdin closes, so it dies with the app. The press only arrives through the
application event loop (a bare `CFRunLoopRun()` registers fine and then never hears a thing), so
the helper runs `NSApplication`'s loop as a background-only process. `GlobalHotkey` runs it and turns each `pressed`
line into a Copy Screen; a new choice in the menu stops the helper and starts another, and is kept
in the user's Java preferences. A capture started that way puts the window back without taking the
keyboard: shown with auto-request-focus off, a Java window on macOS comes back without becoming the
key window, so the app the key was pressed in stays in front; from the button or the menu the window
takes the focus back as before.

When `ollama` is not found, an English Speak shows a confirmation dialog, runs the install script
with `/bin/sh -c`, streams its output to the status bar, and then looks for the binary again. A
failed install shows the script's last lines and points to https://ollama.com/download.

## Tests

`./gradlew test` runs JUnit tests for the chunker (English and Chinese), the pipeline (ordering,
prefetch, pause and resume, stop, failure, status wording), the Copy Screen append rule, the time
stretch (length, pitch and loudness of a sine), pause, resume and speed changes on a real clip
through the sound system (a few seconds of silence), `say` (a real Chinese WAV, the uninstalled
voice error, the voice listing), the WAV file (concatenation, speed, format checks, file names),
the OCR helper (unpacking, plus recognition of text drawn with Java2D on macOS), and the system-wide
shortcut (key specs, the remembered choice and the flag that overrides it, the helper protocol
driven by fake helpers, the real helper registering a key and exiting when its stdin closes, and one
real press: `src/test/swift/press.swift` posts the
modifier keys and the key at the HID level and the helper must report it; that test skips unless the
terminal running the tests has Accessibility permission).
