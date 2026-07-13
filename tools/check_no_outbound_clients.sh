#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi

command -v rg >/dev/null 2>&1 || die "ripgrep (rg) is required"
[ -x ./gradlew ] || die "Gradle wrapper not found"

for configuration in browserReleaseRuntimeClasspath puristReleaseRuntimeClasspath cloudReleaseRuntimeClasspath; do
  dependencies="$(./gradlew -q :app:dependencies --configuration "$configuration")"
  forbidden_dependencies="$(printf '%s\n' "$dependencies" | rg -i \
    '(^|[:/ .+\\-])(okhttp3?|retrofit2?|volley|cronet|ktor-client|apache[^: ]*httpclient|httpclient5?|fuel|firebase|play-services|sentry|bugsnag|appcenter|amplitude|mixpanel|segment-analytics|datadog)([:/ .+\\-]|$)' || true)"
  if [ -n "$forbidden_dependencies" ]; then
    printf '%s\n' "$forbidden_dependencies" >&2
    die "forbidden client, Google service, analytics, or reporting dependency in $configuration"
  fi
  printf 'Runtime dependency graph clean: %s\n' "$configuration"
done

source_matches="$(rg -n -i \
  --glob '*.{kt,kts,java,c,cc,cpp,h,hpp}' \
  --glob '!**/build/**' \
  --glob '!whisper/src/main/cpp/vendor/**' \
  --glob '!tools/**' \
  '(java\.net\.URL|java\.net\.HttpURLConnection|java\.net\.http\.HttpClient|android\.net\.http|org\.chromium\.net|CronetEngine|okhttp3|retrofit2|io\.ktor\.client|org\.apache\.http(\.components)?\.|com\.android\.volley|URLSession|DatagramSocket|InetAddress\.getByName|(^|[^A-Za-z])Socket[[:space:]]*\(|curl_easy_|libcurl)' \
  app/src/main app/src/browser app/src/purist \
  core/src/main storage/src/main voice/src/main whisper/src/main lanserver/src/main || true)"

if [ -n "$source_matches" ]; then
  printf '%s\n' "$source_matches" >&2
  die "outbound client API reference found in offline first-party source"
fi

printf 'No outbound client API references found in browser/purist first-party source.\n'
cloud_connections="$(rg -l 'java\.net\.(URL|HttpURLConnection)' app/src/cloud --glob '*.{kt,java}' || true)"
[ "$cloud_connections" = "app/src/cloud/java/com/soma/app/CloudFeatureFactory.kt" ] || \
  die "experimental cloud connection code escaped its single reviewed boundary"
printf 'Experimental cloud connection code remains isolated to its reviewed source file.\n'
