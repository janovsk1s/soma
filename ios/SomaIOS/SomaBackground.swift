import ImageIO
import SwiftUI
import UIKit

struct SomaForestBackground: View {
    @Environment(\.colorScheme) private var colorScheme
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                LinearGradient(
                    colors: colorScheme == .dark
                        ? [
                            Color(red: 0.055, green: 0.075, blue: 0.065),
                            Color(white: 0.025),
                        ]
                        : [
                            Color(red: 0.88, green: 0.90, blue: 0.88),
                            Color(white: 0.94),
                        ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )

                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.high)
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                        .grayscale(1)
                        .contrast(1.05)
                        .brightness(-0.18)
                        .opacity(colorScheme == .dark ? 1 : 0.58)
                        .transition(.opacity)
                }

                RadialGradient(
                    colors: [
                        .clear,
                        (colorScheme == .dark ? Color.black : Color.white)
                            .opacity(colorScheme == .dark ? 0.26 : 0.20),
                    ],
                    center: UnitPoint(x: 0.5, y: -0.1),
                    startRadius: min(proxy.size.width, proxy.size.height) * 0.30,
                    endRadius: max(proxy.size.width, proxy.size.height) * 0.88
                )
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .task {
            guard image == nil else { return }
            image = await SessionForestLoader.shared.image()
        }
        .animation(.easeOut(duration: 0.28), value: image != nil)
    }
}

struct SomaReadingSurface: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let veil = colorScheme == .dark ? Color.black : Color.white
        LinearGradient(
            stops: [
                .init(color: veil.opacity(colorScheme == .dark ? 0.52 : 0.48), location: 0),
                .init(color: veil.opacity(colorScheme == .dark ? 0.26 : 0.30), location: 0.28),
                .init(color: veil.opacity(colorScheme == .dark ? 0.26 : 0.30), location: 0.70),
                .init(color: veil.opacity(colorScheme == .dark ? 0.56 : 0.52), location: 1),
            ],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

struct SomaScreenBackground: View {
    var body: some View {
        ZStack {
            SomaForestBackground()
            SomaReadingSurface()
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

private enum SessionForest {
    private static let names = ["en", "lv", "et", "lt", "fi", "sv", "de", "sk"]
    static let name = names.randomElement() ?? "lv"

    /// Decode the selected WebP directly into its display buffer. SwiftUI performs
    /// the final scale on the GPU, avoiding a 3× CPU upscale and ~33 MB temporary
    /// bitmap during the first frame.
    static func decodeImage() -> UIImage? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "webp") else {
            return nil
        }
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, sourceOptions) else {
            return nil
        }
        let decodeOptions = [
            kCGImageSourceShouldCache: true,
            kCGImageSourceShouldCacheImmediately: true,
        ] as CFDictionary
        guard let image = CGImageSourceCreateImageAtIndex(source, 0, decodeOptions) else {
            return nil
        }
        return UIImage(cgImage: image)
    }
}

private actor SessionForestLoader {
    static let shared = SessionForestLoader()

    private var cachedImage: UIImage?
    private var loadTask: Task<UIImage?, Never>?

    func image() async -> UIImage? {
        if let cachedImage {
            return cachedImage
        }
        if let loadTask {
            return await loadTask.value
        }

        let task = Task.detached(priority: .userInitiated) {
            SessionForest.decodeImage()
        }
        loadTask = task
        let image = await task.value
        cachedImage = image
        loadTask = nil
        return image
    }
}
