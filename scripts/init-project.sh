#!/usr/bin/env bash
set -euo pipefail

read_init_value() {
    local label="$1"
    local default="${2:-}"
    local answer

    if [[ -n "$default" ]]; then
        read -r -p "$label [$default]: " answer
        if [[ -z "${answer// /}" ]]; then
            printf '%s' "$default"
            return
        fi
        printf '%s' "$(echo "$answer" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        return
    fi

    read -r -p "$label: " answer
    printf '%s' "$(echo "$answer" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
}

to_mod_id() {
    echo "$1" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]//g'
}

to_main_class_name() {
    local result
    result="$(echo "$1" | sed 's/[^a-zA-Z0-9]/ /g' | awk '{
        out = ""
        for (i = 1; i <= NF; i++) {
            word = tolower($i)
            out = out toupper(substr(word, 1, 1)) substr(word, 2)
        }
        print out
    }')"
    if [[ -z "$result" ]]; then
        printf '%s' "MyMod"
    else
        printf '%s' "$result"
    fi
}

to_default_mod_group() {
    local author="$1"
    local mod_id="$2"
    local author_part
    author_part="$(echo "$author" | sed 's/[^a-zA-Z0-9]//g' | tr '[:upper:]' '[:lower:]')"
    if [[ -z "$author_part" ]]; then
        author_part="example"
    fi
    printf 'com.%s.%s' "$author_part" "$mod_id"
}

has_init_properties=false
for argument in "$@"; do
    case "$argument" in
        -Pinit.*)
            has_init_properties=true
            ;;
    esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_dir/.." && pwd)"
gradle_args=(initProject --no-configuration-cache --no-daemon)

if [[ "$has_init_properties" == false ]]; then
    echo
    echo "Configure your mod from the template."
    echo

    mod_name="$(read_init_value "Mod name (human-readable)" "MyMod")"
    default_mod_id="$(to_mod_id "$mod_name")"
    if [[ -z "$default_mod_id" ]]; then
        default_mod_id="mymodid"
    fi
    mod_id="$(read_init_value "Mod ID (lowercase, e.g. mymod)" "$default_mod_id")"
    author="$(read_init_value "Author name" "Developer")"
    default_mod_group="$(to_default_mod_group "$author" "$mod_id")"
    mod_group="$(read_init_value "Root package (modGroup)" "$default_mod_group")"
    main_class="$(read_init_value "Main @Mod class name" "$(to_main_class_name "$mod_name")")"
    description="$(read_init_value "Short mod description" "A Minecraft 1.7.10 Forge mod.")"
    url="$(read_init_value "Project URL (optional)" "")"
    license_answer="$(read_init_value "Create LICENSE from LICENSE-template? (y/N)" "n")"
    write_license=false
    if [[ "$license_answer" =~ ^[Yy]([Ee][Ss])?$ ]]; then
        write_license=true
    fi
    server_only_answer="$(read_init_value "Server-only mod (clients without the mod can connect)? (y/N)" "n")"
    server_only_mod=false
    if [[ "$server_only_answer" =~ ^[Yy]([Ee][Ss])?$ ]]; then
        server_only_mod=true
    fi

    gradle_args+=("-Pinit.modName=$mod_name")
    gradle_args+=("-Pinit.modId=$mod_id")
    gradle_args+=("-Pinit.author=$author")
    gradle_args+=("-Pinit.modGroup=$mod_group")
    gradle_args+=("-Pinit.mainClass=$main_class")
    gradle_args+=("-Pinit.description=$description")
    gradle_args+=("-Pinit.url=$url")
    gradle_args+=("-Pinit.license=$write_license")
    gradle_args+=("-Pinit.serverOnly=$server_only_mod")

    if [[ "$write_license" == true ]]; then
        copyright="$(read_init_value "Copyright holder for LICENSE" "$author")"
        gradle_args+=("-Pinit.copyright=$copyright")
    fi
fi

exec "$project_root/gradlew" "${gradle_args[@]}" "$@"
