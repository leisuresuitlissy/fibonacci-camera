import Foundation
import os

/** Console lines prefixed "[Fibcam]" so they're easy to filter in Xcode's console. */
enum Log {
    private static let logger = Logger(subsystem: "com.leisuresuitlissy.fibonaccicamera", category: "app")
    static func info(_ s: String) { logger.info("\(s, privacy: .public)"); print("[Fibcam] \(s)") }
    static func error(_ s: String) { logger.error("\(s, privacy: .public)"); print("[Fibcam] ERROR \(s)") }
}
