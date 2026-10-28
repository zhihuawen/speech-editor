# Screen Speaker

A small macOS app that reads text aloud with `ollama run phonon`.

Copy Screen lets you drag over any part of the screen (a browser page, a PDF, another app's
window), recognizes the text in the screenshot with Apple's Vision framework and adds it to the end
of the text pane. The pane is a normal editor: paste or type text, fix misread words, select a part.
Speak reads the selection, or the whole pane when nothing is selected, in sentence-sized chunks so
audio starts after the first chunk instead of after the whole text.

## Requirements

- JDK 17 or newer to build (Gradle picks JDK 21 via the toolchain setting in `build.gradle.kts`).
- [Ollama](https://ollama.com). If `ollama` is missing, the first Speak offers to install it with
  `curl -fsSL https://ollama.com/install.sh | sh`. The `phonon` model is pulled on first use.
- macOS with the Xcode Command Line Tools (`xcode-select --install`). The build compiles a tiny
  Swift helper (`src/main/swift/ocr.swift`) with `swiftc` and bundles it into the jar. On other
  platforms the build still produces the jar, but Copy Screen reports that the helper is missing.

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
to use a model other than `phonon`.

## Using it

The window stays on top and parks itself in the lower right corner.

| Action | How |
|---|---|
| Copy Screen | Press Copy Screen (Cmd-R). The window hides and the crosshair appears. Drag a rectangle over the text, or press Space and click a window. Esc cancels. The recognized text is added to the end of the pane as a new paragraph. Nothing is spoken yet, and playback that is already running keeps going |
| Speak | Pick a voice and press Speak (Shift-Cmd-R). Reads the selected part of the pane, or all of it when nothing is selected. Fix a misread word in the pane and press Speak to hear it corrected |
| Speed | Pick 0.75x to 3x next to the voice. A change applies at once, even in the middle of a sentence, and the pitch stays the same: the audio is time-stretched, not played faster |
| Pause / Resume | Pause (Cmd-P) holds playback where it is, even in the middle of a sentence. The same button, now labelled Resume, continues from that point |
| Edit | The pane is a normal text area: type into it, Cmd-V pastes text from any app, Cmd-C copies the recognized text out, Cmd-A selects all |
| Stop | Stop button or Cmd-. ; ends playback for good (paused or not) and kills ollama (or the installer) if it is still running |

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
`TextChunker` splits the text at sentence boundaries into chunks of about 280 characters;
`SpeechPipeline` synthesizes the next chunk while the current one plays. The first run after a
while is slow because Ollama loads the model. Speed is applied at playback: `TimeStretch` shortens
or lengthens the PCM with WSOLA (overlap-add of windows picked by cross-correlation), so the voice
keeps its pitch. A speed change or a resume reopens the clip from the point reached, so both take
effect immediately.

Copy Screen runs `screencapture -i -x` for the screenshot and then the bundled `ocr` helper, which
uses `VNRecognizeTextRequest` (accurate mode, language correction) and prints the lines in reading
order with blank lines where a vertical gap looks like a paragraph break. The helper is unpacked
from the jar into `~/Library/Caches/dev.zhihua.speech-editor/` on first use; set
`-Dscreen.ocr=<path>` to use a different binary.

When `ollama` is not found, Speak shows a confirmation dialog, runs the install script with
`/bin/sh -c`, streams its output to the status bar, and then looks for the binary again. A failed
install shows the script's last lines and points to https://ollama.com/download.

## Tests

`./gradlew test` runs JUnit tests for the chunker, the pipeline (ordering, prefetch, pause and
resume, stop, failure), the Copy Screen append rule, the time stretch (length, pitch and loudness of
a sine), pause, resume and speed changes on a real clip through the sound system (a few seconds
of silence), and the OCR helper (unpacking, plus recognition of text drawn with Java2D
on macOS).
