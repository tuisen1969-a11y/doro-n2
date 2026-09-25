# Tello Controller

スマホからRyze Telloドローンを操作するAndroidアプリです。ボタン操作によるフライト、ライブ映像表示、スクリーンショット保存、MP4録画に対応しています。

## 対応機種

- 標準Tello(SDK 1.3 / 2.0)
- Tello EDU(SDK 2.0 / 3.0)
- RoboMaster TT(教育版)

いずれの機種も共通のSDKコマンドで動作します(`streamon` の応答差異は自動で吸収)。

## 機能

- 離陸 / 着陸 / 非常停止
- 前後左右・上下・左右回転(押している間だけ `rc` コマンドを送信する連続操縦)
- ライブ映像表示(H264 / UDP 11111 を MediaCodec でデコード)
- スクリーンショット(PNG → `Pictures/Tello`)
- 録画(MP4 → `Movies/Tello`)
- バッテリー・高度などのテレメトリ表示(UDP 8890)

## 使い方

1. スマホのWi-Fi設定で **TelloのSSIDに手動接続** してください(AndroidアプリからはWi-Fiを切り替えられないため手動接続が必須です)
2. 本アプリを起動し **[接続]** をタップ
3. ライブ映像が表示されたらボタンで操縦
4. 録画したMP4と撮影したPNGはギャラリーの `Movies/Tello`・`Pictures/Tello` に保存されます

> 非常停止(モーター即停止)は墜落の恐れがあるため、緊急時のみ使用してください。

## APKのビルド方法

### 方法1: GitHub Actions(最も簡単)

1. このリポジトリをGitHubにpush
2. GitHubの **Actions** タブ → **Build APK** ワークフローを開く
3. 実行が完了したらページ下部の **Artifacts** から `Tello-debug-apk` をダウンロード
4. APKをスマホに転送してインストール(「提供元不明のアプリ」の許可が必要)

pushするたびに自動でビルドされます。

### 方法2: Android Studio

1. プロジェクトをAndroid Studioで開く
2. Gradle同期後、実機をUSB接続(USBデバッグ有効)して **Run** 
3. または **Build > Build APK(s)** で `app/build/outputs/apk/debug/app-debug.apk` を生成

### 方法3: コマンドライン

JDK 17 と Android SDK が必要です。

```
gradle assembleDebug
```

## 技術構成

| 項目 | 内容 |
|---|---|
| 言語 / UI | Kotlin + Jetpack Compose (Material3) |
| AGP / Gradle | 8.7.3 / 8.9 |
| SDK | compileSdk 35 / targetSdk 35 / minSdk 29 |
| 通信 | UDP 8889(コマンド)/ UDP 8890(状態)/ UDP 11111(映像) |
| 映像 | H264 → MediaCodec → ImageReader → Bitmap 表示 |
| 録画 | MediaMuxer による MP4 出力 |

## トラブルシューティング

- **接続できない**: スマホがTelloのWi-Fiに接続されているか確認(インターネット警告は無視して接続維持)。Telloの電池残量を確認
- **映像が表示されない**: 接続後、数秒待つ(最初のキーフレームまで表示されません)。標準Telloは `streamon` 応答エラーを無視して動作します
- **録画したMP4が再生できない**: 録画開始前に一度ライブ映像が表示されている必要があります(SPS/PPS取得のため)

## 安全上の注意

- 屋外の風の強い日や人混みでは飛行させないでください
- 電池残量20%未満で自動着陸するため、余裕を持って着陸してください
