package io.momu.frpmanager;

/** Wraps bundled Bash scripts so the remote account's login shell cannot reinterpret them. */
public final class RemoteScriptCommand {
    private static final String DELIMITER = "__FRP_TOOL_BASH_SCRIPT_EOF_8F2C4A__";

    private RemoteScriptCommand() {}

    public static String bash(String script) {
        if (script == null) throw new IllegalArgumentException("远端脚本不能为空");
        if (script.contains(DELIMITER)) throw new IllegalArgumentException("远端脚本包含保留分隔符");
        return "bash -s <<'" + DELIMITER + "'\n" + script
            + (script.endsWith("\n") ? "" : "\n") + DELIMITER;
    }
}
