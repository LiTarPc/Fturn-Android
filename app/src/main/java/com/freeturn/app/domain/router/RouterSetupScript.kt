package com.freeturn.app.domain.router

internal object RouterSetupScript {
    fun install(files: Map<String, String>, key: String): String {
        require(key.matches(Regex("[0-9a-fA-F]{64}")))
        return buildString {
            appendLine("set -eu")
            appendLine("umask 077")
            appendLine("work=\$(mktemp -d /tmp/fturn-setup.XXXXXX)")
            appendLine("trap 'rm -rf \"\$work\"' EXIT")
            files.forEach { (name, text) ->
                require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
                val delimiter = "FTURN_ASSET_" + java.util.UUID.randomUUID().toString().replace("-", "")
                appendLine("cat > \"\$work/$name\" <<'$delimiter'")
                appendLine(text.replace("\r\n", "\n").trimEnd())
                appendLine(delimiter)
            }
            appendLine("printf '%s\\n' '$key' > \"\$work/key\"")
            appendLine("sh \"\$work/install-fturn-router.sh\" --key-file \"\$work/key\"")
        }
    }
}
