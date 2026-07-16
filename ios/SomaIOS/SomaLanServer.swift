import Foundation
import Network
import Observation

/// The Browser view, ported read-only-first like the LightOS original: a tiny
/// HTTP server for the local network, gated by a per-session access code and a
/// session cookie. Plain HTTP on a trusted Wi-Fi is the same documented tradeoff
/// the Android app makes; nothing is writable and media is never served.
@MainActor
@Observable
final class LanBrowserServer {
    private(set) var isRunning = false
    private(set) var port: UInt16?
    private(set) var accessCode = ""
    private(set) var addressText: String?

    private var listener: NWListener?
    private var sessionToken = ""
    private let store: SomaStore

    init(store: SomaStore) {
        self.store = store
    }

    func toggle() {
        isRunning ? stop() : start()
    }

    func start() {
        guard listener == nil else { return }
        accessCode = String(format: "%06d", Int.random(in: 0...999_999))
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-soma-lanserver-fixed-code") {
            accessCode = "123456"
        }
        #endif
        sessionToken = UUID().uuidString

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener: NWListener
        do {
            listener = try NWListener(using: parameters, on: 8686)
        } catch {
            do {
                listener = try NWListener(using: parameters)
            } catch {
                return
            }
        }
        let box = WeakServerBox(self)
        listener.newConnectionHandler = { connection in
            connection.start(queue: .global(qos: .utility))
            Self.receiveRequest(on: connection, buffer: Data()) { request in
                Task { @MainActor in
                    guard let server = box.value else {
                        connection.cancel()
                        return
                    }
                    let response = server.respond(to: request)
                    connection.send(
                        content: response,
                        completion: .contentProcessed { _ in connection.cancel() }
                    )
                }
            }
        }
        listener.stateUpdateHandler = { state in
            Task { @MainActor in
                guard let server = box.value else { return }
                switch state {
                case .ready:
                    server.port = server.listener?.port?.rawValue
                    server.isRunning = true
                    server.addressText = Self.localAddress().map {
                        "http://\($0):\(server.port ?? 0)"
                    }
                case .failed, .cancelled:
                    server.isRunning = false
                    server.port = nil
                    server.addressText = nil
                default:
                    break
                }
            }
        }
        self.listener = listener
        listener.start(queue: .global(qos: .utility))
    }

    func stop() {
        listener?.cancel()
        listener = nil
        isRunning = false
        port = nil
        addressText = nil
        sessionToken = UUID().uuidString
    }

    // MARK: - HTTP

    private struct Request {
        var method: String
        var path: String
        var cookies: [String: String]
        var formFields: [String: String]
    }

    private nonisolated static func receiveRequest(
        on connection: NWConnection,
        buffer: Data,
        completion: @escaping @Sendable (Request) -> Void
    ) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8_192) { data, _, done, error in
            var buffer = buffer
            if let data { buffer.append(data) }
            guard error == nil, buffer.count <= 16_384 else {
                connection.cancel()
                return
            }
            if let request = parse(buffer) {
                completion(request)
            } else if done {
                connection.cancel()
            } else {
                receiveRequest(on: connection, buffer: buffer, completion: completion)
            }
        }
    }

    private nonisolated static func parse(_ data: Data) -> Request? {
        guard let headerEnd = data.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let headerText = String(decoding: data[..<headerEnd.lowerBound], as: UTF8.self)
        var lines = headerText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return nil }
        lines.removeFirst()
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let method = String(parts[0])
        let path = String(parts[1])

        var cookies: [String: String] = [:]
        var contentLength = 0
        for line in lines {
            let lower = line.lowercased()
            if lower.hasPrefix("cookie:") {
                for pair in line.dropFirst("cookie:".count).components(separatedBy: ";") {
                    let kv = pair.split(separator: "=", maxSplits: 1).map {
                        $0.trimmingCharacters(in: .whitespaces)
                    }
                    if kv.count == 2 { cookies[kv[0]] = kv[1] }
                }
            } else if lower.hasPrefix("content-length:") {
                contentLength = Int(line.dropFirst("content-length:".count)
                    .trimmingCharacters(in: .whitespaces)) ?? 0
            }
        }

        guard contentLength <= 4_096 else { return nil }
        let body = data[headerEnd.upperBound...]
        if method == "POST", body.count < contentLength {
            return nil
        }
        var formFields: [String: String] = [:]
        if method == "POST" {
            for pair in String(decoding: body, as: UTF8.self).components(separatedBy: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
                if kv.count == 2 {
                    formFields[kv[0]] = kv[1]
                        .replacingOccurrences(of: "+", with: " ")
                        .removingPercentEncoding ?? kv[1]
                }
            }
        }
        return Request(method: method, path: path, cookies: cookies, formFields: formFields)
    }

    private func respond(to request: Request) -> Data {
        let authorized = !sessionToken.isEmpty
            && request.cookies["soma_session"] == sessionToken

        switch (request.method, request.path) {
        case ("POST", "/login"):
            if request.formFields["code"] == accessCode {
                return Self.response(
                    status: "303 See Other",
                    headers: [
                        "Location": "/day/\(SomaDay.key(Date()))",
                        "Set-Cookie": "soma_session=\(sessionToken); HttpOnly; SameSite=Strict",
                    ]
                )
            }
            return Self.response(html: loginPage(failed: true))
        case ("GET", "/"):
            guard authorized else { return Self.response(html: loginPage(failed: false)) }
            return Self.response(
                status: "303 See Other",
                headers: ["Location": "/day/\(SomaDay.key(Date()))"]
            )
        case ("GET", "/todos"):
            guard authorized else { return Self.response(html: loginPage(failed: false)) }
            return Self.response(html: todosPage())
        case ("GET", let path) where path.hasPrefix("/day/"):
            guard authorized else { return Self.response(html: loginPage(failed: false)) }
            let key = String(path.dropFirst("/day/".count))
            guard SomaDay.date(fromKey: key) != nil else {
                return Self.response(status: "404 Not Found", headers: [:])
            }
            return Self.response(html: dayPage(key: key))
        default:
            return Self.response(status: "404 Not Found", headers: [:])
        }
    }

    // MARK: - Pages

    private func loginPage(failed: Bool) -> String {
        page(
            title: "Soma",
            body: """
            <main class="center">
              <h1>SOMA</h1>
              \(failed ? "<p class=\"meta\">\(escape(String(localized: "That code didn’t match.")))</p>" : "")
              <form method="post" action="/login">
                <input type="password" name="code" inputmode="numeric" autofocus
                       placeholder="\(escape(String(localized: "Access code")))">
                <button type="submit">\(escape(String(localized: "Enter")))</button>
              </form>
            </main>
            """
        )
    }

    private func dayPage(key: String) -> String {
        let calendar = Calendar.autoupdatingCurrent
        let day = SomaDay.date(fromKey: key) ?? Date()
        let previous = SomaDay.key(calendar.date(byAdding: .day, value: -1, to: day) ?? day)
        let next = SomaDay.key(calendar.date(byAdding: .day, value: 1, to: day) ?? day)
        let isLatest = key >= SomaDay.key(Date())

        let entries = store.entries
            .filter { $0.day == key && !$0.isDeleted }
            .sorted { $0.createdAt < $1.createdAt }
        let logs = store.logs.filter { $0.day == key }

        var body = """
        <header>
          <a href="/day/\(previous)">‹</a>
          <h1>\(escape(day.formatted(date: .complete, time: .omitted)))</h1>
          \(isLatest ? "<span class=\"dim\">›</span>" : "<a href=\"/day/\(next)\">›</a>")
        </header>
        <nav><a href="/day/\(SomaDay.key(Date()))">\(escape(String(localized: "Today")))</a> · <a href="/todos">\(escape(String(localized: "Important")))</a></nav>
        <main>
        """
        if entries.isEmpty && logs.isEmpty {
            body += "<p class=\"meta\">\(escape(String(localized: "Nothing was kept on this day.")))</p>"
        }
        for entry in entries {
            let time = entry.createdAt.formatted(date: .omitted, time: .shortened)
            var text = entry.text
            if text.isEmpty {
                text = entry.imageFileName != nil
                    ? String(localized: "Photo note")
                    : String(localized: "Voice note")
            }
            let marker = entry.kind == .voice ? "◦ " : ""
            body += """
            <article><span class="meta">\(escape(time))</span><p>\(marker)\(escape(text))</p></article>
            """
        }
        if !logs.isEmpty {
            body += "<section class=\"logs\">"
            for log in logs {
                body += "<p class=\"meta\">· \(escape(log.title))</p>"
            }
            body += "</section>"
        }
        body += "</main>"
        return page(title: key, body: body)
    }

    private func todosPage() -> String {
        var body = """
        <header><h1>\(escape(String(localized: "Important")))</h1></header>
        <nav><a href="/day/\(SomaDay.key(Date()))">\(escape(String(localized: "Today")))</a></nav>
        <main>
        """
        let open = store.openImportant
        let done = store.completedImportant
        if open.isEmpty && done.isEmpty {
            body += "<p class=\"meta\">\(escape(String(localized: "Nothing important")))</p>"
        }
        for item in open {
            body += "<article><p>○ \(escape(item.text))</p></article>"
        }
        if !done.isEmpty {
            body += "<h2 class=\"meta\">\(escape(String(localized: "Completed")))</h2>"
            for item in done {
                body += "<article><p class=\"done\">● \(escape(item.text))</p></article>"
            }
        }
        body += "</main>"
        return page(title: String(localized: "Important"), body: body)
    }

    // The moonlit reading room, abridged: near-black paper, warm off-white ink.
    private func page(title: String, body: String) -> String {
        """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>\(escape(title))</title>
        <style>
        :root{color-scheme:dark}
        body{background:#0a0b0a;color:#f4f2ec;font:17px/1.6 -apple-system,Georgia,serif;
             margin:0 auto;max-width:40rem;padding:2rem 1.2rem}
        a{color:#f4f2ec}
        header{display:flex;align-items:baseline;gap:1rem}
        header h1{font-size:1.15rem;font-weight:600;letter-spacing:.02em;flex:1;margin:0}
        nav{margin:.4rem 0 1.6rem;font-size:.85rem;color:#8a8a84}
        nav a{color:#8a8a84;text-decoration:none}
        article{margin:1.1rem 0;border-top:1px solid #1c1d1c;padding-top:1.1rem}
        article p{margin:.2rem 0;white-space:pre-wrap}
        .meta{color:#8a8a84;font-size:.8rem}
        .dim{color:#3a3b3a}
        .done{color:#8a8a84;text-decoration:line-through}
        .logs{margin-top:2rem}
        .center{display:flex;flex-direction:column;justify-content:center;
                align-items:center;min-height:80vh;gap:1rem}
        .center h1{letter-spacing:.35em;font-weight:600}
        input{background:transparent;border:1px solid #3a3b3a;color:#f4f2ec;
              padding:.6rem .9rem;border-radius:.5rem;font-size:1rem;text-align:center}
        button{background:#f4f2ec;color:#0a0b0a;border:0;padding:.6rem 1.4rem;
               border-radius:.5rem;font-size:1rem}
        </style></head><body>\(body)</body></html>
        """
    }

    private final class WeakServerBox: @unchecked Sendable {
        weak var value: LanBrowserServer?

        init(_ value: LanBrowserServer?) {
            self.value = value
        }
    }

    private func escape(_ text: String) -> String {
        text
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }

    private nonisolated static func response(html: String) -> Data {
        let body = Data(html.utf8)
        var head = "HTTP/1.1 200 OK\r\n"
        head += "Content-Type: text/html; charset=utf-8\r\n"
        head += "Content-Length: \(body.count)\r\n"
        head += "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n"
        head += "Connection: close\r\n\r\n"
        return Data(head.utf8) + body
    }

    private nonisolated static func response(status: String, headers: [String: String]) -> Data {
        var head = "HTTP/1.1 \(status)\r\n"
        for (key, value) in headers {
            head += "\(key): \(value)\r\n"
        }
        head += "Content-Length: 0\r\nConnection: close\r\n\r\n"
        return Data(head.utf8)
    }

    private nonisolated static func localAddress() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0 else { return nil }
        defer { freeifaddrs(interfaces) }
        var pointer = interfaces
        while let current = pointer {
            let interface = current.pointee
            let family = interface.ifa_addr.pointee.sa_family
            if family == UInt8(AF_INET), String(cString: interface.ifa_name) == "en0" {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(
                    interface.ifa_addr,
                    socklen_t(interface.ifa_addr.pointee.sa_len),
                    &host,
                    socklen_t(host.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                )
                let length = host.firstIndex(of: 0) ?? host.count
                return String(decoding: host[..<length].map { UInt8(bitPattern: $0) }, as: UTF8.self)
            }
            pointer = interface.ifa_next
        }
        return nil
    }
}
