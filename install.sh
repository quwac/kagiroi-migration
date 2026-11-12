#!/bin/sh
set -eu

fail() { printf 'kagiroi: %s\n' "$*" >&2; exit 1; }

version=${KAGIROI_VERSION:-}
install_dir=${KAGIROI_INSTALL_DIR:-"$HOME/.local/bin"}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --version|--install-dir)
      [ "$#" -ge 2 ] || fail "$1 requires a value"
      case "$1" in
        --version) version=$2 ;;
        --install-dir) install_dir=$2 ;;
      esac
      shift 2
      ;;
    --help)
      printf '%s\n' 'Usage: install.sh [--version <version>] [--install-dir <directory>]'
      exit 0
      ;;
    *) fail "Unknown option: $1" ;;
  esac
done

case "$(uname -s)" in
  Darwin) platform=darwin ;;
  Linux) platform=linux ;;
  *) fail 'Supported systems: macOS and Linux. Use install.ps1 for Windows.' ;;
esac
case "$(uname -m)" in
  arm64|aarch64) architecture=arm64 ;;
  x86_64|amd64) architecture=x64 ;;
  *) fail 'Supported architectures: arm64 and x64.' ;;
esac
command -v curl >/dev/null 2>&1 || fail 'curl is required.'
command -v tar >/dev/null 2>&1 || fail 'tar is required.'
if command -v sha256sum >/dev/null 2>&1; then
  checksum_tool=sha256sum
elif command -v shasum >/dev/null 2>&1; then
  checksum_tool=shasum
else
  fail 'sha256sum or shasum is required.'
fi

repository=https://github.com/quwac/kagiroi-migration
if [ -z "$version" ]; then
  release_url=$(curl -fsSL -o /dev/null -w '%{url_effective}' "$repository/releases/latest")
  case "$release_url" in
    "$repository"/releases/tag/v*) version=${release_url##*/v} ;;
    *) fail 'Could not determine the latest release.' ;;
  esac
fi
version=${version#v}
printf '%s\n' "$version" | LC_ALL=C grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$' || fail 'Invalid version.'
case "$install_dir" in
  /*) ;;
  *) fail 'The install directory must be an absolute path.' ;;
esac

temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
trap 'exit 1' HUP INT TERM
name="kagiroi-$version-$platform-$architecture"
archive="$name.tar.gz"
download="$repository/releases/download/v$version/$archive"
curl -fsSL "$download" -o "$temporary/$archive"
curl -fsSL "$download.sha256" -o "$temporary/checksum"
expected=$(awk -v name="$archive" '$2 == name { print $1 }' "$temporary/checksum")
printf '%s\n' "$expected" | LC_ALL=C grep -Eq '^[0-9a-fA-F]{64}$' || fail 'Invalid SHA-256 checksum file.'
if [ "$checksum_tool" = sha256sum ]; then
  actual=$(sha256sum "$temporary/$archive" | awk '{ print $1 }')
else
  actual=$(shasum -a 256 "$temporary/$archive" | awk '{ print $1 }')
fi
[ "$actual" = "$expected" ] || fail 'SHA-256 checksum mismatch; nothing was installed.'
tar -xzf "$temporary/$archive" -C "$temporary" "$name/kagiroi"
[ -f "$temporary/$name/kagiroi" ] && [ ! -L "$temporary/$name/kagiroi" ] || fail 'The archive has no CLI executable.'
mkdir -p "$install_dir"
staged=$(mktemp "$install_dir/.kagiroi-install-XXXXXXXX")
trap 'rm -rf "$temporary"; rm -f "$staged"' EXIT
cat "$temporary/$name/kagiroi" > "$staged"
chmod 755 "$staged"
mv -f "$staged" "$install_dir/kagiroi"
printf 'Installed kagiroi %s to %s/kagiroi\n' "$version" "$install_dir"
case ":${PATH:-}:" in
  *:"$install_dir":*) ;;
  *) printf 'Add this directory to PATH in your shell configuration: %s\n' "$install_dir" ;;
esac
