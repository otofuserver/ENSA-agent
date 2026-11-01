# Edamame Agent

NGINXログを収集し、Edamameサーバーへ送信するセキュリティエージェントです。

## 機能
### 主要機能
- ログ送信: NGINXアクセス/エラーログを収集し、EdamameサーバーへTCP送信
- iptablesブロック: ブロック要求を受信し、iptables / ip6tables へ反映
- ModSecurity連携: ModSecurityの検知ログを収集し、関連リクエストをサーバー側分析に連携

## 動作環境（使用のみ）
- Java 17 / 21 / 25（17以上）
- Linux / Windows

## インストール方法（予定）
- [インストール手順（準備中）](#)

### Linux版インストール（.deb）
```bash
# 1) パッケージをインストール
sudo apt install ./EdamameAgent.deb

# 2) 登録されたサービスを確認（初期状態: 停止・無効）
sudo systemctl status edamame-agent

# 3) 設定ファイルを編集
sudo nano /etc/Edamame/agent/agent-config.json

# 4) CLIコマンド確認
ensaa --help
```

### Windows版インストール（.exe）
- 1回目の実行は新規インストールです。
- インストール先フォルダを選択できます。
- インストール済み環境で再実行するとメンテナンスモードになり、修復または削除を選択できます。
- 初回起動時に Windows 設定テンプレートを `C:\ProgramData\Edamame\agent\agent-config.json` へ自動配置します。
- 初回起動時に `ensaa` コマンドをセットアップし、PATHへ自動追加します（反映後は新しいターミナルで利用可能）。
- Windowsサービス化は `ensaa --service-install` で登録できます（NSSMが必要、初期状態は手動起動）。

### 起動方法（Linux / systemd）
```bash
# 起動（手動）
sudo systemctl start edamame-agent

# 停止（手動）
sudo systemctl stop edamame-agent

# 状態確認
sudo systemctl status edamame-agent

# 設定リロード（プロセス再起動なし）
sudo systemctl reload edamame-agent

# 自動起動を有効化する場合
sudo systemctl enable edamame-agent
```

初期状態は停止・無効です。

### 起動方法（Windows / サービス）
```powershell
# 1) サービス登録（初回のみ、管理者PowerShell）
ensaa --service-install --config "C:\ProgramData\Edamame\agent\agent-config.json"

# 2) サービス起動
ensaa --service-start

# 3) サービス状態確認
ensaa --service-status

# 4) 設定リロード（プロセス再起動なし）
ensaa --reload

# 5) サービス停止
ensaa --service-stop
```

## 設定リンク（予定）
- [設定ガイド（準備中）](#)
- [ModSecurity連携設定（準備中）](#)

設定サンプル:
- `config\agent-config.json`（Linux向け）
- `config\agent-config-windows.json`（Windows向け）

※ セキュリティのため、`edamame.apiKey` は必ず `CHANGE_ME` から実運用キーへ変更してから起動してください。

## コマンド一覧（共通）
```bash
# バージョン表示
ensaa --version

# ヘルプ表示
ensaa --help

# 設定ファイルを指定して通常起動
ensaa --config /etc/Edamame/agent/agent-config.json

# 設定書式チェック + エラー診断（統合コマンド）
ensaa -T --config /etc/Edamame/agent/agent-config.json

# 実行中エージェントへ設定リロード要求（Windows / Linux 共通）
ensaa --reload
```

## コマンド一覧（Windowsサービス）
```powershell
# Windowsサービス登録（NSSM必須）
ensaa --service-install --config "C:\ProgramData\Edamame\agent\agent-config.json"

# Windowsサービス起動
ensaa --service-start

# Windowsサービス停止
ensaa --service-stop

# Windowsサービス状態確認
ensaa --service-status

# Windowsサービス登録解除
ensaa --service-uninstall
```

## コマンド一覧（Linux systemd）
```bash
# サービス状態確認
sudo systemctl status edamame-agent

# サービス起動
sudo systemctl start edamame-agent

# 設定リロード（プロセス再起動なし）
sudo systemctl reload edamame-agent

# サービス停止
sudo systemctl stop edamame-agent
```

Windows でも同じく `ensaa -T` / `ensaa --reload` / `ensaa --help` を利用できます。

## 自分でbuildしたい人向けの情報

### 必要な環境
- JDK 17+（推奨: 17 / 21 / 25、`java -version` / `javac -version` で確認）
- Git
- インターネット接続（初回Gradle依存取得時）

### 環境構築（Windows）
```powershell
# 1) JDK 17+（推奨: 17 / 21 / 25）をインストール
# 2) Git for Windows をインストール
# 3) 確認
java -version
javac -version
git --version
```

### 環境構築（Linux）
```bash
# 1) JDK 17+ をインストール（例: Ubuntu）
sudo apt update
sudo apt install -y openjdk-17-jdk git
# 2) 確認
java -version
javac -version
git --version
```

### gitクローンからbuildまで（Windows）
```powershell
git clone <REPOSITORY_URL>
cd ENSA-agent
.\gradlew.bat clean build
```

### gitクローンからbuildまで（Linux）
```bash
git clone <REPOSITORY_URL>
cd ENSA-agent
./gradlew clean build
```

### ビルド成果物
- `build\libs\EdamameAgent.jar`（実行可能JAR）
