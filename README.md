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
```

### Windows版インストール（.exe）
- 1回目の実行は新規インストールです。
- インストール先フォルダを選択できます。
- インストール済み環境で再実行するとメンテナンスモードになり、修復または削除を選択できます。
- 初回起動時に Windows 設定テンプレートを `C:\ProgramData\Edamame\agent\agent-config.json` へ自動配置します。

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

## 設定リンク（予定）
- [設定ガイド（準備中）](#)
- [ModSecurity連携設定（準備中）](#)

設定サンプル:
- `config\agent-config.json`（Linux向け）
- `config\agent-config-windows.json`（Windows向け）

## コマンドリンク
- [コマンド一覧（準備中）](#コマンド一覧)





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
