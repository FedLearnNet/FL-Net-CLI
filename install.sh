#!/bin/sh
# Installs the flnet CLI from GitHub Releases.
#
#   curl -fsSL https://raw.githubusercontent.com/FedLearnNet/FL-Net-CLI/main/install.sh | sh
#
# Environment variables:
#   FLNET_VERSION      release to install, e.g. v1.0.0 (default: latest)
#   FLNET_INSTALL_DIR  target directory (default: ~/.local/bin, /usr/local/bin as root)
#   FLNET_REPO         GitHub repository (default: FedLearnNet/FL-Net-CLI)
#   FLNET_DOWNLOAD_URL base URL of an internal mirror holding the release assets (overrides the above)
#
# Linux and macOS (x86_64, arm64) get a native binary without further dependencies. On other
# platforms use flnet.jar from the release page with Java 25+: java -jar flnet.jar
set -eu

# Everything runs inside main, so a partially downloaded script never executes.
main() {
    repo="${FLNET_REPO:-FedLearnNet/FL-Net-CLI}"
    version="${FLNET_VERSION:-latest}"
    if [ -n "${FLNET_INSTALL_DIR:-}" ]; then
        install_dir="$FLNET_INSTALL_DIR"
    elif [ "$(id -u)" -eq 0 ]; then
        install_dir="/usr/local/bin"
    else
        install_dir="$HOME/.local/bin"
    fi

    os="$(uname -s)"
    case "$os" in
        Linux) os="linux" ;;
        Darwin) os="darwin" ;;
        *) fail "No native flnet build for $os. Download flnet.jar from https://github.com/$repo/releases and run it with Java 25+." ;;
    esac
    arch="$(uname -m)"
    case "$arch" in
        x86_64 | amd64) arch="amd64" ;;
        arm64 | aarch64) arch="arm64" ;;
        *) fail "No native flnet build for $arch. Download flnet.jar from https://github.com/$repo/releases and run it with Java 25+." ;;
    esac

    asset="flnet-$os-$arch"
    if [ -n "${FLNET_DOWNLOAD_URL:-}" ]; then
        base_url="${FLNET_DOWNLOAD_URL%/}"
    elif [ "$version" = "latest" ]; then
        base_url="https://github.com/$repo/releases/latest/download"
    else
        base_url="https://github.com/$repo/releases/download/$version"
    fi

    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT INT TERM

    info "Downloading $asset from $base_url ..."
    download "$base_url/$asset" "$tmp/$asset"
    download "$base_url/SHA256SUMS" "$tmp/SHA256SUMS"

    expected="$(grep " $asset\$" "$tmp/SHA256SUMS" | awk '{print $1}')"
    [ -n "$expected" ] || fail "$asset is not listed in SHA256SUMS of release $version."
    actual="$(sha256 "$tmp/$asset")"
    [ "$expected" = "$actual" ] || fail "Checksum mismatch for $asset (expected $expected, got $actual). Aborting."

    mkdir -p "$install_dir"
    chmod 755 "$tmp/$asset"
    mv "$tmp/$asset" "$install_dir/flnet"
    info "Installed $("$install_dir/flnet" --version | head -n 1) to $install_dir/flnet"

    case ":$PATH:" in
        *":$install_dir:"*) ;;
        *)
            info ""
            info "$install_dir is not on your PATH. Add it, e.g.:"
            info "  echo 'export PATH=\"$install_dir:\$PATH\"' >> ~/.$(basename "${SHELL:-sh}")rc"
            ;;
    esac
    info ""
    info "Shell completion (bash/zsh):  source <(flnet generate-completion)"
    info "Get started:                  flnet doctor"
}

download() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 3 -o "$2" "$1" || fail "Download failed: $1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1" || fail "Download failed: $1"
    else
        fail "curl or wget is required."
    fi
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        fail "sha256sum or shasum is required to verify the download."
    fi
}

info() {
    printf '%s\n' "$*"
}

fail() {
    printf 'flnet install: %s\n' "$*" >&2
    exit 1
}

main "$@"
