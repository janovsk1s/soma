import CryptoKit
import Darwin
import Foundation
import Observation
import Security
import UIKit

enum SomaBridgeCapability: String, Codable, CaseIterable, Hashable, Sendable {
    case contextRead = "context.read"
    case codexThread = "codex.thread"
    case codexTurn = "codex.turn"
    case codexStream = "codex.stream"
    case proposalRead = "proposal.read"
    case proposalApprove = "proposal.approve"
    case syncRead = "sync.read"
    case syncWrite = "sync.write"
}

struct SomaBridgePairingOffer: Equatable, Sendable {
    let bridgeID: UUID
    let host: String
    let port: Int
    let secret: String
    let certificateFingerprint: String
    let expiresAt: Date

    init(uri: String, now: Date = Date()) throws {
        guard
            let components = URLComponents(string: uri.trimmingCharacters(in: .whitespacesAndNewlines)),
            components.scheme?.lowercased() == "soma",
            components.host?.lowercased() == "pair"
        else {
            throw SomaBridgeError.invalidPairingCode
        }

        var values: [String: String] = [:]
        for item in components.queryItems ?? [] {
            guard let value = item.value, values[item.name] == nil else {
                throw SomaBridgeError.invalidPairingCode
            }
            values[item.name] = value
        }
        guard
            values["v"] == "1",
            let bridgeID = UUID(uuidString: values["bridge"] ?? ""),
            let port = Int(values["port"] ?? ""),
            (1...65_535).contains(port),
            let expirySeconds = TimeInterval(values["expires"] ?? ""),
            expirySeconds.isFinite
        else {
            throw SomaBridgeError.invalidPairingCode
        }

        let host = values["host"] ?? ""
        let secret = values["secret"] ?? ""
        let fingerprint = (values["fingerprint"] ?? "").lowercased()
        let expiresAt = Date(timeIntervalSince1970: expirySeconds)
        guard
            Self.isPrivateHost(host),
            secret.range(of: #"^[A-Za-z0-9_-]{43,64}$"#, options: .regularExpression) != nil,
            Data(base64URLEncoded: secret)?.count == 32,
            fingerprint.range(of: #"^[0-9a-f]{64}$"#, options: .regularExpression) != nil,
            expiresAt > now
        else {
            throw expiresAt <= now
                ? SomaBridgeError.pairingCodeExpired
                : SomaBridgeError.invalidPairingCode
        }

        self.bridgeID = bridgeID
        self.host = host
        self.port = port
        self.secret = secret
        certificateFingerprint = fingerprint
        self.expiresAt = expiresAt
    }

    private static func isPrivateHost(_ host: String) -> Bool {
        let normalized = host
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            .lowercased()
        if normalized == "localhost" {
            return true
        }
        var ipv6 = in6_addr()
        if normalized.withCString({ inet_pton(AF_INET6, $0, &ipv6) }) == 1 {
            let firstByte = withUnsafeBytes(of: &ipv6) { $0[0] }
            return normalized == "::1" || firstByte & 0xfe == 0xfc
        }
        var ipv4 = in_addr()
        guard normalized.withCString({ inet_pton(AF_INET, $0, &ipv4) }) == 1 else {
            return false
        }
        let octets = withUnsafeBytes(of: &ipv4) { Array($0) }
        return octets[0] == 10
            || (octets[0] == 172 && (16...31).contains(octets[1]))
            || (octets[0] == 192 && octets[1] == 168)
            || (octets[0] == 100 && (64...127).contains(octets[1]))
            || octets[0] == 127
    }
}

struct SomaBridgePairing: Codable, Equatable, Sendable {
    let protocolVersion: Int
    let bridgeID: UUID
    let bridgeName: String
    let host: String
    let port: Int
    let certificateFingerprint: String
    let deviceID: UUID
    let capabilities: [SomaBridgeCapability]
    let pairedAt: String
    var sequence: Int64
    var lastThreadID: String?
    var lastThreadEntryID: UUID?

    var connectionLabel: String {
        let octets = host.split(separator: ".").compactMap { UInt8($0) }
        if octets.count == 4, octets[0] == 100, (64...127).contains(octets[1]) {
            return "Tailscale · TLS 1.3"
        }
        return "Private network · TLS 1.3"
    }
}

struct SomaBridgeStatus: Codable, Equatable, Sendable {
    let protocolVersion: Int
    let bridgeID: UUID
    let bridgeName: String
    let pairedDeviceID: UUID
    let capabilities: [SomaBridgeCapability]
    let codexReady: Bool
}

enum SomaBridgeJobStatus: String, Codable, Sendable {
    case running
    case completed
    case failed
    case cancelled
}

struct SomaBridgeJob: Codable, Identifiable, Equatable, Sendable {
    let jobID: UUID
    let threadID: String
    let turnID: String
    let status: SomaBridgeJobStatus
    let output: String
    let error: String?
    let createdAt: String
    let updatedAt: String

    var id: UUID { jobID }
    var isFinished: Bool { status != .running }
}

enum SomaBridgeActivity: Equatable, Sendable {
    case idle
    case pairing
    case checking
    case asking
    case cancelling
}

enum SomaBridgeError: LocalizedError {
    case invalidPairingCode
    case pairingCodeExpired
    case notPaired
    case missingCapability(SomaBridgeCapability)
    case missingEntry
    case invalidResponse
    case keyUnavailable
    case keychain(OSStatus)
    case randomBytesUnavailable
    case contextTooLarge
    case responseTooLarge
    case server(code: String, message: String)
    case http(Int)
    case timedOut

    var errorDescription: String? {
        switch self {
        case .invalidPairingCode:
            "That is not a valid Soma Bridge pairing code."
        case .pairingCodeExpired:
            "That pairing code has expired. Open a new pairing window on the Mac."
        case .notPaired:
            "Pair Soma with your Mac first."
        case .missingCapability(let capability):
            "The Mac did not grant \(capability.rawValue). Re-pair to update access."
        case .missingEntry:
            "That entry is no longer available."
        case .invalidResponse:
            "The Mac returned an invalid bridge response."
        case .keyUnavailable:
            "Soma could not access its device signing key."
        case .keychain:
            "Soma could not access the protected pairing record."
        case .randomBytesUnavailable:
            "Soma could not create a secure request nonce."
        case .contextTooLarge:
            "The selected and connected entries are too large to send safely."
        case .responseTooLarge:
            "The bridge returned more data than Soma allows."
        case .server(_, let message):
            message
        case .http(let status):
            "The bridge returned HTTP \(status)."
        case .timedOut:
            "Codex is still working, but this view stopped waiting. You can try again."
        }
    }
}

@MainActor
@Observable
final class SomaBridgeClient {
    private(set) var pairing: SomaBridgePairing?
    private(set) var status: SomaBridgeStatus?
    private(set) var activeJob: SomaBridgeJob?
    private(set) var activeEntryID: UUID?
    private(set) var activity: SomaBridgeActivity = .idle
    private(set) var lastErrorMessage: String?

    private let store: SomaStore
    private let identity = SomaBridgeDeviceIdentity()
    private let pairingStore = SomaBridgePairingStore()
    private let requestGate = SomaBridgeRequestGate()
    private var authenticatedSession: URLSession?
    private var authenticatedSessionFingerprint: String?
    private var activeOperationID: UUID?

    init(store: SomaStore) {
        self.store = store
        pairing = try? pairingStore.load()
    }

    var isPaired: Bool { pairing != nil }
    var bridgeName: String { pairing?.bridgeName ?? "Mac" }

    func pair(using pairingURI: String) async throws {
        guard pairing == nil else {
            throw SomaBridgeError.server(
                code: "already_paired",
                message: "Disconnect the current Mac before pairing another one."
            )
        }
        activity = .pairing
        lastErrorMessage = nil
        defer { activity = .idle }

        do {
            let offer = try SomaBridgePairingOffer(uri: pairingURI)
            try identity.rotate()
            let deviceID = store.deviceID
            let requestedCapabilities: [SomaBridgeCapability] = [
                .contextRead,
                .codexThread,
                .codexTurn,
                .codexStream,
            ]
            let requestBody = SomaBridgePairRequest(
                protocolVersion: 1,
                deviceID: deviceID,
                deviceName: Self.boundedUTF8(UIDevice.current.name, maximumBytes: 80),
                platform: "ios",
                publicKey: try identity.publicKey().base64EncodedString(),
                requestedCapabilities: requestedCapabilities
            )
            let body = try Self.encoder.encode(requestBody)
            let session = try Self.session(fingerprint: offer.certificateFingerprint)
            defer { session.finishTasksAndInvalidate() }

            var request = URLRequest(url: try Self.url(host: offer.host, port: offer.port, path: "/v1/pair"))
            request.httpMethod = "POST"
            request.httpBody = body
            request.timeoutInterval = 20
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(offer.secret, forHTTPHeaderField: "X-Soma-Pairing-Secret")

            let (data, response) = try await Self.boundedData(
                for: request,
                using: session,
                maximumBytes: 32 * 1_024,
                exactRetry: false
            )
            try Self.validate(response: response, data: data, expectedStatus: 201)
            let result = try Self.decoder.decode(SomaBridgePairResponse.self, from: data)
            let returnedCapabilities = Set(result.capabilities)
            guard
                result.protocolVersion == 1,
                result.bridgeID == offer.bridgeID,
                result.deviceID == deviceID,
                result.platform == "ios",
                result.certificateFingerprint == offer.certificateFingerprint,
                !result.bridgeName.isEmpty,
                result.bridgeName.utf8.count <= 80,
                result.capabilities.count == returnedCapabilities.count,
                returnedCapabilities == Set(requestedCapabilities)
            else {
                throw SomaBridgeError.invalidResponse
            }

            let pairing = SomaBridgePairing(
                protocolVersion: result.protocolVersion,
                bridgeID: result.bridgeID,
                bridgeName: result.bridgeName,
                host: offer.host,
                port: offer.port,
                certificateFingerprint: result.certificateFingerprint,
                deviceID: result.deviceID,
                capabilities: result.capabilities,
                pairedAt: result.pairedAt,
                sequence: 0,
                lastThreadID: nil,
                lastThreadEntryID: nil
            )
            try pairingStore.save(pairing)
            self.pairing = pairing
            status = nil
        } catch {
            lastErrorMessage = error.localizedDescription
            throw error
        }
    }

    @discardableResult
    func refreshStatus() async throws -> SomaBridgeStatus {
        activity = .checking
        lastErrorMessage = nil
        defer { activity = .idle }
        do {
            let status: SomaBridgeStatus = try await signedRequest(
                method: "GET",
                path: "/v1/status",
                expectedStatus: 200
            )
            guard
                let pairing,
                status.protocolVersion == pairing.protocolVersion,
                status.bridgeID == pairing.bridgeID,
                status.pairedDeviceID == pairing.deviceID,
                status.bridgeName == pairing.bridgeName,
                status.capabilities.count == Set(status.capabilities).count,
                Set(status.capabilities) == Set(pairing.capabilities)
            else {
                throw SomaBridgeError.invalidResponse
            }
            self.status = status
            return status
        } catch {
            lastErrorMessage = error.localizedDescription
            throw error
        }
    }

    @discardableResult
    func askCodex(
        question: String,
        about entryID: UUID,
        continueThread: Bool = true
    ) async throws -> SomaBridgeJob {
        guard activeOperationID == nil else {
            throw SomaBridgeError.server(
                code: "codex_busy",
                message: "Finish or stop the current Codex answer first."
            )
        }
        guard let pairing else { throw SomaBridgeError.notPaired }
        for capability in [
            SomaBridgeCapability.contextRead,
            .codexTurn,
            .codexStream,
        ] where !pairing.capabilities.contains(capability) {
            throw SomaBridgeError.missingCapability(capability)
        }
        let cleanedQuestion = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanedQuestion.isEmpty else {
            throw SomaBridgeError.server(code: "empty_question", message: "Write a question first.")
        }

        let operationID = UUID()
        activeOperationID = operationID
        activity = .asking
        activeEntryID = entryID
        activeJob = nil
        lastErrorMessage = nil
        defer {
            if activeOperationID == operationID {
                activeOperationID = nil
                activity = .idle
            }
        }
        do {
            let context = try makeContext(selectedEntryID: entryID)
            let body = SomaBridgeTurnRequest(
                threadID: continueThread && pairing.lastThreadEntryID == entryID
                    ? pairing.lastThreadID
                    : nil,
                question: Self.boundedUTF8(cleanedQuestion, maximumBytes: 4_096),
                context: context
            )
            var job: SomaBridgeJob = try await signedRequest(
                method: "POST",
                path: "/v1/codex/turns",
                body: body,
                expectedStatus: 202
            )
            try rememberThread(job.threadID, entryID: entryID)
            activeJob = job

            let deadline = Date().addingTimeInterval(5 * 60)
            var pollDelayMilliseconds = 350
            var consecutiveTransportFailures = 0
            while job.status == .running {
                guard Date() < deadline else { throw SomaBridgeError.timedOut }
                try await Task.sleep(for: .milliseconds(pollDelayMilliseconds))
                try Task.checkCancellation()
                let previousOutputCount = job.output.utf8.count
                do {
                    job = try await signedRequest(
                        method: "GET",
                        path: "/v1/codex/jobs/\(job.jobID.uuidString.lowercased())",
                        expectedStatus: 200
                    )
                    consecutiveTransportFailures = 0
                } catch let error as URLError
                    where Self.shouldExactRetry(error)
                        && consecutiveTransportFailures < 5
                {
                    consecutiveTransportFailures += 1
                    pollDelayMilliseconds = min(
                        3_000,
                        500 * consecutiveTransportFailures
                    )
                    continue
                }
                activeJob = job
                pollDelayMilliseconds = job.output.utf8.count > previousOutputCount
                    ? 300
                    : min(1_500, pollDelayMilliseconds + 200)
            }
            if job.status == .failed, let message = job.error {
                throw SomaBridgeError.server(code: "codex_failed", message: message)
            }
            return job
        } catch {
            lastErrorMessage = error.localizedDescription
            throw error
        }
    }

    func cancelActiveJob() async {
        guard let job = activeJob, job.status == .running else { return }
        activity = .cancelling
        do {
            let cancelled: SomaBridgeJob = try await signedRequest(
                method: "POST",
                path: "/v1/codex/jobs/\(job.jobID.uuidString.lowercased())/cancel",
                body: SomaBridgeEmptyBody(),
                expectedStatus: 200
            )
            activeJob = cancelled
            if activeOperationID == nil {
                activity = .idle
            }
        } catch {
            lastErrorMessage = error.localizedDescription
            activity = activeOperationID == nil ? .idle : .asking
        }
    }

    func unpair() async throws {
        guard pairing != nil else { return }
        do {
            let response: SomaBridgeRevocationResponse = try await signedRequest(
                method: "DELETE",
                path: "/v1/pairing",
                expectedStatus: 200
            )
            guard response.revoked else { throw SomaBridgeError.invalidResponse }
            try forgetPairing()
        } catch SomaBridgeError.server(let code, _)
            where code == "device_not_paired"
        {
            try forgetPairing()
        }
    }

    func forgetPairing() throws {
        try pairingStore.delete()
        var identityError: Error?
        do {
            try identity.deletePrivateKey()
        } catch {
            identityError = error
        }
        authenticatedSession?.invalidateAndCancel()
        authenticatedSession = nil
        authenticatedSessionFingerprint = nil
        pairing = nil
        status = nil
        activeJob = nil
        activeEntryID = nil
        activeOperationID = nil
        activity = .idle
        lastErrorMessage = nil
        if let identityError { throw identityError }
    }

    private func makeContext(selectedEntryID: UUID) throws -> SomaBridgeContext {
        guard let selected = store.entry(id: selectedEntryID) else {
            throw SomaBridgeError.missingEntry
        }
        let related = store.connections(for: selectedEntryID)
            .compactMap { connection in
                connection.otherEntryID(than: selectedEntryID).flatMap(store.entry(id:))
            }
        var seen = Set<UUID>()
        let candidates = ([selected] + related)
            .filter { seen.insert($0.id).inserted }
            .prefix(12)
        var entries: [SomaBridgeContextEntry] = []
        for entry in candidates {
            let metadata = store.metadata(for: entry.id)
            let candidate = SomaBridgeContextEntry(
                id: entry.id,
                day: entry.day,
                text: Self.boundedUTF8(
                    Self.sanitizedEntryText(entry.text),
                    maximumBytes: 12_000
                ),
                createdAt: Self.iso8601(entry.createdAt),
                updatedAt: Self.iso8601(entry.updatedAt),
                tags: Self.boundedFacets(metadata?.tags ?? []),
                people: Self.boundedFacets(metadata?.people ?? []),
                places: Self.boundedFacets(metadata?.places ?? []),
                organizations: Self.boundedFacets(metadata?.organizations ?? []),
                sourceFingerprint: metadata?.sourceFingerprint.flatMap(Self.validFingerprint)
            )
            let proposed = SomaBridgeContext(
                schemaVersion: 1,
                selectedEntryID: selectedEntryID,
                entries: entries + [candidate]
            )
            let encodedSize = (try? Self.encoder.encode(proposed).count) ?? Int.max
            if encodedSize > 88 * 1_024 {
                if entries.isEmpty { throw SomaBridgeError.contextTooLarge }
                break
            }
            entries.append(candidate)
        }
        let context = SomaBridgeContext(
            schemaVersion: 1,
            selectedEntryID: selectedEntryID,
            entries: entries
        )
        guard try Self.encoder.encode(context).count <= 88 * 1_024 else {
            throw SomaBridgeError.contextTooLarge
        }
        return context
    }

    private func rememberThread(_ threadID: String, entryID: UUID) throws {
        guard var pairing else { throw SomaBridgeError.notPaired }
        pairing.lastThreadID = threadID
        pairing.lastThreadEntryID = entryID
        try pairingStore.save(pairing)
        self.pairing = pairing
    }

    private func signedRequest<Response: Decodable, Body: Encodable>(
        method: String,
        path: String,
        body: Body?,
        expectedStatus: Int
    ) async throws -> Response {
        await requestGate.acquire()
        do {
            let response: Response = try await performSignedRequest(
                method: method,
                path: path,
                body: body,
                expectedStatus: expectedStatus
            )
            await requestGate.release()
            return response
        } catch {
            await requestGate.release()
            throw error
        }
    }

    private func performSignedRequest<Response: Decodable, Body: Encodable>(
        method: String,
        path: String,
        body: Body?,
        expectedStatus: Int
    ) async throws -> Response {
        guard var pairing else { throw SomaBridgeError.notPaired }
        guard pairing.sequence < 9_007_199_254_740_991 else {
            throw SomaBridgeError.invalidResponse
        }
        pairing.sequence += 1
        try pairingStore.save(pairing)
        self.pairing = pairing

        let bodyData = try body.map(Self.encoder.encode) ?? Data()
        let timestamp = Int64(Date().timeIntervalSince1970 * 1_000)
        let nonce = try Self.randomNonce()
        let canonical = [
            "SOMA-BRIDGE-REQUEST",
            String(pairing.protocolVersion),
            pairing.bridgeID.uuidString.uppercased(),
            pairing.certificateFingerprint,
            method,
            path,
            Self.sha256Hex(bodyData),
            pairing.deviceID.uuidString.uppercased(),
            String(pairing.sequence),
            String(timestamp),
            nonce,
        ].joined(separator: "\n")
        guard let canonicalData = canonical.data(using: .utf8) else {
            throw SomaBridgeError.invalidResponse
        }
        let signature = try identity.sign(canonicalData)

        let session = try session(for: pairing)
        var request = URLRequest(
            url: try Self.url(host: pairing.host, port: pairing.port, path: path)
        )
        request.httpMethod = method
        request.httpBody = bodyData.isEmpty ? nil : bodyData
        request.timeoutInterval = 35
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if !bodyData.isEmpty {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        request.setValue(pairing.deviceID.uuidString.uppercased(), forHTTPHeaderField: "X-Soma-Device")
        request.setValue(String(pairing.sequence), forHTTPHeaderField: "X-Soma-Sequence")
        request.setValue(String(timestamp), forHTTPHeaderField: "X-Soma-Timestamp")
        request.setValue(nonce, forHTTPHeaderField: "X-Soma-Nonce")
        request.setValue(signature.base64EncodedString(), forHTTPHeaderField: "X-Soma-Signature")

        let (data, response) = try await Self.boundedData(
            for: request,
            using: session,
            maximumBytes: 256 * 1_024,
            exactRetry: true
        )
        try Self.validate(response: response, data: data, expectedStatus: expectedStatus)
        return try Self.decoder.decode(Response.self, from: data)
    }

    private func signedRequest<Response: Decodable>(
        method: String,
        path: String,
        expectedStatus: Int
    ) async throws -> Response {
        try await signedRequest(
            method: method,
            path: path,
            body: Optional<SomaBridgeEmptyBody>.none,
            expectedStatus: expectedStatus
        )
    }

    private func session(for pairing: SomaBridgePairing) throws -> URLSession {
        if
            let authenticatedSession,
            authenticatedSessionFingerprint == pairing.certificateFingerprint
        {
            return authenticatedSession
        }
        authenticatedSession?.invalidateAndCancel()
        let session = try Self.session(fingerprint: pairing.certificateFingerprint)
        authenticatedSession = session
        authenticatedSessionFingerprint = pairing.certificateFingerprint
        return session
    }

    private static func session(fingerprint: String) throws -> URLSession {
        guard let fingerprintData = Data(hex: fingerprint), fingerprintData.count == 32 else {
            throw SomaBridgeError.invalidPairingCode
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.waitsForConnectivity = false
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpMaximumConnectionsPerHost = 2
        configuration.tlsMinimumSupportedProtocolVersion = .TLSv13
        configuration.tlsMaximumSupportedProtocolVersion = .TLSv13
        return URLSession(
            configuration: configuration,
            delegate: SomaBridgePinnedTrustDelegate(expectedFingerprint: fingerprintData),
            delegateQueue: nil
        )
    }

    private static func url(host: String, port: Int, path: String) throws -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        components.port = port
        components.path = path
        guard let url = components.url else { throw SomaBridgeError.invalidPairingCode }
        return url
    }

    private static func boundedData(
        for request: URLRequest,
        using session: URLSession,
        maximumBytes: Int,
        exactRetry: Bool
    ) async throws -> (Data, URLResponse) {
        var attempt = 0
        while true {
            do {
                let (bytes, response) = try await session.bytes(for: request)
                var data = Data()
                data.reserveCapacity(min(maximumBytes, 32 * 1_024))
                for try await byte in bytes {
                    guard data.count < maximumBytes else {
                        throw SomaBridgeError.responseTooLarge
                    }
                    data.append(byte)
                }
                return (data, response)
            } catch let error as URLError
                where exactRetry && attempt == 0 && shouldExactRetry(error)
            {
                attempt += 1
                try await Task.sleep(for: .milliseconds(150))
                try Task.checkCancellation()
            }
        }
    }

    private static func shouldExactRetry(_ error: URLError) -> Bool {
        switch error.code {
        case .timedOut,
             .cannotFindHost,
             .cannotConnectToHost,
             .networkConnectionLost,
             .dnsLookupFailed,
             .notConnectedToInternet,
             .resourceUnavailable:
            true
        default:
            false
        }
    }

    private static func validate(
        response: URLResponse,
        data: Data,
        expectedStatus: Int
    ) throws {
        guard let response = response as? HTTPURLResponse else {
            throw SomaBridgeError.invalidResponse
        }
        guard response.statusCode == expectedStatus else {
            if let envelope = try? decoder.decode(SomaBridgeErrorEnvelope.self, from: data) {
                throw SomaBridgeError.server(
                    code: envelope.error.code,
                    message: envelope.error.message
                )
            }
            throw SomaBridgeError.http(response.statusCode)
        }
    }

    private static func randomNonce() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 24)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw SomaBridgeError.randomBytesUnavailable
        }
        return Data(bytes).base64URLEncodedString()
    }

    private static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func boundedUTF8(_ text: String, maximumBytes: Int) -> String {
        let bytes = Array(text.utf8)
        guard bytes.count > maximumBytes else { return text }
        var end = maximumBytes
        while end > 0 {
            if let result = String(bytes: bytes[..<end], encoding: .utf8) {
                return result
            }
            end -= 1
        }
        return ""
    }

    private static func boundedFacets(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.compactMap { value in
            let sanitized = String(
                value.unicodeScalars.filter { $0.value >= 0x20 && $0.value != 0x7f }
            )
            let bounded = boundedUTF8(
                sanitized.trimmingCharacters(in: .whitespacesAndNewlines),
                maximumBytes: 128
            )
            guard !bounded.isEmpty, seen.insert(bounded).inserted else { return nil }
            return bounded
        }
        .prefix(32)
        .map(\.self)
    }

    private static func sanitizedEntryText(_ text: String) -> String {
        String(
            text.unicodeScalars.filter { scalar in
                let value = scalar.value
                return value == 0x09
                    || value == 0x0a
                    || value == 0x0d
                    || (value >= 0x20 && value != 0x7f)
            }
        )
    }

    private static func validFingerprint(_ value: String) -> String? {
        value.range(of: #"^[0-9a-f]{64}$"#, options: .regularExpression) == nil
            ? nil
            : value
    }

    private static func iso8601(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return encoder
    }()

    private static let decoder = JSONDecoder()
}

private struct SomaBridgePairRequest: Encodable {
    let protocolVersion: Int
    let deviceID: UUID
    let deviceName: String
    let platform: String
    let publicKey: String
    let requestedCapabilities: [SomaBridgeCapability]
}

private struct SomaBridgePairResponse: Decodable {
    let protocolVersion: Int
    let bridgeID: UUID
    let bridgeName: String
    let deviceID: UUID
    let platform: String
    let capabilities: [SomaBridgeCapability]
    let certificateFingerprint: String
    let pairedAt: String
}

private struct SomaBridgeTurnRequest: Encodable {
    let threadID: String?
    let question: String
    let context: SomaBridgeContext
}

private struct SomaBridgeContext: Encodable {
    let schemaVersion: Int
    let selectedEntryID: UUID
    let entries: [SomaBridgeContextEntry]
}

private struct SomaBridgeContextEntry: Encodable {
    let id: UUID
    let day: String
    let text: String
    let createdAt: String
    let updatedAt: String
    let tags: [String]
    let people: [String]
    let places: [String]
    let organizations: [String]
    let sourceFingerprint: String?
}

private actor SomaBridgeRequestGate {
    private var isLocked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func acquire() async {
        if !isLocked {
            isLocked = true
            return
        }
        await withCheckedContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func release() {
        if waiters.isEmpty {
            isLocked = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}

private struct SomaBridgeEmptyBody: Encodable {}

private struct SomaBridgeRevocationResponse: Decodable {
    let revoked: Bool
}

private struct SomaBridgeErrorEnvelope: Decodable {
    struct Payload: Decodable {
        let code: String
        let message: String
    }

    let error: Payload
}

private final class SomaBridgeDeviceIdentity {
    private let applicationTag = Data("com.soma.native.codex-bridge.signing.v1".utf8)

    func publicKey() throws -> Data {
        let privateKey = try loadOrCreatePrivateKey()
        guard
            let publicKey = SecKeyCopyPublicKey(privateKey),
            let data = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
            data.count == 65,
            data.first == 0x04
        else {
            throw SomaBridgeError.keyUnavailable
        }
        return data
    }

    func sign(_ message: Data) throws -> Data {
        let privateKey = try loadPrivateKey()
        guard SecKeyIsAlgorithmSupported(
            privateKey,
            .sign,
            .ecdsaSignatureMessageX962SHA256
        ) else {
            throw SomaBridgeError.keyUnavailable
        }
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            .ecdsaSignatureMessageX962SHA256,
            message as CFData,
            &error
        ) as Data? else {
            if let error { throw error.takeRetainedValue() }
            throw SomaBridgeError.keyUnavailable
        }
        return signature
    }

    private func loadOrCreatePrivateKey() throws -> SecKey {
        do {
            return try loadPrivateKey()
        } catch SomaBridgeError.keyUnavailable {
            // Pairing is the only path allowed to create a missing identity.
        } catch {
            throw error
        }
        if let secureEnclaveKey = try? createSecureEnclaveKey() {
            return secureEnclaveKey
        }
        return try createSoftwareKey()
    }

    private func loadPrivateKey() throws -> SecKey {
        let query: [CFString: Any] = [
            kSecClass: kSecClassKey,
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag: applicationTag,
            kSecReturnRef: true,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let key = item as! SecKey? {
            return key
        }
        if status == errSecItemNotFound { throw SomaBridgeError.keyUnavailable }
        throw SomaBridgeError.keychain(status)
    }

    func rotate() throws {
        try deletePrivateKey()
    }

    func deletePrivateKey() throws {
        let query: [CFString: Any] = [
            kSecClass: kSecClassKey,
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag: applicationTag,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SomaBridgeError.keychain(status)
        }
    }

    private func createSecureEnclaveKey() throws -> SecKey {
        var accessError: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            .privateKeyUsage,
            &accessError
        ) else {
            if let accessError { throw accessError.takeRetainedValue() }
            throw SomaBridgeError.keyUnavailable
        }
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecAttrTokenID: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs: [
                kSecAttrIsPermanent: true,
                kSecAttrApplicationTag: applicationTag,
                kSecAttrAccessControl: access,
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            if let error { throw error.takeRetainedValue() }
            throw SomaBridgeError.keyUnavailable
        }
        return key
    }

    private func createSoftwareKey() throws -> SecKey {
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecPrivateKeyAttrs: [
                kSecAttrIsPermanent: true,
                kSecAttrApplicationTag: applicationTag,
                kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            if let error { throw error.takeRetainedValue() }
            throw SomaBridgeError.keyUnavailable
        }
        return key
    }
}

private final class SomaBridgePairingStore {
    private let service = "com.soma.native.codex-bridge.pairing.v1"
    private let account = "active"

    func load() throws -> SomaBridgePairing? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw SomaBridgeError.keychain(status)
        }
        return try JSONDecoder().decode(SomaBridgePairing.self, from: data)
    }

    func save(_ pairing: SomaBridgePairing) throws {
        let data = try JSONEncoder().encode(pairing)
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
        let changes: [CFString: Any] = [kSecValueData: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, changes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw SomaBridgeError.keychain(updateStatus)
        }
        var addition = query
        addition[kSecValueData] = data
        addition[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(addition as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw SomaBridgeError.keychain(addStatus)
        }
    }

    func delete() throws {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SomaBridgeError.keychain(status)
        }
    }
}

private final class SomaBridgePinnedTrustDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let expectedFingerprint: Data

    init(expectedFingerprint: Data) {
        self.expectedFingerprint = expectedFingerprint
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard
            challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
            let trust = challenge.protectionSpace.serverTrust,
            let certificates = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
            let certificate = certificates.first
        else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let certificateData = SecCertificateCopyData(certificate) as Data
        let actualFingerprint = Data(SHA256.hash(data: certificateData))
        guard actualFingerprint.constantTimeEquals(expectedFingerprint) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        // Never forward a one-use pairing secret, signed request, or selected
        // Soma context to a redirected origin. Every bridge route is direct.
        completionHandler(nil)
    }
}

private extension Data {
    init?(base64URLEncoded string: String) {
        var value = string.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        value += String(repeating: "=", count: (4 - value.count % 4) % 4)
        self.init(base64Encoded: value)
    }

    init?(hex: String) {
        guard hex.count.isMultiple(of: 2) else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self = Data(bytes)
    }

    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    func constantTimeEquals(_ other: Data) -> Bool {
        guard count == other.count else { return false }
        var difference: UInt8 = 0
        for (left, right) in zip(self, other) {
            difference |= left ^ right
        }
        return difference == 0
    }
}
