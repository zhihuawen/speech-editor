// Test instrument for GlobalHotkeyTest: presses a key the way a finger would, at the HID level, so a
// registered system-wide hotkey fires: modifier keys down one by one, the key down and up, modifiers
// up again. (Flags stamped on a lone synthetic key event are not enough for hotkey matching.)
//
// Usage: swift press.swift <virtual-key-code> <carbon-modifier-bits>
// Posting events needs Accessibility trust for the process that runs this (the terminal or IDE
// running the tests); without it prints "untrusted" and exits with status 2 so the test can skip.

import Cocoa

let args = CommandLine.arguments
guard args.count == 3, let key = UInt16(args[1]), let mods = UInt32(args[2]) else {
    FileHandle.standardError.write("usage: press <virtual-key-code> <carbon-modifier-bits>\n".data(using: .utf8)!)
    exit(1)
}
guard AXIsProcessTrusted() else {
    print("untrusted")
    exit(2)
}

let source = CGEventSource(stateID: .hidSystemState)
func post(_ k: CGKeyCode, down: Bool, flags: CGEventFlags) {
    let event = CGEvent(keyboardEventSource: source, virtualKey: k, keyDown: down)!
    event.flags = flags
    event.post(tap: .cghidEventTap)
    usleep(40_000)
}

// Carbon modifier bits to the modifier keys' own key codes and event flags.
var modifierKeys: [(CGKeyCode, CGEventFlags)] = []
if mods & 0x1000 != 0 { modifierKeys.append((59, .maskControl)) }   // controlKey, kVK_Control
if mods & 0x0800 != 0 { modifierKeys.append((58, .maskAlternate)) } // optionKey, kVK_Option
if mods & 0x0200 != 0 { modifierKeys.append((56, .maskShift)) }     // shiftKey, kVK_Shift
if mods & 0x0100 != 0 { modifierKeys.append((55, .maskCommand)) }   // cmdKey, kVK_Command

var held: CGEventFlags = []
for (k, flag) in modifierKeys { held.insert(flag); post(k, down: true, flags: held) }
post(CGKeyCode(key), down: true, flags: held)
post(CGKeyCode(key), down: false, flags: held)
for (k, flag) in modifierKeys.reversed() { held.remove(flag); post(k, down: false, flags: held) }
print("posted")
