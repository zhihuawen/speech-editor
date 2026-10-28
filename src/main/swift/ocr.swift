// Text recognition helper for Screen Speaker.
//
// Usage: ocr <image-file>
// Prints the text found in the image, one recognized line per output line, in reading order
// (top to bottom, left to right). A blank line marks a vertical gap large enough to be a paragraph
// break. Exit status 1 with a message on stderr if the image cannot be read or recognized.
//
// Built by Gradle (`swiftc -O -o build/helpers/bin/ocr src/main/swift/ocr.swift`) and bundled in
// the jar as /bin/ocr. Uses Apple's Vision framework, so this is macOS only.

import Foundation
import ImageIO
import Vision

struct Line {
    let text: String
    let box: CGRect   // normalized, origin bottom-left (Vision convention)
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(1)
}

let args = CommandLine.arguments
guard args.count == 2 else { fail("usage: ocr <image-file>") }

let url = URL(fileURLWithPath: args[1])
guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    fail("ocr: cannot read image \(args[1])")
}

let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.usesLanguageCorrection = true
if #available(macOS 13.0, *) {
    request.automaticallyDetectsLanguage = true
}

do {
    try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
} catch {
    fail("ocr: recognition failed: \(error.localizedDescription)")
}

let observations = request.results ?? []
let lines: [Line] = observations.compactMap { obs in
    guard let best = obs.topCandidates(1).first else { return nil }
    let text = best.string.trimmingCharacters(in: .whitespaces)
    return text.isEmpty ? nil : Line(text: text, box: obs.boundingBox)
}

// Group lines into rows (same vertical band), order rows top to bottom and each row left to right.
var rows: [[Line]] = []
for line in lines.sorted(by: { $0.box.midY > $1.box.midY }) {
    if let last = rows.last, let anchor = last.first,
       abs(anchor.box.midY - line.box.midY) <= min(anchor.box.height, line.box.height) * 0.5 {
        rows[rows.count - 1].append(line)
    } else {
        rows.append([line])
    }
}

let orderedRows = rows.map { $0.sorted { $0.box.minX < $1.box.minX } }

// Vertical whitespace between consecutive rows, and the height of the shorter of the two rows
// (next to a heading, that is the body text), both normalized to the image height.
func gapAndHeight(_ above: [Line], _ below: [Line]) -> (gap: CGFloat, height: CGFloat) {
    let aboveBottom = above.map { $0.box.minY }.min()!
    let belowTop = below.map { $0.box.maxY }.max()!
    let height = min(above.map { $0.box.height }.max()!, below.map { $0.box.height }.max()!)
    return (aboveBottom - belowTop, height)
}

// Single-spaced text leaves a gap of roughly 0.3-1.1 line heights between rows; a paragraph break
// adds extra space on top of that. With enough rows, the typical in-paragraph gap is taken from
// the lower quartile of all gaps (the median fails when short paragraphs make big gaps common),
// and a break must clearly exceed it. With few rows there is nothing to compare against, so a
// gap well over one line height has to do.
let gaps = zip(orderedRows, orderedRows.dropFirst()).map { gapAndHeight($0, $1).gap }.sorted()
let typicalGap: CGFloat = gaps.count >= 4 ? gaps[gaps.count / 4] : 0
func isParagraphBreak(_ above: [Line], _ below: [Line]) -> Bool {
    let (gap, height) = gapAndHeight(above, below)
    if gaps.count < 4 { return gap > height * 1.3 }
    return gap > height * 0.9 && gap > typicalGap * 1.8
}

let debug = ProcessInfo.processInfo.environment["OCR_DEBUG"] != nil
var output = ""
for (index, row) in orderedRows.enumerated() {
    if index > 0 {
        let (gap, height) = gapAndHeight(orderedRows[index - 1], row)
        if debug {
            FileHandle.standardError.write(String(format: "gap=%.4f height=%.4f ratio=%.2f typical=%.4f break=%d\n",
                gap, height, gap / height, typicalGap, isParagraphBreak(orderedRows[index - 1], row) ? 1 : 0).data(using: .utf8)!)
        }
        output += isParagraphBreak(orderedRows[index - 1], row) ? "\n\n" : "\n"
    }
    output += row.map { $0.text }.joined(separator: " ")
}
print(output)
