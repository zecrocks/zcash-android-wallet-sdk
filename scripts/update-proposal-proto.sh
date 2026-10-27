#!/usr/bin/env bash
#
# update-proposal-proto.sh: vendor backend-lib's proposal.proto from librustzcash.
#
# The canonical definition lives in zcash/librustzcash at
# zcash_client_backend/proto/proposal.proto. The Rust side of this SDK encodes
# proposals with whatever schema the pinned zcash_client_backend was built
# against, so the Kotlin copy has to match that exact version: any field the
# copy lacks is a field Kotlin cannot read.
#
# Unlike the lightwallet protos there is no git subtree to derive from, because
# the upstream version is chosen by a Cargo dependency rather than by a tag this
# repository pulls. The pinned version is therefore read out of
# backend-lib/Cargo.lock and the matching upstream file is fetched over HTTPS.
#
# The derivation adds one thing upstream does not carry: `option java_package`.
# Without it protoc would generate into `cash.z.wallet.sdk.ffi` (from the proto
# `package` declaration) rather than the `cash.z.wallet.sdk.internal.ffi` this
# SDK compiles against. This is the same single deviation that
# scripts/update-lightwallet-protocol.sh applies to the lightwallet protos.
#
# Usage:
#   ./scripts/update-proposal-proto.sh           # rewrite the vendored copy
#   ./scripts/update-proposal-proto.sh --check   # fail if it has drifted (CI)
#
# Both modes are idempotent and neither needs a clean working tree. --check
# writes nothing; it is what the Pull Request workflow runs.

set -euo pipefail

readonly UPSTREAM_REPO="zcash/librustzcash"
readonly UPSTREAM_PATH="zcash_client_backend/proto/proposal.proto"
readonly CRATE="zcash_client_backend"
readonly LOCK="backend-lib/Cargo.lock"
readonly DEST="backend-lib/src/main/proto/proposal.proto"
readonly JAVA_PACKAGE="cash.z.wallet.sdk.internal.ffi"
# crates.io rejects downloads without a User-Agent that identifies the client.
readonly USER_AGENT="zcash-android-wallet-sdk/update-proposal-proto.sh (https://github.com/zecrocks/zcash-android-wallet-sdk)"

REPO_ROOT="$(git rev-parse --show-toplevel)"
readonly REPO_ROOT
cd "${REPO_ROOT}"

usage() {
    sed -n '2,27p' "$0" | sed 's|^# \{0,1\}||'
    exit "${1:-1}"
}

# Prints "<version><TAB><source>" for CRATE's entry in Cargo.lock. Cargo.lock
# separates [[package]] blocks with a blank line, which is what delimits a
# record here.
read_lock_entry() {
    awk -v want="${CRATE}" '
        /^\[\[package\]\]$/ { name = ""; version = ""; source = ""; next }
        /^name = / { gsub(/^name = "|"$/, ""); name = $0; next }
        /^version = / { gsub(/^version = "|"$/, ""); version = $0; next }
        /^source = / { gsub(/^source = "|"$/, ""); source = $0; next }
        /^$/ { if (name == want) { print version "\t" source; exit } }
        END { if (name == want) print version "\t" source }
    ' "${LOCK}"
}

raw_url() {
    echo "https://raw.githubusercontent.com/${UPSTREAM_REPO}/$1/${UPSTREAM_PATH}"
}

# Succeeds if UPSTREAM_PATH can be fetched at the given ref.
ref_exists() {
    curl -fsI -o /dev/null "$(raw_url "$1")"
}

# Prints the librustzcash commit a published CRATE release was packaged from,
# as recorded by `cargo publish` in the crate's .cargo_vcs_info.json. Reads the
# local Cargo registry cache first and otherwise downloads the .crate from
# crates.io (which requires a User-Agent).
crate_vcs_sha() {
    local version="$1"
    local cargo_home="${CARGO_HOME:-${HOME}/.cargo}"
    local info="" candidate

    for candidate in "${cargo_home}"/registry/src/*/"${CRATE}-${version}"/.cargo_vcs_info.json; do
        if [ -f "${candidate}" ]; then
            info="$(cat "${candidate}")"
            break
        fi
    done

    if [ -z "${info}" ]; then
        local crate_file
        crate_file="$(mktemp)"
        # shellcheck disable=SC2064 # expand the path now, while it is still in scope
        trap "rm -f '${crate_file}'" RETURN
        if ! curl -fsSL -A "${USER_AGENT}" -o "${crate_file}" \
            "https://crates.io/api/v1/crates/${CRATE}/${version}/download"; then
            echo "error: could not download ${CRATE} ${version} from crates.io." >&2
            return 1
        fi
        info="$(tar -xzOf "${crate_file}" "${CRATE}-${version}/.cargo_vcs_info.json")" || {
            echo "error: ${CRATE} ${version} has no .cargo_vcs_info.json." >&2
            return 1
        }
    fi

    sed -n 's/.*"sha1"[[:space:]]*:[[:space:]]*"\([0-9a-f]\{40\}\)".*/\1/p' <<<"${info}" | head -n 1
}

# The git ref carrying the proto for the pinned dependency. A crates.io release
# is normally tagged `<crate>-<version>`, but upstream does not always push that
# tag; when it is missing, the commit recorded in the published crate's
# .cargo_vcs_info.json is used instead. A git dependency pins an exact revision,
# which Cargo.lock records as the fragment of the source URL. Resolving the ref
# from the lockfile rather than from Cargo.toml keeps this correct while the
# librustzcash git overrides at the bottom of backend-lib/Cargo.toml are in use.
upstream_ref() {
    local entry version source
    entry="$(read_lock_entry)"

    if [ -z "${entry}" ]; then
        echo "error: no ${CRATE} entry in ${LOCK}." >&2
        exit 1
    fi

    version="${entry%%$'\t'*}"
    source="${entry#*$'\t'}"

    case "${source}" in
        registry+*)
            local tag="${CRATE}-${version}" sha
            if ref_exists "${tag}"; then
                echo "${tag}"
            else
                sha="$(crate_vcs_sha "${version}")"
                if [ -z "${sha}" ]; then
                    echo "error: tag ${tag} is not pushed to ${UPSTREAM_REPO} and the" >&2
                    echo "       published crate records no commit to fall back to." >&2
                    exit 1
                fi
                echo "note: tag ${tag} is not pushed to ${UPSTREAM_REPO};" >&2
                echo "      using the commit the crate was published from (${sha})." >&2
                echo "${sha}"
            fi
            ;;
        git+*)
            if [ "${source}" = "${source#*#}" ]; then
                echo "error: git source for ${CRATE} pins no revision: ${source}" >&2
                exit 1
            fi
            echo "${source##*#}"
            ;;
        *)
            echo "error: unrecognized source for ${CRATE}: '${source}'." >&2
            exit 1
            ;;
    esac
}

# Writes the derived proto for the given upstream ref to stdout.
derive() {
    local ref="$1"
    local url
    url="$(raw_url "${ref}")"
    local upstream
    upstream="$(mktemp)"
    # shellcheck disable=SC2064 # expand the path now, while it is still in scope
    trap "rm -f '${upstream}'" RETURN

    if ! curl -fsSL "${url}" -o "${upstream}"; then
        echo "error: could not fetch ${url}" >&2
        echo "       Is ${ref} pushed to ${UPSTREAM_REPO}?" >&2
        exit 1
    fi

    if grep -q '^option java_package' "${upstream}"; then
        echo "error: upstream now sets java_package itself." >&2
        echo "       This script's injection is obsolete; vendor it verbatim." >&2
        exit 1
    fi

    if ! grep -q '^package ' "${upstream}"; then
        echo "error: upstream has no 'package' declaration to anchor the injection." >&2
        exit 1
    fi

    cat <<EOF
// DERIVED FILE -- DO NOT EDIT.
//
// Generated by scripts/update-proposal-proto.sh from
// ${UPSTREAM_REPO}/${UPSTREAM_PATH}
// at the ${CRATE} version pinned in ${LOCK}.
//
// The only change from upstream is the added \`option java_package\` below.
// Bump the dependency and re-run the script rather than editing this file.

EOF
    awk -v pkg="${JAVA_PACKAGE}" '
        { print }
        !injected && /^package / {
            print "option java_package = \"" pkg "\";"
            injected = 1
        }
    ' "${upstream}"
}

main() {
    local mode="${1:-update}"
    local ref
    ref="$(upstream_ref)"

    case "${mode}" in
        update)
            derive "${ref}" > "${DEST}"
            echo "Derived ${DEST} from ${UPSTREAM_REPO}@${ref}."
            echo
            echo "Review 'git diff' and rebuild:"
            echo "  ./gradlew :backend-lib:generateDebugProto"
            ;;
        check)
            local derived
            derived="$(mktemp)"
            # shellcheck disable=SC2064 # expand the path now, while it is in scope
            trap "rm -f '${derived}'" EXIT
            derive "${ref}" > "${derived}"

            if diff -u "${DEST}" "${derived}"; then
                echo "${DEST} matches ${UPSTREAM_REPO}@${ref}."
            else
                echo >&2
                echo "error: ${DEST} has drifted from ${UPSTREAM_REPO}@${ref}," >&2
                echo "       the ${CRATE} version pinned in ${LOCK}." >&2
                echo "       Run ./scripts/update-proposal-proto.sh and commit the result." >&2
                exit 1
            fi
            ;;
    esac
}

case "${1:-}" in
    -h|--help)
        usage 0
        ;;
    --check)
        main check
        ;;
    '')
        main update
        ;;
    *)
        echo "error: unknown option '$1'." >&2
        usage 1
        ;;
esac
