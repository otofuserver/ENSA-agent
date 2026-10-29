package com.edamame.agent.system;

import com.edamame.agent.config.AgentConfig;
import com.edamame.agent.network.LogTransmitter;
import com.edamame.agent.util.AgentLogger;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * iptables管理クラス
 * Linux iptablesを操作してIPアドレスのブロック・解除を行う
 * WindowsではWindows Firewallコマンドを使用
 *
 * @author Edamame Team
 * @version 1.1.0
 */
public class IptablesManager {

    private static final String EDAMAME_CHAIN = "EDAMAME_BLOCKS";
    private static final String IPTABLES_COMMAND = "iptables";
    private static final String IP6TABLES_COMMAND = "ip6tables";

    private final AgentConfig config;
    private final LogTransmitter logTransmitter;
    private final Map<String, BlockRule> activeBlocks;
    private final boolean isWindows;
    private final ObjectMapper objectMapper;

    /**
     * コンストラクタ
     *
     * @param config エージェント設定
     * @param logTransmitter 統一されたLogTransmitterインスタンス
     */
    public IptablesManager(AgentConfig config, LogTransmitter logTransmitter) {
        this.config = config;
        this.logTransmitter = logTransmitter;
        this.activeBlocks = new ConcurrentHashMap<>();
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        this.objectMapper = new ObjectMapper();

        if (config.isEnableIptables()) {
            initializeFirewallChain();
            restoreActiveBlocksFromFirewall();
        }

        AgentLogger.info("IptablesManagerを初期化しました (OS: " +
                    (isWindows ? "Windows" : "Linux") + ")");
    }

    /**
     * ファイアウォールチェーンを初期化
     */
    private void initializeFirewallChain() {
        try {
            if (isWindows) {
                // Windows Firewallの初期化は不要
                AgentLogger.info("Windows Firewallを準備しました");
            } else {
                initializeLinuxChain(IPTABLES_COMMAND, true);
                initializeLinuxChain(IP6TABLES_COMMAND, false);
            }
        } catch (Exception e) {
            AgentLogger.error("ファイアウォールチェーンの初期化に失敗しました: " + e.getMessage());
        }
    }

    /**
     * Linuxチェーンを初期化する。
     * @param commandName 実行コマンド名（iptables/ip6tables）
     * @param required 必須コマンドかどうか
     */
    private void initializeLinuxChain(String commandName, boolean required) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", commandName + " -t filter -N " + EDAMAME_CHAIN + " 2>/dev/null");
            Process process = pb.start();
            int exitCode = process.waitFor();
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder errorOutput = new StringBuilder();
            String errorLine;
            while ((errorLine = errorReader.readLine()) != null) {
                errorOutput.append(errorLine).append("\n");
            }

            String errorStr = errorOutput.toString().toLowerCase();

            if (exitCode == 0) {
                AgentLogger.debug("コマンド実行成功: sh -c " + commandName + " -t filter -N " + EDAMAME_CHAIN + " 2>/dev/null");
                AgentLogger.info(commandName + "チェーン " + EDAMAME_CHAIN + " を初期化しました");
            } else if (errorStr.contains("chain already exists") || (exitCode == 1 && errorStr.isBlank())) {
                AgentLogger.info(commandName + "チェーン " + EDAMAME_CHAIN + " は既に存在します。既存チェーンを利用します。");
            } else if (errorStr.contains("not found") || errorStr.contains("no such file")) {
                if (required) {
                    AgentLogger.error(commandName + " コマンドが見つかりません。インストール状態を確認してください。");
                } else {
                    AgentLogger.warn(commandName + " が見つからないため、IPv6ブロックは無効です。");
                }
                return;
            } else if (exitCode == 4 && errorStr.isBlank()) {
                AgentLogger.error(commandName + "コマンドが権限不足で失敗しました。root権限で実行してください: sh -c " + commandName + " -t filter -N " + EDAMAME_CHAIN + " 2>/dev/null");
            } else {
                AgentLogger.error(commandName + "チェーン " + EDAMAME_CHAIN + " の初期化に失敗しました。コマンド実行結果: exitCode=" + exitCode + ", error=" + errorOutput);
            }

            // チェーン末尾に-j RETURNがなければ追加
            ProcessBuilder listPb = new ProcessBuilder("sh", "-c", commandName + " -t filter -S " + EDAMAME_CHAIN);
            Process listProcess = listPb.start();
            BufferedReader listReader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String lastRule = null;
            String line;
            while ((line = listReader.readLine()) != null) {
                lastRule = line;
            }
            if (lastRule == null || !lastRule.trim().endsWith("-j RETURN")) {
                boolean added = executeCommand(new String[]{"sh", "-c", commandName + " -A " + EDAMAME_CHAIN + " -j RETURN"});
                if (added) {
                    AgentLogger.info(commandName + "チェーン " + EDAMAME_CHAIN + " の末尾に-j RETURNを追加しました");
                } else {
                    AgentLogger.warn(commandName + "チェーン " + EDAMAME_CHAIN + " の末尾に-j RETURNの追加に失敗しました");
                }
            } else {
                AgentLogger.debug(commandName + "チェーン " + EDAMAME_CHAIN + " の末尾には既に-j RETURNがあります");
            }
        } catch (Exception e) {
            if (required) {
                AgentLogger.error(commandName + " チェーン初期化中に例外が発生しました: " + e.getMessage());
            } else {
                AgentLogger.warn(commandName + " チェーン初期化をスキップしました: " + e.getMessage());
            }
        }
    }

    /**
     * 再起動時に既存のEDAMAME_BLOCKSルールを内部状態へ復元する。
     */
    private void restoreActiveBlocksFromFirewall() {
        if (isWindows || !config.isEnableIptables()) {
            return;
        }
        try {
            int restored = 0;
            List<FirewallRule> rules = new ArrayList<>();
            rules.addAll(listLinuxDropRules(IPTABLES_COMMAND));
            rules.addAll(listLinuxDropRules(IP6TABLES_COMMAND));
            for (FirewallRule rule : rules) {
                String normalizedSource = normalizeAddressForComparison(rule.source());
                if (normalizedSource == null || normalizedSource.isBlank()) {
                    continue;
                }
                if (activeBlocks.putIfAbsent(normalizedSource, new BlockRule(normalizedSource, null)) == null) {
                    restored++;
                }
            }
            if (restored > 0) {
                AgentLogger.info("既存firewallルールを内部状態へ復元しました: " + restored + " 件");
            }
        } catch (Exception e) {
            AgentLogger.warn("既存firewallルールの復元に失敗しました: " + e.getMessage());
        }
    }

    /**
     * ブロック要求を処理
     */
    public void processBlockRequests() {
        if (!config.isEnableIptables()) {
            return;
        }

        try {
            String blockRequestsJson = logTransmitter.fetchBlockRequests();

            // 空の応答やnullチェック
            if (blockRequestsJson == null || blockRequestsJson.trim().isEmpty()) {
                // データなしは正常状態
                return;
            }

            // 新仕様: {"requests":[...]} 形式のみ受理
            BlockRequestEnvelope envelope;
            try {
                envelope = objectMapper.readValue(blockRequestsJson, BlockRequestEnvelope.class);
            } catch (Exception e) {
                AgentLogger.warn("ブロック要求のJSON解析に失敗しました: " + e.getMessage());
                AgentLogger.debug("受信したJSON: " + blockRequestsJson);
                return;
            }

            List<BlockRequest> requests = envelope.getRequests();
            if (requests == null) {
                requests = new ArrayList<>();
            }

            if (!requests.isEmpty()) {
                AgentLogger.debug("ブロック要求を " + requests.size() + " 件処理します");
                List<Map<String, Object>> results = new ArrayList<>();
                for (BlockRequest request : requests) {
                    results.add(processBlockRequest(request));
                }
                if (!results.isEmpty()) {
                    logTransmitter.sendBlockResults(results);
                }
            }

        } catch (Exception e) {
            AgentLogger.warn("ブロック要求の処理中にエラーが発生しました: " + e.getMessage());
        } finally {
            reconcileFirewallState();
        }
    }

    /**
     * 個別のブロック要求を処理
     */
    private Map<String, Object> processBlockRequest(BlockRequest request) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("id", request.getId());
        try {
            int duration = request.getDurationMinutes();

            if ("block".equals(request.getAction())) {
                blockIpAddress(request.getIpAddress(), duration);
                result.put("success", true);
                result.put("message", "block applied");
            } else if ("unblock".equals(request.getAction())) {
                unblockIpAddress(request.getIpAddress());
                result.put("success", true);
                result.put("message", "unblock applied");
            } else {
                result.put("success", false);
                result.put("message", "unknown action: " + request.getAction());
            }

        } catch (Exception e) {
            AgentLogger.error("ブロック要求処理中にエラーが発生しました: " + e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * IPアドレスをブロック
     */
    private void blockIpAddress(String ipAddress, int durationMinutes) {
        try {
            if (activeBlocks.containsKey(ipAddress) && isFirewallRulePresent(ipAddress)) {
                AgentLogger.debug("IPアドレス " + ipAddress + " は既にブロック済みです");
                return;
            }

            boolean unlimited = durationMinutes <= 0;
            LocalDateTime expiryTime = unlimited ? null : LocalDateTime.now().plusMinutes(durationMinutes);
            String command = buildBlockCommand(ipAddress);

            if (executeCommand(command)) {
                activeBlocks.put(ipAddress, new BlockRule(ipAddress, expiryTime));
                if (unlimited) {
                    AgentLogger.info("IPアドレス " + ipAddress + " をブロックしました (期限: 無期限)");
                } else {
                    AgentLogger.info("IPアドレス " + ipAddress + " をブロックしました (期限: " +
                        expiryTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + ")");
                }
            }

        } catch (Exception e) {
            AgentLogger.error("IPアドレス " + ipAddress + " のブロックに失敗しました: " + e.getMessage());
        }
    }

    /**
     * エージェント内部状態とfirewall実状態の不整合を補正する。
     * - activeBlocksにないDROPルールは削除（手動追加対策）
     * - activeBlocksにあるがDROPルールがないIPは再追加（手動削除対策）
     */
    private void reconcileFirewallState() {
        if (isWindows || !config.isEnableIptables()) {
            return;
        }

        try {
            reconcileLinuxFirewallState();
        } catch (Exception e) {
            AgentLogger.warn("firewall整合性補正に失敗しました: " + e.getMessage());
        }
    }

    /**
     * Linux firewall実状態をactiveBlocksへ整合させる。
     */
    private void reconcileLinuxFirewallState() {
        Set<String> expectedIps = new HashSet<>();
        for (String ipAddress : activeBlocks.keySet()) {
            expectedIps.add(normalizeAddressForComparison(ipAddress));
        }

        List<FirewallRule> currentRules = new ArrayList<>();
        currentRules.addAll(listLinuxDropRules(IPTABLES_COMMAND));
        currentRules.addAll(listLinuxDropRules(IP6TABLES_COMMAND));

        for (FirewallRule rule : currentRules) {
            if (rule.normalizedSource() == null || rule.normalizedSource().isBlank()) {
                continue;
            }
            if (expectedIps.contains(rule.normalizedSource())) {
                continue;
            }

            String command = rule.commandName() + " -D " + EDAMAME_CHAIN + " -s " + rule.source() + " -j DROP";
            if (executeCommand(new String[]{"sh", "-c", command})) {
                AgentLogger.info("不整合ルールを削除しました: " + rule.commandName() + " " + rule.source());
            }
        }

        for (String ipAddress : activeBlocks.keySet()) {
            if (isFirewallRulePresent(ipAddress)) {
                continue;
            }

            String command = buildBlockCommand(ipAddress);
            if (executeCommand(command)) {
                AgentLogger.info("欠落していたfirewallルールを再適用しました: " + ipAddress);
            }
        }
    }

    /**
     * 指定IPのDROPルールがfirewall実状態に存在するか確認する。
     */
    private boolean isFirewallRulePresent(String ipAddress) {
        if (isWindows) {
            return activeBlocks.containsKey(ipAddress);
        }
        String commandName = resolveLinuxFirewallCommand(ipAddress);
        String[] command = new String[]{"sh", "-c", commandName + " -t filter -C " + EDAMAME_CHAIN + " -s " + ipAddress + " -j DROP"};
        return executeCheckCommand(command);
    }

    /**
     * Linuxチェーン上のDROPルールを列挙する。
     */
    private List<FirewallRule> listLinuxDropRules(String commandName) {
        List<FirewallRule> rules = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", commandName + " -t filter -S " + EDAMAME_CHAIN).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    FirewallRule rule = parseDropRule(commandName, line);
                    if (rule != null) {
                        rules.add(rule);
                    }
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                AgentLogger.debug("DROPルール列挙をスキップしました: " + commandName + " exitCode=" + exitCode);
            }
        } catch (Exception e) {
            AgentLogger.debug("DROPルール列挙に失敗しました: " + commandName + ", reason=" + e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return rules;
    }

    /**
     * `iptables -S` の1行から DROPルールを抽出する。
     */
    private FirewallRule parseDropRule(String commandName, String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("-A " + EDAMAME_CHAIN + " ") || !trimmed.contains(" -j DROP")) {
            return null;
        }

        String[] tokens = trimmed.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if ("-s".equals(tokens[i])) {
                String source = tokens[i + 1];
                return new FirewallRule(commandName, source, normalizeAddressForComparison(source));
            }
        }
        return null;
    }

    /**
     * 比較用にIP文字列を正規化する（/32, /128は除去）。
     */
    private String normalizeAddressForComparison(String ipAddress) {
        if (ipAddress == null) {
            return "";
        }
        String normalized = ipAddress.trim();
        if (normalized.endsWith("/32")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        if (normalized.endsWith("/128")) {
            return normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    /**
     * コマンドの成否のみ判定する存在確認用実行処理。
     */
    private boolean executeCheckCommand(String[] command) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            AgentLogger.debug("存在確認コマンド実行に失敗しました: " + String.join(" ", command) + " -> " + e.getMessage());
            return false;
        }
    }

    /**
     * IPアドレスのブロックを解除
     */
    private void unblockIpAddress(String ipAddress) {
        try {
            if (!activeBlocks.containsKey(ipAddress) && !isFirewallRulePresent(ipAddress)) {
                AgentLogger.debug("IPアドレス " + ipAddress + " はブロックされていません");
                return;
            }

            String command = buildUnblockCommand(ipAddress);

            if (executeCommand(command)) {
                activeBlocks.remove(ipAddress);
                AgentLogger.info("IPアドレス " + ipAddress + " のブロックを解除しました");
            }

        } catch (Exception e) {
            AgentLogger.error("IPアドレス " + ipAddress + " のブロック解除に失敗しました: " + e.getMessage());
        }
    }

    /**
     * ブロックコマンドを構築
     */
    private String buildBlockCommand(String ipAddress) {
        if (isWindows) {
            return "netsh advfirewall firewall add rule name=\"EDAMAME_BLOCK_" + ipAddress +
                   "\" dir=in action=block remoteip=" + ipAddress;
        } else {
            return resolveLinuxFirewallCommand(ipAddress) + " -I " + EDAMAME_CHAIN + " -s " + ipAddress + " -j DROP";
        }
    }

    /**
     * ブロック解除コマンドを構築
     */
    private String buildUnblockCommand(String ipAddress) {
        if (isWindows) {
            return "netsh advfirewall firewall delete rule name=\"EDAMAME_BLOCK_" + ipAddress + "\"";
        } else {
            return resolveLinuxFirewallCommand(ipAddress) + " -D " + EDAMAME_CHAIN + " -s " + ipAddress + " -j DROP";
        }
    }

    /**
     * Linux向けにIPバージョンに応じたファイアウォールコマンド名を返す。
     */
    private String resolveLinuxFirewallCommand(String ipAddress) {
        return isIpv6Address(ipAddress) ? IP6TABLES_COMMAND : IPTABLES_COMMAND;
    }

    /**
     * 入力IPがIPv6形式か判定する。
     */
    private boolean isIpv6Address(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }

        String candidate = ipAddress.trim();
        int slashIndex = candidate.indexOf('/');
        if (slashIndex > 0) {
            candidate = candidate.substring(0, slashIndex);
        }

        try {
            InetAddress inetAddress = InetAddress.getByName(candidate);
            return inetAddress instanceof Inet6Address;
        } catch (Exception ignored) {
            // 解決不能な入力でも「:」を含む場合はIPv6として扱う
            return candidate.contains(":");
        }
    }

    /**
     * コマンドを実行（配列あり）
     * 権限不足やコマンド失敗時は標準エラー出力・終了コードを詳細にログ出力し、root権限でない場合の警告も明示
     */
    private boolean executeCommand(String[] command) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                AgentLogger.debug("コマンド実行成功: " + String.join(" ", command));
                return true;
            } else {
                BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
                String errorLine;
                StringBuilder errorOutput = new StringBuilder();
                while ((errorLine = errorReader.readLine()) != null) {
                    errorOutput.append(errorLine).append("\n");
                }
                AgentLogger.warn("コマンド実行エラー: " + String.join(" ", command) + " -> " + errorOutput + " (終了コード: " + exitCode + ")");
                String lowerError = errorOutput.toString().toLowerCase();
                if (!isWindows && (lowerError.contains("permission denied") ||
                                   lowerError.contains("operation not permitted") ||
                                   lowerError.contains("must be run as root") ||
                                   lowerError.contains("you must be root") ||
                                   lowerError.contains("not permitted"))) {
                    AgentLogger.error("firewallコマンドが権限不足で失敗しました。root権限で実行してください: " + String.join(" ", command) + "\nエラー内容: " + errorOutput);
                } else if (!isWindows) {
                    AgentLogger.warn("firewallコマンドが失敗しました。root権限でない場合は動作しません: " + String.join(" ", command) + "\nエラー内容: " + errorOutput);
                }
                return false;
            }
        } catch (Exception e) {
            AgentLogger.error("コマンド実行に失敗しました: " + String.join(" ", command) + " -> " + e.getMessage());
            if (!isWindows) {
                AgentLogger.warn("firewallコマンド実行時に例外が発生しました。root権限でない場合は動作しません");
            }
            return false;
        }
    }

    /**
     * コマンドを実行（文字列1本）
     * 権限不足やコマンド失敗時は標準エラー出力・終了コードを詳細にログ出力し、root権限でない場合の警告も明示
     */
    private boolean executeCommand(String command) {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            if (isWindows) {
                builder.command("cmd.exe", "/c", command);
            } else {
                builder.command("sh", "-c", command);
            }
            Process process = builder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                AgentLogger.debug("コマンド実行成功: " + String.join(" ", command));
                return true;
            } else {
                BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
                String errorLine;
                StringBuilder errorOutput = new StringBuilder();
                while ((errorLine = errorReader.readLine()) != null) {
                    errorOutput.append(errorLine).append("\n");
                }
                String errorStr = errorOutput.toString().toLowerCase();
                AgentLogger.warn("コマンド実行エラー: " + command + " -> " + errorOutput + " (終了コード: " + exitCode + ")");
                // Linuxでiptables/ip6tables失敗時は権限不足の可能性を明示
                if (!isWindows && (errorStr.contains("permission denied") || errorStr.contains("operation not permitted") || errorStr.contains("must be run as root") || errorStr.contains("you must be root") || errorStr.contains("not permitted"))) {
                    AgentLogger.error("firewallコマンドが権限不足で失敗しました。root権限で実行してください: " + command + "\nエラー内容: " + errorOutput);
                } else if (!isWindows) {
                    AgentLogger.warn("firewallコマンドが失敗しました。root権限でない場合は動作しません: " + command + "\nエラー内容: " + errorOutput);
                }
                return false;
            }
        } catch (Exception e) {
            AgentLogger.error("コマンド実行に失敗しました: " + command + " -> " + e.getMessage());
            if (!isWindows) {
                AgentLogger.warn("firewallコマンド実行時に例外が発生しました。root権限でない場合は動作しません");
            }
            return false;
        }
    }

    /**
     * 期限切れのブロックを削除
     */
    public void cleanupExpiredBlocks() {
        LocalDateTime now = LocalDateTime.now();
        List<String> expiredIps = new ArrayList<>();

        for (Map.Entry<String, BlockRule> entry : activeBlocks.entrySet()) {
            LocalDateTime expiryTime = entry.getValue().getExpiryTime();
            if (expiryTime != null && expiryTime.isBefore(now)) {
                expiredIps.add(entry.getKey());
            }
        }

        for (String ip : expiredIps) {
            unblockIpAddress(ip);
        }
    }

    /**
     * ブロック要求データクラス
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlockRequest {
        private String id;
        private String ipAddress;
        private String action;
        private int durationMinutes;
        private String reason;

        // ...existing constructors and getters/setters...
        public BlockRequest() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public int getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /**
     * ブロック要求レスポンスのラッパークラス（新仕様専用）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlockRequestEnvelope {
        private List<BlockRequest> requests = new ArrayList<>();

        /**
         * Jackson等のJSONデシリアライズ用。未使用警告が出ても削除禁止。
         */
        public BlockRequestEnvelope() {}

        /**
         * ブロック要求一覧を取得する。
         * @return ブロック要求一覧
         */
        public List<BlockRequest> getRequests() { return requests; }

        /**
         * ブロック要求一覧を設定する。
         * Jackson等のJSONデシリアライズ用。未使用警告が出ても削除禁止。
         * @param requests ブロック要求一覧
         */
        public void setRequests(List<BlockRequest> requests) {
            this.requests = requests == null ? new ArrayList<>() : requests;
        }
    }

    /**
     * アクティブなブロックルールデータクラス
     */
    private static class BlockRule {
        private final String ipAddress;
        private final LocalDateTime expiryTime;

        public BlockRule(String ipAddress, LocalDateTime expiryTime) {
            this.ipAddress = ipAddress;
            this.expiryTime = expiryTime;
        }

        public LocalDateTime getExpiryTime() { return expiryTime; }
    }

    /**
     * firewall実状態の1ルールを表す。
     */
    private record FirewallRule(String commandName, String source, String normalizedSource) {}
}
