#!/bin/sh
# Installs fish tab-completion for the flnet CLI.
#
#   curl -fsSL https://raw.githubusercontent.com/FedLearnNet/FL-Net-CLI/main/install-fish-completion.sh | sh
#
# fish cannot load picocli's bash completion script directly, so this installs a small fish
# completion that runs picocli's bash function in a bash subprocess. The generated bash script
# is cached and refreshed automatically whenever the flnet binary changes.
set -eu

main() {
    command -v bash >/dev/null 2>&1 || fail "bash is required (picocli's completion script is a bash script)."

    config_home="${XDG_CONFIG_HOME:-$HOME/.config}"
    target_dir="$config_home/fish/completions"
    target="$target_dir/flnet.fish"

    mkdir -p "$target_dir"
    cat > "$target" <<'EOF'
# flnet tab completion for fish (wraps picocli's bash completion).
# Installed by flnet's install-fish-completion.sh

set -l __cache_home $HOME/.cache
set -q XDG_CACHE_HOME; and set __cache_home $XDG_CACHE_HOME
set -g __flnet_cache $__cache_home/flnet/completion.bash

# (Re)generate the cached bash script if it is missing or older than the flnet binary.
function __flnet_refresh_cache
    set -l bin (type -p flnet)
    or return 1
    if not test -s $__flnet_cache; or test $__flnet_cache -ot $bin
        mkdir -p (dirname $__flnet_cache)
        flnet generate-completion > $__flnet_cache.tmp
        and mv $__flnet_cache.tmp $__flnet_cache
        or rm -f $__flnet_cache.tmp
    end
    test -s $__flnet_cache
end

function __flnet_complete
    __flnet_refresh_cache; or return

    set -l words (commandline -opc)
    set -l cur (commandline -ct)
    set words $words "$cur"        # keep the (possibly empty) word being completed
    set -l cword (math (count $words) - 1)

    bash -c '
        source "$1"; shift
        COMP_CWORD=$1; shift
        COMP_WORDS=("$@")
        COMP_LINE="${COMP_WORDS[*]}"
        COMP_POINT=${#COMP_LINE}
        _complete_flnet
        printf "%s\n" "${COMPREPLY[@]}"
    ' _ $__flnet_cache $cword $words
end

complete -c flnet -f -a '(__flnet_complete)'
EOF

    info "Installed fish completion to $target"

    # Warm the cache so the first <TAB> is instant (skipped if flnet is not on PATH yet).
    cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/flnet"
    flnet_bin="$(command -v flnet 2>/dev/null || true)"
    [ -n "$flnet_bin" ] || [ ! -x "$HOME/.local/bin/flnet" ] || flnet_bin="$HOME/.local/bin/flnet"
    if [ -n "$flnet_bin" ]; then
        mkdir -p "$cache_dir"
        "$flnet_bin" generate-completion > "$cache_dir/completion.bash.tmp" \
            && mv "$cache_dir/completion.bash.tmp" "$cache_dir/completion.bash" \
            || rm -f "$cache_dir/completion.bash.tmp"
    fi

    info "Open a new fish session (or run: source $target) and try: flnet <TAB>"
}

info() {
    printf '%s\n' "$*"
}

fail() {
    printf 'flnet install-fish-completion: %s\n' "$*" >&2
    exit 1
}

main "$@"