#!/bin/bash
builtin set -euo pipefail

# Capture the inherited execution environment with builtins before installing the validation environment.
if [[ -v PATH ]]; then
    apollo_original_path_present=1
    apollo_original_path=$PATH
else
    apollo_original_path_present=0
    apollo_original_path=
fi
if [[ -v LC_ALL ]]; then
    apollo_original_lc_all_present=1
    apollo_original_lc_all=$LC_ALL
else
    apollo_original_lc_all_present=0
    apollo_original_lc_all=
fi
builtin readonly apollo_original_path_present apollo_original_path
builtin readonly apollo_original_lc_all_present apollo_original_lc_all

# Only builtins run before inherited functions and startup-channel variables are removed.
builtin declare -a apollo_imported_functions
apollo_imported_functions=()
builtin mapfile -t apollo_imported_functions < <(builtin compgen -A function || builtin true)
for apollo_function_name in "${apollo_imported_functions[@]}"; do
    builtin unset -f -- "$apollo_function_name"
done
builtin unset -v apollo_imported_functions apollo_function_name
builtin unset BASH_ENV ENV 2>/dev/null || true
PATH=/usr/bin:/bin
LC_ALL=C
export PATH LC_ALL

readonly APOLLO_COMPANION_ROOT=/opt/apollo-native
readonly APOLLO_AGENT_FILE="$APOLLO_COMPANION_ROOT/apollo-native-agent.jar"
readonly APOLLO_FINGERPRINT_FILE="$APOLLO_COMPANION_ROOT/fingerprint.properties"
readonly APOLLO_NATIVE_LIBRARY_MANIFEST="$APOLLO_COMPANION_ROOT/native-libraries.sha256"

fail() {
    printf '%s\n' "apollo-native-deploy: $*" >&2
    exit 64
}

clear_injection() {
    unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS
    unset APOLLO_NATIVE_AGENT_PATH APOLLO_NATIVE_FINGERPRINT_PATH
    unset APOLLO_NATIVE_LIBRARY_MANIFEST_PATH
}

required() {
    [[ -n $2 ]] || fail "$1 is required"
}

is_sha256() {
    [[ $1 =~ ^[0-9a-f]{64}$ ]]
}

sha256_of() {
    sha256sum "$1" 2>/dev/null | awk '{ print $1 }'
}

validate_exact_executable() {
    local label=$1 path_value=$2 expected_path=$3 kind_value=$4 mode_value=$5 sha_value=$6 actual_mode
    [[ $path_value == "$expected_path" ]] || fail "$label path must be $expected_path"
    [[ ! -L $path_value ]] || fail "$label final symlink is forbidden"
    [[ $kind_value == file ]] || fail "$label kind must be file"
    [[ -f $path_value && -r $path_value && -x $path_value ]] || \
        fail "$label must be a readable executable regular file"
    [[ $mode_value =~ ^0[0-7]{3}$ ]] || fail "$label mode metadata is malformed"
    actual_mode=$(stat -c '%a' "$path_value") || fail "$label mode is unreadable"
    [[ 0$actual_mode == "$mode_value" ]] || fail "$label mode mismatch"
    is_sha256 "$sha_value" || fail "$label SHA-256 metadata is malformed"
    [[ $(sha256_of "$path_value") == "$sha_value" ]] || fail "$label SHA-256 mismatch"
}

validate_bootstrap_contract() {
    required APOLLO_BOOTSTRAP_ENV_PATH "${APOLLO_BOOTSTRAP_ENV_PATH:-}"
    required APOLLO_BOOTSTRAP_ENV_KIND "${APOLLO_BOOTSTRAP_ENV_KIND:-}"
    required APOLLO_BOOTSTRAP_ENV_MODE "${APOLLO_BOOTSTRAP_ENV_MODE:-}"
    required APOLLO_BOOTSTRAP_ENV_SHA256 "${APOLLO_BOOTSTRAP_ENV_SHA256:-}"
    required APOLLO_BOOTSTRAP_BASH_PATH "${APOLLO_BOOTSTRAP_BASH_PATH:-}"
    required APOLLO_BOOTSTRAP_BASH_KIND "${APOLLO_BOOTSTRAP_BASH_KIND:-}"
    required APOLLO_BOOTSTRAP_BASH_MODE "${APOLLO_BOOTSTRAP_BASH_MODE:-}"
    required APOLLO_BOOTSTRAP_BASH_SHA256 "${APOLLO_BOOTSTRAP_BASH_SHA256:-}"
    validate_exact_executable "bootstrap env" "$APOLLO_BOOTSTRAP_ENV_PATH" "/usr/bin/env" \
        "$APOLLO_BOOTSTRAP_ENV_KIND" "$APOLLO_BOOTSTRAP_ENV_MODE" "$APOLLO_BOOTSTRAP_ENV_SHA256"
    validate_exact_executable "bootstrap Bash" "$APOLLO_BOOTSTRAP_BASH_PATH" "/bin/bash" \
        "$APOLLO_BOOTSTRAP_BASH_KIND" "$APOLLO_BOOTSTRAP_BASH_MODE" "$APOLLO_BOOTSTRAP_BASH_SHA256"
}

validate_bootstrap_env_contract() {
    validate_exact_executable "bootstrap env" "$APOLLO_BOOTSTRAP_ENV_PATH" "/usr/bin/env" \
        "$APOLLO_BOOTSTRAP_ENV_KIND" "$APOLLO_BOOTSTRAP_ENV_MODE" "$APOLLO_BOOTSTRAP_ENV_SHA256"
}

safe_relative() {
    case "$2" in
        ""|/*|../*|*/../*|*/..|..) fail "$1 must be a non-traversing path relative to APOLLO_SERVER_ROOT" ;;
    esac
}

fingerprint_value() {
    awk -F= -v wanted="$1" '$1 == wanted { print substr($0, index($0, "=") + 1) }' \
        "$APOLLO_FINGERPRINT_FILE"
}

validate_fingerprint() {
    local first_bytes last_byte computed_identity declared_identity
    first_bytes=$(od -An -tx1 -N3 "$APOLLO_FINGERPRINT_FILE" | tr -d ' \n') || return 1
    [[ $first_bytes != efbbbf* ]] || return 1
    ! od -An -tx1 "$APOLLO_FINGERPRINT_FILE" | grep -Eq '(^|[[:space:]])00([[:space:]]|$)' || return 1
    ! grep -q $'\r' "$APOLLO_FINGERPRINT_FILE" || return 1
    last_byte=$(tail -c 1 "$APOLLO_FINGERPRINT_FILE" | od -An -tx1 | tr -d ' \n') || return 1
    [[ $last_byte == 0a ]] || return 1

    awk '
        function invalid() { exit 1 }
        function ishash(value) { return length(value) == 64 && value !~ /[^0-9a-f]/ }
        function validsuffix(value, position, character) {
            if (value == "") return 0;
            for (position=1; position<=length(value); position++) {
                character=substr(value,position,1);
                if (character == "%") {
                    if (substr(value,position,3) != "%23") return 0;
                    position += 2;
                } else if (character !~ /^[!-~]$/ || character == "#" || character == "=") {
                    return 0;
                }
            }
            return 1;
        }
        BEGIN {
            before[1]="appId"; before[2]="buildId"; before[3]="gameVersionRevision";
            before[4]="serverJarSha256"; before[5]="nativeLibrarySha256";
            before[6]="agentSha256";
            trailing[1]="workshopId"; trailing[2]="luaModId"; trailing[3]="bridgeProtocol";
            phase=1; position=0;
        }
        {
            if ($0 == "" || $0 ~ /^[#!]/) invalid();
            separator=index($0,"="); if (separator < 2) invalid();
            key=substr($0,1,separator-1); value=substr($0,separator+1);
            if (key !~ /^[!-~]+$/ || value !~ /^[!-~]+$/ || value ~ /=/ || seen[key]++) invalid();
            if (phase == 1) {
                position++; if (key != before[position]) invalid();
                if (position == 6) { phase=2; position=0; }
            } else if (phase == 2) {
                position++;
                if (position == 1) {
                    marker=index(value,"@sha256:");
                    if (key != "imageReference" || marker < 2 ||
                            length(value) != marker + 71 ||
                            !ishash(substr(value,marker + 8))) invalid();
                } else if (position == 2) {
                    if (key != "originalEntrypointCount" ||
                            value !~ /^([1-9]|[12][0-9]|3[0-2])$/) invalid();
                    entrypoint_count=value+0;
                    runtime[1]="imageReference"; runtime[2]="originalEntrypointCount";
                    runtime_position=2;
                    for (entrypoint_index=0; entrypoint_index<entrypoint_count; entrypoint_index++) {
                        runtime[++runtime_position]="originalEntrypoint." entrypoint_index ".path";
                        runtime[++runtime_position]="originalEntrypoint." entrypoint_index ".kind";
                        runtime[++runtime_position]="originalEntrypoint." entrypoint_index ".mode";
                        runtime[++runtime_position]="originalEntrypoint." entrypoint_index ".sha256";
                    }
                    runtime[++runtime_position]="imageCmd";
                    runtime[++runtime_position]="runtimeLockMode";
                    runtime[++runtime_position]="fingerprintSha256";
                    runtime[++runtime_position]="jvmFeature";
                    runtime[++runtime_position]="os";
                    runtime[++runtime_position]="arch";
                    runtime_count=runtime_position;
                } else {
                    if (key != runtime[position]) invalid();
                    field=(position-3)%4;
                    if (position <= 2 + entrypoint_count*4) {
                        if ((field == 0 && value !~ /^\//) ||
                                (field == 1 && value != "file") ||
                                (field == 2 && value !~ /^0[0-7][0-7][0-7]$/) ||
                                (field == 3 && !ishash(value))) invalid();
                    }
                    if (key == "imageCmd" && value != "null") invalid();
                    if (key == "runtimeLockMode" && value != "none-captured") invalid();
                }
                if (runtime_count > 0 && position == runtime_count) {
                    phase=3; previous="";
                }
            } else if (phase == 3 && key ~ /^classHashes\./) {
                if (key <= previous || !validsuffix(substr(key,13)) || !ishash(value)) invalid();
                previous=key; classes++;
            } else {
                if (phase == 3) { if (classes < 1) invalid(); phase=4; previous=""; }
                if (phase == 4 && key ~ /^methodDescriptors\./) {
                    if (key <= previous || !validsuffix(substr(key,19))) invalid();
                    previous=key; descriptors++;
                } else {
                    if (phase == 4) { if (descriptors < 1) invalid(); phase=5; position=0; }
                    position++; if (phase != 5 || key != trailing[position]) invalid();
                }
            }
            found[key]=value;
        }
        END {
            if (phase != 5 || position != 3) invalid();
            if (found["appId"] != "380870" || found["buildId"] != "24574884" ||
                found["gameVersionRevision"] != "42.20.2" || found["jvmFeature"] != "25" ||
                found["os"] != "linux" || found["arch"] != "amd64" ||
                found["workshopId"] != "3780069702" || found["luaModId"] != "ApolloMPSyncB42" ||
                found["bridgeProtocol"] != "1" || !ishash(found["serverJarSha256"]) ||
                !ishash(found["nativeLibrarySha256"]) || !ishash(found["agentSha256"]) ||
                !ishash(found["fingerprintSha256"])) invalid();
            production_adapter="methodDescriptors.RUNTIME_ADAPTER|PZ_42_20_2";
            if (found["appId"] == "380870" && found["buildId"] == "24574884" &&
                    found["gameVersionRevision"] == "42.20.2" &&
                    found[production_adapter] == "1") {
                if (found["serverJarSha256"] != "09a80a46e4febe9b436c0f4ec539bdfe9e9113b673eeaf8db22415ac34bef416" ||
                        found["nativeLibrarySha256"] != "86dcfd62671e7a8618c9bbba8433a82425b9c2e896a635c4f21aa70de17108ba" ||
                        found["imageReference"] != "ghcr.io/renegade-master/zomboid-dedicated-server@sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951" ||
                        found["originalEntrypointCount"] != "2" ||
                        found["originalEntrypoint.0.path"] != "/bin/bash" ||
                        found["originalEntrypoint.0.kind"] != "file" ||
                        found["originalEntrypoint.0.mode"] != "0755" ||
                        found["originalEntrypoint.0.sha256"] != "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58" ||
                        found["originalEntrypoint.1.path"] != "/home/steam/run_server.sh" ||
                        found["originalEntrypoint.1.kind"] != "file" ||
                        found["originalEntrypoint.1.mode"] != "0755" ||
                        found["originalEntrypoint.1.sha256"] != "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8" ||
                        found["imageCmd"] != "null" ||
                        found["runtimeLockMode"] != "none-captured") invalid();
            }
        }
    ' "$APOLLO_FINGERPRINT_FILE" || return 1

    computed_identity=$(sed '/^fingerprintSha256=/d' "$APOLLO_FINGERPRINT_FILE" | sha256sum | awk '{print $1}') || return 1
    declared_identity=$(fingerprint_value fingerprintSha256) || return 1
    [[ $computed_identity == "$declared_identity" ]] || return 1
    APOLLO_VERIFIED_FINGERPRINT_SHA256=$computed_identity
}

validate_fingerprint_runtime_tuple() {
    local index prefix
    [[ $(fingerprint_value originalEntrypointCount) == "$entrypoint_count" ]] || return 1
    for ((index=0; index<entrypoint_count; index++)); do
        prefix="originalEntrypoint.$index"
        [[ $(fingerprint_value "$prefix.path") == "${APOLLO_ORIGINAL_ENTRYPOINT[index]}" ]] || return 1
        [[ $(fingerprint_value "$prefix.kind") == "${apollo_entrypoint_kind[index]}" ]] || return 1
        [[ $(fingerprint_value "$prefix.mode") == "${apollo_entrypoint_mode[index]}" ]] || return 1
        [[ $(fingerprint_value "$prefix.sha256") == "${apollo_entrypoint_sha256[index]}" ]] || return 1
    done
    [[ $(fingerprint_value imageCmd) == null && $# -eq 0 ]] || return 1
    [[ $(fingerprint_value runtimeLockMode) == "$APOLLO_RUNTIME_LOCK_MODE" ]] || return 1
}

validate_native_manifest() {
    awk '
        function invalid() { exit 1 }
        function ishash(value) { return length(value) == 64 && value !~ /[^0-9a-f]/ }
        {
            digest=substr($0,1,64); marker=substr($0,65,2); file=substr($0,67);
            if (!ishash(digest) || (marker != "  " && marker != " *") || file == "" ||
                file ~ /^\// || file == ".." || file ~ /^\.\.\// || file ~ /\/\.\.\// || file ~ /\/\.\.$/) invalid();
            entries++;
        }
        END { if (entries < 1) invalid() }
    ' "$APOLLO_NATIVE_LIBRARY_MANIFEST"
}

validate_launcher_vector() {
    local index path_value kind_value mode_value sha_value actual_mode
    for ((index=0; index<entrypoint_count; index++)); do
        path_value=${APOLLO_ORIGINAL_ENTRYPOINT[index]}
        kind_value=${apollo_entrypoint_kind[index]}
        mode_value=${apollo_entrypoint_mode[index]}
        sha_value=${apollo_entrypoint_sha256[index]}
        [[ ! -L $path_value ]] || fail "ENTRYPOINT element $index final symlink is forbidden"
        [[ $kind_value == file ]] || fail "ENTRYPOINT element $index kind must be file"
        [[ -f $path_value && -r $path_value && -x $path_value ]] || \
            fail "ENTRYPOINT element $index must be a readable executable regular file"
        actual_mode=$(stat -c '%a' "$path_value") || fail "ENTRYPOINT element $index mode is unreadable"
        [[ 0$actual_mode == "$mode_value" ]] || fail "ENTRYPOINT element $index mode mismatch"
        [[ $(sha256_of "$path_value") == "$sha_value" ]] || fail "ENTRYPOINT element $index SHA-256 mismatch"
    done
}

builtin declare -a apollo_raw_function_environment_names
apollo_raw_function_environment_names=()

collect_raw_function_environment_names() {
    local entry raw_name
    local -a raw_entries=()
    builtin mapfile -d '' -t raw_entries < <("$APOLLO_BOOTSTRAP_ENV_PATH" -0)
    for entry in "${raw_entries[@]}"; do
        [[ $entry == *=* ]] || fail "raw environment entry cannot be safely represented"
        raw_name=${entry%%=*}
        [[ -n $raw_name ]] || fail "raw environment name cannot be safely represented"
        case "$raw_name" in
            BASH_FUNC_*%%)
                (( ${#raw_name} <= 1024 )) || \
                    fail "raw function environment name exceeds 1024 bytes"
                (( ${#apollo_raw_function_environment_names[@]} < 128 )) || \
                    fail "raw function environment count exceeds 128"
                apollo_raw_function_environment_names+=("$raw_name")
                ;;
        esac
    done
    builtin readonly -a apollo_raw_function_environment_names
}

restore_original_execution_environment() {
    if (( apollo_original_path_present )); then
        PATH=$apollo_original_path
        export PATH
    else
        unset PATH
    fi
    if (( apollo_original_lc_all_present )); then
        LC_ALL=$apollo_original_lc_all
        export LC_ALL
    else
        unset LC_ALL
    fi
}

exec_original_after_final_validation() {
    local raw_name
    local -a scrub_argv=()
    for raw_name in "${apollo_raw_function_environment_names[@]}"; do
        scrub_argv+=(-u "$raw_name")
    done
    validate_launcher_vector
    validate_bootstrap_env_contract
    restore_original_execution_environment
    exec "$APOLLO_BOOTSTRAP_ENV_PATH" "${scrub_argv[@]}" -- \
        "${APOLLO_ORIGINAL_ENTRYPOINT[@]}" "$@"
}

run_bounded_test_hook() {
    if [[ $APOLLO_COMPANION_ROOT != /opt/apollo-native \
            && -x $APOLLO_COMPANION_ROOT/test-before-exec-hook ]]; then
        "$APOLLO_COMPANION_ROOT/test-before-exec-hook"
    fi
}

exec_original() {
    clear_injection
    run_bounded_test_hook
    exec_original_after_final_validation "$@"
}

native_fallback() {
    local reason_code=$1
    shift
    clear_injection
    APOLLO_NATIVE_ASSIST=off
    export APOLLO_NATIVE_ASSIST
    printf '%s\n' "{\"component\":\"apollo-native-deploy\",\"state\":\"INCOMPATIBLE\",\"reasonCode\":\"$reason_code\",\"fallback\":\"vanilla\"}" >&2
    run_bounded_test_hook
    exec_original_after_final_validation "$@"
}

# Reserved Java values must reach the wrapper and are rejected before external commands.
for reserved in JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS \
        APOLLO_NATIVE_AGENT_PATH APOLLO_NATIVE_FINGERPRINT_PATH \
        APOLLO_NATIVE_LIBRARY_MANIFEST_PATH; do
    [[ ! -v $reserved ]] || fail "$reserved is reserved and must not be supplied externally"
done
validate_bootstrap_contract
collect_raw_function_environment_names

# Count-framed Bash argv: wrapper <count> <ENTRYPOINT...> <inherited CMD...>.
[[ $# -ge 1 ]] || fail "original ENTRYPOINT count is missing"
entrypoint_count=$1
shift
[[ $entrypoint_count =~ ^[0-9]+$ ]] || fail "original ENTRYPOINT count is malformed"
(( entrypoint_count >= 1 && entrypoint_count <= 32 )) || fail "original ENTRYPOINT count must be between 1 and 32"
readonly entrypoint_count
required APOLLO_ORIGINAL_ENTRYPOINT_COUNT "${APOLLO_ORIGINAL_ENTRYPOINT_COUNT:-}"
[[ $APOLLO_ORIGINAL_ENTRYPOINT_COUNT == "$entrypoint_count" ]] || fail "original ENTRYPOINT count metadata mismatch"
(( $# >= entrypoint_count )) || fail "original ENTRYPOINT vector is shorter than its count"

declare -a APOLLO_ORIGINAL_ENTRYPOINT=()
declare -a apollo_entrypoint_kind=()
declare -a apollo_entrypoint_mode=()
declare -a apollo_entrypoint_sha256=()
for ((index=0; index<entrypoint_count; index++)); do
    prefix="APOLLO_ORIGINAL_ENTRYPOINT_${index}_"
    path_name="${prefix}PATH"; kind_name="${prefix}KIND"; mode_name="${prefix}MODE"; sha_name="${prefix}SHA256"
    for name in "$path_name" "$kind_name" "$mode_name" "$sha_name"; do
        [[ -v $name ]] || fail "$name is required"
    done
    path_value=${!path_name}; kind_value=${!kind_name}; mode_value=${!mode_name}; sha_value=${!sha_name}
    argv_value=$1; shift
    [[ $argv_value == "$path_value" ]] || fail "ENTRYPOINT element $index does not match indexed path metadata"
    [[ $path_value != *$'\n'* && $path_value != *$'\r'* ]] || fail "ENTRYPOINT element $index path must be one line"
    [[ $path_value == /* ]] || fail "ENTRYPOINT element $index path must be absolute"
    case "$path_value" in "$APOLLO_COMPANION_ROOT"|"$APOLLO_COMPANION_ROOT"/*) fail "ENTRYPOINT recursion into companion is forbidden" ;; esac
    [[ $kind_value == file ]] || fail "ENTRYPOINT element $index kind must be file"
    [[ $mode_value =~ ^0[0-7]{3}$ ]] || fail "ENTRYPOINT element $index mode metadata is malformed"
    is_sha256 "$sha_value" || fail "ENTRYPOINT element $index SHA-256 metadata is malformed"
    APOLLO_ORIGINAL_ENTRYPOINT+=("$path_value")
    apollo_entrypoint_kind+=("$kind_value")
    apollo_entrypoint_mode+=("$mode_value")
    apollo_entrypoint_sha256+=("$sha_value")
done
readonly -a APOLLO_ORIGINAL_ENTRYPOINT apollo_entrypoint_kind
readonly -a apollo_entrypoint_mode apollo_entrypoint_sha256

while IFS= read -r metadata_name; do
    [[ $metadata_name == APOLLO_ORIGINAL_ENTRYPOINT_COUNT ]] && continue
    if [[ $metadata_name =~ ^APOLLO_ORIGINAL_ENTRYPOINT_([0-9]+)_(PATH|KIND|MODE|SHA256)$ ]] \
            && (( 10#${BASH_REMATCH[1]} < entrypoint_count )); then
        continue
    fi
    fail "extra original ENTRYPOINT metadata is forbidden: $metadata_name"
done < <(compgen -A variable APOLLO_ORIGINAL_ENTRYPOINT_)
validate_launcher_vector

required APOLLO_RUNTIME_LOCK_MODE "${APOLLO_RUNTIME_LOCK_MODE:-}"
[[ $APOLLO_RUNTIME_LOCK_MODE == none-captured ]] || fail "runtime lock mode must truthfully be none-captured"

case "${APOLLO_NATIVE_ASSIST:-off}" in
    off) exec_original "$@" ;;
    on) ;;
    *) native_fallback native-mode-invalid "$@" ;;
esac

required APOLLO_SERVER_ROOT "${APOLLO_SERVER_ROOT:-}"
required APOLLO_SERVER_JAR_RELATIVE "${APOLLO_SERVER_JAR_RELATIVE:-}"
safe_relative APOLLO_SERVER_JAR_RELATIVE "$APOLLO_SERVER_JAR_RELATIVE"
[[ $APOLLO_SERVER_ROOT == /* ]] || fail "APOLLO_SERVER_ROOT must be an absolute container path"
readonly APOLLO_SERVER_JAR="$APOLLO_SERVER_ROOT/$APOLLO_SERVER_JAR_RELATIVE"

[[ -n ${APOLLO_FINGERPRINT_FILE_SHA256:-} ]] || native_fallback fingerprint-file-sha256-missing "$@"
is_sha256 "$APOLLO_FINGERPRINT_FILE_SHA256" || native_fallback fingerprint-file-sha256-invalid "$@"
for required_file in "$APOLLO_AGENT_FILE" "$APOLLO_FINGERPRINT_FILE" \
        "$APOLLO_NATIVE_LIBRARY_MANIFEST" "$APOLLO_SERVER_JAR"; do
    [[ -f $required_file ]] || native_fallback native-artifact-missing "$@"
done

[[ $(sha256_of "$APOLLO_FINGERPRINT_FILE") == "$APOLLO_FINGERPRINT_FILE_SHA256" ]] || native_fallback fingerprint-file-sha256-mismatch "$@"
validate_fingerprint || native_fallback fingerprint-invalid "$@"
validate_fingerprint_runtime_tuple "$@" || native_fallback fingerprint-runtime-tuple-mismatch "$@"
[[ $(sha256_of "$APOLLO_AGENT_FILE") == "$(fingerprint_value agentSha256)" ]] || native_fallback agent-jar-sha256-mismatch "$@"
[[ $(sha256_of "$APOLLO_SERVER_JAR") == "$(fingerprint_value serverJarSha256)" ]] || native_fallback server-jar-sha256-mismatch "$@"
[[ $(sha256_of "$APOLLO_NATIVE_LIBRARY_MANIFEST") == "$(fingerprint_value nativeLibrarySha256)" ]] || native_fallback native-manifest-sha256-mismatch "$@"
validate_native_manifest || native_fallback native-manifest-invalid "$@"
(cd "$APOLLO_SERVER_ROOT" && sha256sum -c "$APOLLO_NATIVE_LIBRARY_MANIFEST" >/dev/null 2>&1) || native_fallback native-library-sha256-mismatch "$@"

JAVA_TOOL_OPTIONS="-javaagent:$APOLLO_AGENT_FILE=fingerprint=$APOLLO_FINGERPRINT_FILE -Dapollo.nativeAssist.enabled=true -Dapollo.nativeAssist.expectedFingerprint=$APOLLO_FINGERPRINT_FILE -Dapollo.nativeAssist.observedFingerprint=$APOLLO_FINGERPRINT_FILE -Dapollo.nativeAssist.expectedFingerprintSha256=$APOLLO_VERIFIED_FINGERPRINT_SHA256"
export JAVA_TOOL_OPTIONS
run_bounded_test_hook
exec_original_after_final_validation "$@"
