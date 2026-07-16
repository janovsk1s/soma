import CoreImage
import SwiftUI
import UIKit

struct SomaForestBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color(white: colorScheme == .dark ? 0.04 : 0.91)

                if let image = SessionForest.image {
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
    private static let name = names.randomElement() ?? "lv"

    static let image: UIImage? = {
        guard
            let url = Bundle.main.url(forResource: name, withExtension: "webp"),
            let data = try? Data(contentsOf: url),
            let decoded = UIImage(data: data)
        else {
            return nil
        }
        return upscaled(decoded) ?? decoded
    }()

    /// The bundled forests are 1280×721, so filling a portrait phone stretches them
    /// ~3.5×, which the renderer's bilinear sampling smears. One Lanczos upscale plus
    /// a gentle unsharp mask at load keeps the fill crisp.
    private static func upscaled(_ source: UIImage) -> UIImage? {
        guard let input = CIImage(image: source) else { return nil }
        let scaled = input.applyingFilter(
            "CILanczosScaleTransform",
            parameters: [kCIInputScaleKey: 3.0, kCIInputAspectRatioKey: 1.0]
        )
        let sharpened = scaled.applyingFilter(
            "CIUnsharpMask",
            parameters: [kCIInputRadiusKey: 2.5, kCIInputIntensityKey: 0.55]
        )
        guard let cgImage = CIContext().createCGImage(sharpened, from: sharpened.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage, scale: source.scale, orientation: .up)
    }
}
