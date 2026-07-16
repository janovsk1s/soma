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
    var body: some View {
        Rectangle()
            .fill(.ultraThinMaterial)
            .opacity(0.52)
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
            let data = try? Data(contentsOf: url)
        else {
            return nil
        }
        return UIImage(data: data)
    }()
}
