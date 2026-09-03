# ClipForge

Android向けの **MP4 / MKV 無劣化カット・結合ツール**。SMB共有上の動画を選択し、作業用キャッシュへ安全に取得して処理後にSMBへ戻します。

## 現在の機能

- SMB 2.02〜3.11 接続
- SMBフォルダブラウズ
- MP4 / MKV の選択
- FFmpeg stream copy (`-c copy`) による無劣化結合
- FFmpeg stream copy による無劣化カット
- 結合前のストリーム互換性チェック
- SMBへの一時名アップロード → rename による安全な保存
- 処理完了後のローカル作業ファイル自動削除

## 無劣化カットの制約

再エンコードを一切行わないため、映像の開始位置はキーフレーム境界に制約されます。指定時刻にキーフレームがない場合、フレーム単位で完全一致するカットはできません。ClipForgeはこの場合も勝手に再エンコードしません。

## 結合の制約

映像・音声・字幕などのストリーム構成、コーデック、主要パラメータが一致しない動画は無劣化結合できません。ClipForgeは互換性がない場合に処理を停止し、品質を落とす自動再エンコードへフォールバックしません。

## 技術構成

- Kotlin / Jetpack Compose
- Android Gradle Plugin 9.4 / compileSdk 36 / targetSdk 36
- `dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.7`
- `eu.agno3.jcifs:jcifs-ng:2.1.10`
- minSdk 26

## 開発

JDK 17 と Android SDK 36 が必要です。

```bash
gradle :app:assembleDebug
```

CIでも同じビルドを実行します。
