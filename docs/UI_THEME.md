# UI theme

ClipForge の UI トーンは XFiles を基準にする。

参照実装は XFiles の `app/src/main/java/app/local1st/files/ui/theme/Theme.kt` とし、ClipForge 独自のブランド色や薄紫系の固定パレットは持たない。

## Theme policy

- Android 12 (API 31) 以降で Dynamic Color が有効な場合:
  - light: `dynamicLightColorScheme()`
  - dark: `dynamicDarkColorScheme()`
- Dynamic Color を使用しない場合:
  - light: `expressiveLightColorScheme()`
  - dark: `darkColorScheme()`
- theme wrapper は `MaterialExpressiveTheme` を使用する。
- motion は `MotionScheme.expressive()` を使用する。
- edge-to-edge の status bar / navigation bar のアイコン明暗は、実際の light / dark theme に追従させる。
- API 26-28 の navigation bar scrim は XFiles と同じ light / dark 値を使う。

## Material3 / SDK baseline

XFiles は Expressive API を公開している `androidx.compose.material3:material3:1.5.0-alpha23` と Compose BOM `2026.06.01` を使用している。ClipForge も同じ UI stack に揃える。

Material3 `1.5.0-alpha23` の依存する Compose 1.12 系は compileSdk 37 以上を要求するため、ClipForge は `compileSdk = 37` / `targetSdk = 37` を使用する。

CI / signed-debug release の Android SDK セットアップも XFiles と同じく `android-actions/setup-android` で `platform-tools` を準備し、特定の旧 `sdkmanager` / command-line tools バージョンへ固定しない。必要な compile SDK は Gradle / Android build toolchain に解決させる。

SDK 36 を維持するために古い Material3 へ戻す互換レイヤーは設けない。

## Implementation rule

Theme policy は `ClipForgeTheme` に集約する。Activity や各画面で `MaterialTheme`、`lightColorScheme()`、`darkColorScheme()` を直接選択しない。

テーマ選択条件は純粋関数としてテスト可能にする。純粋関数は Android framework のクラスや定数を参照せず、SDKレベルを整数として受け取る。Android 12 の境界値は API 31 としてアプリ側の定数に保持する。

JVM unit test からテーマ選択関数を安全に呼べるよう、同じ Kotlin file の top-level 初期化でも `Color.argb()` などの Android framework API を実行しない。Android API を必要とする値は、実際の UI 実行時にのみ評価する。

次のケースをテストで固定する。

1. API 31+ / Dynamic Color 有効 / light -> Dynamic Light
2. API 31+ / Dynamic Color 有効 / dark -> Dynamic Dark
3. API 30 以下 / light -> Expressive Light
4. Dynamic Color 無効 / light -> Expressive Light
5. Dynamic Color 無効または API 30 以下 / dark -> Dark

## Scope

今回変更するのはアプリ全体の theme policy、必要な UI dependency / SDK baseline、system bar のトーンであり、画面構造、編集操作、レイアウト、データ処理は変更しない。
