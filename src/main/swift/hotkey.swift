// System-wide shortcut helper for Screen Speaker.
//
// Usage: hotkey <virtual-key-code> <carbon-modifier-bits>
// Registers the key with Carbon's RegisterEventHotKey, which works from any process and needs no
// Accessibility permission, prints "ready", and then prints "pressed" every time the key is hit,
// whichever app is in front. Exits with status 0 when stdin closes (the app that started it is
// gone), and with status 1 and a message on stderr if the arguments are wrong or the key cannot be
// registered.
//
// Built by Gradle (`swiftc -O -o build/helpers/bin/hotkey src/main/swift/hotkey.swift`) and bundled
// in the jar as /bin/hotkey. Carbon's hot key API exists only on macOS.

import Carbon
import Foundation

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(1)
}

func tell(_ line: String) {
    print(line)
    fflush(stdout) // stdout is a pipe, so it would otherwise be block-buffered
}

let args = CommandLine.arguments
guard args.count == 3, let keyCode = UInt32(args[1]), let modifiers = UInt32(args[2]) else {
    fail("usage: hotkey <virtual-key-code> <carbon-modifier-bits>")
}

var hotKey: EventHotKeyRef?
let hotKeyID = EventHotKeyID(signature: 0x5350_5352 /* 'SPSR' */, id: 1)
let registered = RegisterEventHotKey(keyCode, modifiers, hotKeyID, GetApplicationEventTarget(), 0, &hotKey)
guard registered == noErr else {
    fail("hotkey: cannot register key code \(keyCode) with modifiers \(modifiers) (error \(registered))")
}

var pressed = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
let installed = InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
    tell("pressed")
    return noErr
}, 1, &pressed, nil, nil)
guard installed == noErr else {
    fail("hotkey: cannot install the key handler (error \(installed))")
}

// The app holds the other end of stdin; when it exits, the pipe closes and so does this helper.
DispatchQueue.global().async {
    while readLine() != nil {}
    exit(0)
}

tell("ready")
CFRunLoopRun()
