# Speech Editor and Screen Speaker

Two small macOS apps that turn text into speech with `ollama run phonon`.

- **Speech Editor**: a plain-text editor with a Speak button. Speak reads the selected text (or the
  whole document when nothing is selected).
- **Screen Speaker**: reads text that is on your screen. Read Screen lets you drag over any part of
  the screen (a browser page, a PDF, another app's window), recognizes the text in the screenshot
  with Apple's Vision framework, shows it, and speaks it.

Both share the same ollama/phonon speech code and read text in sentence-sized chunks, so audio
starts after the first chunk instead of after the whole text.

## Requirements

- JDK 17 or newer to build (Gradle picks JDK 21 via the toolchain setting in `build.gradle.kts`).
- [Ollama](https://ollama.com). If `ollama` is missing, the first Speak offers to install it with
  `curl -fsSL https://ollama.com/install.sh | sh`. The `phonon` model is pulled on first use.
- For Screen Speaker: macOS with the Xcode Command Line Tools (`xcode-select --install`). The build
  compiles a tiny Swift helper (`src/main/swift/ocr.swift`) with `swiftc` and bundles it into the
  jar. On other platforms the build still produces Speech Editor; Screen Speaker reports that the
  helper is missing.

## Build

```
./gradlew build            # build/libs/speech-editor.jar and build/libs/screen-speaker.jar, runs the tests
./gradlew run              # Speech Editor from sources
./gradlew runScreen        # Screen Speaker from sources
./gradlew appImage         # build/app/Speech Editor.app (macOS bundle with its own runtime)
./gradlew appImageScreen   # build/app/Screen Speaker.app
```

## Run

```
java -jar build/libs/speech-editor.jar [file]
java -jar build/libs/screen-speaker.jar
```

Or double-click the `.app`. The apps find `ollama` via `OLLAMA_BIN`, then `PATH`, then
`/usr/local/bin`, `/opt/homebrew/bin` and `/Applications/Ollama.app`. Set `-Dspeech.model=<name>`
to use a model other than `phonon`.

## Speech Editor

| Action | How |
|---|---|
| New, Open, Save, Save As | File menu, Cmd-N / Cmd-O / Cmd-S / Shift-Cmd-S |
| Cut, Copy, Paste, Select All | Edit menu or the usual Cmd shortcuts; pasting from any app works |
| Speak | Select text, pick a voice, press Speak (Cmd-R). No selection reads the whole document |
| Stop | Stop button or Cmd-. ; kills ollama (or the installer) if it is still running |

## Screen Speaker

The window stays on top and parks itself in the lower right corner.

| Action | How |
|---|---|
| Read Screen | Press Read Screen (Cmd-R). The window hides and the crosshair appears. Drag a rectangle over the text, or press Space and click a window. Esc cancels. The recognized text appears in the pane and is read aloud |
| Speak | Reads the pane again (Shift-Cmd-R), or only the part you have selected. Fix a misread word in the pane and press Speak to hear it corrected |
| Copy | The pane is a normal text area, so Cmd-C copies the recognized text into any other app |
| Stop | Stop button or Cmd-. |

**Screen Recording permission.** macOS lets `screencapture` see other apps' windows only after the
responsible app has Screen Recording permission: Terminal or your IDE when you use `./gradlew
runScreen`, or `Screen Speaker.app` when you use the bundle. macOS asks the first time; if you
declined, turn it on in System Settings > Privacy & Security > Screen Recording. Without it the
screenshot shows only the desktop and Screen Speaker reports "No text found".

**Known limit.** Text is ordered top to bottom, left to right. Multi-column layouts (a sidebar next
to an article) interleave; drag a tighter rectangle around the column you want.

## How it works

`ollama run phonon --voice <v> "<text>"` writes a 16-bit mono 24 kHz WAV to stdout when stdout
is not a terminal. `OllamaSpeech` captures those bytes and plays them with `javax.sound.sampled`.
`TextChunker` splits the text at sentence boundaries into chunks of about 280 characters;
`SpeechPipeline` synthesizes the next chunk while the current one plays. The first run after a
while is slow because Ollama loads the model.

Screen Speaker runs `screencapture -i -x` for the screenshot and then the bundled `ocr` helper,
which uses `VNRecognizeTextRequest` (accurate mode, language correction) and prints the lines in
reading order with blank lines where a vertical gap looks like a paragraph break. The helper is
unpacked from the jar into `~/Library/Caches/dev.zhihua.speech-editor/` on first use; set
`-Dscreen.ocr=<path>` to use a different binary.

When `ollama` is not found, Speak shows a confirmation dialog, runs the install script with
`/bin/sh -c`, streams its output to the status bar, and then looks for the binary again. A failed
install shows the script's last lines and points to https://ollama.com/download.

## Tests

`./gradlew test` runs JUnit tests for the chunker, the pipeline (ordering, prefetch, stop, failure)
and the OCR helper (unpacking, plus recognition of text drawn with Java2D on macOS).
